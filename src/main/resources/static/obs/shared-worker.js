// One HTTP/1.1 notification connection per origin/browser context, regardless
// of the number of OBS sources. Closed/frozen pages cannot retain ports forever.
const clients=new Map();
let source=null,timer=null;
const send=(port,message)=>{try{port.postMessage(message);}catch{remove(port);}};
function stopUnused() {
 if(![...clients.values()].some(client=>client.subscribed)){source?.close();source=null;}
 if(!clients.size){clearInterval(timer);timer=null;}
}
function remove(port) {clients.delete(port);port.close();stopUnused();}
function startSource() {
 if(source)return;
 source=new EventSource('/api/v1/obs/stream');
 for(const name of ['connected','config-changed','event-log-changed','match-finished','match-changed','scoreboard-profile-changed']) {
  source.addEventListener(name,event=>{
   let data;try{data=JSON.parse(event.data);}catch{return;}
   for(const [port,client] of clients) {
    if(!client.subscribed)continue;
    if(['event-log-changed','match-finished','match-changed','scoreboard-profile-changed'].includes(name)&&client.matchId!==data.matchId)continue;
    send(port,{type:'event',name,data});
   }
  });
 }
 source.onerror=()=>{for(const [port,client] of clients)if(client.subscribed)send(port,{type:'error'});};
}
self.onconnect=event=>{
 const port=event.ports[0],client={subscribed:false,matchId:null,lastSeen:Date.now()};
 clients.set(port,client);
 if(timer===null)timer=setInterval(()=>{
  for(const [port,client] of clients) {
   if(Date.now()-client.lastSeen>45000){send(port,{type:'expired'});remove(port);}
   else send(port,{type:'ping'});
  }
 },15000);
 port.onmessage=event=>{
  const message=event.data;
  if(message?.type==='unsubscribe'){remove(port);return;}
  if(!clients.has(port))return;
  client.lastSeen=Date.now();
  if(message?.type==='subscribe') {
   client.subscribed=true;client.matchId=message.matchId??null;
   const alreadyOpen=source?.readyState===1;
   startSource();
   if(alreadyOpen)send(port,{type:'event',name:'connected',data:{scope:'service'}});
  }
 };
 port.onmessageerror=()=>remove(port);
 port.start();
};
