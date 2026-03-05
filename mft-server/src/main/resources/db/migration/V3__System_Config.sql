-- V3: System Configuration Support
CREATE TABLE system_config (
    config_key VARCHAR(255) PRIMARY KEY,
    config_value TEXT NOT NULL,
    description TEXT
);

INSERT INTO system_config (config_key, config_value, description)
VALUES ('mft.chunk.size', '5242880', 'Default upload chunk size in bytes (5MB)'),
       ('mft.retention.days', '30', 'Days to keep files before auto-deletion'),
       ('mft.email.notifications', 'false', 'Enable/Disable email alerts for transfers');
