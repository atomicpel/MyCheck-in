package main

import (
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha1"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/base32"
	"encoding/base64"
	"encoding/binary"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"
)

// Config — секреты сервера (файл config.json, права 600). Паспортных данных здесь нет.
type Config struct {
	Listen          string `json:"listen"`
	Pepper          string `json:"pepper"`          // base64, 32 байта
	TOTPSecret      string `json:"totpSecret"`      // base32
	SetupTokenHash  string `json:"setupTokenHash"`  // sha256(hex) одноразового токена первичной настройки; "" — использован
	TrustedProxy    bool   `json:"trustedProxy"`    // за Caddy на этом же сервере: брать адрес клиента из X-Forwarded-For
	SessionIdleMin  int    `json:"sessionIdleMin"`  // 30
	SessionMaxHours int    `json:"sessionMaxHours"` // 12
	DeviceDays      int    `json:"deviceDays"`      // 90
}

func randomBytes(n int) []byte {
	b := make([]byte, n)
	if _, err := rand.Read(b); err != nil {
		panic(err)
	}
	return b
}

func randomToken() string { return base64.RawURLEncoding.EncodeToString(randomBytes(32)) }

func sha256hex(s string) string {
	h := sha256.Sum256([]byte(s))
	return hex.EncodeToString(h[:])
}

func loadConfig(dir string) (*Config, error) {
	var c Config
	if err := readJSON(filepath.Join(dir, "config.json"), &c); err != nil {
		return nil, err
	}
	if c.SessionIdleMin <= 0 {
		c.SessionIdleMin = 30
	}
	if c.SessionMaxHours <= 0 {
		c.SessionMaxHours = 12
	}
	if c.DeviceDays <= 0 {
		c.DeviceDays = 90
	}
	if c.Listen == "" {
		c.Listen = "127.0.0.1:8743"
	}
	return &c, nil
}

// ---- TOTP (RFC 6238: SHA-1, 6 цифр, шаг 30 с) ----

func totpCode(secret []byte, counter uint64) string {
	var msg [8]byte
	binary.BigEndian.PutUint64(msg[:], counter)
	m := hmac.New(sha1.New, secret)
	m.Write(msg[:])
	sum := m.Sum(nil)
	off := sum[len(sum)-1] & 0x0f
	v := (uint32(sum[off])&0x7f)<<24 | uint32(sum[off+1])<<16 | uint32(sum[off+2])<<8 | uint32(sum[off+3])
	return fmt.Sprintf("%06d", v%1_000_000)
}

func decodeTOTPSecret(s string) ([]byte, error) {
	s = strings.ToUpper(strings.ReplaceAll(s, " ", ""))
	return base32.StdEncoding.WithPadding(base32.NoPadding).DecodeString(strings.TrimRight(s, "="))
}

type TOTP struct {
	mu          sync.Mutex
	secret      []byte
	lastCounter uint64 // защита от повторного использования кода
	now         func() time.Time
}

// Verify — код текущего или соседнего 30-секундного окна, каждый код — один раз.
func (t *TOTP) Verify(code string) bool {
	code = strings.TrimSpace(code)
	if len(code) != 6 {
		return false
	}
	t.mu.Lock()
	defer t.mu.Unlock()
	c := uint64(t.now().Unix() / 30)
	for _, d := range []int64{0, -1, 1} {
		cc := uint64(int64(c) + d)
		if cc <= t.lastCounter {
			continue
		}
		if subtle.ConstantTimeCompare([]byte(totpCode(t.secret, cc)), []byte(code)) == 1 {
			t.lastCounter = cc
			return true
		}
	}
	return false
}

// ---- Сессии ----

type Session struct {
	DeviceID  string
	Name      string
	IP        string
	Created   time.Time
	LastSeen  time.Time
	ExpiresAt time.Time
}

type Sessions struct {
	mu   sync.Mutex
	byID map[string]*Session // ключ — sha256(токена): сами токены в памяти не хранятся
	idle time.Duration
	max  time.Duration
	now  func() time.Time
}

func (s *Sessions) New(deviceID, name, ip string) string {
	tok := randomToken()
	now := s.now()
	s.mu.Lock()
	defer s.mu.Unlock()
	s.byID[sha256hex(tok)] = &Session{DeviceID: deviceID, Name: name, IP: ip, Created: now, LastSeen: now, ExpiresAt: now.Add(s.max)}
	return tok
}

func (s *Sessions) Check(tok string) (*Session, bool) {
	if tok == "" {
		return nil, false
	}
	now := s.now()
	s.mu.Lock()
	defer s.mu.Unlock()
	k := sha256hex(tok)
	ses := s.byID[k]
	if ses == nil {
		return nil, false
	}
	if now.After(ses.ExpiresAt) || now.Sub(ses.LastSeen) > s.idle {
		delete(s.byID, k)
		return nil, false
	}
	ses.LastSeen = now
	c := *ses
	return &c, true
}

func (s *Sessions) Drop(tok string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	delete(s.byID, sha256hex(tok))
}

// DropAll — закрыть все сессии (смена пароля, отзыв устройств).
func (s *Sessions) DropAll() {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.byID = map[string]*Session{}
}

func (s *Sessions) DropDevice(deviceID string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	for k, v := range s.byID {
		if v.DeviceID == deviceID {
			delete(s.byID, k)
		}
	}
}

// ---- Защита от подбора ----

type Limiter struct {
	mu       sync.Mutex
	perIP    map[string][]time.Time
	global   []time.Time
	lockedTo time.Time
	now      func() time.Time
}

const (
	ipWindow     = 15 * time.Minute
	ipMaxFails   = 5
	globalWindow = time.Hour
	globalMax    = 20
	globalLock   = time.Hour
)

func prune(ts []time.Time, after time.Time) []time.Time {
	out := ts[:0]
	for _, t := range ts {
		if t.After(after) {
			out = append(out, t)
		}
	}
	return out
}

// Allowed — можно ли сейчас пытаться войти с этого адреса (и сколько ждать, если нет).
func (l *Limiter) Allowed(ip string) (bool, time.Duration) {
	now := l.now()
	l.mu.Lock()
	defer l.mu.Unlock()
	if now.Before(l.lockedTo) {
		return false, l.lockedTo.Sub(now)
	}
	l.perIP[ip] = prune(l.perIP[ip], now.Add(-ipWindow))
	if f := l.perIP[ip]; len(f) >= ipMaxFails {
		return false, f[0].Add(ipWindow).Sub(now)
	}
	return true, 0
}

// Fail — неудачная попытка; возвращает true, если включилась общая блокировка.
func (l *Limiter) Fail(ip string) bool {
	now := l.now()
	l.mu.Lock()
	defer l.mu.Unlock()
	l.perIP[ip] = append(prune(l.perIP[ip], now.Add(-ipWindow)), now)
	l.global = append(prune(l.global, now.Add(-globalWindow)), now)
	if len(l.global) >= globalMax {
		l.lockedTo = now.Add(globalLock)
		l.global = nil
		return true
	}
	return false
}

func (l *Limiter) Success(ip string) {
	l.mu.Lock()
	defer l.mu.Unlock()
	delete(l.perIP, ip)
}

// ---- Журнал аудита (JSON Lines, без персональных данных) ----

type AuditEntry struct {
	Time   time.Time `json:"time"`
	Event  string    `json:"event"`
	IP     string    `json:"ip,omitempty"`
	Device string    `json:"device,omitempty"`
	Detail string    `json:"detail,omitempty"`
	OK     bool      `json:"ok"`
}

type Audit struct {
	mu   sync.Mutex
	path string
	now  func() time.Time
}

const auditMaxBytes = 5 << 20

func (a *Audit) Log(event, ip, device, detail string, ok bool) {
	a.mu.Lock()
	defer a.mu.Unlock()
	if info, err := os.Stat(a.path); err == nil && info.Size() > auditMaxBytes {
		_ = os.Rename(a.path, a.path+".1")
	}
	f, err := os.OpenFile(a.path, os.O_CREATE|os.O_APPEND|os.O_WRONLY, 0o600)
	if err != nil {
		return
	}
	defer f.Close()
	b, _ := json.Marshal(AuditEntry{Time: a.now().UTC(), Event: event, IP: ip, Device: device, Detail: detail, OK: ok})
	f.Write(append(b, '\n'))
}

// Last — последние n записей (новые сверху).
func (a *Audit) Last(n int) []AuditEntry {
	a.mu.Lock()
	defer a.mu.Unlock()
	var lines []string
	for _, p := range []string{a.path + ".1", a.path} {
		if b, err := os.ReadFile(p); err == nil {
			lines = append(lines, strings.Split(strings.TrimSpace(string(b)), "\n")...)
		}
	}
	out := []AuditEntry{}
	for i := len(lines) - 1; i >= 0 && len(out) < n; i-- {
		var e AuditEntry
		if json.Unmarshal([]byte(lines[i]), &e) == nil {
			out = append(out, e)
		}
	}
	return out
}
