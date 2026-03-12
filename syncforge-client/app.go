package main

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"log"
	"mime/multipart"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"

	"github.com/fsnotify/fsnotify"
	"github.com/wailsapp/wails/v2/pkg/runtime"
)

// ── Config ────────────────────────────────────────────────────────────────────

type Config struct {
	ServerURL    string `json:"serverUrl"`
	Username     string `json:"username"`
	Password     string `json:"password"`
	WatchDir     string `json:"watchDir"`
	Concurrency  int    `json:"concurrency"`
	ChunkSizeMB  int    `json:"chunkSizeMB"`
	AutoStart    bool   `json:"autoStart"`
}

func defaultConfig() Config {
	return Config{
		ServerURL:   "https://drayhub.org",
		Username:    "",
		Password:    "",
		WatchDir:    "",
		Concurrency: 4,
		ChunkSizeMB: 1,
		AutoStart:   true,
	}
}

// ── Activity ──────────────────────────────────────────────────────────────────

type ActivityEntry struct {
	Time     string `json:"time"`
	Filename string `json:"filename"`
	Status   string `json:"status"` // "uploading" | "complete" | "error" | "skipped"
	Detail   string `json:"detail,omitempty"`
}

type UploadProgress struct {
	Filename string  `json:"filename"`
	Percent  float64 `json:"percent"`
	SpeedMbps float64 `json:"speedMbps"`
	ChunksDone int   `json:"chunksDone"`
	ChunksTotal int  `json:"chunksTotal"`
}

type ConnectionResult struct {
	OK      bool   `json:"ok"`
	Message string `json:"message"`
}

// ── Persistent state (dedup + resume) ────────────────────────────────────────

type transferState struct {
	FileID          string `json:"fileId"`
	ChunkSize       int    `json:"chunkSize"`
	TotalSize       int64  `json:"totalSize"`
	CompletedChunks []int  `json:"completedChunks,omitempty"`
}

type fileRecord struct {
	Signature string         `json:"signature"`
	Hash      string         `json:"hash"`
	FileID    string         `json:"fileId,omitempty"`
	Transfer  *transferState `json:"transfer,omitempty"`
}

type hashRecord struct {
	FileID string `json:"fileId"`
	Path   string `json:"path"`
}

type appState struct {
	Files  map[string]fileRecord `json:"files"`
	Hashes map[string]hashRecord `json:"hashes"`
}

// ── App ───────────────────────────────────────────────────────────────────────

type App struct {
	ctx context.Context

	cfgPath  string
	statePath string

	cfgMu  sync.RWMutex
	cfg    Config

	stateMu sync.Mutex
	state   appState

	client *http.Client

	watchMu  sync.Mutex
	watcher  *fsnotify.Watcher
	watching bool

	uploadMu sync.Mutex
	pending  map[string]*time.Timer
	inFlight map[string]struct{}

	activityMu sync.Mutex
	activity   []ActivityEntry
}

func NewApp() *App {
	return &App{
		pending:  make(map[string]*time.Timer),
		inFlight: make(map[string]struct{}),
		activity: []ActivityEntry{},
	}
}

func (a *App) startup(ctx context.Context) {
	a.ctx = ctx

	// Resolve config/state paths in OS app-data
	appData, _ := os.UserConfigDir()
	dir := filepath.Join(appData, "SyncForge")
	os.MkdirAll(dir, 0755)
	a.cfgPath = filepath.Join(dir, "config.json")
	a.statePath = filepath.Join(dir, "state.json")

	a.cfg = a.loadConfig()
	a.state = a.loadState()

	a.client = &http.Client{
		Timeout: 90 * time.Second,
		Transport: &http.Transport{
			DialContext:         (&net.Dialer{Timeout: 10 * time.Second, KeepAlive: 30 * time.Second}).DialContext,
			MaxIdleConnsPerHost: 20,
			IdleConnTimeout:     90 * time.Second,
		},
	}

	if a.cfg.AutoStart && a.cfg.WatchDir != "" && a.cfg.Username != "" {
		go a.StartWatching()
	}
}

func (a *App) shutdown(ctx context.Context) {
	a.StopWatching()
}

// ── Bound methods (called from JS) ───────────────────────────────────────────

func (a *App) GetConfig() Config {
	a.cfgMu.RLock()
	defer a.cfgMu.RUnlock()
	return a.cfg
}

func (a *App) SaveConfig(cfg Config) error {
	if cfg.Concurrency < 1 {
		cfg.Concurrency = 1
	}
	if cfg.ChunkSizeMB < 1 {
		cfg.ChunkSizeMB = 1
	}
	cfg.ServerURL = strings.TrimRight(cfg.ServerURL, "/")

	a.cfgMu.Lock()
	a.cfg = cfg
	a.cfgMu.Unlock()

	return a.persistConfig(cfg)
}

func (a *App) TestConnection() ConnectionResult {
	a.cfgMu.RLock()
	cfg := a.cfg
	a.cfgMu.RUnlock()

	if cfg.ServerURL == "" {
		return ConnectionResult{false, "Server URL is required"}
	}
	if cfg.Username == "" || cfg.Password == "" {
		return ConnectionResult{false, "Username and password are required"}
	}

	req, err := http.NewRequest("GET", cfg.ServerURL+"/api/system/ping", nil)
	if err != nil {
		return ConnectionResult{false, "Invalid URL: " + err.Error()}
	}
	req.Header.Set("Authorization", "Basic "+base64.StdEncoding.EncodeToString([]byte(cfg.Username+":"+cfg.Password)))

	resp, err := a.client.Do(req)
	if err != nil {
		return ConnectionResult{false, "Connection failed: " + err.Error()}
	}
	defer resp.Body.Close()

	if resp.StatusCode == 401 {
		return ConnectionResult{false, "Authentication failed — check username/password"}
	}
	if resp.StatusCode >= 400 {
		return ConnectionResult{false, fmt.Sprintf("Server error: HTTP %d", resp.StatusCode)}
	}
	return ConnectionResult{true, "Connected successfully"}
}

func (a *App) BrowseFolder() string {
	dir, err := runtime.OpenDirectoryDialog(a.ctx, runtime.OpenDialogOptions{
		Title: "Select Watch Folder",
	})
	if err != nil || dir == "" {
		return ""
	}
	return dir
}

func (a *App) GetActivity() []ActivityEntry {
	a.activityMu.Lock()
	defer a.activityMu.Unlock()
	// return a copy, most recent first
	out := make([]ActivityEntry, len(a.activity))
	for i, e := range a.activity {
		out[len(a.activity)-1-i] = e
	}
	return out
}

func (a *App) GetWatchStatus() bool {
	a.watchMu.Lock()
	defer a.watchMu.Unlock()
	return a.watching
}

func (a *App) StartWatching() error {
	a.cfgMu.RLock()
	cfg := a.cfg
	a.cfgMu.RUnlock()

	if cfg.WatchDir == "" {
		return fmt.Errorf("no watch directory configured")
	}
	if _, err := os.Stat(cfg.WatchDir); err != nil {
		return fmt.Errorf("watch directory not found: %s", cfg.WatchDir)
	}

	a.watchMu.Lock()
	if a.watching {
		a.watchMu.Unlock()
		return nil
	}

	w, err := fsnotify.NewWatcher()
	if err != nil {
		a.watchMu.Unlock()
		return err
	}
	a.watcher = w
	a.watching = true
	a.watchMu.Unlock()

	runtime.EventsEmit(a.ctx, "watcher:status", true)

	if err := a.addWatchRecursive(w, cfg.WatchDir); err != nil {
		a.StopWatching()
		return err
	}

	go a.runWatcher(w)
	go a.scanExisting(cfg.WatchDir)

	return nil
}

func (a *App) StopWatching() {
	a.watchMu.Lock()
	defer a.watchMu.Unlock()
	if a.watcher != nil {
		a.watcher.Close()
		a.watcher = nil
	}
	a.watching = false
	if a.ctx != nil {
		runtime.EventsEmit(a.ctx, "watcher:status", false)
	}
}

// ── Watcher internals ─────────────────────────────────────────────────────────

func (a *App) addWatchRecursive(w *fsnotify.Watcher, root string) error {
	return filepath.WalkDir(root, func(path string, d os.DirEntry, err error) error {
		if err != nil {
			return nil
		}
		if d.IsDir() {
			return w.Add(path)
		}
		return nil
	})
}

func (a *App) runWatcher(w *fsnotify.Watcher) {
	for {
		select {
		case event, ok := <-w.Events:
			if !ok {
				return
			}
			path := filepath.Clean(event.Name)
			if a.shouldIgnore(path) {
				continue
			}
			if event.Has(fsnotify.Create) {
				if info, err := os.Stat(path); err == nil && info.IsDir() {
					a.addWatchRecursive(w, path)
					continue
				}
			}
			if event.Has(fsnotify.Create) || event.Has(fsnotify.Write) {
				a.scheduleUpload(path)
			}
		case _, ok := <-w.Errors:
			if !ok {
				return
			}
		}
	}
}

func (a *App) scanExisting(root string) {
	filepath.WalkDir(root, func(path string, d os.DirEntry, err error) error {
		if err != nil || d.IsDir() || a.shouldIgnore(path) {
			return nil
		}
		a.scheduleUpload(path)
		return nil
	})
}

func (a *App) shouldIgnore(path string) bool {
	base := strings.ToLower(filepath.Base(path))
	return strings.HasPrefix(base, ".") || base == "desktop.ini" || base == "thumbs.db"
}

func (a *App) scheduleUpload(path string) {
	a.uploadMu.Lock()
	defer a.uploadMu.Unlock()
	if t, ok := a.pending[path]; ok {
		t.Reset(1500 * time.Millisecond)
		return
	}
	a.pending[path] = time.AfterFunc(1500*time.Millisecond, func() {
		a.doUpload(path)
	})
}

// ── Upload ────────────────────────────────────────────────────────────────────

func (a *App) doUpload(path string) {
	a.uploadMu.Lock()
	delete(a.pending, path)
	if _, running := a.inFlight[path]; running {
		a.uploadMu.Unlock()
		return
	}
	a.inFlight[path] = struct{}{}
	a.uploadMu.Unlock()

	defer func() {
		a.uploadMu.Lock()
		delete(a.inFlight, path)
		a.uploadMu.Unlock()
	}()

	info, err := os.Stat(path)
	if err != nil || info.IsDir() || info.Size() == 0 {
		return
	}

	// Wait for file to stabilise
	if err := a.waitStable(path); err != nil {
		return
	}

	info, err = os.Stat(path)
	if err != nil {
		return
	}

	sig := fmt.Sprintf("%d:%d", info.Size(), info.ModTime().UnixNano())
	hash, err := computeHash(path)
	if err != nil {
		return
	}

	// Skip if already uploaded at this sig+hash
	if a.isKnown(path, sig, hash) {
		a.logActivity(filepath.Base(path), "skipped", "already on server")
		return
	}

	// Binary dedup
	if rec, ok := a.lookupHash(hash); ok {
		a.logActivity(filepath.Base(path), "skipped", "duplicate of "+filepath.Base(rec.Path))
		a.markDone(path, sig, hash, rec.FileID)
		return
	}

	a.logActivity(filepath.Base(path), "uploading", "")
	runtime.EventsEmit(a.ctx, "upload:start", filepath.Base(path))

	fileID, err := a.uploadFile(path, sig, hash, info.Size())
	if err != nil {
		a.logActivity(filepath.Base(path), "error", err.Error())
		runtime.EventsEmit(a.ctx, "upload:error", map[string]string{"filename": filepath.Base(path), "error": err.Error()})
		return
	}

	a.logActivity(filepath.Base(path), "complete", "")
	runtime.EventsEmit(a.ctx, "upload:complete", filepath.Base(path))
	_ = fileID
}

func (a *App) waitStable(path string) error {
	var lastSig string
	stable := 0
	for i := 0; i < 12; i++ {
		info, err := os.Stat(path)
		if err != nil {
			return err
		}
		sig := fmt.Sprintf("%d:%d", info.Size(), info.ModTime().UnixNano())
		if sig == lastSig {
			stable++
			if stable >= 3 {
				return nil
			}
		} else {
			lastSig = sig
			stable = 1
		}
		time.Sleep(1500 * time.Millisecond)
	}
	return fmt.Errorf("file never stabilised")
}

func (a *App) uploadFile(filePath, sig, hash string, totalSize int64) (string, error) {
	a.cfgMu.RLock()
	cfg := a.cfg
	a.cfgMu.RUnlock()

	chunkSize := cfg.ChunkSizeMB * 1024 * 1024

	// Init upload (get file ID, existing chunks for resume)
	type initResp struct {
		ID              string `json:"id"`
		Status          string `json:"status"`
		CompletedChunks []int  `json:"completedChunks"`
	}
	body, _ := json.Marshal(map[string]any{
		"originalFilename": filepath.Base(filePath),
		"totalSize":        totalSize,
	})
	req, _ := http.NewRequest("POST", cfg.ServerURL+"/api/files/init", bytes.NewReader(body))
	req.Header.Set("Content-Type", "application/json")
	a.setAuth(req, cfg)

	respBytes, err := a.doReq(req, cfg)
	if err != nil {
		return "", fmt.Errorf("init: %w", err)
	}
	var init initResp
	if err := json.Unmarshal(respBytes, &init); err != nil {
		return "", fmt.Errorf("init parse: %w", err)
	}

	completedSet := map[int]struct{}{}
	for _, idx := range init.CompletedChunks {
		completedSet[idx] = struct{}{}
	}

	totalChunks := int((totalSize + int64(chunkSize) - 1) / int64(chunkSize))

	// Upload chunks in parallel
	type job struct {
		index int
		data  []byte
	}

	file, err := os.Open(filePath)
	if err != nil {
		return "", err
	}
	defer file.Close()

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	jobs := make(chan job, cfg.Concurrency)
	errCh := make(chan error, 1)
	var wg sync.WaitGroup
	var doneChunks int64
	var mu sync.Mutex
	t0 := time.Now()

	for w := 0; w < cfg.Concurrency; w++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for {
				select {
				case <-ctx.Done():
					return
				case j, ok := <-jobs:
					if !ok {
						return
					}
					if err := a.uploadChunk(init.ID, j.index, j.data, cfg); err != nil {
						select {
						case errCh <- fmt.Errorf("chunk %d: %w", j.index, err):
						default:
						}
						cancel()
						return
					}
					mu.Lock()
					doneChunks++
					done := doneChunks
					mu.Unlock()

					elapsed := time.Since(t0).Seconds()
					speedMbps := 0.0
					if elapsed > 0 {
						speedMbps = float64(done*int64(chunkSize)) / elapsed / 1e6 * 8
					}
					runtime.EventsEmit(a.ctx, "upload:progress", UploadProgress{
						Filename:    filepath.Base(filePath),
						Percent:     float64(done) / float64(totalChunks) * 100,
						SpeedMbps:   speedMbps,
						ChunksDone:  int(done),
						ChunksTotal: totalChunks,
					})
				}
			}
		}()
	}

	for i := 0; i < totalChunks; i++ {
		if _, done := completedSet[i]; done {
			continue
		}
		if ctx.Err() != nil {
			break
		}
		offset := int64(i * chunkSize)
		length := int64(chunkSize)
		if offset+length > totalSize {
			length = totalSize - offset
		}
		data := make([]byte, length)
		n, err := file.ReadAt(data, offset)
		if err != nil && err != io.EOF {
			cancel()
			break
		}
		data = data[:n]
		select {
		case jobs <- job{i, data}:
		case <-ctx.Done():
		}
	}
	close(jobs)
	wg.Wait()

	select {
	case err := <-errCh:
		return "", err
	default:
	}

	// Complete (async on server — returns 202)
	req2, _ := http.NewRequest("POST", fmt.Sprintf("%s/api/files/%s/complete", cfg.ServerURL, init.ID), nil)
	a.setAuth(req2, cfg)
	if _, err := a.doReq(req2, cfg); err != nil {
		return "", fmt.Errorf("complete: %w", err)
	}

	a.markDone(filePath, sig, hash, init.ID)
	return init.ID, nil
}

func (a *App) uploadChunk(fileID string, index int, data []byte, cfg Config) error {
	buf := &bytes.Buffer{}
	w := multipart.NewWriter(buf)
	part, err := w.CreateFormFile("chunk", "chunk.bin")
	if err != nil {
		return err
	}
	part.Write(data)
	w.Close()

	req, err := http.NewRequest("POST",
		fmt.Sprintf("%s/api/files/%s/chunk?chunkIndex=%d", cfg.ServerURL, fileID, index),
		buf)
	if err != nil {
		return err
	}
	req.Header.Set("Content-Type", w.FormDataContentType())
	a.setAuth(req, cfg)

	for attempt := 1; attempt <= 4; attempt++ {
		cloned := req.Clone(context.Background())
		cloned.Body = io.NopCloser(bytes.NewReader(buf.Bytes()))
		resp, err := a.client.Do(cloned)
		if err == nil {
			io.Copy(io.Discard, resp.Body)
			resp.Body.Close()
			if resp.StatusCode >= 200 && resp.StatusCode < 300 {
				return nil
			}
			if resp.StatusCode < 500 {
				return fmt.Errorf("HTTP %d", resp.StatusCode)
			}
		}
		time.Sleep(time.Duration(attempt) * time.Second)
	}
	return fmt.Errorf("chunk %d failed after retries", index)
}

func (a *App) doReq(req *http.Request, cfg Config) ([]byte, error) {
	for attempt := 1; attempt <= 3; attempt++ {
		cloned := req.Clone(context.Background())
		a.setAuth(cloned, cfg)
		if req.Body != nil && req.GetBody != nil {
			body, _ := req.GetBody()
			cloned.Body = body
		}
		resp, err := a.client.Do(cloned)
		if err == nil {
			b, _ := io.ReadAll(resp.Body)
			resp.Body.Close()
			if resp.StatusCode >= 200 && resp.StatusCode < 300 {
				return b, nil
			}
			if resp.StatusCode < 500 {
				return nil, fmt.Errorf("HTTP %d: %s", resp.StatusCode, strings.TrimSpace(string(b)))
			}
		}
		if attempt < 3 {
			time.Sleep(time.Duration(attempt) * time.Second)
		}
	}
	return nil, fmt.Errorf("request failed after retries")
}

func (a *App) setAuth(req *http.Request, cfg Config) {
	req.Header.Set("Authorization", "Basic "+base64.StdEncoding.EncodeToString([]byte(cfg.Username+":"+cfg.Password)))
}

// ── State helpers ─────────────────────────────────────────────────────────────

func (a *App) isKnown(path, sig, hash string) bool {
	a.stateMu.Lock()
	defer a.stateMu.Unlock()
	r, ok := a.state.Files[path]
	return ok && r.Transfer == nil && r.Signature == sig && r.Hash == hash
}

func (a *App) lookupHash(hash string) (hashRecord, bool) {
	a.stateMu.Lock()
	defer a.stateMu.Unlock()
	r, ok := a.state.Hashes[hash]
	return r, ok
}

func (a *App) markDone(path, sig, hash, fileID string) {
	a.stateMu.Lock()
	defer a.stateMu.Unlock()
	a.state.Files[path] = fileRecord{Signature: sig, Hash: hash, FileID: fileID}
	a.state.Hashes[hash] = hashRecord{FileID: fileID, Path: path}
	a.saveStateLocked()
}

func (a *App) saveStateLocked() {
	data, err := json.MarshalIndent(a.state, "", "  ")
	if err != nil {
		return
	}
	tmp := a.statePath + ".tmp"
	os.WriteFile(tmp, data, 0644)
	os.Rename(tmp, a.statePath)
}

func (a *App) loadState() appState {
	s := appState{Files: map[string]fileRecord{}, Hashes: map[string]hashRecord{}}
	data, err := os.ReadFile(a.statePath)
	if err != nil || len(bytes.TrimSpace(data)) == 0 {
		return s
	}
	json.Unmarshal(data, &s)
	if s.Files == nil {
		s.Files = map[string]fileRecord{}
	}
	if s.Hashes == nil {
		s.Hashes = map[string]hashRecord{}
	}
	return s
}

// ── Config persistence ────────────────────────────────────────────────────────

func (a *App) loadConfig() Config {
	cfg := defaultConfig()
	data, err := os.ReadFile(a.cfgPath)
	if err != nil || len(bytes.TrimSpace(data)) == 0 {
		return cfg
	}
	json.Unmarshal(data, &cfg)
	return cfg
}

func (a *App) persistConfig(cfg Config) error {
	data, err := json.MarshalIndent(cfg, "", "  ")
	if err != nil {
		return err
	}
	return os.WriteFile(a.cfgPath, data, 0644)
}

// ── Activity log ──────────────────────────────────────────────────────────────

func (a *App) logActivity(filename, status, detail string) {
	entry := ActivityEntry{
		Time:     time.Now().Format("15:04:05"),
		Filename: filename,
		Status:   status,
		Detail:   detail,
	}
	a.activityMu.Lock()
	a.activity = append(a.activity, entry)
	if len(a.activity) > 200 {
		a.activity = a.activity[len(a.activity)-200:]
	}
	a.activityMu.Unlock()

	if a.ctx != nil {
		runtime.EventsEmit(a.ctx, "activity:new", entry)
	}
	log.Printf("[%s] %s — %s %s", status, filename, detail, entry.Time)
}

// ── Utilities ─────────────────────────────────────────────────────────────────

func computeHash(path string) (string, error) {
	f, err := os.Open(path)
	if err != nil {
		return "", err
	}
	defer f.Close()
	h := sha256.New()
	io.Copy(h, f)
	return hex.EncodeToString(h.Sum(nil)), nil
}

