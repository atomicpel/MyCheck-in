package main

import (
	"crypto/hmac"
	"crypto/sha256"
	"crypto/subtle"
	"embed"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"io"
	"io/fs"
	"log"
	"net"
	"net/http"
	"strconv"
	"strings"
	"sync"
	"time"
)

//go:embed web
var webFS embed.FS

type Server struct {
	cfg      *Config
	pepper   []byte
	store    *Store
	totp     *TOTP
	sessions *Sessions
	limiter  *Limiter
	audit    *Audit
	devMu    sync.Mutex
	now      func() time.Time
}

func NewServer(cfg *Config, dataDir string, now func() time.Time) (*Server, error) {
	pepper, err := base64.StdEncoding.DecodeString(cfg.Pepper)
	if err != nil || len(pepper) != 32 {
		return nil, errors.New("config: pepper должен быть 32 байта в base64")
	}
	secret, err := decodeTOTPSecret(cfg.TOTPSecret)
	if err != nil || len(secret) < 10 {
		return nil, errors.New("config: неверный totpSecret")
	}
	st, err := OpenStore(dataDir)
	if err != nil {
		return nil, err
	}
	if now == nil {
		now = time.Now
	}
	return &Server{
		cfg: cfg, pepper: pepper, store: st, now: now,
		totp:     &TOTP{secret: secret, now: now},
		sessions: &Sessions{byID: map[string]*Session{}, idle: time.Duration(cfg.SessionIdleMin) * time.Minute, max: time.Duration(cfg.SessionMaxHours) * time.Hour, now: now},
		limiter:  &Limiter{perIP: map[string][]time.Time{}, now: now},
		audit:    &Audit{path: dataDir + "/audit.log", now: now},
	}, nil
}

// ---- Общие обработчики ----

func (s *Server) Handler() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /api/v1/status", s.status)
	mux.HandleFunc("POST /api/v1/setup", s.setup)
	mux.HandleFunc("GET /api/v1/prelogin", s.prelogin)
	mux.HandleFunc("POST /api/v1/login", s.login)
	mux.HandleFunc("POST /api/v1/device-login", s.deviceLogin)
	mux.HandleFunc("POST /api/v1/logout", s.authed(s.logout))
	mux.HandleFunc("GET /api/v1/records", s.authed(s.listRecords))
	mux.HandleFunc("PUT /api/v1/records/{id}", s.authed(s.putRecord))
	mux.HandleFunc("DELETE /api/v1/records/{id}", s.authed(s.deleteRecord))
	mux.HandleFunc("PUT /api/v1/blobs/{id}", s.authed(s.putBlob))
	mux.HandleFunc("GET /api/v1/blobs/{id}", s.authed(s.getBlob))
	mux.HandleFunc("DELETE /api/v1/blobs/{id}", s.authed(s.deleteBlob))
	mux.HandleFunc("GET /api/v1/audit", s.authed(s.listAudit))
	mux.HandleFunc("GET /api/v1/devices", s.authed(s.listDevices))
	mux.HandleFunc("DELETE /api/v1/devices/{id}", s.authed(s.revokeDevice))
	mux.HandleFunc("POST /api/v1/password", s.authed(s.changePassword))
	mux.HandleFunc("GET /api/v1/vault-key", s.authed(s.vaultKey))
	mux.HandleFunc("/api/", func(w http.ResponseWriter, r *http.Request) {
		jsonError(w, http.StatusNotFound, "Нет такого метода")
	})

	static, _ := fs.Sub(webFS, "web")
	files := http.FileServer(http.FS(static))
	mux.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodGet && r.Method != http.MethodHead {
			jsonError(w, http.StatusMethodNotAllowed, "Метод не поддерживается")
			return
		}
		if r.URL.Path == "/" || r.URL.Path == "/index.html" {
			w.Header().Set("Cache-Control", "no-cache")
		} else {
			w.Header().Set("Cache-Control", "public, max-age=3600")
		}
		files.ServeHTTP(w, r)
	})
	return s.secure(mux)
}

// secure — заголовки безопасности, запрет кеширования API, перехват паник.
func (s *Server) secure(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		h := w.Header()
		h.Set("Content-Security-Policy", "default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self' blob: data:; "+
			"connect-src 'self'; font-src 'self'; manifest-src 'self'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'")
		h.Set("X-Content-Type-Options", "nosniff")
		h.Set("X-Frame-Options", "DENY")
		h.Set("Referrer-Policy", "no-referrer")
		h.Set("Cross-Origin-Opener-Policy", "same-origin")
		h.Set("Cross-Origin-Resource-Policy", "same-origin")
		h.Set("Permissions-Policy", "camera=(), microphone=(), geolocation=(), payment=(), usb=(), interest-cohort=()")
		h.Set("X-Robots-Tag", "noindex, nofollow")
		if strings.HasPrefix(r.URL.Path, "/api/") {
			h.Set("Cache-Control", "no-store")
		}
		defer func() {
			if v := recover(); v != nil {
				log.Printf("panic: %v", v)
				jsonError(w, http.StatusInternalServerError, "Внутренняя ошибка")
			}
		}()
		next.ServeHTTP(w, r)
	})
}

func jsonOK(w http.ResponseWriter, v any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	json.NewEncoder(w).Encode(v)
}

func jsonError(w http.ResponseWriter, code int, msg string) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(code)
	json.NewEncoder(w).Encode(map[string]string{"error": msg})
}

func readBody(w http.ResponseWriter, r *http.Request, max int64, v any) bool {
	if ct := r.Header.Get("Content-Type"); !strings.HasPrefix(ct, "application/json") {
		jsonError(w, http.StatusUnsupportedMediaType, "Нужен application/json")
		return false
	}
	dec := json.NewDecoder(http.MaxBytesReader(w, r.Body, max))
	dec.DisallowUnknownFields()
	if err := dec.Decode(v); err != nil {
		jsonError(w, http.StatusBadRequest, "Неверный запрос")
		return false
	}
	return true
}

// clientIP — адрес клиента. За Caddy на этом же сервере — последний адрес из X-Forwarded-For.
func (s *Server) clientIP(r *http.Request) string {
	host, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		host = r.RemoteAddr
	}
	if s.cfg.TrustedProxy {
		if ip := net.ParseIP(host); ip != nil && ip.IsLoopback() {
			if xff := r.Header.Get("X-Forwarded-For"); xff != "" {
				parts := strings.Split(xff, ",")
				if c := strings.TrimSpace(parts[len(parts)-1]); net.ParseIP(c) != nil {
					return c
				}
			}
		}
	}
	return host
}

func bearer(r *http.Request) string {
	a := r.Header.Get("Authorization")
	if strings.HasPrefix(a, "Bearer ") {
		return strings.TrimSpace(a[7:])
	}
	return ""
}

type ctxHandler func(w http.ResponseWriter, r *http.Request, ses *Session, tok string)

func (s *Server) authed(h ctxHandler) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		tok := bearer(r)
		ses, ok := s.sessions.Check(tok)
		if !ok {
			jsonError(w, http.StatusUnauthorized, "Сессия истекла — войдите снова")
			return
		}
		h(w, r, ses, tok)
	}
}

func (s *Server) authHash(authKey []byte) string {
	m := hmac.New(sha256.New, s.pepper)
	m.Write(authKey)
	return hex.EncodeToString(m.Sum(nil))
}

func decodeB64(s string, want int) ([]byte, bool) {
	b, err := base64.StdEncoding.DecodeString(s)
	if err != nil || (want > 0 && len(b) != want) {
		return nil, false
	}
	return b, true
}

// ---- Настройка и вход ----

func (s *Server) status(w http.ResponseWriter, r *http.Request) {
	v, _ := s.store.Vault()
	jsonOK(w, map[string]any{"initialized": v != nil, "version": Version})
}

func validKDF(k KDF) bool {
	salt, ok := decodeB64(k.Salt, 16)
	return ok && salt != nil && k.Alg == "PBKDF2-SHA256" && k.Iter >= 600_000 && k.Iter <= 10_000_000
}

func validWrapped(s string) bool {
	_, ok := decodeB64(s, 12+32+16) // iv + ключ + тег GCM
	return ok
}

func (s *Server) setup(w http.ResponseWriter, r *http.Request) {
	ip := s.clientIP(r)
	if ok, wait := s.limiter.Allowed(ip); !ok {
		jsonError(w, http.StatusTooManyRequests, "Слишком много попыток. Подождите "+wait.Round(time.Minute).String())
		return
	}
	var req struct {
		SetupToken string `json:"setupToken"`
		KDF        KDF    `json:"kdf"`
		AuthKey    string `json:"authKey"`
		WrappedKey string `json:"wrappedKey"`
	}
	if !readBody(w, r, 4096, &req) {
		return
	}
	if v, _ := s.store.Vault(); v != nil || s.cfg.SetupTokenHash == "" {
		jsonError(w, http.StatusConflict, "Хранилище уже создано")
		return
	}
	if subtle.ConstantTimeCompare([]byte(sha256hex(req.SetupToken)), []byte(s.cfg.SetupTokenHash)) != 1 {
		s.limiter.Fail(ip)
		s.audit.Log("setup", ip, "", "неверный токен настройки", false)
		jsonError(w, http.StatusForbidden, "Неверный токен настройки")
		return
	}
	authKey, ok := decodeB64(req.AuthKey, 32)
	if !ok || !validKDF(req.KDF) || !validWrapped(req.WrappedKey) {
		jsonError(w, http.StatusBadRequest, "Неверные параметры ключей")
		return
	}
	now := s.now().UTC()
	if err := s.store.SaveVault(&Vault{KDF: req.KDF, AuthHash: s.authHash(authKey), WrappedKey: req.WrappedKey, CreatedAt: now, ChangedAt: now}); err != nil {
		jsonError(w, http.StatusInternalServerError, "Не удалось сохранить")
		return
	}
	s.audit.Log("setup", ip, "", "хранилище создано", true)
	w.WriteHeader(http.StatusNoContent)
}

func (s *Server) prelogin(w http.ResponseWriter, r *http.Request) {
	v, err := s.store.Vault()
	if err != nil || v == nil {
		jsonError(w, http.StatusConflict, "Хранилище ещё не создано")
		return
	}
	jsonOK(w, map[string]any{"kdf": v.KDF})
}

func (s *Server) login(w http.ResponseWriter, r *http.Request) {
	ip := s.clientIP(r)
	if ok, wait := s.limiter.Allowed(ip); !ok {
		s.audit.Log("login", ip, "", "заблокировано (подбор)", false)
		jsonError(w, http.StatusTooManyRequests, "Слишком много попыток. Подождите "+wait.Round(time.Minute).String())
		return
	}
	var req struct {
		AuthKey     string `json:"authKey"`
		TOTP        string `json:"totp"`
		TrustDevice bool   `json:"trustDevice"`
		DeviceName  string `json:"deviceName"`
	}
	if !readBody(w, r, 2048, &req) {
		return
	}
	v, err := s.store.Vault()
	if err != nil || v == nil {
		jsonError(w, http.StatusConflict, "Хранилище ещё не создано")
		return
	}
	name := cleanName(req.DeviceName)
	authKey, ok := decodeB64(req.AuthKey, 32)
	passOK := ok && subtle.ConstantTimeCompare([]byte(s.authHash(authKey)), []byte(v.AuthHash)) == 1
	// Код проверяем, только если пароль верен: иначе неверный пароль «сжёг» бы действующий код.
	if !passOK || !s.totp.Verify(req.TOTP) {
		locked := s.limiter.Fail(ip)
		detail := "неверный пароль или код"
		if locked {
			detail += "; включена общая блокировка входа на 1 час"
		}
		s.audit.Log("login", ip, name, detail, false)
		jsonError(w, http.StatusUnauthorized, "Неверный пароль или код подтверждения")
		return
	}
	s.limiter.Success(ip)
	resp := map[string]any{"wrappedKey": v.WrappedKey, "expiresIn": s.cfg.SessionIdleMin * 60}
	deviceID := ""
	if req.TrustDevice {
		devTok := randomToken()
		deviceID = randomToken()[:16]
		now := s.now().UTC()
		s.devMu.Lock()
		devs, _ := s.store.Devices()
		devs = append(devs, Device{ID: deviceID, Name: name, TokenHash: sha256hex(devTok), CreatedAt: now, LastSeen: now, LastIP: ip,
			ExpiresAt: now.Add(time.Duration(s.cfg.DeviceDays) * 24 * time.Hour)})
		err := s.store.SaveDevices(devs)
		s.devMu.Unlock()
		if err != nil {
			jsonError(w, http.StatusInternalServerError, "Не удалось сохранить устройство")
			return
		}
		resp["deviceToken"] = devTok
		resp["deviceId"] = deviceID
	}
	resp["token"] = s.sessions.New(deviceID, name, ip)
	s.audit.Log("login", ip, name, map[bool]string{true: "вход, устройство добавлено в доверенные", false: "вход"}[req.TrustDevice], true)
	jsonOK(w, resp)
}

func cleanName(n string) string {
	n = strings.TrimSpace(n)
	n = strings.Map(func(r rune) rune {
		if r < 32 || r == 127 {
			return -1
		}
		return r
	}, n)
	if len([]rune(n)) > 60 {
		n = string([]rune(n)[:60])
	}
	if n == "" {
		n = "без названия"
	}
	return n
}

// deviceLogin — вход доверенного устройства по его токену (без пароля и кода).
func (s *Server) deviceLogin(w http.ResponseWriter, r *http.Request) {
	ip := s.clientIP(r)
	if ok, wait := s.limiter.Allowed(ip); !ok {
		jsonError(w, http.StatusTooManyRequests, "Слишком много попыток. Подождите "+wait.Round(time.Minute).String())
		return
	}
	var req struct {
		DeviceToken string `json:"deviceToken"`
	}
	if !readBody(w, r, 1024, &req) {
		return
	}
	h := sha256hex(req.DeviceToken)
	now := s.now().UTC()
	s.devMu.Lock()
	devs, _ := s.store.Devices()
	var dev *Device
	for i := range devs {
		if subtle.ConstantTimeCompare([]byte(devs[i].TokenHash), []byte(h)) == 1 {
			dev = &devs[i]
		}
	}
	if dev == nil || now.After(dev.ExpiresAt) {
		s.devMu.Unlock()
		s.limiter.Fail(ip)
		s.audit.Log("device-login", ip, "", "неизвестное или просроченное устройство", false)
		jsonError(w, http.StatusUnauthorized, "Устройство не доверено или срок истёк — войдите с паролем")
		return
	}
	dev.LastSeen, dev.LastIP = now, ip
	name, id := dev.Name, dev.ID
	_ = s.store.SaveDevices(devs)
	s.devMu.Unlock()
	s.limiter.Success(ip)
	s.audit.Log("device-login", ip, name, "вход доверенного устройства", true)
	jsonOK(w, map[string]any{"token": s.sessions.New(id, name, ip), "expiresIn": s.cfg.SessionIdleMin * 60})
}

func (s *Server) logout(w http.ResponseWriter, r *http.Request, ses *Session, tok string) {
	s.sessions.Drop(tok)
	s.audit.Log("logout", s.clientIP(r), ses.Name, "", true)
	w.WriteHeader(http.StatusNoContent)
}

func (s *Server) changePassword(w http.ResponseWriter, r *http.Request, ses *Session, tok string) {
	ip := s.clientIP(r)
	var req struct {
		TOTP       string `json:"totp"`
		CurrentKey string `json:"currentAuthKey"`
		KDF        KDF    `json:"kdf"`
		AuthKey    string `json:"authKey"`
		WrappedKey string `json:"wrappedKey"`
	}
	if !readBody(w, r, 4096, &req) {
		return
	}
	v, _ := s.store.Vault()
	cur, ok := decodeB64(req.CurrentKey, 32)
	if v == nil || !ok || subtle.ConstantTimeCompare([]byte(s.authHash(cur)), []byte(v.AuthHash)) != 1 || !s.totp.Verify(req.TOTP) {
		s.limiter.Fail(ip)
		s.audit.Log("password", ip, ses.Name, "неверный текущий пароль или код", false)
		jsonError(w, http.StatusForbidden, "Неверный текущий пароль или код подтверждения")
		return
	}
	authKey, ok := decodeB64(req.AuthKey, 32)
	if !ok || !validKDF(req.KDF) || !validWrapped(req.WrappedKey) {
		jsonError(w, http.StatusBadRequest, "Неверные параметры ключей")
		return
	}
	v.KDF, v.AuthHash, v.WrappedKey, v.ChangedAt = req.KDF, s.authHash(authKey), req.WrappedKey, s.now().UTC()
	if err := s.store.SaveVault(v); err != nil {
		jsonError(w, http.StatusInternalServerError, "Не удалось сохранить")
		return
	}
	// Смена пароля — повод считать прежние входы скомпрометированными: отзываем всё.
	s.devMu.Lock()
	_ = s.store.SaveDevices([]Device{})
	s.devMu.Unlock()
	s.sessions.DropAll()
	s.audit.Log("password", ip, ses.Name, "пароль изменён, все сессии и устройства отозваны", true)
	w.WriteHeader(http.StatusNoContent)
}

// vaultKey — завёрнутый ключ хранилища для вошедшего (нужен для смены пароля; без мастер-пароля бесполезен).
func (s *Server) vaultKey(w http.ResponseWriter, r *http.Request, ses *Session, _ string) {
	v, err := s.store.Vault()
	if err != nil || v == nil {
		jsonError(w, http.StatusConflict, "Хранилище ещё не создано")
		return
	}
	jsonOK(w, map[string]any{"wrappedKey": v.WrappedKey})
}

// ---- Записи и фото ----

func (s *Server) listRecords(w http.ResponseWriter, r *http.Request, ses *Session, _ string) {
	since, _ := strconv.ParseInt(r.URL.Query().Get("since"), 10, 64)
	rev, recs := s.store.Changes(since)
	jsonOK(w, map[string]any{"rev": rev, "records": recs})
}

func (s *Server) putRecord(w http.ResponseWriter, r *http.Request, ses *Session, _ string) {
	id := r.PathValue("id")
	if !validID(id) {
		jsonError(w, http.StatusBadRequest, "Неверный идентификатор")
		return
	}
	var req struct {
		BaseRev int64  `json:"baseRev"`
		Data    string `json:"data"`
	}
	if !readBody(w, r, MaxRecordBytes+1024, &req) {
		return
	}
	if raw, ok := decodeB64(req.Data, 0); !ok || len(raw) < 12+16 {
		jsonError(w, http.StatusBadRequest, "Данные должны быть зашифрованы")
		return
	}
	rec, err := s.store.Put(id, req.BaseRev, req.Data)
	switch {
	case errors.Is(err, ErrConflict):
		w.Header().Set("Content-Type", "application/json; charset=utf-8")
		w.WriteHeader(http.StatusConflict)
		json.NewEncoder(w).Encode(map[string]any{"error": "Запись изменена на другом устройстве", "record": rec})
	case errors.Is(err, ErrTooLarge):
		jsonError(w, http.StatusInsufficientStorage, "Превышен объём хранилища")
	case err != nil:
		jsonError(w, http.StatusInternalServerError, "Не удалось сохранить")
	default:
		jsonOK(w, map[string]any{"rev": rec.Rev})
	}
}

func (s *Server) deleteRecord(w http.ResponseWriter, r *http.Request, ses *Session, _ string) {
	id := r.PathValue("id")
	base, _ := strconv.ParseInt(r.URL.Query().Get("baseRev"), 10, 64)
	if !validID(id) {
		jsonError(w, http.StatusBadRequest, "Неверный идентификатор")
		return
	}
	rec, err := s.store.Delete(id, base)
	switch {
	case errors.Is(err, ErrNotFound):
		jsonError(w, http.StatusNotFound, "Нет такой записи")
	case errors.Is(err, ErrConflict):
		w.Header().Set("Content-Type", "application/json; charset=utf-8")
		w.WriteHeader(http.StatusConflict)
		json.NewEncoder(w).Encode(map[string]any{"error": "Запись изменена на другом устройстве", "record": rec})
	case err != nil:
		jsonError(w, http.StatusInternalServerError, "Не удалось удалить")
	default:
		s.audit.Log("delete", s.clientIP(r), ses.Name, "удалена запись", true)
		jsonOK(w, map[string]any{"rev": rec.Rev})
	}
}

func (s *Server) putBlob(w http.ResponseWriter, r *http.Request, ses *Session, _ string) {
	id := r.PathValue("id")
	if !validID(id) {
		jsonError(w, http.StatusBadRequest, "Неверный идентификатор")
		return
	}
	data, err := io.ReadAll(http.MaxBytesReader(w, r.Body, MaxBlobBytes))
	if err != nil {
		jsonError(w, http.StatusRequestEntityTooLarge, "Фото больше 4 МБ")
		return
	}
	if len(data) < 12+16 {
		jsonError(w, http.StatusBadRequest, "Данные должны быть зашифрованы")
		return
	}
	if err := s.store.PutBlob(id, data); err != nil {
		if errors.Is(err, ErrTooLarge) {
			jsonError(w, http.StatusInsufficientStorage, "Превышен объём хранилища")
			return
		}
		jsonError(w, http.StatusInternalServerError, "Не удалось сохранить")
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

func (s *Server) getBlob(w http.ResponseWriter, r *http.Request, ses *Session, _ string) {
	id := r.PathValue("id")
	if !validID(id) {
		jsonError(w, http.StatusBadRequest, "Неверный идентификатор")
		return
	}
	b, err := s.store.GetBlob(id)
	if err != nil {
		jsonError(w, http.StatusNotFound, "Нет такого фото")
		return
	}
	w.Header().Set("Content-Type", "application/octet-stream")
	w.Write(b)
}

func (s *Server) deleteBlob(w http.ResponseWriter, r *http.Request, ses *Session, _ string) {
	id := r.PathValue("id")
	if !validID(id) {
		jsonError(w, http.StatusBadRequest, "Неверный идентификатор")
		return
	}
	if err := s.store.DeleteBlob(id); err != nil && !errors.Is(err, ErrNotFound) {
		jsonError(w, http.StatusInternalServerError, "Не удалось удалить")
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

// ---- Аудит и устройства ----

func (s *Server) listAudit(w http.ResponseWriter, r *http.Request, ses *Session, _ string) {
	n, _ := strconv.Atoi(r.URL.Query().Get("limit"))
	if n <= 0 || n > 1000 {
		n = 200
	}
	recs, bytes := s.store.Count()
	jsonOK(w, map[string]any{"entries": s.audit.Last(n), "records": recs, "bytes": bytes})
}

func (s *Server) listDevices(w http.ResponseWriter, r *http.Request, ses *Session, _ string) {
	s.devMu.Lock()
	devs, _ := s.store.Devices()
	s.devMu.Unlock()
	type pub struct {
		ID        string    `json:"id"`
		Name      string    `json:"name"`
		CreatedAt time.Time `json:"createdAt"`
		LastSeen  time.Time `json:"lastSeen"`
		LastIP    string    `json:"lastIp"`
		ExpiresAt time.Time `json:"expiresAt"`
		Current   bool      `json:"current"`
	}
	out := []pub{}
	for _, d := range devs {
		out = append(out, pub{d.ID, d.Name, d.CreatedAt, d.LastSeen, d.LastIP, d.ExpiresAt, d.ID == ses.DeviceID})
	}
	jsonOK(w, map[string]any{"devices": out})
}

func (s *Server) revokeDevice(w http.ResponseWriter, r *http.Request, ses *Session, _ string) {
	id := r.PathValue("id")
	s.devMu.Lock()
	devs, _ := s.store.Devices()
	kept := devs[:0]
	var name string
	for _, d := range devs {
		if d.ID == id {
			name = d.Name
			continue
		}
		kept = append(kept, d)
	}
	err := s.store.SaveDevices(kept)
	s.devMu.Unlock()
	if err != nil {
		jsonError(w, http.StatusInternalServerError, "Не удалось сохранить")
		return
	}
	s.sessions.DropDevice(id)
	s.audit.Log("revoke-device", s.clientIP(r), ses.Name, "отозвано устройство «"+name+"»", true)
	w.WriteHeader(http.StatusNoContent)
}
