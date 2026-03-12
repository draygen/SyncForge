package main

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"mime/multipart"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/fsnotify/fsnotify"
)

type Config struct {
	ConfigFile       string
	ServerURL        string
	WatchDir         string
	Username         string
	Password         string
	ChunkSize        int
	Concurrency      int
	StableChecks     int
	StableInterval   time.Duration
	RetryAttempts    int
	RetryDelay       time.Duration
	HTTPTimeout      time.Duration
	StateFile        string
	ArchiveDir       string
	ArchiveOnSuccess bool
}

type configFilePayload struct {
	ServerURL        string `json:"server_url"`
	WatchDir         string `json:"watch_dir"`
	Username         string `json:"username"`
	Password         string `json:"password"`
	ChunkSizeMB      int    `json:"chunk_size_mb"`
	Concurrency      int    `json:"concurrency"`
	StableChecks     int    `json:"stable_checks"`
	StableIntervalMS int    `json:"stable_interval_ms"`
	RetryAttempts    int    `json:"retry_attempts"`
	RetryDelayMS     int    `json:"retry_delay_ms"`
	HTTPTimeoutSec   int    `json:"http_timeout_sec"`
	StateFile        string `json:"state_file"`
	ArchiveDir       string `json:"archive_dir"`
	ArchiveOnSuccess *bool  `json:"archive_on_success"`
}

type InitResponse struct {
	ID               string `json:"id"`
	OriginalFilename string `json:"originalFilename"`
	Status           string `json:"status"`
	TotalSize        int64  `json:"totalSize"`
	UploadedSize     int64  `json:"uploadedSize"`
	CompletedChunks  []int  `json:"completedChunks"`
}

type chunkJob struct {
	index int
	data  []byte
}

type transferState struct {
	FileID          string `json:"fileId"`
	ChunkSize       int    `json:"chunkSize"`
	TotalSize       int64  `json:"totalSize"`
	CompletedChunks []int  `json:"completedChunks,omitempty"`
	StartedAt       string `json:"startedAt,omitempty"`
}

type fileState struct {
	Signature    string         `json:"signature"`
	Hash         string         `json:"hash"`
	ServerFileID string         `json:"serverFileId,omitempty"`
	UploadedAt   string         `json:"uploadedAt,omitempty"`
	ArchivedPath string         `json:"archivedPath,omitempty"`
	Transfer     *transferState `json:"transfer,omitempty"`
}

type hashState struct {
	ServerFileID string `json:"serverFileId,omitempty"`
	Path         string `json:"path"`
	UploadedAt   string `json:"uploadedAt,omitempty"`
}

type persistentState struct {
	Files  map[string]fileState `json:"files"`
	Hashes map[string]hashState `json:"hashes"`
}

type uploadSession struct {
	fileID       string
	resumed      bool
	completedSet map[int]struct{}
	totalChunks  int
}

type uploadManager struct {
	cfg    Config
	client *http.Client

	mu       sync.Mutex
	pending  map[string]*time.Timer
	inFlight map[string]struct{}

	stateMu sync.Mutex
	state   persistentState
}

func main() {
	cfg, err := loadConfig()
	if err != nil {
		log.Fatalf("failed to load config: %v", err)
	}

	if err := os.MkdirAll(cfg.WatchDir, 0755); err != nil {
		log.Fatalf("failed to create watch directory: %v", err)
	}
	if cfg.ArchiveOnSuccess {
		if err := os.MkdirAll(cfg.ArchiveDir, 0755); err != nil {
			log.Fatalf("failed to create archive directory: %v", err)
		}
	}

	manager, err := newUploadManager(cfg)
	if err != nil {
		log.Fatalf("failed to initialize manager: %v", err)
	}

	watcher, err := fsnotify.NewWatcher()
	if err != nil {
		log.Fatalf("failed to create watcher: %v", err)
	}
	defer watcher.Close()

	if err := addWatchRecursive(watcher, cfg.WatchDir); err != nil {
		log.Fatalf("failed to add watcher: %v", err)
	}

	log.Printf("AutoPost client started")
	log.Printf("Config:    %s", cfg.ConfigFile)
	log.Printf("Watching:  %s", cfg.WatchDir)
	log.Printf("Archiving: %t", cfg.ArchiveOnSuccess)
	if cfg.ArchiveOnSuccess {
		log.Printf("Archive:   %s", cfg.ArchiveDir)
	}
	log.Printf("State:     %s", cfg.StateFile)
	log.Printf("Server:    %s", cfg.ServerURL)
	log.Printf("Chunks:    %d bytes", cfg.ChunkSize)
	log.Printf("Workers:   %d", cfg.Concurrency)

	go processEvents(watcher, manager)

	if err := scanExistingFiles(cfg.WatchDir, manager); err != nil {
		log.Printf("startup scan failed: %v", err)
	}

	select {}
}

func loadConfig() (Config, error) {
	defaults := Config{
		ConfigFile:       "./autopost-client.json",
		ServerURL:        "http://localhost:8888",
		WatchDir:         "./sync_folder",
		Username:         "admin",
		Password:         "hello5",
		ChunkSize:        1 * 1024 * 1024,
		Concurrency:      4,
		StableChecks:     3,
		StableInterval:   1500 * time.Millisecond,
		RetryAttempts:    4,
		RetryDelay:       1 * time.Second,
		HTTPTimeout:      60 * time.Second,
		StateFile:        "./autopost-state.json",
		ArchiveDir:       "./archive",
		ArchiveOnSuccess: true,
	}

	cfgPath := getEnv("MFT_CONFIG_FILE", defaults.ConfigFile)
	cfgPathAbs, err := filepath.Abs(cfgPath)
	if err == nil {
		cfgPath = cfgPathAbs
	}
	defaults.ConfigFile = cfgPath

	cfg := defaults
	if err := applyConfigFile(&cfg, cfg.ConfigFile); err != nil {
		return cfg, err
	}
	applyEnvOverrides(&cfg)

	cfg.ServerURL = strings.TrimRight(cfg.ServerURL, "/")
	cfg.WatchDir = mustAbs(cfg.WatchDir)
	cfg.StateFile = mustAbs(cfg.StateFile)
	cfg.ArchiveDir = mustAbs(cfg.ArchiveDir)
	cfg.ConfigFile = mustAbs(cfg.ConfigFile)

	if cfg.Concurrency < 1 {
		cfg.Concurrency = 1
	}
	if cfg.StableChecks < 2 {
		cfg.StableChecks = 2
	}
	if cfg.ChunkSize < 1 {
		cfg.ChunkSize = 1 * 1024 * 1024
	}
	if cfg.RetryAttempts < 1 {
		cfg.RetryAttempts = 1
	}
	if cfg.StableInterval < 250*time.Millisecond {
		cfg.StableInterval = 250 * time.Millisecond
	}
	if cfg.RetryDelay < 200*time.Millisecond {
		cfg.RetryDelay = 200 * time.Millisecond
	}
	if cfg.HTTPTimeout < 5*time.Second {
		cfg.HTTPTimeout = 5 * time.Second
	}

	return cfg, nil
}

func applyConfigFile(cfg *Config, path string) error {
	data, err := os.ReadFile(path)
	if err != nil {
		if os.IsNotExist(err) {
			return nil
		}
		return err
	}
	if len(bytes.TrimSpace(data)) == 0 {
		return nil
	}

	var payload configFilePayload
	if err := json.Unmarshal(data, &payload); err != nil {
		return fmt.Errorf("parse config file %s: %w", path, err)
	}

	if payload.ServerURL != "" {
		cfg.ServerURL = payload.ServerURL
	}
	if payload.WatchDir != "" {
		cfg.WatchDir = payload.WatchDir
	}
	if payload.Username != "" {
		cfg.Username = payload.Username
	}
	if payload.Password != "" {
		cfg.Password = payload.Password
	}
	if payload.ChunkSizeMB > 0 {
		cfg.ChunkSize = payload.ChunkSizeMB * 1024 * 1024
	}
	if payload.Concurrency > 0 {
		cfg.Concurrency = payload.Concurrency
	}
	if payload.StableChecks > 0 {
		cfg.StableChecks = payload.StableChecks
	}
	if payload.StableIntervalMS > 0 {
		cfg.StableInterval = time.Duration(payload.StableIntervalMS) * time.Millisecond
	}
	if payload.RetryAttempts > 0 {
		cfg.RetryAttempts = payload.RetryAttempts
	}
	if payload.RetryDelayMS > 0 {
		cfg.RetryDelay = time.Duration(payload.RetryDelayMS) * time.Millisecond
	}
	if payload.HTTPTimeoutSec > 0 {
		cfg.HTTPTimeout = time.Duration(payload.HTTPTimeoutSec) * time.Second
	}
	if payload.StateFile != "" {
		cfg.StateFile = payload.StateFile
	}
	if payload.ArchiveDir != "" {
		cfg.ArchiveDir = payload.ArchiveDir
	}
	if payload.ArchiveOnSuccess != nil {
		cfg.ArchiveOnSuccess = *payload.ArchiveOnSuccess
	}
	return nil
}

func applyEnvOverrides(cfg *Config) {
	cfg.ServerURL = getEnv("MFT_SERVER_URL", cfg.ServerURL)
	cfg.WatchDir = getEnv("MFT_WATCH_DIR", cfg.WatchDir)
	cfg.Username = getEnv("MFT_USERNAME", cfg.Username)
	cfg.Password = getEnv("MFT_PASSWORD", cfg.Password)
	cfg.StateFile = getEnv("MFT_STATE_FILE", cfg.StateFile)
	cfg.ArchiveDir = getEnv("MFT_ARCHIVE_DIR", cfg.ArchiveDir)
	cfg.ArchiveOnSuccess = getEnvBool("MFT_ARCHIVE_ON_SUCCESS", cfg.ArchiveOnSuccess)
	cfg.ChunkSize = getEnvInt("MFT_CHUNK_SIZE_MB", cfg.ChunkSize/(1024*1024)) * 1024 * 1024
	cfg.Concurrency = getEnvInt("MFT_CONCURRENCY", cfg.Concurrency)
	cfg.StableChecks = getEnvInt("MFT_STABLE_CHECKS", cfg.StableChecks)
	cfg.StableInterval = time.Duration(getEnvInt("MFT_STABLE_INTERVAL_MS", int(cfg.StableInterval/time.Millisecond))) * time.Millisecond
	cfg.RetryAttempts = getEnvInt("MFT_RETRY_ATTEMPTS", cfg.RetryAttempts)
	cfg.RetryDelay = time.Duration(getEnvInt("MFT_RETRY_DELAY_MS", int(cfg.RetryDelay/time.Millisecond))) * time.Millisecond
	cfg.HTTPTimeout = time.Duration(getEnvInt("MFT_HTTP_TIMEOUT_SEC", int(cfg.HTTPTimeout/time.Second))) * time.Second
}

func mustAbs(path string) string {
	abs, err := filepath.Abs(path)
	if err != nil {
		return path
	}
	return abs
}

func newUploadManager(cfg Config) (*uploadManager, error) {
	state, err := loadState(cfg.StateFile)
	if err != nil {
		return nil, err
	}

	transport := &http.Transport{
		Proxy:               http.ProxyFromEnvironment,
		DialContext:         (&net.Dialer{Timeout: 10 * time.Second, KeepAlive: 30 * time.Second}).DialContext,
		ForceAttemptHTTP2:   true,
		MaxIdleConns:        100,
		MaxIdleConnsPerHost: 20,
		IdleConnTimeout:     90 * time.Second,
	}

	return &uploadManager{
		cfg: cfg,
		client: &http.Client{
			Timeout:   cfg.HTTPTimeout,
			Transport: transport,
		},
		pending:  make(map[string]*time.Timer),
		inFlight: make(map[string]struct{}),
		state:    state,
	}, nil
}

func processEvents(watcher *fsnotify.Watcher, manager *uploadManager) {
	for {
		select {
		case event, ok := <-watcher.Events:
			if !ok {
				return
			}
			manager.handleEvent(watcher, event)
		case err, ok := <-watcher.Errors:
			if !ok {
				return
			}
			log.Printf("watcher error: %v", err)
		}
	}
}

func (m *uploadManager) handleEvent(watcher *fsnotify.Watcher, event fsnotify.Event) {
	path := filepath.Clean(event.Name)
	if m.shouldIgnorePath(path) {
		return
	}

	if event.Has(fsnotify.Create) {
		if info, err := os.Stat(path); err == nil && info.IsDir() {
			if err := addWatchRecursive(watcher, path); err != nil {
				log.Printf("failed adding nested watch for %s: %v", path, err)
			}
			return
		}
	}

	if event.Has(fsnotify.Create) || event.Has(fsnotify.Write) || event.Has(fsnotify.Rename) {
		m.scheduleUpload(path)
	}
}

func scanExistingFiles(root string, manager *uploadManager) error {
	return filepath.WalkDir(root, func(path string, d os.DirEntry, err error) error {
		if err != nil {
			return err
		}
		if d.IsDir() {
			if manager.shouldIgnorePath(path) && path != root {
				return filepath.SkipDir
			}
			return nil
		}
		if manager.shouldIgnorePath(path) {
			return nil
		}
		manager.scheduleUpload(path)
		return nil
	})
}

func addWatchRecursive(watcher *fsnotify.Watcher, root string) error {
	return filepath.WalkDir(root, func(path string, d os.DirEntry, err error) error {
		if err != nil {
			return err
		}
		if d.IsDir() {
			if watchErr := watcher.Add(path); watchErr != nil {
				return fmt.Errorf("watch %s: %w", path, watchErr)
			}
		}
		return nil
	})
}

func (m *uploadManager) shouldIgnorePath(path string) bool {
	cleanPath := filepath.Clean(path)
	if cleanPath == filepath.Clean(m.cfg.StateFile) || cleanPath == filepath.Clean(m.cfg.ConfigFile) {
		return true
	}
	if m.cfg.ArchiveOnSuccess && isSubPath(m.cfg.ArchiveDir, cleanPath) {
		return true
	}
	return false
}

func (m *uploadManager) scheduleUpload(path string) {
	m.mu.Lock()
	defer m.mu.Unlock()

	if timer, ok := m.pending[path]; ok {
		timer.Reset(m.cfg.StableInterval)
		return
	}

	m.pending[path] = time.AfterFunc(m.cfg.StableInterval, func() {
		m.startUpload(path)
	})
}

func (m *uploadManager) startUpload(path string) {
	m.mu.Lock()
	delete(m.pending, path)
	if _, running := m.inFlight[path]; running {
		m.mu.Unlock()
		return
	}
	m.inFlight[path] = struct{}{}
	m.mu.Unlock()

	defer func() {
		m.mu.Lock()
		delete(m.inFlight, path)
		m.mu.Unlock()
	}()

	readyPath, sig, err := m.waitForReady(path)
	if err != nil {
		log.Printf("skipping %s: %v", path, err)
		return
	}

	info, err := os.Stat(readyPath)
	if err != nil {
		log.Printf("stat failed for %s: %v", readyPath, err)
		return
	}

	hash, err := computeFileHash(readyPath)
	if err != nil {
		log.Printf("hash failed for %s: %v", readyPath, err)
		return
	}

	if m.isKnownCompletedState(readyPath, sig, hash) {
		log.Printf("no changes detected, skipping: %s", filepath.Base(readyPath))
		return
	}

	if existing, ok := m.lookupHash(hash); ok {
		log.Printf("binary duplicate detected, skipping upload: %s -> %s", filepath.Base(readyPath), existing.Path)
		archivedPath, archiveErr := m.archiveFile(readyPath)
		if archiveErr != nil {
			log.Printf("archive failed for duplicate %s: %v", filepath.Base(readyPath), archiveErr)
		}
		m.recordCompletedState(readyPath, fileState{
			Signature:    sig,
			Hash:         hash,
			ServerFileID: existing.ServerFileID,
			UploadedAt:   existing.UploadedAt,
			ArchivedPath: archivedPath,
		})
		return
	}

	session, err := m.prepareUploadSession(readyPath, sig, hash, info.Size())
	if err != nil {
		log.Printf("session setup failed for %s: %v", filepath.Base(readyPath), err)
		return
	}

	fileID, err := m.uploadFile(readyPath, sig, hash, session, info.Size())
	if err != nil {
		log.Printf("upload failed for %s: %v", filepath.Base(readyPath), err)
		return
	}

	archivedPath, archiveErr := m.archiveFile(readyPath)
	if archiveErr != nil {
		log.Printf("archive failed for %s: %v", filepath.Base(readyPath), archiveErr)
	}

	uploadedAt := time.Now().Format(time.RFC3339)
	m.recordCompletedState(readyPath, fileState{
		Signature:    sig,
		Hash:         hash,
		ServerFileID: fileID,
		UploadedAt:   uploadedAt,
		ArchivedPath: archivedPath,
	})
	m.recordHash(hash, hashState{
		ServerFileID: fileID,
		Path:         readyPath,
		UploadedAt:   uploadedAt,
	})
}

func (m *uploadManager) waitForReady(path string) (string, string, error) {
	var lastSig string
	stableCount := 0

	for attempt := 0; attempt < m.cfg.StableChecks*4; attempt++ {
		info, err := os.Stat(path)
		if err != nil {
			if os.IsNotExist(err) {
				if m.hasRecordedPath(path) {
					return "", "", fmt.Errorf("file already archived or removed")
				}
				return "", "", fmt.Errorf("file disappeared before upload")
			}
			time.Sleep(m.cfg.StableInterval)
			continue
		}
		if info.IsDir() {
			return "", "", fmt.Errorf("path is a directory")
		}
		if info.Size() == 0 {
			return "", "", fmt.Errorf("zero-byte file")
		}

		sig := fmt.Sprintf("%d:%d", info.Size(), info.ModTime().UnixNano())
		if sig == lastSig {
			stableCount++
			if stableCount >= m.cfg.StableChecks {
				return path, sig, nil
			}
		} else {
			lastSig = sig
			stableCount = 1
		}
		time.Sleep(m.cfg.StableInterval)
	}

	return "", "", fmt.Errorf("file never stabilized")
}

func (m *uploadManager) prepareUploadSession(path, signature, hash string, totalSize int64) (uploadSession, error) {
	totalChunks := chunkCount(totalSize, m.cfg.ChunkSize)
	initResp, err := m.initUpload(filepath.Base(path), totalSize)
	if err != nil {
		return uploadSession{}, fmt.Errorf("init upload: %w", err)
	}

	completedSet := make(map[int]struct{}, len(initResp.CompletedChunks))
	for _, idx := range initResp.CompletedChunks {
		completedSet[idx] = struct{}{}
	}

	if len(completedSet) > 0 {
		log.Printf("resuming upload: %s (%d/%d chunks)", filepath.Base(path), len(completedSet), totalChunks)
	}

	transfer := &transferState{
		FileID:          initResp.ID,
		ChunkSize:       m.cfg.ChunkSize,
		TotalSize:       totalSize,
		CompletedChunks: initResp.CompletedChunks,
		StartedAt:       time.Now().Format(time.RFC3339),
	}
	m.recordTransferState(path, fileState{
		Signature: signature,
		Hash:      hash,
		Transfer:  transfer,
	})

	return uploadSession{
		fileID:       initResp.ID,
		resumed:      len(completedSet) > 0,
		completedSet: completedSet,
		totalChunks:  totalChunks,
	}, nil
}

func (m *uploadManager) uploadFile(filePath, signature, hash string, session uploadSession, totalSize int64) (string, error) {
	file, err := os.Open(filePath)
	if err != nil {
		return "", err
	}
	defer file.Close()

	if !session.resumed {
		log.Printf("starting upload: %s (%d bytes)", filepath.Base(filePath), totalSize)
	}

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	jobs := make(chan chunkJob, m.cfg.Concurrency)
	errCh := make(chan error, 1)
	var wg sync.WaitGroup

	for worker := 0; worker < m.cfg.Concurrency; worker++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for {
				select {
				case <-ctx.Done():
					return
				case job, ok := <-jobs:
					if !ok {
						return
					}
					if chunkErr := m.uploadChunk(session.fileID, job.index, job.data); chunkErr != nil {
						select {
						case errCh <- fmt.Errorf("chunk %d: %w", job.index, chunkErr):
						default:
						}
						cancel()
						return
					}
					m.markChunkComplete(filePath, signature, hash, totalSize, session.fileID, job.index)
				}
			}
		}()
	}

	for chunkIndex := 0; chunkIndex < session.totalChunks; chunkIndex++ {
		if _, done := session.completedSet[chunkIndex]; done {
			continue
		}
		if ctx.Err() != nil {
			break
		}

		offset := int64(chunkIndex * m.cfg.ChunkSize)
		length := minInt64(int64(m.cfg.ChunkSize), totalSize-offset)
		data := make([]byte, length)
		n, readErr := file.ReadAt(data, offset)
		if readErr != nil && !errors.Is(readErr, io.EOF) {
			cancel()
			close(jobs)
			wg.Wait()
			return "", fmt.Errorf("read chunk %d: %w", chunkIndex, readErr)
		}
		if int64(n) != length {
			cancel()
			close(jobs)
			wg.Wait()
			return "", fmt.Errorf("short read on chunk %d", chunkIndex)
		}

		select {
		case jobs <- chunkJob{index: chunkIndex, data: data}:
		case <-ctx.Done():
			break
		}
	}

	close(jobs)
	wg.Wait()

	select {
	case workerErr := <-errCh:
		return "", workerErr
	default:
	}

	if err := m.completeUpload(session.fileID); err != nil {
		return "", fmt.Errorf("complete upload: %w", err)
	}

	log.Printf("transfer complete: %s", filepath.Base(filePath))
	return session.fileID, nil
}

func (m *uploadManager) initUpload(filename string, totalSize int64) (InitResponse, error) {
	payload := map[string]any{
		"originalFilename": filename,
		"totalSize":        totalSize,
	}
	body, err := json.Marshal(payload)
	if err != nil {
		return InitResponse{}, err
	}

	req, err := http.NewRequest(http.MethodPost, m.cfg.ServerURL+"/api/files/init", bytes.NewReader(body))
	if err != nil {
		return InitResponse{}, err
	}
	req.Header.Set("Content-Type", "application/json")

	respBody, err := m.doRequest(req)
	if err != nil {
		return InitResponse{}, err
	}

	var initResp InitResponse
	if err := json.Unmarshal(respBody, &initResp); err != nil {
		return InitResponse{}, err
	}
	if initResp.ID == "" {
		return InitResponse{}, fmt.Errorf("server returned empty file id")
	}
	return initResp, nil
}

func (m *uploadManager) uploadChunk(fileID string, chunkIndex int, chunkData []byte) error {
	body := &bytes.Buffer{}
	writer := multipart.NewWriter(body)

	part, err := writer.CreateFormFile("chunk", "chunk.bin")
	if err != nil {
		return err
	}
	if _, err := part.Write(chunkData); err != nil {
		return err
	}
	if err := writer.Close(); err != nil {
		return err
	}

	req, err := http.NewRequest(
		http.MethodPost,
		fmt.Sprintf("%s/api/files/%s/chunk?chunkIndex=%d", m.cfg.ServerURL, fileID, chunkIndex),
		body,
	)
	if err != nil {
		return err
	}
	req.Header.Set("Content-Type", writer.FormDataContentType())

	_, err = m.doRequest(req)
	return err
}

func (m *uploadManager) completeUpload(fileID string) error {
	req, err := http.NewRequest(http.MethodPost, fmt.Sprintf("%s/api/files/%s/complete", m.cfg.ServerURL, fileID), nil)
	if err != nil {
		return err
	}
	_, err = m.doRequest(req)
	return err
}

func (m *uploadManager) doRequest(req *http.Request) ([]byte, error) {
	var lastErr error

	for attempt := 1; attempt <= m.cfg.RetryAttempts; attempt++ {
		cloned := req.Clone(context.Background())
		setBasicAuth(cloned, m.cfg.Username, m.cfg.Password)

		if req.GetBody != nil {
			body, err := req.GetBody()
			if err != nil {
				return nil, err
			}
			cloned.Body = body
		}

		resp, err := m.client.Do(cloned)
		if err == nil {
			respBody, readErr := io.ReadAll(resp.Body)
			resp.Body.Close()
			if readErr != nil {
				return nil, readErr
			}
			if resp.StatusCode >= 200 && resp.StatusCode < 300 {
				return respBody, nil
			}
			lastErr = fmt.Errorf("status %d: %s", resp.StatusCode, strings.TrimSpace(string(respBody)))
			if resp.StatusCode < 500 && resp.StatusCode != http.StatusTooManyRequests {
				return nil, lastErr
			}
		} else {
			lastErr = err
		}

		if attempt < m.cfg.RetryAttempts {
			time.Sleep(time.Duration(attempt) * m.cfg.RetryDelay)
		}
	}

	return nil, lastErr
}

func (m *uploadManager) archiveFile(sourcePath string) (string, error) {
	if !m.cfg.ArchiveOnSuccess {
		return "", nil
	}

	rel, err := filepath.Rel(m.cfg.WatchDir, sourcePath)
	if err != nil {
		rel = filepath.Base(sourcePath)
	}

	targetPath := filepath.Join(m.cfg.ArchiveDir, rel)
	if err := os.MkdirAll(filepath.Dir(targetPath), 0755); err != nil {
		return "", err
	}

	targetPath = uniqueArchivePath(targetPath)
	if err := os.Rename(sourcePath, targetPath); err != nil {
		return "", err
	}
	return targetPath, nil
}

func uniqueArchivePath(path string) string {
	if _, err := os.Stat(path); os.IsNotExist(err) {
		return path
	}
	ext := filepath.Ext(path)
	base := strings.TrimSuffix(path, ext)
	stamp := time.Now().Format("20060102-150405")
	return base + "-" + stamp + ext
}

func (m *uploadManager) isKnownCompletedState(path, signature, hash string) bool {
	entry, ok := m.getFileState(path)
	if !ok {
		return false
	}
	return entry.Transfer == nil && entry.Signature == signature && entry.Hash == hash
}

func (m *uploadManager) getFileState(path string) (fileState, bool) {
	m.stateMu.Lock()
	defer m.stateMu.Unlock()
	entry, ok := m.state.Files[path]
	return entry, ok
}

func (m *uploadManager) lookupHash(hash string) (hashState, bool) {
	m.stateMu.Lock()
	defer m.stateMu.Unlock()
	entry, ok := m.state.Hashes[hash]
	return entry, ok
}

func (m *uploadManager) hasRecordedPath(path string) bool {
	m.stateMu.Lock()
	defer m.stateMu.Unlock()
	_, ok := m.state.Files[path]
	return ok
}

func (m *uploadManager) recordTransferState(path string, entry fileState) {
	m.stateMu.Lock()
	defer m.stateMu.Unlock()
	m.state.Files[path] = entry
	m.saveStateLocked()
}

func (m *uploadManager) markChunkComplete(path, signature, hash string, totalSize int64, fileID string, chunkIndex int) {
	m.stateMu.Lock()
	defer m.stateMu.Unlock()

	entry := m.state.Files[path]
	if entry.Transfer == nil {
		entry.Transfer = &transferState{
			FileID:          fileID,
			ChunkSize:       m.cfg.ChunkSize,
			TotalSize:       totalSize,
			CompletedChunks: []int{},
			StartedAt:       time.Now().Format(time.RFC3339),
		}
	}
	entry.Signature = signature
	entry.Hash = hash
	entry.Transfer.FileID = fileID
	entry.Transfer.ChunkSize = m.cfg.ChunkSize
	entry.Transfer.TotalSize = totalSize
	entry.Transfer.CompletedChunks = appendUniqueChunk(entry.Transfer.CompletedChunks, chunkIndex)
	m.state.Files[path] = entry
	m.saveStateLocked()
}

func (m *uploadManager) recordCompletedState(path string, entry fileState) {
	m.stateMu.Lock()
	defer m.stateMu.Unlock()
	entry.Transfer = nil
	m.state.Files[path] = entry
	m.saveStateLocked()
}

func (m *uploadManager) recordHash(hash string, entry hashState) {
	m.stateMu.Lock()
	defer m.stateMu.Unlock()
	m.state.Hashes[hash] = entry
	m.saveStateLocked()
}

func (m *uploadManager) saveStateLocked() {
	if err := saveState(m.cfg.StateFile, m.state); err != nil {
		log.Printf("failed to save state: %v", err)
	}
}

func appendUniqueChunk(chunks []int, chunk int) []int {
	for _, existing := range chunks {
		if existing == chunk {
			return chunks
		}
	}
	chunks = append(chunks, chunk)
	sort.Ints(chunks)
	return chunks
}

func loadState(path string) (persistentState, error) {
	state := persistentState{
		Files:  map[string]fileState{},
		Hashes: map[string]hashState{},
	}

	data, err := os.ReadFile(path)
	if err != nil {
		if os.IsNotExist(err) {
			return state, nil
		}
		return state, err
	}
	if len(bytes.TrimSpace(data)) == 0 {
		return state, nil
	}
	if err := json.Unmarshal(data, &state); err != nil {
		return state, err
	}
	if state.Files == nil {
		state.Files = map[string]fileState{}
	}
	if state.Hashes == nil {
		state.Hashes = map[string]hashState{}
	}
	return state, nil
}

func saveState(path string, state persistentState) error {
	if err := os.MkdirAll(filepath.Dir(path), 0755); err != nil {
		return err
	}
	data, err := json.MarshalIndent(state, "", "  ")
	if err != nil {
		return err
	}
	tmpPath := path + ".tmp"
	if err := os.WriteFile(tmpPath, data, 0644); err != nil {
		return err
	}
	return os.Rename(tmpPath, path)
}

func computeFileHash(path string) (string, error) {
	file, err := os.Open(path)
	if err != nil {
		return "", err
	}
	defer file.Close()

	hasher := sha256.New()
	if _, err := io.Copy(hasher, file); err != nil {
		return "", err
	}
	return hex.EncodeToString(hasher.Sum(nil)), nil
}

func chunkCount(totalSize int64, chunkSize int) int {
	if totalSize <= 0 {
		return 0
	}
	return int((totalSize + int64(chunkSize) - 1) / int64(chunkSize))
}

func minInt64(a, b int64) int64 {
	if a < b {
		return a
	}
	return b
}

func isSubPath(root, path string) bool {
	rel, err := filepath.Rel(root, path)
	if err != nil {
		return false
	}
	return rel == "." || (!strings.HasPrefix(rel, "..") && rel != "")
}

func getEnv(key, fallback string) string {
	if value, ok := os.LookupEnv(key); ok && strings.TrimSpace(value) != "" {
		return strings.TrimSpace(value)
	}
	return fallback
}

func getEnvInt(key string, fallback int) int {
	value := getEnv(key, "")
	if value == "" {
		return fallback
	}
	parsed, err := strconv.Atoi(value)
	if err != nil {
		log.Printf("invalid %s=%q, using %d", key, value, fallback)
		return fallback
	}
	return parsed
}

func getEnvBool(key string, fallback bool) bool {
	value := strings.ToLower(getEnv(key, ""))
	if value == "" {
		return fallback
	}
	switch value {
	case "1", "true", "yes", "on":
		return true
	case "0", "false", "no", "off":
		return false
	default:
		log.Printf("invalid %s=%q, using %t", key, value, fallback)
		return fallback
	}
}

func setBasicAuth(req *http.Request, username, password string) {
	auth := username + ":" + password
	req.Header.Set("Authorization", "Basic "+base64.StdEncoding.EncodeToString([]byte(auth)))
}
