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
	ServerURL    = "http://localhost:8888"
	WatchDir     = "./sync_folder"
	Username     = "admin"
	Password     = "Renoise28!"
	BaseChunkSize = 5 * 1024 * 1024 // 5 MB chunks
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
	if err != nil {
		log.Printf("Error stating file %s: %v", filePath, err)
		return
	}

	if fileInfo.IsDir() {
		return // Skip directories
	}

	filename := filepath.Base(filePath)
	totalSize := fileInfo.Size()

	log.Printf("Starting upload for: %s (%d bytes)", filename, totalSize)

	// Step 1: Initialize Upload
	fileID, err := initUpload(filename, totalSize)
	if err != nil {
		log.Printf("Failed to initialize upload: %v", err)
		return
	}
	log.Printf("Server granted File ID: %s", fileID)

	// Step 2: Chunk and Upload
	file, err := os.Open(filePath)
	if err != nil {
		log.Printf("Failed to open file: %v", err)
		return
	}
	defer file.Close()

	buffer := make([]byte, BaseChunkSize)
	chunkIndex := 0

	for {
		bytesRead, err := file.Read(buffer)
		if err != nil && err != io.EOF {
			log.Printf("Error reading chunk: %v", err)
			return
		}
		if bytesRead == 0 {
			break
		}

		log.Printf("Uploading chunk %d (%d bytes)...", chunkIndex, bytesRead)
		err = uploadChunk(fileID, buffer[:bytesRead])
		if err != nil {
			log.Printf("Failed to upload chunk: %v", err)
			return // In a robust app, we'd add retry logic here
		}
		chunkIndex++
	}

	// Step 3: Complete Upload
	err = completeUpload(fileID)
	if err != nil {
		log.Printf("Failed to complete upload: %v", err)
		return
	}

	log.Printf("✅ Successfully uploaded %s to the MFT server.", filename)
	
	// Optional: Delete local file after sync
	// os.Remove(filePath)
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
