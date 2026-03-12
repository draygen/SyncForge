package com.mft.server.repository;

import com.mft.server.model.UploadChunk;
import com.mft.server.model.UploadChunkId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Repository
public interface UploadChunkRepository extends JpaRepository<UploadChunk, UploadChunkId> {
    boolean existsByFileIdAndChunkIndex(UUID fileId, Integer chunkIndex);
    List<UploadChunk> findByFileIdOrderByChunkIndexAsc(UUID fileId);

    @Modifying
    @Transactional
    @Query("DELETE FROM UploadChunk c WHERE c.fileId = :fileId")
    void deleteByFileId(@Param("fileId") UUID fileId);

    @Query("select coalesce(sum(c.chunkSize), 0) from UploadChunk c where c.fileId = :fileId")
    long sumChunkSizeByFileId(@Param("fileId") UUID fileId);
}
