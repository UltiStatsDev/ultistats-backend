import {resolveRow} from './feed.mjs';
import {teamAccent,readableText} from './colors.mjs';
const labels={GOAL:'Гол',BLOCK:'Блок',INTERCEPTION:'Перехват',CALLAHAN:'Кэллахан'};
const paths={GOAL:'M4 12h14m-5-5 5 5-5 5M20 4v16',BLOCK:'M12 3 4 7v5c0 5 8 9 8 9s8-4 8-9V7z',
 INTERCEPTION:'m5 5 14 14M19 5 5 19m0-6v6h6m2-14h6v6',CALLAHAN:'m13 2-9 12h7l-1 8 10-13h-7z'};
export class Renderer {
 constructor(root) {this.root=root;this.nodes=new Map();}
 render(rows,match,config,autoColors={}) {
  this.root.dataset.corner=config.corner;
  const ids=new Set(rows.map(row=>row.event.id));
  for(const [id,node] of this.nodes) if(!ids.has(id)) {
   this.nodes.delete(id);node.classList.add('leaving');setTimeout(()=>node.remove(),180);
  }
  for(const entry of rows) {
   const row=resolveRow(entry.event,match);if(!row)continue;
   let node=this.nodes.get(row.id);
   if(!node) {node=document.createElement('div');node.className='feed-row';node.dataset.eventId=row.id;this.nodes.set(row.id,node);}
   node.style.backgroundColor=config.backgroundColor;
   node.style.color=readableText(config.backgroundColor,config.textColor);
   const side=(player,which)=>{
    const span=document.createElement('span');span.className=`player ${which}`;
    const accent=teamAccent(player.teamId,config.teamColors,autoColors,config.backgroundColor);
    span.style.setProperty('--accent',accent);span.textContent=player.label;return span;
   };
   const icon=document.createElementNS('http://www.w3.org/2000/svg','svg');
   icon.setAttribute('viewBox','0 0 24 24');icon.setAttribute('aria-label',labels[row.category]);icon.setAttribute('role','img');
   const title=document.createElementNS(icon.namespaceURI,'title');title.textContent=labels[row.category];
   const path=document.createElementNS(icon.namespaceURI,'path');path.setAttribute('d',paths[row.category]);
   icon.append(title,path);node.replaceChildren(side(row.left,'left'),icon,side(row.right,'right'));
  }
  // Keep stable nodes so updates never replay entry animation.
  for(const row of [...rows].reverse()) {const node=this.nodes.get(row.event.id);if(node)this.root.prepend(node);}
 }
}
