-- V1: Initial MFT Server Setup
-- This script is executed by Flyway for automatic schema creation and in-place upgrades.

-- 1. File Metadata Table
CREATE TABLE file_metadata (
    id UUID PRIMARY KEY,
    original_filename VARCHAR(255) NOT NULL,
    stored_filename VARCHAR(255) NOT NULL,
    total_size BIGINT,
    uploaded_size BIGINT DEFAULT 0,
    status VARCHAR(50) NOT NULL,
    encrypted_aes_key TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 2. User Management
CREATE TABLE users (
    id UUID PRIMARY KEY,
    username VARCHAR(255) NOT NULL UNIQUE,
    password VARCHAR(255) NOT NULL,
    enabled BOOLEAN DEFAULT TRUE
);

CREATE TABLE user_roles (
    user_id UUID NOT NULL REFERENCES users(id),
    role VARCHAR(50) NOT NULL,
    PRIMARY KEY (user_id, role)
);

-- 3. System Meta-Model Tracking
CREATE TABLE system_metadata (
    id SERIAL PRIMARY KEY,
    version VARCHAR(50) NOT NULL,
    data_model_version VARCHAR(50) NOT NULL,
    last_upgrade_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    system_status VARCHAR(50) DEFAULT 'HEALTHY'
);
