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
        String storedFilename = UUID.randomUUID().toString() + ".enc";

        FileMetadata metadata = new FileMetadata();
        metadata.setOriginalFilename(originalFilename);
        metadata.setStoredFilename(storedFilename);
        metadata.setTotalSize(totalSize);
        metadata.setStatus("INITIATED");
        metadata.setEncryptedAesKey(encryptedAesKey);

        return fileMetadataRepository.save(metadata);
    }

    public void appendChunk(UUID fileId, byte[] chunkData) throws Exception {
        Optional<FileMetadata> optionalMetadata = fileMetadataRepository.findById(fileId);
        if (optionalMetadata.isEmpty()) {
            throw new IllegalArgumentException("File ID not found");
        }

        FileMetadata metadata = optionalMetadata.get();
        if ("COMPLETED".equals(metadata.getStatus())) {
            throw new IllegalStateException("File already completed");
        }

        metadata.setStatus("IN_PROGRESS");

        String aesKey = encryptionService.decryptAesKeyWithMasterKey(metadata.getEncryptedAesKey());
        byte[] encryptedChunk = encryptionService.encryptChunk(chunkData, aesKey);

        File file = new File(storagePath, metadata.getStoredFilename());
        try (FileOutputStream fos = new FileOutputStream(file, true)) {
            fos.write(encryptedChunk);
        }

        metadata.setUploadedSize(metadata.getUploadedSize() + chunkData.length);
        fileMetadataRepository.save(metadata);
    }

    public void completeUpload(UUID fileId) {
        Optional<FileMetadata> optionalMetadata = fileMetadataRepository.findById(fileId);
        if (optionalMetadata.isPresent()) {
            FileMetadata metadata = optionalMetadata.get();
            metadata.setStatus("COMPLETED");
            fileMetadataRepository.save(metadata);
        }
    }

    public java.util.List<FileMetadata> getAllFiles() {
        return fileMetadataRepository.findAll(org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.DESC, "createdAt"));
    }
}
