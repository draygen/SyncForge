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

    private final com.mft.server.service.FileStorageService fileStorageService;
    private final com.mft.server.service.ActivityService activityService;

    public FileTransferController(com.mft.server.service.FileStorageService fileStorageService, 
                                  com.mft.server.service.ActivityService activityService) {
        this.fileStorageService = fileStorageService;
        this.activityService = activityService;
    }

    @PostMapping("/init")
    public ResponseEntity<com.mft.server.model.FileMetadata> initUpload(@RequestBody com.mft.server.model.InitUploadRequest request, org.springframework.security.core.Authentication auth) {
        try {
            com.mft.server.model.FileMetadata metadata = fileStorageService.initializeUpload(request.getOriginalFilename(), request.getTotalSize());
            activityService.log("UPLOAD_INIT", auth.getName(), "Started: " + request.getOriginalFilename());
            return ResponseEntity.ok(metadata);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    @PostMapping("/{fileId}/chunk")
    public ResponseEntity<String> uploadChunk(
            @PathVariable java.util.UUID fileId,
            @RequestParam("chunk") MultipartFile chunk,
            org.springframework.security.core.Authentication auth) {
        try {
            fileStorageService.appendChunk(fileId, chunk.getBytes());
            // We don't log every chunk to avoid flooding, maybe just some summary later
            return ResponseEntity.ok("Chunk appended successfully");
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body("Failed to process chunk: " + e.getMessage());
        }
    }

    @PostMapping("/{fileId}/complete")
    public ResponseEntity<String> completeUpload(@PathVariable java.util.UUID fileId, org.springframework.security.core.Authentication auth) {
        try {
            fileStorageService.completeUpload(fileId);
            activityService.log("UPLOAD_COMPLETE", auth.getName(), "Finished ID: " + fileId);
            return ResponseEntity.ok("Upload completed");
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body("Failed to complete upload: " + e.getMessage());
        }
    }

    @GetMapping
    public ResponseEntity<java.util.List<com.mft.server.model.FileMetadata>> listFiles(org.springframework.security.core.Authentication auth) {
        return ResponseEntity.ok(fileStorageService.getAllFiles().stream()
            .filter(f -> f.getStatus() != null) // Basic filter for now
            .collect(java.util.stream.Collectors.toList()));
    }
}
