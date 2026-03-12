package com.mft.server.model;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

public class UploadChunkId implements Serializable {
    private UUID fileId;
    private Integer chunkIndex;

    public UploadChunkId() {}

    public UploadChunkId(UUID fileId, Integer chunkIndex) {
        this.fileId = fileId;
        this.chunkIndex = chunkIndex;
    }

    public UUID getFileId() { return fileId; }
    public void setFileId(UUID fileId) { this.fileId = fileId; }

    public Integer getChunkIndex() { return chunkIndex; }
    public void setChunkIndex(Integer chunkIndex) { this.chunkIndex = chunkIndex; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof UploadChunkId that)) return false;
        return Objects.equals(fileId, that.fileId) && Objects.equals(chunkIndex, that.chunkIndex);
    }

    @Override
    public int hashCode() {
        return Objects.hash(fileId, chunkIndex);
    }
}
