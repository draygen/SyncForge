package main

import (
	"bytes"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"io"
	"log"
	"mime/multipart"
	"net/http"
	"os"
	"path/filepath"
	"time"

	"github.com/fsnotify/fsnotify"
)

// Configuration - this would normally be loaded from a config.yaml or .env
const (
	ServerURL     = "http://localhost:8888"
	WatchDir      = "./sync_folder"
	Username      = "admin"
	Password      = "Renoise28!"
	BaseChunkSize = 1024 * 1024 // 1 MB chunks
	Concurrency   = 4           // Parallel chunks per file
)

// InitResponse matches the Java Spring Boot FileMetadata JSON response
type InitResponse struct {
	ID               string `json:"id"`
	OriginalFilename string `json:"originalFilename"`
	Status           string `json:"status"`
}

func main() {
	// 1. Ensure watch directory exists
	if err := os.MkdirAll(WatchDir, 0755); err != nil {
		log.Fatalf("Failed to create watch directory: %v", err)
	}

	// 2. Initialize the file watcher
	watcher, err := fsnotify.NewWatcher()
	if err != nil {
		log.Fatal(err)
	}
	defer watcher.Close()

	// 3. Start a background goroutine to process events
	go func() {
		for {
			select {
			case event, ok := <-watcher.Events:
				if !ok {
					return
				}
				// We care about new files or written files
				if event.Has(fsnotify.Create) || event.Has(fsnotify.Write) {
					log.Println("Detected file modification:", event.Name)
					
					// Small debounce to ensure file is fully written before we read it
					// In a real MFT, you'd check for file locks to ensure writing is complete
					time.Sleep(2 * time.Second) 
					
					go handleUpload(event.Name)
				}
			case err, ok := <-watcher.Errors:
				if !ok {
					return
				}
				log.Println("Watcher error:", err)
			}
		}
	}()

	err = watcher.Add(WatchDir)
	if err != nil {
		log.Fatal(err)
	}

	log.Printf("AutoPost Go Client started. Watching directory: %s", WatchDir)
	log.Printf("Connecting to MFT Server: %s", ServerURL)
	
	// Scan existing files
	entries, err := os.ReadDir(WatchDir)
	if err == nil {
		for _, entry := range entries {
			if !entry.IsDir() {
				go handleUpload(filepath.Join(WatchDir, entry.Name()))
			}
		}
	}

	// Block main thread
	<-make(chan struct{})
}

func handleUpload(filePath string) {
	fileInfo, err := os.Stat(filePath)
	if err != nil || fileInfo.IsDir() {
		return
	}

	filename := filepath.Base(filePath)
	totalSize := fileInfo.Size()
	log.Printf("Starting PARALLEL upload for: %s (%d bytes)", filename, totalSize)

	fileID, err := initUpload(filename, totalSize)
	if err != nil {
		log.Printf("Init failed: %v", err)
		return
	}

	file, err := os.Open(filePath)
	if err != nil {
		return
	}
	defer file.Close()

	type chunkJob struct {
		index int
		data  []byte
	}

	jobs := make(chan chunkJob, Concurrency)
	var wg sync.WaitGroup

	// Start workers
	for w := 0; w < Concurrency; w++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for job := range jobs {
				log.Printf("Uploading chunk %d...", job.index)
				uploadChunk(fileID, job.data)
			}
		}()
	}

	buffer := make([]byte, BaseChunkSize)
	chunkIndex := 0
	for {
		bytesRead, err := file.Read(buffer)
		if bytesRead == 0 {
			break
		}
		
		data := make([]byte, bytesRead)
		copy(data, buffer[:bytesRead])
		jobs <- chunkJob{index: chunkIndex, data: data}
		chunkIndex++
	}
	close(jobs)
	wg.Wait()

	completeUpload(fileID)
	log.Printf("✅ Parallel Transfer Complete: %s", filename)
}

func initUpload(filename string, totalSize int64) (string, error) {
	payload := map[string]interface{}{
		"originalFilename": filename,
		"totalSize":        totalSize,
	}
	jsonPayload, _ := json.Marshal(payload)

	req, _ := http.NewRequest("POST", ServerURL+"/api/files/init", bytes.NewBuffer(jsonPayload))
	req.Header.Set("Content-Type", "application/json")
	setBasicAuth(req)

	client := &http.Client{}
	resp, err := client.Do(req)
	if err != nil {
		return "", err
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		return "", fmt.Errorf("server returned status: %d", resp.StatusCode)
	}

	var initResp InitResponse
	json.NewDecoder(resp.Body).Decode(&initResp)
	return initResp.ID, nil
}

func uploadChunk(fileID string, chunkData []byte) error {
	body := &bytes.Buffer{}
	writer := multipart.NewWriter(body)
	
	part, err := writer.CreateFormFile("chunk", "chunk.bin")
	if err != nil {
		return err
	}
	part.Write(chunkData)
	writer.Close()

	req, _ := http.NewRequest("POST", fmt.Sprintf("%s/api/files/%s/chunk", ServerURL, fileID), body)
	req.Header.Set("Content-Type", writer.FormDataContentType())
	setBasicAuth(req)

	client := &http.Client{}
	resp, err := client.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("server returned status: %d", resp.StatusCode)
	}
	return nil
}

func completeUpload(fileID string) error {
	req, _ := http.NewRequest("POST", fmt.Sprintf("%s/api/files/%s/complete", ServerURL, fileID), nil)
	setBasicAuth(req)

	client := &http.Client{}
	resp, err := client.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("server returned status: %d", resp.StatusCode)
	}
	return nil
}

func setBasicAuth(req *http.Request) {
	auth := Username + ":" + Password
	encodedAuth := base64.StdEncoding.EncodeToString([]byte(auth))
	req.Header.Set("Authorization", "Basic "+encodedAuth)
}
