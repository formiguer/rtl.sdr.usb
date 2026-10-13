# rtl.sdr.usb

App Android para recibir ADS-B (aviones) con un dongle **RTL-SDR** por USB OTG y verlos en un mapa.

- Objetivo: tablet con Android 7.1.1 (API 25).
- Estado: ADS-B con mapa centrado en Palma, banda aérea (audio AM 118–137 MHz) y **driver USB integrado**.

## Cómo funciona
```
RTL-SDR ──USB OTG──▶ driver USB integrado (RTL2832U + R820T/R820T2) ──▶ ADS-B (1090 MHz)  → mapa
                                                                     └▶ banda aérea AM (118–137 MHz) → audio
```
La app habla directamente con el dongle: al abrirla (o al enchufarlo) Android pide el permiso USB y no hace falta
ninguna otra app. Si prefieres un driver externo, marca la casilla *Usar un driver externo (rtl_tcp)* y la app
leerá de `127.0.0.1:1234` (p. ej. *RTL2832U driver* de Martin Marinov).

### El driver USB
`adsb/src/main/kotlin/.../driver` es un port a Kotlin de [librtlsdr](https://github.com/osmocom/rtl-sdr) limitado al
camino **R820T / R820T2** (los dongles RTL-SDR más habituales). Con otros sintonizadores (E4000, FC0012/13, R828D)
la app lo indica y se puede usar el driver externo.
Se verifica con `DriverTranscriptTest`: el driver Kotlin debe generar **la misma secuencia de 341 transferencias USB,
byte a byte**, que el `librtlsdr` oficial (ver `tools/ref-transcript`). Lo que no se puede comprobar sin hardware es
el comportamiento real del dongle en cada tablet.

## Uso
1. Instala el APK (*Actions → último run → Artifacts*). Si ya tenías una versión anterior firmada con otra clave, desinstálala una vez.
2. Conecta el dongle con el cable OTG y acepta el permiso USB (marca "usar de forma predeterminada").
3. Pestaña **ADS-B** para los aviones o **Banda aérea** para escuchar.

Necesitas antena para 1090 MHz (ADS-B) y otra de VHF (banda aérea), y vista despejada.

## Mapa de aviones
Funciona **sin conexión** (Leaflet, costas, fronteras y 245 aeropuertos van dentro de la app, ver `app/src/main/assets/map/`).
Botones del mapa: ⚙ ajustes, ☰ lista, ⛶ pantalla completa, ◎ centrar.
- **Ajustes:** unidades (km / millas / millas náuticas; ft / m / FL; kt / km/h / mph; ft/min / m/s), número y distancia de los anillos,
  aeropuerto de referencia (por IATA u OACI), estelas (con duración), filtro aire / tierra / todos, qué datos se ven junto a cada avión
  (siempre o solo en el seleccionado) y fondo (sin conexión u OpenStreetMap).
- **Lista lateral** ordenada por cercanía al aeropuerto; al tocar un avión se muestra su ficha completa.
- **En tierra:** se decodifican los mensajes de superficie del ADS-B (posición, velocidad y rumbo de los aviones que ruedan).
- Los ajustes se guardan en la tablet. Prueba de la interfaz: `node tests/map-ui/map-ui.mjs` (necesita Playwright).

## Pestaña "Banda aérea"
Receptor AM de toda la banda aeronáutica (118.000–136.990 MHz, canales de 25 y 8,33 kHz):
- Sintonía manual (teclear frecuencia, pasos de 25 kHz u 8,33 kHz, ±1 MHz), memorias, volumen, squelch y ganancia.
- **Escáner**: recorre la banda completa (22 ventanas de 1,2 MHz con FFT), se para en la frecuencia con portadora,
  la escucha mientras dure y sigue. "Excluir" bloquea una frecuencia (interferencias fijas) y "Actividad" lista lo detectado.
- Con un solo dongle, ADS-B y banda aérea **se turnan**: al cambiar de pestaña se re-sintoniza.
- Necesita una antena de VHF; la de 1090 MHz casi no recibe esta banda.

## Módulos
- `adsb/` — procesado de señal en Kotlin puro con tests: ADS-B (CRC24, demodulador 2 Msps, CPR, velocidad), receptor AM, escáner de banda y cliente rtl_tcp.
- `app/` — interfaz Android (estado + mapa Leaflet/OpenStreetMap).

## Hoja de ruta
1. Detección USB + mapa. ✅
2. Decodificador ADS-B + aviones en el mapa. ✅ (pendiente de probar con antena real)
3. Banda aérea: sintonía, escáner y audio. ✅ (pendiente de probar con antena VHF real)
4. Driver USB propio (sin app externa). ✅ (pendiente de probar con el dongle)
5. Historial de rutas, otros sintonizadores y extras.

## Licencia
GPL-2.0-or-later (ver `LICENSE`): el driver USB es un port de librtlsdr / tuner_r82xx.c
(© Steve Markgraf, Mauro Carvalho Chehab y colaboradores de osmocom/rtl-sdr).

## Matrículas

La app lleva incluida una base offline de matrículas por código ICAO (`app/src/main/assets/matriculas.bin`, ~570 000 aviones, datos de
[tar1090-db](https://github.com/wiedehopf/tar1090-db)). Se muestra junto al indicativo en el panel lateral, en la ficha del avión y,
opcionalmente, en la etiqueta del mapa. Se regenera con `python3 tools/matriculas/build.py`.

## Satélite sin conexión

El botón 🛰 del mapa alterna entre el mapa de costas y la vista de satélite. Las imágenes (Sentinel-2 sin nubes de
[EOX](https://s2maps.eu), uso no comercial) no vienen en el APK: en *Ajustes → Satélite sin conexión* se pulsa **Descargar** una vez
con internet y se guardan en la tablet (zoom 3–13 alrededor del aeropuerto de referencia, más detalle cuanto más cerca). Después se ven
sin conexión. La descarga se puede cancelar y reanudar, y se puede borrar. En el visor web de swap-turnos el satélite se carga de internet.

## Conexión con el servidor

En *☁ Servidor* se escribe el **código de 6 cifras** que se elige en swap-turnos (RADAR → ☁ Tablet); no hace falta dirección (se usa la de `RadarUploader.DEFAULT_SERVER`; para otro servidor se admite `dirección|código`). La app se identifica con un User-Agent de navegador porque algunos hostings gratuitos devuelven 403 a clientes que no lo parecen, y si el servidor responde con una página que no es de la API, el mensaje de error muestra un trozo de su texto para poder diagnosticarlo.
