# rtl.sdr.usb

App Android para recibir ADS-B (aviones) con un dongle **RTL-SDR** por USB OTG y verlos en un mapa.

- Objetivo: tablet con Android 7.1.1 (API 25).
- Estado: **fase 3** — ADS-B con mapa centrado en Palma + pestaña de banda aérea (audio AM 118–137 MHz).

## Cómo funciona
```
RTL-SDR ──USB OTG──▶ app "driver" (rtl_tcp, puerto 1234) ──TCP local──▶ esta app
                                                             demodula 1090 MHz → CRC → posición/altitud/velocidad → mapa
```
La parte USB la hace una app de driver RTL-SDR que expone el servidor `rtl_tcp`
(en Google Play: *RTL2832U driver* de Martin Marinov, paquete `marto.rtl_tcp_andro`, u otra equivalente).
Esta app solo se conecta a `127.0.0.1:1234`, configura 1090 MHz / 2 Msps / ganancia 49.6 dB y decodifica.

## Uso
1. Instala el driver RTL-SDR y esta app (APK de *Actions → Artifacts*).
2. Conecta el dongle con el cable OTG y abre el driver; acepta el permiso USB y pulsa **Start**.
3. Vuelve a esta app: arriba verás el estado y los mensajes ADS-B recibidos; los aviones salen en el mapa.

Necesitas antena para 1090 MHz y, para ver aviones, vista despejada (ventana/exterior).

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
4. Driver USB propio (sin app externa), historial de rutas y extras.
