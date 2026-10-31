import {Feed,defaults} from './feed.mjs';
import {Renderer} from './renderer.mjs';
import {imageAccent} from './colors.mjs';
import {Reconciler,readJson,watchConfig,watchMatch} from './client.mjs';
const params=new URLSearchParams(location.search),matchId=params.get('matchId');
const preview=params.get('preview')==='1' && window.parent!==window;
const feed=new Feed(),renderer=new Renderer(document.getElementById('feed'));
let config=defaults,match={teams:[]},autoColors={},stopMatch=()=>{},testUntil=0,started=false;
const status=message=>{if(preview)parent.postMessage({type:'obs-status',message},location.origin);};
function render() {
 const rows=feed.rows();
 const test=preview&&Date.now()<testUntil?[{event:{id:'preview-test',type:'GOAL',fromParticipantId:'preview-a',toParticipantId:'preview-b'}}]:[];
 const displayMatch=test.length?{teams:[...match.teams,{teamId:'preview-team',participants:[
  {participantId:'preview-a',kind:'PLAYER',number:22,lastName:'Сартаков',firstName:'Михаил'},
  {participantId:'preview-b',kind:'PLAYER',number:44,lastName:'Братчиков',firstName:'Денис'}]}]}:match;
 renderer.render([...test,...rows].slice(0,config.maxRows),displayMatch,config,autoColors);
}
const details=new Reconciler(()=>{
 if(!/^[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}$/i.test(matchId??''))throw Error('Укажите UUID матча');
 return readJson(`/api/v1/matches/${encodeURIComponent(matchId)}`);
},value=>{
 match=value;
 if(!started){started=true;stopMatch=watchMatch(matchId,snapshot=>{feed.reconcile(snapshot);render();},status,()=>feed.rows().length>0);}
 for(const team of match.teams) void imageAccent(team.photoUrl).then(color=>{autoColors[team.teamId]=color;render();});
 render();
},status);
let loaded=false;
const stopConfig=watchConfig(value=>{config=value;feed.configure(value);render();if(!loaded){loaded=true;void details.request();}},status);
if(preview) addEventListener('message',event=>{
 if(event.origin!==location.origin||event.source!==parent)return;
 if(event.data?.type==='obs-test'){testUntil=Date.now()+config.ttlSeconds*1000;render();}
});
const tick=setInterval(render,100);
addEventListener('pagehide',()=>{clearInterval(tick);stopMatch();stopConfig();details.close();});
