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

const AIR = { hex: 'A1B2C3', flight: 'KLM1023', lat: 39.9, lon: 3.1, alt: 35000, speed: 450, track: 90, vr: -64, gnd: false, age: 1, msgs: 40 };
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

// 11) otro aeropuerto de referencia
await page.click('#settings .xbtn').catch(() => {});
await page.click('#btn-set');
await page.fill('#apin', 'BCN');
await page.click('text=Cambiar aeropuerto');
const bcn = await page.$eval('.refap span', (e) => e.textContent);
check('aeropuerto de referencia cambiado a BCN', bcn === 'BCN', bcn);

check('sin errores de JavaScript', errors.length === 0, errors.join(' | '));
await browser.close();
console.log(failed ? `\n${failed} comprobaciones fallidas` : '\nTodo correcto');
process.exit(failed ? 1 : 0);
