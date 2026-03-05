package com.mft.server.controller;

import com.mft.server.model.FileMetadata;
import com.mft.server.model.InitUploadRequest;
import com.mft.server.service.FileStorageService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

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

    @GetMapping("/{fileId}/download")
    public ResponseEntity<StreamingResponseBody> downloadFile(
            @PathVariable UUID fileId,
            org.springframework.security.core.Authentication auth) {
        try {
            // Resolve metadata first to get filename before streaming
            com.mft.server.model.FileMetadata meta = fileStorageService.getAllFiles().stream()
                    .filter(f -> f.getId().equals(fileId))
                    .findFirst()
                    .orElse(null);
            if (meta == null) return ResponseEntity.notFound().build();

            String filename = meta.getOriginalFilename();
            activityService.log("DOWNLOAD", auth.getName(), "Downloaded: " + filename);

            StreamingResponseBody stream = out -> {
                try { fileStorageService.downloadFile(fileId, out); }
                catch (Exception e) { throw new java.io.IOException(e); }
            };

            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .body(stream);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    @DeleteMapping("/{fileId}")
    public ResponseEntity<String> deleteFile(
            @PathVariable UUID fileId,
            org.springframework.security.core.Authentication auth) {
        try {
            fileStorageService.deleteFile(fileId);
            activityService.log("DELETE", auth.getName(), "Deleted file: " + fileId);
            lastCacheUpdate = 0;
            return ResponseEntity.ok("Deleted");
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body("Delete failed: " + e.getMessage());
        }
    }

    @GetMapping("/{fileId}")
    public ResponseEntity<com.mft.server.model.FileMetadata> getFile(@PathVariable UUID fileId) {
        return fileStorageService.getAllFiles().stream()
                .filter(f -> f.getId().equals(fileId))
                .findFirst()
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
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
