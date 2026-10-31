// Extend integration.mjs canonical end states with a fresh live fixture for native OBS QA.
import {readFileSync,writeFileSync} from 'node:fs';
import assert from 'node:assert/strict';
const [command,input,output]=process.argv.slice(2);
assert.ok(input,'Usage: fixtures.mjs create <integration-result.json> <fixtures.json> | point <fixtures.json> | correct-finished <fixtures.json>');
const state=JSON.parse(readFileSync(input,'utf8')),base=state.base;
assert.equal(base,'http://127.0.0.1:19835','This helper is pinned to the isolated OBS135 runtime, never the human API');
async function req(path,method='GET',body){const r=await fetch(base+path,{method,headers:{'Content-Type':'application/json'},body:body===undefined?undefined:JSON.stringify(body)});
 const text=await r.text();assert.ok(r.ok,`${r.status} ${text}`);return text?JSON.parse(text):null;}
if(command==='create') {
 assert.ok(output);const live=await req('/api/v1/matches','POST',{teamIds:state.teams,playersPerTeam:2});
 await req(`/api/v1/matches/${live.id}/start`,'POST',{timestamp:new Date(Date.now()-3792000).toISOString()});
 const fixtures={base,teams:state.teams,live:live.id,planned:state.otherMatchId,finished:state.matchId,finishedDuration:'63:12',finishedOriginalScore:'0:2',finishedProfile:state.profile};
 for(const key of ['live','planned','finished'])fixtures[key+'Source']=`${base}/obs-scoreboard.html?matchId=${fixtures[key]}`;
 fixtures.eventLog=`${base}/obs-battle-log.html?matchId=${live.id}`;fixtures.admin=base+'/obs-admin.html';
 writeFileSync(output,JSON.stringify(fixtures,null,2));console.log(JSON.stringify(fixtures,null,2));
} else if(command==='point') {
 const m=await req(`/api/v1/matches/${state.live}`),log=await req(`/api/v2/matches/${state.live}/events`);
 const a=m.teams[0].participants,b=m.teams[1].participants;
 let now=Math.max(Date.now(),...log.events.map(e=>Date.parse(e.occurredAt)+1000));
 for(const [i,type,fields] of [[0,'PULL',{participantId:a[0].participantId}],[1,'PICKUP',{participantId:b[0].participantId}],[2,'GOAL',{fromParticipantId:b[0].participantId,toParticipantId:b[1].participantId}]])
  await req(`/api/v1/matches/${state.live}/events`,'POST',{type,occurredAt:new Date(now+i*1000).toISOString(),...fields});
 console.log(await req(`/api/v1/obs/scoreboard/${state.live}`));
} else if(command==='correct-finished') {
 const m=await req(`/api/v1/matches/${state.finished}`),log=await req(`/api/v2/matches/${state.finished}/events`),goal=log.events.at(-1);
 assert.equal(goal.type,'GOAL');const team=m.teams.find(t=>!t.participants.some(p=>p.participantId===goal.toParticipantId));
 await req(`/api/v1/matches/${state.finished}/events/${goal.id}`,'PATCH',{type:'GOAL',fromParticipantId:team.participants[0].participantId,toParticipantId:team.participants[1].participantId});
 console.log(await req(`/api/v1/obs/scoreboard/${state.finished}`));
} else throw Error('Unknown command');
