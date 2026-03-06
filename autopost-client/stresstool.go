package main

import (
	"bytes"
	"crypto/rand"
	"encoding/json"
	"fmt"
	"io"
	"log"
	"mime/multipart"
	"net/http"
	"sync"
	"time"
)

const (
	ServerURL      = "http://localhost:8888"
	Username       = "admin"
	Password      = "SyncForge2026!"
	ConcurrentJobs = 20 // Number of simultaneous uploads
	FilesPerJob    = 5  // Total 100 files
	ChunkSize      = 5 * 1024 * 1024
)

type InitResponse struct {
	ID string `json:"id"`
}

func main() {
	start := time.Now()
	var wg sync.WaitGroup
	results := make(chan bool, ConcurrentJobs*FilesPerJob)

	log.Printf("🚀 Starting Load Test: %d concurrent workers, 50 total files...", ConcurrentJobs)

	for i := 0; i < ConcurrentJobs; i++ {
		wg.Add(1)
		go func(workerID int) {
			defer wg.Done()
			for j := 0; j < FilesPerJob; j++ {
				filename := fmt.Sprintf("loadtest_w%d_f%d.bin", workerID, j)
				size := int64(5*1024*1024 + (workerID+j)*1024*1024) // 5MB to 20MB
				
				err := simulateUpload(filename, size)
				if err != nil {
					log.Printf("[Worker %d] ❌ Failed %s: %v", workerID, filename, err)
					results <- false
				} else {
					results <- true
				}
			}
		}(i)
	}

	wg.Wait()
	close(results)

	successCount := 0
	for r := range results {
		if r {
			successCount++
		}
	}

	duration := time.Since(start)
	log.Printf("\n--- Load Test Results ---")
	log.Printf("Total Files: %d", ConcurrentJobs*FilesPerJob)
	log.Printf("Successes:   %d", successCount)
	log.Printf("Failures:    %d", (ConcurrentJobs*FilesPerJob)-successCount)
	log.Printf("Total Time:  %v", duration)
	log.Printf("Avg Speed:   %.2f files/sec", float64(successCount)/duration.Seconds())
}

func simulateUpload(filename string, size int64) error {
	// 1. Init
	fileID, err := apiInit(filename, size)
	if err != nil {
		return err
	}

	// 2. Chunk (simulated with random data)
	remaining := size
	for remaining > 0 {
		currentChunk := ChunkSize
		if remaining < int64(ChunkSize) {
			currentChunk = int(remaining)
		}

		data := make([]byte, currentChunk)
		rand.Read(data) // Generate random noise for encryption testing

		err = apiChunk(fileID, data)
		if err != nil {
			return err
		}
		remaining -= int64(currentChunk)
	}

	// 3. Complete
	return apiComplete(fileID)
}

func apiInit(filename string, size int64) (string, error) {
	payload := map[string]interface{}{"originalFilename": filename, "totalSize": size}
	b, _ := json.Marshal(payload)
	req, _ := http.NewRequest("POST", ServerURL+"/api/files/init", bytes.NewBuffer(b))
	req.Header.Set("Content-Type", "application/json")
	return doRequest(req)
}

func apiChunk(fileID string, data []byte) error {
	body := &bytes.Buffer{}
	writer := multipart.NewWriter(body)
	part, _ := writer.CreateFormFile("chunk", "blob")
	part.Write(data)
	writer.Close()

	req, _ := http.NewRequest("POST", fmt.Sprintf("%s/api/files/%s/chunk", ServerURL, fileID), body)
	req.Header.Set("Content-Type", writer.FormDataContentType())
	_, err := doRequest(req)
	return err
}

func apiComplete(fileID string) error {
	req, _ := http.NewRequest("POST", fmt.Sprintf("%s/api/files/%s/complete", ServerURL, fileID), nil)
	_, err := doRequest(req)
	return err
}

func doRequest(req *http.Request) (string, error) {
	req.SetBasicAuth(Username, Password)
	
	client := &http.Client{Timeout: 30 * time.Second}
	resp, err := client.Do(req)
	if err != nil {
		return "", err
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		b, _ := io.ReadAll(resp.Body)
		return "", fmt.Errorf("status %d for %s: %s", resp.StatusCode, req.URL.Path, string(b))
	}

	if req.URL.Path == "/api/files/init" {
		var r InitResponse
		json.NewDecoder(resp.Body).Decode(&r)
		return r.ID, nil
	}
	return "", nil
}
