package main

import (
	"bytes"
	"crypto/rand"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

type clock struct{ t time.Time }

func (c *clock) now() time.Time { return c.t }

type env struct {
	t     *testing.T
	srv   *Server
	h     http.Handler
	clk   *clock
	dir   string
	setup string
	auth  []byte
}

func newEnv(t *testing.T) *env {
	dir := t.TempDir()
	setup := "setup-token-123"
	cfg := &Config{Listen: "127.0.0.1:0", Pepper: base64.StdEncoding.EncodeToString(randomBytes(32)), TOTPSecret: newTOTPSecret(),
		SetupTokenHash: sha256hex(setup), SessionIdleMin: 30, SessionMaxHours: 12, DeviceDays: 90}
	clk := &clock{t: time.Date(2026, 10, 28, 12, 0, 0, 0, time.UTC)}
	srv, err := NewServer(cfg, dir, clk.now)
	if err != nil {
		t.Fatal(err)
	}
	auth := make([]byte, 32)
	rand.Read(auth)
	return &env{t: t, srv: srv, h: srv.Handler(), clk: clk, dir: dir, setup: setup, auth: auth}
}

func (e *env) do(method, path, token string, body any) *httptest.ResponseRecorder {
	var rd *bytes.Reader
	if b, ok := body.([]byte); ok {
		rd = bytes.NewReader(b)
	} else if body != nil {
		b, _ := json.Marshal(body)
		rd = bytes.NewReader(b)
	} else {
		rd = bytes.NewReader(nil)
	}
	req := httptest.NewRequest(method, path, rd)
	if body != nil {
		if _, raw := body.([]byte); !raw {
			req.Header.Set("Content-Type", "application/json")
		}
	}
	if token != "" {
		req.Header.Set("Authorization", "Bearer "+token)
	}
	w := httptest.NewRecorder()
	e.h.ServeHTTP(w, req)
	return w
}

func (e *env) code() string {
	s, _ := decodeTOTPSecret(e.srv.cfg.TOTPSecret)
	return totpCode(s, uint64(e.clk.t.Unix()/30))
}

func b64(b []byte) string { return base64.StdEncoding.EncodeToString(b) }

func (e *env) initVault() {
	kdf := KDF{Alg: "PBKDF2-SHA256", Iter: 600000, Salt: b64(randomBytes(16))}
	w := e.do("POST", "/api/v1/setup", "", map[string]any{"setupToken": e.setup, "kdf": kdf, "authKey": b64(e.auth), "wrappedKey": b64(randomBytes(60))})
	if w.Code != 204 {
		e.t.Fatalf("setup: %d %s", w.Code, w.Body)
	}
}

func (e *env) login(trust bool) map[string]any {
	e.clk.t = e.clk.t.Add(31 * time.Second) // новый код TOTP (каждый код — один раз)
	w := e.do("POST", "/api/v1/login", "", map[string]any{"authKey": b64(e.auth), "totp": e.code(), "trustDevice": trust, "deviceName": "realme"})
	if w.Code != 200 {
		e.t.Fatalf("login: %d %s", w.Code, w.Body)
	}
	var m map[string]any
	json.Unmarshal(w.Body.Bytes(), &m)
	return m
}

func TestSetupOnceAndLogin(t *testing.T) {
	e := newEnv(t)
	if w := e.do("GET", "/api/v1/status", "", nil); !strings.Contains(w.Body.String(), `"initialized":false`) {
		t.Fatal(w.Body)
	}
	// Неверный токен настройки
	w := e.do("POST", "/api/v1/setup", "", map[string]any{"setupToken": "x", "kdf": KDF{"PBKDF2-SHA256", 600000, b64(randomBytes(16))}, "authKey": b64(e.auth), "wrappedKey": b64(randomBytes(60))})
	if w.Code != 403 {
		t.Fatal(w.Code)
	}
	// Слабый KDF отвергается
	w = e.do("POST", "/api/v1/setup", "", map[string]any{"setupToken": e.setup, "kdf": KDF{"PBKDF2-SHA256", 1000, b64(randomBytes(16))}, "authKey": b64(e.auth), "wrappedKey": b64(randomBytes(60))})
	if w.Code != 400 {
		t.Fatal("слабый KDF принят", w.Code)
	}
	e.initVault()
	// Повторная настройка невозможна
	w = e.do("POST", "/api/v1/setup", "", map[string]any{"setupToken": e.setup, "kdf": KDF{"PBKDF2-SHA256", 600000, b64(randomBytes(16))}, "authKey": b64(e.auth), "wrappedKey": b64(randomBytes(60))})
	if w.Code != 409 {
		t.Fatal("повторная настройка", w.Code)
	}
	m := e.login(false)
	if m["token"] == "" || m["wrappedKey"] == "" {
		t.Fatal(m)
	}
	// Хранилище на диске не содержит authKey
	b, _ := os.ReadFile(filepath.Join(e.dir, "vault.json"))
	if bytes.Contains(b, []byte(b64(e.auth))) {
		t.Fatal("authKey хранится в открытом виде")
	}
}

func TestLoginRequiresTOTPAndBlocksBruteForce(t *testing.T) {
	e := newEnv(t)
	e.initVault()
	// Верный пароль без кода — нельзя
	if w := e.do("POST", "/api/v1/login", "", map[string]any{"authKey": b64(e.auth), "totp": "000000"}); w.Code != 401 {
		t.Fatal(w.Code)
	}
	for i := 0; i < 4; i++ {
		e.do("POST", "/api/v1/login", "", map[string]any{"authKey": b64(randomBytes(32)), "totp": e.code()})
	}
	// Пятая ошибка уже была — теперь даже верные данные блокируются
	w := e.do("POST", "/api/v1/login", "", map[string]any{"authKey": b64(e.auth), "totp": e.code()})
	if w.Code != 429 {
		t.Fatal("нет блокировки после 5 ошибок", w.Code)
	}
	e.clk.t = e.clk.t.Add(16 * time.Minute)
	e.login(false)
}

func TestTOTPCodeIsSingleUse(t *testing.T) {
	e := newEnv(t)
	e.initVault()
	e.clk.t = e.clk.t.Add(31 * time.Second)
	code := e.code()
	if w := e.do("POST", "/api/v1/login", "", map[string]any{"authKey": b64(e.auth), "totp": code}); w.Code != 200 {
		t.Fatal(w.Code)
	}
	if w := e.do("POST", "/api/v1/login", "", map[string]any{"authKey": b64(e.auth), "totp": code}); w.Code != 401 {
		t.Fatal("код использован повторно", w.Code)
	}
}

func TestRecordsSyncConflictAndTombstone(t *testing.T) {
	e := newEnv(t)
	e.initVault()
	tok := e.login(false)["token"].(string)
	if w := e.do("GET", "/api/v1/records", "", nil); w.Code != 401 {
		t.Fatal("записи без входа", w.Code)
	}
	data := b64(randomBytes(100))
	w := e.do("PUT", "/api/v1/records/aaaaaaaa-1111", tok, map[string]any{"baseRev": 0, "data": data})
	if w.Code != 200 {
		t.Fatal(w.Code, w.Body)
	}
	// Старая ревизия — конфликт с текущей записью в ответе
	w = e.do("PUT", "/api/v1/records/aaaaaaaa-1111", tok, map[string]any{"baseRev": 0, "data": data})
	if w.Code != 409 || !strings.Contains(w.Body.String(), `"rev":1`) {
		t.Fatal("нет конфликта", w.Code, w.Body)
	}
	// Незашифрованные данные не принимаются
	if w := e.do("PUT", "/api/v1/records/bbbbbbbb-2222", tok, map[string]any{"baseRev": 0, "data": "dGVzdA=="}); w.Code != 400 {
		t.Fatal(w.Code)
	}
	// Опасный идентификатор
	if w := e.do("PUT", "/api/v1/records/..%2F..%2Fetc", tok, map[string]any{"baseRev": 0, "data": data}); w.Code == 200 {
		t.Fatal("принят путь с ..")
	}
	if w := e.do("DELETE", "/api/v1/records/aaaaaaaa-1111?baseRev=1", tok, nil); w.Code != 200 {
		t.Fatal(w.Code)
	}
	var list struct {
		Rev     int64    `json:"rev"`
		Records []Record `json:"records"`
	}
	json.Unmarshal(e.do("GET", "/api/v1/records?since=1", tok, nil).Body.Bytes(), &list)
	if list.Rev != 2 || len(list.Records) != 1 || !list.Records[0].Deleted || list.Records[0].Data != "" {
		t.Fatal("нет надгробия", list)
	}
	// Перезапуск: всё читается с диска
	srv2, err := NewServer(e.srv.cfg, e.dir, e.clk.now)
	if err != nil {
		t.Fatal(err)
	}
	if rev, _ := srv2.store.Changes(0); rev != 2 {
		t.Fatal("ревизия не восстановилась", rev)
	}
}

func TestBlobsAndLimits(t *testing.T) {
	e := newEnv(t)
	e.initVault()
	tok := e.login(false)["token"].(string)
	blob := randomBytes(1000)
	if w := e.do("PUT", "/api/v1/blobs/photo-12345678", tok, blob); w.Code != 204 {
		t.Fatal(w.Code, w.Body)
	}
	if w := e.do("GET", "/api/v1/blobs/photo-12345678", tok, nil); w.Code != 200 || !bytes.Equal(w.Body.Bytes(), blob) {
		t.Fatal("фото не совпало")
	}
	if w := e.do("PUT", "/api/v1/blobs/photo-big00001", tok, randomBytes(MaxBlobBytes+1)); w.Code != 413 {
		t.Fatal("слишком большое фото принято", w.Code)
	}
}

func TestSessionIdleAndDeviceToken(t *testing.T) {
	e := newEnv(t)
	e.initVault()
	m := e.login(true)
	tok, devTok := m["token"].(string), m["deviceToken"].(string)
	e.clk.t = e.clk.t.Add(31 * time.Minute)
	if w := e.do("GET", "/api/v1/records", tok, nil); w.Code != 401 {
		t.Fatal("сессия не истекла", w.Code)
	}
	w := e.do("POST", "/api/v1/device-login", "", map[string]any{"deviceToken": devTok})
	if w.Code != 200 {
		t.Fatal(w.Code, w.Body)
	}
	var d map[string]any
	json.Unmarshal(w.Body.Bytes(), &d)
	tok2 := d["token"].(string)
	// Отзыв устройства закрывает его сессии и токен
	var devs struct {
		Devices []struct{ ID string } `json:"devices"`
	}
	json.Unmarshal(e.do("GET", "/api/v1/devices", tok2, nil).Body.Bytes(), &devs)
	if w := e.do("DELETE", "/api/v1/devices/"+devs.Devices[0].ID, tok2, nil); w.Code != 204 {
		t.Fatal(w.Code)
	}
	if w := e.do("GET", "/api/v1/records", tok2, nil); w.Code != 401 {
		t.Fatal("сессия отозванного устройства жива")
	}
	if w := e.do("POST", "/api/v1/device-login", "", map[string]any{"deviceToken": devTok}); w.Code != 401 {
		t.Fatal("токен отозванного устройства принят")
	}
	// Токены устройств на диске только в виде хеша
	b, _ := os.ReadFile(filepath.Join(e.dir, "devices.json"))
	if bytes.Contains(b, []byte(devTok)) {
		t.Fatal("токен устройства в открытом виде")
	}
}

func TestPasswordChangeRevokesEverything(t *testing.T) {
	e := newEnv(t)
	e.initVault()
	m := e.login(true)
	tok, devTok := m["token"].(string), m["deviceToken"].(string)
	newAuth := randomBytes(32)
	e.clk.t = e.clk.t.Add(31 * time.Second)
	w := e.do("POST", "/api/v1/password", tok, map[string]any{"totp": e.code(), "currentAuthKey": b64(e.auth),
		"kdf": KDF{"PBKDF2-SHA256", 600000, b64(randomBytes(16))}, "authKey": b64(newAuth), "wrappedKey": b64(randomBytes(60))})
	if w.Code != 204 {
		t.Fatal(w.Code, w.Body)
	}
	if w := e.do("GET", "/api/v1/records", tok, nil); w.Code != 401 {
		t.Fatal("старая сессия жива")
	}
	if w := e.do("POST", "/api/v1/device-login", "", map[string]any{"deviceToken": devTok}); w.Code != 401 {
		t.Fatal("старое устройство живо")
	}
	e.auth = newAuth
	e.login(false)
}

func TestSecurityHeadersAndStatic(t *testing.T) {
	e := newEnv(t)
	w := e.do("GET", "/", "", nil)
	if w.Code != 200 {
		t.Fatal(w.Code)
	}
	csp := w.Header().Get("Content-Security-Policy")
	if !strings.Contains(csp, "script-src 'self'") || strings.Contains(csp, "unsafe") {
		t.Fatal("CSP", csp)
	}
	if w.Header().Get("X-Frame-Options") != "DENY" || w.Header().Get("Referrer-Policy") != "no-referrer" {
		t.Fatal("заголовки")
	}
	if w := e.do("GET", "/api/v1/status", "", nil); w.Header().Get("Cache-Control") != "no-store" {
		t.Fatal("API кешируется")
	}
	if w := e.do("POST", "/api/v1/login", "", []byte("authKey=1")); w.Code != 415 && w.Code != 400 {
		t.Fatal("не-JSON принят", w.Code)
	}
}

func TestClientIPOnlyFromLocalProxy(t *testing.T) {
	e := newEnv(t)
	e.srv.cfg.TrustedProxy = true
	r := httptest.NewRequest("GET", "/", nil)
	r.RemoteAddr = "127.0.0.1:5555"
	r.Header.Set("X-Forwarded-For", "1.2.3.4, 5.6.7.8")
	if ip := e.srv.clientIP(r); ip != "5.6.7.8" {
		t.Fatal(ip)
	}
	r.RemoteAddr = "9.9.9.9:5555" // не через Caddy — заголовку не верим
	if ip := e.srv.clientIP(r); ip != "9.9.9.9" {
		t.Fatal(ip)
	}
}

// Параллельный подбор: запросы с отложенным телом не должны обходить лимит попыток.
func TestParallelBruteForceLimited(t *testing.T) {
	e := newEnv(t)
	e.initVault()
	ts := httptest.NewServer(e.h)
	defer ts.Close()
	const n = 100
	writers := make([]*io.PipeWriter, n)
	codes := make(chan int, n)
	for i := 0; i < n; i++ {
		pr, pw := io.Pipe()
		writers[i] = pw
		go func() {
			req, _ := http.NewRequest("POST", ts.URL+"/api/v1/login", pr)
			req.Header.Set("Content-Type", "application/json")
			resp, err := http.DefaultClient.Do(req)
			if err != nil {
				codes <- 0
				return
			}
			resp.Body.Close()
			codes <- resp.StatusCode
		}()
	}
	time.Sleep(300 * time.Millisecond) // все запросы уже прошли заголовки и ждут тело
	body, _ := json.Marshal(map[string]any{"authKey": b64(e.auth), "totp": "000000"})
	for _, w := range writers {
		go func(w *io.PipeWriter) { w.Write(body); w.Close() }(w)
	}
	checked := 0
	for i := 0; i < n; i++ {
		if c := <-codes; c == 401 {
			checked++
		}
	}
	if checked > ipMaxFails {
		t.Fatalf("проверено %d попыток вместо не более %d", checked, ipMaxFails)
	}
}

// Общая блокировка входа по паролю не выключает доверенные устройства.
func TestGlobalLockDoesNotStopDevices(t *testing.T) {
	e := newEnv(t)
	e.initVault()
	dev := e.login(true)["deviceToken"].(string)
	for i := 0; i < globalMax; i++ {
		e.srv.limiter.Fail(fmt.Sprintf("10.0.0.%d", i))
	}
	if w := e.do("POST", "/api/v1/login", "", map[string]any{"authKey": b64(e.auth), "totp": e.code()}); w.Code != 429 {
		t.Fatal("ожидалась общая блокировка", w.Code)
	}
	if w := e.do("POST", "/api/v1/device-login", "", map[string]any{"deviceToken": dev}); w.Code != 200 {
		t.Fatal("доверенное устройство заблокировано", w.Code, w.Body)
	}
}
