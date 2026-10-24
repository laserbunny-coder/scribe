// The pill at the bottom of the screen. Ctrl + Shift + Space starts it
// listening, the same keys again send it off, and the answer is typed into
// the program you were in. Then a small card offers Original, the other
// modes and Undo for a few seconds.
import {
  invoke, listen, store, connected, api, modes, refreshModes, modeFor, nameOf, pending, clock
} from './shared.js';

const root = document.getElementById('root');
const WIDTH = 560;

let state = 'idle'; // idle | recording | working | done | copied | failed | notice
let target = { app: '', title: '' };
let mode = null; // the mode id this dictation uses
let picking = false; // the mode chips are showing while recording
let rec = null;
let job = null;
let last = null; // { original, text, mode, outcome }
let failure = null; // { message, retry }
let notice = '';
let hideTimer = null;
let ticker = null;

listen('key', ({ payload }) => {
  if (payload === 'talk') state === 'recording' ? stop() : state === 'working' ? null : start();
  else if (payload === 'mode' && state === 'recording') pick(nextMode());
  else if (payload === 'cancel') cancel();
  else if (payload === 'retry') retry();
});

const appName = () => {
  const a = target.app.replace(/\.exe$/, '');
  return a ? a[0].toUpperCase() + a.slice(1) : 'it';
};
// What the server is told about where this goes ("outlook.exe: Re: Shoot dates").
const where = () => (target.title ? `${target.app}: ${target.title}` : target.app).slice(0, 100);

async function start() {
  clearTimeout(hideTimer);
  if (!connected()) return show('notice', 'Scribe isn’t connected yet. Open Settings from the tray icon to connect it.');
  target = (await invoke('take_target').catch(() => null)) || { app: '', title: '' };
  mode = modeFor(target.app, target.title).id;
  picking = false;
  refreshModes().then(() => state === 'recording' && render()).catch(() => {});
  let stream;
  try {
    stream = await navigator.mediaDevices.getUserMedia({
      audio: { channelCount: 1, echoCancellation: true, noiseSuppression: true, autoGainControl: true }
    });
  } catch {
    return show('failed', 'Scribe can’t use the microphone. In Windows, open Settings > Privacy & security > Microphone and let desktop apps use it.');
  }
  const type = MediaRecorder.isTypeSupported('audio/webm;codecs=opus') ? 'audio/webm;codecs=opus' : 'audio/webm';
  // 32 kbps: about 15 MB an hour, so even a long ramble goes through in one piece.
  const recorder = new MediaRecorder(stream, { mimeType: type, audioBitsPerSecond: 32000 });
  const chunks = [];
  recorder.ondataavailable = (e) => e.data.size && chunks.push(e.data);
  const ctx = new AudioContext();
  const analyser = ctx.createAnalyser();
  analyser.fftSize = 512;
  ctx.createMediaStreamSource(stream).connect(analyser);
  rec = { recorder, stream, chunks, ctx, analyser, type, started: Date.now(), levels: new Array(24).fill(0) };
  recorder.start(1000);
  await invoke('pill_keys', { on: true }).catch(() => {});
  state = 'recording';
  render();
  ticker = setInterval(tick, 80);
}

function tick() {
  if (state !== 'recording' || !rec) return;
  const data = new Uint8Array(rec.analyser.fftSize);
  rec.analyser.getByteTimeDomainData(data);
  let peak = 0;
  for (const v of data) peak = Math.max(peak, Math.abs(v - 128) / 128);
  rec.levels.push(Math.min(1, peak * 2.2));
  rec.levels.shift();
  const time = root.querySelector('.time');
  if (time) time.textContent = clock(Date.now() - rec.started);
  const c = root.querySelector('canvas');
  if (!c) return;
  const g = c.getContext('2d');
  const w = c.width / rec.levels.length;
  const bar = w * 0.55;
  g.clearRect(0, 0, c.width, c.height);
  g.fillStyle = '#f28b4b';
  rec.levels.forEach((l, i) => {
    const h = c.height * (0.15 + 0.85 * l);
    g.beginPath();
    g.roundRect(i * w + (w - bar) / 2, (c.height - h) / 2, bar, h, bar / 2);
    g.fill();
  });
}

/** Ends the recording; the audio, or null if it was thrown away. */
async function finish(keep) {
  clearInterval(ticker);
  if (!rec) return null;
  const r = rec;
  rec = null;
  const done = new Promise((resolve) => { r.recorder.onstop = resolve; });
  if (r.recorder.state !== 'inactive') r.recorder.stop();
  await done;
  r.stream.getTracks().forEach((t) => t.stop());
  r.ctx.close().catch(() => {});
  return keep ? { audio: new Blob(r.chunks, { type: r.type }), ms: Date.now() - r.started } : null;
}

async function stop() {
  const got = await finish(true);
  if (!got) return;
  const j = { ...got, mode, app: target.app, where: where() };
  await pending.save(j);
  send(j);
}

async function send(j) {
  state = 'working';
  render();
  const form = new FormData();
  form.append('audio', j.audio, 'scribe.webm');
  form.append('mode', j.mode);
  form.append('app', j.where || j.app || '');
  form.append('duration', String(Math.round(j.ms)));
  job = new AbortController();
  const timeout = setTimeout(() => job?.abort('timeout'), 6 * 60 * 1000);
  try {
    const res = await api('', { form, signal: job.signal });
    await pending.clear();
    if (!res.text) return show('notice', 'Didn’t catch anything that time.');
    last = { original: res.original, text: res.text, mode: res.mode, warning: res.warning || '' };
    store.set('last', { original: res.original, text: res.text, mode: res.mode, at: Date.now() });
    await typeIn(res.text, false);
  } catch (e) {
    if (job?.signal.aborted && job.signal.reason !== 'timeout') return;
    const message = job?.signal.reason === 'timeout'
      ? 'That took too long. Your recording is kept.'
      : `${e.message} Your recording is kept.`;
    failure = { retry: true };
    show('failed', message);
  } finally {
    clearTimeout(timeout);
    job = null;
  }
}

async function retry() {
  const j = await pending.load();
  if (!j) return show('notice', 'There’s no recording waiting.');
  mode = j.mode;
  send(j);
}

async function typeIn(text, replace, force = false) {
  const outcome = await invoke('type_in', { text, replace, force }).catch(() => 'copied');
  last.outcome = outcome;
  last.text = text;
  show(outcome === 'typed' ? 'done' : 'copied');
}

async function redo(id) {
  if (!last) return;
  clearTimeout(hideTimer);
  const replace = last.outcome === 'typed';
  if (id === 'original') {
    last.mode = 'original';
    return typeIn(last.original, replace);
  }
  store.set(`chosen:${target.app}`, id);
  mode = id;
  state = 'working';
  render();
  try {
    const res = await api('', { body: { action: 'redo', original: last.original, mode: id, app: where() } });
    last.mode = res.mode;
    store.set('last', { original: last.original, text: res.text, mode: res.mode, at: Date.now() });
    await typeIn(res.text, replace);
  } catch (e) {
    failure = { retry: false };
    show('failed', e.message);
  }
}

async function undo() {
  await invoke('undo').catch(() => false);
  hide();
}

async function cancel() {
  if (state === 'recording') await finish(false);
  else if (state === 'working') job?.abort('cancel');
  hide();
}

function nextMode() {
  const all = modes();
  const i = all.findIndex((m) => m.id === mode);
  return all[(i + 1) % all.length].id;
}

function pick(id) {
  mode = id;
  picking = true;
  if (target.app) store.set(`chosen:${target.app}`, id);
  render();
}

function show(s, message = '') {
  state = s;
  // Ctrl + Shift + S and Esc go back to the programs once Scribe isn't listening.
  invoke('pill_keys', { on: false }).catch(() => {});
  notice = message;
  render();
  const wait = { done: 8000, copied: 20000, notice: 6000, failed: 30000 }[s];
  if (wait) autoHide(wait);
}

function autoHide(ms) {
  clearTimeout(hideTimer);
  hideTimer = setTimeout(hide, ms);
}

function hide() {
  clearTimeout(hideTimer);
  clearInterval(ticker);
  state = 'idle';
  root.replaceChildren();
  invoke('pill_keys', { on: false }).catch(() => {});
  invoke('hide_pill').catch(() => {});
}

// ---------- drawing ----------

const el = (tag, props = {}, ...kids) => {
  const e = Object.assign(document.createElement(tag), props);
  e.append(...kids.filter((k) => k != null && k !== false));
  return e;
};
const button = (text, cls, onclick) => el('button', { className: cls, textContent: text, onclick });

function chips(onPick, current, extra = []) {
  return el('div', { className: 'chips' },
    ...extra,
    ...modes().map((m) => button(m.name, `chip${m.id === current ? ' on' : ''}`, () => onPick(m.id))));
}

function render() {
  clearTimeout(hideTimer);
  const parts = [];
  if (state === 'recording') {
    if (picking) parts.push(el('div', { className: 'card' }, chips(pick, mode)));
    parts.push(el('div', { className: 'card pill' },
      el('span', { className: 'dot' }),
      el('span', { className: 'time', textContent: clock(Date.now() - (rec?.started || Date.now())) }),
      el('canvas', { width: 192, height: 48 }),
      button(nameOf(mode), 'chip on', () => pick(nextMode())),
      button('Done', 'btn fill', stop),
      button('×', 'btn plain', cancel)));
    parts.push(el('div', { className: 'hint' },
      kbdLine([['Ctrl + Shift + Space', 'type it in'], ['Ctrl + Shift + S', 'mode'], ['Esc', 'throw away']])));
  } else if (state === 'working') {
    parts.push(el('div', { className: 'card pill' },
      el('span', { className: 'spin' }),
      el('span', { textContent: 'Writing it out…' }),
      el('span', { className: 'chip on', textContent: nameOf(mode) }),
      button('×', 'btn plain', cancel)));
  } else if (state === 'done' || state === 'copied') {
    const label = last.mode === 'original' ? 'Typed in as you said it' : `Typed in as ${nameOf(last.mode)}`;
    const head = state === 'done'
      ? el('div', { className: 'row' }, el('strong', { textContent: label }),
        button('Undo', 'btn', undo), button('×', 'btn plain x', hide))
      : el('div', { className: 'row' }, el('strong', { textContent: 'Copied. Press Ctrl + V where you want it.' }),
        target.app && button(`Type it into ${appName()}`, 'btn fill', () => typeIn(last.text, false, true)),
        button('×', 'btn plain x', hide));
    const card = el('div', { className: 'card' }, head,
      last.warning && el('div', { className: 'says', textContent: last.warning }),
      el('div', { className: 'dim', style: 'margin: 10px 0 6px; font-size: 12px', textContent: 'Redo the same words as' }),
      chips(redo, last.mode, [button('Original', `chip${last.mode === 'original' ? ' on' : ''}`, () => redo('original'))]));
    card.onmouseenter = () => clearTimeout(hideTimer);
    card.onmouseleave = () => autoHide(4000);
    parts.push(card);
  } else if (state === 'failed') {
    parts.push(el('div', { className: 'card' },
      el('div', { textContent: notice }),
      el('div', { className: 'row', style: 'margin-top: 10px' },
        failure?.retry && button('Try again', 'btn fill', retry),
        button('Settings', 'btn', () => { invoke('open_settings'); hide(); }),
        button('×', 'btn plain x', hide))));
  } else if (state === 'notice') {
    parts.push(el('div', { className: 'card row' }, el('span', { textContent: notice }),
      !connected() && button('Settings', 'btn fill', () => { invoke('open_settings'); hide(); }),
      button('×', 'btn plain x', hide)));
  }
  root.replaceChildren(...parts);
  if (!parts.length) return;
  tick();
  requestAnimationFrame(() => invoke('show_pill', { width: WIDTH, height: Math.ceil(root.getBoundingClientRect().height) }));
}

function kbdLine(pairs) {
  const span = el('span');
  pairs.forEach(([keys, what], i) => {
    if (i) span.append('   ');
    span.append(el('kbd', { textContent: keys }), ` ${what}`);
  });
  return span;
}
