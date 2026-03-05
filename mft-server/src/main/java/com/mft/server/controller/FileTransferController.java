package com.mft.server.controller;

import com.mft.server.model.FileMetadata;
import com.mft.server.model.InitUploadRequest;
import com.mft.server.service.FileStorageService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

@RestController
@RequestMapping("/api/files")
public class FileTransferController {

    private final FileStorageService fileStorageService;

    public FileTransferController(FileStorageService fileStorageService) {
        this.fileStorageService = fileStorageService;
    }

    @PostMapping("/init")
    public ResponseEntity<FileMetadata> initUpload(@RequestBody InitUploadRequest request) {
        try {
            FileMetadata metadata = fileStorageService.initializeUpload(request.getOriginalFilename(), request.getTotalSize());
            return ResponseEntity.ok(metadata);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    @PostMapping("/{fileId}/chunk")
    public ResponseEntity<String> uploadChunk(
            @PathVariable UUID fileId,
            @RequestParam("chunk") MultipartFile chunk) {
        try {
            fileStorageService.appendChunk(fileId, chunk.getBytes());
            return ResponseEntity.ok("Chunk appended successfully");
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body("Failed to process chunk: " + e.getMessage());
        }
    }

    @PostMapping("/{fileId}/complete")
    public ResponseEntity<String> completeUpload(@PathVariable UUID fileId) {
        try {
            fileStorageService.completeUpload(fileId);
            return ResponseEntity.ok("Upload completed");
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body("Failed to complete upload: " + e.getMessage());
        }
    }

    @GetMapping
    public ResponseEntity<java.util.List<FileMetadata>> listFiles() {
        return ResponseEntity.ok(fileStorageService.getAllFiles());
    }
}
