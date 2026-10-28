// «Учёт SIM» — веб-версия. Всё расшифровывается только здесь, в браузере; сервер видит лишь шифротекст.
// Пользовательские данные выводятся только через textContent (никакого innerHTML с данными).
import * as C from './crypto.js';

const IDLE_LOCK_MS = 15 * 60 * 1000;
const POLL_MS = 45 * 1000;
const PAGE = 120;

const S = {
  token: null, vaultKey: null, rev: 0,
  records: new Map(),          // id -> { rev, obj }
  q: '', contract: null, status: null, shown: PAGE,
  idleTimer: null, poll: null, busy: false, lastSync: 0,
};

// ---------------- Мелочи ----------------

const ICONS = {
  search: '<svg viewBox="0 0 24 24" fill="currentColor"><path d="M15.5 14h-.79l-.28-.27A6.5 6.5 0 1 0 9.5 16a6.47 6.47 0 0 0 4.23-1.57l.27.28v.79l5 4.99L20.49 19l-4.99-5zm-6 0C7.01 14 5 11.99 5 9.5S7.01 5 9.5 5 14 7.01 14 9.5 11.99 14 9.5 14z"/></svg>',
  lock: '<svg viewBox="0 0 24 24" fill="currentColor"><path d="M18 8h-1V6A5 5 0 0 0 7 6v2H6a2 2 0 0 0-2 2v10a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V10a2 2 0 0 0-2-2zm-6 9a2 2 0 1 1 0-4 2 2 0 0 1 0 4zm3.1-9H8.9V6a3.1 3.1 0 0 1 6.2 0v2z"/></svg>',
  shield: '<svg width="18" height="18" viewBox="0 0 24 24" fill="currentColor"><path d="M12 1 3 5v6c0 5.55 3.84 10.74 9 12 5.16-1.26 9-6.45 9-12V5l-9-4zm-2 16-4-4 1.41-1.41L10 14.17l6.59-6.59L18 9l-8 8z"/></svg>',
  close: '<svg viewBox="0 0 24 24" fill="currentColor"><path d="M19 6.41 17.59 5 12 10.59 6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 12 13.41 17.59 19 19 17.59 13.41 12z"/></svg>',
  add: '<svg viewBox="0 0 24 24" fill="currentColor"><path d="M19 13h-6v6h-2v-6H5v-2h6V5h2v6h6v2z"/></svg>',
  history: '<svg viewBox="0 0 24 24" fill="currentColor"><path d="M13 3a9 9 0 0 0-9 9H1l3.89 3.89.07.14L9 12H6a7 7 0 1 1 2.06 4.94l-1.42 1.42A9 9 0 1 0 13 3zm-1 5v5l4.28 2.54.72-1.21-3.5-2.08V8H12z"/></svg>',
  devices: '<svg viewBox="0 0 24 24" fill="currentColor"><path d="M4 6h18V4H4a2 2 0 0 0-2 2v11H0v3h14v-3H4V6zm19 2h-6a1 1 0 0 0-1 1v10a1 1 0 0 0 1 1h6a1 1 0 0 0 1-1V9a1 1 0 0 0-1-1zm-1 9h-4v-7h4v7z"/></svg>',
  key: '<svg viewBox="0 0 24 24" fill="currentColor"><path d="M12.65 10A5.99 5.99 0 0 0 7 6a6 6 0 1 0 5.65 8H17v4h4v-4h2v-4H12.65zM7 14a2 2 0 1 1 0-4 2 2 0 0 1 0 4z"/></svg>',
  download: '<svg viewBox="0 0 24 24" fill="currentColor"><path d="M19 9h-4V3H9v6H5l7 7 7-7zM5 18v2h14v-2H5z"/></svg>',
  sim: '<svg viewBox="0 0 24 24" fill="currentColor"><path d="M19.99 4c0-1.1-.89-2-1.99-2h-8L4 8v12c0 1.1.9 2 2 2h12.01c1.1 0 1.99-.9 1.99-2l-.01-16zM9 19H7v-2h2v2zm8 0h-2v-2h2v2zm-8-4H7v-4h2v4zm4 4h-2v-4h2v4zm0-6h-2v-2h2v2zm4 2h-2v-4h2v4z"/></svg>',
  trash: '<svg viewBox="0 0 24 24" fill="currentColor"><path d="M6 19a2 2 0 0 0 2 2h8a2 2 0 0 0 2-2V7H6v12zM19 4h-3.5l-1-1h-5l-1 1H5v2h14V4z"/></svg>',
  image: '<svg viewBox="0 0 24 24" fill="currentColor"><path d="M21 19V5a2 2 0 0 0-2-2H5a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2zM8.5 13.5l2.5 3.01L14.5 12l4.5 6H5l3.5-4.5z"/></svg>',
};

function el(tag, props = {}, ...kids) {
  const e = document.createElement(tag);
  for (const [k, v] of Object.entries(props || {})) {
    if (v == null || v === false) continue;
    if (k === 'class') e.className = v;
    else if (k === 'text') e.textContent = v;
    else if (k === 'icon') e.insertAdjacentHTML('afterbegin', ICONS[v]); // только постоянные значки
    else if (k.startsWith('on')) e.addEventListener(k.slice(2), v);
    else if (k === 'style') Object.assign(e.style, v);
    else if (k === 'value') e.value = v;
    else e.setAttribute(k, v === true ? '' : v);
  }
  for (const kid of kids.flat()) if (kid != null && kid !== false) e.append(kid.nodeType ? kid : document.createTextNode(String(kid)));
  return e;
}

const $app = () => document.getElementById('app');

function toast(msg, err = false) {
  const t = el('div', { class: 'toast' + (err ? ' err' : ''), text: msg });
  document.getElementById('toasts').append(t);
  setTimeout(() => { t.classList.add('out'); setTimeout(() => t.remove(), 300); }, err ? 5000 : 2800);
}

function fmtPhone(m) {
  return m && m.length === 11 ? `+7 ${m.slice(1, 4)} ${m.slice(4, 7)}-${m.slice(7, 9)}-${m.slice(9)}` : (m || '');
}
function isoToRu(d) { return d ? d.split('-').reverse().join('.') : ''; }
function ruToIso(s) {
  const m = /^(\d{2})\.(\d{2})\.(\d{4})$/.exec((s || '').trim());
  if (!m) return null;
  const d = new Date(Date.UTC(+m[3], +m[2] - 1, +m[1]));
  return d.getUTCDate() === +m[1] && d.getUTCMonth() === +m[2] - 1 ? `${m[3]}-${m[2]}-${m[1]}` : null;
}
function fmtTime(t) {
  if (!t) return '';
  return new Date(t).toLocaleString('ru-RU', { day: '2-digit', month: '2-digit', year: 'numeric', hour: '2-digit', minute: '2-digit' });
}
function plural(n, a, b, c) { const m10 = n % 10, m100 = n % 100; return m10 === 1 && m100 !== 11 ? a : m10 >= 2 && m10 <= 4 && (m100 < 12 || m100 > 14) ? b : c; }
function fullName(o) { return [o.surname, o.name, o.patronymic].filter(Boolean).join(' '); }
function hasPassport(o) { return !!(o.series && o.number); }
function normMsisdn(s) {
  const d = (s || '').replace(/\D/g, '');
  if (d.length === 10) return '7' + d;
  if (d.length === 11 && (d[0] === '7' || d[0] === '8')) return '7' + d.slice(1);
  return null;
}

// ---------------- Статусы (как в приложении) ----------------

const ST = {
  CONFIRMED: ['Подтверждено', 'var(--ok)'], DONE: ['Внесено', 'var(--ok)'], WAITING: ['Ждёт Госуслуг', 'var(--waiting)'],
  SENT: ['Отправлено', 'var(--sent)'], PROBLEM: ['Есть проблема', 'var(--bad)'], IN_MTS: ['Есть в МТС', 'var(--sent)'],
  NEEDED: ['Нужно внести', 'var(--warn)'], READY: ['Не отправлено', 'var(--neutral)'], NO_PASSPORT: ['Нет паспорта', 'var(--neutral)'],
};
const CONFIRM = {
  Activated: 'Данные подтверждены.', WaitingForAcceptance: 'Данные отправлены в Госуслуги и ждут подтверждения пользователем.',
  WaitingForCheck: 'Идёт техническая проверка.', MismatchOfData: 'Данные в МТС не совпадают с данными на Госуслугах.',
  NotFoundInEsia: 'У пользователя нет учётной записи на Госуслугах.', Refusal: 'Пользователь отказался от подтверждения.',
  RequestNotFoundInEsia: 'Техническая ошибка на стороне Госуслуг.', Anonymous: 'Данных пользователя в МТС нет.',
  Migration: 'Номер оформлен до 01.06.2021, данные нужно внести.', ActivatedPortIn: 'Номер перенесён от другого оператора.',
  NotRequired: 'Подтверждение не требуется.', Depersonalized: 'Номер по 44-ФЗ / 223-ФЗ: данные подтверждаются через Госуслуги.',
};
function status(o) {
  const c = o.confirmation || '';
  if (c === 'Activated' || c === 'NotRequired') return 'CONFIRMED';
  if (o.opStatus === 'Faulted' || ['MismatchOfData', 'NotFoundInEsia', 'Refusal', 'RequestNotFoundInEsia'].includes(c)) return 'PROBLEM';
  if (c === 'WaitingForAcceptance' || c === 'WaitingForCheck') return 'WAITING';
  if (o.messageId && o.opStatus === 'Completed') return 'DONE';
  if (o.messageId) return 'SENT';
  if (c === 'Depersonalized' && hasPassport(o) && fullName(o)) return 'IN_MTS';
  if (['Anonymous', 'Migration', 'ActivatedPortIn', 'Depersonalized'].includes(c)) return 'NEEDED';
  return hasPassport(o) ? 'READY' : 'NO_PASSPORT';
}

// ---------------- Сервер ----------------

class ApiError extends Error { constructor(msg, code, body) { super(msg); this.code = code; this.body = body; } }

async function api(method, path, body, raw = false) {
  const headers = {};
  if (S.token) headers.Authorization = 'Bearer ' + S.token;
  let payload;
  if (raw) payload = body;
  else if (body !== undefined) { headers['Content-Type'] = 'application/json'; payload = JSON.stringify(body); }
  let r;
  try {
    r = await fetch('/api/v1' + path, { method, headers, body: payload, cache: 'no-store', credentials: 'omit', referrerPolicy: 'no-referrer' });
  } catch {
    throw new ApiError('Нет связи с сервером', 0);
  }
  if (r.status === 204) return null;
  const isJson = (r.headers.get('Content-Type') || '').includes('json');
  const data = isJson ? await r.json() : await r.arrayBuffer();
  if (!r.ok) {
    if (r.status === 401 && S.vaultKey && !path.startsWith('/login')) { lock('Сессия истекла — войдите снова'); }
    throw new ApiError((isJson && data.error) || 'Ошибка ' + r.status, r.status, data);
  }
  return data;
}

// ---------------- Блокировка по бездействию ----------------

function touch() {
  if (!S.vaultKey) return;
  clearTimeout(S.idleTimer);
  S.idleTimer = setTimeout(() => lock('Заблокировано после 15 минут бездействия'), IDLE_LOCK_MS);
}
['pointerdown', 'keydown', 'wheel', 'touchstart'].forEach((e) => addEventListener(e, touch, { passive: true }));

function lock(message) {
  const tok = S.token;
  S.token = null; S.vaultKey = null; S.records = new Map(); S.rev = 0;
  clearTimeout(S.idleTimer); clearInterval(S.poll);
  document.querySelectorAll('.drawer, .scrim, .modal, .lightbox').forEach((x) => x.remove());
  if (tok) fetch('/api/v1/logout', { method: 'POST', headers: { Authorization: 'Bearer ' + tok }, credentials: 'omit' }).catch(() => {});
  showLogin(message);
}

// ---------------- Экраны входа ----------------

function brand(sub) {
  return el('div', { class: 'brand' }, el('img', { src: 'icon.svg', alt: '' }), el('div', {}, el('b', { text: 'Учёт SIM' }), el('span', { text: sub })));
}

function secureNote(text) {
  const n = el('div', { class: 'lock-note' });
  n.insertAdjacentHTML('afterbegin', ICONS.shield);
  n.append(el('div', { text }));
  return n;
}

function otpInput() {
  const wrap = el('div', { class: 'otp' });
  const boxes = [];
  for (let i = 0; i < 6; i++) {
    const b = el('input', { class: 'input mono', inputmode: 'numeric', autocomplete: i === 0 ? 'one-time-code' : 'off', 'aria-label': `Цифра ${i + 1}` });
    b.addEventListener('input', () => {
      const d = b.value.replace(/\D/g, '');
      if (d.length > 1) { fill(d); return; }
      b.value = d;
      if (d && boxes[i + 1]) boxes[i + 1].focus();
      if (d && i === 5) wrap.dispatchEvent(new Event('complete'));
    });
    b.addEventListener('keydown', (e) => { if (e.key === 'Backspace' && !b.value && boxes[i - 1]) boxes[i - 1].focus(); });
    b.addEventListener('paste', (e) => { e.preventDefault(); fill((e.clipboardData.getData('text') || '').replace(/\D/g, '')); });
    boxes.push(b);
    wrap.append(b);
  }
  function fill(d) {
    boxes.forEach((x, k) => { x.value = d[k] || ''; });
    boxes[Math.min(d.length, 5)].focus();
    if (d.length >= 6) wrap.dispatchEvent(new Event('complete'));
  }
  wrap.value = () => boxes.map((x) => x.value).join('');
  wrap.clear = () => { boxes.forEach((x) => { x.value = ''; }); boxes[0].focus(); };
  return wrap;
}

function strength(pw) {
  let score = 0;
  if (pw.length >= 12) score++;
  if (pw.length >= 16) score++;
  if (/[a-zа-яё]/.test(pw) && /[A-ZА-ЯЁ]/.test(pw)) score++;
  if (/\d/.test(pw)) score++;
  if (/[^\p{L}\d]/u.test(pw)) score++;
  if (pw.length < 12) score = Math.min(score, 1);
  return score; // 0..5
}

async function boot() {
  try {
    const st = await api('GET', '/status');
    if (st.initialized) showLogin(); else showSetup();
  } catch (e) {
    $app().replaceChildren(el('div', { class: 'auth' }, el('div', { class: 'auth-card' }, brand('Сервер недоступен'), el('p', { text: e.message }),
      el('button', { class: 'btn primary block', text: 'Повторить', onclick: () => location.reload() }))));
  }
}

function showSetup() {
  const token = el('input', { class: 'input mono', placeholder: 'Из вывода команды simvault init', autocomplete: 'off', spellcheck: 'false' });
  const pw = el('input', { class: 'input', type: 'password', autocomplete: 'new-password' });
  const pw2 = el('input', { class: 'input', type: 'password', autocomplete: 'new-password' });
  const bar = el('i');
  const err = el('div', { class: 'error-text' });
  const btn = el('button', { class: 'btn primary block', text: 'Создать хранилище' });
  pw.addEventListener('input', () => {
    const s = strength(pw.value);
    bar.style.width = (s / 5 * 100) + '%';
    bar.style.background = s >= 4 ? 'var(--ok)' : s >= 3 ? 'var(--waiting)' : 'var(--bad)';
  });
  btn.onclick = async () => {
    err.textContent = '';
    if (!token.value.trim()) return (err.textContent = 'Введите токен настройки');
    if (strength(pw.value) < 3) return (err.textContent = 'Слишком простой пароль: не меньше 12 символов, буквы разного регистра, цифры');
    if (pw.value !== pw2.value) return (err.textContent = 'Пароли не совпадают');
    btn.disabled = true; btn.replaceChildren(el('div', { class: 'spinner sm' }), 'Создаю ключи…');
    try {
      const v = await C.createVault(pw.value);
      await api('POST', '/setup', { setupToken: token.value.trim(), kdf: v.kdf, authKey: v.authKey, wrappedKey: v.wrappedKey });
      toast('Хранилище создано');
      showLogin('Хранилище создано. Войдите с мастер-паролем и кодом из приложения-аутентификатора.');
    } catch (e) {
      err.textContent = e.message; btn.disabled = false; btn.textContent = 'Создать хранилище';
    }
  };
  $app().replaceChildren(el('div', { class: 'auth' }, el('div', { class: 'auth-card' },
    brand('Первичная настройка'),
    el('h2', { text: 'Новое зашифрованное хранилище' }),
    el('p', { text: 'Мастер-пароль знаете только вы. Сервер его не получает, восстановить его невозможно — запишите и храните надёжно.' }),
    el('div', { class: 'field' }, el('label', { text: 'Токен настройки' }), token),
    el('div', { class: 'field' }, el('label', { text: 'Мастер-пароль' }), pw, el('div', { class: 'meter' }, bar),
      el('div', { class: 'hint', text: 'Лучше фраза из 4–5 слов с цифрой: длинная и запоминаемая.' })),
    el('div', { class: 'field' }, el('label', { text: 'Повторите пароль' }), pw2),
    err, btn,
    secureNote('Шифрование AES-256 происходит в браузере. На сервере — только зашифрованные данные.'))));
  token.focus();
}

function showLogin(message) {
  const pw = el('input', { class: 'input', type: 'password', autocomplete: 'current-password', 'aria-label': 'Мастер-пароль' });
  const otp = otpInput();
  const err = el('div', { class: 'error-text', text: message || '' });
  if (message) err.style.color = 'var(--muted)';
  const btn = el('button', { class: 'btn primary block', text: 'Войти' });
  const go = async () => {
    err.textContent = ''; err.style.color = '';
    if (!pw.value) { pw.classList.add('err'); setTimeout(() => pw.classList.remove('err'), 400); return; }
    if (otp.value().length !== 6) return (err.textContent = 'Введите 6 цифр кода из приложения-аутентификатора');
    btn.disabled = true; btn.replaceChildren(el('div', { class: 'spinner sm' }), 'Вычисляю ключ…');
    try {
      const pre = await api('GET', '/prelogin');
      const { authKey, kek } = await C.deriveKeys(pw.value, pre.kdf);
      btn.lastChild.textContent = 'Вхожу…';
      const r = await api('POST', '/login', { authKey, totp: otp.value(), trustDevice: false, deviceName: 'Браузер: ' + browserName() });
      const vaultKey = await C.unwrapVaultKey(kek, r.wrappedKey);
      pw.value = '';
      S.token = r.token; S.vaultKey = vaultKey;
      touch();
      await openMain();
    } catch (e) {
      err.textContent = e.message;
      btn.disabled = false; btn.textContent = 'Войти';
      otp.clear();
    }
  };
  btn.onclick = go;
  otp.addEventListener('complete', go);
  pw.addEventListener('keydown', (e) => { if (e.key === 'Enter') otp.querySelector('input').focus(); });
  $app().replaceChildren(el('div', { class: 'auth' }, el('div', { class: 'auth-card' },
    brand('Корпоративные SIM-карты'),
    el('div', { class: 'field' }, el('label', { text: 'Мастер-пароль' }), pw),
    el('div', { class: 'field' }, el('label', { text: 'Код подтверждения' }), otp),
    err, btn,
    secureNote('Сквозное шифрование: данные расшифровываются только в этой вкладке. Автоблокировка через 15 минут бездействия.'))));
  pw.focus();
}

function browserName() {
  const ua = navigator.userAgent;
  return /Edg\//.test(ua) ? 'Edge' : /YaBrowser/.test(ua) ? 'Яндекс Браузер' : /Firefox/.test(ua) ? 'Firefox' : /Chrome/.test(ua) ? 'Chrome' : /Safari/.test(ua) ? 'Safari' : 'браузер';
}

// ---------------- Данные ----------------

async function sync(quiet = true) {
  if (!S.vaultKey || S.busy) return;
  S.busy = true; setSyncState();
  try {
    const r = await api('GET', '/records?since=' + S.rev);
    let changed = 0, bad = 0;
    for (const rec of r.records) {
      if (rec.deleted) { if (S.records.delete(rec.id)) changed++; continue; }
      try {
        S.records.set(rec.id, { rev: rec.rev, obj: await C.decryptRecord(S.vaultKey, rec.id, rec.data) });
        changed++;
      } catch { bad++; }
    }
    S.rev = r.rev; S.lastSync = Date.now();
    if (bad) toast(`${bad} записей не расшифровались — возможна подмена на сервере`, true);
    if (changed) render();
    else if (!quiet) toast('Изменений нет');
  } catch (e) {
    if (e.code !== 401) toast(e.message, true);
  } finally {
    S.busy = false; setSyncState();
  }
}

async function saveRecord(id, obj) {
  const cur = S.records.get(id);
  obj.updatedAt = Date.now();
  const data = await C.encryptRecord(S.vaultKey, id, obj);
  try {
    const r = await api('PUT', '/records/' + id, { baseRev: cur ? cur.rev : 0, data });
    S.records.set(id, { rev: r.rev, obj });
    S.rev = Math.max(S.rev, r.rev - 1);
    return true;
  } catch (e) {
    if (e.code === 409) { toast('Карточку изменили на другом устройстве — загружена свежая версия', true); await sync(); return false; }
    throw e;
  }
}

function sims() { return [...S.records.entries()].filter(([, r]) => r.obj.t === 'sim').map(([id, r]) => ({ id, ...r.obj })); }
function contracts() {
  const m = new Map();
  for (const [, r] of S.records) if (r.obj.t === 'contract') m.set(r.obj.id, r.obj);
  return m;
}
function contractTitle(id, cs = contracts()) { const c = cs.get(id); return c ? (c.label || c.orgName || c.id) : id; }

function matches(o, q) {
  const words = q.toLowerCase().split(/\s+/).filter(Boolean);
  if (!words.length) return true;
  const text = [o.surname, o.name, o.patronymic, o.notes, o.region, o.issuedBy].filter(Boolean).join(' ').toLowerCase();
  const digits = [o.msisdn, o.iccid, (o.series || '') + (o.number || ''), o.accountNo, o.imsi].filter(Boolean);
  return words.every((w) => {
    const d = w.replace(/\D/g, '');
    if (d && d.length === w.replace(/[^\p{L}\d]/gu, '').length) {
      const alt = d.length === 11 && d[0] === '8' ? '7' + d.slice(1) : d;
      return digits.some((x) => x.includes(d) || x.includes(alt));
    }
    return text.includes(w);
  });
}

// ---------------- Главный экран ----------------

async function openMain() {
  $app().replaceChildren(el('div', { class: 'boot' }, el('div', { class: 'spinner' })));
  await sync();
  buildMain();
  clearInterval(S.poll);
  S.poll = setInterval(() => { if (document.visibilityState === 'visible') sync(); }, POLL_MS);
}

let $grid, $chips, $stchips, $summary, $sync;

function buildMain() {
  const search = el('input', { type: 'search', placeholder: 'ФИО, телефон, SIM (ICCID), паспорт', value: S.q, autocomplete: 'off', spellcheck: 'false' });
  let t;
  search.addEventListener('input', () => { clearTimeout(t); t = setTimeout(() => { S.q = search.value; S.shown = PAGE; render(); }, 120); });
  const sbox = el('div', { class: 'search' }, search);
  sbox.insertAdjacentHTML('afterbegin', ICONS.search);
  $sync = el('div', { class: 'sync', title: 'Синхронизация с сервером' }, el('i', { class: 'dot' }), el('span', { text: '' }));
  $sync.onclick = () => sync(false);
  const btn = (icon, title, fn) => el('button', { class: 'icon-btn', icon, title, 'aria-label': title, onclick: fn });
  $chips = el('div', { class: 'chips' });
  $stchips = el('div', { class: 'chips' });
  $summary = el('div', { class: 'summary' });
  $grid = el('div', { class: 'grid' });
  $app().replaceChildren(el('div', { class: 'app' },
    el('header', { class: 'topbar' }, el('div', { class: 'topbar-in' },
      brand('Корпоративные SIM'), sbox, el('div', { class: 'spacer' }), $sync,
      btn('download', 'Выгрузить в Excel (CSV)', exportCsv),
      btn('history', 'Журнал входов и действий', showAudit),
      btn('devices', 'Устройства', showDevices),
      btn('key', 'Сменить мастер-пароль', showPassword),
      btn('lock', 'Заблокировать', () => lock('Заблокировано')))),
    el('main', { class: 'content' }, $summary, $chips, $stchips, $grid)),
  el('button', { class: 'btn primary fab', icon: 'add', onclick: () => openCard(null) }, 'Добавить SIM'));
  render();
  setSyncState();
}

function setSyncState() {
  if (!$sync) return;
  $sync.classList.toggle('busy', S.busy);
  $sync.lastChild.textContent = S.busy ? 'Синхронизация…' : S.lastSync ? 'Обновлено ' + new Date(S.lastSync).toLocaleTimeString('ru-RU', { hour: '2-digit', minute: '2-digit' }) : '';
}

function render() {
  if (!$grid) return;
  const all = sims();
  const cs = contracts();
  // Сводка
  const by = {};
  all.forEach((o) => { const s = status(o); by[s] = (by[s] || 0) + 1; });
  const stat = (n, label) => el('div', { class: 'stat' }, el('b', { class: 'mono', text: String(n) }), el('span', { text: label }));
  $summary.replaceChildren(
    stat(all.length, plural(all.length, 'SIM-карта', 'SIM-карты', 'SIM-карт')),
    stat((by.CONFIRMED || 0) + (by.DONE || 0) + (by.IN_MTS || 0), 'внесено / есть в МТС'),
    stat(by.NEEDED || 0, 'нужно внести'),
    stat((by.PROBLEM || 0) + (by.WAITING || 0), 'проблемы и ожидание'));
  // Контракты
  const counts = {};
  all.forEach((o) => { counts[o.contractId || ''] = (counts[o.contractId || ''] || 0) + 1; });
  const chip = (label, n, on, fn, cls = '', color) => {
    const c = el('button', { class: 'chip ' + cls + (on ? ' on' : ''), onclick: fn }, label, el('span', { class: 'n', text: String(n) }));
    if (color) c.style.setProperty('--c', color);
    return c;
  };
  const ids = Object.keys(counts).filter(Boolean).sort((a, b) => contractTitle(a, cs).localeCompare(contractTitle(b, cs), 'ru'));
  $chips.replaceChildren(chip('Все контракты', all.length, !S.contract, () => { S.contract = null; S.shown = PAGE; render(); }),
    ...ids.map((id) => chip(contractTitle(id, cs), counts[id], S.contract === id, () => { S.contract = id; S.shown = PAGE; render(); })));
  $stchips.replaceChildren(chip('Все статусы', all.length, !S.status, () => { S.status = null; S.shown = PAGE; render(); }),
    ...Object.keys(ST).filter((k) => by[k]).map((k) => chip(ST[k][0], by[k], S.status === k, () => { S.status = S.status === k ? null : k; S.shown = PAGE; render(); }, 'st', ST[k][1])));
  // Карточки
  const list = all.filter((o) => (!S.contract || o.contractId === S.contract) && (!S.status || status(o) === S.status) && matches(o, S.q))
    .sort((a, b) => (!!fullName(b) - !!fullName(a)) || fullName(a).localeCompare(fullName(b), 'ru') || (a.msisdn || '').localeCompare(b.msisdn || ''));
  if (!list.length) {
    const e = el('div', { class: 'empty' });
    e.insertAdjacentHTML('afterbegin', ICONS.sim);
    e.append(el('h3', { text: all.length ? 'Ничего не найдено' : 'Пока пусто' }),
      el('div', { text: all.length ? 'Измените запрос или фильтр' : 'Синхронизируйте приложение на телефоне или добавьте SIM вручную' }));
    $grid.replaceChildren(e);
    return;
  }
  const frag = list.slice(0, S.shown).map((o, i) => card(o, cs, i));
  $grid.replaceChildren(...frag);
  if (list.length > S.shown) $grid.append(el('div', { class: 'more', style: { gridColumn: '1 / -1' } },
    el('button', { class: 'btn ghost', text: `Показать ещё (${list.length - S.shown})`, onclick: () => { S.shown += PAGE; render(); } })));
}

function card(o, cs, i) {
  const st = status(o);
  const name = fullName(o);
  const initials = ((o.surname || '')[0] || '') + ((o.name || '')[0] || '');
  const pill = el('span', { class: 'pill', text: ST[st][0] });
  pill.style.setProperty('--c', ST[st][1]);
  const c = el('div', { class: 'card', tabindex: '0', role: 'button', onclick: () => openCard(o.id), onkeydown: (e) => { if (e.key === 'Enter') openCard(o.id); } },
    el('div', { class: 'avatar', text: initials.toUpperCase() || '#' }),
    el('div', { class: 'card-main' },
      el('div', { class: 'card-top' }, el('div', { class: 'card-name' + (name ? '' : ' none'), text: name || 'Без ФИО' }), pill),
      el('div', { class: 'card-phone mono', text: fmtPhone(o.msisdn) }),
      el('div', { class: 'card-sub mono', text: o.iccid ? 'SIM ' + o.iccid : 'SIM не указана' }),
      el('div', { class: 'card-sub', text: [o.contractId && contractTitle(o.contractId, cs), hasPassport(o) ? `Паспорт ${o.series.slice(0, 2)} ${o.series.slice(2)} ${o.number}` : 'Паспорт не указан'].filter(Boolean).join(' · ') })));
  c.style.animationDelay = Math.min(i, 12) * 22 + 'ms';
  return c;
}

// ---------------- Карточка SIM ----------------

const blobUrls = [];

async function loadPhoto(blobId, box, big) {
  if (!blobId) return;
  box.replaceChildren(el('div', { class: 'spinner sm' }));
  try {
    const enc = await api('GET', '/blobs/' + blobId);
    const plain = await C.decryptBlob(S.vaultKey, blobId, enc);
    const url = URL.createObjectURL(new Blob([plain], { type: 'image/jpeg' }));
    blobUrls.push(url);
    const img = el('img', { src: url, alt: big ? 'Фото паспорта' : 'Фото владельца' });
    img.onclick = () => document.body.append(el('div', { class: 'lightbox', onclick: (e) => e.currentTarget.remove() }, el('img', { src: url, alt: '' })));
    box.replaceChildren(img);
  } catch {
    box.replaceChildren(el('span', { text: 'Фото не загрузилось' }));
  }
}

async function shrinkImage(file) {
  const bmp = await createImageBitmap(file);
  const k = Math.min(1, 2000 / Math.max(bmp.width, bmp.height));
  const cv = document.createElement('canvas');
  cv.width = Math.round(bmp.width * k); cv.height = Math.round(bmp.height * k);
  cv.getContext('2d').drawImage(bmp, 0, 0, cv.width, cv.height);
  const blob = await new Promise((res) => cv.toBlob(res, 'image/jpeg', 0.88));
  return new Uint8Array(await blob.arrayBuffer());
}

function openCard(id) {
  const rec = id ? S.records.get(id) : null;
  const o = rec ? structuredClone(rec.obj) : { t: 'sim', contractId: S.contract || '', inMts: false, address: {} };
  o.address = o.address || {};
  const recId = id || C.newId();
  const cs = contracts();
  const pending = { passportPhoto: null }; // новое фото, ещё не сохранённое в карточке

  const f = {};
  const input = (key, label, opts = {}) => {
    const i = el(opts.area ? 'textarea' : 'input', { class: 'input' + (opts.mono ? ' mono' : ''), value: opts.value ?? (o[key] || ''), inputmode: opts.inputmode, placeholder: opts.placeholder, maxlength: opts.max, spellcheck: 'false' });
    f[key] = i;
    return el('div', { class: 'field' }, el('label', { text: label }), i);
  };
  const sexSeg = el('div', { class: 'seg' });
  let sex = o.sex || null;
  const sexBtn = (v, t) => el('button', { type: 'button', class: sex === v ? 'on' : '', text: t, onclick: (e) => { sex = v; [...sexSeg.children].forEach((b) => b.classList.remove('on')); e.currentTarget.classList.add('on'); } });
  sexSeg.append(sexBtn('M', 'Мужской'), sexBtn('F', 'Женский'));

  const contractSel = el('select', { class: 'input' }, el('option', { value: '', text: 'Без контракта' }),
    ...[...cs.values()].map((c) => el('option', { value: c.id, text: `${c.label || c.orgName || c.id} (${c.id})` })));
  contractSel.value = o.contractId || '';

  const st = status(o);
  const sbox = el('div', { class: 'status-box' }, el('b', { text: ST[st][0] }), el('div', { text: [
    o.messageId && `Заявка ${o.messageId}${o.sentAt ? ' от ' + fmtTime(o.sentAt) : ''}`,
    o.confirmation && 'Госуслуги: ' + (CONFIRM[o.confirmation] || o.confirmation),
    o.checkedAt && 'Проверено ' + fmtTime(o.checkedAt),
    'Внесение в МТС и проверка статуса — в приложении на телефоне.',
  ].filter(Boolean).join('\n') }));
  sbox.style.setProperty('--c', ST[st][1]);

  const passBox = el('div', { class: 'photo', text: 'Фото паспорта нет' });
  const faceBox = el('div', { class: 'photo', text: 'Фото владельца нет' });
  const file = el('input', { type: 'file', accept: 'image/*', class: 'hidden' });
  file.onchange = async () => {
    const fl = file.files[0];
    if (!fl) return;
    try {
      const bytes = await shrinkImage(fl);
      const bid = C.newId();
      await api('PUT', '/blobs/' + bid, await C.encryptBlob(S.vaultKey, bid, bytes), true);
      pending.passportPhoto = bid;
      await loadPhoto(bid, passBox, true);
      toast('Фото зашифровано и загружено — сохраните карточку');
    } catch (e) { toast(e instanceof ApiError ? e.message : 'Не удалось открыть изображение — выберите фото в формате JPG или PNG', true); }
    file.value = '';
  };

  const body = el('div', { class: 'drawer-body' },
    sbox,
    el('div', { class: 'section', text: 'SIM-карта' }),
    el('div', { class: 'field' }, el('label', { text: 'Контракт' }), contractSel),
    el('div', { class: 'row' }, input('msisdn', 'Номер телефона *', { value: fmtPhone(o.msisdn), inputmode: 'tel', mono: true }), input('iccid', 'ICCID', { inputmode: 'numeric', mono: true, max: 22 })),
    (o.accountNo || o.region) ? el('div', { class: 'hint', text: [o.accountNo && 'Лицевой счёт ' + o.accountNo, o.region].filter(Boolean).join(' · ') }) : null,
    el('div', { class: 'section', text: 'Паспорт владельца' }),
    el('div', { class: 'photos' }, passBox, faceBox),
    el('div', { style: { margin: '10px 0 4px' } }, el('button', { class: 'btn ghost sm', icon: 'image', type: 'button', onclick: () => file.click() }, 'Загрузить фото паспорта'), file),
    input('surname', 'Фамилия'), el('div', { class: 'row' }, input('name', 'Имя'), input('patronymic', 'Отчество')),
    el('div', { class: 'field' }, el('label', { text: 'Пол' }), sexSeg),
    el('div', { class: 'row' }, input('birthDate', 'Дата рождения', { value: isoToRu(o.birthDate), placeholder: 'ДД.ММ.ГГГГ', inputmode: 'numeric', mono: true }),
      input('issueDate', 'Дата выдачи', { value: isoToRu(o.issueDate), placeholder: 'ДД.ММ.ГГГГ', inputmode: 'numeric', mono: true })),
    input('birthPlace', 'Место рождения', { area: true }),
    el('div', { class: 'row' }, input('series', 'Серия', { inputmode: 'numeric', mono: true, max: 4 }), input('number', 'Номер', { inputmode: 'numeric', mono: true, max: 6 })),
    input('divisionCode', 'Код подразделения', { placeholder: '000-000', mono: true, max: 7 }),
    input('issuedBy', 'Кем выдан', { area: true, max: 256 }),
    el('div', { class: 'section', text: 'Адрес регистрации' }),
    el('div', { class: 'row' }, input('a_region', 'Регион', { value: o.address.region }), input('a_city', 'Город', { value: o.address.city })),
    input('a_street', 'Улица', { value: o.address.street }),
    el('div', { class: 'row' }, input('a_house', 'Дом', { value: o.address.house }), input('a_apartment', 'Квартира', { value: o.address.apartment })),
    el('div', { class: 'section', text: 'Примечание' }), input('notes', 'Кто, где, для чего', { area: true }));

  const save = el('button', { class: 'btn primary', text: 'Сохранить' });
  const del = id ? el('button', { class: 'btn danger', icon: 'trash', title: 'Удалить карточку' }) : null;
  const scrim = el('div', { class: 'scrim' });
  const drawer = el('aside', { class: 'drawer', role: 'dialog', 'aria-label': 'Карточка SIM' },
    el('div', { class: 'drawer-head' }, el('h3', { text: id ? (fullName(o) || fmtPhone(o.msisdn) || 'SIM-карта') : 'Новая SIM-карта' }),
      el('button', { class: 'icon-btn', icon: 'close', 'aria-label': 'Закрыть', onclick: close })),
    body, el('div', { class: 'drawer-foot' }, del, save));
  document.body.append(scrim, drawer);
  requestAnimationFrame(() => { scrim.classList.add('show'); drawer.classList.add('show'); });
  scrim.onclick = close;
  const esc = (e) => { if (e.key === 'Escape') close(); };
  addEventListener('keydown', esc);
  loadPhoto(o.passportPhoto, passBox, true);
  loadPhoto(o.facePhoto, faceBox, false);

  function close() {
    removeEventListener('keydown', esc);
    scrim.classList.remove('show'); drawer.classList.remove('show');
    setTimeout(() => { scrim.remove(); drawer.remove(); blobUrls.splice(0).forEach((u) => URL.revokeObjectURL(u)); }, 320);
  }

  function bad(key, msg) {
    const i = f[key];
    i.classList.add('err'); i.focus();
    setTimeout(() => i.classList.remove('err'), 600);
    toast(msg, true);
  }

  save.onclick = async () => {
    const v = (k) => f[k].value.trim();
    const msisdn = normMsisdn(v('msisdn'));
    if (!msisdn) return bad('msisdn', 'Номер телефона — 11 цифр, +7 XXX XXX-XX-XX');
    const iccid = v('iccid').replace(/\s/g, '');
    if (iccid && !/^\d{18,22}$/.test(iccid)) return bad('iccid', 'ICCID — 19–20 цифр');
    const dup = sims().find((s) => s.msisdn === msisdn && s.id !== recId);
    if (dup) return bad('msisdn', 'Этот номер уже есть в списке');
    const bd = v('birthDate') ? ruToIso(v('birthDate')) : '';
    if (bd === null) return bad('birthDate', 'Дата рождения: ДД.ММ.ГГГГ');
    const idt = v('issueDate') ? ruToIso(v('issueDate')) : '';
    if (idt === null) return bad('issueDate', 'Дата выдачи: ДД.ММ.ГГГГ');
    if (v('series') && !/^\d{4}$/.test(v('series'))) return bad('series', 'Серия — 4 цифры');
    if (v('number') && !/^\d{6}$/.test(v('number'))) return bad('number', 'Номер — 6 цифр');
    let code = v('divisionCode').replace(/\s/g, '');
    if (/^\d{6}$/.test(code)) code = code.slice(0, 3) + '-' + code.slice(3);
    if (code && !/^\d{3}-\d{3}$/.test(code)) return bad('divisionCode', 'Код подразделения — 000-000');
    const next = { ...o, msisdn, iccid, contractId: contractSel.value, surname: v('surname'), name: v('name'), patronymic: v('patronymic'), sex,
      birthDate: bd, issueDate: idt, birthPlace: v('birthPlace').replace(/\s+/g, ' '), series: v('series'), number: v('number'),
      divisionCode: code, issuedBy: v('issuedBy').replace(/\s+/g, ' '), notes: v('notes'),
      address: { region: v('a_region'), city: v('a_city'), street: v('a_street'), house: v('a_house'), apartment: v('a_apartment'), zip: o.address.zip || '' } };
    const oldPhoto = o.passportPhoto;
    if (pending.passportPhoto) next.passportPhoto = pending.passportPhoto;
    save.disabled = true;
    try {
      if (await saveRecord(recId, next)) {
        if (pending.passportPhoto && oldPhoto) api('DELETE', '/blobs/' + oldPhoto).catch(() => {});
        toast(id ? 'Сохранено' : 'Карточка добавлена');
        render(); close();
      }
    } catch (e) { toast(e.message, true); }
    save.disabled = false;
  };

  if (del) del.onclick = () => confirmBox('Удалить карточку?', 'Карточка исчезнет и на телефоне после синхронизации. В МТС ничего не изменится.', 'Удалить', async () => {
    try {
      await api('DELETE', `/records/${recId}?baseRev=${rec.rev}`);
      S.records.delete(recId);
      [o.passportPhoto, o.facePhoto].filter(Boolean).forEach((b) => api('DELETE', '/blobs/' + b).catch(() => {}));
      toast('Карточка удалена'); render(); close();
    } catch (e) { toast(e.message, true); }
  });
}

// ---------------- Окна ----------------

function modal(title, content, narrow = false) {
  const scrim = el('div', { class: 'scrim show' });
  const m = el('div', { class: 'modal' }, scrim, el('div', { class: 'modal-card' + (narrow ? ' narrow' : '') },
    el('div', { class: 'modal-head' }, el('h3', { text: title }), el('button', { class: 'icon-btn', icon: 'close', 'aria-label': 'Закрыть', onclick: () => m.remove() })),
    el('div', { class: 'modal-body' }, content)));
  scrim.onclick = () => m.remove();
  document.body.append(m);
  return m;
}

function confirmBox(title, text, action, fn) {
  const m = modal(title, el('div', {}, el('p', { class: 'muted', text }),
    el('div', { style: { display: 'flex', gap: '10px', justifyContent: 'flex-end' } },
      el('button', { class: 'btn ghost', text: 'Отмена', onclick: () => m.remove() }),
      el('button', { class: 'btn primary', text: action, onclick: () => { m.remove(); fn(); } }))), true);
}

const EVENTS = { login: 'Вход', 'device-login': 'Вход доверенного устройства', logout: 'Выход', setup: 'Создание хранилища', password: 'Смена пароля',
  'revoke-device': 'Отзыв устройства', delete: 'Удаление записи', start: 'Запуск сервера' };

async function showAudit() {
  const body = el('div', {}, el('div', { class: 'spinner' }));
  modal('Журнал входов и действий', body);
  try {
    const r = await api('GET', '/audit?limit=300');
    body.replaceChildren(el('p', { class: 'muted', text: `На сервере ${r.records} зашифрованных записей, ${(r.bytes / 1048576).toFixed(1)} МБ. Сервер не может их прочитать.` }),
      ...r.entries.map((e) => {
        const d = el('i', { class: 'dot' });
        d.style.setProperty('--c', e.ok ? 'var(--ok)' : 'var(--bad)');
        return el('div', { class: 'list-item' }, d, el('div', {},
          el('div', { class: 't', text: (EVENTS[e.event] || e.event) + (e.ok ? '' : ' — отказ') }),
          el('div', { class: 's', text: [fmtTime(e.time), e.device, e.ip, e.detail].filter(Boolean).join(' · ') })));
      }));
  } catch (e) { body.replaceChildren(el('p', { text: e.message })); }
}

async function showDevices() {
  const body = el('div', {}, el('div', { class: 'spinner' }));
  modal('Доверенные устройства', body);
  const load = async () => {
    const r = await api('GET', '/devices');
    body.replaceChildren(el('p', { class: 'muted', text: 'Телефоны, которым разрешён вход без пароля на 90 дней. Отзовите устройство, если оно потеряно.' }),
      ...(r.devices.length ? r.devices.map((d) => el('div', { class: 'list-item' }, el('div', { style: { flex: '1' } },
        el('div', { class: 't', text: d.name + (d.current ? ' (это устройство)' : '') }),
        el('div', { class: 's', text: `Добавлено ${fmtTime(d.createdAt)} · последний вход ${fmtTime(d.lastSeen)}${d.lastIp ? ' с ' + d.lastIp : ''} · до ${fmtTime(d.expiresAt)}` })),
      el('button', { class: 'btn danger sm', text: 'Отозвать', onclick: () => confirmBox('Отозвать устройство?', `«${d.name}» больше не сможет входить без пароля.`, 'Отозвать',
        async () => { try { await api('DELETE', '/devices/' + d.id); toast('Устройство отозвано'); load(); } catch (e) { toast(e.message, true); } }) })))
        : [el('p', { text: 'Доверенных устройств нет.' })]));
  };
  try { await load(); } catch (e) { body.replaceChildren(el('p', { text: e.message })); }
}

function showPassword() {
  const cur = el('input', { class: 'input', type: 'password', autocomplete: 'current-password' });
  const pw = el('input', { class: 'input', type: 'password', autocomplete: 'new-password' });
  const pw2 = el('input', { class: 'input', type: 'password', autocomplete: 'new-password' });
  const otp = otpInput();
  const err = el('div', { class: 'error-text' });
  const btn = el('button', { class: 'btn primary block', text: 'Сменить пароль' });
  const m = modal('Смена мастер-пароля', el('div', {},
    el('p', { class: 'muted', text: 'Данные не перешифровываются — меняется только ключ к ним. После смены все сессии и доверенные устройства будут отозваны.' }),
    el('div', { class: 'field' }, el('label', { text: 'Текущий пароль' }), cur),
    el('div', { class: 'field' }, el('label', { text: 'Новый пароль' }), pw),
    el('div', { class: 'field' }, el('label', { text: 'Повторите новый пароль' }), pw2),
    el('div', { class: 'field' }, el('label', { text: 'Код подтверждения' }), otp), err, btn), true);
  btn.onclick = async () => {
    err.textContent = '';
    if (strength(pw.value) < 3) return (err.textContent = 'Слишком простой новый пароль');
    if (pw.value !== pw2.value) return (err.textContent = 'Новые пароли не совпадают');
    btn.disabled = true; btn.replaceChildren(el('div', { class: 'spinner sm' }), 'Вычисляю ключи…');
    try {
      const pre = await api('GET', '/prelogin');
      const old = await C.deriveKeys(cur.value, pre.kdf);
      let unwrapped;
      try {
        unwrapped = await C.unwrapVaultKey(old.kek, (await api('GET', '/vault-key')).wrappedKey, true);
      } catch (e) {
        throw new Error(e instanceof ApiError ? e.message : 'Неверный текущий пароль');
      }
      const n = await C.rewrap(unwrapped.raw, pw.value);
      unwrapped.raw.fill(0);
      await api('POST', '/password', { totp: otp.value(), currentAuthKey: old.authKey, kdf: n.kdf, authKey: n.authKey, wrappedKey: n.wrappedKey });
      m.remove();
      lock('Пароль изменён. Войдите с новым паролем.');
    } catch (e) { err.textContent = e.message; btn.disabled = false; btn.textContent = 'Сменить пароль'; otp.clear(); }
  };
}

function exportCsv() {
  const cs = contracts();
  const list = sims().filter((o) => (!S.contract || o.contractId === S.contract) && (!S.status || status(o) === S.status) && matches(o, S.q));
  if (!list.length) return toast('Нечего выгружать');
  const head = ['Контракт', 'Номер контракта', 'Лицевой счёт', 'Телефон', 'ICCID', 'Фамилия', 'Имя', 'Отчество', 'Пол', 'Дата рождения', 'Место рождения',
    'Серия', 'Номер', 'Дата выдачи', 'Код подразделения', 'Кем выдан', 'Статус', 'Номер заявки', 'Примечание'];
  const q = (v) => '"' + String(v ?? '').replace(/"/g, '""') + '"';
  const rows = list.map((o) => [contractTitle(o.contractId, cs), o.contractId, o.accountNo, o.msisdn, o.iccid, o.surname, o.name, o.patronymic,
    o.sex === 'M' ? 'М' : o.sex === 'F' ? 'Ж' : '', isoToRu(o.birthDate), o.birthPlace, o.series, o.number, isoToRu(o.issueDate), o.divisionCode, o.issuedBy,
    ST[status(o)][0], o.messageId, o.notes]);
  const csv = '﻿' + [head, ...rows].map((r) => r.map(q).join(';')).join('\r\n');
  const url = URL.createObjectURL(new Blob([csv], { type: 'text/csv;charset=utf-8' }));
  const a = el('a', { href: url, download: `SIM-${new Date().toISOString().slice(0, 16).replace(/[T:]/g, '-')}.csv` });
  document.body.append(a); a.click(); a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 2000);
  toast(`Выгружено ${list.length} SIM. Файл не зашифрован — храните его бережно.`);
}

boot();
