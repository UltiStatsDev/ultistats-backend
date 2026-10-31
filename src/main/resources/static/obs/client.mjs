import {SharedSource} from './transport.mjs';

export async function readJson(url) {
    const response=await fetch(url,{cache:'no-store'});
    if (!response.ok) throw new Error(`HTTP ${response.status}`);
    return response.json();
}

// All invalidations share one serialized reader. Failed REST reads retry even after SSE has closed on finish.
export class Reconciler {
    constructor(fetchSnapshot,onSnapshot,onStatus=()=>{},retryMs=1000) {
        Object.assign(this,{fetchSnapshot,onSnapshot,onStatus,retryMs});
        this.pending=false; this.closed=false; this.loop=null; this.timer=null; this.wake=null;
    }
    request(wakeRetry=false) {
        if(this.closed) return Promise.resolve();
        this.pending=true;
        if(wakeRetry){clearTimeout(this.timer);this.wake?.();}
        if(!this.loop) this.loop=this.run().finally(()=>{this.loop=null;});
        return this.loop;
    }
    finish() { return this.request(); }
    async run() {
        let failures=0;
        while(this.pending&&!this.closed) {
            this.pending=false;
            try {
                const snapshot=await this.fetchSnapshot();
                if(this.closed) return;
                this.onSnapshot(snapshot); this.onStatus('Синхронизировано'); failures=0;
            } catch(error) {
                if(this.closed) return;
                this.onStatus(`Нет связи: ${error.message}. Повтор…`); this.pending=true;
                await new Promise(resolve=>{
                    this.wake=resolve; this.timer=setTimeout(resolve,Math.min(this.retryMs*2**failures++,15000));
                });
                this.timer=null; this.wake=null;
            }
        }
    }
    close() { this.closed=true;clearTimeout(this.timer);this.wake?.(); }
}

export function watchConfig(onConfig,onStatus=()=>{}) {
    const reader=new Reconciler(()=>readJson('/api/v1/obs/config'),onConfig,onStatus);
    const source=new SharedSource();
    source.addEventListener('connected',()=>void reader.request());
    source.addEventListener('config-changed',()=>void reader.request());
    source.onerror=()=>onStatus('Настройки: переподключение…');
    return ()=>{source.close();reader.close();};
}

export function watchMatch(matchId,onSnapshot,onStatus=()=>{},hasVisibleRows=()=>false,initiallyFinished=false) {
    const reader=new Reconciler(()=>readJson(`/api/v2/matches/${encodeURIComponent(matchId)}/events`),onSnapshot,onStatus);
    const source=new SharedSource(matchId);
    let terminalTimer=null,disposed=false;
    const stopTerminal=()=>{clearInterval(terminalTimer);terminalTimer=null;reader.close();};
    source.addEventListener('connected',()=>{if(initiallyFinished)finish();else void reader.request();});
    source.addEventListener('event-log-changed',()=>void reader.request());
    const finish=()=>{
        source.close();
        // First obtain the final committed snapshot even if the feed is empty or
        // REST temporarily fails. Only then start the bounded correction window.
        void reader.finish().then(()=>{
            if(disposed)return;
            onStatus('Матч завершён');
            if(!hasVisibleRows()){stopTerminal();return;}
            const deadline=Date.now()+60000; // maximum configurable row TTL
            terminalTimer=setInterval(()=>{
                if(Date.now()>=deadline||!hasVisibleRows()){stopTerminal();return;}
                void reader.request();
            },1000);
        });
    };
    source.addEventListener('match-finished',finish);
    source.onerror=()=>onStatus('Матч: переподключение…');
    return ()=>{disposed=true;source.close();stopTerminal();};
}
