#!/bin/sh
# Instala radar-subir como servicio (arranca solo con la Raspberry). Uso:  sudo sh instalar.sh
# Solo crea /etc/systemd/system/radar-subir.service. No toca PiAware ni dump1090.
set -e
[ "$(id -u)" = 0 ] || { echo "Ejecútalo con sudo:  sudo sh instalar.sh"; exit 1; }
DIR="$(cd "$(dirname "$0")" && pwd)"
USUARIO="${SUDO_USER:-$(stat -c %U "$DIR")}"
[ -f "$DIR/radar-subir.ini" ] || { echo "Falta $DIR/radar-subir.ini (copia radar-subir.ini.ejemplo y rellénalo)"; exit 1; }
cat > /etc/systemd/system/radar-subir.service <<UNIT
[Unit]
Description=Envio de aviones al radar de SWAP-TURNOS
After=network-online.target
Wants=network-online.target

[Service]
User=$USUARIO
ExecStart=/usr/bin/python3 $DIR/radar-subir.py
Restart=always
RestartSec=10
Nice=10

[Install]
WantedBy=multi-user.target
UNIT
systemctl daemon-reload
systemctl enable --now radar-subir
echo "Hecho. Ver estado:  systemctl status radar-subir   ·   registro:  journalctl -u radar-subir -f"
