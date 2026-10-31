import {watchScoreboard} from './scoreboard-client.mjs';
import {ClockAnchor} from './scoreboard-model.mjs';
import {renderScoreboard} from './scoreboard-renderer.mjs';
import {imageAccent} from './colors.mjs';
const params=new URLSearchParams(location.search),matchId=params.get('matchId'),preview=params.get('preview')==='1';
const root=document.getElementById('scoreboard'),stage=document.getElementById('stage'),clock=new ClockAnchor();
let snapshot=null,draft=null,clockNode=null,disposed=false,viewKey=null;const auto={},photos=new Map();
const status=message=>{if(preview&&parent!==window)parent.postMessage({type:'scoreboard-status',message},location.origin);};
function scale(){stage.style.transform=`scale(${Math.min(innerWidth/1000,innerHeight/160)})`;}
function render(){if(!snapshot||disposed)return;clockNode=renderScoreboard(root,snapshot,draft??snapshot.profile,auto);clockNode.textContent=clock.label();}
function accept(value) {
 snapshot=value;clock.accept(value);
 const key=JSON.stringify([value.teams,value.profile]);
 if(key!==viewKey){viewKey=key;render();}
 for(const team of value.teams)if(photos.get(team.teamId)!==team.photoUrl){
  photos.set(team.teamId,team.photoUrl);const photo=team.photoUrl;
  void imageAccent(photo).then(color=>{if(disposed||photos.get(team.teamId)!==photo)return;auto[team.teamId]=color;render();});
 }
}
const message=event=>{
 if(!preview||event.origin!==location.origin||event.source!==parent||event.data?.type!=='scoreboard-draft')return;
 const p=event.data.profile;
 if(!p||typeof p.swapped!=='boolean'||!p.shortNames||!p.teamColors)return;
 draft=p;render();
};
addEventListener('message',message);addEventListener('resize',scale);scale();
let stop=()=>{};
if(/^[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}$/i.test(matchId??''))stop=watchScoreboard(matchId,accept,status);
else status('Введите корректный UUID матча');
const tick=setInterval(()=>{if(clockNode)clockNode.textContent=clock.label();},1000);
addEventListener('pagehide',()=>{disposed=true;stop();clearInterval(tick);removeEventListener('message',message);removeEventListener('resize',scale);},{once:true});
