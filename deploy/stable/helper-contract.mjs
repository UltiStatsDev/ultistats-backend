import { createHash, randomUUID } from 'node:crypto';
import { readFileSync, writeFileSync, lstatSync, realpathSync, linkSync, unlinkSync } from 'node:fs';
import { dirname, isAbsolute, resolve, join } from 'node:path';
export const BASELINE = '24704e11143a0bf919559a41324603f936ae03cf';
export const OPERATIONS = ['inspect','fence-acquire','fence-recheck','fence-release','backup','postflight','rollback-plan','rollback-plan'];
export const hash = value => createHash('sha256').update(value).digest('hex');
export const safeReason = error => /^[A-Z][A-Z0-9_]{2,79}$/.test(error?.message ?? '') ? error.message : 'HELPER_FAILED';
export function check(condition, reason) { if (!condition) throw new Error(reason); }
export function privateDirectory(path) {
  check(isAbsolute(path ?? '') && resolve(path) === path, 'PRIVATE_DIRECTORY_REQUIRED');
  const s=lstatSync(path);
  check(s.isDirectory() && !s.isSymbolicLink() && s.uid === process.getuid?.() && !(s.mode & 0o077) && realpathSync(path) === path, 'PRIVATE_DIRECTORY_REQUIRED');
}
export function readPrivateJson(path) {
  check(isAbsolute(path ?? '') && resolve(path) === path, 'PRIVATE_FILE_REQUIRED');
  const s=lstatSync(path);
  check(s.isFile() && !s.isSymbolicLink() && s.uid === process.getuid?.() && !(s.mode&0o077) && s.size<=4*1024*1024 && realpathSync(path)===path,'PRIVATE_FILE_REQUIRED');
  return JSON.parse(readFileSync(path,'utf8'));
}
export function writePrivateJson(path,value) {
  privateDirectory(dirname(path));
  const temp=join(dirname(path),`.helper-${randomUUID()}.tmp`);
  try {
    writeFileSync(temp,JSON.stringify(value)+'\n',{mode:0o600,flag:'wx'});
    // Atomic publish without replacing an existing result, receipt or symlink.
    linkSync(temp,path);
  } finally { try {unlinkSync(temp);} catch {} }
}
export function validateRequest(r) {
  const required=['protocolVersion','operation','transactionId','installationManifestPath','targetRecordPath','targetRecordSha256','evidenceDirectory'];
  const optional=['fencePath','backupPath','previousManifestPath','rollbackPlanPath'];
  check(r && typeof r==='object' && !Array.isArray(r) && required.every(k=>Object.hasOwn(r,k)) && Object.keys(r).every(k=>[...required,...optional].includes(k)) && r.protocolVersion===1 && OPERATIONS.includes(r.operation) && /^[A-Za-z0-9-]{8,80}$/.test(r.transactionId) && /^[a-f0-9]{64}$/.test(r.targetRecordSha256),'INVALID_REQUEST');
  for(const k of [...required,...optional].filter(k=>k.endsWith('Path')||k==='evidenceDirectory')) if(r[k]!==undefined)check(typeof r[k]==='string'&&isAbsolute(r[k])&&resolve(r[k])===r[k],'INVALID_REQUEST');
  if(['fence-acquire','fence-recheck','fence-release','backup','postflight','rollback-plan'].includes(r.operation))check(!!r.fencePath,'INVALID_REQUEST');
  if(['backup','postflight','fence-release','rollback-plan'].includes(r.operation))check(!!r.backupPath,'INVALID_REQUEST');
  if(r.operation==='rollback-plan')check(!!r.previousManifestPath,'INVALID_REQUEST');
  if(r.rollbackPlanPath)check(['postflight','fence-release'].includes(r.operation),'INVALID_REQUEST');
  check(new Set([r.installationManifestPath,r.targetRecordPath,r.fencePath,r.previousManifestPath].filter(Boolean)).size===[r.installationManifestPath,r.targetRecordPath,r.fencePath,r.previousManifestPath].filter(Boolean).length,'INVALID_REQUEST');
  return r;
}
export function flywayChecksum(text) {
  let crc=0xffffffff;
  const bytes=Buffer.from(text.replace(/^\uFEFF/,'').split(/\r\n|\r|\n/).join(''),'utf8');
  for(const byte of bytes){crc^=byte;for(let n=0;n<8;n++)crc=(crc>>>1)^((crc&1)?0xedb88320:0);}
  return (crc^0xffffffff)|0;
}
export function validateManifest(m,{bootstrap=false}={}) {
  const b=m?.backend,h=b?.helper,i=b?.ingress;
  const sha=x=>typeof x==='string'&&/^[a-f0-9]{40}$/.test(x);
  const digest=x=>typeof x==='string'&&/^[a-f0-9]{64}$/.test(x);
  const id=x=>digest(x),image=x=>typeof x==='string'&&/^sha256:[a-f0-9]{64}$/.test(x);
  check(m?.schemaVersion===1&&m.mode==='stable'&&/^[a-f0-9]{12}$/.test(m.instanceId)&&b?.ownership==='managed','UNSUPPORTED_INSTALLATION');
  check(b.project===`ultistats-stable-${m.instanceId}`&&sha(b.revision)&&image(b.imageId)&&m.release&&sha(m.release.webSource)&&m.release.backendSource===b.revision&&typeof m.release.version==='string','INVALID_MANIFEST');
  check(h?.schemaVersion===1&&h.productBaseline===BASELINE&&id(h.databaseContainerId)&&Array.isArray(h.writerContainerIds)&&h.writerContainerIds.length>0&&h.writerContainerIds.length<=8&&h.writerContainerIds.every(id)&&new Set(h.writerContainerIds).size===h.writerContainerIds.length,'INVALID_MANIFEST');
  check(h.uploadsMount==='/app/uploads'&&h.databaseMount==='/var/lib/postgresql/data'&&/^[a-zA-Z_][a-zA-Z0-9_]{0,62}$/.test(h.databaseName)&&/^[a-zA-Z_][a-zA-Z0-9_]{0,62}$/.test(h.databaseUser)&&Array.isArray(h.settingsPaths)&&h.settingsPaths.every(p=>isAbsolute(p)&&resolve(p)===p),'INVALID_MANIFEST');
  check(typeof h.sourceCheckout==='string'&&isAbsolute(h.sourceCheckout)&&typeof h.databaseNetwork==='string'&&h.databaseNetwork.length>0&&typeof b.databaseVolume==='string'&&typeof b.uploadsVolume==='string'&&b.databaseVolume!==b.uploadsVolume,'INVALID_MANIFEST');
  check(i&&id(i.containerId)&&image(i.imageId)&&i.backendNetwork===h.databaseNetwork&&typeof i.edgeNetwork==='string'&&i.edgeNetwork!==i.backendNetwork&&isAbsolute(i.configPath??''),'INVALID_MANIFEST');
  check((bootstrap&&h.migrationFingerprint===null)||digest(h.migrationFingerprint),'MIGRATION_UNPROVEN');
  check((bootstrap&&h.jarSha256===null)||digest(h.jarSha256),'RUNTIME_UNPROVEN');
  const origin=new URL(b.origin);check(origin.origin===b.origin&&origin.protocol==='http:'&&['127.0.0.1','localhost','[::1]'].includes(origin.hostname),'INVALID_MANIFEST');
  return m;
}
