// Shared by staff tools only. Public OBS sources never import this module.
const identityHeaders=['X-UltiStats-Installation-Id','X-UltiStats-Account-Id','X-UltiStats-Session-Id'];
const uuid=/^[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}$/;
const safeMethods=new Set(['GET','HEAD','OPTIONS']);
function denied(code,message) { return Object.assign(new Error(message),{code}); }
function capture(context,permission) {
 if(context?.state==='PASSWORD_CHANGE_REQUIRED')throw denied('AUTH_PASSWORD_CHANGE_REQUIRED','Сначала измените временный пароль.');
 if(context?.state!=='AUTHENTICATED'||!context.account)throw denied('AUTH_REQUIRED','Войдите в систему и откройте страницу заново.');
 if(context.contractVersion!=='1.0.0'||![context.installationId,context.account.id,context.sessionId].every(value=>typeof value==='string'&&uuid.test(value)))
  throw denied('AUTH_IDENTITY_MISMATCH','Не удалось проверить контекст установки и сессии.');
 if(!context.permissions?.includes(permission))throw denied('AUTH_PERMISSION_DENIED','Недостаточно прав. Несохранённые изменения сохранены на странице.');
 return Object.freeze([context.installationId,context.account.id,context.sessionId]);
}
function sameOwner(left,right) { return left.every((value,index)=>value===right[index]); }

export function createStaffSession({fetchImpl=globalThis.fetch.bind(globalThis),origin=globalThis.location?.origin}={}) {
 const base=new URL(origin);
 let owner=null,requiredPermission=null,booting=null;
 function localUrl(value) {
  const url=new URL(value,base);
  if(url.origin!==base.origin||url.username||url.password||url.hash||
     [...url.searchParams.keys()].some(key=>/^(token|password|authorization|csrf|session)$/i.test(key)))
   throw denied('AUTH_PERMISSION_DENIED','Защищённый запрос разрешён только на текущий адрес без секретов в URL.');
  return url.href;
 }
 function headersFor(options={},csrf) {
  const headers=new Headers(options.headers);
  identityHeaders.forEach((name,index)=>headers.set(name,owner[index]));
  if(csrf)headers.set('X-CSRF-TOKEN',csrf);
  return headers;
 }
 async function json(url,headers) {
  const response=await fetchImpl(localUrl(url),{credentials:'same-origin',cache:'no-store',headers});
  if(!response.ok){
   const problem=await response.json().catch(()=>({}));
   throw denied(problem.code??'AUTH_REQUIRED',problem.detail??`HTTP ${response.status}. Откройте страницу заново после входа.`);
  }
  return response.json();
 }
 async function checkContext(permission) {
  const context=await json('/api/v1/auth/session',owner?headersFor():undefined);
  const observed=capture(context,permission);
  if(owner&&!sameOwner(owner,observed))throw denied('AUTH_IDENTITY_MISMATCH','Сессия изменилась. Проверьте аккаунт и откройте страницу заново; запрос не отправлен.');
  // Freeze even when later CSRF retrieval fails; never silently bootstrap a new owner.
  owner??=observed;
  return context;
 }
 async function csrfToken() {
  const csrf=await json('/api/v1/auth/csrf',headersFor());
  if(csrf.headerName!=='X-CSRF-TOKEN'||typeof csrf.token!=='string'||!csrf.token)
   throw denied('CSRF_INVALID','Не удалось проверить защиту запроса. Несохранённые изменения остаются на странице.');
  return csrf.token;
 }
 async function bootstrap(permission) {
  if(!permission)throw denied('AUTH_PERMISSION_DENIED','Не указано требуемое право.');
  if(!booting)booting=(async()=>{
   const context=await checkContext(permission);
   await csrfToken();requiredPermission=permission;
   return context;
  })().finally(()=>{booting=null;});
  const context=await booting;
  capture(context,permission);
  return context;
 }
 async function prepareRequest(url,options={}) {
  const destination=localUrl(url);
  if(!owner||!requiredPermission)throw denied('AUTH_REQUIRED','Сначала войдите и откройте страницу заново.');
  await checkContext(requiredPermission);
  const method=(options.method??'GET').toUpperCase();
  const csrf=safeMethods.has(method)?undefined:await csrfToken();
  return {...options,method,headers:headersFor(options,csrf),credentials:'same-origin',cache:'no-store',url:destination};
 }
 async function protectedFetch(url,options={}) {
  const {url:destination,...prepared}=await prepareRequest(url,options);
  // One attempt only: auth/domain/transport failure never authorizes a new-owner retry.
  return fetchImpl(destination,prepared);
 }
 return Object.freeze({bootstrap,prepareRequest,protectedFetch});
}
