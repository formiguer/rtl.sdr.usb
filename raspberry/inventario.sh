#!/bin/sh
# Guarda en un solo archivo (~/inventario-raspi.txt) todo lo que hay que saber para reconstruir esta Raspberry.
# NO guarda contraseñas ni el código del radar (se censuran). Uso:  sh inventario.sh
# Guárdalo FUERA de la Raspberry (cópialo a tu ordenador): scp pi@192.168.0.192:inventario-raspi.txt .
OUT="$HOME/inventario-raspi.txt"
sec() { printf '\n===== %s =====\n' "$1"; }
{
  echo "Inventario de $(hostname) · $(date)"
  sec "Sistema"; cat /etc/os-release; uname -a; cat /proc/device-tree/model 2>/dev/null; echo
  sec "Disco"; df -h /; lsblk
  sec "Red (sin contraseñas)"; ip -4 addr show | grep -E 'inet |^[0-9]+:'; ip route | head -3
  sec "Servicios activados"; systemctl list-unit-files --state=enabled --no-pager
  sec "Servicios propios (/etc/systemd/system)"; ls -l /etc/systemd/system/*.service 2>/dev/null
  for f in /etc/systemd/system/*.service; do
    [ -f "$f" ] || continue
    sec "$f"; sed -E 's/((password|secret|token|key|codigo)[^=]*=).*/\1***censurado***/I' "$f"
  done
  sec "Servicio de estado (puerto 5050)"; (ss -ltnp 2>/dev/null || netstat -ltnp 2>/dev/null) | grep -E ':5050|State|Proto'
  sec "Procesos Python / Flask"; ps aux | grep -E 'python|flask|gunicorn' | grep -v grep
  sec "PiAware"; piaware-config -show 2>/dev/null | sed -E 's/((password|secret|token)[^ ]* ).*/\1***censurado***/I'
  sed -E 's/((password|secret|token)[^=]*=).*/\1***censurado***/I' /etc/piaware.conf 2>/dev/null
  sec "dump1090-fa"; cat /etc/default/dump1090-fa 2>/dev/null
  sec "Dongles USB"; lsusb
  sec "Cron del usuario"; crontab -l 2>/dev/null
  sec "Archivos en la carpeta personal (sin .ini)"; ls -laR "$HOME" 2>/dev/null | grep -v -E '\.ini$|\.cache|\.local' | head -80
  sec "Paquetes instalados a mano"; apt-mark showmanual
  sec "fstab"; cat /etc/fstab
} > "$OUT" 2>&1
echo "Hecho: $OUT ($(wc -l < "$OUT") líneas). Cópialo a tu ordenador:  scp pi@$(hostname -I | awk '{print $1}'):inventario-raspi.txt ."
