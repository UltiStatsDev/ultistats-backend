import {test} from 'node:test';
import assert from 'node:assert/strict';
import oracle from '../../../tools/obs147/config-oracle.cjs';
test('config oracle compares category membership without hiding other changes or mutating captured values',()=>{
 const before={categories:['GOAL','BLOCK','INTERCEPTION','CALLAHAN'],corner:'TOP_RIGHT',ttlSeconds:8,maxRows:5,
  backgroundColor:'#17202e',textColor:'#ffffff',teamColors:{a:'#ffffff'}};
 const after={...before,categories:['GOAL','INTERCEPTION','CALLAHAN','BLOCK']};
 const captured=structuredClone(before);
 assert.deepEqual(oracle.configSnapshot(after),oracle.configSnapshot(before));
 assert.deepEqual(before,captured,'snapshot must not sort the captured input in place');
 for(const changed of [{...after,ttlSeconds:9},{...after,corner:'BOTTOM_LEFT'},
  {...after,teamColors:{a:'#000000'}},{...after,categories:['GOAL','BLOCK']},
  {...after,categories:['GOAL','GOAL','INTERCEPTION','CALLAHAN','BLOCK']}])
  assert.notDeepEqual(oracle.configSnapshot(changed),oracle.configSnapshot(before));
});
