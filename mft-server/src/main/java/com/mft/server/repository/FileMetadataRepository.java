package com.mft.server.repository;

import com.mft.server.model.FileMetadata;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface FileMetadataRepository extends JpaRepository<FileMetadata, UUID> {
    Optional<FileMetadata> findTopByOriginalFilenameAndTotalSizeAndStatusInOrderByCreatedAtDesc(
            String originalFilename,
            Long totalSize,
            Collection<String> statuses
    );
}
