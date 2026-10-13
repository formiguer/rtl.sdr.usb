# Raspberry Pi del radar (`192.168.0.192`): restaurar tras una SD rota

Esta Raspberry recibe los aviones (PiAware / dump1090-fa + dongle RTL-SDR), sirve el estado del equipo en el puerto 5050
y los envía al radar de **SWAP-TURNOS** (`http://blas.byethost7.com/swap_repo_v1/`, sección RADAR).
Este documento y los archivos de esta carpeta bastan para reconstruirla. **Aquí no hay contraseñas ni el código del radar**
(el repositorio es público): ver «Dónde está el código» más abajo.

> El código de `radar-subir.py` se mantiene en el repositorio `formiguer/swap-turnos` (`tools/raspberry/`).
> Esta carpeta guarda una copia por si ese repositorio no estuviera a mano.

## Datos de la instalación
| Dato | Valor |
|---|---|
| Equipo | Raspberry Pi · nombre `raspberrypi` · usuario `pi` · IP `192.168.0.192` (fíjala en el router) |
| Sistema | Raspberry Pi OS / Debian reciente (arranque en `/boot/firmware`). **No trae `raspi-config`** |
| SD | 29 GB (el sistema se amplió a mano, ver paso 5) |
| Decodificador | PiAware + dump1090-fa. Aviones en `http://localhost/skyaware/data/aircraft.json` |
| Estado del equipo | `http://localhost:5050/status` → `{"cpu":"11.0%","ram":"22.5%","temp":47.2}` ⚠ **su programa no está documentado aquí**: ejecuta `inventario.sh` (abajo) para saber dónde está y guárdalo |
| Envío al radar | `~/radar-subir/radar-subir.py` + `radar-subir.ini` + servicio `radar-subir` |
| Otras Raspberry | `.130` Pi-NODE-1 · `.139` FlightFeeder (la del `skyaware` del monitor `control-raspi`) · todas con estado en el puerto 5050 |
| Radar en la web | icono RADAR de «Más secciones». El administrador general ve el botón «☁ Tablet» |

## Dónde está el código de 6 cifras
No se guarda en ningún repositorio. Para recuperarlo: entra en la web como **administrador general** → RADAR → **☁ Tablet**.
Ahí se ve (o se cambia por otro). Ese mismo código va en `radar-subir.ini` (`codigo = ...`).

## Restaurar en una SD nueva

### Camino A — tienes una imagen de la SD (lo más rápido)
1. Descomprime: `gunzip raspi192-FECHA.img.gz`.
2. Grábala con **Raspberry Pi Imager → «Usar personalizado»** en una SD **igual o mayor** (la imagen tiene el tamaño de la SD original).
3. Arranca. Si la SD es mayor, amplía (paso 5). Comprueba (paso 9).

### Camino B — desde cero
1. **Sistema:** Raspberry Pi Imager → Raspberry Pi OS Lite (64 bits o el que corresponda a tu modelo).
   En «Ajustes»: nombre `raspberrypi`, usuario `pi` + contraseña, **activar SSH**, Wi-Fi si no usas cable.
2. **IP fija:** en el router, reserva `192.168.0.192` para esta Raspberry (por su MAC). Entra: `ssh pi@192.168.0.192`
   (si avisa de que la huella cambió: `ssh-keygen -R 192.168.0.192` y vuelve a entrar; **sin `sudo`**).
3. **Actualiza:** `sudo apt update && sudo apt full-upgrade -y`.
4. **PiAware + dump1090-fa:** instálalos con las instrucciones oficiales de FlightAware para tu sistema
   (https://flightaware.com/adsb/piaware/install). Enchufa el dongle RTL-SDR y comprueba:
   `curl -s http://localhost/skyaware/data/aircraft.json | head -c 300` → debe salir JSON con `"aircraft"`.
   Si usas el dongle y la antena grande del FlightFeeder, es solo cambiar el dongle: se detecta solo.
   Vincula la Raspberry a tu cuenta de FlightAware desde su web si te lo pide.
5. **Ampliar el sistema a toda la SD** (no hay `raspi-config`):
   ```
   sudo apt install -y cloud-guest-utils
   sudo growpart /dev/mmcblk0 2
   sudo resize2fs /dev/mmcblk0p2
   df -h /      # debe verse todo el tamaño de la SD
   ```
6. **Servicio de estado (puerto 5050):** restáuralo desde tu copia nocturna de la nube (ver «Copias de seguridad»)
   o, si no hay copia, vuelve a crearlo. Su descripción exacta está en `inventario-raspi.txt` (generado con `inventario.sh`).
   Debe responder `http://IP:5050/status` con `cpu`, `ram` y `temp`. El monitor `control-raspi` lo usa en las tres Raspberry.
7. **Envío al radar:**
   ```
   mkdir ~/radar-subir && cd ~/radar-subir
   curl -O https://raw.githubusercontent.com/formiguer/rtl.sdr.usb/main/raspberry/radar-subir.py
   curl -O https://raw.githubusercontent.com/formiguer/rtl.sdr.usb/main/raspberry/radar-subir.ini.ejemplo
   curl -O https://raw.githubusercontent.com/formiguer/rtl.sdr.usb/main/raspberry/instalar.sh
   cp radar-subir.ini.ejemplo radar-subir.ini
   nano radar-subir.ini     # pon tu código de 6 cifras en «codigo =» y revisa las URLs (abajo)
   chmod 600 radar-subir.ini
   ```
   `radar-subir.ini` correcto:
   ```
   [radar]
   url = http://blas.byethost7.com/swap_repo_v1/
   codigo = TU_CODIGO_DE_6_CIFRAS
   aircraft_json = http://localhost/skyaware/data/aircraft.json
   estado_url = http://localhost:5050/status
   nombre = Raspberry Pi
   lat = 39.548
   lon = 2.733
   ```
8. **Probar y dejar fijo:**
   ```
   python3 radar-subir.py --probar     # debe terminar con «OK»
   sudo sh instalar.sh                  # crea y arranca el servicio radar-subir
   systemctl status radar-subir         # «active (running)»; sal con q
   ```
   El script resuelve solo la comprobación anti-bots del hosting (Byethost) con `openssl` (ya incluido en el sistema).
9. **Comprobación final:** `sudo reboot`; después, en la web, RADAR: aviones en el mapa y `🖥 CPU · RAM · °C` arriba.
   `http://192.168.0.192/skyaware/` y `http://192.168.0.192:5050/status` deben responder.

## Si algo falla al enviar
`journalctl -u radar-subir -f` (sal con Ctrl+C).
- `Firma no válida` → el código del `.ini` no es el de la web (☁ Tablet).
- `El radar no está activado` → actívalo con un código en la web.
- `El servidor no contestó JSON ...` → Byethost cambió su comprobación anti-bots; pega el error completo en una sesión de Claude.
- No se lee `aircraft.json` → prueba la URL con `curl`; a veces es `http://localhost:8080/data/aircraft.json` o el archivo `/run/dump1090-fa/aircraft.json` (vale como ruta).

## Copias de seguridad (para que esto sea fácil)
- **Imagen completa, una vez** (desde tu ordenador; mejor con la Raspberry parada y `sync`):
  `ssh pi@192.168.0.192 "sudo dd if=/dev/mmcblk0 bs=4M status=none | gzip -" > raspi192-$(date +%F).img.gz`
- **Copia ligera nocturna a la nube** con `rclone` (guarda `/home/pi`, `/etc/piaware.conf`, `/etc/default/dump1090-fa`,
  `/etc/systemd/system/radar-subir.service`, `/etc/fstab`, `/etc/hostname`). Restaurar: `rclone copy nube:copias-raspi/ARCHIVO .`
  y `sudo tar xzf ARCHIVO -C /` + `sudo systemctl daemon-reload && sudo systemctl enable --now radar-subir`.
- **Inventario** (qué hay instalado y dónde está el servicio 5050): `sh inventario.sh` en la Raspberry y copia
  `inventario-raspi.txt` a tu ordenador. Hazlo de nuevo cada vez que cambies algo importante.
- Las SD fallan: usa una «high endurance», apaga siempre con `sudo shutdown now` y guarda una SD de repuesto ya grabada.

## Archivos de esta carpeta
- `radar-subir.py` · `radar-subir.ini.ejemplo` · `instalar.sh` — envío al radar (copia de `swap-turnos/tools/raspberry`).
- `inventario.sh` — vuelca la configuración de la Raspberry (sin contraseñas ni el código) para poder reconstruirla.
