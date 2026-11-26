import {RosterClock} from './infographics-model.mjs';
import {watchStaffJson} from './staff-reader.mjs';

const path = '/api/v1/obs/infographics/presentations';
const labels = {LEADERS:'Лидеры матча', ROSTER:'Ростеры команд', POINT:'Состав на поинт', STATS:'Итоговая статистика'};

export function activeGraphic(control, serverNow) {
  const receipt = control?.activePresentation;
  if (!receipt) return control?.activePhase ? {...control.activePhase, seconds:null} : null;
  const remaining = Date.parse(receipt.expiresAt) - serverNow;
  return remaining > 0 ? {...receipt, seconds:Math.ceil(remaining / 1000)} : null;
}

export function commandFor(action, control, active, operationId) {
  if (!control) return null;
  if (action === 'hide' && active?.generation) return {method:'DELETE', path:`${path}/${active.generation}`};
  const body = {binding:control.binding, expectedMatchId:control.matchId, expectedGeneration:control.expectedGeneration, operationId};
  if (action === 'hide') {
    return active?.phaseToken
      ? {method:'POST', path:`${path}/hide-phase`, body:{...body, phaseToken:active.phaseToken}}
      : null;
  }
  return control.availability[action] === true ? {method:'POST', path, body:{...body, kind:action,
    expectedInputRevision:control.expectedInputRevision, expectedSettingsRevision:control.expectedSettingsRevision}} : null;
}

export function mountOperatorDesk(session, endPage) {
  const $ = id => document.getElementById(id);
  const capture = session.capture(), clock = new RosterClock();
  const buttons = [...document.querySelectorAll('[data-show]')];
  const hide = $('air-hide'), retryButton = $('air-retry'), air = $('air-kind'), time = $('air-time'), status = $('air-status');
  const current = () => !stopped && session.isCurrent(capture);
  let stopped = false, stopWatching = () => {}, requested, resolved, control = null, pending, retry;
  const active = () => activeGraphic(control, clock.current());
  const showText = (element, text) => {if (element.textContent !== text) element.textContent = text;};

  function render() {
    const graphic = active();
    for (const button of buttons) {
      button.disabled = !current() || !!pending || control?.availability[button.dataset.show] !== true;
    }
    hide.disabled = !current() || !!pending || !graphic;
    retryButton.hidden = !retry;
    retryButton.disabled = !current() || !!pending || !control;
    const kind = graphic?.kind;
    air.dataset.kind = kind ?? '';
    showText(air, labels[kind] ?? (control ? 'Нет активной плашки' : 'Эфир не подтверждён'));
    showText(time, graphic ? (graphic.seconds === null ? 'Автоматическая фаза' :
      `${graphic.origin === 'AUTO' ? 'Автоматический показ' : 'Ручной показ'} · ${graphic.seconds} с`) : '');
  }

  function clearIntent() {
    pending?.abort();
    pending = retry = undefined;
    status.textContent = '';
  }

  function watch() {
    stopWatching();
    control = null;
    render();
    if (!current() || !resolved) return;
    const binding = requested ? 'EXACT' : 'AUTO', id = resolved;
    stopWatching = watchStaffJson(session, `/api/v1/obs/infographics/leaders/${requested ?? 'auto'}`, result => {
      const snapshot = requested ? result : result.snapshot;
      const value = snapshot?.presentation;
      if (snapshot?.matchId !== id || value?.matchId !== id || value.binding !== binding ||
          !Number.isFinite(Date.parse(value.serverNow))) {
        control = null;
      } else if (clock.accept(value)) {
        control = value;
      }
      render();
    }, message => {
      if (message !== 'Синхронизировано') {control = null; render();}
    }, endPage);
  }

  async function send(intent) {
    if (!current() || pending || !control || !intent) return;
    const request = new AbortController();
    pending = request;
    status.textContent = 'Ожидаем подтверждение сервера…';
    render();
    const sameContext = () => current() && pending === request;
    try {
      const {path, method, body} = intent;
      const response = await session.protectedFetch(path, {
        method, signal:AbortSignal.any([request.signal, AbortSignal.timeout(5000)]),
        ...(body ? {headers:{'Content-Type':'application/json'}, body:JSON.stringify(body)} : {}),
      });
      if (!sameContext()) return;
      if ([401,403].includes(response.status)) {endPage(); return;}
      if (response.status >= 500) throw Error('Подтверждение недоступно');
      retry = undefined;
      status.textContent = response.ok ? 'Команда подтверждена' : response.status === 409
        ? 'Эфир или данные изменились. Проверьте текущее состояние' : `Ошибка HTTP ${response.status}`;
    } catch {
      if (sameContext()) {
        retry = intent;
        status.textContent = 'Результат команды неизвестен. Можно повторить ту же команду';
      }
    } finally {
      if (sameContext()) {pending = undefined; watch();}
    }
  }

  const act = action => {
    const intent = commandFor(action, control, active(), crypto.randomUUID());
    if (intent) {retry = undefined; void send(intent);}
  };
  for (const button of buttons) button.addEventListener('click', () => act(button.dataset.show));
  hide.addEventListener('click', () => act('hide'));
  retryButton.addEventListener('click', () => void send(retry));
  const tick = setInterval(render, 200);
  return {
    select(binding) {
      if (requested === binding) return;
      requested = binding;
      resolved = undefined;
      clock.accept(null);
      clearIntent();
      watch();
    },
    resolve(matchId) {
      if (resolved === matchId) return;
      resolved = matchId;
      clearIntent();
      watch();
    },
    stop() {
      stopped = true;
      stopWatching();
      clearInterval(tick);
      clearIntent();
      control = null;
      render();
    },
  };
}
