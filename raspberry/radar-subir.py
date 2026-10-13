#!/usr/bin/env python3
"""
Envía los aviones que recibe esta Raspberry Pi (PiAware / dump1090-fa / readsb) al radar de SWAP-TURNOS.

Solo LEE el aircraft.json del decodificador (el mismo que lee tu radar local) y manda una copia firmada
a «radar/subir». No modifica ni reinicia nada del decodificador. Solo usa la librería estándar de Python 3.

  python3 radar-subir.py                 envía en bucle (así lo usa el servicio systemd)
  python3 radar-subir.py --probar        un solo envío, muestra lo que se manda y la respuesta, y termina
  python3 radar-subir.py -c otra.ini     usa otro archivo de configuración (por defecto: radar-subir.ini junto al script)
"""
import argparse
import configparser
import hashlib
import hmac
import json
import os
import re
import struct
import subprocess
import sys
import time
import urllib.error
import urllib.request

PAUSA_CON_ESPECTADOR = 2     # s entre envíos si alguien mira el mapa
PAUSA_SIN_ESPECTADOR = 10    # s entre envíos si nadie lo mira
PAUSA_ERROR_MAX = 30         # s máximos de espera tras errores seguidos
MAX_AVIONES = 400            # el servidor descarta lo que pase de aquí


def log(*a):
    print(time.strftime('%Y-%m-%d %H:%M:%S'), *a, flush=True)


def cargar_config(ruta):
    c = configparser.ConfigParser()
    if not c.read(ruta):
        sys.exit(f'No encuentro el archivo de configuración: {ruta}\n'
                 f'Copia radar-subir.ini.ejemplo a radar-subir.ini y rellénalo.')
    s = c['radar']
    cfg = {
        'url': s.get('url', '').strip().rstrip('/'),
        'codigo': s.get('codigo', '').strip(),
        'aircraft': s.get('aircraft_json', 'http://localhost/skyaware/data/aircraft.json').strip(),
        'matriculas': s.get('matriculas', os.path.join(os.path.dirname(os.path.abspath(__file__)), 'matriculas.bin')).strip(),
        'estado': s.get('estado_url', '').strip(),
        'cookie': s.get('cookie_test', '').strip(),
        'nombre': s.get('nombre', 'Raspberry Pi').strip()[:40],
        'lat': s.getfloat('lat', 39.548),
        'lon': s.getfloat('lon', 2.733),
    }
    if not cfg['url'] or not cfg['codigo'].isdigit() or len(cfg['codigo']) != 6:
        sys.exit('Revisa radar-subir.ini: «url» no puede estar vacía y «codigo» son exactamente 6 cifras.')
    return cfg


# ---------- estado de la Raspberry ----------
_cpu_prev = None


def leer_cpu():
    """% de uso de CPU desde la lectura anterior (la primera vez devuelve None)."""
    global _cpu_prev
    try:
        with open('/proc/stat') as f:
            v = [int(x) for x in f.readline().split()[1:]]
        total, idle = sum(v), v[3] + (v[4] if len(v) > 4 else 0)
    except Exception:
        return None
    prev, _cpu_prev = _cpu_prev, (total, idle)
    if not prev or total <= prev[0]:
        return None
    return round(100.0 * (1 - (idle - prev[1]) / (total - prev[0])), 1)


def leer_ram():
    try:
        m = {}
        with open('/proc/meminfo') as f:
            for linea in f:
                k, v = linea.split(':')
                m[k] = int(v.split()[0])
        return round(100.0 * (1 - m['MemAvailable'] / m['MemTotal']), 1)
    except Exception:
        return None


def leer_temp():
    try:
        with open('/sys/class/thermal/thermal_zone0/temp') as f:
            return round(int(f.read().strip()) / 1000.0, 1)
    except Exception:
        return None


def num_texto(v):
    """'11.0%' / '47.2°C' / 47.2 → número (o None)."""
    if isinstance(v, str):
        v = v.replace('%', '').replace('°C', '').replace('C', '').strip()
    try:
        return round(float(v), 1)
    except (TypeError, ValueError):
        return None


def leer_estado_url(url):
    """CPU/RAM/temperatura desde un servicio de estado propio (p. ej. http://192.168.0.192:5050/status)."""
    try:
        req = urllib.request.Request(url, headers={'User-Agent': 'radar-subir'})
        with urllib.request.urlopen(req, timeout=3) as r:
            d = json.loads(r.read().decode('utf-8', 'replace'))
        return {'cpu': num_texto(d.get('cpu')), 'ram': num_texto(d.get('ram')), 'temp': num_texto(d.get('temp'))}
    except Exception:
        return None


# ---------- aircraft.json -> formato de SWAP-TURNOS ----------
def numero(v):
    return v if isinstance(v, (int, float)) and not isinstance(v, bool) else None


class Matriculas:
    """Matrículas por código ICAO (el mismo archivo matriculas.bin de la app de la tablet, formato MAT1):
    cabecera «MAT1» + nº de registros (uint32) + registros de 11 bytes ordenados (3 de código + 8 de matrícula)."""
    CAB, REG = 8, 11

    def __init__(self, ruta):
        with open(ruta, 'rb') as f:
            self.d = f.read()
        if self.d[:4] != b'MAT1' or len(self.d) < self.CAB:
            raise ValueError('no es una base de matrículas MAT1')
        self.n = struct.unpack_from('<I', self.d, 4)[0]
        if len(self.d) < self.CAB + self.n * self.REG:
            raise ValueError('base de matrículas incompleta')

    def buscar(self, icao):
        lo, hi = 0, self.n - 1
        while lo <= hi:
            mid = (lo + hi) // 2
            o = self.CAB + mid * self.REG
            k = int.from_bytes(self.d[o:o + 3], 'big')
            if k < icao:
                lo = mid + 1
            elif k > icao:
                hi = mid - 1
            else:
                return self.d[o + 3:o + self.REG].split(b'\0')[0].decode('ascii', 'replace') or None
        return None


def convertir(a, matriculas=None):
    """Un avión de dump1090/readsb → el formato de radar/subir (alt en ft, speed en kt, vr en ft/min, age en s)."""
    hexa = str(a.get('hex', '')).upper()
    lat, lon = numero(a.get('lat')), numero(a.get('lon'))
    if len(hexa) != 6 or lat is None or lon is None:   # sin posición no se puede dibujar (y «~xxxxxx» no es ICAO)
        return None
    alt = a.get('alt_baro', a.get('altitude'))
    suelo = alt == 'ground'
    vr = numero(a.get('baro_rate', a.get('geom_rate', a.get('vert_rate'))))
    seen = numero(a.get('seen_pos', a.get('seen')))
    return {
        'hex': hexa,
        'flight': (a.get('flight') or '').strip() or None,
        'reg': (a.get('r') or '').strip() or (matriculas.buscar(int(hexa, 16)) if matriculas and re.fullmatch(r'[0-9A-F]{6}', hexa) else None),
        'lat': lat, 'lon': lon,
        'alt': 0 if suelo else numero(alt),
        'speed': numero(a.get('gs', a.get('speed'))),
        'track': numero(a.get('track')),
        'vr': vr,
        'squawk': a.get('squawk') if isinstance(a.get('squawk'), str) else None,
        'gnd': True if suelo else False,
        'age': seen if seen is not None else 0,
        'msgs': numero(a.get('messages')) or 0,
    }


class Estado:
    """Recuerda cuándo llegó por última vez algún mensaje de avión (para «segundos sin recibir nada»)."""
    def __init__(self):
        self.msgs = None
        self.cambio = None

    def ultimo_s(self, data, aviones_raw):
        ahora = time.time()
        total = numero(data.get('messages'))
        if total is not None and total != self.msgs:
            if self.msgs is not None:
                self.cambio = ahora
            self.msgs = total
        seen = [numero(a.get('seen')) for a in aviones_raw]
        seen = [s for s in seen if s is not None]
        if seen:
            return int(min(seen))
        return None if self.cambio is None else int(ahora - self.cambio)


def construir(cfg, estado, matriculas=None):
    if cfg['aircraft'].startswith(('http://', 'https://')):
        req = urllib.request.Request(cfg['aircraft'], headers={'User-Agent': 'radar-subir'})
        with urllib.request.urlopen(req, timeout=5) as r:
            data = json.loads(r.read().decode('utf-8', 'replace'))
    else:   # también vale la ruta de un archivo, p. ej. /run/dump1090-fa/aircraft.json
        with open(cfg['aircraft']) as f:
            data = json.load(f)
    raw = data.get('aircraft') or []
    aviones = [x for x in (convertir(a, matriculas) for a in raw) if x][:MAX_AVIONES]
    sistema = leer_estado_url(cfg['estado']) if cfg['estado'] else None
    if sistema is None:   # sin servicio de estado (o no contesta): se mide en esta misma Raspberry
        sistema = {'cpu': leer_cpu(), 'ram': leer_ram(), 'temp': leer_temp()}
    return {
        'estado': 'adsb',
        'estacion': {'nombre': cfg['nombre'], 'lat': cfg['lat'], 'lon': cfg['lon'],
                     'mensajes': int(numero(data.get('messages')) or 0),
                     'ultimo_s': estado.ultimo_s(data, raw)},
        'sistema': {k: v for k, v in sistema.items() if v is not None},
        'aviones': aviones,
    }


# ---------- envío firmado ----------
class Reloj:
    """Corrige el reloj con la hora que devuelve el servidor y garantiza horas siempre crecientes."""
    def __init__(self):
        self.desfase = 0
        self.ultimo = 0

    def hora(self):
        t = int(time.time()) + self.desfase
        self.ultimo = max(t, self.ultimo + 1)
        return self.ultimo

    def ajustar(self, hora_servidor):
        if isinstance(hora_servidor, int):
            self.desfase = hora_servidor - int(time.time())


def resolver_reto(texto):
    """El hosting gratuito (Byethost) responde a los programas con una página «aes.js» que un navegador resuelve solo:
    descifra un valor con AES-128-CBC y lo guarda en la cookie __test. Aquí se hace lo mismo con el comando openssl
    (que ya trae la Raspberry). Devuelve el valor de la cookie, o None si la página no tiene el formato esperado."""
    v = re.findall(r'toNumbers\("([0-9a-fA-F]+)"\)', texto)
    if len(v) < 3:
        return None
    clave, iv, cifrado = v[:3]
    try:
        r = subprocess.run(['openssl', 'enc', '-d', '-aes-128-cbc', '-nopad', '-K', clave, '-iv', iv],
                           input=bytes.fromhex(cifrado), capture_output=True, timeout=10)
    except (OSError, ValueError, subprocess.SubprocessError):
        return None
    return r.stdout.hex() if r.returncode == 0 and r.stdout else None


def enviar(cfg, payload, reloj):
    cuerpo = json.dumps(payload, separators=(',', ':'), ensure_ascii=False).encode('utf-8')
    ts = reloj.hora()
    firma = hmac.new(cfg['codigo'].encode(), f'{ts}.'.encode() + cuerpo, hashlib.sha256).hexdigest()
    for intento in (1, 2):
        cabeceras = {'Content-Type': 'application/json', 'X-Requested-With': 'swap-turnos',
                     'X-Radar-Time': str(ts), 'X-Radar-Sig': firma, 'User-Agent': 'radar-subir'}
        ruta = '/api/index.php?r=radar/subir'
        if cfg['cookie']:   # cookie __test que exige el hosting gratuito (puesta a mano o resuelta sola)
            cabeceras['Cookie'] = '__test=' + cfg['cookie']
            ruta += '&i=1'
        req = urllib.request.Request(cfg['url'] + ruta, data=cuerpo, method='POST', headers=cabeceras)
        try:
            with urllib.request.urlopen(req, timeout=10) as r:
                texto, codigo = r.read().decode('utf-8', 'replace'), r.status
        except urllib.error.HTTPError as e:
            texto, codigo = e.read().decode('utf-8', 'replace'), e.code
        try:
            j = json.loads(texto)
            break
        except ValueError:
            if 'aes.js' in texto and intento == 1:
                nueva = resolver_reto(texto)
                if nueva and nueva != cfg['cookie']:
                    cfg['cookie'] = nueva
                    log('Resuelta la comprobación anti-bots del hosting')
                    continue   # se reenvía lo mismo (la primera vez no llegó al servidor)
            raise RuntimeError(f'El servidor no contestó JSON (HTTP {codigo}): {texto[:700]!r}')
    reloj.ajustar(j.get('hora', j.get('hora_servidor')))
    if codigo != 200:
        raise RuntimeError(f'HTTP {codigo}: {j.get("error", j)}')
    return j


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument('-c', '--config', default=os.path.join(os.path.dirname(os.path.abspath(__file__)), 'radar-subir.ini'))
    ap.add_argument('--probar', action='store_true', help='un solo envío y salir')
    args = ap.parse_args()
    cfg = cargar_config(args.config)
    estado, reloj = Estado(), Reloj()
    matriculas = None
    if os.path.exists(cfg['matriculas']):
        try:
            matriculas = Matriculas(cfg['matriculas'])
            log(f'Base de matrículas: {matriculas.n} aviones')
        except Exception as e:
            log(f'No se pudo leer la base de matrículas ({e}); se enviará sin matrículas')
    else:
        log('Sin base de matrículas (matriculas.bin): los aviones saldrán sin matrícula. Mira el README.')
    leer_cpu()   # primera lectura: fija la referencia para el % de CPU
    if args.probar:
        time.sleep(1)   # para que el envío de prueba ya lleve la CPU
    fallos, pausa = 0, PAUSA_CON_ESPECTADOR
    log(f'Enviando a {cfg["url"]} desde {cfg["aircraft"]}')
    while True:
        try:
            payload = construir(cfg, estado, matriculas)
            if args.probar:
                log(f'Aviones con posición: {len(payload["aviones"])} · sistema: {payload["sistema"]}')
                for a in payload['aviones'][:5]:
                    log('  ', a)
            r = enviar(cfg, payload, reloj)
            if fallos:
                log('Conexión recuperada')
            fallos = 0
            pausa = PAUSA_CON_ESPECTADOR if r.get('ver') else PAUSA_SIN_ESPECTADOR
            if args.probar:
                log('Respuesta del servidor:', r)
                log('OK: si ves aviones en el mapa, todo funciona.')
                return
        except Exception as e:
            fallos += 1
            if fallos == 1 or fallos % 10 == 0:   # no llenar el registro si el fallo dura
                log(f'Error ({fallos}): {e}')
            if args.probar:
                sys.exit(1)
            pausa = min(PAUSA_ERROR_MAX, PAUSA_SIN_ESPECTADOR + fallos)
        time.sleep(pausa)


if __name__ == '__main__':
    main()
