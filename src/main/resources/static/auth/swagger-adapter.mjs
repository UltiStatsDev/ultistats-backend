export function createSwaggerInterceptor(session) {
 return async request=>{
  const prepared=await session.prepareRequest(request.url,request);
  return {...prepared,headers:Object.fromEntries(prepared.headers)};
 };
}
