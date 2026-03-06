-- V2: Seed Default Data
-- This ensures the admin user and system version are created during the migration phase.

INSERT INTO users (id, username, password, enabled) 
VALUES ('00000000-0000-0000-0000-000000000001', 'admin', '$2b$12$Nu4CkvY0QqSkMkrSuegAZuadximVMcoK8642TfvqG/SXS0FOBHwGq', TRUE)
ON CONFLICT (username) DO NOTHING;

INSERT INTO user_roles (user_id, role)
VALUES ('00000000-0000-0000-0000-000000000001', 'ADMIN'),
       ('00000000-0000-0000-0000-000000000001', 'USER')
ON CONFLICT DO NOTHING;

INSERT INTO system_metadata (version, data_model_version, last_upgrade_at, system_status)
VALUES ('1.0.0', '1.0', CURRENT_TIMESTAMP, 'HEALTHY');
