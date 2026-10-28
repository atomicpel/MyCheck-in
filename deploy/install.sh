#!/bin/bash
# Установка и обновление сервера «Учёт SIM» (simvault) на VPS с Caddy и systemd.
#
#   sudo bash install.sh                      # домен по умолчанию
#   sudo DOMAIN=sim.example.ru bash install.sh
#
# Повторный запуск обновляет программу, данные и настройки не трогает.
set -euo pipefail

REPO=atomicpel/MyCheck-in
RELEASE=${RELEASE:-__RELEASE__}
DOMAIN=${DOMAIN:-sim.atomicpel.cloud-ip.cc}
PREFIX=/opt/simvault
DATA=/var/lib/simvault
CADDYFILE=${CADDYFILE:-/etc/caddy/Caddyfile}

say()  { printf '\033[1;31m▸\033[0m %s\n' "$*"; }
ok()   { printf '\033[1;32m✓\033[0m %s\n' "$*"; }
die()  { printf '\033[1;31m✗ %s\033[0m\n' "$*" >&2; exit 1; }

[ "$(id -u)" = 0 ] || die "Запустите от root: sudo bash install.sh"
case "$RELEASE" in server-[0-9]*) ;; *) die "Укажите выпуск: RELEASE=server-N bash install.sh (или скачайте install.sh со страницы выпуска)" ;; esac
[[ "$DOMAIN" =~ ^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+$ ]] || die "Неверное доменное имя: $DOMAIN"
command -v systemctl >/dev/null || die "Нужен systemd"
command -v curl >/dev/null || die "Нужен curl"
command -v caddy >/dev/null || die "Caddy не найден. Скрипт рассчитан на Caddy, установленный как системная служба"
[ -f "$CADDYFILE" ] || die "Не найден $CADDYFILE (укажите CADDYFILE=/путь/к/Caddyfile)"

case "$(uname -m)" in
  x86_64|amd64) ARCH=amd64 ;;
  aarch64|arm64) ARCH=arm64 ;;
  *) die "Неподдерживаемая архитектура: $(uname -m)" ;;
esac

# ---- 1. Скачивание и проверка ----
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
BASE="https://github.com/$REPO/releases/download/$RELEASE"
say "Скачиваю $RELEASE ($ARCH)…"
for f in "simvault-linux-$ARCH" SHA256SUMS simvault.service Caddyfile.snippet backup.sh; do
  curl -fsSL --proto '=https' --tlsv1.2 -o "$TMP/$f" "$BASE/$f" || die "Не удалось скачать $f"
done
( cd "$TMP" && grep -E " (simvault-linux-$ARCH|simvault.service|Caddyfile.snippet|backup.sh)\$" SHA256SUMS | sha256sum -c --quiet - ) \
  || die "Контрольная сумма не совпала — файлы скачались с ошибкой. Установка остановлена"
ok "Контрольные суммы сходятся"

# ---- 2. Пользователь и папки ----
if ! id simvault >/dev/null 2>&1; then
  useradd --system --home-dir "$DATA" --no-create-home --shell /usr/sbin/nologin simvault
  ok "Создан системный пользователь simvault (без входа в систему)"
fi
install -d -m 755 -o root -g root "$PREFIX"
install -d -m 700 -o simvault -g simvault "$DATA"

WAS_RUNNING=0
systemctl is-active --quiet simvault 2>/dev/null && WAS_RUNNING=1
install -m 755 -o root -g root "$TMP/simvault-linux-$ARCH" "$PREFIX/simvault.new"
mv -f "$PREFIX/simvault.new" "$PREFIX/simvault"
install -m 755 -o root -g root "$TMP/backup.sh" "$PREFIX/backup.sh"
ok "Программа: $PREFIX/simvault ($("$PREFIX/simvault" version))"

# ---- 3. Первичная настройка ----
FIRST=0
if [ ! -f "$DATA/config.json" ]; then
  FIRST=1
  PORT=8743
  # Порт занят, если на нём кто-то отвечает (другие сайты на этом сервере не трогаем).
  while (exec 3<>"/dev/tcp/127.0.0.1/$PORT") 2>/dev/null; do
    PORT=$((PORT + 1))
    [ "$PORT" -lt 8800 ] || die "Не нашёл свободный порт 8743–8799"
  done
  INIT_OUT=$(runuser -u simvault -- "$PREFIX/simvault" init -data "$DATA" -listen "127.0.0.1:$PORT")
else
  PORT=$(grep -o '"listen": *"[^"]*"' "$DATA/config.json" | grep -o '[0-9]*"$' | tr -d '"')
  [ -n "$PORT" ] || PORT=8743
fi
chown -R simvault:simvault "$DATA"
chmod 700 "$DATA"

# ---- 4. Служба systemd ----
install -m 644 "$TMP/simvault.service" /etc/systemd/system/simvault.service
cat > /etc/systemd/system/simvault-backup.service <<EOF
[Unit]
Description=Резервная копия «Учёт SIM»
[Service]
Type=oneshot
ExecStart=$PREFIX/backup.sh
EOF
cat > /etc/systemd/system/simvault-backup.timer <<'EOF'
[Unit]
Description=Ежедневная резервная копия «Учёт SIM»
[Timer]
OnCalendar=*-*-* 03:20:00
RandomizedDelaySec=20m
Persistent=true
[Install]
WantedBy=timers.target
EOF
systemctl daemon-reload
systemctl enable --now simvault-backup.timer >/dev/null
if [ "$WAS_RUNNING" = 1 ]; then systemctl restart simvault; else systemctl enable --now simvault >/dev/null; fi
sleep 1
systemctl is-active --quiet simvault || { journalctl -u simvault -n 30 --no-pager; die "Служба не запустилась"; }
curl -fsS "http://127.0.0.1:$PORT/api/v1/status" >/dev/null || die "Сервер не отвечает на 127.0.0.1:$PORT"
ok "Служба simvault работает на 127.0.0.1:$PORT (наружу не открыта)"

# ---- 5. Caddy ----
# Сайт уже есть, если домен стоит в начале строки адреса сайта (не в комментарии и не частью другого имени).
if grep -qE "^[[:space:]]*(https?://)?${DOMAIN//./\\.}(:[0-9]+)?[[:space:]]*(,|\{|$)" "$CADDYFILE"; then
  ok "Caddy: сайт $DOMAIN уже настроен — не трогаю"
else
  BK="$CADDYFILE.bak-simvault-$(date +%Y%m%d%H%M%S)"
  cp -p "$CADDYFILE" "$BK"
  { echo; sed -e "s/__DOMAIN__/$DOMAIN/" -e "s/__PORT__/$PORT/" "$TMP/Caddyfile.snippet"; } >> "$CADDYFILE"
  if caddy validate --config "$CADDYFILE" --adapter caddyfile >/dev/null 2>&1; then
    systemctl reload caddy
    ok "Caddy: добавлен сайт https://$DOMAIN (копия старого файла: $BK)"
  else
    cp -p "$BK" "$CADDYFILE"
    die "Caddy не принял новую конфигурацию — вернул старую. Проверьте: caddy validate --config $CADDYFILE"
  fi
fi

echo
ok "Готово: https://$DOMAIN"
if [ "$FIRST" = 1 ]; then
  cat <<EOF

────────────────────────────────────────────────────────────────────
 ПЕРВЫЙ ЗАПУСК. Эти данные показываются один раз и нигде не хранятся
 в открытом виде (кроме секрета TOTP в $DATA/config.json, доступного
 только службе). Не делайте скриншот в облако и не пересылайте их.
────────────────────────────────────────────────────────────────────
$INIT_OUT

Дальше:
 1. Добавьте ключ TOTP в приложение-аутентификатор на телефоне.
 2. Откройте https://$DOMAIN, введите токен настройки и придумайте
    мастер-пароль (от 12 символов). Его не знает никто, включая сервер:
    если забыть — данные не восстановить.
EOF
  # Вывод init больше не нужен в памяти оболочки.
  unset INIT_OUT
fi
