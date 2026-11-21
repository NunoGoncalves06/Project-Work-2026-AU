// AU Parking admin dashboard. Plain DOM, no innerHTML: every API value goes in as text.
// Role checks here only hide buttons; the server decides what each role may do (SecurityConfig).
import * as auth from './auth.js';

const RANK = { viewer: 1, operator: 2, admin: 3 };
const STATE_LABEL = {
  Updated: 'Updated',
  Outdated: 'Outdated',
  Updating: 'Updating...',
  ErrorUpdating: 'Error Updating',
  UpdateSuccessful: 'Update Successful',
  Unavailable: 'Unavailable',
};

const $ = sel => document.querySelector(sel);
const view = $('#view');
let me = null;
let cleanup = [];
const can = role => !!me && RANK[me.role] >= RANK[role];

// ---------- DOM helpers ----------

/** h('p', {class: 'x', text: 'y', onclick: f}, child...) — attributes via setAttribute, text via text nodes. */
function h(tag, props = {}, ...kids) {
  const el = document.createElement(tag);
  for (const [k, v] of Object.entries(props)) {
    if (v == null || v === false) continue;
    if (k === 'class') el.className = v;
    else if (k === 'text') el.textContent = v;
    else if (k.startsWith('on')) el.addEventListener(k.slice(2), v);
    else el.setAttribute(k, v === true ? '' : String(v));
  }
  el.append(...kids.flat().filter(c => c != null && c !== false));
  return el;
}

/** Replaces a container's children and puts focus back on the element with the same data-key. */
function swap(container, ...kids) {
  const key = container.contains(document.activeElement) ? document.activeElement.dataset.key : null;
  container.replaceChildren(...kids.flat().filter(Boolean));
  if (key) container.querySelector(`[data-key="${CSS.escape(key)}"]`)?.focus();
}

const field = text => h('p', { class: 'field', text });
const plate = () => h('span', { class: 'plate', 'aria-hidden': 'true', text: 'P' });
const tear = () => h('hr', { class: 'tear' });
const fullStamp = () => h('span', { class: 'stamp-full', text: 'FULL' });
const lotName = lot => `${lot.address.street} ${lot.address.houseNr}`;
const time = d => d.toLocaleTimeString('en-GB');
// what Caddy answers while the backend restarts during an update
const RESTARTING = [502, 503, 504];
const shortSha = s => (s ? s.slice(0, 7) : 'unknown');

function printStamp() {
  const el = h('p', { class: 'printed' });
  el.reprint = () => {
    el.textContent = 'Printed ' + time(new Date());
    el.classList.remove('ink');
    void el.offsetWidth; // restart the ink animation
    el.classList.add('ink');
  };
  return el;
}

// ---------- messages ----------

let toastTimer;
function say(text, isError = false) {
  const s = $('#status');
  s.textContent = isError ? 'Error: ' + text : text;
  s.classList.toggle('error', isError);
  s.classList.add('show');
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => s.classList.remove('show'), isError ? 9000 : 4000);
}

/** A plain-words reason for a refused request; `words` overrides the text per status. */
async function reason(res, words = {}) {
  let body = {};
  try { body = await res.json(); } catch { /* empty body */ }
  const server = body.error || body.message;
  const text = {
    400: 'The server refused that input. Check the numbers and try again.',
    403: 'Your role is not allowed to do that.',
    404: 'That item no longer exists; it may have been archived. The page has been refreshed.',
    409: server === 'Already in use' ? 'That sensor id is already in use. Pick another one.'
      : (server || 'That clashes with what is on the server now.'),
    503: 'The server cannot check logins right now. Try again in a minute.',
    ...words,
  }[res.status];
  return text || `The server answered with status ${res.status}. Try again.`;
}

function fail(e) {
  if (e instanceof auth.LoginRequired) { signIn('Your session has ended. Sign in again.'); return; }
  say(navigator.onLine ? 'The server could not be reached. Try again.' : 'You are offline.', true);
}

/** Runs an admin request; on success says `done` and calls after(). Returns true on success. */
async function act(path, opts, done, words, after) {
  try {
    const res = await auth.api(path, opts);
    if (!res.ok) { say(await reason(res, words), true); after?.(); return false; }
    say(done);
    after?.();
    return true;
  } catch (e) { fail(e); return false; }
}

function confirmDialog(title, text, okLabel) {
  const d = $('#confirm');
  $('#confirm-title').textContent = title;
  $('#confirm-text').textContent = text;
  $('#confirm-ok').textContent = okLabel;
  d.returnValue = '';
  d.showModal();
  return new Promise(resolve => d.addEventListener('close', () => resolve(d.returnValue === 'ok'), { once: true }));
}

// ---------- live stream ----------

let live = false;
const onChange = new Set();

function setLive(on) {
  live = on;
  $('#live').classList.toggle('off', !on);
  $('#live-text').textContent = on ? 'LIVE' : 'OFFLINE';
  document.body.classList.toggle('offline', !on);
}

function startStream() {
  $('#live').hidden = false;
  setLive(false);
  const es = new EventSource('/parking-lots/stream');
  es.addEventListener('open', () => { setLive(true); onChange.forEach(f => f()); });
  es.addEventListener('error', () => setLive(false)); // EventSource reconnects by itself
  es.addEventListener('changed', () => onChange.forEach(f => f()));
  // stream down: fall back to a slow poll, like the app does
  setInterval(() => { if (!live) onChange.forEach(f => f()); }, 10_000);
}

/** Calls f (debounced) on every change event while the current page is shown. */
function whileShown(f) {
  let t;
  const debounced = () => { clearTimeout(t); t = setTimeout(f, 250); };
  onChange.add(debounced);
  cleanup.push(() => { onChange.delete(debounced); clearTimeout(t); });
}

function every(ms, f) {
  const id = setInterval(f, ms);
  cleanup.push(() => clearInterval(id));
  return id;
}

async function fetchLots() {
  const res = await auth.api('/api/admin/lots');
  if (!res.ok) throw new Error(await reason(res));
  return res.json();
}

const spotsOf = lot => lot.rows.flatMap(r => r.spots);
const freeOf = spots => spots.filter(s => !s.occupied).length;

// ---------- painted bays ----------

/** The bays as painted on the floor: open lines with a number when free, a parked car when taken. */
function bays(spots, { label, onToggle } = {}) {
  // without a label the drawing is decoration next to a count that already says it (overview tickets)
  const strip = label ? h('div', { class: 'bays', role: 'group', 'aria-label': label })
    : h('div', { class: 'bays', 'aria-hidden': 'true' });
  for (const s of spots) {
    const state = s.occupied ? 'taken' : 'free';
    const sensor = s.sensorId == null ? 'no sensor' : `sensor ${s.sensorId}`;
    const desc = `Spot ${s.id}, ${state}, ${sensor}`;
    const cls = `bay ${state}${s.sensorId == null ? ' unsensed' : ''}`;
    const inner = s.occupied ? h('span', { class: 'car' }) : h('span', { class: 'bay-num', text: String(s.id) });
    if (onToggle && s.sensorId != null) {
      strip.append(h('button', {
        type: 'button', class: cls, 'data-key': 'spot-' + s.id, title: desc,
        'aria-label': `${desc}. Mark ${s.occupied ? 'free' : 'taken'}`,
        onclick: () => onToggle(s),
      }, inner));
    } else {
      strip.append(label ? h('span', { class: cls, role: 'img', 'aria-label': desc, title: desc }, inner)
        : h('span', { class: cls }, inner));
    }
  }
  return strip;
}

function legend(withSensors) {
  return h('div', { class: 'legend', 'aria-hidden': 'true' },
    h('span', {}, h('span', { class: 'key key-free' }), 'free'),
    h('span', {}, h('span', { class: 'key key-taken' }), 'taken'),
    withSensors && h('span', {}, h('span', { class: 'key key-free key-unsensed' }), 'no sensor'));
}

function countBlock(free, total, big) {
  if (total > 0 && free === 0) return h('div', { class: 'count' }, fullStamp(), h('span', { class: 'unit', text: `0 of ${total} free` }));
  return h('div', { class: 'count' },
    h('span', { class: big ? 'num-hero' : 'num' , text: String(free) }),
    h('span', { class: 'unit', text: `of ${total} free` }));
}

// ---------- pages ----------

async function pageLots() {
  const grid = h('ul', { class: 'lots', role: 'list' });
  const stamp = printStamp();
  const chip = h('a', { class: 'chip', href: '#/update' }, 'Server: checking…');
  view.replaceChildren(
    h('div', { class: 'page-head' }, h('h1', { text: 'Lots' }), stamp, chip),
    grid,
    can('admin') ? addLotForm() : h('p', { class: 'hint', text: 'Only an admin can add or edit lots.' }),
  );

  async function refresh() {
    try {
      const lots = await fetchLots();
      swap(grid, lots.length ? lots.map(lotTicket)
        : h('li', { class: 'empty' }, can('admin') ? 'No lots yet. Add the first one below.' : 'No lots yet.'));
      stamp.reprint();
    } catch (e) { fail(e); }
  }
  whileShown(refresh);
  await refresh();
  updateChip(chip);
}

function lotTicket(lot) {
  const spots = spotsOf(lot);
  return h('li', {},
    h('a', { class: 'ticket lot', href: `#/lots/${lot.id}`, 'data-key': 'lot-' + lot.id },
      h('div', { class: 'lot-head' }, plate(), h('div', {},
        h('h2', { text: lotName(lot) }),
        field(`${lot.address.city} · lot ${lot.id}`))),
      countBlock(freeOf(spots), spots.length, false),
      spots.length ? bays(spots) : h('p', { class: 'muted', text: 'No spots yet' }),
      tear()));
}

async function updateChip(chip) {
  try {
    const res = await auth.api('/api/admin/update/status');
    const s = res.ok ? await res.json() : null;
    chip.textContent = 'Server: ' + (s ? STATE_LABEL[s.state] || s.state : 'status unknown');
    chip.dataset.state = s?.state || '';
  } catch (e) { fail(e); }
}

function addLotForm() {
  const form = h('form', { class: 'form' },
    h('label', {}, 'Street', h('input', { name: 'street', required: true, maxlength: 50, autocomplete: 'off' })),
    h('label', {}, 'House number', h('input', { name: 'houseNr', required: true, maxlength: 10, autocomplete: 'off' })),
    h('label', {}, 'City', h('input', { name: 'city', required: true, maxlength: 50, value: 'Aarhus' })),
    h('button', { class: 'btn btn-ink', type: 'submit' }, 'Add lot'));
  form.addEventListener('submit', async e => {
    e.preventDefault();
    const f = new FormData(form);
    const ok = await busy(form, () => act('/api/admin/lots', {
      method: 'POST', json: { street: f.get('street').trim(), houseNr: f.get('houseNr').trim(), city: f.get('city').trim() },
    }, 'Lot added.'));
    if (ok) { form.reset(); onChange.forEach(fn => fn()); }
  });
  return h('details', { class: 'tools' }, h('summary', {}, 'Add a lot'), form);
}

/** Disables a form's controls while `run` is in flight. */
async function busy(form, run) {
  const controls = [...form.querySelectorAll('button, input, select')];
  controls.forEach(c => { c.disabled = true; });
  try { return await run(); } finally { controls.forEach(c => { c.disabled = false; }); }
}

async function pageLot(id) {
  const back = h('a', { class: 'back', href: '#/lots' }, 'All lots');
  view.replaceChildren(back, h('p', { class: 'loading', text: 'Loading…' }));
  let shape = null;   // the lot's structure; when it changes the page is rebuilt
  let refs = null;

  async function refresh() {
    let lots;
    try { lots = await fetchLots(); } catch (e) { fail(e); return; }
    const lot = lots.find(l => l.id === id);
    if (!lot) {
      view.replaceChildren(back, h('div', { class: 'ticket notice' },
        h('h1', { text: `Lot ${id} is not in the list` }),
        h('p', { text: 'It may have been archived, or the link is wrong.' })));
      shape = null;
      return;
    }
    const next = JSON.stringify([lot.address, lot.entranceSensorIds, lot.rows.map(r => [r.id, r.spots.map(s => [s.id, s.sensorId])])]);
    if (next !== shape) {
      const open = [...view.querySelectorAll('details[open]')].map(d => d.dataset.key);
      refs = buildLot(lot, back, refresh);
      view.querySelectorAll('details').forEach(d => { if (open.includes(d.dataset.key)) d.open = true; });
      shape = next;
    }
    fillLot(lot, refs);
  }
  whileShown(refresh);
  await refresh();
}

function buildLot(lot, back, refresh) {
  const refs = { count: h('div'), rows: new Map(), stamp: printStamp() };
  const hero = h('section', { class: 'ticket hero', 'aria-labelledby': 'lot-title' },
    h('div', { class: 'lot-head' }, plate(), h('div', {},
      h('h1', { id: 'lot-title', text: lotName(lot) }),
      field(`${lot.address.city} · lot ${lot.id}`))),
    refs.count,
    field(lot.entranceSensorIds.length ? `Entrance sensors: ${lot.entranceSensorIds.join(', ')}` : 'No entrance sensor'),
    tear(),
    refs.stamp);

  const rows = lot.rows.map(row => {
    const head = h('h2', {});
    const strip = h('div');
    refs.rows.set(row.id, { head, strip });
    return h('section', { class: 'ticket row' }, head, strip, can('admin') && rowTools(lot, row, refresh), tear());
  });

  swap(view, back, hero,
    h('div', { class: 'row-head' }, h('h2', { class: 'section-title', text: 'Rows' }), legend(true)),
    can('operator') && h('p', { class: 'hint', text: 'Select a bay to flip it between free and taken. Bays without a sensor cannot be flipped.' }),
    rows.length ? rows : h('p', { class: 'empty', text: 'This lot has no rows yet.' }),
    can('admin') ? lotTools(lot, refresh) : h('p', { class: 'hint', text: 'Only an admin can edit this lot.' }));
  return refs;
}

function fillLot(lot, refs) {
  const spots = spotsOf(lot);
  swap(refs.count, countBlock(freeOf(spots), spots.length, true));
  for (const row of lot.rows) {
    const r = refs.rows.get(row.id);
    r.head.textContent = `Row ${row.id} · ${freeOf(row.spots)} of ${row.spots.length} free`;
    swap(r.strip, row.spots.length
      ? bays(row.spots, { label: `Row ${row.id} spots`, onToggle: can('operator') ? toggleSpot : null })
      : h('p', { class: 'muted', text: 'No spots in this row yet.' }));
  }
  refs.stamp.reprint();
}

function toggleSpot(spot) {
  const occupied = !spot.occupied;
  act(`/api/admin/simulator/spots/${spot.id}`, { method: 'POST', json: { occupied } },
    `Spot ${spot.id} marked ${occupied ? 'taken' : 'free'}.`,
    { 404: `Spot ${spot.id} has no sensor or was archived, so it cannot be flipped.` },
    () => onChange.forEach(f => f()));
}

function numberInput(name, labelText) {
  return h('label', {}, labelText, h('input', { name, type: 'number', min: 1, step: 1, required: true, inputmode: 'numeric' }));
}

/** A one-field form posting {sensorId}. */
function sensorForm(path, buttonText, done, refresh) {
  const form = h('form', { class: 'form inline' }, numberInput('sensorId', 'Sensor id'),
    h('button', { class: 'btn btn-ink', type: 'submit' }, buttonText));
  form.addEventListener('submit', async e => {
    e.preventDefault();
    const sensorId = Number(new FormData(form).get('sensorId'));
    if (await busy(form, () => act(path, { method: 'POST', json: { sensorId } }, done(sensorId)))) {
      form.reset();
      refresh();
    }
  });
  return form;
}

async function archive(name, path, refresh, after) {
  const ok = await confirmDialog(`Archive ${name}?`,
    `${name} disappears from the app and the dashboard. Its history is kept, and its sensors can be moved to another spot.`,
    `Archive ${name}`);
  if (!ok) return;
  if (await act(path, { method: 'DELETE' }, `${name} archived.`, { 404: `${name} was already archived or no longer exists.` })) {
    after ? after() : refresh();
  } else refresh();
}

function rowTools(lot, row, refresh) {
  const pick = h('select', { name: 'spot', 'aria-label': 'Spot to archive' },
    row.spots.map(s => h('option', { value: s.id }, `Spot ${s.id}${s.sensorId == null ? '' : ` (sensor ${s.sensorId})`}`)));
  const archiveSpot = h('form', { class: 'form inline' }, h('label', {}, 'Spot', pick),
    h('button', { class: 'btn btn-danger', type: 'submit' }, 'Archive spot…'));
  archiveSpot.addEventListener('submit', e => {
    e.preventDefault();
    const id = Number(pick.value);
    archive(`Spot ${id}`, `/api/admin/spots/${id}`, refresh);
  });
  return h('details', { class: 'tools', 'data-key': 'row-' + row.id },
    h('summary', {}, `Edit row ${row.id}`),
    h('h3', { text: 'Add a spot' }),
    sensorForm(`/api/admin/rows/${row.id}/spots`, 'Add spot', s => `Spot with sensor ${s} added to row ${row.id}.`, refresh),
    row.spots.length > 0 && [h('h3', { text: 'Archive a spot' }), archiveSpot],
    h('h3', { text: 'Archive this row' }),
    h('button', { class: 'btn btn-danger', type: 'button',
      onclick: () => archive(`Row ${row.id}`, `/api/admin/rows/${row.id}`, refresh) }, `Archive row ${row.id}…`));
}

function lotTools(lot, refresh) {
  const addRow = h('button', { class: 'btn btn-ink', type: 'button', onclick: async () => {
    addRow.disabled = true;
    await act(`/api/admin/lots/${lot.id}/rows`, { method: 'POST' }, 'Row added.');
    addRow.disabled = false;
    refresh();
  } }, 'Add row');

  const move = h('div');
  const loadParked = async () => {
    try {
      const res = await auth.api('/api/admin/sensors/parked');
      if (!res.ok) { swap(move, h('p', { class: 'muted', text: await reason(res) })); return; }
      swap(move, moveForm(lot, await res.json(), refresh));
    } catch (e) { fail(e); }
  };
  const details = h('details', { class: 'tools', 'data-key': 'lot-tools' },
    h('summary', {}, 'Edit lot'),
    h('h3', { text: 'Add a row' }), addRow,
    h('h3', { text: 'Add an entrance sensor' }),
    sensorForm(`/api/admin/lots/${lot.id}/entrance-sensors`, 'Add entrance sensor', s => `Entrance sensor ${s} added.`, refresh),
    h('h3', { text: 'Move a parked sensor here' }),
    h('p', { class: 'muted', text: 'Sensors left on an archived spot can be moved to a spot of this lot that has none.' }),
    move,
    h('h3', { text: 'Archive this lot' }),
    h('button', { class: 'btn btn-danger', type: 'button',
      onclick: () => archive(`Lot ${lotName(lot)}`, `/api/admin/lots/${lot.id}`, refresh,
        () => { location.hash = '#/lots'; }) }, 'Archive lot…'));
  details.addEventListener('toggle', () => { if (details.open) loadParked(); });
  return details;
}

function moveForm(lot, parked, refresh) {
  const targets = spotsOf(lot).filter(s => s.sensorId == null);
  if (!parked.length) return h('p', { class: 'muted', text: 'No parked sensors right now.' });
  if (!targets.length) return h('p', { class: 'muted', text: 'Every spot in this lot already has a sensor. Add a spot first.' });
  const sensor = h('select', { name: 'sensor' }, parked.map(p => h('option', { value: p.sensorId }, `Sensor ${p.sensorId} (was on spot ${p.spotId})`)));
  const spot = h('select', { name: 'spot' }, targets.map(s => h('option', { value: s.id }, `Spot ${s.id}`)));
  const form = h('form', { class: 'form inline' }, h('label', {}, 'Sensor', sensor), h('label', {}, 'To spot', spot),
    h('button', { class: 'btn btn-ink', type: 'submit' }, 'Move sensor'));
  form.addEventListener('submit', async e => {
    e.preventDefault();
    const sensorId = Number(sensor.value);
    const spotId = Number(spot.value);
    await busy(form, () => act(`/api/admin/sensors/${sensorId}/spot`, { method: 'PUT', json: { spotId } },
      `Sensor ${sensorId} moved to spot ${spotId}.`,
      { 404: 'That sensor or spot no longer exists. The lists have been refreshed.',
        409: `Spot ${spotId} already has a sensor, or sensor ${sensorId} is in use. Pick another.` }));
    refresh();
  });
  return form;
}

// ---------- traffic simulator ----------

async function pageTraffic() {
  view.replaceChildren(h('h1', { text: 'Traffic simulator' }));
  if (!can('operator')) {
    view.append(h('div', { class: 'ticket notice' },
      h('p', { text: 'The traffic simulator needs the operator role. Ask an admin to add you to the operator group.' })));
    return;
  }
  view.append(h('p', { class: 'lede', text: 'Moves cars in and out of one lot until it reaches the target occupancy. It stops by itself after 60 minutes.' }));
  const status = h('section', { class: 'ticket sim', 'aria-labelledby': 'sim-title' });
  view.append(status);
  let lots = [];
  try { lots = await fetchLots(); } catch (e) { fail(e); }
  const nameOf = id => { const l = lots.find(x => x.id === id); return l ? lotName(l) : `lot ${id}`; };

  let s = null;
  let poll = null;
  let tries = 0;
  const left = h('p', { class: 'remaining' });

  const tick = () => {
    if (!s?.running || !s.endsAt) { left.textContent = ''; return; }
    const ms = Math.max(0, new Date(s.endsAt) - Date.now());
    const m = Math.floor(ms / 60000), sec = Math.floor(ms / 1000) % 60;
    left.textContent = `${m}:${String(sec).padStart(2, '0')} left`;
  };
  every(1000, tick);

  async function load() {
    try {
      const res = await auth.api('/api/admin/simulator/traffic');
      if (!res.ok) { say(await reason(res), true); return; }
      const next = await res.json();
      const changed = !s || next.running !== s.running;
      s = next;
      if (changed) render(); else fill();
      if (s.running && !poll) { tries = 0; poll = every(3000, () => { if (++tries > 1300) clearInterval(poll); load(); }); }
      if (!s.running && poll) { clearInterval(poll); poll = null; }
    } catch (e) { fail(e); }
  }

  const facts = h('dl', { class: 'facts' });
  function fill() {
    if (!s.running) return;
    swap(facts,
      h('dt', {}, 'Lot'), h('dd', { text: nameOf(s.lotId) }),
      h('dt', {}, 'Target'), h('dd', { text: `${Math.round(s.targetOccupancy * 100)}% taken` }),
      h('dt', {}, 'Speed'), h('dd', { text: `${s.changesPerMinute} changes a minute` }),
      h('dt', {}, 'Changes so far'), h('dd', { text: String(s.changes) }));
    tick();
  }

  function render() {
    if (s.running) {
      const stop = h('button', { class: 'btn btn-danger', type: 'button', onclick: async () => {
        stop.disabled = true;
        await act('/api/admin/simulator/traffic', { method: 'DELETE' }, 'Traffic simulator stopped.');
        load();
      } }, 'Stop');
      swap(status, field('Status'), h('h2', { id: 'sim-title', class: 'state', text: 'Running' }), left, facts, stop, tear());
      fill();
      return;
    }
    const lotSel = h('select', { name: 'lot', required: true },
      lots.map(l => h('option', { value: l.id }, `${lotName(l)} (${spotsOf(l).length} spots)`)));
    const target = h('input', { type: 'range', name: 'target', min: 0, max: 100, step: 5, value: 70, id: 'sim-target' });
    const out = h('output', { for: 'sim-target', class: 'out', text: '70%' });
    target.addEventListener('input', () => { out.textContent = target.value + '%'; });
    const rate = h('input', { type: 'number', name: 'rate', min: 1, max: 60, step: 1, value: 12, required: true, inputmode: 'numeric' });
    const form = h('form', { class: 'form' },
      h('label', {}, 'Lot', lotSel),
      h('label', { for: 'sim-target' }, h('span', {}, 'Target occupancy ', out), target),
      h('label', {}, 'Changes per minute (1 to 60)', rate),
      h('button', { class: 'btn btn-ink', type: 'submit', disabled: lots.length === 0 }, 'Start'));
    form.addEventListener('submit', async e => {
      e.preventDefault();
      await busy(form, () => act('/api/admin/simulator/traffic', {
        method: 'POST',
        json: { lotId: Number(lotSel.value), targetOccupancy: Number(target.value) / 100, changesPerMinute: Number(rate.value) },
      }, 'Traffic simulator started.', { 404: 'That lot no longer exists. Pick another.', 409: 'A simulation is already running. Stop it first.' }));
      load();
    });
    swap(status, field('Status'), h('h2', { id: 'sim-title', class: 'state', text: 'Stopped' }),
      lots.length ? form : h('p', { class: 'muted', text: 'There are no lots to simulate.' }), tear());
  }

  await load();
}

// ---------- server update ----------

async function pageUpdate() {
  const box = h('section', { class: 'ticket update', 'aria-labelledby': 'upd-title' });
  view.replaceChildren(h('h1', { id: 'upd-title', text: 'Server update' }), box);
  const button = h('button', { class: 'update-btn', type: 'button', 'aria-describedby': 'upd-help' });
  const help = h('p', { id: 'upd-help', class: 'help', 'aria-live': 'polite' });
  const facts = h('dl', { class: 'facts' });
  const problem = h('p', { class: 'update-error', role: 'alert', hidden: true });
  const logText = h('pre', { class: 'update-log', tabindex: '0', text: '' });
  const logBox = h('details', { class: 'update-logs' }, h('summary', { text: 'Show logs' }), logText);
  box.append(field('Server version'), button, problem, help, facts, can('admin') && logBox, tear());

  let s = null;
  let poll = null;
  let tries = 0;

  function show() {
    const state = s.state;
    button.textContent = STATE_LABEL[state] || state;
    button.dataset.state = state;
    button.disabled = !(can('admin') && (state === 'Outdated' || state === 'ErrorUpdating'));
    const admin = can('admin');
    help.textContent = {
      Updated: 'The server runs the latest version on main.',
      Outdated: admin ? 'A newer version is on main. Press the button to install it; the dashboard pauses for about a minute while the server restarts.'
        : 'A newer version is on main. An admin can install it.',
      Updating: 'Installing. The server restarts at the end; this page reconnects by itself.',
      ErrorUpdating: 'The last update failed.' + (admin ? ' Press the button to try again (at least 5 minutes after the last run).' : ''),
      UpdateSuccessful: 'The update finished and the server is running the new version.',
      Unavailable: 'Updates cannot be started on this server: the update service is not installed.',
    }[state] || '';
    swap(facts,
      h('dt', {}, 'Running'), h('dd', { class: 'mono', text: shortSha(s.localSha) }),
      h('dt', {}, 'Latest on main'), h('dd', { class: 'mono', text: shortSha(s.remoteSha) }),
      s.finishedAt && [h('dt', {}, 'Last update'), h('dd', { text: new Date(s.finishedAt).toLocaleString('en-GB') })],
      s.message && state !== 'ErrorUpdating' && [h('dt', {}, 'Message'), h('dd', { text: s.message })]);
    problem.hidden = !(state === 'ErrorUpdating' && s.message);
    problem.textContent = s.message || '';
  }

  // lazy: only while the box is open; the status poll below refreshes it every 3 s during an update
  async function loadLog() {
    try {
      const res = await auth.api('/api/admin/update/log');
      if (RESTARTING.includes(res.status)) return; // the server restarts during an update: keep the last text
      logText.textContent = res.ok ? (await res.text()) || '(no log yet)' : await reason(res);
      logText.scrollTop = logText.scrollHeight;
    } catch (e) { /* the server restarts during an update: keep the last text */ }
  }
  logBox.addEventListener('toggle', () => { if (logBox.open) loadLog(); });

  /** Takes a new status, keeping the versions already known if this answer has none. */
  function adopt(next) {
    s = { ...next, localSha: next.localSha ?? s?.localSha, remoteSha: next.remoteSha ?? s?.remoteSha };
  }

  /** The update restarts the backend: Caddy answers 502 (or nothing) until it is back. Not an error: keep asking. */
  function restarting() {
    help.textContent = 'The server is restarting with the new version. This page reconnects by itself…';
    if (!poll) { tries = 0; poll = every(3000, tick); }
  }

  async function load() {
    try {
      const res = await auth.api('/api/admin/update/status');
      if (RESTARTING.includes(res.status)) { restarting(); return; }
      if (!res.ok) { help.textContent = await reason(res); return; }
      adopt(await res.json());
      show();
      if (logBox.open) loadLog();
      watch();
    } catch (e) {
      if (e instanceof auth.LoginRequired) { fail(e); return; }
      if (s?.state === 'Updating' || !s) restarting(); // no answer at all: the server is down for the restart
      else fail(e);
    }
  }

  function tick() {
    if (++tries > 400) { clearInterval(poll); poll = null; help.textContent = 'Still updating after 20 minutes. Check the server.'; return; }
    load();
  }

  function watch() {
    if (s.state === 'Updating' && !poll) {
      tries = 0;
      poll = every(3000, tick);
    } else if (s.state !== 'Updating' && poll) {
      clearInterval(poll);
      poll = null;
    }
  }

  button.addEventListener('click', async () => {
    button.disabled = true;
    try {
      const res = await auth.api('/api/admin/update', { method: 'POST' });
      if (res.status === 202) { adopt(await res.json()); say('Update started.'); }
      else if (res.status === 409) {
        const body = await res.json().catch(() => ({}));
        if (body.error === 'cooldown') {
          say(`Try again after ${new Date(body.retryAt).toLocaleTimeString('en-GB', { hour: '2-digit', minute: '2-digit' })}.`, true);
        } else { say('An update is already pending or running.', true); s = { ...s, state: 'Updating' }; }
      }
      else if (res.status === 503) { s = { ...s, state: 'Unavailable' }; say('Updates are not available on this server.', true); }
      else say(await reason(res), true);
      show();
      watch();
    } catch (e) { fail(e); show(); }
  });

  await load();
}

// ---------- screens outside the dashboard ----------

function shell(on) {
  $('#tabs').hidden = !on;
  $('#who').hidden = !on;
}

function screen(title, text, ...actions) {
  shell(false);
  view.replaceChildren(h('section', { class: 'ticket notice gate' },
    h('div', { class: 'lot-head' }, plate(), h('h1', { text: title })),
    text && h('p', { text }),
    actions.length > 0 && h('div', { class: 'actions' }, actions),
    tear()));
}

function signIn(message) {
  cleanup.forEach(f => f());
  cleanup = [];
  const go = h('button', { class: 'btn btn-ink', type: 'button', onclick: () => auth.startLogin() }, 'Sign in');
  screen('Sign in to AU Parking admin', message || 'Use the account an admin created for you.', go);
  go.focus();
}

// ---------- router and start ----------

function route() {
  cleanup.forEach(f => f());
  cleanup = [];
  const hash = location.hash || '#/lots';
  const lot = hash.match(/^#\/lots\/(\d+)$/);
  const tab = lot ? 'lots' : hash.slice(2);
  document.querySelectorAll('#tabs a').forEach(a => {
    if (a.dataset.tab === tab) a.setAttribute('aria-current', 'page'); else a.removeAttribute('aria-current');
  });
  const page = lot ? () => pageLot(Number(lot[1]))
    : { '#/lots': pageLots, '#/traffic': pageTraffic, '#/update': pageUpdate }[hash] || pageLots;
  page();
  if (document.activeElement !== document.body && !view.contains(document.activeElement)) view.focus({ preventScroll: true });
}

/** No answer, or Caddy's 502/503/504 while the server restarts (an update): try again by itself. */
function unreachable() {
  screen('The server cannot be reached', 'It may be restarting. This page tries again by itself every few seconds.',
    h('button', { class: 'btn btn-ink', type: 'button', onclick: () => location.reload() }, 'Try again'));
  setTimeout(() => location.reload(), 5000);
}

async function boot() {
  let config;
  try { config = await auth.loadConfig(); } catch { unreachable(); return; }
  if (config.mode === 'unconfigured') {
    screen('Admin login is not set up', 'This server has no COGNITO_* settings, so nobody can sign in. Set them in .env and restart the app.');
    return;
  }
  if (config.mode === 'cognito') {
    try { await auth.handleRedirect(); } catch (e) { signIn(e.message); return; }
    if (!auth.hasTokens()) { signIn(); return; }
  }
  let res;
  try { res = await auth.api('/api/admin/me'); } catch (e) {
    if (e instanceof auth.LoginRequired) signIn('Your session has ended. Sign in again.');
    else unreachable();
    return;
  }
  if (RESTARTING.includes(res.status)) { unreachable(); return; }
  if (res.status === 403) {
    screen('Your account has no role yet', 'Ask an admin to add you to the viewer, operator or admin group, then sign in again.',
      h('button', { class: 'btn', type: 'button', onclick: auth.logout }, 'Log out'));
    return;
  }
  if (!res.ok) { screen('Sign-in is unavailable', await reason(res)); return; }
  me = await res.json();

  $('#who-name').textContent = me.username || me.sub;
  $('#who-role').textContent = me.role;
  $('#logout').hidden = config.mode !== 'cognito';
  $('#logout').addEventListener('click', auth.logout);
  document.querySelectorAll('[data-min-role]').forEach(el => { el.hidden = !can(el.dataset.minRole); });
  shell(true);
  startStream();
  window.addEventListener('hashchange', route);
  route();
}

boot();
