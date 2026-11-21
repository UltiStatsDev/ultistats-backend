// Defensive synthetic matrix on the owned HTTPS fixture; never log credentials or full source URLs.
import assert from 'node:assert/strict';
import {readFileSync,writeFileSync} from 'node:fs';
import {createHash} from 'node:crypto';
export async function verifyLegacyRetirement(browser,input,link) {
 const {origin,match,staff,evidence}=input, token=new URL(link.url).hash.slice(5), rows=[];
 const guest=await browser.newContext(), staffContext=await browser.newContext();
 await staffContext.addCookies(Object.entries(input.cookies).map(([name,value])=>({name,value,url:origin,secure:true})));
 const roles=[['guest',guest,{}],['viewer-key',guest,{Authorization:'Bearer '+token}],['staff',staffContext,staff]];
 const request=async(role,context,headers,method,path,status)=>{
  const response=await context.request.fetch(origin+path,{method,headers,maxRedirects:0,timeout:5000});
  assert.equal(response.status(),status,`${role} ${method} ${path}`);assert.equal(response.headers().location,undefined);
  const body=await response.text();if(method==='HEAD')assert.equal(body,'');
  rows.push({role,method,path:path.split('?')[0],status,cookiePresent:role==='staff',bearerPresent:role==='viewer-key'});return {response,body};
 };
 try {
  for(const path of ['/obs-battle-log.html','/obs-scoreboard.html','/obs-diagnostic.html']) {
   let expected;
   for(const suffix of ['','?preview=1','?preview=0&preview=1','?preview=1&preview=0','?extra=retirement-marker-246','?key=retirement-marker-246'])
    for(const [role,context,headers]of roles)for(const method of ['GET','HEAD']){
     const {body}=await request(role,context,headers,method,path+suffix,200);
     if(method==='GET'){
      assert.match(body,/Browser Source/);assert.doesNotMatch(body,/<script|<iframe|<img|<link|location\.|\/api\/|retirement-marker-246/i);
      expected??=body;assert.equal(body,expected);
     }
    }
  }
  for(const path of ['/api/v1/obs/config',`/api/v1/obs/scoreboard/${match}`])
   for(const [role,context,headers]of roles)for(const method of ['GET','HEAD'])
    await request(role,context,headers,method,path,role==='staff'?200:401);
  for(const path of ['/api/v1/obs/config/stream','/api/v1/obs/stream'])
   for(const [role,context,headers]of roles)for(const method of ['GET','HEAD']){
    const {response,body}=await request(role,context,{...headers,Accept:'text/event-stream'},method,path,role==='staff'?410:401);
    assert.deepEqual([...new Set(response.headers()['cache-control'].split(',').map(value=>value.trim()))],['no-store']);assert.doesNotMatch(body,/event:|data:/);
    if(role==='staff')assert.match(response.headers()['content-type'],/^text\/plain/);
   }
  for(const path of ['/obs/shared-worker.js','/obs/client.mjs','/obs/overlay.mjs','/obs/transport.mjs','/obs/scoreboard-client.mjs','/obs/scoreboard.mjs'])
   for(const [role,context,headers]of roles)for(const method of ['GET','HEAD'])
    await request(role,context,headers,method,path,role==='staff'?200:401);
  for(const path of ['/obs-scoreboard.html;old=1','/obs//shared-worker.js','/api/v1/obs/config%2fstream','/api/v1/obs//stream','/OBS-scoreboard.html'])
   for(const [role,context,headers]of roles)for(const method of ['GET','HEAD']){
    const response=await context.request.fetch(origin+path,{method,headers,maxRedirects:0,timeout:5000});
    assert.ok([400,403].includes(response.status()),`${role} ${method} malformed ${path}: ${response.status()}`);
    rows.push({role,method,path,status:response.status(),malformed:true});
   }
  for(const path of [`/api/v1/matches/${match}`,`/api/v1/matches/${match}/events`,`/api/v2/matches/${match}/events`,'/api/v1/installation'])
   await request('guest',guest,{},'GET',path,200);
  // Login/return stays owned by the existing entry navigation. No fake Web/login page is served.
  const navigation=await guest.request.get(origin+'/obs-admin.html',{headers:{Accept:'text/html'},maxRedirects:0});
  assert.equal(navigation.status(),302);assert.ok(navigation.headers().location.includes('returnTo='));
  const page=await guest.newPage();await page.goto(origin+'/obs-scoreboard.html?preview=1');
  const general=await page.evaluate(async path=>{
   const controller=new AbortController();const timer=setTimeout(()=>controller.abort(),3000);
   try{const response=await fetch(path,{signal:controller.signal});const chunk=await response.body.getReader().read();return{status:response.status,connected:new TextDecoder().decode(chunk.value).includes('event:connected')}}
   finally{clearTimeout(timer);controller.abort()}
  },`/api/v1/matches/${match}/events/stream`);
  assert.equal(general.status,200);assert.equal(general.connected,true);
  const spec=await (await staffContext.request.get(origin+'/v3/api-docs',{headers:staff})).json();
  for(const path of ['/api/v1/obs/config','/api/v1/obs/scoreboard/{matchId}','/api/v1/obs/config/stream','/api/v1/obs/stream'])
   assert.deepEqual(spec.paths[path].get.security,[{staffSession:[]}]);
  for(const path of ['/api/v1/obs/config/stream','/api/v1/obs/stream']){
   const op=spec.paths[path].get;assert.equal(op.deprecated,true);assert.ok(op.responses['410']);assert.equal(op.responses['200'],undefined);
  }
  // Simulated old cached script, not a claim that Chromium held a historical cache entry.
  // Only its relative stream target is made absolute for Blob worker URL resolution.
  const old=readFileSync(new URL('./fixtures/obs-pre-g2-cached-worker.js',import.meta.url),'utf8');
  const simulate=async context=>{
   const page=await context.newPage();await page.goto(origin+'/obs-scoreboard.html');
   return page.evaluate(async source=>{
    const blob=URL.createObjectURL(new Blob([source],{type:'text/javascript'}));const worker=new SharedWorker(blob);const names=[];
    try{return await new Promise((resolve,reject)=>{
     const timeout=setTimeout(()=>reject(Error('cached worker did not encounter retired channel')),3000);
     worker.port.onmessage=event=>{const m=event.data;if(m.type==='event')names.push(m.name);if(m.type==='error'){clearTimeout(timeout);resolve({error:true,eventNames:names})}};
     worker.port.start();worker.port.postMessage({type:'subscribe',matchId:null});
    })}finally{worker.port.postMessage({type:'unsubscribe'});worker.port.close();URL.revokeObjectURL(blob)}
   },old.replace("new EventSource('/api/v1/obs/stream')",`new EventSource('${origin}/api/v1/obs/stream')`));
  };
  const cachedGuest=await simulate(guest),cachedStaff=await simulate(staffContext);
  assert.deepEqual(cachedGuest,{error:true,eventNames:[]});assert.deepEqual(cachedStaff,{error:true,eventNames:[]});
  const cachedKey=await guest.request.get(`${origin}/api/v1/obs/sources/scoreboard/auto`,{headers:{Authorization:'Bearer '+token},maxRedirects:0});assert.equal(cachedKey.status(),200);
  const sourceHead=await guest.request.head(`${origin}/api/v1/obs/sources/scoreboard/auto`,{headers:{Authorization:'Bearer '+token},maxRedirects:0});assert.equal(sourceHead.status(),403);
  writeFileSync(evidence+'/retirement-matrix.json',JSON.stringify({rows,generalStream:general,authNavigation:{status:302,returnToPresent:true},openApi:{staffSession:true,retired410:true,no200Stream:true},cachedWorker:{simulated:true,fixtureSHA256:createHash('sha256').update(old).digest('hex'),guest:cachedGuest,staff:cachedStaff,serverProof:'Real GET/HEAD guest/key401 + staff410 matrix above; worker error/no events independently observed. No claim historical browser cache was present.'},limits:'Owned synthetic HTTPS only; no native OBS/ProductQA/hosted activation.'},null,2)+'\n');
 }finally{await guest.close();await staffContext.close();}
}
