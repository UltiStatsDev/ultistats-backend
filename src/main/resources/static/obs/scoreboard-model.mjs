export const defaultProfile=()=>({shortNames:{},teamColors:{},swapped:false});
export function elapsedLabel(snapshot,serverNowMs) {
 if(snapshot.status==='PLANNED'||!snapshot.startedAt)return '00:00';
 const start=Date.parse(snapshot.startedAt);
 const end=snapshot.status==='FINISHED'?Date.parse(snapshot.endedAt):serverNowMs;
 const seconds=Number.isFinite(end-start)?Math.max(0,Math.floor((end-start)/1000)):0;
 return `${String(Math.floor(seconds/60)).padStart(2,'0')}:${String(seconds%60).padStart(2,'0')}`;
}
export class ClockAnchor {
 constructor(now=()=>performance.now()){this.now=now;}
 accept(snapshot){this.snapshot=snapshot;this.server=Date.parse(snapshot.serverNow);this.anchor=this.now();}
 label(){return this.snapshot?elapsedLabel(this.snapshot,this.server+Math.max(0,this.now()-this.anchor)):'00:00';}
}
export function scoreboardTeams(snapshot,profile=snapshot.profile) {
 const teams=snapshot.teams.map(team=>({...team,teamName:profile.shortNames[team.teamId]??team.teamName,
  color:profile.teamColors[team.teamId]}));
 return profile.swapped?teams.reverse():teams;
}
export function validateSnapshot(snapshot,matchId) {
 if(snapshot?.matchId!==matchId||!Array.isArray(snapshot.teams)||snapshot.teams.length!==2||
  !['PLANNED','IN_PROGRESS','FINISHED'].includes(snapshot.status)||!Number.isFinite(Date.parse(snapshot.serverNow))||
  (snapshot.status!=='PLANNED'&&!Number.isFinite(Date.parse(snapshot.startedAt)))||
  (snapshot.status==='FINISHED'&&!Number.isFinite(Date.parse(snapshot.endedAt)))||
  snapshot.teams.some(t=>typeof t.teamName!=='string'||typeof t.teamId!=='string'||!Number.isInteger(t.score)||t.score<0)||
  !snapshot.profile||typeof snapshot.profile.swapped!=='boolean'||!snapshot.profile.shortNames||!snapshot.profile.teamColors)
  throw Error('Некорректные данные табло');
 return snapshot;
}
