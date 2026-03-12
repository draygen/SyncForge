package com.mft.server.model;

import java.util.List;
import java.util.UUID;

public record UploadSessionResponse(
        UUID id,
        String originalFilename,
        String status,
        Long totalSize,
        Long uploadedSize,
        List<Integer> completedChunks
) {
    public static UploadSessionResponse from(FileMetadata metadata, List<Integer> completedChunks) {
        return new UploadSessionResponse(
                metadata.getId(),
                metadata.getOriginalFilename(),
                metadata.getStatus(),
                metadata.getTotalSize(),
                metadata.getUploadedSize(),
                completedChunks
        );
    }
}
