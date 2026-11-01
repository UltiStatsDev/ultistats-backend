const secretOperation=path=>/\/api\/v1\/(?:auth|accounts)(?:\/|$)/.test(path);
const stale=()=>Error('Сессия изменилась. Несохранённые данные остаются на странице. Откройте документацию заново.');
// Swagger UI 5.21 executes through system.fn.fetch. Its Redux request metadata
// never receives credential material; the Web coordinator adds CSRF at fetch time.
export function createSwaggerTransportPlugin(session,pageCapture) {
 const assertCurrent=()=>{if(!session.isCurrent(pageCapture))throw stale();};
 return system=>{
  const swaggerFetch=system.fn.fetch;
  return {
  fn:{fetch:async(url,options)=>{
   assertCurrent();
   const request=typeof url==='string'?{...options,url}:{...url};
   if(secretOperation(new URL(request.url,globalThis.location?.origin).pathname))throw Error('Вход, пароли и аккаунты доступны в приложении.');
   // Preserve Swagger's query/form/multipart and response serializers. Only its
   // actual network callback delegates to Web; no CSRF enters the request object.
   request.userFetch=async(destination,options)=>{
    assertCurrent();
    const response=await session.protectedFetch(destination,options);
    assertCurrent();return response;
   };
   const serialized=await swaggerFetch(request);
   assertCurrent();return serialized;
  }},
  statePlugins:{spec:{wrapSelectors:{allowTryItOutFor:original=>(state,path,...rest)=>session.isCurrent(pageCapture)&&!secretOperation(path)&&original(state,path,...rest)}}},
 };
 };
}
