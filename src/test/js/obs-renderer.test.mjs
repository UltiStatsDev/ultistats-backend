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
  this.moves=0;
 }
 get firstElementChild(){return this.children[0]??null;}
 get nextElementSibling(){return this.parentNode?.children[this.parentNode.children.indexOf(this)+1]??null;}
 setAttribute(){}
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
