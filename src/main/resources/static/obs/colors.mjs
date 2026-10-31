export const FALLBACK='#53c7ed';
const rgb=hex=>hex.slice(1).match(/../g).map(v=>parseInt(v,16));
const luminance=hex=>rgb(hex).map(v=>v/255).map(v=>v<=0.04045?v/12.92:((v+0.055)/1.055)**2.4)
    .reduce((sum,v,i)=>sum+v*[0.2126,0.7152,0.0722][i],0);
export const contrast=(a,b)=>(Math.max(luminance(a),luminance(b))+0.05)/(Math.min(luminance(a),luminance(b))+0.05);
export function readableText(background,preferred='#ffffff') {
    if(contrast(background,preferred)>=4.5) return preferred;
    return contrast(background,'#ffffff')>=contrast(background,'#000000')?'#ffffff':'#000000';
}
export function teamAccent(id,manual={},auto={},background='#17202e') {
    if(manual[id]) return manual[id];
    const candidate=auto[id]??FALLBACK;
    if(contrast(candidate,background)>=3) return candidate;
    return contrast(FALLBACK,background)>=3?FALLBACK:readableText(background);
}
export function dominantColor(pixels) {
    const buckets=new Map();
    for(let i=0;i<pixels.length;i+=4) {
        const [r,g,b,a]=pixels.slice(i,i+4);
        if(a<192 || Math.max(r,g,b)<35 || Math.min(r,g,b)>230) continue;
        const key=[r,g,b].map(v=>v>>4).join(',');
        const bucket=buckets.get(key)??{count:0,r:0,g:0,b:0};
        bucket.count++;bucket.r+=r;bucket.g+=g;bucket.b+=b;buckets.set(key,bucket);
    }
    const best=[...buckets.values()].sort((a,b)=>b.count-a.count)[0];
    return best?'#'+[best.r,best.g,best.b].map(v=>Math.round(v/best.count).toString(16).padStart(2,'0')).join(''):FALLBACK;
}
export async function imageAccent(url) {
    if(!url) return FALLBACK;
    try {
        const parsed=new URL(url,location.href);
        if(parsed.origin!==location.origin) return FALLBACK;
        const image=new Image(); image.src=parsed.href;
        let timeout;
        try {
            await Promise.race([image.decode(),new Promise((_,reject)=>{timeout=setTimeout(()=>reject(Error('image timeout')),5000);})]);
        } finally { clearTimeout(timeout); }
        const canvas=document.createElement('canvas');canvas.width=64;canvas.height=64;
        const context=canvas.getContext('2d',{willReadFrequently:true});
        context.drawImage(image,0,0,64,64);
        return dominantColor(context.getImageData(0,0,64,64).data);
    } catch { return FALLBACK; }
}
