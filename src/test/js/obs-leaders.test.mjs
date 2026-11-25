import {test} from 'node:test';
import assert from 'node:assert/strict';
import {leaderPath,watchLeaders} from '../../main/resources/static/obs/leaders.mjs';
test('typed binding accepts only AUTO or exact canonical UUID',()=>{
 assert.equal(leaderPath('auto','ignored'),'/api/v1/obs/infographics/leaders/auto');
 assert.equal(leaderPath('exact','ABCDEF00-0000-0000-0000-000000000001'),'/api/v1/obs/infographics/leaders/abcdef00-0000-0000-0000-000000000001');
 for(const args of [['exact','1'],['bogus',''],['exact',' auto ']])assert.throws(()=>leaderPath(...args));
});
test('switching captured binding clears and drops late response without overlap',async()=>{
 let release,current=true,listener;const session={capture:()=>1,isCurrent:()=>current,subscribe:f=>{listener=f;return()=>{}},protectedFetch:()=>new Promise(r=>release=r)};
 const seen=[],status=[];const stop=watchLeaders(session,'/api/v1/obs/infographics/leaders/auto',v=>seen.push(v),v=>status.push(v));
 stop();release({ok:true,json:async()=>({snapshot:{private:'late'}})});await new Promise(r=>setTimeout(r,10));
 assert.deepEqual(seen,[null]);
 current=false;listener?.();assert.equal(seen.length,1);
});
