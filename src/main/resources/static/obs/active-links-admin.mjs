import {createActiveLinks} from './active-links.mjs';
const labels = {LOADING: 'Проверка текущей ссылки…', ACTIVE: 'Активная ссылка', NO_ACTIVE_LINK: 'Активной ссылки нет',
  REISSUE_REQUIRED: 'Старая ссылка: для копирования нужен явный перевыпуск.', UNAVAILABLE: 'Материал ключа недоступен (503). Ссылка не изменена.',
  ERROR: 'Не удалось проверить ссылку.', UNKNOWN: 'Результат операции неизвестен.', SESSION_CHANGED: 'Сессия изменилась. Откройте страницу заново.'};
export function mountActiveLinks(session, {document = globalThis.document, clipboard = globalThis.navigator?.clipboard, onContext = () => {},
  listen = (type, fn) => globalThis.addEventListener(type, fn, {once: true})} = {}) {
  const $ = id => document.getElementById(id), dialog = $('links-dialog');
  let confirmation = null, framePath = '', controller, selection = 0;
  function close() {confirmation = null;if (dialog.open) dialog.close();}
  function render(state) {
    if (!state.current) close();
    for (const kind of ['scoreboard', 'event-log', 'infographics']) {
      const card = state.cards[kind], prefix = `link-${kind}`;
      $(prefix+'-url').value = card.url;
      $(prefix+'-status').textContent = card.message || labels[card.status];
      $(prefix+'-copy').disabled = !state.current || card.busy || card.status !== 'ACTIVE';
      $(prefix+'-issue').disabled = !state.current || card.busy || card.status === 'LOADING';
      $(prefix+'-issue').textContent = card.retry ? 'Повторить выдачу' : card.key ? 'Перевыпустить' : 'Выдать ссылку';
      $(prefix+'-revoke').disabled = !state.current || card.busy || !card.key;
    }
    $('links-open').disabled = !state.current;
    $('links-refresh').disabled = !state.current;
    if (!state.current) {
      $('links-preview').src = 'about:blank';framePath = '';
      $('links-kind').disabled = $('preview-refresh').disabled = true;
    }
  }
  controller = createActiveLinks({session, onChange: render});
  function preview(refresh = false) {
    if (!controller.state.current) return;
    const kind = $('links-kind').value;
    const path = kind === 'stats' ? controller.preview('infographics') + '&view=stats' : controller.preview(kind);
    if (refresh || path !== framePath) {$('links-preview').src = path;framePath = path;}
    $('links-kind').disabled = $('preview-refresh').disabled = false;
  }
  async function select() {
    close();const selected = ++selection;
    const id = $('links-mode').value === 'auto' ? null : $('links-match').value.trim().toLowerCase();
    try {
      const promise = controller.select(id);
      if (controller.state.current && id === controller.state.matchId) onContext(id);
      preview();await promise;
      if (selected !== selection || !controller.state.current) return;
      $('links-context').textContent = id ? `Открыт конкретный матч · ${id}` : 'Автоматический выбор сервера';
    } catch { if (selected === selection && controller.state.current) $('links-context').textContent = 'Введите корректный UUID матча. До этого открыт прежний контекст ссылок.'; }
  }
  $('links-open').addEventListener('click', select);
  $('links-refresh').addEventListener('click', () => {close();void controller.select(controller.state.matchId);});
  $('links-mode').addEventListener('change', () => {
    $('links-match-label').hidden = $('links-mode').value === 'auto';
    // EXACT requires an explicitly opened binding; changing this selector alone keeps the old context.
    close();if ($('links-mode').value === 'auto') void select();
    else {selection++;$('links-context').textContent = 'Введите UUID и нажмите «Открыть контекст». До этого открыт прежний контекст ссылок.';}
  });
  $('links-kind').addEventListener('change', () => preview());
  $('preview-refresh').addEventListener('click', () => preview(true));
  $('links-matches').addEventListener('change', () => {
    if (!$('links-matches').value) return;
    $('links-mode').value = 'exact';$('links-match-label').hidden = false;
    $('links-match').value = $('links-matches').value;void select();
  });
  for (const kind of ['scoreboard', 'event-log', 'infographics']) {
    const prefix = `link-${kind}`;
    $(prefix+'-copy').addEventListener('click', async () => {
      const state = controller.state, card = state.cards[kind];
      if (!state.current || card.busy || card.status !== 'ACTIVE') return;
      try {
        await clipboard.writeText(card.url);
        if (controller.state === state && controller.state.current) $(prefix+'-status').textContent = 'Ссылка скопирована';
      } catch {
        if (controller.state !== state || !controller.state.current) return;
        $(prefix+'-url').focus();$(prefix+'-url').select();$(prefix+'-status').textContent = 'Скопируйте выделенную ссылку вручную.';
      }
    });
    for (const action of ['issue', 'revoke']) $(prefix+'-'+action).addEventListener('click', () => {
      const state = controller.state, intent = action === 'revoke' ? controller.revokeIntent(kind) : null;
      if (!state.current || state.cards[kind].busy || (action === 'revoke' && !intent)) return;
      confirmation = {state, kind, action, intent};
      $('links-dialog-text').textContent = action === 'issue'
        ? `${kind === 'scoreboard' ? 'Табло' : kind === 'event-log' ? 'Event Log' : 'Инфографика'}: выдать ссылку для показанного контекста? Прежняя ссылка этого вида и контекста перестанет работать. Ссылку в OBS потребуется заменить.`
        : 'Отозвать показанную ссылку? Подключённый по ней OBS-источник перестанет работать.';
      dialog.showModal();
    });
  }
  $('links-cancel').addEventListener('click', close);
  dialog.addEventListener('cancel', close);
  $('links-confirm').addEventListener('click', () => {
    const chosen = confirmation;close();
    if (!chosen || chosen.state !== controller.state || !controller.state.current) return;
    void (chosen.action === 'issue' ? controller.issue(chosen.kind) : controller.revoke(chosen.intent));
  });
  listen('pagehide', () => {close();controller.dispose();});
  void select();
  return controller;
}
