import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, writeFileSync, chmodSync, readFileSync, symlinkSync, rmSync, statSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
const api = await import('../helper-contract.mjs').catch(()=>({}));
function temp(t){const p=mkdtempSync(join(tmpdir(),'backend161-'));t.after(()=>rmSync(p,{recursive:true,force:true}));return p;}
test('private JSON rejects readable-by-group settings and symlinks',t=>{const p=temp(t),file=join(p,'input.json');writeFileSync(file,'{}',{mode:0o644});assert.throws(()=>api.readPrivateJson(file),{message:'PRIVATE_FILE_REQUIRED'});chmodSync(file,0o600);symlinkSync(file,join(p,'link'));assert.throws(()=>api.readPrivateJson(join(p,'link')),{message:'PRIVATE_FILE_REQUIRED'});assert.deepEqual(api.readPrivateJson(file),{});});
test('atomic result refuses overwrite and is private',t=>{const p=temp(t),file=join(p,'result');api.writePrivateJson(file,{safe:true});assert.equal(statSync(file).mode&0o777,0o600);assert.throws(()=>api.writePrivateJson(file,{safe:false}));assert.equal(JSON.parse(readFileSync(file)).safe,true);});
test('unknown request keys cannot smuggle maintenance boolean',()=>{assert.throws(()=>api.validateRequest({protocolVersion:1,operation:'inspect',transactionId:'transaction-001',installationManifestPath:'/a',targetRecordPath:'/b',targetRecordSha256:'a'.repeat(64),evidenceDirectory:'/c',maintenance:true}),{message:'INVALID_REQUEST'});});
test('acquire requires caller private fence path',()=>{assert.throws(()=>api.validateRequest({protocolVersion:1,operation:'fence-acquire',transactionId:'transaction-001',installationManifestPath:'/a',targetRecordPath:'/b',targetRecordSha256:'a'.repeat(64),evidenceDirectory:'/c'}),{message:'INVALID_REQUEST'});});
test('sanitizer never returns command stderr or secret values',()=>{assert.equal(api.safeReason(new Error('password=SUPERSECRET stderr private DB')), 'HELPER_FAILED');});
test('Flyway checksum normalizes line endings and matches known CRC32',()=>{assert.equal(api.flywayChecksum('123456789\r\n'),-873187034);});

test('malformed CLI request cannot produce an invalid or secret-bearing result envelope',async t=>{
 const cli=await import('../backend-release.mjs');const p=temp(t),request=join(p,'request.json'),result=join(p,'result.json');
 writeFileSync(request,JSON.stringify({operation:'SECRET_OPERATION',transactionId:'SECRET_TRANSACTION'}),{mode:0o600});
 const status=await cli.main(['inspect','--request',request,'--result',result]);
 assert.equal(status,64);assert.equal((await import('node:fs')).existsSync(result),false);
});
