// Genera app/src/main/assets/map/basemap.js (costas, fronteras y aeropuertos) para el mapa sin conexión.
// Datos: Natural Earth (dominio público, vía npm "world-atlas") y OurAirports (dominio público).
// Uso:  npm install world-atlas@2.0.2 topojson-client@3.1.0 @turf/bbox-clip@7.2.0 @turf/simplify@7.2.0
//       curl -o airports.csv https://raw.githubusercontent.com/davidmegginson/ourairports-data/main/airports.csv
//       node build.mjs airports.csv ../../app/src/main/assets/map/basemap.js
import fs from 'node:fs';
import { createRequire } from 'node:module';
import * as topojson from 'topojson-client';
import bboxClip from '@turf/bbox-clip';
import simplify from '@turf/simplify';

const require = createRequire(import.meta.url);
const land = require('world-atlas/land-10m.json');
const countries = require('world-atlas/countries-10m.json');

// Región: Mediterráneo occidental (de sobra para 250 km alrededor de Palma y aviones lejanos)
const BBOX = [-9, 33, 15, 46]; // oeste, sur, este, norte
const round = (n) => Math.round(n * 1000) / 1000;

function clipRound(feature, tolerance) {
  const clipped = bboxClip(feature, BBOX);
  const simple = simplify(clipped, { tolerance, highQuality: true, mutate: false });
  const walk = (c) => (typeof c[0] === 'number' ? [round(c[0]), round(c[1])] : c.map(walk));
  return walk(simple.geometry.coordinates);
}

// Tierra: polígonos
const landFeature = topojson.feature(land, land.objects.land);
const landPolys = [];
for (const f of landFeature.type === 'FeatureCollection' ? landFeature.features : [landFeature]) {
  const polys = f.geometry.type === 'MultiPolygon' ? f.geometry.coordinates : [f.geometry.coordinates];
  for (const p of polys) {
    const poly = { type: 'Feature', properties: {}, geometry: { type: 'Polygon', coordinates: p } };
    const [w, s, e, n] = [Math.min(...p[0].map((c) => c[0])), Math.min(...p[0].map((c) => c[1])),
      Math.max(...p[0].map((c) => c[0])), Math.max(...p[0].map((c) => c[1]))];
    if (e < BBOX[0] || w > BBOX[2] || n < BBOX[1] || s > BBOX[3]) continue; // fuera de la región
    const coords = clipRound(poly, 0.0015);
    if (coords.length && coords[0].length >= 4) landPolys.push(coords);
  }
}

// Fronteras entre países: líneas
const borderMesh = topojson.mesh(countries, countries.objects.countries, (a, b) => a !== b);
const borderLines = [];
const lines = borderMesh.type === 'MultiLineString' ? borderMesh.coordinates : [borderMesh.coordinates];
for (const l of lines) {
  const clipped = bboxClip({ type: 'Feature', properties: {}, geometry: { type: 'LineString', coordinates: l } }, BBOX);
  const g = clipped.geometry;
  const parts = g.type === 'MultiLineString' ? g.coordinates : [g.coordinates];
  for (const part of parts) {
    if (part.length < 2) continue;
    const s = simplify({ type: 'Feature', properties: {}, geometry: { type: 'LineString', coordinates: part } }, { tolerance: 0.004, highQuality: true });
    borderLines.push(s.geometry.coordinates.map((c) => [round(c[0]), round(c[1])]));
  }
}

// Aeropuertos (OurAirports): grandes/medianos y pequeños con código IATA
const csv = fs.readFileSync(process.argv[2], 'utf8');
function parseCsv(text) {
  const rows = []; let row = [], cell = '', q = false;
  for (let i = 0; i < text.length; i++) {
    const ch = text[i];
    if (q) { if (ch === '"') { if (text[i + 1] === '"') { cell += '"'; i++; } else q = false; } else cell += ch; }
    else if (ch === '"') q = true;
    else if (ch === ',') { row.push(cell); cell = ''; }
    else if (ch === '\n') { row.push(cell); rows.push(row); row = []; cell = ''; }
    else if (ch !== '\r') cell += ch;
  }
  return rows;
}
const rows = parseCsv(csv);
const head = rows[0]; const ix = (n) => head.indexOf(n);
const airports = [];
for (const r of rows.slice(1)) {
  if (r.length < head.length) continue;
  const lat = +r[ix('latitude_deg')], lon = +r[ix('longitude_deg')];
  if (!(lon >= BBOX[0] && lon <= BBOX[2] && lat >= BBOX[1] && lat <= BBOX[3])) continue;
  const type = r[ix('type')], iata = r[ix('iata_code')];
  const ident = r[ix('ident')];
  const oaci = /^[A-Z]{4}$/.test(ident); // descarta códigos internos tipo «ES-0109»
  if (!(iata || oaci)) continue;
  if (!(type === 'large_airport' || type === 'medium_airport' || (type === 'small_airport' && iata))) continue;
  airports.push({ icao: r[ix('ident')], iata: iata || '', name: r[ix('name')], lat: Math.round(lat * 1e4) / 1e4, lon: Math.round(lon * 1e4) / 1e4, type: type === 'large_airport' ? 'L' : type === 'medium_airport' ? 'M' : 'S' });
}

const out = `// Generado por tools/basemap/build.mjs — Natural Earth + OurAirports (dominio público)\n` +
  `window.BASEMAP = ${JSON.stringify({ bbox: BBOX, land: landPolys, borders: borderLines })};\n` +
  `window.AIRPORTS = ${JSON.stringify(airports)};\n`;
fs.writeFileSync(process.argv[3], out);
console.log(`tierra: ${landPolys.length} polígonos, fronteras: ${borderLines.length} líneas, aeropuertos: ${airports.length}, tamaño: ${(out.length / 1024).toFixed(0)} kB`);
