export const defaults = Object.freeze({categories:['GOAL','BLOCK','INTERCEPTION','CALLAHAN'],maxRows:5,ttlSeconds:8,
    corner:'TOP_RIGHT',backgroundColor:'#17202e',textColor:'#ffffff',teamColors:{}});
export const category = type => ['BLOCK','BLOCK_MARKER','BLOCK_FIELD'].includes(type) ? 'BLOCK' :
    ['GOAL','INTERCEPTION','CALLAHAN'].includes(type) ? type : null;

export class Feed {
    constructor(config=defaults, clock=()=>Date.now()) {
        this.config=config; this.clock=clock; this.baseline=null; this.seen=new Set(); this.visible=[]; this.revision=-1;
    }
    configure(config) { this.config=config; this.rows(); }
    eligible(event) { return this.config.categories.includes(category(event.type)); }
    rows() {
        const now=this.clock();
        this.visible=this.visible.filter(row=>now-row.shownAt<this.config.ttlSeconds*1000 && this.eligible(row.event))
            .slice(0,this.config.maxRows);
        return this.visible;
    }
    reconcile(snapshot) {
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
    const defense=kind!=='GOAL';
    return {id:event.id,type:event.type,category:kind,
        left:player(defense?event.toParticipantId:event.fromParticipantId),
        right:player(defense?event.fromParticipantId:event.toParticipantId)};
}
