package com.mft.server.service;

import com.mft.server.model.FileMetadata;
import com.mft.server.model.UploadChunk;
import com.mft.server.model.UploadSessionResponse;
import com.mft.server.repository.FileMetadataRepository;
import com.mft.server.repository.UploadChunkRepository;
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
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class FileStorageService {

    @Value("${mft.storage.path}")
    private String storagePath;

    private final FileMetadataRepository fileMetadataRepository;
    private final UploadChunkRepository uploadChunkRepository;
    private final EncryptionService encryptionService;
    
    // Concurrent cache for rapid chunk processing
    private final java.util.Map<java.util.UUID, FileMetadata> activeTransfers = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Map<java.util.UUID, java.util.concurrent.locks.ReentrantLock> fileLocks = new java.util.concurrent.ConcurrentHashMap<>();

    public FileStorageService(
            FileMetadataRepository fileMetadataRepository,
            UploadChunkRepository uploadChunkRepository,
            EncryptionService encryptionService
    ) {
        this.fileMetadataRepository = fileMetadataRepository;
        this.uploadChunkRepository = uploadChunkRepository;
        this.encryptionService = encryptionService;
    }

    @PostConstruct
    public void init() throws Exception {
        Path path = Paths.get(storagePath);
        if (!Files.exists(path)) {
            Files.createDirectories(path);
        }
    }

    public UploadSessionResponse initializeUpload(String originalFilename, Long totalSize) throws Exception {
        var existing = fileMetadataRepository.findTopByOriginalFilenameAndTotalSizeAndStatusInOrderByCreatedAtDesc(
                originalFilename,
                totalSize,
                List.of("INITIATED", "IN_PROGRESS")
        );
        if (existing.isPresent()) {
            FileMetadata metadata = refreshProgress(existing.get());
            activeTransfers.put(metadata.getId(), metadata);
            fileLocks.computeIfAbsent(metadata.getId(), ignored -> new java.util.concurrent.locks.ReentrantLock());
            return toUploadSession(metadata);
        }

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
        return toUploadSession(saved);
    }

    public FileMetadata appendChunk(java.util.UUID fileId, byte[] chunkData, int chunkIndex) throws Exception {
        FileMetadata metadata = activeTransfers.get(fileId);
        if (metadata == null) {
            metadata = fileMetadataRepository.findById(fileId)
                .orElseThrow(() -> new IllegalArgumentException("File ID not found"));
            activeTransfers.put(fileId, metadata);
        }
        
        java.util.concurrent.locks.ReentrantLock lock = fileLocks.computeIfAbsent(fileId, k -> new java.util.concurrent.locks.ReentrantLock());
        lock.lock();
        try {
            if (chunkIndex >= 0) {
                File partFile = new File(storagePath, metadata.getStoredFilename() + ".part" + chunkIndex);
                if (uploadChunkRepository.existsByFileIdAndChunkIndex(fileId, chunkIndex)) {
                    return refreshProgress(metadata);
                }
                try (FileOutputStream fos = new FileOutputStream(partFile, false)) {
                    fos.write(chunkData);
                }
                UploadChunk uploadChunk = new UploadChunk();
                uploadChunk.setFileId(fileId);
                uploadChunk.setChunkIndex(chunkIndex);
                uploadChunk.setChunkSize((long) chunkData.length);
                uploadChunkRepository.save(uploadChunk);
            } else {
                String aesKey = encryptionService.decryptAesKeyWithMasterKey(metadata.getEncryptedAesKey());
                byte[] encryptedChunk = encryptionService.encryptChunkGcm(chunkData, aesKey);

                // GCM framing: [4-byte big-endian length][IV+ciphertext+tag]
                File file = new File(storagePath, metadata.getStoredFilename());
                try (FileOutputStream fos = new FileOutputStream(file, true)) {
                    byte[] lenBytes = ByteBuffer.allocate(4).putInt(encryptedChunk.length).array();
                    fos.write(lenBytes);
                    fos.write(encryptedChunk);
                }
                metadata.setUploadedSize((metadata.getUploadedSize() == null ? 0L : metadata.getUploadedSize()) + chunkData.length);
            }

            metadata.setStatus("IN_PROGRESS");
            return fileMetadataRepository.save(refreshProgress(metadata));
        } finally {
            lock.unlock();
        }
    }

    @org.springframework.transaction.annotation.Transactional
    public void markAssembling(UUID fileId) {
        fileMetadataRepository.findById(fileId).ifPresent(m -> {
            m.setStatus("ASSEMBLING");
            fileMetadataRepository.save(m);
        });
        activeTransfers.remove(fileId);
    }

    @org.springframework.transaction.annotation.Transactional
    public void markFailed(UUID fileId) {
        fileMetadataRepository.findById(fileId).ifPresent(m -> {
            m.setStatus("ERROR");
            fileMetadataRepository.save(m);
        });
        activeTransfers.remove(fileId);
        fileLocks.remove(fileId);
    }

    @org.springframework.transaction.annotation.Transactional
    public FileMetadata completeUpload(java.util.UUID fileId) throws Exception {
        FileMetadata metadata = activeTransfers.get(fileId);
        if (metadata == null) {
            metadata = fileMetadataRepository.findById(fileId).orElse(null);
        }

        if (metadata != null) {
            java.util.concurrent.locks.ReentrantLock lock = fileLocks.computeIfAbsent(fileId, k -> new java.util.concurrent.locks.ReentrantLock());
            lock.lock();
            try {
                String aesKey = encryptionService.decryptAesKeyWithMasterKey(metadata.getEncryptedAesKey());
                final String storedFilename = metadata.getStoredFilename();
                File file = new File(storagePath, storedFilename);
                List<UploadChunk> chunks = uploadChunkRepository.findByFileIdOrderByChunkIndexAsc(fileId);
                if (!chunks.isEmpty()) {
                    // Check if enc was already assembled by a previous complete attempt
                    // (part files gone, enc exists, chunks still in DB due to prior failure)
                    boolean partsExist = chunks.stream().anyMatch(c ->
                            new File(storagePath, storedFilename + ".part" + c.getChunkIndex()).exists());

                    if (partsExist) {
                        try (FileOutputStream fos = new FileOutputStream(file, false)) {
                            for (UploadChunk chunk : chunks) {
                                File partFile = new File(storagePath, metadata.getStoredFilename() + ".part" + chunk.getChunkIndex());
                                if (!partFile.exists()) {
                                    throw new IllegalStateException("Missing chunk file: " + chunk.getChunkIndex());
                                }
                                byte[] chunkData = Files.readAllBytes(partFile.toPath());
                                byte[] encryptedChunk = encryptionService.encryptChunkGcm(chunkData, aesKey);
                                byte[] lenBytes = ByteBuffer.allocate(4).putInt(encryptedChunk.length).array();
                                fos.write(lenBytes);
                                fos.write(encryptedChunk);
                                Files.deleteIfExists(partFile.toPath());
                            }
                        }
                    } else if (!file.exists()) {
                        throw new IllegalStateException("No uploaded chunks or assembled file found");
                    }
                    // Clean up chunk records regardless of whether we just assembled or recovered
                    uploadChunkRepository.deleteByFileId(fileId);
                    metadata.setUploadedSize(metadata.getTotalSize());
                } else if (!file.exists()) {
                    throw new IllegalStateException("No uploaded chunks found for file");
                }
                metadata.setStatus("COMPLETED");
                return fileMetadataRepository.save(metadata);
            } finally {
                lock.unlock();
                activeTransfers.remove(fileId);
                fileLocks.remove(fileId);
            }
        }
        
        activeTransfers.remove(fileId);
        fileLocks.remove(fileId);
        return null;
    }

    public UploadSessionResponse getUploadSession(UUID fileId) {
        FileMetadata metadata = fileMetadataRepository.findById(fileId)
                .orElseThrow(() -> new IllegalArgumentException("File not found"));
        return toUploadSession(refreshProgress(metadata));
    }

    public FileMetadata getFile(UUID fileId) {
        return fileMetadataRepository.findById(fileId).orElse(null);
    }

    public boolean storedFileExists(FileMetadata metadata) {
        if (metadata == null || metadata.getStoredFilename() == null || metadata.getStoredFilename().isBlank()) {
            return false;
        }
        return Files.exists(Paths.get(storagePath, metadata.getStoredFilename()));
    }

    private UploadSessionResponse toUploadSession(FileMetadata metadata) {
        return UploadSessionResponse.from(
                metadata,
                uploadChunkRepository.findByFileIdOrderByChunkIndexAsc(metadata.getId()).stream()
                        .map(UploadChunk::getChunkIndex)
                        .collect(Collectors.toList())
        );
    }

    private FileMetadata refreshProgress(FileMetadata metadata) {
        long uploadedSize = uploadChunkRepository.sumChunkSizeByFileId(metadata.getId());
        if (uploadedSize > 0) {
            metadata.setUploadedSize(uploadedSize);
            if (!"COMPLETED".equals(metadata.getStatus())) {
                metadata.setStatus(uploadedSize >= (metadata.getTotalSize() != null ? metadata.getTotalSize() : Long.MAX_VALUE)
                        ? "IN_PROGRESS"
                        : "IN_PROGRESS");
            }
        }
        return metadata;
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
        for (UploadChunk chunk : uploadChunkRepository.findByFileIdOrderByChunkIndexAsc(fileId)) {
            Files.deleteIfExists(Paths.get(storagePath, metadata.getStoredFilename() + ".part" + chunk.getChunkIndex()));
        }
        uploadChunkRepository.deleteByFileId(fileId);
        activeTransfers.remove(fileId);
        fileLocks.remove(fileId);
        fileMetadataRepository.deleteById(fileId);
    }

    @org.springframework.transaction.annotation.Transactional
    public void purgeAllData() {
        uploadChunkRepository.deleteAll();
        activeTransfers.clear();
        fileLocks.clear();
        fileMetadataRepository.deleteAll();
    }
}
