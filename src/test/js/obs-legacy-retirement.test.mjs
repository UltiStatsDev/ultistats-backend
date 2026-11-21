import {test} from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import vm from 'node:vm';
const asset=path=>readFile(new URL('../../main/resources/static/'+path,import.meta.url),'utf8');
test('all obsolete HTML entries contain literal replacement instructions without live or reflective code',async()=>{
 for(const path of ['obs-battle-log.html','obs-scoreboard.html','obs-diagnostic.html']) {
  const html=await asset(path);
  assert.match(html,/Browser Source/);assert.match(html,/href="\/obs-admin.html"/);
  assert.doesNotMatch(html,/<script|<iframe|<img|<link|http-equiv|location\.|URLSearchParams|EventSource|SharedWorker|\/api\/|\bfetch\s*\(/i);
 }
});
test('retired direct worker closes ports without fetching, subscribing or caching data',async()=>{
 const source=await asset('obs/shared-worker.js');let closed=0;const self={};
 vm.runInNewContext(source,{self,EventSource:()=>assert.fail('stream created'),fetch:()=>assert.fail('read created'),Map:()=>assert.fail('data cache created')});
 self.onconnect({ports:[{postMessage:message=>assert.equal(message.type,'retired'),close:()=>closed++}]});
 assert.equal(closed,1);
});
