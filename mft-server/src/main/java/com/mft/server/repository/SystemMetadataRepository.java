package com.mft.server.repository;

import com.mft.server.model.SystemMetadata;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface SystemMetadataRepository extends JpaRepository<SystemMetadata, Long> {
    Optional<SystemMetadata> findTopByOrderByIdDesc();
}
