import {defaults} from '../../../main/resources/static/obs/feed.mjs';
export const ids = {
  match:'10000000-0000-4000-8000-000000000001', team:'10000000-0000-4000-8000-000000000002',
  otherTeam:'10000000-0000-4000-8000-000000000003', known:'10000000-0000-4000-8000-000000000004',
  opposing:'10000000-0000-4000-8000-000000000005', legacy:'10000000-0000-4000-8000-000000000006',
  first:'10000000-0000-4000-8000-000000000007', second:'10000000-0000-4000-8000-000000000008',
  event:'10000000-0000-4000-8000-000000000009', missing:'10000000-0000-4000-8000-000000000010',
};
export const instant = ms => new Date(Date.parse('2026-01-01T00:00:00Z')+ms).toISOString();
export function temporarySnapshot() {
  return {
    matchId:ids.match, serverNow:instant(0), logRevision:1,
    config:{...defaults,maxRows:1,ttlSeconds:45,teamColors:{[ids.team]:'#ffcc00',[ids.otherTeam]:'#00ccff'}},
    teams:[{teamId:ids.team,teamName:'A',score:0},{teamId:ids.otherTeam,teamName:'B',score:0}],
    participants:[
      {participantId:ids.known,teamId:ids.team,kind:'PLAYER',number:7,firstName:'Иван',lastName:'Иванов'},
      {participantId:ids.opposing,teamId:ids.otherTeam,kind:'PLAYER',number:9,firstName:'Петр',lastName:'Петров'},
      {participantId:ids.legacy,teamId:ids.team,kind:'UNKNOWN',number:88,firstName:null,lastName:null},
    ],
    temporarySlots:[ids.first,ids.second].map((slotId,index)=>({slotId,pointId:ids.match,teamId:ids.team,number:index+1,resolvedParticipantId:null})),
    events:[{id:ids.event,type:'GOAL',sequenceNumber:1,occurredAt:instant(0),fromParticipantId:ids.first,toParticipantId:ids.second}],
    retractions:[],
  };
}
