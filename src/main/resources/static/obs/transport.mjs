// SharedWorker owns the sole EventSource. Never silently fall back to one SSE
// per page: that would restore the HTTP/1.1 connection starvation bug.
export class SharedSource {
 constructor(matchId=null) {
  this.matchId=matchId;this.listeners=new Map();this.closed=false;this.retry=null;this.failures=0;
  this.lastMessage=Date.now();
  this.watchdog=setInterval(()=>{if(Date.now()-this.lastMessage>45000)this.recover();},15000);
  this.visible=()=>{if(globalThis.document?.visibilityState!=='hidden')this.subscribe();};
  globalThis.addEventListener?.('visibilitychange',this.visible);
  this.connect();
 }
 addEventListener(name,handler) {
  if(!this.listeners.has(name))this.listeners.set(name,new Set());
  this.listeners.get(name).add(handler);
 }
 subscribe(){try{this.port?.postMessage({type:'subscribe',matchId:this.matchId});}catch{this.recover();}}
 connect() {
  if(this.closed)return;
  try {
   const worker=new SharedWorker('/obs/shared-worker.js',{name:'ultistats-obs'});
   this.worker=worker;this.port=worker.port;this.lastMessage=Date.now();
   worker.onerror=()=>this.recover();
   this.port.onmessageerror=()=>this.recover();
   this.port.onmessage=event=>{
    if(this.closed)return;
    this.lastMessage=Date.now();
    const message=event.data;
    if(message?.type==='ping'){this.port.postMessage({type:'pong'});return;}
    if(message?.type==='expired'){this.recover();return;}
    if(message?.type==='error'){this.onerror?.();return;}
    if(message?.type==='event') {
     if(message.name==='connected')this.failures=0;
     for(const handler of this.listeners.get(message.name)??[])handler({data:JSON.stringify(message.data)});
    }
   };
   this.port.start();this.subscribe();
  }catch{this.recover();}
 }
 detach() {
  if(this.worker)this.worker.onerror=null;
  if(this.port){
   this.port.onmessage=null;this.port.onmessageerror=null;
   try{this.port.postMessage({type:'unsubscribe'});}catch{}
   this.port.close();this.port=null;
  }
  this.worker=null;
 }
 recover() {
  if(this.closed||this.retry!==null)return;
  this.detach();this.onerror?.();
  this.retry=setTimeout(()=>{this.retry=null;this.connect();},Math.min(1000*2**this.failures++,15000));
 }
 close() {
  if(this.closed)return;
  this.closed=true;clearTimeout(this.retry);clearInterval(this.watchdog);this.retry=null;this.detach();this.listeners.clear();
  globalThis.removeEventListener?.('visibilitychange',this.visible);
 }
}
