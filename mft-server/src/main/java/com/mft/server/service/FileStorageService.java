package com.mft.server.service;

import com.mft.server.model.FileMetadata;
import com.mft.server.repository.FileMetadataRepository;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.UUID;

@Service
public class FileStorageService {

    @Value("${mft.storage.path}")
    private String storagePath;

    private final FileMetadataRepository fileMetadataRepository;
    private final EncryptionService encryptionService;
    
    // Concurrent cache for rapid chunk processing
    private final java.util.Map<java.util.UUID, FileMetadata> activeTransfers = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Map<java.util.UUID, java.util.concurrent.locks.ReentrantLock> fileLocks = new java.util.concurrent.ConcurrentHashMap<>();

    public FileStorageService(FileMetadataRepository fileMetadataRepository, EncryptionService encryptionService) {
        this.fileMetadataRepository = fileMetadataRepository;
        this.encryptionService = encryptionService;
    }

    @PostConstruct
    public void init() throws Exception {
        Path path = Paths.get(storagePath);
        if (!Files.exists(path)) {
            Files.createDirectories(path);
        }
    }

    public FileMetadata initializeUpload(String originalFilename, Long totalSize) throws Exception {
        String aesKey = encryptionService.generateAesKey();
        String encryptedAesKey = encryptionService.encryptAesKeyWithMasterKey(aesKey);
        String storedFilename = java.util.UUID.randomUUID().toString() + ".enc";

        FileMetadata metadata = new FileMetadata();
        metadata.setOriginalFilename(originalFilename);
        metadata.setStoredFilename(storedFilename);
        metadata.setTotalSize(totalSize);
        metadata.setStatus("INITIATED");
        metadata.setEncryptedAesKey(encryptedAesKey);

        FileMetadata saved = fileMetadataRepository.save(metadata);
        activeTransfers.put(saved.getId(), saved);
        fileLocks.put(saved.getId(), new java.util.concurrent.locks.ReentrantLock());
        return saved;
    }

    public void appendChunk(java.util.UUID fileId, byte[] chunkData) throws Exception {
        FileMetadata metadata = activeTransfers.get(fileId);
        if (metadata == null) {
            metadata = fileMetadataRepository.findById(fileId)
                .orElseThrow(() -> new IllegalArgumentException("File ID not found"));
            activeTransfers.put(fileId, metadata);
        }
        
        java.util.concurrent.locks.ReentrantLock lock = fileLocks.computeIfAbsent(fileId, k -> new java.util.concurrent.locks.ReentrantLock());
        lock.lock();
        try {
            String aesKey = encryptionService.decryptAesKeyWithMasterKey(metadata.getEncryptedAesKey());
            byte[] encryptedChunk = encryptionService.encryptChunk(chunkData, aesKey);

            File file = new File(storagePath, metadata.getStoredFilename());
            try (FileOutputStream fos = new FileOutputStream(file, true)) {
                fos.write(encryptedChunk);
            }

            metadata.setUploadedSize(metadata.getUploadedSize() + chunkData.length);
            metadata.setStatus("IN_PROGRESS");
        } finally {
            lock.unlock();
        }
    }

    public FileMetadata completeUpload(java.util.UUID fileId) {
        FileMetadata metadata = activeTransfers.remove(fileId);
        fileLocks.remove(fileId);
        if (metadata == null) {
            metadata = fileMetadataRepository.findById(fileId).orElse(null);
        }
        
        if (metadata != null) {
            metadata.setStatus("COMPLETED");
            return fileMetadataRepository.save(metadata);
        }
        return null;
    }

    public java.util.List<FileMetadata> getAllFiles() {
        return fileMetadataRepository.findAll(org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.DESC, "createdAt"));
    }

    @org.springframework.transaction.annotation.Transactional
    public void purgeAllData() {
        activeTransfers.clear();
        fileLocks.clear();
        fileMetadataRepository.deleteAll();
    }
}
