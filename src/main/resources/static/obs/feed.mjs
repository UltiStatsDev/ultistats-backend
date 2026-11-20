export const defaults = Object.freeze({categories:['GOAL','BLOCK','INTERCEPTION','CALLAHAN'],maxRows:5,ttlSeconds:8,
    corner:'TOP_RIGHT',backgroundColor:'#17202e',textColor:'#ffffff',teamColors:{}});
export const category = type => ['BLOCK','BLOCK_MARKER','BLOCK_FIELD'].includes(type) ? 'BLOCK' :
    ['GOAL','INTERCEPTION','CALLAHAN'].includes(type) ? type : null;

export class Feed {
    constructor(config=defaults, clock=()=>Date.now(), options={}) {
        this.protected=options.protected===true; this.epoch=0; this.matchId=null; this.serverTime=null;
        this.config=config; this.clock=clock; this.baseline=null; this.seen=new Set(); this.visible=[]; this.revision=-1;
    }
    configure(config) { this.config=config; this.rows(); }
    eligible(event) { return this.config.categories.includes(category(event.type)); }
    rows() {
        if (this.protected) return this.protectedRows();
        const now=this.clock();
        this.visible=this.visible.filter(row=>now-row.shownAt<this.config.ttlSeconds*1000 && this.eligible(row.event))
            .slice(0,this.config.maxRows);
        return this.visible;
    }
    reconcile(snapshot) {
        if (this.protected) return this.reconcileProtected(snapshot);
        if (snapshot.logRevision<this.revision) return;
        this.revision=snapshot.logRevision;
        const current=new Map(snapshot.events.map(event=>[event.id,event]));
        this.rows();
        this.visible=this.visible.filter(row=>current.has(row.event.id)).map(row=>({...row,event:current.get(row.event.id)}));
        if (this.baseline!==null) {
            const added=snapshot.events.filter(event=>!this.seen.has(event.id) && this.eligible(event))
                .sort((a,b)=>a.sequenceNumber-b.sequenceNumber || a.id.localeCompare(b.id)).slice(-this.config.maxRows);
            for (const event of added) this.visible.unshift({event,shownAt:this.clock()});
        }
        for (const id of current.keys()) this.seen.add(id);
        this.baseline=current;
        this.rows();
    }
    serverNow() {
        return this.serverTime===null ? 0 : this.serverTime+Math.max(0,this.clock()-this.syncedAt);
    }
    protectedRows() {
        const now=this.serverNow(), groups=new Set();
        this.visible=this.visible.filter(row=>now<row.deadline && this.eligible(row.event)).filter(row=>{
            if (!groups.has(row.event.id) && groups.size>=this.config.maxRows) return false;
            groups.add(row.event.id); return true;
        });
        return this.visible;
    }
    reconcileProtected(snapshot) {
        if (snapshot.matchId!==this.matchId) {
            this.epoch++; this.matchId=snapshot.matchId; this.baseline=null; this.seen.clear();
            this.visible=[]; this.revision=-1; this.serverTime=null;
        }
        if (snapshot.logRevision<this.revision) return;
        const now=Math.max(this.serverNow(),Date.parse(snapshot.serverNow));
        this.serverTime=now; this.syncedAt=this.clock(); this.revision=snapshot.logRevision;
        const current=new Map(snapshot.events.map(event=>[event.id,event]));
        const retracted=new Map(snapshot.retractions.map(r=>[r.event.id,r]));
        this.protectedRows();
        this.visible=this.visible.filter(row=>row.kind==='cancellation' || current.has(row.event.id) || retracted.has(row.event.id))
            .map(row=>row.kind==='original' && current.has(row.event.id) ? {...row,event:current.get(row.event.id)} : row);
        const key=(event,kind)=>`${this.epoch}:${snapshot.matchId}:${event.id}:${kind}`;
        const add=(event,kind,deadline)=>{
            const identity=key(event,kind);
            if (this.seen.has(identity)) return;
            this.seen.add(identity);
            if (deadline<=now || !this.eligible(event)) return;
            const entry={event,kind,key:identity,shownAt:now,deadline};
            // A cancellation moves its logical event to the front but never replaces its original.
            const pair=this.visible.filter(row=>row.event.id===event.id);
            this.visible=[entry,...pair,...this.visible.filter(row=>row.event.id!==event.id)];
        };
        const ordered=events=>events.sort((a,b)=>a.sequenceNumber-b.sequenceNumber || a.id.localeCompare(b.id));
        for (const event of ordered([...current.values()])) {
            if (this.baseline===null) this.seen.add(key(event,'original'));
            else add(event,'original',now+this.config.ttlSeconds*1000);
        }
        for (const r of [...retracted.values()].sort((a,b)=>Date.parse(a.cancelledAt)-Date.parse(b.cancelledAt) || a.event.id.localeCompare(b.event.id))) {
            this.seen.add(key(r.event,'original'));
            add(r.event,'cancellation',Date.parse(r.cancelledAt)+this.config.ttlSeconds*1000);
        }
        this.baseline=true;
        this.protectedRows();
    }

}

export function resolveRow(event,match) {
    const kind=category(event.type); if (!kind) return null;
    const participants=new Map((match.teams??[]).flatMap(team=>(team.participants??[])
        .map(p=>[p.participantId,{...p,teamId:team.teamId}])));
    const player=id=>{
        const p=participants.get(id);
        if (!p || p.kind==='UNKNOWN') return {label:'Неизвестный игрок',teamId:p?.teamId};
        const name=[p.lastName?.trim(),p.firstName?.trim() ? `${Array.from(p.firstName.trim())[0]}.` : ''].filter(Boolean).join(' ');
        return {label:[p.number??'',name||'Игрок без имени'].filter(v=>v!=='').join(' '),teamId:p.teamId};
    };
    // Canonical from/to order is thrower/receiver or attacker/defending actor.
    return {id:event.id,type:event.type,category:kind,
        left:player(event.fromParticipantId),
        right:player(event.toParticipantId)};
}
