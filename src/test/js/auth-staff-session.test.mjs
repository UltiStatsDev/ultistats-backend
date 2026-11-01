import {test} from 'node:test';
import assert from 'node:assert/strict';

// These tests catch wrong-owner headers, leaked cross-origin credentials and unsafe retries.
const modulePath='../../main/resources/static/auth/staff-session.mjs';
const staff=await import(modulePath).catch(error=>{
 if(error.code==='ERR_MODULE_NOT_FOUND')return {};
 throw error;
});
const ids={installationId:'10000000-0000-0000-0000-000000000001',accountId:'20000000-0000-0000-0000-000000000001',sessionId:'30000000-0000-0000-0000-000000000001'};
const context=()=>({contractVersion:'1.0.0',installationId:ids.installationId,serverNow:'2026-11-01T00:00:00Z',
 state:'AUTHENTICATED',sessionId:ids.sessionId,account:{id:ids.accountId,login:'organizer',displayName:'Организатор',role:'ORGANIZER',status:'ACTIVE',mustChangePassword:false,createdAt:'2026-11-01T00:00:00Z',updatedAt:'2026-11-01T00:00:00Z'},
 permissions:['SPORTS_WRITE','STATISTICS_READ_ALL','TRANSFER','OBS_ADMIN','API_DOCS'],lastActivityAt:'2026-11-01T00:00:00Z',idleExpiresAt:'2026-11-15T00:00:00Z',reauthenticatedUntil:null,replacedSessionId:null});
function fixture() {
 const state={context:context(),calls:[],status:200,csrf:'masked-in-memory-csrf',writes:0};
 const fetchImpl=async(url,options={})=>{
  const path=new URL(url,'https://staff.example').pathname;
  const call={path,options:{...options,headers:new Headers(options.headers)}};state.calls.push(call);
  if(path==='/api/v1/auth/session')return Response.json(state.context,{status:state.status});
  if(path==='/api/v1/auth/csrf')return Response.json({headerName:'X-CSRF-TOKEN',token:state.csrf});
  state.writes++;return Response.json({saved:true},{status:state.writeStatus??200});
 };
 assert.equal(typeof staff.createStaffSession,'function','staff session adapter must be implemented');
 state.client=staff.createStaffSession({fetchImpl,origin:'https://staff.example'});
 return state;
}
function assertCapture(call) {
 assert.equal(call.options.headers.get('X-UltiStats-Installation-Id'),ids.installationId);
 assert.equal(call.options.headers.get('X-UltiStats-Account-Id'),ids.accountId);
 assert.equal(call.options.headers.get('X-UltiStats-Session-Id'),ids.sessionId);
 assert.equal(call.options.credentials,'same-origin');
 assert.equal(call.options.cache,'no-store');
}
test('protected save validates current owner and sends masked CSRF plus complete captured identity',async()=>{
 const f=fixture();await f.client.bootstrap('OBS_ADMIN');
 const response=await f.client.protectedFetch('/api/v1/obs/config',{method:'PUT',headers:{'Content-Type':'application/json'},body:'{"maxRows":8}'});
 assert.equal(response.status,200);assert.equal(f.writes,1);
 assert.deepEqual(f.calls.map(c=>c.path),['/api/v1/auth/session','/api/v1/auth/csrf','/api/v1/auth/session','/api/v1/auth/csrf','/api/v1/obs/config']);
 for(const call of f.calls.slice(1))assertCapture(call);
 assert.equal(f.calls.at(-1).options.headers.get('X-CSRF-TOKEN'),'masked-in-memory-csrf');
 assert.equal(f.calls.at(-1).options.body,'{"maxRows":8}');
});
for(const changed of ['installation','account','session'])test(`save after ${changed} switch preserves original owner and sends no mutation`,async()=>{
 const f=fixture();await f.client.bootstrap('OBS_ADMIN');
 if(changed==='installation')f.context.installationId='10000000-0000-0000-0000-000000000002';
 if(changed==='account')f.context.account.id='20000000-0000-0000-0000-000000000002';
 if(changed==='session')f.context.sessionId='30000000-0000-0000-0000-000000000002';
 await assert.rejects(f.client.protectedFetch('/api/v1/obs/config',{method:'PUT',body:'original draft'}),{code:'AUTH_IDENTITY_MISMATCH'});
 assert.equal(f.writes,0);assertCapture(f.calls.at(-1));
});
for(const state of ['ANONYMOUS','PASSWORD_CHANGE_REQUIRED'])test(`${state} cannot bootstrap protected editor`,async()=>{
 const f=fixture();f.context.state=state;f.context.permissions=[];
 if(state==='ANONYMOUS'){f.context.account=null;f.context.sessionId=null;}
 await assert.rejects(f.client.bootstrap('OBS_ADMIN'));
 assert.equal(f.writes,0);assert.equal(f.calls.length,1);
});
test('loss of permission stops save without changing capture or retrying',async()=>{
 const f=fixture();await f.client.bootstrap('OBS_ADMIN');f.context.permissions=[];
 await assert.rejects(f.client.protectedFetch('/api/v1/obs/config',{method:'PUT'}),{code:'AUTH_PERMISSION_DENIED'});
 assert.equal(f.writes,0);
});
test('explicit auth denial never retries a protected request',async()=>{
 const f=fixture();await f.client.bootstrap('OBS_ADMIN');f.writeStatus=403;
 const response=await f.client.protectedFetch('/api/v1/obs/config',{method:'PUT',body:'frozen'});
 assert.equal(response.status,403);assert.equal(f.writes,1);
});
test('refuses unbootstrapped writes and cross-origin or credential-bearing URLs',async()=>{
 const f=fixture();await assert.rejects(f.client.protectedFetch('/api/v1/obs/config',{method:'PUT'}));assert.equal(f.writes,0);
 await f.client.bootstrap('OBS_ADMIN');const count=f.calls.length;
 for(const url of ['https://evil.example/api/v1/teams','//evil.example/api/v1/teams','https://user:pass@staff.example/api/v1/teams','/api/v1/teams?token=secret'])
  await assert.rejects(f.client.protectedFetch(url,{method:'POST'}));
 assert.equal(f.calls.length,count);
});
test('re-bootstrap cannot silently adopt a changed cookie identity',async()=>{
 const f=fixture();await f.client.bootstrap('API_DOCS');f.context.sessionId='30000000-0000-0000-0000-000000000002';
 await assert.rejects(f.client.bootstrap('API_DOCS'),{code:'AUTH_IDENTITY_MISMATCH'});assert.equal(f.writes,0);assertCapture(f.calls.at(-1));
});
test('malformed CSRF response cannot produce unsafe request',async()=>{
 const f=fixture();await f.client.bootstrap('OBS_ADMIN');f.csrf='';
 await assert.rejects(f.client.protectedFetch('/api/v1/obs/config',{method:'PUT'}));assert.equal(f.writes,0);
});

test('private reads attach captured identity without unsafe token and never renew activity',async()=>{
 const f=fixture();await f.client.bootstrap('API_DOCS');f.calls=[];
 const prepared=await f.client.prepareRequest('/v3/api-docs',{method:'GET'});
 assert.equal(prepared.headers.get('X-CSRF-TOKEN'),null);
 assert.equal(prepared.headers.get('X-UltiStats-Session-Id'),ids.sessionId);
 assert.deepEqual(f.calls.map(c=>c.path),['/api/v1/auth/session']);
});
test('concurrent bootstrap is single-flight and remains bound to one observed owner',async()=>{
 const f=fixture();await Promise.all([f.client.bootstrap('OBS_ADMIN'),f.client.bootstrap('OBS_ADMIN')]);
 assert.deepEqual(f.calls.map(c=>c.path),['/api/v1/auth/session','/api/v1/auth/csrf']);
});
test('caller headers cannot override captured identity or CSRF',async()=>{
 const f=fixture();await f.client.bootstrap('OBS_ADMIN');
 const prepared=await f.client.prepareRequest('/api/v1/obs/config',{method:'PUT',headers:{'X-UltiStats-Session-Id':'other','X-CSRF-TOKEN':'untrusted'}});
 assert.equal(prepared.headers.get('X-UltiStats-Session-Id'),ids.sessionId);
 assert.equal(prepared.headers.get('X-CSRF-TOKEN'),'masked-in-memory-csrf');
});
test('Swagger interceptor prepares captured headers without executing or replaying try-it-out',async()=>{
 const swagger=await import('../../main/resources/static/auth/swagger-adapter.mjs').catch(error=>{if(error.code==='ERR_MODULE_NOT_FOUND')return {};throw error;});
 assert.equal(typeof swagger.createSwaggerInterceptor,'function','Swagger adapter must be implemented');
 const f=fixture();await f.client.bootstrap('API_DOCS');
 const intercept=swagger.createSwaggerInterceptor(f.client);
 const prepared=await intercept({url:'https://staff.example/api/v1/teams',method:'POST',headers:{'Content-Type':'application/json'},body:'{"name":"Synthetic"}'});
 assert.equal(new Headers(prepared.headers).get('X-CSRF-TOKEN'),'masked-in-memory-csrf');
 assert.equal(new Headers(prepared.headers).get('X-UltiStats-Account-Id'),ids.accountId);
 assert.equal(prepared.body,'{"name":"Synthetic"}');assert.equal(f.writes,0);
 assert.equal(prepared.credentials,'same-origin');
});
