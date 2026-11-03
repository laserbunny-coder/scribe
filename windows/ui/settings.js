// Scribe's Settings window: connect, try it, your modes, the keys, start with
// Windows, and the last thing it typed.
import {
  invoke, emitTo, store, connected, connectFrom, api, modes, refreshModes, nameOf, pending, INKWELL
} from './shared.js';

const T = window.__TAURI__;
const page = document.getElementById('page');
let editing = null; // the mode open for changes ({} for a new one), with what's been typed or said into it so far
let listening = null; // a spoken change being recorded: { recorder, stream, chunks, type, started }
let message = '';
let update = null;
// Kept across redraws so what you tried isn't wiped.
const tryBox = Object.assign(document.createElement('textarea'), { id: 'try', placeholder: 'Click here, press Ctrl + Shift + Space, say something, and press it again.' });

const el = (tag, props = {}, ...kids) => {
  const e = Object.assign(document.createElement(tag), props);
  e.append(...kids.filter((k) => k != null && k !== false));
  return e;
};
const MODELS = [
  ['haiku', 'Haiku: quick and cheap, for texts'],
  ['sonnet', 'Sonnet: in between, for emails'],
  ['opus', 'Opus: the best writing, for ideas and characters']
];
const modelName = (id) => ({ haiku: 'Haiku', sonnet: 'Sonnet', opus: 'Opus' })[id] || 'Opus';
const button = (text, cls, onclick) => el('button', { className: cls, textContent: text, onclick });

// Copying the link in Inkwell is enough: Scribe looks at the clipboard when you come back.
async function lookForLink() {
  if (connected()) return;
  const text = await invoke('read_clipboard').catch(() => '');
  if (connectFrom(text)) {
    await invoke('copy_text', { text: '' }).catch(() => {});
    message = 'Connected.';
    refreshModes().then(render).catch(render);
    render();
  }
}

async function render() {
  const has = await pending.load();
  const last = store.get('last');
  const auto = await invoke('autostart').catch(() => false);
  const paste = el('input', { placeholder: 'Or paste the scribe://connect link here' });
  paste.oninput = () => {
    if (connectFrom(paste.value)) { message = 'Connected.'; refreshModes().then(render).catch(render); render(); }
  };

  page.replaceChildren(...[
    el('h2', { textContent: 'Connect' }),
    connected()
      ? el('div', { className: 'card good' },
        el('p', { className: 'ok', textContent: message || 'Connected through Inkwell. Your modes are the same as on your phone.' }),
        el('div', { className: 'row' }, button('Connect again', 'btn', () => {
          store.set('token', null); store.set('url', null); message = ''; render();
        })))
      : el('div', { className: 'card' },
        el('p', { textContent: '1. Open Inkwell, then choose Connect in the box that asks.' }),
        el('p', { textContent: '2. Copy the link it shows (Copy button).' }),
        el('p', { textContent: '3. Come back here. Scribe picks it up from the clipboard.' }),
        el('div', { className: 'row' }, button('Open Inkwell', 'btn fill', () => T.opener.openUrl(INKWELL))),
        paste),

    el('h2', { textContent: 'Try it' }),
    tryBox,

    has && el('div', { className: 'card' },
      el('p', { textContent: 'A recording didn’t go through yet. It’s kept until it does.' }),
      el('div', { className: 'row' },
        button('Try again', 'btn fill', () => emitTo('pill', 'key', 'retry')),
        button('Throw it away', 'btn', async () => { await pending.clear(); render(); }))),

    el('h2', { textContent: 'Your modes' }),
    el('p', { className: 'dim', textContent: 'Shared with your phone. Click one to read or change it. While talking, Ctrl + Shift + S switches mode; Scribe remembers your pick for that program.' }),
    ...modes().map((m) => (editing && editing.id === m.id ? editor(editing) : el('div', {
      className: 'card mode', onclick: () => { stopListening(); editing = { ...m }; render(); }
    }, el('strong', { textContent: m.name }), el('span', { className: 'dim', textContent: `  ·  ${modelName(m.model)}${m.keep ? '  ·  Kept in Inkwell' : ''}` }), el('div', { className: 'prompt', textContent: m.prompt || 'As you said it.' })))),
    editing && !editing.id ? editor(editing) : el('div', { className: 'row' }, button('New mode', 'btn', () => { stopListening(); editing = {}; render(); })),

    el('h2', { textContent: 'Keys' }),
    el('div', { className: 'card keys' },
      keyRow('Ctrl + Shift + Space', 'Start talking, then type it in'),
      keyRow('Ctrl + Shift + S', 'Next mode (while talking)'),
      keyRow('Esc', 'Throw it away (while talking)')),

    el('h2', { textContent: 'Start with Windows' }),
    el('label', { className: 'switch' },
      el('input', { type: 'checkbox', checked: auto, style: 'width:auto;margin:0', onchange: async (e) => { await invoke('autostart', { on: e.target.checked }); } }),
      el('span', { textContent: 'Keep Scribe ready in the tray from when you sign in' })),

    last && el('h2', { textContent: 'Last dictation' }),
    last && el('div', { className: 'card' },
      el('p', { className: 'dim', textContent: `${nameOf(last.mode)} · ${new Date(last.at).toLocaleString([], { weekday: 'short', hour: 'numeric', minute: '2-digit' })}` }),
      el('p', { textContent: last.text, style: 'white-space: pre-wrap; user-select: text' }),
      el('div', { className: 'row' }, button('Copy', 'btn', () => invoke('copy_text', { text: last.text })))),

    el('h2', { textContent: 'About' }),
    el('p', { className: 'dim', id: 'version' }),
    update && el('div', { className: 'card' }, el('p', { textContent: `A new version (${update.version}) is ready.` }),
      el('div', { className: 'row' }, button('Download it', 'btn fill', () => T.opener.openUrl(update.url))))
  ].filter((x) => x != null && x !== false));
  T.app.getVersion().then((v) => { const e = document.getElementById('version'); if (e) e.textContent = `Scribe ${v}. Closing this window keeps Scribe in the tray; Quit is in the tray icon’s menu.`; });
}

function keyRow(keys, what) {
  return el('div', {}, el('kbd', { textContent: keys }), el('span', { className: 'dim', textContent: what }));
}

function editor(m) {
  const name = el('input', { value: m.name || '', placeholder: 'Name, like Work Slack' });
  const prompt = el('textarea', { value: m.prompt || '', placeholder: 'How it writes, in plain words', className: 'prompt-box' });
  const apps = el('input', { value: (m.apps || []).join(', '), placeholder: 'Programs it picks itself in, like slack.exe, or Gmail' });
  const model = el('select', {}, ...MODELS.map(([id, label]) => el('option', { value: id, textContent: label, selected: (m.model || (m.id ? 'opus' : 'haiku')) === id })));
  // Keep in Inkwell: the recording and its words go on that day's Inkwell page.
  const keep = el('input', { type: 'checkbox', style: 'width:auto;margin:0', checked: m.keep ?? ['idea', 'journal', 'scene'].includes(m.id) });
  const keepRow = el('label', { className: 'switch', style: 'margin:10px 0' }, keep, el('span', { textContent: 'Keep in Inkwell: the recording and its words go on that day’s page' }));
  const error = el('p', { className: m.status?.bad ? 'error' : 'dim', textContent: m.status?.text || '' });
  const fail = (text) => { error.className = 'error'; error.textContent = text; };
  const note = el('p', { className: 'dim', textContent: m.added ? '' : 'Type at the end to add to it, paste in a whole new prompt, or say what to change.' });
  if (m.added) note.append('Added to the end (not saved yet): ', el('span', { className: 'added', textContent: m.added }));
  // What's in the boxes now, so a redraw or a spoken change starts from it.
  const now = () => ({ ...m, status: null, name: name.value.trim(), prompt: prompt.value.trim(), apps: apps.value.split(',').map((a) => a.trim()).filter(Boolean), model: model.value, keep: keep.checked });
  // Kept as you type, so a redraw (Scribe refreshes when the window gets focus) doesn't wipe it.
  for (const box of [name, prompt, apps, model, keep]) box.addEventListener(box === keep || box === model ? 'change' : 'input', () => { if (editing) Object.assign(editing, now(), { status: m.status }); });
  const save = async () => {
    stopListening();
    const { added, status, ...rest } = now();
    const mode = { ...rest, id: m.id || '' };
    if (!mode.name) { fail('A mode needs a name.'); return; }
    try { await api('', { body: { action: 'save-mode', mode } }); await refreshModes(); editing = null; render(); } catch (e) { fail(e.message); }
  };
  const remove = async () => {
    try { await api('', { body: { action: 'delete-mode', id: m.id } }); await refreshModes(); editing = null; render(); } catch (e) { fail(e.message); }
  };
  // A prompt written with Claude in another window: copy it there, then this swaps it in.
  const paste = async () => {
    const text = String(await invoke('read_clipboard').catch(() => '') || '').trim();
    if (!text) { fail('Copy the new prompt first, then click Paste over it.'); return; }
    if (/^scribe:\/\/connect/.test(text)) { fail('That’s the connect link, not a prompt.'); return; }
    editing = { ...now(), prompt: text, added: '' };
    render();
  };
  const say = async () => {
    if (listening) { await changeBySpeaking(now()); return; }
    editing = now();
    await startListening(error);
  };
  const sayLabel = listening ? 'Stop, and add it' : 'Say a change';
  return el('div', { className: 'card' }, el('strong', { textContent: m.id ? m.name : 'New mode' }), name, prompt, note, model, apps, keepRow, error,
    el('div', { className: 'row' }, button('Save', 'btn fill', save), button('Cancel', 'btn', () => { stopListening(); editing = null; render(); }),
      button(sayLabel, listening ? 'btn rec on' : 'btn rec', say), button('Paste over it', 'btn', paste),
      m.id && button('Remove', 'btn plain', remove)));
}

// ---- Changing a mode by saying it: "also break long ones into paragraphs". ----
async function startListening(error) {
  let stream;
  try {
    stream = await navigator.mediaDevices.getUserMedia({ audio: { channelCount: 1, echoCancellation: true, noiseSuppression: true, autoGainControl: true } });
  } catch {
    error.className = 'error';
    error.textContent = 'Scribe can’t use the microphone. In Windows, open Settings > Privacy & security > Microphone and let desktop apps use it.';
    return;
  }
  const type = MediaRecorder.isTypeSupported('audio/webm;codecs=opus') ? 'audio/webm;codecs=opus' : 'audio/webm';
  const recorder = new MediaRecorder(stream, { mimeType: type, audioBitsPerSecond: 32000 });
  const chunks = [];
  recorder.ondataavailable = (e) => e.data.size && chunks.push(e.data);
  recorder.start(1000);
  listening = { recorder, stream, chunks, type, started: Date.now() };
  render();
}

/** Ends a recording; its audio, or null if there wasn't one. */
async function stopListening() {
  if (!listening) return null;
  const r = listening;
  listening = null;
  const done = new Promise((resolve) => { r.recorder.onstop = resolve; });
  if (r.recorder.state !== 'inactive') r.recorder.stop();
  await done;
  r.stream.getTracks().forEach((t) => t.stop());
  return { audio: new Blob(r.chunks, { type: r.type }), ms: Date.now() - r.started };
}

async function changeBySpeaking(current) {
  const got = await stopListening();
  if (!got) return;
  editing = { ...current, added: '', status: { text: 'Working it in…' } };
  await render();
  const form = new FormData();
  form.append('action', 'make-mode');
  form.append('audio', got.audio, 'change.webm');
  form.append('duration', String(Math.round(got.ms)));
  const { added, status, ...mode } = current;
  form.append('current', JSON.stringify({ ...mode, id: mode.id || '' }));
  try {
    const res = await api('', { form });
    // Nothing is saved until Save; Cancel leaves the mode as it was.
    if (editing) editing = { ...current, ...res.mode, id: current.id, keep: current.keep, added: res.added || '', status: null };
  } catch (e) {
    if (editing) editing = { ...current, status: { text: e.message, bad: true } };
  }
  render();
}

async function checkForUpdate() {
  try {
    const res = await fetch('https://api.github.com/repos/laserbunny-coder/scribe/releases/tags/windows');
    if (!res.ok) return;
    const rel = await res.json();
    const latest = /(\d+\.\d+\.\d+)/.exec(rel.name || '')?.[1];
    const asset = (rel.assets || []).find((a) => a.name.endsWith('.exe'));
    const mine = await T.app.getVersion();
    const newer = (a, b) => { const x = a.split('.').map(Number); const y = b.split('.').map(Number); for (let i = 0; i < 3; i++) if (x[i] !== y[i]) return x[i] > y[i]; return false; };
    if (latest && asset && newer(latest, mine)) { update = { version: latest, url: asset.browser_download_url }; render(); }
  } catch { /* offline */ }
}

// Started for the first time: keep it ready from sign-in.
if (!store.get('autostartSet')) { invoke('autostart', { on: true }).catch(() => {}); store.set('autostartSet', true); }
window.addEventListener('focus', () => { lookForLink(); if (connected()) refreshModes().then(render).catch(() => {}); });
render();
lookForLink();
if (connected()) refreshModes().then(render).catch(() => {});
checkForUpdate();
