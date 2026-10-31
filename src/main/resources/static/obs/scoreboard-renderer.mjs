import {readableText,teamAccent} from './colors.mjs';
import {scoreboardTeams} from './scoreboard-model.mjs';
const element=(tag,className,text)=>{const node=document.createElement(tag);node.className=className;if(text!==undefined)node.textContent=text;return node;};
export function renderScoreboard(root,snapshot,profile=snapshot.profile,auto={}) {
 const teams=scoreboardTeams(snapshot,profile);
 const plate=(team,side)=>{
  const node=element('div',`scoreboard-team ${side}`);node.dataset.teamId=team.teamId;
  const color=teamAccent(team.teamId,profile.teamColors,auto);
  node.style.backgroundColor=color;node.style.color=readableText(color);
  const logo=element('div','scoreboard-logo');
  const initials=element('span','scoreboard-initials',team.teamName.trim().split(/\s+/u).slice(0,2).map(s=>Array.from(s)[0]??'').join('').toLocaleUpperCase());
  logo.append(initials);
  if(team.photoUrl) {
   const url=new URL(team.photoUrl,location.href);
   if(['http:','https:'].includes(url.protocol)) {
    const image=element('img','');image.alt='';image.src=url.href;
    image.addEventListener('load',()=>{initials.hidden=true;});
    image.addEventListener('error',()=>{image.remove();initials.hidden=false;});logo.append(image);
   }
  }
  const name=element('span','scoreboard-name',team.teamName);name.title=team.teamName;
  node.append(logo,name);return node;
 };
 const bar=element('div','scoreboard-bar');bar.append(plate(teams[0],'left'));
 const middle=element('div','scoreboard-middle');middle.append(element('span','scoreboard-score',`${teams[0].score} : ${teams[1].score}`));
 bar.append(middle,plate(teams[1],'right'));
 const clock=element('div','scoreboard-clock','00:00');clock.setAttribute('aria-label','Время матча');
 root.replaceChildren(bar,clock);root.hidden=false;
 return clock;
}
