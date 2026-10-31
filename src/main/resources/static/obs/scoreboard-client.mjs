import {SharedSource} from './transport.mjs';
import {Reconciler} from './client.mjs';
import {validateSnapshot} from './scoreboard-model.mjs';

// Stays subscribed after FINISHED: permitted result corrections must reach an idle overlay too.
export function watchScoreboard(matchId,onSnapshot,onStatus=()=>{},options={}) {
 const {Source=SharedSource,pollMs=1000,timeoutMs=1500,retryMs=1000}=options;
 let controller=null,closed=false;
 const fetchSnapshot=options.fetchSnapshot??(async()=>{
  controller=new AbortController();const active=controller;
  const timer=setTimeout(()=>active.abort(),timeoutMs);
  try {
   const response=await fetch(`/api/v1/obs/scoreboard/${encodeURIComponent(matchId)}`,{cache:'no-store',signal:active.signal});
   if(!response.ok)throw Error(`HTTP ${response.status}`);
   return validateSnapshot(await response.json(),matchId);
  } finally {clearTimeout(timer);if(controller===active)controller=null;}
 });
 const reader=new Reconciler(fetchSnapshot,onSnapshot,onStatus,retryMs),source=new Source(matchId);
 for(const name of ['connected','event-log-changed','match-finished','match-changed','scoreboard-profile-changed'])
  source.addEventListener(name,()=>void reader.request());
 source.onerror=()=>onStatus('Табло: переподключение…');
 // Also recovers missed process-local notifications, while serialized reads bound HTTP concurrency.
 const poll=setInterval(()=>void reader.request(),pollMs);
 void reader.request();
 return ()=>{if(closed)return;closed=true;clearInterval(poll);source.close();reader.close();controller?.abort();};
}
