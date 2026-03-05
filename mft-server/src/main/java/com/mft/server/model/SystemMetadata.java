package com.mft.server.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "system_metadata")
public class SystemMetadata {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String version;

    @Column(nullable = false)
    private String dataModelVersion;

    private LocalDateTime lastUpgradeAt = LocalDateTime.now();

    private String systemStatus = "HEALTHY";

    // Getters and Setters

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }

    public String getDataModelVersion() { return dataModelVersion; }
    public void setDataModelVersion(String dataModelVersion) { this.dataModelVersion = dataModelVersion; }

    public LocalDateTime getLastUpgradeAt() { return lastUpgradeAt; }
    public void setLastUpgradeAt(LocalDateTime lastUpgradeAt) { this.lastUpgradeAt = lastUpgradeAt; }

    public String getSystemStatus() { return systemStatus; }
    public void setSystemStatus(String systemStatus) { this.systemStatus = systemStatus; }
}
