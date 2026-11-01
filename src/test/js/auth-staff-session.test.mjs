import {test} from 'node:test';
import assert from 'node:assert/strict';
import {createSwaggerTransportPlugin} from '../../main/resources/static/auth/swagger-adapter.mjs';
function fixture(){
 const ticket={generation:1};let current=true;const calls=[];
 const session={capture:()=>ticket,isCurrent:value=>current&&value===ticket,protectedFetch:async(url,init)=>{calls.push({url,init});return Response.json({saved:true});}};
 const system={fn:{fetch:async request=>{const response=await request.userFetch(request.url,request);return {status:response.status,body:await response.json()};}}};
 const plugin=createSwaggerTransportPlugin(session,ticket)(system);
 return {plugin,calls,invalidate:()=>current=false};
}
test('Swagger uses shared network execution without adding CSRF to request metadata',async()=>{
 const f=fixture(),request={url:'https://staff.example/api/v1/teams',method:'POST',headers:{'Content-Type':'application/json'},body:'{"name":"draft"}'};
 assert.equal((await f.plugin.fn.fetch(request)).status,200);assert.equal(f.calls.length,1);
 assert.equal(f.calls[0].init.body,request.body);assert.equal(new Headers(request.headers).has('X-CSRF-TOKEN'),false);
});
test('Swagger blocks auth/account execution and Try it out selectors',async()=>{
 const f=fixture(),allowed=f.plugin.statePlugins.spec.wrapSelectors.allowTryItOutFor(()=>true);
 for(const path of ['/api/v1/auth/login','/api/v1/accounts','/api/v1/accounts/123']){
  assert.equal(allowed({},path,'post'),false);
  await assert.rejects(f.plugin.fn.fetch({url:'https://staff.example'+path,method:'POST'}));
 }
 assert.equal(allowed({},'/api/v1/teams','post'),true);assert.equal(f.calls.length,0);
});
test('Swagger cannot execute or publish a result after the original page capture changes',async()=>{
 const f=fixture();f.invalidate();await assert.rejects(f.plugin.fn.fetch({url:'https://staff.example/api/v1/teams'}));assert.equal(f.calls.length,0);
});
test('Swagger drops an already executed response when capture changes during serialization',async()=>{
 const ticket={};let current=true;
 const plugin=createSwaggerTransportPlugin({isCurrent:()=>current,protectedFetch:async()=>Response.json({saved:true})},ticket)({fn:{fetch:async request=>{await request.userFetch(request.url,request);current=false;return {status:200};}}});
 await assert.rejects(plugin.fn.fetch({url:'https://staff.example/api/v1/teams'}));
});
