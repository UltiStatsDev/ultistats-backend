// Synthetic fault-only proxy. Successful backend responses are forwarded without fabrication.
const http=require('node:http');
function createProxy(backend='http://127.0.0.1:19856',port=19857){
 const target=new URL(backend),streams=new Set();let mode='healthy',hangNext=false,releaseHang;
 const state={attempts:[],active:0,peak:0,hungReads:0};
 const server=http.createServer((req,res)=>{
  if(req.url==='/_test/state'){res.setHeader('Content-Type','application/json');res.end(JSON.stringify({...state,mode}));return;}
  if(req.url==='/_test/fault'&&req.method==='PUT'){
   let body='';req.on('data',b=>body+=b);req.on('end',()=>{const value=JSON.parse(body);mode=value.mode??mode;hangNext=!!value.hangNext;
    if(value.drop)for(const stream of [...streams])stream.destroy();res.end('{}');});return;
  }
  const sse=req.url==='/api/v1/obs/stream';
  if(sse&&mode==='503'){state.attempts.push({at:Date.now(),status:503});res.writeHead(503,{'Content-Type':'text/plain'});res.end('Synthetic SSE unavailable');return;}
  const forward=()=>{
   if(res.destroyed)return;
   const upstream=http.request(new URL(req.url,target),{method:req.method,headers:{...req.headers,host:target.host}},response=>{
    if(sse){state.attempts.push({at:Date.now(),status:response.statusCode});if(response.statusCode===200){streams.add(res);state.active=streams.size;state.peak=Math.max(state.peak,state.active);}}
    res.writeHead(response.statusCode,response.headers);response.pipe(res);
    res.on('close',()=>{streams.delete(res);state.active=streams.size;response.destroy();upstream.destroy();});
   });upstream.on('error',()=>{if(!res.headersSent)res.writeHead(502);res.end();});req.pipe(upstream);
  };
  if(hangNext&&req.headers['x-obs147-hang']==='once'&&/^\/api\/v1\/obs\/scoreboard\//.test(req.url)&&req.method==='GET'){
   hangNext=false;state.hungReads++;releaseHang=forward;return;
  }
  forward();
 });
 return {state,listen:()=>new Promise(r=>server.listen(port,'127.0.0.1',r)),close:async()=>{releaseHang?.();for(const s of streams)s.destroy();server.closeAllConnections();await new Promise(r=>server.close(r));}};
}
module.exports={createProxy};
if(require.main===module){const proxy=createProxy();proxy.listen().then(()=>console.log('147 fault proxy19857 -> API19856'));for(const sig of ['SIGINT','SIGTERM'])process.on(sig,()=>proxy.close().then(()=>process.exit()));}
