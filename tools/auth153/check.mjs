// Owned-target only. Secrets remain in memory; stdout is a sanitized result receipt.
import assert from 'node:assert/strict';
import {readFile,stat,writeFile} from 'node:fs/promises';
import {randomUUID,randomBytes,createHash} from 'node:crypto';
const origin=new URL(process.env.AUTH153_ORIGIN??'http://127.0.0.1:21153');
assert.ok(['127.0.0.1','localhost','[::1]'].includes(origin.hostname),'Own loopback target required');
assert.equal(origin.port,'21153','Assigned worker153 API port required');
const credentialPath=process.env.AUTH153_CREDENTIALS_FILE;
assert.ok(credentialPath,'Provide private owned synthetic credential file');
assert.equal((await stat(credentialPath)).mode&0o077,0,'Credential file must be owner-only');
const credentials=JSON.parse(await readFile(credentialPath,'utf8'));
const inventory=JSON.parse(await readFile(new URL('../../docs/api/auth-surfaces-v1.json',import.meta.url),'utf8'));
const captureNames=['X-UltiStats-Installation-Id','X-UltiStats-Account-Id','X-UltiStats-Session-Id'];
const results=[];
function record(name,extra={}){results.push({name,status:'PASS',...extra});}
function client() {
 const jar=new Map();let context=null,csrf=null;
 const capture=()=>context?[context.installationId,context.account.id,context.sessionId]:null;
 async function request(method,path,{body,identity=true,token=true,headers={},dropCsrfCookie=false}={}) {
  const outgoing=new Headers(headers);
  if(identity&&capture())captureNames.forEach((name,i)=>{if(!outgoing.has(name))outgoing.set(name,capture()[i]);});
  if(token&&csrf&&!['GET','HEAD','OPTIONS'].includes(method))outgoing.set('X-CSRF-TOKEN',csrf);
  const cookies=[...jar].filter(([name])=>!dropCsrfCookie||!name.includes('CSRF'));
  if(cookies.length)outgoing.set('Cookie',cookies.map(([name,value])=>`${name}=${value}`).join('; '));
  if(body!==undefined&&!outgoing.has('Content-Type'))outgoing.set('Content-Type','application/json');
  const response=await fetch(new URL(path,origin),{method,headers:outgoing,body:body===undefined?undefined:typeof body==='string'?body:JSON.stringify(body),redirect:'manual',signal:AbortSignal.timeout(10000)});
  const setCookies=response.headers.getSetCookie();
  for(const cookie of setCookies){const first=cookie.split(';',1)[0],at=first.indexOf('=');jar.set(first.slice(0,at),first.slice(at+1));}
  const bytes=new Uint8Array(await response.arrayBuffer());
  const data=response.headers.get('content-type')?.includes('json')&&bytes.length?JSON.parse(new TextDecoder().decode(bytes)):null;
  return {method,status:response.status,data,bytes,setCookies,headers:response.headers};
 }
 async function refreshCsrf(){const result=await request('GET','/api/v1/auth/csrf');assert.equal(result.status,200,'CSRF fetch');assert.equal(result.data.headerName,'X-CSRF-TOKEN');csrf=result.data.token;}
 async function login(login,password){
  const installation=await request('GET','/api/v1/installation',{identity:false});assert.equal(installation.status,200,'Installation metadata');assert.equal(installation.data.entryMode,'LOOPBACK','Explicit owned loopback mode required');
  await refreshCsrf();
  const result=await request('POST','/api/v1/auth/login',{identity:false,body:{login,password},headers:{[captureNames[0]]:installation.data.installationId}});
  assert.equal(result.status,200,`Synthetic login failed: ${result.status}/${result.data?.code}`);context=result.data;await refreshCsrf();
 }
 async function changeTemporary(password){const replacement=randomBytes(32).toString('base64url');
  const result=await request('POST','/api/v1/auth/password',{body:{currentPassword:password,newPassword:replacement}});
  assert.equal(result.status,200,'Synthetic password change');context=result.data;await refreshCsrf();return replacement;}
 return {request,login,changeTemporary,refreshCsrf,get context(){return context;}};
}
function rejection(response,status,code){assert.equal(response.status,status,`Expected ${status}/${code}, got ${response.status}/${response.data?.code}`);if(response.method!=='HEAD'){assert.equal(response.data.code,code);assert.equal(response.data.requestRejectedBeforeExecution,true);}assert.equal(response.headers.get('location'),null);}
const admin=client();await admin.login(credentials.login,credentials.password);
if(admin.context.state==='PASSWORD_CHANGE_REQUIRED'){credentials.password=await admin.changeTemporary(credentials.password);await writeFile(credentialPath,JSON.stringify(credentials),{mode:0o600});}
const guest=client();await guest.refreshCsrf();
const suffix=randomUUID();
const created=[];
async function create(path,body){const response=await admin.request('POST',path,{body});assert.equal(response.status,201,`Create ${path}: ${response.status}/${response.data?.code}`);created.push({type:path.split('/').at(-1),id:response.data.id??response.data.account?.id});await writeFile(process.env.AUTH153_FIXTURE_MANIFEST??'/tmp/ultistats-auth153/http-fixtures-current.json',JSON.stringify({status:'RUNNING',ownedFixtures:created},null,2));return response.data;}
const teams=await Promise.all([create('/api/v1/teams',{name:`auth153-A-${suffix}`}),create('/api/v1/teams',{name:`auth153-B-${suffix}`})]);
const match=await create('/api/v1/matches',{teamIds:teams.map(t=>t.id),playersPerTeam:2});
const ids={matchId:match.id,id:teams[0].id,teamId:teams[0].id,playerId:randomUUID(),eventId:randomUUID(),commandId:randomUUID()};
const pathFor=path=>path.replace(/\{([^}]+)}/g,(_,name)=>ids[name]);
const protectedWrites=inventory.routes.filter(r=>r.owner==='enforcement'&&!['GET','HEAD'].includes(r.method));
async function baseline(){
 const data={};for(const path of ['/api/v1/teams','/api/v1/players','/api/v1/matches',`/api/v2/matches/${match.id}/events`,'/api/v1/obs/config']){
  const response=await admin.request('GET',path);assert.equal(response.status,200);data[path]=response.data;
 }
 return createHash('sha256').update(JSON.stringify(data)).digest('hex');
}
const before=await baseline();
for(const row of protectedWrites){
 const path=pathFor(row.path);
 rejection(await guest.request(row.method,path,{body:'{malformed'}),401,'AUTH_REQUIRED');
 for(let index=0;index<3;index++){
  const response=await admin.request(row.method,path,{body:'{malformed',token:false,dropCsrfCookie:true,headers:{[captureNames[index]]:randomUUID()}});
  rejection(response,403,'AUTH_IDENTITY_MISMATCH');assert.equal(response.setCookies.length,0,'Mismatched owner must not materialize CSRF/session cookies');
 }
}
assert.equal(await baseline(),before,'Denied requests changed domain state');record('all protected unsafe routes guest and each owner UUID denied before binding with no domain change',{routes:protectedWrites.length});
const newAccount=await create('/api/v1/accounts',{login:`auth153-${suffix}`,displayName:'Auth153 synthetic organizer',role:'ORGANIZER'});
const organizer=client();await organizer.login(newAccount.account.login,newAccount.temporaryPassword);
rejection(await organizer.request('PUT','/api/v1/obs/config',{body:'{}'}),403,'AUTH_PASSWORD_CHANGE_REQUIRED');
await organizer.changeTemporary(newAccount.temporaryPassword);record('temporary password downscope');
rejection(await organizer.request('GET','/api/v1/accounts'),403,'AUTH_PERMISSION_DENIED');record('organizer account administration separation');
for(const endpoint of ['/swagger-ui.html','/swagger-ui/index.html','/v3/api-docs','/v3/api-docs.yaml','/obs-admin.html','/obs-scoreboard.html?preview=1','/obs-battle-log.html?preview=1']){
 rejection(await guest.request('GET',endpoint),401,'AUTH_REQUIRED');
 const allowed=await organizer.request('GET',endpoint);assert.ok([200,302].includes(allowed.status),`Staff tools ${endpoint}: ${allowed.status}`);
}
record('private docs aliases editor and previews');
for(const ending of ['', '/export'])for(const method of ['GET','HEAD'])rejection(await guest.request(method,`/api/v1/matches/${match.id}/statistics${ending}`),401,'AUTH_REQUIRED');
assert.equal((await guest.request('GET',`/api/v1/matches/${match.id}`)).status,200);record('planned live public but statistics JSON HEAD ZIP private');
const start='2026-11-01T00:00:00Z';
assert.equal((await organizer.request('POST',`/api/v1/matches/${match.id}/start`,{body:{timestamp:start}})).status,200);
const snapshot=(await organizer.request('GET',`/api/v2/matches/${match.id}/events`)).data;
const eventId=randomUUID();
const batch={expectedLastSequenceNumber:snapshot.lastSequenceNumber,expectedLogRevision:snapshot.logRevision,events:[{eventId,type:'PULL',occurredAt:start,participantId:match.teams[0].participants[0].participantId}]};
const committed=await organizer.request('POST',`/api/v2/matches/${match.id}/events/batch`,{body:batch});assert.equal(committed.status,201);
const afterCommit=(await organizer.request('GET',`/api/v2/matches/${match.id}/events`)).data;
rejection(await organizer.request('POST',`/api/v2/matches/${match.id}/events/batch`,{body:batch,headers:{[captureNames[2]]:randomUUID()}}),403,'AUTH_IDENTITY_MISMATCH');
assert.deepEqual((await organizer.request('GET',`/api/v2/matches/${match.id}/events`)).data,afterCommit);record('prior committed batch remains canonical after current-attempt auth denial');
const undo={commandId:randomUUID(),expectedLastEventId:eventId,expectedLogRevision:afterCommit.logRevision};
assert.equal((await organizer.request('POST',`/api/v2/matches/${match.id}/events/undo`,{body:undo})).status,200);
const receiptPath=`/api/v2/matches/${match.id}/events/undo/${undo.commandId}`;
const receipt=(await organizer.request('GET',receiptPath)).data;
rejection(await organizer.request('POST',`/api/v2/matches/${match.id}/events/undo`,{body:undo,headers:{[captureNames[1]]:randomUUID()}}),403,'AUTH_IDENTITY_MISMATCH');
assert.deepEqual((await organizer.request('GET',receiptPath)).data,receipt);
rejection(await guest.request('GET',receiptPath),401,'AUTH_REQUIRED');record('immutable committed undo receipt preserved and private');
// Existing lifecycle requires a completed point; undo above intentionally left an empty log.
const finishBase=(await organizer.request('GET',`/api/v2/matches/${match.id}/events`)).data;
const home=match.teams[0].participants,away=match.teams[1].participants;
const completedPoint={expectedLastSequenceNumber:finishBase.lastSequenceNumber,expectedLogRevision:finishBase.logRevision,events:[
 {eventId:randomUUID(),type:'PULL',occurredAt:'2026-11-01T00:00:01Z',participantId:home[0].participantId},
 {eventId:randomUUID(),type:'PICKUP',occurredAt:'2026-11-01T00:00:02Z',participantId:away[0].participantId},
 {eventId:randomUUID(),type:'GOAL',occurredAt:'2026-11-01T00:00:03Z',fromParticipantId:away[0].participantId,toParticipantId:away[1].participantId},
]};
assert.equal((await organizer.request('POST',`/api/v2/matches/${match.id}/events/batch`,{body:completedPoint})).status,201,'Completed-point fixture');
assert.equal((await organizer.request('POST',`/api/v1/matches/${match.id}/end`,{body:{timestamp:'2026-11-01T00:01:00Z'}})).status,200);
for(const ending of ['', '/export'])for(const method of ['GET','HEAD']){
 const response=await guest.request(method,`/api/v1/matches/${match.id}/statistics${ending}`);assert.equal(response.status,200,'FINISHED public statistics');
 if(ending&&method==='GET')assert.deepEqual([...response.bytes.slice(0,2)],[80,75],'ZIP signature');
}
record('finished guest statistics JSON HEAD ZIP');
rejection(await guest.request('POST','/api/v1/matches/export',{body:{matchIds:[match.id]}}),401,'AUTH_REQUIRED');record('finished portable JSON remains private');
// No deletion while a consumer may need this exact fixture. Record nonsecret fixture IDs for explicit cleanup.
console.log(JSON.stringify({status:'PASS',origin:origin.origin,installationId:admin.context.installationId,contractVersion:'1.0.0',results,ownedFixtures:created,accountId:newAccount.account.id,notCovered:['anonymous connector (154 integration)','real DB commit failure/revoke race (152 integration)','browser/native OBS','combined QA24']},null,2));
