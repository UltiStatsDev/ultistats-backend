import fs from 'node:fs';
import assert from 'node:assert/strict';
import {execFileSync} from 'node:child_process';
import openapiTS, {astToString} from 'openapi-typescript';
import Ajv from 'ajv/dist/2020.js';
import addFormats from 'ajv-formats';
const raw = fs.readFileSync(process.argv[2], 'utf8');
const api = JSON.parse(raw);
const ajv = new Ajv({strict:false, allErrors:true});
addFormats(ajv);
ajv.addSchema(api, 'api');
const uuid = '11111111-1111-1111-1111-111111111111';
const groups = [
  [['INCOMPLETE_PASS','PULL','BRICK','PICKUP'], {participantId:uuid}],
  [['PASS','GOAL','BLOCK','BLOCK_MARKER','BLOCK_FIELD','INTERCEPTION','CALLAHAN'], {fromParticipantId:uuid,toParticipantId:uuid}],
  [['TIMEOUT_START','TIMEOUT_END'], {teamId:uuid}],
  [['HALFTIME_START','HALFTIME_END'], {}],
];
let checks = 0;
function check(name, value, expected) {
  const validate = ajv.compile({$ref:'api#/components/schemas/'+name});
  assert.equal(validate(value), expected, name+' '+JSON.stringify(value)+' '+JSON.stringify(validate.errors));
  checks++;
}
const runtimeFixtures = JSON.parse(fs.readFileSync(process.argv[3] ?? new URL('runtime-fixtures.json', 'file://'+fs.realpathSync(process.argv[2]))));
for (const fixture of runtimeFixtures) check(fixture.schema, fixture.payload, true);
for (const [types, fields] of groups) for (const type of types) {
  const create = {type, occurredAt:'2026-07-28T12:30:00Z', ...fields};
  for (const name of ['CreateEventRequest','BatchEventRequest']) {
    const valid = name === 'BatchEventRequest' ? {...create,eventId:uuid} : create;
    check(name,valid,true);
    check(name,{...valid,type:'INVALID'},false);
    check(name,{...valid,occurredAt:'invalid'},false);
    check(name,{...valid,eventId:'invalid'},false);
    const missing = {...valid}; delete missing.occurredAt;
    check(name,missing,false);
    for (const field of Object.keys(fields)) {const bad={...valid};delete bad[field];check(name,bad,false);}
  }
  check('BatchEventRequest',create,false);
  check('BatchEventRequest',{...create,eventId:null},false);
  const read = {...create,id:uuid,sequenceNumber:1};
  check('EventResponse',read,true);
  check('EventResponse',{...read,type:'INVALID'},false);
  for (const field of Object.keys(fields)) {const bad={...read};delete bad[field];check('EventResponse',bad,false);}
  const noId={...read};delete noId.id;check('EventResponse',noId,false);
  if (!type.startsWith('HALFTIME')) {
    check('UpdateEventRequest',{type,...fields},true);
    check('UpdateEventRequest',{type:'INVALID',...fields},false);
    for (const field of Object.keys(fields)) check('UpdateEventRequest',{type,...fields,[field]:'invalid'},false);
    if ('fromParticipantId' in fields) {
      check('UpdateEventRequest',{type},true);
      check('UpdateEventRequest',{type,fromParticipantId:null,toParticipantId:null},true);
    } else check('UpdateEventRequest',{type},false);
  } else check('UpdateEventRequest',{type},false);
}
check('CreateEventBatchRequest',{expectedLastSequenceNumber:0,expectedLogRevision:0,events:[{type:'HALFTIME_START',occurredAt:'2026-07-28T12:30:00Z',eventId:uuid}]},true);
for (const events of [[], Array(101).fill({type:'HALFTIME_START',occurredAt:'2026-07-28T12:30:00Z',eventId:uuid})])
  check('CreateEventBatchRequest',{expectedLastSequenceNumber:0,expectedLogRevision:0,events},false);
// The generator mutates discriminator schemas. Give it a fresh parse only
// after validating untouched raw JSON; its repairs must never reach AJV.
fs.writeFileSync('generated.ts', astToString(await openapiTS(JSON.parse(raw))));
const semantic = ['import type {components} from "./generated";'];
for (const [i, fixture] of runtimeFixtures.entries()) {
  const mapping = api.components.schemas[fixture.schema].discriminator?.mapping;
  const leaf = mapping?.[fixture.payload.type]?.split('/').at(-1);
  for (const name of new Set([fixture.schema, leaf].filter(Boolean))) {
    semantic.push(`const fixture${i}${name} = ${JSON.stringify(fixture.payload)} satisfies components["schemas"]["${name}"];`);
  }
}
for (const [types, fields] of groups) for (const type of types) {
  const batch = {type, occurredAt:'2026-07-28T12:30:00Z', eventId:uuid, ...fields};
  semantic.push(`const batch${semantic.length} = ${JSON.stringify(batch)} satisfies components["schemas"]["BatchEventRequest"];`);
  const noId = {...batch}; delete noId.eventId;
  semantic.push('// @ts-expect-error v2 batch requires eventId');
  semantic.push(`const noEventId${semantic.length} = ${JSON.stringify(noId)} satisfies components["schemas"]["BatchEventRequest"];`);
}
for (const base of ['CreateEventRequest','UpdateEventRequest','EventResponse']) {
  for (const ref of api.components.schemas[base].oneOf) {
    const name = ref.$ref.split('/').at(-1);
    const schema = api.components.schemas[name];
    assert.equal(schema.properties.type.type, 'string', name);
    if (schema.properties.type.example !== undefined)
      assert.ok(schema.properties.type.enum.includes(schema.properties.type.example), name+' example outside enum');
    for (const invalid of [{}, 'INVALID', 1, null]) {
      semantic.push('// @ts-expect-error discriminator rejects invalid values');
      semantic.push(`const invalid${semantic.length} = ${JSON.stringify(invalid)} satisfies components["schemas"]["${name}"]["type"];`);
    }
    const valid = runtimeFixtures.find(f => f.schema === base && mappingLeaf(base, f.payload.type) === name).payload;
    for (const field of schema.required) {
      const bad = {...valid}; delete bad[field];
      semantic.push('// @ts-expect-error required field cannot be omitted');
      semantic.push(`const missing${semantic.length} = ${JSON.stringify(bad)} satisfies components["schemas"]["${name}"];`);
    }
  }
}
function mappingLeaf(base, type) {
  return api.components.schemas[base].discriminator.mapping[type].split('/').at(-1);
}
fs.writeFileSync('semantic.ts', semantic.join('\n'));
execFileSync(process.execPath, ['node_modules/typescript/bin/tsc', '--ignoreConfig', '--noEmit', '--strict', 'generated.ts', 'semantic.ts'], {stdio:'inherit'});
console.log('PASS: raw generation, strict TypeScript, '+checks+' fixture validations');
