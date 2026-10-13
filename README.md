# rtl.sdr.usb

App Android para recibir ADS-B (aviones) con un dongle **RTL-SDR** por USB OTG y verlos en un mapa.

- Objetivo: tablet con Android 7.1.1 (API 25).
- Estado: **fase 1** — detección del RTL-SDR, permiso USB y mapa base (Leaflet/OpenStreetMap).

## Hoja de ruta
1. Esqueleto: detección USB + mapa. ✅
2. Decodificador ADS-B nativo (NDK) y lista de aviones en tiempo real.
3. Aviones en el mapa (posición, altitud, velocidad, rumbo).
4. Historial de rutas y extras.

## Instalar
Cada push compila un APK en GitHub Actions (pestaña *Actions* → último run → *Artifacts*).
En la tablet: permitir "orígenes desconocidos" e instalar el APK.
