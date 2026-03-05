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
            com.mft.server.model.FileMetadata metadata = fileStorageService.completeUpload(fileId);
            String fileName = (metadata != null) ? metadata.getOriginalFilename() : fileId.toString();
            activityService.log("UPLOAD_COMPLETE", auth.getName(), "Finished: " + fileName);
            
            // Invalidate cache
            lastCacheUpdate = 0;
            
            return ResponseEntity.ok("Upload completed");
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body("Failed to complete upload: " + e.getMessage());
        }
    }

    // High-speed Metadata Cache
    private volatile java.util.List<com.mft.server.model.FileMetadata> cachedFileList = new java.util.ArrayList<>();
    private volatile long lastCacheUpdate = 0;
    private static final long CACHE_TTL = 5000; // 5 seconds

    @GetMapping
    public ResponseEntity<java.util.List<com.mft.server.model.FileMetadata>> listFiles(org.springframework.security.core.Authentication auth) {
        long now = System.currentTimeMillis();
        if (now - lastCacheUpdate > CACHE_TTL) {
            synchronized (this) {
                if (System.currentTimeMillis() - lastCacheUpdate > CACHE_TTL) {
                    java.util.List<com.mft.server.model.FileMetadata> files = fileStorageService.getAllFiles();
                    // Defense: Ensure originalFilename is never null in the response
                    files.forEach(f -> {
                        if (f.getOriginalFilename() == null || f.getOriginalFilename().isEmpty()) {
                            f.setOriginalFilename("UNTITLED_ARTIFACT_" + f.getId().toString().substring(0,8));
                        }
                    });
                    cachedFileList = files;
                    lastCacheUpdate = System.currentTimeMillis();
                }
            }
        }
        return ResponseEntity.ok(cachedFileList);
    }
}
