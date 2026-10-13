# Enviar los aviones de la Raspberry Pi al radar de SWAP-TURNOS

`radar-subir.py` lee el `aircraft.json` de tu PiAware/dump1090 (el mismo que lee tu radar local) y lo envía firmado a
`radar/subir`, junto con la CPU, RAM y temperatura de la Raspberry. **No modifica nada de PiAware ni de tu radar local.**
Solo necesita Python 3 (ya viene en Raspberry Pi OS).

## Instalar (por SSH)

1. Copia esta carpeta a la Raspberry, por ejemplo a `~/radar-subir`:
   ```
   scp -r tools/raspberry usuario@192.168.0.192:~/radar-subir
   ```
   (o crea los archivos a mano con `nano`).
2. Crea la configuración y pon tu código de 6 cifras:
   ```
   cd ~/radar-subir
   cp radar-subir.ini.ejemplo radar-subir.ini
   nano radar-subir.ini        # cambia «codigo = 000000» por tu código
   chmod 600 radar-subir.ini
   ```
3. **Prueba** (un solo envío, no deja nada instalado):
   ```
   python3 radar-subir.py --probar
   ```
   Debe terminar con `OK`. Abre el radar en la web y comprueba que salen los aviones y `🖥 CPU … · RAM … · … °C`.
4. Déjalo funcionando siempre:
   ```
   sudo sh instalar.sh
   ```

## Matrículas
PiAware no trae la matrícula de los aviones. El script la busca por código ICAO en la misma base que usa la app de la tablet
(`matriculas.bin`, ~570 000 aviones, 6 MB, sin internet). Descárgala una vez junto al script y reinicia:
```
cd ~/radar-subir
curl -L -o matriculas.bin https://raw.githubusercontent.com/formiguer/rtl.sdr.usb/main/app/src/main/assets/matriculas.bin
sudo systemctl restart radar-subir
```
En el registro (`journalctl -u radar-subir`) debe salir «Base de matrículas: 568799 aviones». En el mapa, marca además
«Matrícula» en los ajustes → «Datos junto a cada avión». Si un avión no está en la base, sale sin matrícula.

## Comandos útiles
- Estado: `systemctl status radar-subir`
- Registro: `journalctl -u radar-subir -f`
- Parar: `sudo systemctl stop radar-subir`
- **Quitarlo del todo:** `sudo systemctl disable --now radar-subir && sudo rm /etc/systemd/system/radar-subir.service`
  y borrar la carpeta. La Raspberry queda como estaba.

## Si algo falla
- `El servidor no contestó JSON` / `aes.js`: Byethost pide a los programas una cookie `__test` que normalmente pone el navegador.
  Cópiala una vez de tu navegador: abre tu web, `F12` → pestaña **Application** (Chrome) → **Cookies** → `blas.byethost7.com` → fila `__test` → copia el **Value**
  y pégalo en `radar-subir.ini` (`cookie_test = ...`). Suele durar años; si algún día vuelve el error, repite esto.
- `Firma no válida`: el código del `.ini` no es el que activaste en el radar (☁ Tablet).
- `El radar no está activado`: activa el código en el radar (☁ Tablet).
- `No se puede leer aircraft.json`: prueba `curl http://localhost/skyaware/data/aircraft.json` y ajusta `aircraft_json` en el `.ini`
  (a veces es `http://localhost:8080/data/aircraft.json`; también vale la ruta de un archivo: `/run/dump1090-fa/aircraft.json`).
- El código solo se comprueba en el servidor; el servidor es `http`, así que el código no viaja: cada envío va firmado (ver README principal).
