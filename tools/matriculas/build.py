#!/usr/bin/env python3
"""Genera app/src/main/assets/matriculas.bin: código ICAO de 24 bits -> matrícula, para ver la matrícula de cada avión sin conexión.

Origen: base de datos de aviones de Mictronics (https://www.mictronics.de/aircraft-database/) en la copia que mantiene
wiedehopf/tar1090-db. Solo se usa el par «código ICAO» + «matrícula» (no se guarda ningún dato de propietarios).

Uso:
  curl -L -o aircraft.csv.gz https://raw.githubusercontent.com/wiedehopf/tar1090-db/csv/aircraft.csv.gz
  python3 build.py aircraft.csv.gz ../../app/src/main/assets/matriculas.bin

Formato (MAT1): "MAT1" + nº de registros (uint32 little-endian) + registros de 11 bytes ordenados por código:
  3 bytes con el código ICAO (big-endian) + 8 bytes con la matrícula en ASCII, rellena con ceros.
"""
import gzip, re, struct, sys

RE_REG = re.compile(r'^[A-Z0-9-]{1,8}$')

def main(origen, destino):
    registros = {}
    descartadas = 0
    with gzip.open(origen, 'rt', encoding='utf-8', errors='replace') as f:
        for linea in f:
            c = linea.rstrip('\n').split(';')
            if len(c) < 2 or not re.fullmatch(r'[0-9A-Fa-f]{6}', c[0]):
                continue
            reg = c[1].strip().upper()
            if not reg:
                continue
            if not RE_REG.match(reg):
                descartadas += 1   # matrículas con caracteres raros o de más de 8 (identificadores militares, etc.)
                continue
            registros[int(c[0], 16)] = reg
    claves = sorted(registros)
    with open(destino, 'wb') as out:
        out.write(b'MAT1' + struct.pack('<I', len(claves)))
        for k in claves:
            out.write(k.to_bytes(3, 'big') + registros[k].encode('ascii').ljust(8, b'\0'))
    print(f'{len(claves)} matrículas ({descartadas} descartadas por formato), {8 + 11 * len(claves)} bytes')

if __name__ == '__main__':
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    main(sys.argv[1], sys.argv[2])
