import {createPublicSources} from './public-sources.mjs';
import {createStaffSession} from './staff-session.mjs';
export const obsSession=createStaffSession();
export const obsSources=createPublicSources();

let pageCapture;
export function bindObsPage(){pageCapture??=obsSession.capture();return pageCapture;}
export function obsPageCurrent(){return !!pageCapture&&obsSession.isCurrent(pageCapture);}
export function assertObsPage(){if(!obsPageCurrent())throw Error('Сессия изменилась. Откройте страницу заново; несохранённые изменения остаются на странице.');}
