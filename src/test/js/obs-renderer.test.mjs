import {test} from 'node:test';
import assert from 'node:assert/strict';
import {Renderer} from '../../main/resources/static/obs/renderer.mjs';
import {defaults} from '../../main/resources/static/obs/feed.mjs';

// Minimal DOM tracks moves of connected row elements: browsers restart their CSS
// entrance animation on reinsertion. No browser/dependency is needed in Node CI.
class Element {
 constructor(namespaceURI) {
  this.namespaceURI=namespaceURI;this.children=[];this.dataset={};this.parentNode=null;
  this.style={setProperty(){}};this.classes=new Set();
  this.classList={add:value=>this.classes.add(value),contains:value=>this.classes.has(value)};
  this.moves=0;this.attributes={};
 }
 get firstElementChild(){return this.children[0]??null;}
 get nextElementSibling(){return this.parentNode?.children[this.parentNode.children.indexOf(this)+1]??null;}
 setAttribute(name,value){this.attributes[name]=value;}
 append(...nodes){for(const node of nodes)this.insertBefore(node,null);}
 prepend(node){this.insertBefore(node,this.firstElementChild);}
 insertBefore(node,before){
  if(node.parentNode){node.moves++;node.remove();}
  const index=before===null?this.children.length:this.children.indexOf(before);
  assert.ok(index>=0);this.children.splice(index,0,node);node.parentNode=this;
 }
 remove(){if(this.parentNode){const list=this.parentNode.children;list.splice(list.indexOf(this),1);this.parentNode=null;}}
 replaceChildren(...nodes){for(const node of [...this.children])node.remove();this.append(...nodes);}
}
const row=id=>({event:{id,type:'GOAL',fromParticipantId:'a',toParticipantId:'b'}});

test('unchanged ticks, config changes and visible edits retain row positions without restarting entrance',()=>{
 const oldDocument=globalThis.document;
 globalThis.document={createElement:()=>new Element(),createElementNS:ns=>new Element(ns)};
 try {
  const root=new Element(),renderer=new Renderer(root),match={teams:[]},rows=[row('new'),row('old')];
  renderer.render(rows,match,defaults);
  const nodes=[...root.children];
  for(let tick=0;tick<10;tick++)renderer.render(rows,match,defaults);
  renderer.render(rows,match,{...defaults,corner:'BOTTOM_LEFT',backgroundColor:'#ffffff'});
  renderer.render([{event:{...rows[0].event,toParticipantId:'edited'}},rows[1]],match,defaults);
  assert.deepEqual(root.children,nodes);
  assert.deepEqual(nodes.map(node=>node.moves),[0,0],'visible rows must never be reinserted on render ticks or edits');
  renderer.render([row('latest'),...rows],match,defaults);
  assert.deepEqual(root.children.map(node=>node.dataset.eventId),['latest','new','old']);
  assert.deepEqual(nodes.map(node=>node.moves),[0,0],'adding a new row must not move existing rows');
 } finally {globalThis.document=oldDocument;}
});

// Catch category/role regressions at the renderer boundary, including all block
// variants. Geometry assertions protect the selected glyph, not its readability:
// legibility at the actual 27px size is checked separately in Chromium/OBS.
test('event markers preserve player roles, accessible names and shared block glyph',()=>{
 const oldDocument=globalThis.document;
 globalThis.document={createElement:()=>new Element(),createElementNS:ns=>new Element(ns)};
 try {
  const root=new Element(),renderer=new Renderer(root);
  const match={teams:[{teamId:'t',participants:[
   {participantId:'a',number:17,lastName:'Бросавший',firstName:'Александр'},
   {participantId:'b',number:9,lastName:'Принимающий',firstName:'Борис'}
  ]}]};
  const types=['GOAL','BLOCK','BLOCK_MARKER','BLOCK_FIELD','INTERCEPTION','CALLAHAN'];
  renderer.render(types.map(type=>({event:{id:type,type,fromParticipantId:'a',toParticipantId:'b'}})),match,defaults);
  assert.equal(root.children.length,6,'CALLAHAN appears once');
  const glyphs={};
  const labels=['Гол','Блок','Блок','Блок','Перехват','Кэллахан'];
  root.children.forEach((node,index)=>{
   assert.equal(node.children.length,3,'only players and icon, no visible badge');
   const [left,icon,right]=node.children;
   assert.equal(left.textContent,index===0?'17 Бросавший А.':'9 Принимающий Б.');
   assert.equal(right.textContent,index===0?'9 Принимающий Б.':'17 Бросавший А.');
   assert.equal(icon.namespaceURI,'http://www.w3.org/2000/svg');
   assert.equal(icon.attributes.role,'img');
   assert.equal(icon.attributes['aria-label'],labels[index]);
   assert.equal(icon.children[0].textContent,labels[index]);
   glyphs[types[index]]=icon.children[1].attributes.d;
  });
  assert.equal(glyphs.GOAL,'M4 12h16m-6-6 6 6-6 6','plain rightward arrow without receiver-side bar');
  assert.equal(glyphs.BLOCK,'M12 3 4 7v5c0 5 8 9 8 9s8-4 8-9V7z');
  assert.equal(glyphs.BLOCK_MARKER,glyphs.BLOCK);
  assert.equal(glyphs.BLOCK_FIELD,glyphs.BLOCK);
  assert.ok(glyphs.INTERCEPTION.startsWith('M10 11c0 1-2 1.8-4.5 1.8S1 12 1 11s2-1.8 4.5-1.8S10 10 10 11Z'),'interception includes the flat oval disc on the left');
  assert.ok(glyphs.INTERCEPTION.includes('M13 12V6a1.5 1.5 0 0 1 3 0v4-6'),'interception includes the upright catching hand on the right');
  assert.equal(glyphs.CALLAHAN,'m13 2-9 12h7l-1 8 10-13h-7z');
  assert.equal(new Set([glyphs.GOAL,glyphs.BLOCK,glyphs.INTERCEPTION,glyphs.CALLAHAN]).size,4);
  renderer.render([{event:{id:'unknown',type:'INTERCEPTION'}}],{teams:[]},defaults);
  const unknown=renderer.nodes.get('unknown');
  assert.equal(unknown.children[0].textContent,'Неизвестный игрок');
  assert.equal(unknown.children[2].textContent,'Неизвестный игрок');
 } finally {globalThis.document=oldDocument;}
});
