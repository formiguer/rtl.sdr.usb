# rtl.sdr.usb

App Android para recibir ADS-B (aviones) con un dongle **RTL-SDR** por USB OTG y verlos en un mapa.

- Objetivo: tablet con Android 7.1.1 (API 25).
- Estado: **fase 2** — decodificador ADS-B y aviones en el mapa.

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

## Módulos
- `adsb/` — decodificador en Kotlin puro con tests (CRC24, demodulador 2 Msps, CPR, identificación, velocidad, cliente rtl_tcp).
- `app/` — interfaz Android (estado + mapa Leaflet/OpenStreetMap).

## Hoja de ruta
1. Detección USB + mapa. ✅
2. Decodificador ADS-B + aviones en el mapa. ✅ (pendiente de probar con antena real)
3. Driver USB propio (sin app externa), historial de rutas y extras.
