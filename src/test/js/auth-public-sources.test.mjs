import {test} from 'node:test';
import assert from 'node:assert/strict';
const module=await import('../../main/resources/static/auth/public-sources.mjs').catch(error=>{if(error.code==='ERR_MODULE_NOT_FOUND')return {};throw error;});
const installationId='10000000-0000-0000-0000-000000000001',matchId='20000000-0000-0000-0000-000000000001';
function fixture(){
 const state={metadata:{contractVersion:'1.0.0',installationId,entryMode:'SECURE',publicOrigin:'http://guest.example:8080',secureOrigin:'https://staff.example'},calls:[],offline:false};
 assert.equal(typeof module.createPublicSources,'function','public source metadata adapter must be implemented');
 state.sources=module.createPublicSources({origin:'https://staff.example',fetchImpl:async(url,options)=>{
  state.calls.push({url,options});if(state.offline)throw Error('offline');return Response.json(state.metadata);
 }});return state;
}
test('public source URLs use configured public HTTP while metadata fetch omits staff credentials',async()=>{
 const f=fixture();await f.sources.load(installationId);
 assert.equal(f.sources.sourceUrl('/obs-battle-log.html',matchId),`http://guest.example:8080/obs-battle-log.html?matchId=${matchId}`);
 assert.equal(f.sources.sourceUrl('/obs-scoreboard.html',matchId),`http://guest.example:8080/obs-scoreboard.html?matchId=${matchId}`);
 assert.equal(f.calls[0].url,'https://staff.example/api/v1/installation');
 assert.equal(f.calls[0].options.credentials,'omit');assert.equal(f.calls[0].options.cache,'no-store');
 assert.equal(f.calls[0].options.headers,undefined);
});
test('mismatched installation clears previously available links and never adopts the new owner',async()=>{
 const f=fixture();await f.sources.load(installationId);f.metadata.installationId='10000000-0000-0000-0000-000000000002';
 await assert.rejects(f.sources.load(installationId),{code:'AUTH_IDENTITY_MISMATCH'});
 assert.throws(()=>f.sources.sourceUrl('/obs-scoreboard.html',matchId));
 await assert.rejects(f.sources.load(f.metadata.installationId),{code:'AUTH_IDENTITY_MISMATCH'});
});
test('metadata failure exposes no staff-origin fallback and explicit retry can recover same installation',async()=>{
 const f=fixture();await f.sources.load(installationId);f.offline=true;
 await assert.rejects(f.sources.load(installationId));assert.throws(()=>f.sources.sourceUrl('/obs-scoreboard.html',matchId));
 f.offline=false;await f.sources.load(installationId);
 assert.equal(new URL(f.sources.sourceUrl('/obs-scoreboard.html',matchId)).origin,'http://guest.example:8080');
});
for(const origin of ['https://user:secret@guest.example','http://guest.example/path','https://guest.example?token=secret','https://guest.example#fragment','javascript:alert(1)'])test('malformed public origin has no generated source URL: '+new URL(origin).protocol,async()=>{
 const f=fixture();f.metadata.publicOrigin=origin;
 await assert.rejects(f.sources.load(installationId));assert.throws(()=>f.sources.sourceUrl('/obs-scoreboard.html',matchId));
});
test('only source page and canonical match UUID can become query values',async()=>{
 const f=fixture();await f.sources.load(installationId);
 for(const page of ['/obs-admin.html','//evil.example/source','/obs-scoreboard.html?token=secret'])assert.throws(()=>f.sources.sourceUrl(page,matchId));
 for(const id of ['other','?account=secret','20000000-0000-0000-0000-000000000001&token=x'])assert.throws(()=>f.sources.sourceUrl('/obs-scoreboard.html',id));
 assert.deepEqual([...new URL(f.sources.sourceUrl('/obs-scoreboard.html',matchId)).searchParams.keys()],['matchId']);
});
