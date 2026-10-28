#!/bin/sh
# Ежедневная копия хранилища «Учёт SIM». Записи и фото в копии зашифрованы мастер-паролем,
# но config.json содержит секрет второго фактора — поэтому копии доступны только root (0600).
set -eu
SRC=/var/lib/simvault
DST=/var/backups/simvault
KEEP=${KEEP:-14}
umask 077
mkdir -p "$DST"
chmod 700 "$DST"
f="$DST/simvault-$(date +%Y-%m-%d_%H%M%S).tar.gz"
tar -C "$SRC" -czf "$f.tmp" .
mv "$f.tmp" "$f"
# Оставляем последние $KEEP копий.
ls -1t "$DST"/simvault-*.tar.gz 2>/dev/null | tail -n +"$((KEEP + 1))" | while read -r old; do rm -f -- "$old"; done
echo "Копия: $f"
