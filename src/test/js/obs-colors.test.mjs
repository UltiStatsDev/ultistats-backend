import {test} from 'node:test';
import assert from 'node:assert/strict';
import {dominantColor, readableText, contrast, teamAccent, imageAccent, FALLBACK} from '../../main/resources/static/obs/colors.mjs';
test('dominant opaque image color ignores transparency and unusable colors',()=>{
 assert.equal(dominantColor(new Uint8ClampedArray([255,0,0,255,255,0,0,255,0,0,255,255,0,0,0,0])),'#ff0000');
 assert.equal(dominantColor(new Uint8ClampedArray([255,255,255,255,0,0,0,255])),FALLBACK);
});
test('manual team override wins; reset and failed image use auto or fallback',()=>{
 assert.equal(teamAccent('a',{a:'#123456'},{a:'#ff0000'}),'#123456');
 assert.equal(teamAccent('a',{}, {a:'#ff0000'}),'#ff0000');
 assert.equal(teamAccent('a',{},{}),FALLBACK);
});
test('text remains readable for poor custom color pairs',()=>{
 for(const bg of ['#ffffff','#000000','#777777','#ff0000'])
  assert.ok(contrast(bg,readableText(bg,bg))>=4.5);
 assert.equal(readableText('#000000','#ffffff'),'#ffffff');
});

test('automatic accents contrast against configured background; manual overrides stay explicit',()=>{
 assert.ok(contrast('#ff0000',teamAccent('a',{}, {a:'#ff0000'},'#ff0000'))>=3);
 assert.ok(contrast(FALLBACK,teamAccent('a',{}, {},FALLBACK))>=3);
 assert.equal(teamAccent('a',{a:'#ff0000'},{},'#ff0000'),'#ff0000');
});
test('failed same-origin image produces a fallback contrasting with current background',async()=>{
 const oldLocation=globalThis.location,oldImage=globalThis.Image;
 globalThis.location={origin:'http://localhost',href:'http://localhost/obs-battle-log.html'};
 globalThis.Image=class {decode(){return Promise.reject(Error('404'));}};
 try {
  const auto={a:await imageAccent('/missing.png')};
  assert.equal(auto.a,FALLBACK);
  assert.ok(contrast(FALLBACK,teamAccent('a',{},auto,FALLBACK))>=3);
 }finally{globalThis.location=oldLocation;globalThis.Image=oldImage;}
});
