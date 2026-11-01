import test from 'node:test';
import assert from 'node:assert/strict';
const api = await import('../maintenance.mjs').catch(() => ({}));
const id = n => String(n).repeat(64);
function fixture() {
  return { expected: { instanceId:'abc123456789',project:'ultistats-stable-abc123456789',databaseContainerId:id(1),writerContainerIds:[id(2)],imageId:`sha256:${id(3)}`,databaseVolume:'owned-pg',uploadsVolume:'owned-uploads',databaseNetwork:'owned-internal',ingressContainerId:id(4),ingressImageId:`sha256:${id(5)}`,origin:'http://127.0.0.1:25461' }, observed: { database:{id:id(1),project:'ultistats-stable-abc123456789',instanceId:'abc123456789',running:true,volume:'owned-pg',networks:['owned-internal'],publishedPorts:0}, writers:[{id:id(2),project:'ultistats-stable-abc123456789',instanceId:'abc123456789',imageId:`sha256:${id(3)}`,running:false,uploadsVolume:'owned-uploads',networks:['owned-internal'],publishedPorts:0}], ingress:{id:id(4),imageId:`sha256:${id(5)}`,project:'ultistats-stable-abc123456789',instanceId:'abc123456789',maintenance:true,configVerified:true}, network:{name:'owned-internal',internal:true,containerIds:[id(1),id(4)]},databaseReady:true,activeMatches:0,otherClients:0,migrationsVerified:true,sourceVerified:true,jarVerified:true } };
}
test('quiet point accepts observed stopped owned writers and ready V9 DB',()=>{const f=fixture();assert.equal(api.verifyQuietPoint(f.expected,f.observed),'ok');});
for(const [name,mutate,reason] of [
 ['foreign project',f=>f.observed.database.project='foreign','OWNERSHIP_MISMATCH'],
 ['different DB volume',f=>f.observed.database.volume='foreign','IDENTITY_MISMATCH'],
 ['running API writer',f=>f.observed.writers[0].running=true,'WRITER_RUNNING'],
 ['new network client',f=>f.observed.network.containerIds.push(id(9)),'UNKNOWN_NETWORK_CLIENT'],
 ['external DB network',f=>f.observed.database.networks.push('public'),'NETWORK_NOT_ISOLATED'],
 ['published DB port',f=>f.observed.database.publishedPorts=1,'NETWORK_NOT_ISOLATED'],
 ['active match created after inspect',f=>f.observed.activeMatches=1,'MATCH_IN_PROGRESS'],
 ['unknown database readiness',f=>f.observed.databaseReady=false,'DATABASE_UNREADY'],
 ['new direct database client',f=>f.observed.otherClients=1,'UNKNOWN_DATABASE_CLIENT'],
 ['unsupported migration',f=>f.observed.migrationsVerified=false,'MIGRATION_UNPROVEN'],
 ['open ingress disguised as maintenance',f=>f.observed.ingress.configVerified=false,'INGRESS_NOT_FENCED'],
 ['runtime JAR mismatch',f=>f.observed.jarVerified=false,'RUNTIME_UNPROVEN'],
 ['wrong writer image',f=>f.observed.writers[0].imageId=`sha256:${id(9)}`,'IDENTITY_MISMATCH'],
 ['omitted writer',f=>f.observed.writers=[],'IDENTITY_MISMATCH'],
 ['NaN count is not zero',f=>f.observed.activeMatches=null,'DATABASE_UNREADY'],
]) test(`refuses ${name}`,()=>{const f=fixture();mutate(f);assert.throws(()=>api.verifyQuietPoint(f.expected,f.observed),{message:reason});});
test('rollback refuses schema9 alone without exact supported source pair',()=>{assert.equal(api.rollbackCompatible({web:'a'.repeat(40),backend:'b'.repeat(40)}, {fromSchema:9,toSchema:9,appRollback:true,supportedSourcePairs:[{web:'c'.repeat(40),backend:'b'.repeat(40)}]},true),false);});
test('rollback requires actual unchanged product and migrations',()=>{assert.equal(api.rollbackCompatible({web:'a'.repeat(40),backend:'b'.repeat(40)}, {fromSchema:9,toSchema:9,appRollback:true,supportedSourcePairs:[{web:'a'.repeat(40),backend:'b'.repeat(40)}]},false),false);});
