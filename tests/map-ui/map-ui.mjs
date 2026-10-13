// Prueba de la interfaz del mapa en un Chromium real (Playwright).  Uso: node map-ui.mjs [capturas-dir]
// Necesita playwright (npm i playwright) o la variable PLAYWRIGHT_MODULE con su ruta.
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
const here = path.dirname(fileURLToPath(import.meta.url));
const pw = await import(process.env.PLAYWRIGHT_MODULE || 'playwright');
const { chromium } = pw.default || pw;
const outDir = process.argv[2] || path.join(here, 'out');
import fs from 'node:fs'; fs.mkdirSync(outDir, { recursive: true });
const page_url = pathToFileURL(path.join(here, '../../app/src/main/assets/map/index.html')).href;

let failed = 0;
function check(name, ok, extra) { console.log((ok ? 'OK   ' : 'FALLA') + ' ' + name + (extra ? '  → ' + extra : '')); if (!ok) failed++; }

const browser = await chromium.launch({ executablePath: process.env.CHROME || '/opt/pw-browsers/chromium-1194/chrome-linux/chrome', args: ['--no-sandbox'] });
const ctx = await browser.newContext({ viewport: { width: 1280, height: 800 } });

// Puente Android simulado: ajustes en memoria y registro de llamadas
await ctx.addInitScript(() => {
  window.__bridge = { saved: null, fs: [] };
  window.AndroidBridge = {
    loadSettings: () => window.__bridge.saved,
    saveSettings: (j) => { window.__bridge.saved = j; },
    setFullscreen: (on) => { window.__bridge.fs.push(on); },
  };
});

const page = await ctx.newPage();
const errors = [];
page.on('pageerror', (e) => errors.push(String(e)));
page.on('console', (m) => { if (m.type() === 'error') errors.push(m.text()); });
await page.goto(page_url);
await page.waitForFunction(() => window.RadarMap);

const AIR = { hex: 'A1B2C3', flight: 'KLM1023', reg: 'PH-BXA', lat: 39.9, lon: 3.1, alt: 35000, speed: 450, track: 90, vr: -64, gnd: false, age: 1, msgs: 40 };
const GND = { hex: '34AABB', flight: 'VLG8841', lat: 39.5600, lon: 2.7300, alt: null, speed: 12, track: 200, vr: null, gnd: true, age: 0, msgs: 90 };
const FAR = { hex: '4840D6', flight: 'RYR22', lat: 41.3, lon: 2.08, alt: 12000, speed: 300, track: 10, vr: 1500, gnd: false, age: 3, msgs: 12 };
const NOPOS = { hex: 'ABC123', flight: null, lat: null, lon: null, alt: null, speed: null, track: null, vr: null, gnd: null, age: 5, msgs: 3 };
const feed = (list, meta) => page.evaluate(([l, m]) => RadarMap.update(l, m), [list, meta || {}]);
const state = () => page.evaluate(() => RadarMap._state());
const chip = () => page.textContent('#chip');
const rowIds = () => page.$$eval('#list .row', (rs) => rs.map((r) => r.getAttribute('data-hex')));
const selectOpt = async (label, value) => {
  const s = page.locator('#settings .f', { hasText: label }).locator('select').first();
  await s.selectOption(String(value));
};

// 1) datos básicos
await feed([AIR, GND, FAR, NOPOS]);
let st = await state();
check('3 marcadores (el avión sin posición no se dibuja)', st.markers === 3, JSON.stringify(st));
check('contador', (await chip()).includes('4 aviones · 3 en el mapa'), await chip());
check('hay estilos de avión en tierra', (await page.$$('.plane.gnd')).length === 1);

// 2) lista por cercanía
await page.click('#btn-list');
let ids = await rowIds();
check('lista ordenada por cercanía a PMI (tierra, aire, lejano, sin posición)', JSON.stringify(ids) === JSON.stringify(['34AABB', 'A1B2C3', '4840D6', 'ABC123']), ids.join(','));
const firstRow = await page.textContent('#list .row');
check('la fila muestra distancia en km', /km/.test(firstRow), firstRow);

// 2b) matrícula junto al indicativo
const regTxt = await page.textContent('#list .row[data-hex="A1B2C3"] .l1 .reg').catch(() => null);
check('la lista muestra la matrícula junto al indicativo', regTxt === 'PH-BXA', String(regTxt));
check('sin matrícula no hay etiqueta vacía', (await page.$$('#list .row[data-hex="34AABB"] .reg')).length === 0);

// 3) filtro tierra / aire
await page.click('#btn-set');
await selectOpt('Mostrar', 'air');
st = await state();
check('solo aire: 2 marcadores', st.markers === 2, JSON.stringify(st));
ids = await rowIds();
check('solo aire: la lista no incluye tierra', !ids.includes('34AABB'), ids.join(','));
await selectOpt('Mostrar', 'gnd');
st = await state();
ids = await rowIds();
check('solo tierra: 1 marcador y 1 fila', st.markers === 1 && ids.length === 1 && ids[0] === '34AABB', ids.join(','));
await selectOpt('Mostrar', 'all');

// 4) unidades
await selectOpt('Distancia', 'nm');
await selectOpt('Altitud', 'm');
await selectOpt('Velocidad', 'kmh');
const row2 = await page.textContent('#list .row:nth-child(2)');
check('distancia en NM', /NM/.test(row2), row2);
await page.click('#settings .xbtn'); // cerrar
await page.click('.plane:not(.gnd) >> nth=0');
const det = await page.textContent('#detail');
check('detalle en las unidades elegidas (m, km/h, NM)', /\d+ m/.test(det) && /km\/h/.test(det) && /NM/.test(det), det.replace(/\s+/g, ' ').slice(0, 160));
check('detalle muestra estado', /En el aire|En tierra/.test(det));

// 5) anillos
await page.click('#btn-set');
const num = (label) => page.locator('#settings .f', { hasText: label }).locator('input[type=number]').first();
await num('Número de anillos').fill('4'); await num('Número de anillos').dispatchEvent('change');
await num('Distancia entre anillos').fill('40'); await num('Distancia entre anillos').dispatchEvent('change');
const ringLabels = await page.$$eval('.ring span', (s) => s.map((x) => x.textContent));
check('4 anillos de 40 NM', JSON.stringify(ringLabels) === JSON.stringify(['40 NM', '80 NM', '120 NM', '160 NM']), ringLabels.join(','));
await selectOpt('Distancia', 'km');
const ringKm = await page.$$eval('.ring span', (s) => s.map((x) => x.textContent));
check('anillos en km tras cambiar la unidad', ringKm[0] === '40 km', ringKm.join(','));

// 6) estelas
await page.click('#settings .xbtn');
for (let i = 1; i <= 3; i++) await feed([{ ...AIR, lat: 39.9 + i * 0.01, lon: 3.1 + i * 0.01 }, GND, FAR, NOPOS]);
st = await state();
check('estelas dibujadas con el avión que se mueve', st.trails >= 1, JSON.stringify(st));
await page.click('#btn-set'); await page.locator('#settings .f', { hasText: 'Mostrar estelas' }).locator('input').uncheck();
st = await state();
check('estelas desactivadas', st.trails === 0, JSON.stringify(st));
await page.locator('#settings .f', { hasText: 'Mostrar estelas' }).locator('input').check();

// 7) etiquetas
check('etiquetas siempre: 3 visibles', (await page.$$('.plane .lbl')).length === 3);
await selectOpt('Mostrarlos', 'selected');
check('solo seleccionado: solo la del avión seleccionado', (await page.$$('.plane .lbl')).length === 1);
await selectOpt('Mostrarlos', 'never');
check('nunca: ninguna etiqueta', (await page.$$('.plane .lbl')).length === 0);
await selectOpt('Mostrarlos', 'always');
await page.locator('#settings .chk label', { hasText: 'Velocidad' }).locator('input').check();
await page.locator('#settings .chk label', { hasText: 'Distancia' }).locator('input').check();
const lbl = await page.textContent('.plane:not(.gnd) .lbl >> nth=0');
check('etiqueta con los campos elegidos', /km\/h/.test(lbl) && /km\b/.test(lbl), lbl);

// 8) selección y panel de detalle
await page.click('#settings .xbtn');
await page.click('#list .row >> nth=2');
check('seleccionar desde la lista', (await state()).selected !== null);
await page.mouse.click(1000, 120); // clic en el fondo
await page.waitForTimeout(100);
// 9) pantalla completa
await page.click('#btn-fs');
check('pantalla completa avisa a la app', await page.evaluate(() => JSON.stringify(window.__bridge.fs)) === '[true]');
await page.click('#btn-fs');

// 10) persistencia
const saved = await page.evaluate(() => window.__bridge.saved);
check('ajustes guardados (JSON)', !!saved && JSON.parse(saved).units.dist === 'km' && JSON.parse(saved).rings.count === 4, saved && saved.slice(0, 80));
await page.screenshot({ path: path.join(outDir, '1-lista-y-etiquetas.png') });
await page.click('#btn-set');
await page.screenshot({ path: path.join(outDir, '2-ajustes.png') });

await page.evaluate((s) => { window.__bridge.saved = s; }, saved);
await page.reload(); await page.waitForFunction(() => window.RadarMap);
// el initScript vuelve a crear el puente vacío; reinyectar los ajustes antes de recargar
const page2 = await ctx.newPage();
await page2.addInitScript((s) => { window.AndroidBridge = { loadSettings: () => s, saveSettings: () => {}, setFullscreen: () => {} }; }, saved);
await page2.goto(page_url); await page2.waitForFunction(() => window.RadarMap);
const s2 = await page2.evaluate(() => RadarMap.getSettings());
check('los ajustes se restauran al reabrir', s2.rings.count === 4 && s2.rings.step === 40 && s2.units.alt === 'm' && s2.labels.fields.speed === true, JSON.stringify(s2.rings));
await page2.evaluate((l) => RadarMap.update(l, {}), [AIR, GND, FAR]);
await page2.screenshot({ path: path.join(outDir, '3-reabierto.png') });

// 10b) ping de actividad POR AVIÓN: segundos desde el último mensaje de cada uno, sin depender de la proximidad
await page.click('#settings .xbtn').catch(() => {});
await page.evaluate(() => { if (!RadarMap.getSettings().panel) document.getElementById('btn-list').click(); });
const pings = () => page.$$eval('#list .row', (rs) => Object.fromEntries(rs.map((r) => [r.getAttribute('data-hex'), r.querySelector('.pg') ? { n: r.querySelector('.pg b').textContent, cls: r.querySelector('.pg').className.replace('pg ', ''), pulse: r.querySelector('.pg .dot').classList.contains('pulse') } : null])));
await feed([{ ...AIR, age: 1 }, { ...GND, age: 0 }, { ...FAR, age: 20 }, { ...NOPOS, age: 7 }]);
let pg = await pings();
check('ping: cada fila tiene su contador', Object.values(pg).every((x) => x && /^\d+ s$/.test(x.n)), JSON.stringify(pg));
check('ping: cada avión con SU tiempo (independiente del orden por cercanía)', pg['34AABB'].n === '0 s' && pg.A1B2C3.n === '1 s' && pg['4840D6'].n === '20 s' && pg.ABC123.n === '7 s', JSON.stringify(Object.entries(pg).map(([k, v]) => k + ':' + v.n)));
check('ping: colores por antigüedad (verde <5 s, ámbar <15 s, rojo)', pg['34AABB'].cls === 'ok' && pg.ABC123.cls === 'warn' && pg['4840D6'].cls === 'bad', JSON.stringify(Object.entries(pg).map(([k, v]) => k + ':' + v.cls)));
check('ping: también los aviones sin posición lo llevan', pg.ABC123 !== null);
await page.waitForTimeout(2300);
pg = await pings();
check('ping: los contadores suben solos cada segundo', pg['34AABB'].n === '2 s' && pg['4840D6'].n === '22 s' && pg.ABC123.n === '9 s', JSON.stringify(Object.entries(pg).map(([k, v]) => k + ':' + v.n)));
// llega un mensaje nuevo SOLO del avión de en medio (su contador baja); los demás siguen subiendo
await feed([{ ...AIR, age: 0 }, { ...GND, age: 2 }, { ...FAR, age: 22 }, { ...NOPOS, age: 9 }]);
pg = await pings();
check('ping: late solo el avión que ha recibido un mensaje', pg.A1B2C3.pulse === true && !pg['34AABB'].pulse && !pg['4840D6'].pulse && !pg.ABC123.pulse, JSON.stringify(Object.entries(pg).map(([k, v]) => k + ':' + v.pulse)));
check('ping: y su contador vuelve a 0', pg.A1B2C3.n === '0 s', pg.A1B2C3.n);
// en la ficha del avión seleccionado
await page.click('#list .row[data-hex="4840D6"]');
const ficha = await page.$eval('#detail', (d) => ({ txt: d.textContent, ping: !!d.querySelector('[data-ping]') }));
check('ping: la ficha del avión seleccionado también lo muestra', ficha.ping && /Último mensaje/.test(ficha.txt));
// como dato junto al avión en el mapa
await page.click('#btn-set');
await page.locator('#settings .chk label', { hasText: 'Último dato' }).locator('input').check();
const lbl2 = await page.textContent('.plane:not(.gnd) .lbl >> nth=0');
check('ping: opción «Último dato (s)» junto al avión en el mapa', /⟲ \d+ s/.test(lbl2), lbl2);
await page.locator('#settings .chk label', { hasText: 'Último dato' }).locator('input').uncheck();
await page.click('#settings .xbtn');
// aviso general en la barra de estado cuando no llega ningún dato
await feed([{ ...AIR, age: 30 }], { lastDataAge: 30 });
const chipTxt = await chip();
check('barra de estado: avisa de que no llega ningún dato', /sin datos hace 3\d s/.test(chipTxt), chipTxt);
await feed([{ ...AIR, age: 1 }], { lastDataAge: 1 });
check('barra de estado: sin aviso si hay datos recientes', !/sin datos/.test(await chip()), await chip());
await page.screenshot({ path: path.join(outDir, '4-ping-por-avion.png') });

// 11) otro aeropuerto de referencia
await page.click('#settings .xbtn').catch(() => {});
await page.click('#btn-set');
await page.fill('#apin', 'BCN');
await page.click('text=Cambiar aeropuerto');
const bcn = await page.$eval('.refap span', (e) => e.textContent);
check('aeropuerto de referencia cambiado a BCN', bcn === 'BCN', bcn);

// 12) responsive: móvil vertical y apaisado, tableta y escritorio
const SIZES = [['movil-vertical', 360, 740, true], ['movil-pequeno', 320, 568, true], ['movil-apaisado', 740, 360, true], ['tableta-vertical', 800, 1280, true], ['escritorio', 1366, 768, false]];
for (const [name, w, h, touch] of SIZES) {
  const c = await browser.newContext({ viewport: { width: w, height: h }, hasTouch: touch, isMobile: touch, deviceScaleFactor: 2 });
  const p = await c.newPage(); const errs = []; p.on('pageerror', (e) => errs.push(String(e)));
  await p.goto(page_url); await p.waitForFunction(() => window.RadarMap);
  await p.evaluate(([l]) => RadarMap.update(l, { lastDataAge: 2 }), [[AIR, GND, FAR, NOPOS]]);
  const measure = () => p.evaluate(() => {
    const r = (sel) => { const e = document.querySelector(sel); if (!e || !e.offsetParent && getComputedStyle(e).position !== 'fixed') return null; const b = e.getBoundingClientRect(); return { x: b.x, y: b.y, w: b.width, h: b.height, r: b.right, b: b.bottom }; };
    return { vw: innerWidth, vh: innerHeight, sw: document.documentElement.scrollWidth, sh: document.documentElement.scrollHeight, bar: r('.rb a'), chip: r('#chip'), side: r('#side.open'), box: r('#settings.open .box'), volver: null };
  });
  let m = await measure();
  check(`${name} (${w}×${h}): sin desbordamiento horizontal ni vertical`, m.sw <= m.vw && m.sh <= m.vh, `${m.sw}x${m.sh} en ${m.vw}x${m.vh}`);
  check(`${name}: botones ≥ ${touch ? 44 : 36} px`, m.bar.w >= (touch ? 43.5 : 35.5) && m.bar.h >= (touch ? 43.5 : 35.5), `${m.bar.w}x${m.bar.h}`);
  await p.click('#btn-list'); await p.waitForSelector('#list .row'); m = await measure();
  check(`${name}: lista dentro de la pantalla`, m.side && m.side.x >= -0.5 && m.side.r <= m.vw + 0.5 && m.side.b <= m.vh + 0.5 && m.side.y >= -0.5, JSON.stringify(m.side));
  if (w <= 640 && h > w) check(`${name}: la lista es una hoja inferior (el mapa sigue visible)`, m.side.w >= m.vw - 1 && m.side.y > m.vh * 0.4 && m.side.h <= m.vh * 0.55, JSON.stringify(m.side));
  check(`${name}: la barra de estado no queda tapada por la lista`, !m.chip || !m.side || m.chip.b <= m.side.y + 1 || m.chip.r <= m.side.x + 1, JSON.stringify({ chip: m.chip, side: m.side }));
  const tiene = await p.$$eval('#list .row', (rs) => rs.every((r) => r.scrollWidth <= r.clientWidth + 1));
  check(`${name}: las filas de la lista no desbordan`, tiene);
  const ping = await p.$$eval('#list .row .pg', (e) => e.length);
  check(`${name}: cada fila muestra su contador`, ping === 4, String(ping));
  await p.screenshot({ path: path.join(outDir, `r-${name}-lista.png`) });
  await p.click('#btn-set'); m = await measure();
  check(`${name}: ajustes dentro de la pantalla`, m.box && m.box.x >= -0.5 && m.box.r <= m.vw + 0.5 && m.box.y >= -0.5 && m.box.b <= m.vh + 0.5, JSON.stringify(m.box));
  const desb = await p.$$eval('#settings .body *', (els) => els.filter((e) => e.getBoundingClientRect().right > innerWidth + 1).length);
  check(`${name}: ningún control de ajustes se sale por la derecha`, desb === 0, String(desb));
  const fs = await p.$$eval('#settings select, #settings input[type=number], #settings input[type=text]', (els) => Math.min(...els.map((e) => parseFloat(getComputedStyle(e).fontSize))));
  if (w <= 640) check(`${name}: campos con letra ≥ 16 px (sin zoom en iOS)`, fs >= 16, String(fs));
  await p.screenshot({ path: path.join(outDir, `r-${name}-ajustes.png`) });
  check(`${name}: sin errores de JavaScript`, errs.length === 0, errs.join('|'));
  await c.close();
}

check('sin errores de JavaScript', errors.length === 0, errors.join(' | '));
await browser.close();
console.log(failed ? `\n${failed} comprobaciones fallidas` : '\nTodo correcto');
process.exit(failed ? 1 : 0);
