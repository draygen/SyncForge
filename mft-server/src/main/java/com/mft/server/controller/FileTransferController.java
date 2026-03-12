package com.mft.server.controller;

import com.mft.server.model.FileMetadata;
import com.mft.server.model.InitUploadRequest;
import com.mft.server.model.UploadSessionResponse;
import com.mft.server.service.FileStorageService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@RestController
@RequestMapping("/api/files")
public class FileTransferController {
    public record BulkFileRequest(List<UUID> fileIds) {}

    private final com.mft.server.service.FileStorageService fileStorageService;
    private final com.mft.server.service.ActivityService activityService;

    public FileTransferController(com.mft.server.service.FileStorageService fileStorageService, 
                                  com.mft.server.service.ActivityService activityService) {
        this.fileStorageService = fileStorageService;
        this.activityService = activityService;
    }

    @PostMapping("/init")
    public ResponseEntity<com.mft.server.model.UploadSessionResponse> initUpload(@RequestBody com.mft.server.model.InitUploadRequest request, org.springframework.security.core.Authentication auth) {
        try {
            com.mft.server.model.UploadSessionResponse metadata = fileStorageService.initializeUpload(request.getOriginalFilename(), request.getTotalSize());
            activityService.log("UPLOAD_INIT", auth.getName(), "Started: " + request.getOriginalFilename());
            lastCacheUpdate = 0;
            return ResponseEntity.ok(metadata);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    @PostMapping("/{fileId}/chunk")
    public ResponseEntity<String> uploadChunk(
            @PathVariable java.util.UUID fileId,
            @RequestParam("chunk") MultipartFile chunk,
            @RequestParam(value = "chunkIndex", defaultValue = "-1") int chunkIndex,
            org.springframework.security.core.Authentication auth) {
        try {
            fileStorageService.appendChunk(fileId, chunk.getBytes(), chunkIndex);
            lastCacheUpdate = 0;
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
            // Mark assembling immediately so polls see correct state
            fileStorageService.markAssembling(fileId);
            lastCacheUpdate = 0;
            final String username = auth.getName();
            // Assembly of large files (encrypt + write 5000+ chunk parts from disk) can take minutes.
            // Running async avoids Cloudflare's 100s upstream timeout killing the request.
            java.util.concurrent.CompletableFuture.runAsync(() -> {
                try {
                    com.mft.server.model.FileMetadata metadata = fileStorageService.completeUpload(fileId);
                    String fileName = (metadata != null) ? metadata.getOriginalFilename() : fileId.toString();
                    activityService.log("UPLOAD_COMPLETE", username, "Finished: " + fileName);
                } catch (Exception e) {
                    fileStorageService.markFailed(fileId);
                }
            });
            return ResponseEntity.accepted().body("Assembly started");
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body("Failed to start assembly: " + e.getMessage());
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

            var response = ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                    .contentType(MediaType.APPLICATION_OCTET_STREAM);
            if (meta.getTotalSize() != null && meta.getTotalSize() > 0) {
                response = response.header(HttpHeaders.CONTENT_LENGTH, String.valueOf(meta.getTotalSize()));
            }
            return response.body(stream);
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

    @GetMapping("/{fileId}/status")
    public ResponseEntity<com.mft.server.model.UploadSessionResponse> getFileStatus(@PathVariable UUID fileId) {
        try {
            return ResponseEntity.ok(fileStorageService.getUploadSession(fileId));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @PostMapping("/bulk-delete")
    public ResponseEntity<String> bulkDeleteFiles(
            @RequestBody BulkFileRequest request,
            org.springframework.security.core.Authentication auth) {
        try {
            if (request == null || request.fileIds() == null || request.fileIds().isEmpty()) {
                return ResponseEntity.badRequest().body("No file IDs supplied");
            }
            for (UUID fileId : new LinkedHashSet<>(request.fileIds())) {
                fileStorageService.deleteFile(fileId);
                activityService.log("DELETE", auth.getName(), "Deleted file: " + fileId);
            }
            lastCacheUpdate = 0;
            return ResponseEntity.ok("Bulk delete completed");
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body("Bulk delete failed: " + e.getMessage());
        }
    }

    @PostMapping("/download-zip")
    public ResponseEntity<StreamingResponseBody> downloadZip(
            @RequestBody BulkFileRequest request,
            org.springframework.security.core.Authentication auth) {
        if (request == null || request.fileIds() == null || request.fileIds().isEmpty()) {
            return ResponseEntity.badRequest().build();
        }

        List<UUID> fileIds = new LinkedHashSet<>(request.fileIds()).stream().toList();
        List<FileMetadata> filesToZip = new ArrayList<>();
        List<String> skippedFiles = new ArrayList<>();
        Set<String> usedEntryNames = new HashSet<>();

        for (UUID fileId : fileIds) {
            FileMetadata meta = fileStorageService.getFile(fileId);
            if (meta == null) {
                skippedFiles.add(fileId + " (not found)");
                continue;
            }
            if (!"COMPLETED".equals(meta.getStatus())) {
                skippedFiles.add(displayName(meta, fileId) + " (not completed)");
                continue;
            }
            if (!fileStorageService.storedFileExists(meta)) {
                skippedFiles.add(displayName(meta, fileId) + " (storage missing)");
                continue;
            }
            filesToZip.add(meta);
        }

        if (filesToZip.isEmpty()) {
            String reason = skippedFiles.isEmpty()
                    ? "No eligible files selected"
                    : "No eligible files selected: " + String.join(", ", skippedFiles);
            return ResponseEntity.badRequest()
                    .contentType(MediaType.TEXT_PLAIN)
                    .body(out -> out.write(reason.getBytes(StandardCharsets.UTF_8)));
        }

        StreamingResponseBody stream = out -> {
            try (ZipOutputStream zip = new ZipOutputStream(out)) {
                for (FileMetadata meta : filesToZip) {
                    String filename = uniqueZipEntryName(meta, usedEntryNames);
                    try {
                        zip.putNextEntry(new ZipEntry(filename));
                        fileStorageService.downloadFile(meta.getId(), new NonClosingOutputStream(zip));
                        zip.closeEntry();
                        activityService.log("DOWNLOAD", auth.getName(), "Downloaded in zip: " + filename);
                    } catch (Exception e) {
                        try {
                            zip.closeEntry();
                        } catch (IOException ignored) {
                        }
                        skippedFiles.add(filename + " (" + e.getMessage() + ")");
                    }
                }
                if (!skippedFiles.isEmpty()) {
                    ZipEntry manifest = new ZipEntry("_skipped_files.txt");
                    zip.putNextEntry(manifest);
                    zip.write(String.join(System.lineSeparator(), skippedFiles).getBytes(StandardCharsets.UTF_8));
                    zip.closeEntry();
                }
                zip.finish();
            }
        };

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"syncforge-artifacts.zip\"")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(stream);
    }

    private String displayName(FileMetadata meta, UUID fileId) {
        return meta.getOriginalFilename() != null && !meta.getOriginalFilename().isBlank()
                ? meta.getOriginalFilename()
                : fileId + ".bin";
    }

    private String uniqueZipEntryName(FileMetadata meta, Set<String> usedEntryNames) {
        String baseName = displayName(meta, meta.getId());
        if (usedEntryNames.add(baseName)) {
            return baseName;
        }

        int dot = baseName.lastIndexOf('.');
        String stem = dot > 0 ? baseName.substring(0, dot) : baseName;
        String ext = dot > 0 ? baseName.substring(dot) : "";
        int suffix = 2;
        while (true) {
            String candidate = stem + " (" + suffix + ")" + ext;
            if (usedEntryNames.add(candidate)) {
                return candidate;
            }
            suffix++;
        }
    }

    private static final class NonClosingOutputStream extends OutputStream {
        private final OutputStream delegate;

        private NonClosingOutputStream(OutputStream delegate) {
            this.delegate = delegate;
        }

        @Override
        public void write(int b) throws IOException {
            delegate.write(b);
        }

        @Override
        public void write(byte[] b) throws IOException {
            delegate.write(b);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            delegate.write(b, off, len);
        }

        @Override
        public void flush() throws IOException {
            delegate.flush();
        }

        @Override
        public void close() {
            // ZipOutputStream owns the underlying response stream.
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
