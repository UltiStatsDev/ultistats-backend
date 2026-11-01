const uuid=/^[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}$/;
const unavailable=()=>Object.assign(new Error('Публичные ссылки недоступны. Повторите открытие матча.'),{code:'PUBLIC_SOURCE_UNAVAILABLE'});
const mismatch=()=>Object.assign(new Error('Установка изменилась. Публичная ссылка не сформирована; откройте страницу заново.'),{code:'AUTH_IDENTITY_MISMATCH'});

export function createPublicSources({fetchImpl=globalThis.fetch.bind(globalThis),origin=globalThis.location?.origin}={}) {
 let installation=null,publicOrigin=null,inflight=null,generation=0;
 async function load(expectedInstallationId) {
  if(typeof expectedInstallationId!=='string'||!uuid.test(expectedInstallationId)||
     (installation!==null&&installation!==expectedInstallationId)){
   generation++;publicOrigin=null;throw mismatch();
  }
  installation??=expectedInstallationId;
  if(inflight)return inflight;
  publicOrigin=null;const observed=generation;
  const pending=(async()=>{
   const response=await fetchImpl(new URL('/api/v1/installation',origin).href,{credentials:'omit',cache:'no-store',signal:AbortSignal.timeout(5000)});
   if(!response.ok)throw unavailable();
   const metadata=await response.json();
   if(observed!==generation||metadata.installationId!==installation)throw mismatch();
   if(metadata.contractVersion!=='1.0.0'||typeof metadata.publicOrigin!=='string')throw unavailable();
   let url;try{url=new URL(metadata.publicOrigin);}catch{throw unavailable();}
   if(!['http:','https:'].includes(url.protocol)||url.username||url.password||url.pathname!=='/'||url.search||url.hash)throw unavailable();
   publicOrigin=url.origin;
  })().catch(error=>{publicOrigin=null;throw error.code?error:unavailable();});
  inflight=pending;
  try{await pending;}finally{if(inflight===pending)inflight=null;}
 }
 function sourceUrl(page,matchId) {
  if(!publicOrigin)throw unavailable();
  if(!['/obs-battle-log.html','/obs-scoreboard.html'].includes(page)||typeof matchId!=='string'||!uuid.test(matchId))throw unavailable();
  const url=new URL(page,publicOrigin);url.searchParams.set('matchId',matchId);return url.href;
 }
 return Object.freeze({load,sourceUrl});
}
