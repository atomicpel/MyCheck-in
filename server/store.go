package main

import (
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"regexp"
	"sort"
	"sync"
	"time"
)

// Хранилище шифротекстов. Сервер не знает ключа и не может прочитать содержимое.
// Записи — отдельные файлы (атомарная запись через временный файл и rename).

var idPattern = regexp.MustCompile(`^[A-Za-z0-9-]{8,64}$`)

func validID(id string) bool { return idPattern.MatchString(id) }

// KDF — параметры получения ключа из мастер-пароля (вычисляется на клиенте).
type KDF struct {
	Alg  string `json:"alg"`  // "PBKDF2-SHA256"
	Iter int    `json:"iter"` // не меньше 600000
	Salt string `json:"salt"` // base64, 16 байт
}

// Vault — служебные данные хранилища. authHash = HMAC(pepper, authKey); wrappedKey — шифротекст ключа.
type Vault struct {
	KDF        KDF       `json:"kdf"`
	AuthHash   string    `json:"authHash"`
	WrappedKey string    `json:"wrappedKey"`
	CreatedAt  time.Time `json:"createdAt"`
	ChangedAt  time.Time `json:"changedAt"`
}

type Record struct {
	ID        string    `json:"id"`
	Rev       int64     `json:"rev"`
	Deleted   bool      `json:"deleted,omitempty"`
	Data      string    `json:"data,omitempty"` // base64(iv || AES-GCM)
	UpdatedAt time.Time `json:"updatedAt"`
}

type Device struct {
	ID        string    `json:"id"`
	Name      string    `json:"name"`
	TokenHash string    `json:"tokenHash"`
	CreatedAt time.Time `json:"createdAt"`
	LastSeen  time.Time `json:"lastSeen"`
	LastIP    string    `json:"lastIp"`
	ExpiresAt time.Time `json:"expiresAt"`
}

var (
	ErrConflict = errors.New("conflict")
	ErrNotFound = errors.New("not found")
	ErrTooLarge = errors.New("storage limit")
)

const (
	MaxRecordBytes = 256 << 10
	MaxBlobBytes   = 4 << 20
	MaxTotalBytes  = 2 << 30
)

type Store struct {
	dir     string
	mu      sync.Mutex
	rev     int64
	records map[string]*Record
	total   int64
}

func OpenStore(dir string) (*Store, error) {
	for _, d := range []string{dir, filepath.Join(dir, "records"), filepath.Join(dir, "blobs")} {
		if err := os.MkdirAll(d, 0o700); err != nil {
			return nil, err
		}
	}
	s := &Store{dir: dir, records: map[string]*Record{}}
	entries, err := os.ReadDir(filepath.Join(dir, "records"))
	if err != nil {
		return nil, err
	}
	for _, e := range entries {
		if e.IsDir() || filepath.Ext(e.Name()) != ".json" {
			continue
		}
		var r Record
		if err := readJSON(filepath.Join(dir, "records", e.Name()), &r); err != nil {
			return nil, fmt.Errorf("запись %s: %w", e.Name(), err)
		}
		s.records[r.ID] = &r
		s.total += int64(len(r.Data))
		if r.Rev > s.rev {
			s.rev = r.Rev
		}
	}
	blobs, _ := os.ReadDir(filepath.Join(dir, "blobs"))
	for _, b := range blobs {
		if info, err := b.Info(); err == nil {
			s.total += info.Size()
		}
	}
	return s, nil
}

func writeFileAtomic(path string, data []byte) error {
	tmp, err := os.CreateTemp(filepath.Dir(path), ".tmp-*")
	if err != nil {
		return err
	}
	name := tmp.Name()
	defer os.Remove(name)
	if err := tmp.Chmod(0o600); err != nil {
		tmp.Close()
		return err
	}
	if _, err := tmp.Write(data); err != nil {
		tmp.Close()
		return err
	}
	if err := tmp.Sync(); err != nil {
		tmp.Close()
		return err
	}
	if err := tmp.Close(); err != nil {
		return err
	}
	return os.Rename(name, path)
}

func writeJSON(path string, v any) error {
	b, err := json.MarshalIndent(v, "", " ")
	if err != nil {
		return err
	}
	return writeFileAtomic(path, b)
}

func readJSON(path string, v any) error {
	b, err := os.ReadFile(path)
	if err != nil {
		return err
	}
	return json.Unmarshal(b, v)
}

// ---- Хранилище (vault.json) ----

func (s *Store) Vault() (*Vault, error) {
	var v Vault
	if err := readJSON(filepath.Join(s.dir, "vault.json"), &v); err != nil {
		if errors.Is(err, os.ErrNotExist) {
			return nil, nil
		}
		return nil, err
	}
	return &v, nil
}

func (s *Store) SaveVault(v *Vault) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	return writeJSON(filepath.Join(s.dir, "vault.json"), v)
}

// ---- Записи ----

// Changes — записи, изменённые после ревизии since (включая удалённые — «надгробия»).
func (s *Store) Changes(since int64) (int64, []Record) {
	s.mu.Lock()
	defer s.mu.Unlock()
	out := []Record{}
	for _, r := range s.records {
		if r.Rev > since {
			out = append(out, *r)
		}
	}
	sort.Slice(out, func(i, j int) bool { return out[i].Rev < out[j].Rev })
	return s.rev, out
}

// Put — сохранить запись, если её ревизия не изменилась с baseRev (0 — новая запись).
func (s *Store) Put(id string, baseRev int64, data string) (*Record, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	cur := s.records[id]
	if (cur == nil && baseRev != 0) || (cur != nil && cur.Rev != baseRev) {
		if cur == nil {
			return nil, ErrConflict
		}
		c := *cur
		return &c, ErrConflict
	}
	delta := int64(len(data))
	if cur != nil {
		delta -= int64(len(cur.Data))
	}
	if s.total+delta > MaxTotalBytes {
		return nil, ErrTooLarge
	}
	r := &Record{ID: id, Rev: s.rev + 1, Data: data, UpdatedAt: time.Now().UTC()}
	if err := writeJSON(filepath.Join(s.dir, "records", id+".json"), r); err != nil {
		return nil, err
	}
	s.rev = r.Rev
	s.total += delta
	s.records[id] = r
	c := *r
	return &c, nil
}

// Delete — оставить «надгробие», чтобы другие устройства узнали об удалении.
func (s *Store) Delete(id string, baseRev int64) (*Record, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	cur := s.records[id]
	if cur == nil {
		return nil, ErrNotFound
	}
	if cur.Rev != baseRev {
		c := *cur
		return &c, ErrConflict
	}
	r := &Record{ID: id, Rev: s.rev + 1, Deleted: true, UpdatedAt: time.Now().UTC()}
	if err := writeJSON(filepath.Join(s.dir, "records", id+".json"), r); err != nil {
		return nil, err
	}
	s.total -= int64(len(cur.Data))
	s.rev = r.Rev
	s.records[id] = r
	c := *r
	return &c, nil
}

func (s *Store) Count() (records int, bytes int64) {
	s.mu.Lock()
	defer s.mu.Unlock()
	for _, r := range s.records {
		if !r.Deleted {
			records++
		}
	}
	return records, s.total
}

// ---- Фото (зашифрованные) ----

func (s *Store) blobPath(id string) string { return filepath.Join(s.dir, "blobs", id) }

func (s *Store) PutBlob(id string, data []byte) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	var old int64
	if info, err := os.Stat(s.blobPath(id)); err == nil {
		old = info.Size()
	}
	if s.total-old+int64(len(data)) > MaxTotalBytes {
		return ErrTooLarge
	}
	if err := writeFileAtomic(s.blobPath(id), data); err != nil {
		return err
	}
	s.total += int64(len(data)) - old
	return nil
}

func (s *Store) GetBlob(id string) ([]byte, error) {
	b, err := os.ReadFile(s.blobPath(id))
	if errors.Is(err, os.ErrNotExist) {
		return nil, ErrNotFound
	}
	return b, err
}

func (s *Store) DeleteBlob(id string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	info, err := os.Stat(s.blobPath(id))
	if errors.Is(err, os.ErrNotExist) {
		return ErrNotFound
	}
	if err != nil {
		return err
	}
	if err := os.Remove(s.blobPath(id)); err != nil {
		return err
	}
	s.total -= info.Size()
	return nil
}

// ---- Доверенные устройства ----

func (s *Store) Devices() ([]Device, error) {
	var d []Device
	err := readJSON(filepath.Join(s.dir, "devices.json"), &d)
	if errors.Is(err, os.ErrNotExist) {
		return []Device{}, nil
	}
	return d, err
}

func (s *Store) SaveDevices(d []Device) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	return writeJSON(filepath.Join(s.dir, "devices.json"), d)
}
