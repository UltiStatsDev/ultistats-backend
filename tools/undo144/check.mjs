import fs from 'node:fs';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import {execFileSync} from 'node:child_process';
import assert from 'node:assert/strict';
import openapiTS, {astToString} from 'openapi-typescript';
import Ajv2020 from 'ajv/dist/2020.js';
import addFormats from 'ajv-formats';

const dir = path.resolve(process.argv[2] || '../../build/contracts/undo144');
const api = JSON.parse(fs.readFileSync(path.join(dir, 'openapi.json')));
const fixtures = JSON.parse(fs.readFileSync(path.join(dir, 'fixtures.json')));
const ajv = new Ajv2020({strict: false, allErrors: true});
addFormats(ajv);
ajv.addFormat('int32', {type: 'number', validate: Number.isInteger});
ajv.addFormat('int64', {type: 'number', validate: Number.isInteger});
const validate = name => ajv.compile({$ref: `#/components/schemas/${name}`, components: api.components});
const validators = Object.fromEntries(['UndoCommandResponse','UndoCommandProblem','ProblemDetail','UndoEventRequest'].map(n => [n,validate(n)]));
for (const fixture of fixtures) {
  assert.equal(validators[fixture.schema](fixture.payload), true, JSON.stringify({fixture, errors:validators[fixture.schema].errors}));
}
const applied = fixtures.find(f => f.schema==='UndoCommandResponse' && f.payload.outcome==='APPLIED').payload;
const rejected = fixtures.find(f => f.schema==='UndoCommandResponse' && f.payload.outcome==='REJECTED').payload;
for (const fixture of [applied,rejected]) {
  for (const field of ['rejection','removedEventId','removedSequenceNumber']) {
    const missing = {...fixture}; delete missing[field];
    assert.equal(validators.UndoCommandResponse(missing),false,`${field} must be present even when null`);
  }
}
const envelope = fixtures.find(f => f.schema==='UndoEventRequest').payload;
for (const field of ['commandId','expectedLastEventId','expectedLogRevision']) {
  const missing={...envelope};delete missing[field];
  assert.equal(validators.UndoEventRequest(missing),false);
  assert.equal(validators.UndoEventRequest({...envelope,[field]:null}),false);
}
assert.equal(validators.UndoEventRequest({...envelope,expectedLogRevision:-1}),false);
fs.writeFileSync(path.join(dir,'generated.ts'),astToString(await openapiTS(api)));
const consumer = `import type {components} from './generated';
type S = components['schemas'];
type Equal<A,B> = (<T>()=>T extends A?1:2) extends (<T>()=>T extends B?1:2)?true:false;
type Check<T extends true> = T;
type NullableId=Check<Equal<S['UndoCommandResponse']['removedEventId'],string|null>>;
type NullableSequence=Check<Equal<S['UndoCommandResponse']['removedSequenceNumber'],number|null>>;
type NullableRejection=Check<Equal<S['UndoCommandResponse']['rejection'],S['UndoCommandRejection']|null>>;
${fixtures.map((f,i)=>`const actual${i}: S['${f.schema}']${f.schema==='ProblemDetail'?' & {code:string}':''} = ${JSON.stringify(f.payload)};`).join('\n')}
// @ts-expect-error Nullable result fields are required, even when null.
const missingResult: S['UndoCommandResponse'] = {matchId:'id',commandId:'id',expectedLastEventId:'id',expectedLogRevision:1,outcome:'APPLIED',resultLogRevision:2};
// @ts-expect-error An undo requires a stable identity.
const missingIdentity: S['UndoEventRequest'] = {expectedLastEventId:'id',expectedLogRevision:1};
// @ts-expect-error Explicit null is not an observed revision.
const nullRevision: S['UndoEventRequest'] = {commandId:'id',expectedLastEventId:'id',expectedLogRevision:null};
`;
fs.writeFileSync(path.join(dir,'consumer.ts'),consumer);
execFileSync(process.execPath,[fileURLToPath(new URL('node_modules/typescript/bin/tsc',import.meta.url)),'--ignoreConfig','--strict','--noEmit',path.join(dir,'consumer.ts')],{stdio:'inherit'});
console.log(`Raw OpenAPI AJV validation and generated TypeScript strict compile PASS: ${fixtures.length} real HTTP fixtures; required/null negative checks PASS.`);
