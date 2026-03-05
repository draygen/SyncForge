package com.mft.server.service;

import com.mft.server.model.FileMetadata;
import com.mft.server.repository.FileMetadataRepository;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
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
        metadata.setCipherMode("AES_GCM");

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
            byte[] encryptedChunk = encryptionService.encryptChunkGcm(chunkData, aesKey);

            // GCM framing: [4-byte big-endian length][IV+ciphertext+tag]
            File file = new File(storagePath, metadata.getStoredFilename());
            try (FileOutputStream fos = new FileOutputStream(file, true)) {
                byte[] lenBytes = ByteBuffer.allocate(4).putInt(encryptedChunk.length).array();
                fos.write(lenBytes);
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

    /**
     * Stream-decrypt a completed file to the given OutputStream.
     * Supports AES_GCM (length-framed chunks) and AES_ECB (legacy, fixed-size chunks).
     */
    public FileMetadata downloadFile(UUID fileId, OutputStream out) throws Exception {
        FileMetadata metadata = fileMetadataRepository.findById(fileId)
                .orElseThrow(() -> new IllegalArgumentException("File not found"));
        if (!"COMPLETED".equals(metadata.getStatus())) {
            throw new IllegalStateException("File transfer not complete");
        }

        String aesKey = encryptionService.decryptAesKeyWithMasterKey(metadata.getEncryptedAesKey());
        File file = new File(storagePath, metadata.getStoredFilename());

        boolean isGcm = "AES_GCM".equals(metadata.getCipherMode());

        try (FileInputStream fis = new FileInputStream(file)) {
            if (isGcm) {
                byte[] lenBuf = new byte[4];
                while (fis.read(lenBuf) == 4) {
                    int encLen = ByteBuffer.wrap(lenBuf).getInt();
                    byte[] encData = readFully(fis, encLen);
                    out.write(encryptionService.decryptChunkGcm(encData, aesKey));
                }
            } else {
                // Legacy AES/ECB: fixed-size encrypted chunks
                // Full chunk (1MB) pads to 1MB+16; last chunk is remainder+padding
                final int CHUNK = 1024 * 1024;
                long totalSize = metadata.getTotalSize() != null ? metadata.getTotalSize() : 0L;
                long remaining = totalSize;
                while (remaining > 0) {
                    int plainLen = (int) Math.min(CHUNK, remaining);
                    int paddedLen = plainLen + (16 - plainLen % 16); // PKCS5 padding
                    byte[] encData = readFully(fis, paddedLen);
                    out.write(encryptionService.decryptChunkEcb(encData, aesKey));
                    remaining -= plainLen;
                }
            }
        }
        return metadata;
    }

    private byte[] readFully(InputStream in, int len) throws java.io.IOException {
        byte[] buf = new byte[len];
        int read = 0;
        while (read < len) {
            int n = in.read(buf, read, len - read);
            if (n < 0) throw new java.io.EOFException("Unexpected EOF reading encrypted chunk");
            read += n;
        }
        return buf;
    }

    @org.springframework.transaction.annotation.Transactional
    public void deleteFile(UUID fileId) throws Exception {
        FileMetadata metadata = fileMetadataRepository.findById(fileId)
                .orElseThrow(() -> new IllegalArgumentException("File not found"));
        File file = new File(storagePath, metadata.getStoredFilename());
        if (file.exists()) {
            // Single-pass overwrite before delete
            try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(file, "rws")) {
                byte[] zeros = new byte[4096];
                long len = raf.length();
                long pos = 0;
                while (pos < len) {
                    int toWrite = (int) Math.min(zeros.length, len - pos);
                    raf.write(zeros, 0, toWrite);
                    pos += toWrite;
                }
            }
            file.delete();
        }
        activeTransfers.remove(fileId);
        fileLocks.remove(fileId);
        fileMetadataRepository.deleteById(fileId);
    }

    @org.springframework.transaction.annotation.Transactional
    public void purgeAllData() {
        activeTransfers.clear();
        fileLocks.clear();
        fileMetadataRepository.deleteAll();
    }
}
