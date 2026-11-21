import {test} from 'node:test';
import assert from 'node:assert/strict';
import {watchStaffJson,readStaffJson} from '../../main/resources/static/obs/staff-reader.mjs';
const pause=()=>new Promise(r=>setTimeout(r,10));
function fixture(fetch){
 let current=true,listener,disposed=false;const captured={};
 const session={capture:()=>captured,isCurrent:c=>current&&c===captured,subscribe:fn=>{listener=fn;return()=>listener=null},protectedFetch:fetch};
 return {session,invalidate(){current=false;listener?.()},get listening(){return !!listener}};
}
test('captured reader rejects late JSON after session replacement',async()=>{
 let release;const f=fixture(async()=>({ok:true,status:200,json:()=>new Promise(r=>release=r)}));
 const seen=[],ended=[];watchStaffJson(f.session,'/api/v1/obs/config',v=>seen.push(v),()=>{},()=>ended.push(true),{pollMs:1});
 await pause();f.invalidate();release({private:'late'});await pause();assert.deepEqual(seen,[]);assert.equal(ended.length,1);assert.equal(f.listening,false);
});
test('poll is serialized and explicit stop discards pending fetch without disposing parent session',async()=>{
 let release,calls=0;const f=fixture(()=>{calls++;return new Promise(r=>release=r)}),seen=[];
 const stop=watchStaffJson(f.session,'/api/v1/obs/config',v=>seen.push(v),()=>{},()=>{}, {pollMs:1});
 await pause();assert.equal(calls,1);stop();release({ok:true,status:200,json:async()=>({})});await pause();assert.deepEqual(seen,[]);assert.equal(calls,1);assert.equal(f.listening,false);
});
test('authentication denial is terminal and clears once instead of polling replacement cookies',async()=>{
 let calls=0,ended=0;const f=fixture(async()=>{calls++;return {status:403,ok:false}});
 watchStaffJson(f.session,'/api/v1/obs/config',()=>assert.fail(),()=>{},()=>ended++,{pollMs:1});
 await pause();await pause();assert.equal(calls,1);assert.equal(ended,1);assert.equal(f.listening,false);
});
test('one-shot read refuses stale capture after response JSON',async()=>{
 let release;const f=fixture(async()=>({ok:true,status:200,json:()=>new Promise(r=>release=r)}));
 const result=readStaffJson(f.session,'/api/v1/matches');await pause();f.invalidate();release({});await assert.rejects(result,/Сессия/);
});
test('poll timeout aborts request and retries serially; stop cancels retry',async()=>{
 let calls=0,aborted=0;const f=fixture((path,init)=>{calls++;return new Promise((_,reject)=>init.signal.addEventListener('abort',()=>{aborted++;reject(Error('abort'))},{once:true}))});
 const stop=watchStaffJson(f.session,'/api/v1/obs/config',()=>assert.fail(),()=>{},()=>{},{pollMs:1,timeoutMs:2});
 await pause();stop();const before=calls;await pause();assert.ok(aborted>0);assert.equal(calls,before);
});
