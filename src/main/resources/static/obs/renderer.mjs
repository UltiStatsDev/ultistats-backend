import {resolveRow} from './feed.mjs';
import {teamAccent,readableText} from './colors.mjs';
const labels={GOAL:'Гол',BLOCK:'Блок',INTERCEPTION:'Перехват',CALLAHAN:'Кэллахан'};
// INTERCEPTION: hand adapted from Lucide hand-platter; original flat disc.
// Source and complete ISC notice: ./lucide-LICENSE.txt (shipped with this asset).
const paths={GOAL:'M4 12h16m-6-6 6 6-6 6',BLOCK:'M12 3 4 7v5c0 5 8 9 8 9s8-4 8-9V7z',
 INTERCEPTION:'M21 5c0 1.1-3.6 2-8 2S5 6.1 5 5s3.6-2 8-2 8 .9 8 2ZM15.4 17.4l3.2-2.8a2 2 0 1 1 2.8 2.9l-3.6 3.3c-.7.8-1.7 1.2-2.8 1.2h-4c-1.1 0-2.1-.4-2.8-1.2L6.9 19.3a1 1 0 0 0-.749-.3H5M2 12h12a2 2 0 0 1 0 4h-2M5 12v8a1 1 0 0 1-1 1H2',CALLAHAN:'m13 2-9 12h7l-1 8 10-13h-7z'};
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
  // Reinserting even the same node restarts its CSS entrance in Chromium.
  // Only insert new/out-of-order rows; leave correctly placed rows connected.
  let cursor=this.root.firstElementChild;
  for(const row of rows) {
   while(cursor?.classList.contains('leaving'))cursor=cursor.nextElementSibling;
   const node=this.nodes.get(row.event.id);if(!node)continue;
   if(node===cursor)cursor=cursor.nextElementSibling;
   else this.root.insertBefore(node,cursor);
  }
 }
}
