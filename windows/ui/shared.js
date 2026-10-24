// What both windows share: the connection, your modes, and talking to the
// server (the `scribe` function in the daybook repo, the same one the phone uses).
const T = window.__TAURI__;
export const invoke = T.core.invoke;
export const listen = T.event.listen;
export const emitTo = T.event.emitTo;

const get = (k, fallback = null) => {
  try { const v = localStorage.getItem(`scribe:${k}`); return v == null ? fallback : JSON.parse(v); } catch { return fallback; }
};
const set = (k, v) => { try { v == null ? localStorage.removeItem(`scribe:${k}`) : localStorage.setItem(`scribe:${k}`, JSON.stringify(v)); } catch { /* full */ } };
export const store = { get, set };

export const INKWELL = 'https://laserbunny-coder.github.io/inkwell/?connect=scribe';
export const connected = () => !!(get('token') && get('url'));

/** Takes a scribe://connect?token=…&url=… link (from Inkwell) out of any text; true if it connected. */
export function connectFrom(text) {
  const m = /scribe:\/\/connect\?(\S+)/.exec(text || '');
  if (!m) return false;
  const q = new URLSearchParams(m[1]);
  const token = q.get('token') || '';
  const url = q.get('url') || '';
  if (!/^[0-9a-f]{48}$/.test(token) || !url.startsWith('https://')) return false;
  set('token', token);
  set('url', url.replace(/\/+$/, ''));
  return true;
}

export class ScribeError extends Error {
  constructor(message, code = null, status = 0) { super(message); this.code = code; this.status = status; }
}

/** One call to the server: JSON in (or a form), JSON out, errors in plain words. */
export async function api(path, { body, form, method, signal } = {}) {
  if (!connected()) throw new ScribeError('Scribe isn’t connected yet. Open Settings to connect it.', 'not-connected');
  const headers = { Authorization: `Bearer ${get('token')}` };
  if (body) headers['Content-Type'] = 'application/json';
  let res;
  try {
    res = await fetch(`${get('url')}/scribe${path}`, {
      method: method || (body || form ? 'POST' : 'GET'),
      headers,
      body: body ? JSON.stringify(body) : form,
      signal
    });
  } catch (e) {
    if (e.name === 'AbortError') throw e;
    throw new ScribeError('Couldn’t reach the server. Check the internet connection.', 'offline');
  }
  const data = await res.json().catch(() => ({}));
  if (!res.ok) throw new ScribeError(data.error || `The server said no (${res.status}).`, data.code, res.status);
  return data;
}

// Shown until your own modes have been fetched once.
const STARTER = [
  { id: 'text', name: 'Text message', prompt: '', apps: [] },
  { id: 'email', name: 'Email', prompt: '', apps: ['outlook.exe', 'thunderbird.exe'] },
  { id: 'clean', name: 'Clean only', prompt: '', apps: [] },
  { id: 'idea', name: 'Idea', prompt: '', apps: [] }
];

export const modes = () => { const m = get('modes'); return Array.isArray(m) && m.length ? m : STARTER; };

export async function refreshModes() {
  const { modes: fresh } = await api('/modes');
  if (Array.isArray(fresh) && fresh.length) set('modes', fresh);
  return modes();
}

/**
 * The mode for a program: the one you last picked there by hand, else the
 * first mode listing it (by program name like "outlook.exe", or a word in
 * the window title like "Gmail"), else your first mode.
 */
export function modeFor(app, title = '') {
  const all = modes();
  const chosen = get(`chosen:${app}`);
  const t = title.toLowerCase();
  return all.find((m) => m.id === chosen)
    || all.find((m) => (m.apps || []).some((a) => a.toLowerCase() === app))
    || all.find((m) => (m.apps || []).some((a) => !a.includes('.') && a.length > 2 && t.includes(a.toLowerCase())))
    || all[0];
}

export const nameOf = (id) => modes().find((m) => m.id === id)?.name || id;

// A recording that hasn't been written out yet stays here (not just in
// memory), so nothing said is lost if something goes wrong.
function db() {
  return new Promise((resolve, reject) => {
    const r = indexedDB.open('scribe', 1);
    r.onupgradeneeded = () => r.result.createObjectStore('audio');
    r.onsuccess = () => resolve(r.result);
    r.onerror = () => reject(r.error);
  });
}
async function idb(mode, fn) {
  const d = await db();
  return new Promise((resolve, reject) => {
    const tx = d.transaction('audio', mode);
    const req = fn(tx.objectStore('audio'));
    tx.oncomplete = () => resolve(req?.result);
    tx.onerror = () => reject(tx.error);
  });
}
export const pending = {
  save: (rec) => idb('readwrite', (s) => s.put(rec, 'pending')).catch(() => {}),
  load: () => idb('readonly', (s) => s.get('pending')).catch(() => null),
  clear: () => idb('readwrite', (s) => s.delete('pending')).catch(() => {})
};

export const clock = (ms) => {
  const s = Math.floor(ms / 1000);
  const h = Math.floor(s / 3600);
  const mm = String(Math.floor((s % 3600) / 60));
  const ss = String(s % 60).padStart(2, '0');
  return h ? `${h}:${mm.padStart(2, '0')}:${ss}` : `${mm}:${ss}`;
};
