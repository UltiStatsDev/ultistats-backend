import {test} from 'node:test';
import assert from 'node:assert/strict';
import {Reconciler} from '../../main/resources/static/obs/client.mjs';

test('serializes invalidations and final reconciliation; retries failed final read',async()=>{
 let running=0,peak=0,calls=0,fail=false,release;
 const gate=new Promise(r=>release=r), values=[];
 const sync=new Reconciler(async()=>{ running++; peak=Math.max(peak,running); calls++; if(calls===1) await gate;
  running--; if(fail){fail=false;throw Error('offline');} return calls; },value=>values.push(value),()=>{},5);
 const first=sync.request(); sync.request(); sync.request(); release(); await first;
 assert.equal(peak,1); assert.deepEqual(values,[1,2]);
 fail=true; await sync.finish(); assert.deepEqual(values,[1,2,4]); sync.close();
});
test('requests arriving during failed fetch are retained and close cancels retries',async()=>{
 let calls=0; const values=[];
 const sync=new Reconciler(async()=>{calls++;if(calls===1)throw Error('offline');return calls;},v=>values.push(v),()=>{},5);
 await sync.request(); assert.deepEqual(values,[2]);sync.close();await sync.request();assert.equal(calls,2);
});
