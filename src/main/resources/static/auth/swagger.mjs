import {createStaffSession} from './staff-session.mjs';
import {createSwaggerTransportPlugin} from './swagger-adapter.mjs';
const status=document.getElementById('auth-status');
try {
 const session=createStaffSession();
 await session.bootstrap('API_DOCS');
 const pageCapture=session.capture();
 session.subscribe(()=>{if(!session.isCurrent(pageCapture)){
  status.textContent='Сессия изменилась. Откройте документацию заново; введённые данные сохранены на странице.';
  document.querySelectorAll('#swagger-ui .try-out__btn, #swagger-ui .execute').forEach(button=>button.disabled=true);
 }});
 status.textContent='Защищённая сессия проверена.';
 globalThis.ui=SwaggerUIBundle({
  url:'/v3/api-docs',dom_id:'#swagger-ui',deepLinking:true,
  presets:[SwaggerUIBundle.presets.apis,SwaggerUIStandalonePreset],layout:'StandaloneLayout',
  validatorUrl:null,persistAuthorization:false,showMutatedRequest:false,
  plugins:[createSwaggerTransportPlugin(session,pageCapture)],
 });
}catch(error){status.textContent=`${error.message} Войдите и откройте документацию заново.`;}
