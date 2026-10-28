package main

import (
	"context"
	"encoding/base32"
	"encoding/base64"
	"errors"
	"flag"
	"fmt"
	"log"
	"net/http"
	"net/url"
	"os"
	"os/signal"
	"path/filepath"
	"syscall"
	"time"
)

var Version = "dev"

const usage = `simvault — зашифрованное хранилище «Учёт SIM»

  simvault init   -data DIR [-listen 127.0.0.1:8743]  создать конфигурацию, секрет TOTP и токен настройки
  simvault serve  -data DIR                          запустить сервер
  simvault setup-token -data DIR                     новый токен первичной настройки (пока хранилище не создано)
  simvault reset-totp  -data DIR                     новый секрет второго фактора
  simvault revoke-devices -data DIR                  отозвать все доверенные устройства
  simvault version
`

func main() {
	if len(os.Args) < 2 {
		fmt.Print(usage)
		os.Exit(2)
	}
	cmd := os.Args[1]
	fs := flag.NewFlagSet(cmd, flag.ExitOnError)
	data := fs.String("data", "/var/lib/simvault", "папка данных")
	listen := fs.String("listen", "127.0.0.1:8743", "адрес (только localhost — наружу через Caddy)")
	fs.Parse(os.Args[2:])
	var err error
	switch cmd {
	case "init":
		err = cmdInit(*data, *listen)
	case "serve":
		err = cmdServe(*data)
	case "setup-token":
		err = cmdSetupToken(*data)
	case "reset-totp":
		err = cmdResetTOTP(*data)
	case "revoke-devices":
		err = writeJSON(filepath.Join(*data, "devices.json"), []Device{})
		if err == nil {
			fmt.Println("Все доверенные устройства отозваны. Перезапустите службу: systemctl restart simvault")
		}
	case "version":
		fmt.Println(Version)
	default:
		fmt.Print(usage)
		os.Exit(2)
	}
	if err != nil {
		fmt.Fprintln(os.Stderr, "Ошибка:", err)
		os.Exit(1)
	}
}

func newTOTPSecret() string {
	return base32.StdEncoding.WithPadding(base32.NoPadding).EncodeToString(randomBytes(20))
}

func printTOTP(secret string) {
	label := url.PathEscape("Учёт SIM:владелец")
	uri := fmt.Sprintf("otpauth://totp/%s?secret=%s&issuer=%s&algorithm=SHA1&digits=6&period=30", label, secret, url.QueryEscape("Учёт SIM"))
	fmt.Println("Второй фактор (TOTP). Добавьте ключ в приложение-аутентификатор (Яндекс Ключ, Google Authenticator, Aegis):")
	fmt.Println("  Ключ:", secret)
	fmt.Println("  Ссылка:", uri)
}

func cmdInit(dir, listen string) error {
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return err
	}
	path := filepath.Join(dir, "config.json")
	if _, err := os.Stat(path); err == nil {
		return errors.New("конфигурация уже есть: " + path)
	}
	setup := randomToken()
	cfg := Config{
		Listen: listen, Pepper: base64.StdEncoding.EncodeToString(randomBytes(32)), TOTPSecret: newTOTPSecret(),
		SetupTokenHash: sha256hex(setup), TrustedProxy: true, SessionIdleMin: 30, SessionMaxHours: 12, DeviceDays: 90,
	}
	if err := writeJSON(path, cfg); err != nil {
		return err
	}
	fmt.Println("Конфигурация создана:", path)
	printTOTP(cfg.TOTPSecret)
	fmt.Println()
	fmt.Println("Токен первичной настройки (одноразовый, введите его в веб-версии при создании хранилища):")
	fmt.Println(" ", setup)
	return nil
}

func cmdSetupToken(dir string) error {
	cfg, err := loadConfig(dir)
	if err != nil {
		return err
	}
	st, err := OpenStore(dir)
	if err != nil {
		return err
	}
	if v, _ := st.Vault(); v != nil {
		return errors.New("хранилище уже создано — новый токен настройки не нужен")
	}
	tok := randomToken()
	cfg.SetupTokenHash = sha256hex(tok)
	if err := writeJSON(filepath.Join(dir, "config.json"), cfg); err != nil {
		return err
	}
	fmt.Println("Новый токен первичной настройки:", tok)
	fmt.Println("Перезапустите службу: systemctl restart simvault")
	return nil
}

func cmdResetTOTP(dir string) error {
	cfg, err := loadConfig(dir)
	if err != nil {
		return err
	}
	cfg.TOTPSecret = newTOTPSecret()
	if err := writeJSON(filepath.Join(dir, "config.json"), cfg); err != nil {
		return err
	}
	printTOTP(cfg.TOTPSecret)
	fmt.Println("Перезапустите службу: systemctl restart simvault")
	return nil
}

func cmdServe(dir string) error {
	cfg, err := loadConfig(dir)
	if err != nil {
		return fmt.Errorf("нет конфигурации (сначала simvault init): %w", err)
	}
	srv, err := NewServer(cfg, dir, nil)
	if err != nil {
		return err
	}
	if v, _ := srv.store.Vault(); v != nil && cfg.SetupTokenHash != "" {
		// Хранилище создано — токен настройки больше не нужен.
		cfg.SetupTokenHash = ""
		_ = writeJSON(filepath.Join(dir, "config.json"), cfg)
	}
	hs := &http.Server{
		Addr: cfg.Listen, Handler: srv.Handler(),
		ReadHeaderTimeout: 10 * time.Second, ReadTimeout: 60 * time.Second, WriteTimeout: 120 * time.Second,
		IdleTimeout: 60 * time.Second, MaxHeaderBytes: 16 << 10,
	}
	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer stop()
	go func() {
		<-ctx.Done()
		sh, cancel := context.WithTimeout(context.Background(), 10*time.Second)
		defer cancel()
		hs.Shutdown(sh)
	}()
	log.Printf("simvault %s слушает %s", Version, cfg.Listen)
	srv.audit.Log("start", "", "", "сервер запущен "+Version, true)
	if err := hs.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
		return err
	}
	return nil
}
