/* Radar ADS-B: mapa sin conexión, lista por cercanía, estelas, filtros y ajustes.
 * Lo usan la app Android (WebView) y el visor web de swap-turnos.
 * El anfitrión llama a  RadarMap.update(lista, meta)  /  updateAircraft(lista)  con los aviones:
 *   [{hex, flight, reg, lat, lon, alt, speed, track, vr, gnd, age, msgs}]   (alt en ft, speed en kt, vr en ft/min, age en s)
 * Opcional (Android): window.AndroidBridge con loadSettings(), saveSettings(json), setFullscreen(bool). */
(function () {
  'use strict';

  // ---------- ajustes ----------
  var DEFAULTS = {
    units: { dist: 'km', alt: 'ft', speed: 'kt', vr: 'fpm' },
    airport: 'LEPA',
    rings: { show: true, count: 10, step: 25, labels: true },
    trails: { on: true, seconds: 300 },
    ground: 'all',            // all | air | gnd
    maxAge: 60,               // s sin mensajes para ocultar un avión
    listNoPos: true,
    listReg: true,            // matrícula junto al indicativo en la lista
    labels: { mode: 'always', fields: { flight: true, reg: false, hex: false, alt: true, speed: false, track: false, vr: false, dist: false, age: false } },
    base: 'offline',          // offline | osm
    showAirports: true,
    showBorders: true,
    panel: false
  };
  var host = window.AndroidBridge || null;

  function clone(o) { return JSON.parse(JSON.stringify(o)); }
  function merge(base, over) {
    if (!over || typeof over !== 'object') return base;
    Object.keys(base).forEach(function (k) {
      if (!(k in over)) return;
      if (base[k] && typeof base[k] === 'object' && !Array.isArray(base[k])) merge(base[k], over[k]);
      else if (typeof over[k] === typeof base[k]) base[k] = over[k];
    });
    return base;
  }
  function loadSettings() {
    var raw = null;
    try { raw = host && host.loadSettings ? host.loadSettings() : window.localStorage.getItem('radar.settings'); } catch (e) { raw = null; }
    var s = clone(DEFAULTS);
    if (raw) { try { merge(s, JSON.parse(raw)); } catch (e) { /* ajustes dañados: valores por defecto */ } }
    return s;
  }
  var S = loadSettings();
  function saveSettings() {
    var raw = JSON.stringify(S);
    try { if (host && host.saveSettings) host.saveSettings(raw); else window.localStorage.setItem('radar.settings', raw); } catch (e) { /* sin almacenamiento */ }
  }

  // ---------- unidades ----------
  var DIST = { km: { f: 1000, l: 'km' }, mi: { f: 1609.344, l: 'mi' }, nm: { f: 1852, l: 'NM' } };
  function pad3(n) { n = String(n); while (n.length < 3) n = '0' + n; return n; }
  function fmtDist(m) {
    if (m == null) return null;
    var u = DIST[S.units.dist], v = m / u.f;
    return (v < 10 ? v.toFixed(1) : String(Math.round(v))) + ' ' + u.l;
  }
  function fmtAlt(ft) {
    if (ft == null) return null;
    if (S.units.alt === 'm') return Math.round(ft * 0.3048) + ' m';
    if (S.units.alt === 'fl') return 'FL' + pad3(Math.round(ft / 100));
    return Math.round(ft) + ' ft';
  }
  function fmtSpeed(kt) {
    if (kt == null) return null;
    if (S.units.speed === 'kmh') return Math.round(kt * 1.852) + ' km/h';
    if (S.units.speed === 'mph') return Math.round(kt * 1.15078) + ' mph';
    return Math.round(kt) + ' kt';
  }
  function fmtVr(fpm) {
    if (fpm == null) return null;
    if (S.units.vr === 'ms') return (fpm >= 0 ? '+' : '') + (fpm * 0.00508).toFixed(1) + ' m/s';
    return (fpm >= 0 ? '+' : '') + fpm + ' ft/min';
  }
  function fmtTrack(t) { return t == null ? null : pad3(Math.round(t) % 360) + '°'; }

  // ---------- referencia (aeropuerto) ----------
  var AIRPORTS = window.AIRPORTS || [];
  function findAirport(code) {
    code = String(code || '').trim().toUpperCase();
    if (!code) return null;
    for (var i = 0; i < AIRPORTS.length; i++) {
      var a = AIRPORTS[i];
      if (a.icao === code || (a.iata && a.iata === code)) return a;
    }
    return null;
  }
  var REF = findAirport(S.airport) || findAirport('LEPA') || { icao: 'LEPA', iata: 'PMI', name: 'Palma de Mallorca', lat: 39.5517, lon: 2.7388 };
  function refName() { return REF.iata || REF.icao; }
  function distM(lat, lon) {
    var R = 6371000, rad = Math.PI / 180;
    var dLat = (lat - REF.lat) * rad, dLon = (lon - REF.lon) * rad;
    var a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
      Math.cos(REF.lat * rad) * Math.cos(lat * rad) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
    return 2 * R * Math.asin(Math.sqrt(a));
  }

  // ---------- mapa ----------
  var map = L.map('map', { zoomControl: true, preferCanvas: true, zoomSnap: 0.25 });
  map.setView([REF.lat, REF.lon], 6);
  var mapEl = document.getElementById('map');

  // fondo sin conexión: tierra y fronteras
  var baseGroup = L.layerGroup().addTo(map);
  var bordersLayer = null, osmLayer = null;
  function buildBase() {
    var B = window.BASEMAP;
    if (!B) return;
    var swap = function (ring) { return ring.map(function (c) { return [c[1], c[0]]; }); };
    L.polygon(B.land.map(function (poly) { return poly.map(swap); }), {
      stroke: true, color: '#8d8d7a', weight: 0.8, fillColor: '#f3efe4', fillOpacity: 1, interactive: false
    }).addTo(baseGroup);
    bordersLayer = L.polyline(B.borders.map(swap), { color: '#b07aa8', weight: 1, dashArray: '5 4', interactive: false });
  }
  buildBase();
  var SAT_ONLINE = 'https://tiles.maps.eox.at/wmts/1.0.0/s2cloudless-2020_3857/default/g/{z}/{y}/{x}.jpg';
  var SAT_ATTR = 'Sentinel-2 cloudless &copy; <a href="https://s2maps.eu">EOX</a> (contiene datos modificados de Copernicus Sentinel 2020)';
  var BLANK = 'data:image/gif;base64,R0lGODlhAQABAAAAACH5BAEKAAEALAAAAAABAAEAAAICTAEAOw==';
  function satAvailable() { return !!(host && host.satInfo); }
  function applyBase() {
    if (osmLayer) { map.removeLayer(osmLayer); osmLayer = null; }
    var photo = S.base === 'osm' || S.base === 'sat';
    if (S.base === 'osm') {
      osmLayer = L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', { maxZoom: 18, attribution: '&copy; OpenStreetMap' }).addTo(map);
      mapEl.style.background = '#dddddd';
    } else if (S.base === 'sat') {
      // En la tablet se leen las teselas guardadas (https://sat.local/...); en el navegador, directamente de internet.
      osmLayer = L.tileLayer(satAvailable() ? 'https://sat.local/{z}/{x}/{y}.jpg' : SAT_ONLINE,
        { maxNativeZoom: 13, maxZoom: 16, minZoom: 3, errorTileUrl: BLANK, attribution: SAT_ATTR }).addTo(map);
      mapEl.style.background = '#1d2a35';
    } else {
      mapEl.style.background = '#cfe3f2';
    }
    if (baseGroup) { if (photo) map.removeLayer(baseGroup); else if (!map.hasLayer(baseGroup)) baseGroup.addTo(map); }
    if (bordersLayer) { if (S.showBorders && S.base !== 'osm') { if (!map.hasLayer(bordersLayer)) bordersLayer.addTo(map); } else map.removeLayer(bordersLayer); }
    mapEl.classList.toggle('noap', !S.showAirports);
  }

  // aeropuertos (etiquetas según el zoom)
  var apLayer = L.layerGroup().addTo(map);
  AIRPORTS.forEach(function (a) {
    var html = '<i></i><span>' + (a.iata || a.icao) + '</span>';
    L.marker([a.lat, a.lon], { interactive: false, keyboard: false,
      icon: L.divIcon({ className: 'ap t' + a.type, iconSize: [0, 0], html: html }) }).addTo(apLayer);
  });
  function zoomClasses() {
    var z = map.getZoom();
    mapEl.classList.toggle('zlt7', z < 7);
    mapEl.classList.toggle('zlt8', z < 8);
  }
  map.on('zoomend', zoomClasses);

  // aeropuerto de referencia y anillos
  var ringLayer = L.layerGroup().addTo(map);
  function ringExtentM() { return S.rings.count * S.rings.step * DIST[S.units.dist].f; }
  function drawRings() {
    ringLayer.clearLayers();
    L.marker([REF.lat, REF.lon], { interactive: false, keyboard: false,
      icon: L.divIcon({ className: 'refap', iconSize: [0, 0], html: '<span>' + refName() + '</span>' }) }).addTo(ringLayer);
    L.circleMarker([REF.lat, REF.lon], { radius: 5, color: '#fff', weight: 2, fillColor: '#0d47a1', fillOpacity: 1, interactive: false }).addTo(ringLayer);
    if (!S.rings.show) return;
    var u = DIST[S.units.dist];
    for (var i = 1; i <= S.rings.count; i++) {
      var r = i * S.rings.step * u.f, major = i % 2 === 0;
      L.circle([REF.lat, REF.lon], { radius: r, color: '#1565c0', weight: major ? 1.6 : 0.8, opacity: major ? 0.75 : 0.5, fill: false, interactive: false }).addTo(ringLayer);
      if (S.rings.labels) {
        var txt = (Math.round(i * S.rings.step * 100) / 100) + ' ' + u.l;
        L.marker([REF.lat + r / 111320, REF.lon], { interactive: false, keyboard: false,
          icon: L.divIcon({ className: 'ring', iconSize: [0, 0], html: '<span>' + txt + '</span>' }) }).addTo(ringLayer);
      }
    }
  }
  function fitRings() {
    var m = Math.max(ringExtentM(), 20000);
    var dLat = m / 111320, dLon = m / (111320 * Math.cos(REF.lat * Math.PI / 180));
    map.fitBounds([[REF.lat - dLat, REF.lon - dLon], [REF.lat + dLat, REF.lon + dLon]]);
  }

  // ---------- aviones ----------
  var PLANE_SVG = '<svg viewBox="0 0 24 24"><path d="M12 2l2.5 7 7.5 4v2l-7.5-2-.5 6 2.5 2v1L12 21l-4.5 1v-1L10 19l-.5-6L2 15v-2l7.5-4z" fill="@C" stroke="#fff" stroke-width="1"/></svg>';
  function colorFor(a) {
    if (a.gnd === true) return '#546e7a';
    if (a.alt == null) return '#c62828';
    if (a.alt < 10000) return '#e65100';
    if (a.alt < 20000) return '#f9a825';
    if (a.alt < 30000) return '#2e7d32';
    if (a.alt < 40000) return '#1565c0';
    return '#6a1b9a';
  }
  function esc(s) { return String(s).replace(/[&<>"]/g, function (c) { return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]; }); }

  var acs = {};          // hex -> { marker, poly, trail: [[t, lat, lon]], html, sel }
  var current = [];      // aviones procesados del último envío
  var lastList = [], lastMeta = {}, lastAt = 0;
  var dataAge = null, dataAgeAt = 0;   // segundos desde el último dato recibido de cualquier avión (aviso general de la barra de estado)
  var ages = {};                       // hex -> { age, at }: segundos desde el último mensaje de ESE avión, medidos en el instante «at"
  var selected = null;
  var trailGroup = L.layerGroup().addTo(map);
  var planeGroup = L.layerGroup().addTo(map);

  function prepare(a, extra) {
    var o = {
      hex: a.hex, flight: a.flight || null, reg: a.reg || null, lat: a.lat, lon: a.lon, alt: a.alt, speed: a.speed, track: a.track,
      vr: a.vr, gnd: a.gnd, age: (a.age || 0) + extra, msgs: a.msgs
    };
    o.hasPos = typeof o.lat === 'number' && typeof o.lon === 'number';
    o.dist = o.hasPos ? distM(o.lat, o.lon) : null;
    o.id = o.flight || o.hex;
    // ping: ¿ha llegado un mensaje nuevo de este avión desde la última actualización? (su contador baja)
    var now = Date.now(), prev = ages[o.hex];
    o.fresh = !!prev && o.age < prev.age + (now - prev.at) / 1000 - 0.5;
    ages[o.hex] = { age: o.age, at: now };
    return o;
  }
  /* Segundos desde el último mensaje de un avión, ahora mismo (sube solo entre actualizaciones) */
  function ageNow(hex) {
    var r = ages[hex];
    return r ? r.age + (Date.now() - r.at) / 1000 : null;
  }
  function pingClass(age) { return age < 5 ? 'ok' : age < 15 ? 'warn' : 'bad'; }
  function fmtPing(age) { return age < 3600 ? Math.floor(age) + ' s' : '> 1 h'; }
  /* Punto que late + segundos; independiente de la distancia y del orden de la lista */
  function pingHtml(a, fresh) {
    var age = ageNow(a.hex);
    if (age == null) return '';
    return '<span class="pg ' + pingClass(age) + '" data-ping="' + esc(a.hex) + '" title="Segundos desde el último mensaje de este avión">' +
      '<i class="dot' + (fresh ? ' pulse' : '') + '"></i><b>' + fmtPing(age) + '</b></span>';
  }
  function tickPings() {
    var els = document.querySelectorAll('[data-ping]');
    for (var i = 0; i < els.length; i++) {
      var el = els[i], age = ageNow(el.getAttribute('data-ping'));
      if (age == null) continue;
      el.className = 'pg ' + pingClass(age);
      el.querySelector('b').textContent = fmtPing(age);
    }
  }
  function passes(a) {
    if (a.age > S.maxAge) return false;
    if (S.ground === 'air') return a.gnd !== true;
    if (S.ground === 'gnd') return a.gnd === true;
    return true;
  }

  function ingest(list, extra) {
    var now = Date.now();
    var cutoff = now - 600000; // se guardan 10 min de estela como máximo
    current = [];
    var seen = {};
    list.forEach(function (raw) {
      var a = prepare(raw, extra);
      current.push(a);
      seen[a.hex] = true;
      if (!a.hasPos) return;
      var st = acs[a.hex] || (acs[a.hex] = { trail: [] });
      var last = st.trail.length ? st.trail[st.trail.length - 1] : null;
      if (!last || Math.abs(last[1] - a.lat) > 0.0002 || Math.abs(last[2] - a.lon) > 0.0002) st.trail.push([now, a.lat, a.lon]);
      while (st.trail.length && st.trail[0][0] < cutoff) st.trail.shift();
    });
    // avisos que ya no están: se borran del mapa y se olvida su estela
    Object.keys(acs).forEach(function (hex) {
      if (!seen[hex]) {
        if (acs[hex].marker) planeGroup.removeLayer(acs[hex].marker);
        if (acs[hex].poly) trailGroup.removeLayer(acs[hex].poly);
        delete acs[hex];
        if (selected === hex) selected = null;
      }
    });
  }

  function labelHtml(a, sel) {
    if (S.labels.mode === 'never') return '';
    if (S.labels.mode === 'selected' && !sel) return '';
    var f = S.labels.fields, ids = [], rest = [];
    if (f.flight) ids.push(a.flight || a.hex);
    if (f.reg && a.reg) ids.push(a.reg);
    if (f.hex && (!f.flight || a.flight)) ids.push(a.hex);
    if (f.alt && a.gnd !== true && fmtAlt(a.alt)) rest.push(fmtAlt(a.alt));
    if (f.speed && fmtSpeed(a.speed)) rest.push(fmtSpeed(a.speed));
    if (f.track && fmtTrack(a.track)) rest.push(fmtTrack(a.track));
    if (f.vr && fmtVr(a.vr)) rest.push(fmtVr(a.vr));
    if (f.dist && a.dist != null) rest.push(fmtDist(a.dist));
    if (f.age) rest.push('⟲ ' + fmtPing(a.age));
    if (a.gnd === true && (ids.length || rest.length)) rest.unshift('tierra');
    if (!ids.length && !rest.length) return '';
    return (ids.length ? '<b>' + esc(ids.join(' · ')) + '</b>' : '') + (ids.length && rest.length ? '<br>' : '') + esc(rest.join(' · '));
  }

  function drawPlanes() {
    var shown = {};
    current.forEach(function (a) {
      if (!a.hasPos || !passes(a)) return;
      shown[a.hex] = true;
      var st = acs[a.hex];
      var sel = selected === a.hex;
      var lbl = labelHtml(a, sel);
      var rot = a.track != null ? a.track : 0;
      var html = '<div class="ac" style="transform:rotate(' + rot + 'deg)">' + PLANE_SVG.replace('@C', colorFor(a)) + '</div>' +
        (lbl ? '<div class="lbl">' + lbl + '</div>' : '');
      var cls = 'plane' + (a.gnd === true ? ' gnd' : '') + (sel ? ' sel' : '');
      var key = cls + '|' + html;
      if (!st.marker) {
        st.marker = L.marker([a.lat, a.lon], { icon: L.divIcon({ className: cls, iconSize: [28, 28], iconAnchor: [14, 14], html: html }), zIndexOffset: sel ? 1000 : 0 });
        (function (hex) { st.marker.on('click', function () { select(hex, false); }); })(a.hex);
        st.marker.addTo(planeGroup);
        st.key = key;
      } else {
        st.marker.setLatLng([a.lat, a.lon]);
        st.marker.setZIndexOffset(sel ? 1000 : 0);
        if (st.key !== key) { st.marker.setIcon(L.divIcon({ className: cls, iconSize: [28, 28], iconAnchor: [14, 14], html: html })); st.key = key; }
      }
    });
    Object.keys(acs).forEach(function (hex) {
      var st = acs[hex];
      if (!shown[hex] && st.marker) { planeGroup.removeLayer(st.marker); st.marker = null; st.key = null; }
    });
  }

  function drawTrails() {
    var now = Date.now(), from = now - S.trails.seconds * 1000;
    Object.keys(acs).forEach(function (hex) {
      var st = acs[hex];
      var a = null;
      for (var i = 0; i < current.length; i++) if (current[i].hex === hex) { a = current[i]; break; }
      var want = S.trails.on && a && passes(a) && a.hasPos;
      if (!want) { if (st.poly) { trailGroup.removeLayer(st.poly); st.poly = null; } return; }
      var pts = [];
      for (var k = 0; k < st.trail.length; k++) if (st.trail[k][0] >= from) pts.push([st.trail[k][1], st.trail[k][2]]);
      if (pts.length < 2) { if (st.poly) { trailGroup.removeLayer(st.poly); st.poly = null; } return; }
      if (!st.poly) st.poly = L.polyline(pts, { color: colorFor(a), weight: 2, opacity: 0.75, interactive: false }).addTo(trailGroup);
      else { st.poly.setLatLngs(pts); st.poly.setStyle({ color: colorFor(a) }); }
    });
  }

  // ---------- panel lateral ----------
  var sideEl, listEl, detailEl, headEl;
  function buildSide() {
    sideEl = document.createElement('div'); sideEl.id = 'side';
    sideEl.innerHTML = '<div class="hd"><b id="sidehd"></b><button id="sidex" aria-label="Cerrar">✕</button></div>' +
      '<div id="detail"></div><div id="list"></div>';
    document.body.appendChild(sideEl);
    listEl = sideEl.querySelector('#list'); detailEl = sideEl.querySelector('#detail'); headEl = sideEl.querySelector('#sidehd');
    sideEl.querySelector('#sidex').onclick = function () { setPanel(false); };
    L.DomEvent.disableClickPropagation(sideEl);
    L.DomEvent.disableScrollPropagation(sideEl);
    listEl.addEventListener('click', function (ev) {
      var n = ev.target; while (n && n !== listEl && !n.getAttribute('data-hex')) n = n.parentNode;
      if (n && n !== listEl) select(n.getAttribute('data-hex'), true);
    });
  }
  function setPanel(on) {
    S.panel = !!on; saveSettings();
    sideEl.classList.toggle('open', S.panel);
    document.body.classList.toggle('panel-open', S.panel);
    var b = document.getElementById('btn-list'); if (b) b.classList.toggle('on', S.panel);
    if (S.panel) drawList();
  }

  function fieldRows(a) {
    var rows = [];
    function add(k, v) { if (v != null && v !== '') rows.push([k, v]); }
    add('Matrícula', a.reg);
    add('ICAO', a.hex);
    add('Estado', a.gnd === true ? 'En tierra' : (a.gnd === false ? 'En el aire' : 'Desconocido'));
    if (a.gnd !== true) add('Altitud', fmtAlt(a.alt));
    add('Velocidad', fmtSpeed(a.speed));
    add('Rumbo', fmtTrack(a.track));
    if (a.gnd !== true) add('Vel. vertical', fmtVr(a.vr));
    add('Distancia a ' + refName(), a.dist != null ? fmtDist(a.dist) : null);
    add('Posición', a.hasPos ? a.lat.toFixed(4) + ', ' + a.lon.toFixed(4) : 'sin posición todavía');
    add('Mensajes', a.msgs);
    return rows;
  }

  function drawList() {
    if (!S.panel) return;
    var items = current.filter(passes).filter(function (a) { return a.hasPos || S.listNoPos; });
    items.sort(function (x, y) {
      if (x.dist == null && y.dist == null) return x.id < y.id ? -1 : 1;
      if (x.dist == null) return 1;
      if (y.dist == null) return -1;
      return x.dist - y.dist;
    });
    headEl.textContent = items.length + ' aviones · por cercanía a ' + refName();
    var f = S.labels.fields, top = listEl.scrollTop, html = '';
    items.forEach(function (a) {
      var rest = [];
      if (f.hex && a.flight) rest.push(a.hex);
      if (f.alt && a.gnd !== true && fmtAlt(a.alt)) rest.push(fmtAlt(a.alt));
      if (f.speed && fmtSpeed(a.speed)) rest.push(fmtSpeed(a.speed));
      if (f.track && fmtTrack(a.track)) rest.push(fmtTrack(a.track));
      if (f.vr && a.gnd !== true && fmtVr(a.vr)) rest.push(fmtVr(a.vr));
      var tag = a.gnd === true ? '<span class="tag">tierra</span>' : '';
      if (!a.hasPos) tag += '<span class="tag nopos">sin posición</span>';
      html += '<div class="row' + (selected === a.hex ? ' sel' : '') + '" data-hex="' + esc(a.hex) + '">' +
        '<div class="l1"><span>' + esc(a.id) + (S.listReg && a.reg ? '<span class="reg">' + esc(a.reg) + '</span>' : '') + tag + '</span><span>' + (a.dist != null ? esc(fmtDist(a.dist)) : '—') + '</span></div>' +
        '<div class="l2"><span>' + (rest.length ? esc(rest.join(' · ')) : '&nbsp;') + '</span>' + pingHtml(a, a.fresh) + '</div></div>';
    });
    listEl.innerHTML = html || '<div class="empty">Sin aviones con estos filtros.</div>';
    listEl.scrollTop = top;
  }

  function drawDetail() {
    var a = null;
    for (var i = 0; i < current.length; i++) if (current[i].hex === selected) a = current[i];
    if (!a) { detailEl.style.display = 'none'; return; }
    var t = '<div class="t"><span>' + esc(a.id) + '</span><button class="xbtn" id="deselect" aria-label="Quitar selección">✕</button></div><table>';
    fieldRows(a).forEach(function (r) { t += '<tr><td>' + esc(r[0]) + '</td><td>' + esc(r[1]) + '</td></tr>'; });
    t += '<tr><td>Último mensaje</td><td>' + pingHtml(a, a.fresh) + '</td></tr>';
    detailEl.innerHTML = t + '</table>';
    detailEl.style.display = 'block';
    detailEl.querySelector('#deselect').onclick = function () { select(null); };
  }

  function select(hex, pan) {
    selected = hex;
    var a = null;
    for (var i = 0; i < current.length; i++) if (current[i].hex === hex) a = current[i];
    if (a && pan && a.hasPos) map.panTo([a.lat, a.lon]);
    if (a && !S.panel) setPanel(true);
    redraw();
  }
  map.on('click', function () { if (selected) select(null); });

  function drawChip() {
    var chip = document.getElementById('chip');
    var vis = current.filter(passes), onMap = vis.filter(function (a) { return a.hasPos; }).length;
    var txt = vis.length + ' aviones · ' + onMap + ' en el mapa';
    var age = lastAt ? Math.round((Date.now() - lastAt) / 1000) : null;
    var snap = lastMeta && lastMeta.extraAge ? lastMeta.extraAge : 0;
    var da = currentDataAge();
    chip.classList.remove('warn');
    if (lastMeta && lastMeta.message) { txt = lastMeta.message; chip.classList.add('warn'); }
    else if (age == null) { txt = 'Esperando datos…'; chip.classList.add('warn'); }
    else if (da != null && da > 10) { txt += ' · sin datos hace ' + Math.floor(da) + ' s'; chip.classList.add('warn'); }
    else if (da == null && age + snap > 10) { txt += ' · sin datos hace ' + (age + snap) + ' s'; chip.classList.add('warn'); }
    chip.textContent = txt;
  }

  /* Segundos desde el último mensaje de cualquier avión (solo para el aviso general de la barra de estado) */
  function currentDataAge() {
    return dataAge == null ? null : dataAge + (Date.now() - dataAgeAt) / 1000;
  }

  function redraw() {
    drawPlanes(); drawTrails(); drawList(); drawDetail(); drawChip();
  }

  // ---------- API para el anfitrión ----------
  function update(list, meta) {
    lastList = Array.isArray(list) ? list : [];
    lastMeta = meta || {};
    lastAt = Date.now();
    var extra = lastMeta.extraAge || 0;
    ingest(lastList, extra);
    if (typeof lastMeta.lastDataAge === 'number') dataAge = lastMeta.lastDataAge + extra;
    else {
      var min = null;
      current.forEach(function (a) { if (min == null || a.age < min) min = a.age; });   // la edad ya incluye extraAge
      dataAge = min;
    }
    dataAgeAt = Date.now();
    redraw();
  }
  window.updateAircraft = function (list) { update(list, {}); };
  var api = window.RadarMap = {
    update: update,
    getSettings: function () { return clone(S); },
    setFullscreenState: function (on) { fsOn = !!on; refreshButtons(); },
    _state: function () { return { selected: selected, markers: Object.keys(acs).filter(function (h) { return acs[h].marker; }).length, trails: Object.keys(acs).filter(function (h) { return acs[h].poly; }).length }; }
  };

  // ---------- botones del mapa ----------
  var fsOn = false;
  function toggleFullscreen() {
    fsOn = !fsOn;
    if (host && host.setFullscreen) { try { host.setFullscreen(fsOn); } catch (e) { /* sin puente */ } }
    else {
      var el = document.documentElement;
      if (fsOn && el.requestFullscreen) el.requestFullscreen().catch(function () {});
      else if (!fsOn && document.exitFullscreen && document.fullscreenElement) document.exitFullscreen();
    }
    refreshButtons();
  }
  document.addEventListener('fullscreenchange', function () { fsOn = !!document.fullscreenElement; refreshButtons(); });
  function refreshButtons() {
    var bb = document.getElementById('btn-base'); if (bb) bb.classList.toggle('on', S.base === 'sat');
    var b = document.getElementById('btn-fs'); if (b) { b.classList.toggle('on', fsOn); b.title = fsOn ? 'Salir de pantalla completa' : 'Pantalla completa'; }
  }
  var Bar = L.Control.extend({
    options: { position: 'topleft' },
    onAdd: function () {
      var div = L.DomUtil.create('div', 'leaflet-bar rb');
      function btn(id, txt, title, fn) {
        var a = L.DomUtil.create('a', '', div); a.id = id; a.href = '#'; a.innerHTML = txt; a.title = title; a.setAttribute('role', 'button'); a.setAttribute('aria-label', title);
        L.DomEvent.on(a, 'click', function (e) { L.DomEvent.stop(e); fn(); });
      }
      btn('btn-set', '⚙', 'Ajustes', function () { openSettings(true); });
      btn('btn-list', '☰', 'Lista de aviones', function () { setPanel(!S.panel); });
      btn('btn-base', '🛰', 'Cambiar entre mapa y satélite', function () { S.base = S.base === 'sat' ? 'offline' : 'sat'; applyAll(); });
      btn('btn-fs', '⛶', 'Pantalla completa', toggleFullscreen);
      btn('btn-fit', '◎', 'Centrar en ' + refName(), function () { fitRings(); });
      L.DomEvent.disableClickPropagation(div);
      return div;
    }
  });
  map.addControl(new Bar());

  // ---------- ajustes (ventana) ----------
  var setEl;
  function toast(msg) {
    var t = document.createElement('div'); t.className = 'toast'; t.textContent = msg; document.body.appendChild(t);
    setTimeout(function () { if (t.parentNode) t.parentNode.removeChild(t); }, 2200);
  }
  function openSettings(on) {
    if (!setEl) buildSettings();
    setEl.classList.toggle('open', on);
  }
  function applyAll(refit) {
    REF = findAirport(S.airport) || REF;
    applyBase(); drawRings(); if (refit) fitRings(); redraw(); refreshButtons(); saveSettings();
  }
  function buildSettings() {
    setEl = document.createElement('div'); setEl.id = 'settings';
    var box = document.createElement('div'); box.className = 'box';
    box.innerHTML = '<div class="hd"><b>Ajustes del mapa</b><button class="xbtn" id="setx" aria-label="Cerrar">✕</button></div><div class="body"></div>';
    setEl.appendChild(box); document.body.appendChild(setEl);
    L.DomEvent.disableClickPropagation(setEl);
    var body = box.querySelector('.body');
    box.querySelector('#setx').onclick = function () { openSettings(false); };
    setEl.addEventListener('click', function (e) { if (e.target === setEl) openSettings(false); });

    function h(t) { var e = document.createElement('h3'); e.textContent = t; body.appendChild(e); }
    function note(t) { var e = document.createElement('div'); e.className = 'note'; e.textContent = t; body.appendChild(e); }
    function sel(label, get, opts, set) {
      var d = document.createElement('div'); d.className = 'f';
      d.innerHTML = '<span></span><select></select>'; d.firstChild.textContent = label;
      var s = d.lastChild;
      opts.forEach(function (o) { var op = document.createElement('option'); op.value = o[0]; op.textContent = o[1]; s.appendChild(op); });
      s.value = String(get());
      s.onchange = function () { set(s.value); };
      body.appendChild(d); return s;
    }
    function num(label, get, min, max, step, set) {
      var d = document.createElement('div'); d.className = 'f';
      d.innerHTML = '<span></span><input type="number">'; d.firstChild.textContent = label;
      var i = d.lastChild; i.min = min; i.max = max; i.step = step; i.value = get();
      i.onchange = function () { var v = parseFloat(i.value); if (isNaN(v)) v = get(); v = Math.max(min, Math.min(max, v)); i.value = v; set(v); };
      body.appendChild(d); return i;
    }
    function chk(parent, label, get, set) {
      var l = document.createElement('label'); var c = document.createElement('input'); c.type = 'checkbox'; c.checked = !!get();
      c.onchange = function () { set(c.checked); };
      l.appendChild(c); l.appendChild(document.createTextNode(' ' + label)); parent.appendChild(l); return c;
    }
    function chkRow(label, get, set) {
      var d = document.createElement('div'); d.className = 'f c'; chk(d, label, get, set); body.appendChild(d);
    }

    h('Unidades');
    sel('Distancia', function () { return S.units.dist; }, [['km', 'Kilómetros (km)'], ['mi', 'Millas (mi)'], ['nm', 'Millas náuticas (NM)']], function (v) { S.units.dist = v; applyAll(); });
    sel('Altitud', function () { return S.units.alt; }, [['ft', 'Pies (ft)'], ['m', 'Metros (m)'], ['fl', 'Nivel de vuelo (FL)']], function (v) { S.units.alt = v; applyAll(); });
    sel('Velocidad', function () { return S.units.speed; }, [['kt', 'Nudos (kt)'], ['kmh', 'km/h'], ['mph', 'mph']], function (v) { S.units.speed = v; applyAll(); });
    sel('Velocidad vertical', function () { return S.units.vr; }, [['fpm', 'ft/min'], ['ms', 'm/s']], function (v) { S.units.vr = v; applyAll(); });

    h('Anillos de distancia');
    chkRow('Mostrar anillos', function () { return S.rings.show; }, function (v) { S.rings.show = v; applyAll(); });
    num('Número de anillos', function () { return S.rings.count; }, 1, 40, 1, function (v) { S.rings.count = Math.round(v); applyAll(true); });
    num('Distancia entre anillos (en la unidad elegida)', function () { return S.rings.step; }, 1, 500, 1, function (v) { S.rings.step = v; applyAll(true); });
    chkRow('Etiquetas de distancia', function () { return S.rings.labels; }, function (v) { S.rings.labels = v; applyAll(); });

    h('Aeropuerto de referencia');
    var d = document.createElement('div'); d.className = 'f';
    d.innerHTML = '<span>Código IATA u OACI</span><input type="text" id="apin" maxlength="4" size="6">'; body.appendChild(d);
    var apin = d.querySelector('#apin'); apin.value = refName();
    var go = document.createElement('button'); go.className = 'b'; go.textContent = 'Cambiar aeropuerto';
    go.onclick = function () {
      var a = findAirport(apin.value);
      if (!a) { toast('Aeropuerto no encontrado en la región del mapa'); return; }
      S.airport = a.icao; REF = a; apin.value = refName(); applyAll(true); toast('Referencia: ' + a.name);
    };
    body.appendChild(go);
    note('Centro de los anillos y base de las distancias. Solo aeropuertos de la región (Mediterráneo occidental).');

    h('Estelas');
    chkRow('Mostrar estelas', function () { return S.trails.on; }, function (v) { S.trails.on = v; applyAll(); });
    sel('Duración de la estela', function () { return S.trails.seconds; }, [[60, '1 minuto'], [120, '2 minutos'], [300, '5 minutos'], [600, '10 minutos']], function (v) { S.trails.seconds = +v; applyAll(); });

    h('Aviones');
    sel('Mostrar', function () { return S.ground; }, [['all', 'Todos'], ['air', 'Solo en el aire'], ['gnd', 'Solo en tierra']], function (v) { S.ground = v; applyAll(); });
    sel('Ocultar si no hay datos desde hace', function () { return S.maxAge; }, [[15, '15 s'], [30, '30 s'], [60, '60 s'], [120, '2 min']], function (v) { S.maxAge = +v; applyAll(); });
    chkRow('Matrícula junto al indicativo en la lista', function () { return S.listReg; }, function (v) { S.listReg = v; applyAll(); });
    chkRow('Listar también los aviones sin posición', function () { return S.listNoPos; }, function (v) { S.listNoPos = v; applyAll(); });
    note('En el mapa el color indica la altitud: naranja <10 000 ft, amarillo <20 000, verde <30 000, azul <40 000, morado ≥40 000; gris = en tierra.');

    h('Datos junto a cada avión');
    sel('Mostrarlos', function () { return S.labels.mode; }, [['always', 'Siempre'], ['selected', 'Solo en el avión seleccionado'], ['never', 'Nunca']], function (v) { S.labels.mode = v; applyAll(); });
    var cw = document.createElement('div'); cw.className = 'chk'; body.appendChild(cw);
    [['flight', 'Indicativo'], ['reg', 'Matrícula'], ['hex', 'ICAO'], ['alt', 'Altitud'], ['speed', 'Velocidad'], ['track', 'Rumbo'], ['vr', 'Vel. vertical'], ['dist', 'Distancia'], ['age', 'Último dato (s)']].forEach(function (f) {
      chk(cw, f[1], function () { return S.labels.fields[f[0]]; }, function (v) { S.labels.fields[f[0]] = v; applyAll(); });
    });
    note('El avión seleccionado muestra siempre todos sus datos en el panel lateral.');

    h('Mapa');
    sel('Fondo', function () { return S.base; }, [['offline', 'Mapa sin conexión (costas y fronteras)'], ['sat', satAvailable() ? 'Satélite (descargado en la tablet)' : 'Satélite (necesita internet)'], ['osm', 'OpenStreetMap (necesita internet)']], function (v) { S.base = v; applyAll(); });
    if (satAvailable()) {
      h('Satélite sin conexión');
      var st = document.createElement('div'); st.className = 'note'; body.appendChild(st);
      var row = document.createElement('div'); body.appendChild(row);
      function mk(txt, fn) { var b = document.createElement('button'); b.className = 'b'; b.textContent = txt; b.onclick = fn; row.appendChild(b); return b; }
      var bDown = mk('Descargar zona de ' + refName(), function () {
        if (confirm('Se descargarán imágenes de satélite alrededor de ' + refName() + ' (necesita internet; ocupa unas decenas de MB). ¿Continuar?')) {
          try { host.satDownload(REF.lat, REF.lon); } catch (e) { /* sin puente */ }
          setTimeout(upd, 300);
        }
      });
      var bCan = mk('Cancelar', function () { try { host.satCancel(); } catch (e) { /* sin puente */ } });
      var bClr = mk('Borrar', function () { if (confirm('¿Borrar las imágenes de satélite guardadas?')) { try { host.satClear(); } catch (e) { /* sin puente */ } upd(); } });
      var tm = null;
      function upd() {
        var i = null; try { i = JSON.parse(host.satInfo()); } catch (e) { /* sin datos */ }
        if (!i) return;
        var mb = (i.bytes / 1048576).toFixed(1);
        st.textContent = i.running ? 'Descargando… ' + i.done + ' / ' + i.total + (i.failed ? ' (' + i.failed + ' fallidas)' : '') + ' · ' + mb + ' MB guardados'
          : (i.tiles ? i.tiles + ' imágenes guardadas (' + mb + ' MB)' + (i.failed ? ' · ' + i.failed + ' no se pudieron descargar; vuelve a pulsar Descargar para reintentar' : '') : 'Todavía no hay imágenes guardadas.');
        bDown.disabled = i.running; bCan.disabled = !i.running; bClr.disabled = i.running || !i.tiles;
        if (i.running && !tm) tm = setInterval(upd, 1000);
        if (!i.running && tm) { clearInterval(tm); tm = null; if (S.base === 'sat') applyAll(); }
      }
      upd();
      note('Conéctate a internet, pulsa «Descargar» una vez y después el satélite se ve sin conexión. Imágenes: Sentinel-2 cloudless de EOX (uso no comercial).');
    }
    chkRow('Mostrar aeropuertos', function () { return S.showAirports; }, function (v) { S.showAirports = v; applyAll(); });
    chkRow('Mostrar fronteras', function () { return S.showBorders; }, function (v) { S.showBorders = v; applyAll(); });
    var reset = document.createElement('button'); reset.className = 'b'; reset.textContent = 'Restablecer todos los ajustes';
    reset.onclick = function () {
      S = clone(DEFAULTS); saveSettings(); REF = findAirport(S.airport) || REF;
      if (setEl.parentNode) setEl.parentNode.removeChild(setEl); setEl = null;
      applyAll(true); setPanel(S.panel); toast('Ajustes restablecidos');
    };
    body.appendChild(reset);
  }

  // ---------- arranque ----------
  var chip = document.createElement('div'); chip.id = 'chip'; document.body.appendChild(chip);
  buildSide();
  zoomClasses();
  applyBase(); drawRings(); fitRings();
  if (S.panel) setPanel(true);
  refreshButtons();
  drawChip();
  setInterval(drawChip, 1000);
  setInterval(tickPings, 250);   // el contador de cada avión cambia justo al cumplirse el segundo
})();
