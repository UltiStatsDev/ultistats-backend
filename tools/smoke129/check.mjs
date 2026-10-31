import fs from 'node:fs';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import {execFileSync} from 'node:child_process';
import openapiTS, {astToString} from 'openapi-typescript';

const input = path.resolve(process.argv[2]);
const output = path.dirname(input);
const api = JSON.parse(fs.readFileSync(input, 'utf8'));
// Generate from the complete raw document: no schema normalization.
fs.writeFileSync(path.join(output, 'generated.ts'), astToString(await openapiTS(api)));
fs.writeFileSync(path.join(output, 'consumer.ts'), `
import type {components} from './generated';
type S = components['schemas'];
type Equal<A, B> = (<T>() => T extends A ? 1 : 2) extends (<T>() => T extends B ? 1 : 2) ? true : false;
type Check<T extends true> = T;
type PatchName = Check<Equal<S['PatchPlayerRequest']['firstName'], string | undefined>>;
type PatchCity = Check<Equal<S['PatchTeamRequest']['city'], string | null | undefined>>;
type PatchTime = Check<Equal<S['PatchMatchRequest']['plannedStartTimestamp'], string | null | undefined>>;
type ResponseName = Check<Equal<S['MatchParticipantResponse']['firstName'], string | null>>;
type StatisticsNumber = Check<Equal<S['ParticipantStatisticsResponse']['number'], number | null>>;
const player: S['PatchPlayerRequest'] = {firstName: 'Changed'};
const team: S['PatchTeamRequest'] = {city: null};
const match: S['PatchMatchRequest'] = {plannedStartTimestamp: null};
const membership: S['UpsertTeamPlayerRequest'] = {number: null};
const legacy: S['UpdateMatchRequest'] = {teamIds: null, playersPerTeam: null, plannedStartTimestamp: null};
const unknown: S['MatchParticipantResponse'] = {participantId: 'id', kind: 'UNKNOWN', unknownSlot: 1, firstName: null, lastName: null, displayName: 'Unknown', number: null, photoUrl: null};
// @ts-expect-error Explicit null is forbidden for PATCH names.
const invalidPatch: S['PatchPlayerRequest'] = {firstName: null};
// @ts-expect-error ALWAYS fields must remain present, even when null.
const missingName: S['MatchParticipantResponse'] = {participantId: 'id', kind: 'UNKNOWN', unknownSlot: 1, lastName: null, displayName: 'Unknown', number: null, photoUrl: null};
// @ts-expect-error Nonnullable create fields remain required.
const missingTeams: S['CreateMatchRequest'] = {playersPerTeam: 2};
`);
execFileSync(process.execPath, [fileURLToPath(new URL('node_modules/typescript/bin/tsc', import.meta.url)),
  '--ignoreConfig', '--noEmit', '--strict', path.join(output, 'consumer.ts')], {stdio: 'inherit'});
console.log('Raw OpenAPI generated and nullable consumer contract compiled successfully.');
