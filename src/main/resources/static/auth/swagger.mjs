import {createStaffSession} from './staff-session.mjs';
import {createSwaggerInterceptor} from './swagger-adapter.mjs';
const status=document.getElementById('auth-status');
try {
 const session=createStaffSession();
 await session.bootstrap('API_DOCS');
 status.textContent='Защищённая сессия проверена.';
 globalThis.ui=SwaggerUIBundle({
  url:'/v3/api-docs',dom_id:'#swagger-ui',deepLinking:true,
  presets:[SwaggerUIBundle.presets.apis,SwaggerUIStandalonePreset],layout:'StandaloneLayout',
  validatorUrl:null,persistAuthorization:false,showMutatedRequest:false,
  requestInterceptor:createSwaggerInterceptor(session),
 });
}catch(error){status.textContent=`${error.message} Войдите и откройте документацию заново.`;}
