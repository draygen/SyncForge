# SyncForge Technical Specification v2.0
**Project Codename:** SyncForge
**System Type:** High-Concurrency Managed File Transfer (MFT)
**Classification:** Internal Engineering Reference
**Version:** 2.0 — Hardening & Maturity Release
**Author:** Draygen Systems Engineering
**Date:** March 5, 2026
**Status:** ACTIVE — Supersedes v1.0

---

## Table of Contents

1. [Executive Summary](#1-executive-summary)
2. [Current Architecture Baseline](#2-current-architecture-baseline)
3. [Verified Bugs & Critical Issues](#3-verified-bugs--critical-issues)
4. [Security Hardening Plan](#4-security-hardening-plan)
5. [Engineering Improvements Specification](#5-engineering-improvements-specification)
6. [API Reference (Current + Proposed)](#6-api-reference-current--proposed)
7. [Database Schema Specification](#7-database-schema-specification)
8. [Go Client Specification](#8-go-client-specification)
9. [Infrastructure & Deployment Spec](#9-infrastructure--deployment-spec)
10. [Testing & QA Requirements](#10-testing--qa-requirements)
11. [Configuration Reference](#11-configuration-reference)
12. [Implementation Roadmap](#12-implementation-roadmap)

---

## 1. Executive Summary

SyncForge is a production-grade, three-tier Managed File Transfer (MFT) platform built on Spring Boot 4.x (Tomcat), PostgreSQL 15, Nginx, and a Go 1.24 client binary. The system implements parallel 1MB chunked uploads with per-file AES-256 encryption, RBAC, Flyway schema management, and a WebGL dashboard frontend.

This v2 specification documents all findings from a comprehensive code review conducted March 5, 2026, catalogues verified bugs and security vulnerabilities, and provides actionable engineering specifications for each improvement. The v2 release targets production readiness: real envelope encryption, correct cipher mode, credential externalization, chunk ordering guarantees, download capability, and hardened access controls.

**Current v1.0 Capabilities:**
- 3-step chunked upload protocol (init / chunk / complete)
- Per-file unique AES-256 key generation (SecureRandom)
- PostgreSQL persistence with Flyway automated migrations
- Docker Compose full-stack deployment
- Go client with fsnotify file watching and 4-worker parallel upload
- BCrypt password hashing; session-based RBAC
- Nginx reverse proxy with streaming (proxy_request_buffering off)
- 5GB max file size support
- 500-thread Tomcat pool; 50-connection HikariCP pool
- In-memory double-checked locking file list cache (5s TTL)
- Server-Sent Events activity stream endpoint

---

## 2. Current Architecture Baseline

### 2.1 Stack Versions

| Component | Version | Role |
|---|---|---|
| Spring Boot | 4.0.3 | Application framework |
| Java | 17 | Runtime |
| Tomcat (embedded) | ~10.x | HTTP server |
| PostgreSQL | 15-alpine | Data persistence |
| Flyway | (Boot-managed) | Schema migration |
| HikariCP | (Boot-managed) | Connection pool |
| Nginx | alpine | Reverse proxy / static server |
| Go | 1.24.0 | Sync client binary |
| fsnotify | 1.7.0 | Go file watching |
| golang.org/x/crypto | 0.48.0 | Go crypto library |
| Playwright | (Python) | E2E testing |

### 2.2 Network Topology

```
Internet / Cloudflare Tunnel (TLS 1.3)
         │
         ▼
    Nginx :80 (external :8888)
    Nginx :443 (external :4443) [TLS-ready, not yet configured]
         │
         │  proxy_request_buffering off
         │  client_max_body_size 5G
         ▼
  Spring Boot / Tomcat :8080
  (not directly exposed)
         │
         │  HikariCP (50 connections)
         ▼
   PostgreSQL :5432
```

### 2.3 File Upload State Machine

```
[CLIENT]          [API]               [STORAGE]           [DB]
   │                │                     │                  │
   ├─POST /init────►│                     │                  │
   │                ├─generateAesKey()    │                  │
   │                ├─────────────────────────────save()────►│
   │◄──{fileId}─────┤                     │                  │
   │                │                     │                  │
   ├─POST /chunk───►│                     │                  │
   │  (×N parallel) ├─lock(fileId)        │                  │
   │                ├─decryptKey()        │                  │
   │                ├─encryptChunk()      │                  │
   │                ├────────────────────►│ append(.enc)     │
   │                ├─unlock(fileId)      │                  │
   │◄──200 OK───────┤                     │                  │
   │                │                     │                  │
   ├─POST /complete►│                     │                  │
   │                ├─────────────────────────────save(COMPLETED)►│
   │◄──200 OK───────┤                     │                  │
```

### 2.4 Class Responsibility Map

| Class | Package | Responsibility |
|---|---|---|
| `MftServerApplication` | root | Spring Boot entry point |
| `SecurityConfig` | config | Spring Security filter chain, BCrypt bean |
| `DataInitializer` | config | Flyway ordering, startup version log |
| `FlywayConfig` | config | Flyway datasource configuration |
| `FileTransferController` | controller | Upload lifecycle endpoints + file list cache |
| `SystemController` | controller | Ping, maintenance mode, admin purge |
| `UserController` | controller | Admin user CRUD |
| `ActivityController` | controller | Activity log + SSE stream + active users |
| `ConfigController` | controller | System configuration key-value |
| `ProfileController` | controller | Authenticated user profile |
| `FileStorageService` | service | Chunk storage, per-file locks, purge |
| `EncryptionService` | service | AES key generation, chunk encryption |
| `ActivityService` | service | In-memory event ring buffer, active user map |
| `CustomUserDetailsService` | security | DB-backed Spring UserDetailsService |
| `FileMetadata` | model | JPA entity: file_metadata table |
| `User` | model | JPA entity: users + user_roles tables |
| `ActivityEvent` | model | In-memory event DTO |
| `SystemMetadata` | model | JPA entity: system_metadata table |
| `SystemConfig` | model | JPA entity: system_config table |

---

## 3. Verified Bugs & Critical Issues

These are confirmed bugs identified through direct source code inspection. Each has been traced to its exact file and line.

### BUG-001 — Compilation Failure: ActivityService.purge() references undefined field

**Severity:** CRITICAL (prevents compilation / runtime crash)
**File:** `mft-server/src/main/java/com/mft/server/service/ActivityService.java`
**Line:** 38

**Root Cause:** The `purge()` method calls `recentEvents.clear()`. The field is named `events`, not `recentEvents`. This is an undefined symbol that will cause a compilation error or, if somehow compiled, a NullPointerException.

```java
// CURRENT (broken):
public void purge() {
    recentEvents.clear();  // ← 'recentEvents' does not exist
    activeUsers.clear();
}

// CORRECT:
public void purge() {
    events.clear();
    activeUsers.clear();
}
```

**Impact:** The admin purge endpoint (`POST /api/system/purge`) will fail at the call to `activityService.purge()`. If the JAR compiles at all (it should not), this will throw `NoSuchFieldError` at runtime.

---

### BUG-002 — Security: Password Hashes Exposed in User List API

**Severity:** HIGH
**File:** `mft-server/src/main/java/com/mft/server/controller/UserController.java`
**Line:** 26-27

**Root Cause:** `listUsers()` returns `List<User>` directly. The `User` JPA entity includes a `password` field containing the BCrypt hash. Any admin who calls `GET /api/admin/users` receives all password hashes.

**Impact:** BCrypt hashes are computationally expensive to crack but should never be transmitted over the wire. Password hashes in API responses violate defense-in-depth and compliance requirements (PCI-DSS, SOC 2).

**Fix:** Return a `UserDTO` that excludes the `password` field.

---

### BUG-003 — Logic: Parallel Chunk Ordering Not Guaranteed

**Severity:** HIGH
**File:** `autopost-client/main.go`
**Lines:** 130-155

**Root Cause:** The Go client reads file chunks sequentially and dispatches them to a 4-worker goroutine pool via a buffered channel. Workers consume chunks concurrently. Since `uploadChunk()` is an HTTP call with variable latency, worker 1 may deliver chunk 3 to the server before worker 2 delivers chunk 2. The server appends chunks in the order they are received (via `FileOutputStream` with `append=true`), meaning the reconstructed `.enc` file will have chunks in network-arrival order, not file-byte order.

**Impact:** All multi-chunk files (> 1MB) will be silently corrupted. The file will appear as `COMPLETED` in the DB but the stored bytes will be scrambled.

**Fix:** Two options:
1. **Sequential upload (simple):** Remove the goroutine pool; upload chunks serially. Loses parallelism benefit.
2. **Ordered parallel upload (correct):** Include chunk index and total chunk count in each request. Server holds chunks in a pending map and assembles in order only when all chunks are received.

Option 2 requires server-side changes: store chunks as individual numbered files, assemble on `/complete`.

---

### BUG-004 — Security: Fake RSA Envelope Encryption (AES Keys Stored in Plaintext)

**Severity:** CRITICAL
**File:** `mft-server/src/main/java/com/mft/server/service/EncryptionService.java`
**Lines:** 44-50

**Root Cause:** `encryptAesKeyWithMasterKey()` wraps the AES key in a string prefix: `"RSA_ENCRYPTED_[" + aesKey + "]"`. The "decryption" simply strips that prefix with `String.replace()`. The raw Base64 AES key is stored verbatim in `file_metadata.encrypted_aes_key`.

**Impact:** Any actor with SELECT access to the PostgreSQL `file_metadata` table can read all AES keys and decrypt all stored files. The at-rest encryption provides zero protection against database compromise.

---

### BUG-005 — Cryptographic Weakness: AES/ECB Mode

**Severity:** HIGH
**File:** `mft-server/src/main/java/com/mft/server/service/EncryptionService.java`
**Lines:** 28, 36

**Root Cause:** `Cipher.getInstance("AES")` in Java defaults to `AES/ECB/PKCS5Padding`. ECB (Electronic Codebook) mode is deterministic — identical 16-byte plaintext blocks always produce identical ciphertext blocks. For binary files with repeating patterns (e.g., compressed archives, executable headers, sparse files), ECB leaks structural information through ciphertext patterns.

**Fix:** Replace with `AES/GCM/NoPadding` (authenticated encryption). GCM provides both confidentiality and integrity via authentication tag. Each encryption operation requires a unique 12-byte IV.

---

### BUG-006 — Security: Hardcoded Credentials in Source Code

**Severity:** HIGH
**Files & Lines:**
- `SecurityConfig.java:47` — `passwordEncoder().encode("Renoise28!")`
- `autopost-client/main.go:25` — `Password = "Renoise28!"`
- `cleanse_db.py:10` — `password="Renoise28!"`

**Impact:** Any person with read access to the repository has the admin password. Rotating the password requires recompiling the JAR and rebuilding the Go binary.

---

### BUG-007 — Security: CSRF Protection Disabled

**Severity:** MEDIUM
**File:** `SecurityConfig.java:28`
**Line:** `.csrf(csrf -> csrf.disable())`

**Root Cause:** CSRF is disabled unconditionally. The app uses session-based authentication (`SessionCreationPolicy.IF_REQUIRED`). Any cross-origin request from a browser session can forge state-changing actions (file uploads, purge, user creation) without the user's knowledge.

---

### BUG-008 — Architecture: No File Download Endpoint

**Severity:** HIGH (functional gap)
**Impact:** Files can be uploaded and stored encrypted on disk, but there is no API endpoint to retrieve them. The system is write-only, making stored files permanently inaccessible via the API.

---

### BUG-009 — Security: Dead In-Memory Admin User

**Severity:** LOW (dead code risk)
**File:** `SecurityConfig.java:44-51`

**Root Cause:** An `InMemoryUserDetailsManager` bean is declared with admin credentials, but it is never registered as an `AuthenticationProvider` in the security filter chain. Only `dbAuthenticationProvider()` is registered. The in-memory user cannot authenticate.

**Risk:** This creates confusion about who the "real" admin user is. If a future developer registers the in-memory provider, a second admin account with hardcoded credentials becomes active.

---

### BUG-010 — Performance: ActivityService In-Memory Only (No DB Persistence)

**Severity:** MEDIUM
**File:** `ActivityService.java`

**Root Cause:** All activity events are stored in a `Collections.synchronizedList(new LinkedList<>())` in-memory ring buffer capped at 50 events. Events are lost on restart. The `activity_events` table exists in the DB schema but `ActivityService` never writes to it.

---

## 4. Security Hardening Plan

### 4.1 Credential Externalization (Priority: IMMEDIATE)

All credentials must be removed from source code and loaded from environment variables or a secrets manager.

**Implementation:**

`application.properties` — replace hardcoded values with Spring property placeholders:
```properties
spring.datasource.password=${DB_PASSWORD}
mft.master.key.path=${MASTER_KEY_PATH:/run/secrets/master.key}
```

`SecurityConfig.java` — remove the in-memory admin bean entirely. Admin user is seeded via Flyway V2 migration only.

`autopost-client/main.go` — load from environment:
```go
Password = os.Getenv("MFT_PASSWORD")
```

`docker-compose.yml` — use Docker secrets or `.env` file (not committed to git):
```yaml
environment:
  - SPRING_DATASOURCE_PASSWORD=${DB_PASSWORD}
```

### 4.2 Real Envelope Encryption (Priority: IMMEDIATE)

Replace the fake RSA wrapper with real AES-256-GCM key wrapping.

**Design:**
1. On server startup, load (or generate) a 256-bit Master Encryption Key (MEK) from a key file at `${MASTER_KEY_PATH}`.
2. For each file upload, generate a unique 256-bit Data Encryption Key (DEK) using `SecureRandom`.
3. Wrap the DEK by encrypting it with the MEK using AES-256-GCM with a random 12-byte IV.
4. Store the wrapped DEK (IV + ciphertext + auth tag) as Base64 in `encrypted_aes_key`.
5. For chunk decryption, unwrap the DEK using the MEK, then decrypt the chunk.

**Key storage spec:**
- MEK lives outside the application JAR, ideally in a Docker secret or HSM.
- The `key` file in `mft-server/key` (currently present in the repo) must be added to `.gitignore` immediately.
- MEK rotation: re-encrypt all DEKs with new MEK, then replace MEK atomically.

### 4.3 AES/GCM Cipher Mode (Priority: IMMEDIATE)

Replace ECB with GCM in `EncryptionService`:
- Algorithm: `AES/GCM/NoPadding`
- IV: 12 bytes, `SecureRandom`, unique per chunk
- Auth tag length: 128 bits
- Storage format per chunk: `[12-byte IV][N-byte ciphertext+tag]`

This requires updating both `encryptChunk()` and `decryptChunk()` and the storage format for all `.enc` files.

### 4.4 CSRF Re-enablement (Priority: HIGH)

For stateful session endpoints, enable CSRF with a cookie-to-header pattern (compatible with the WebGL/JS dashboard):
```java
.csrf(csrf -> csrf
    .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
)
```

The API endpoints used by the Go client authenticate via Basic Auth with every request (stateless). These can be exempted:
```java
.csrf(csrf -> csrf
    .ignoringRequestMatchers("/api/files/**")
)
```

### 4.5 Password Hash Sanitization in User API (Priority: HIGH)

Introduce a `UserDTO`:
```java
public record UserDTO(UUID id, String username, boolean enabled, Set<String> roles) {}
```

All user-returning endpoints map `User` → `UserDTO` before serialization.

### 4.6 Input Validation (Priority: MEDIUM)

- `originalFilename`: validate against path traversal patterns (reject filenames containing `..`, `/`, `\`, null bytes).
- `totalSize`: enforce a maximum (e.g., 5GB matching multipart config), reject negative values.
- `chunkData`: enforce max chunk size (e.g., 10MB), reject empty chunks.
- Use `@Valid` + `@NotBlank`, `@Size`, `@Max` Jakarta Validation annotations on request models.

### 4.7 Rate Limiting (Priority: MEDIUM)

Add a Spring `HandlerInterceptor` or use Nginx `limit_req_zone` for:
- Auth endpoints: 10 req/min per IP
- Chunk upload: 1000 req/min per authenticated user
- Admin endpoints: 30 req/min per user

### 4.8 Remove `key` File from Repository (Priority: IMMEDIATE)

The file `mft-server/key` is present in the repo. If this contains any key material, it must be:
1. Immediately revoked / regenerated.
2. Added to `.gitignore`.
3. Purged from git history (`git filter-repo`).

---

## 5. Engineering Improvements Specification

### 5.1 Ordered Chunk Upload Protocol

**Problem:** Parallel chunk uploads arrive at the server in non-deterministic order, corrupting files.

**Proposed Protocol Change:**

Request: `POST /api/files/{fileId}/chunk`
```json
{
  "chunkIndex": 3,
  "totalChunks": 10
}
```
(as multipart fields alongside the chunk binary)

**Server behavior:**
1. Store each received chunk as a temporary file: `{fileId}_{chunkIndex}.part`
2. When all `totalChunks` parts are present (tracked in `activeTransfers` map), assemble in index order.
3. Assembly happens in `completeUpload()`: open each `.part` file in order, encrypt and append.
4. Delete `.part` files after assembly.

**Alternative (simpler):** Force sequential upload in the Go client (remove worker pool, upload chunks in a single goroutine). Loses parallel benefit but is immediately correct.

**Go client change (simple fix):**
```go
// Replace parallel worker pool with sequential loop
for chunkIndex, chunkData := range chunks {
    if err := uploadChunk(fileID, chunkIndex, len(chunks), chunkData); err != nil {
        log.Printf("Chunk %d failed: %v", chunkIndex, err)
        return
    }
}
```

### 5.2 File Download Endpoint

**New endpoint:** `GET /api/files/{fileId}/download`

**Behavior:**
1. Retrieve `FileMetadata` by ID; verify status is `COMPLETED`.
2. Verify requesting user has permission (owner or ADMIN role).
3. Unwrap DEK from DB using MEK.
4. Open `.enc` file, decrypt chunks using DEK, stream decrypted bytes to response.
5. Set `Content-Disposition: attachment; filename="{originalFilename}"`.
6. Set `Content-Type: application/octet-stream`.

**Streaming implementation:** Use Spring's `StreamingResponseBody` to avoid loading the entire file into heap. Read-decrypt-write in 1MB windows.

### 5.3 File Delete Endpoint

**New endpoint:** `DELETE /api/files/{fileId}`

**Behavior:**
1. Verify requesting user is owner or ADMIN.
2. Delete the `.enc` file from disk.
3. Delete `file_metadata` row from DB.
4. Log deletion in activity_events.
5. Invalidate file list cache.

**Secure deletion:** Overwrite the file with random bytes before deletion (single-pass is sufficient for non-SSD; use 3-pass DoD for HDDs if required by policy).

### 5.4 Activity Event DB Persistence

**Problem:** `ActivityService` stores events in a 50-item in-memory ring buffer. All history is lost on restart.

**Fix:**
1. Inject `ActivityEventRepository` into `ActivityService`.
2. Write events to DB on each `log()` call.
3. `getRecentEvents()` queries DB (with `LIMIT 100 ORDER BY created_at DESC`).
4. Keep the in-memory buffer as a read cache (TTL: 2s) for the SSE stream.
5. `purge()` deletes from DB and clears in-memory buffer (fix BUG-001 simultaneously).

### 5.5 Session Token / JWT Authentication for Client

**Problem:** The Go client sends BCrypt-authenticated Basic Auth with every chunk request. Under high concurrency (hundreds of 1MB chunks), BCrypt verification on every request is a CPU bottleneck (as documented in the existing test report).

**Fix: Session Token Flow:**
1. Go client authenticates once: `POST /api/auth/login` → receives a session cookie or Bearer JWT.
2. All subsequent chunk uploads include only the session cookie or `Authorization: Bearer {token}`.
3. Server validates JWT (HMAC-SHA256 or EdDSA) — O(1) operation vs. BCrypt O(2^12).

**JWT spec:**
- Algorithm: `EdDSA` (Ed25519) — fast, small, secure
- Claims: `sub` (username), `roles`, `iat`, `exp` (1 hour)
- Stored server-side in Redis (for revocation) — optional for v2, required for v3

### 5.6 Configurable Chunk Size

**Problem:** The Go client uses a hardcoded 1MB chunk size. The DB `system_config` table has `mft.chunk.size=5242880` (5MB) but it is never used.

**Fix:**
1. Go client fetches chunk size from `GET /api/config/chunk-size` on startup.
2. Spring `ConfigController` serves the value from `system_config` table.
3. Default: 1MB for low-bandwidth, configurable up to 50MB for high-throughput LAN scenarios.

### 5.7 File Retention & Secure Deletion Scheduler

**The DB already has `mft.retention.days=30` in `system_config`.** This feature is documented in the roadmap but not implemented.

**Implementation:**
1. Spring `@Scheduled(cron = "0 3 * * *")` daily task in a new `RetentionService`.
2. Query `file_metadata` where `created_at < NOW() - INTERVAL '{retention_days} days'` AND `status = 'COMPLETED'`.
3. For each expired file: secure-delete `.enc` file, delete DB row, log event.
4. Configurable: `ACTIVE` (auto-delete) / `ARCHIVE` (mark for manual review).

### 5.8 Health Check & Metrics Endpoint

**Add:** `GET /api/public/health`

Response:
```json
{
  "status": "UP",
  "db": "UP",
  "storage": "UP",
  "version": "1.0.0",
  "activeTransfers": 3,
  "storageUsedBytes": 10485760
}
```

Integrates with Docker `HEALTHCHECK` and monitoring systems (Prometheus via Spring Actuator).

### 5.9 Structured Logging

**Problem:** `CustomUserDetailsService` uses `System.out.println()` for security-relevant events (login attempts, user lookups, role mappings). This output is unstructured and not queryable.

**Fix:**
1. Replace all `System.out.println()` with `private static final Logger log = LoggerFactory.getLogger(...)`.
2. Use `log.info()` / `log.warn()` / `log.error()` with structured MDC context (username, request ID).
3. Configure `logback-spring.xml` for JSON output in production (Logstash-compatible).
4. Never log passwords, keys, or session tokens.

### 5.10 UserDTO / API Response Hardening

All API responses must be reviewed against the following rules:
- No password hashes in any response (BUG-002 fix).
- No internal exception stack traces in error responses (return generic messages).
- No raw UUID stored filenames exposed in file list (only `originalFilename`, `id`, `status`, `totalSize`, `createdAt`).

### 5.11 Download Progress Tracking

**Extend `file_metadata`:**
```sql
ALTER TABLE file_metadata ADD COLUMN download_count INTEGER DEFAULT 0;
ALTER TABLE file_metadata ADD COLUMN last_downloaded_at TIMESTAMP;
```

Track in `FileStorageService.downloadFile()`: increment `download_count`, set `last_downloaded_at`.

---

## 6. API Reference (Current + Proposed)

### Authentication
All endpoints (except `/api/public/**`) require HTTP Basic Auth or session cookie.
Admin endpoints (`/api/admin/**`, `/api/system/purge`) require `ROLE_ADMIN`.

### Current Endpoints

| Method | Path | Auth | Description |
|---|---|---|---|
| POST | `/api/files/init` | USER | Initialize upload, returns `fileId` + metadata |
| POST | `/api/files/{fileId}/chunk` | USER | Upload a chunk (multipart `chunk` field) |
| POST | `/api/files/{fileId}/complete` | USER | Finalize upload |
| GET | `/api/files` | USER | List all files (cached, 5s TTL) |
| GET | `/api/system/ping` | USER | Health ping + maintenance mode flag |
| POST | `/api/system/purge` | ADMIN | Delete all files and activity logs |
| GET | `/api/admin/users` | ADMIN | List all users |
| POST | `/api/admin/users` | ADMIN | Create user |
| DELETE | `/api/admin/users/{id}` | ADMIN | Delete user |
| GET | `/api/admin/activity` | ADMIN | Recent activity events |
| GET | `/api/admin/activity/stream` | ADMIN | SSE activity stream |
| GET | `/api/admin/activity/active-users` | ADMIN | Currently active users |

### Proposed New Endpoints (v2)

| Method | Path | Auth | Description |
|---|---|---|---|
| POST | `/api/auth/login` | none | Authenticate, return JWT / session cookie |
| POST | `/api/auth/logout` | USER | Invalidate session |
| GET | `/api/files/{fileId}/download` | USER | Stream-decrypt and download file |
| DELETE | `/api/files/{fileId}` | USER/ADMIN | Secure delete file |
| GET | `/api/files/{fileId}` | USER | Get single file metadata |
| GET | `/api/public/health` | none | System health check (DB + storage) |
| GET | `/api/config/chunk-size` | USER | Get configured chunk size |
| PUT | `/api/admin/config/{key}` | ADMIN | Update system config value |

### Request / Response Schemas

**POST /api/files/init — Request:**
```json
{
  "originalFilename": "report_q1_2026.pdf",
  "totalSize": 52428800
}
```

**POST /api/files/init — Response:**
```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "originalFilename": "report_q1_2026.pdf",
  "status": "INITIATED",
  "totalSize": 52428800,
  "createdAt": "2026-03-05T14:24:02"
}
```

**POST /api/files/{fileId}/chunk — Request:**
```
Content-Type: multipart/form-data
chunk: [binary data]
chunkIndex: 0       [PROPOSED: required for ordering]
totalChunks: 50     [PROPOSED: required for ordering]
```

**GET /api/files — Response:**
```json
[
  {
    "id": "550e8400-e29b-41d4-a716-446655440000",
    "originalFilename": "report_q1_2026.pdf",
    "status": "COMPLETED",
    "totalSize": 52428800,
    "uploadedSize": 52428800,
    "createdAt": "2026-03-05T14:24:02",
    "updatedAt": "2026-03-05T14:24:45"
  }
]
```

Note: `storedFilename` and `encryptedAesKey` must never appear in API responses.

---

## 7. Database Schema Specification

### V5 Migration (Proposed) — Download Tracking

```sql
-- V5: Download Tracking + Activity Persistence
ALTER TABLE file_metadata
    ADD COLUMN IF NOT EXISTS download_count INTEGER DEFAULT 0,
    ADD COLUMN IF NOT EXISTS last_downloaded_at TIMESTAMP,
    ADD COLUMN IF NOT EXISTS owner_user_id UUID REFERENCES users(id),
    ADD COLUMN IF NOT EXISTS chunk_count INTEGER DEFAULT 0;

-- Persist activity events to DB (previously in-memory only)
CREATE TABLE IF NOT EXISTS activity_events (
    id BIGSERIAL PRIMARY KEY,
    event_type VARCHAR(50) NOT NULL,
    username VARCHAR(255) NOT NULL,
    detail TEXT,
    occurred_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_activity_events_occurred_at ON activity_events (occurred_at DESC);
CREATE INDEX idx_activity_events_username ON activity_events (username);
CREATE INDEX idx_file_metadata_owner ON file_metadata (owner_user_id);
```

### V6 Migration (Proposed) — Chunk Part Tracking

```sql
-- V6: Chunk part tracking for ordered parallel reassembly
CREATE TABLE file_chunks (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    file_id UUID NOT NULL REFERENCES file_metadata(id) ON DELETE CASCADE,
    chunk_index INTEGER NOT NULL,
    part_filename VARCHAR(255) NOT NULL,
    received_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (file_id, chunk_index)
);

CREATE INDEX idx_file_chunks_file_id ON file_chunks (file_id);
```

### Full Schema Reference

```sql
-- file_metadata
id              UUID        PK
original_filename VARCHAR(255) NOT NULL
stored_filename VARCHAR(255) NOT NULL
total_size      BIGINT
uploaded_size   BIGINT DEFAULT 0
status          VARCHAR(50) NOT NULL  -- INITIATED, IN_PROGRESS, COMPLETED, FAILED
encrypted_aes_key TEXT NOT NULL       -- v2: real AES-GCM wrapped DEK
created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
updated_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
download_count  INTEGER DEFAULT 0    -- v5
last_downloaded_at TIMESTAMP         -- v5
owner_user_id   UUID FK→users.id     -- v5
chunk_count     INTEGER DEFAULT 0    -- v5

-- users
id              UUID        PK
username        VARCHAR(255) UNIQUE NOT NULL
password        VARCHAR(255) NOT NULL    -- BCrypt hash
enabled         BOOLEAN DEFAULT TRUE

-- user_roles
user_id         UUID FK→users.id
role            VARCHAR(50)
PK(user_id, role)

-- system_config
config_key      VARCHAR(255) PK
config_value    TEXT NOT NULL
description     TEXT

-- system_metadata
id              SERIAL PK
version         VARCHAR(50) NOT NULL
data_model_version VARCHAR(50) NOT NULL
last_upgrade_at TIMESTAMP
system_status   VARCHAR(50) DEFAULT 'HEALTHY'

-- activity_events (v5, replaces in-memory only)
id              BIGSERIAL PK
event_type      VARCHAR(50) NOT NULL
username        VARCHAR(255) NOT NULL
detail          TEXT
occurred_at     TIMESTAMP DEFAULT CURRENT_TIMESTAMP
```

---

## 8. Go Client Specification

### 8.1 Current Architecture

- Entry: `main.go` — fsnotify watcher + parallel upload pipeline
- `stresstool.go` — concurrent load testing tool
- `hashgen.go` — file hash generation utility
- Watch directory: `./sync_folder`
- Concurrency: 4 workers (goroutine pool via buffered channel)
- Chunk size: 1MB hardcoded
- Auth: HTTP Basic, hardcoded credentials

### 8.2 v2 Client Spec

**Configuration:** Load from environment variables with fallback to `config.yaml`:
```go
type Config struct {
    ServerURL   string `yaml:"server_url" env:"MFT_SERVER_URL"`
    WatchDir    string `yaml:"watch_dir"  env:"MFT_WATCH_DIR"`
    Username    string `yaml:"username"   env:"MFT_USERNAME"`
    Password    string `yaml:"password"   env:"MFT_PASSWORD"`
    Concurrency int    `yaml:"concurrency" env:"MFT_CONCURRENCY"`
}
```

**Authentication:** Session token flow:
1. `POST /api/auth/login` with credentials → receive JWT or session cookie.
2. Store token in memory only (never write to disk).
3. Re-authenticate on 401 response (token refresh).

**Ordered chunk upload:**
1. Enumerate all chunks before uploading.
2. Pass `chunkIndex` and `totalChunks` in each request.
3. Retry failed chunks up to 3 times with exponential backoff.

**Integrity verification:**
1. Compute SHA-256 of the source file before upload.
2. After upload complete, call `GET /api/files/{fileId}` to verify `uploadedSize == totalSize`.
3. Log a warning if sizes don't match.

**Graceful shutdown:**
1. Trap `SIGTERM` / `SIGINT`.
2. Complete in-progress uploads before exiting.
3. Do not start new uploads during shutdown.

---

## 9. Infrastructure & Deployment Spec

### 9.1 Docker Compose (Production Hardening)

```yaml
version: '3.8'

services:
  db:
    image: postgres:15-alpine
    environment:
      POSTGRES_USER: ${DB_USER}
      POSTGRES_PASSWORD: ${DB_PASSWORD}
      POSTGRES_DB: mft_db
    volumes:
      - pgdata:/var/lib/postgresql/data
    # Do NOT expose 5432 to host in production
    expose:
      - "5432"
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U ${DB_USER}"]
      interval: 10s
      timeout: 5s
      retries: 5

  server:
    build: .
    environment:
      - SPRING_DATASOURCE_URL=jdbc:postgresql://db:5432/mft_db
      - SPRING_DATASOURCE_USERNAME=${DB_USER}
      - SPRING_DATASOURCE_PASSWORD=${DB_PASSWORD}
      - MASTER_KEY_PATH=/run/secrets/master.key
    secrets:
      - master.key
    depends_on:
      db:
        condition: service_healthy
    volumes:
      - ./storage:/app/storage
    expose:
      - "8080"

  proxy:
    image: nginx:alpine
    ports:
      - "8888:80"
      - "4443:443"
    volumes:
      - ./nginx/nginx.conf:/etc/nginx/nginx.conf:ro
      - ./nginx/html:/usr/share/nginx/html:ro
      - ./nginx/ssl:/etc/nginx/ssl:ro  # TLS certificates
    depends_on:
      - server

secrets:
  master.key:
    file: ./secrets/master.key

volumes:
  pgdata:
```

### 9.2 Dockerfile (Multi-Stage Build)

```dockerfile
# Stage 1: Build
FROM maven:3.9-eclipse-temurin-17 AS builder
WORKDIR /app
COPY pom.xml .
RUN mvn dependency:go-offline
COPY src ./src
RUN mvn package -DskipTests

# Stage 2: Runtime
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
RUN addgroup -S mft && adduser -S mft -G mft
COPY --from=builder /app/target/*.jar app.jar
RUN mkdir -p /app/storage && chown -R mft:mft /app
USER mft
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=10s --retries=3 \
  CMD wget -qO- http://localhost:8080/api/public/health || exit 1
ENTRYPOINT ["java", "-jar", "app.jar"]
```

### 9.3 TLS Configuration (Nginx)

The port 4443 is ready but not configured. Production Nginx TLS configuration:

```nginx
server {
    listen 443 ssl http2;
    server_name your-domain.com;

    ssl_certificate     /etc/nginx/ssl/fullchain.pem;
    ssl_certificate_key /etc/nginx/ssl/privkey.pem;
    ssl_protocols       TLSv1.3;
    ssl_ciphers         TLS_AES_256_GCM_SHA384:TLS_CHACHA20_POLY1305_SHA256;
    ssl_session_timeout 1d;
    ssl_session_cache   shared:SSL:50m;

    add_header Strict-Transport-Security "max-age=31536000; includeSubDomains" always;
    add_header X-Frame-Options DENY always;
    add_header X-Content-Type-Options nosniff always;
    add_header Content-Security-Policy "default-src 'self'" always;

    # ... proxy_pass config as current ...
}

server {
    listen 80;
    return 301 https://$host$request_uri;
}
```

---

## 10. Testing & QA Requirements

### 10.1 Unit Test Coverage Targets (v2)

| Component | Target Coverage | Current |
|---|---|---|
| `EncryptionService` | 95% | ~0% |
| `FileStorageService` | 90% | ~0% |
| `ActivityService` | 85% | ~0% |
| `FileTransferController` | 80% | ~0% |
| `SecurityConfig` | 70% | ~0% |

### 10.2 Required Unit Tests

**EncryptionService:**
- `generateAesKey()` returns 256-bit Base64 key
- `encryptChunk()` + `decryptChunk()` round-trip returns original bytes
- `encryptChunk()` with GCM produces different ciphertext for same plaintext (IV uniqueness)
- `encryptAesKeyWithMasterKey()` returns bytes not equal to input
- `decryptAesKeyWithMasterKey(encryptAesKeyWithMasterKey(key)) == key`

**FileStorageService:**
- `initializeUpload()` creates DB record with status `INITIATED`
- `appendChunk()` throws `IllegalArgumentException` for unknown fileId
- `completeUpload()` transitions status to `COMPLETED`
- Concurrent `appendChunk()` calls do not interleave (ReentrantLock test)

**ActivityService:**
- `log()` prepends to event list
- Event list does not exceed `MAX_EVENTS`
- `purge()` clears both `events` and `activeUsers`
- `getActiveUsers()` returns only users within 10-second window

### 10.3 Integration Test Requirements

Use `@SpringBootTest` with `@TestContainers` (PostgreSQL testcontainer):

- Full upload lifecycle (init → N chunks → complete → list → download → delete)
- Auth: unauthenticated requests return 401
- Auth: USER role cannot access `/api/admin/**`
- Auth: ADMIN role can access all endpoints
- Purge endpoint clears DB state
- File list cache returns stale data within TTL, refreshes after TTL

### 10.4 Security Test Requirements

- Path traversal in `originalFilename`: send `../../etc/passwd`, verify 400 response
- Oversized chunk: send 20MB chunk, verify rejection
- Missing auth: all non-public endpoints return 401
- Role escalation: USER attempting admin action returns 403
- SQL injection in filename: send `'; DROP TABLE file_metadata; --` as filename

### 10.5 Load Test Targets

Based on existing benchmark results (8.86 files/sec @ 20 workers):

| Metric | Current | v2 Target |
|---|---|---|
| Upload throughput | 8.86 files/sec | 15+ files/sec |
| Chunk latency (p99) | <50ms | <30ms |
| Concurrent uploads | 20 workers | 50 workers |
| File list latency | <50ms | <20ms |
| Max file size tested | 5MB | 500MB |

---

## 11. Configuration Reference

### application.properties (v2)

```properties
# Database
spring.datasource.url=jdbc:postgresql://localhost:5432/mft_db
spring.datasource.username=${DB_USER:mft_user}
spring.datasource.password=${DB_PASSWORD}

# JPA
spring.jpa.hibernate.ddl-auto=none
spring.jpa.show-sql=false
spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect

# Flyway
spring.flyway.enabled=true
spring.flyway.baseline-on-migrate=true
spring.flyway.baseline-version=0
spring.flyway.locations=classpath:db/migration

# File Upload
spring.servlet.multipart.max-file-size=5GB
spring.servlet.multipart.max-request-size=5GB
spring.servlet.multipart.enabled=true

# Storage
mft.storage.path=${STORAGE_PATH:storage}
mft.master.key.path=${MASTER_KEY_PATH:/run/secrets/master.key}

# Tomcat
server.forwarded-headers-strategy=framework
server.tomcat.threads.max=500
server.tomcat.threads.min-spare=50
server.tomcat.max-connections=10000
server.tomcat.accept-count=1000
server.tomcat.connection-timeout=20000

# HikariCP
spring.datasource.hikari.maximum-pool-size=50
spring.datasource.hikari.minimum-idle=10
spring.datasource.hikari.idle-timeout=300000
spring.datasource.hikari.connection-timeout=20000

# Logging
logging.level.org.springframework.security=WARN
logging.level.com.mft.server=INFO
```

---

## 12. Implementation Roadmap

### Phase 0 — Critical Bug Fixes (1–3 days, do immediately)

| # | Task | File | Impact |
|---|---|---|---|
| 0.1 | Fix `ActivityService.purge()` field name | `ActivityService.java:38` | BUG-001: fixes compilation |
| 0.2 | Add `key` to `.gitignore` | `.gitignore` | BUG-006: key file not in repo |
| 0.3 | Remove hardcoded password from `SecurityConfig` | `SecurityConfig.java:47` | BUG-006 |
| 0.4 | Remove hardcoded password from Go client | `main.go:25` | BUG-006 |
| 0.5 | Return `UserDTO` instead of `User` in `UserController` | `UserController.java` | BUG-002 |

### Phase 1 — Security Hardening (1–2 weeks)

| # | Task | Spec Section |
|---|---|---|
| 1.1 | Real AES-GCM envelope encryption (replace fake RSA) | 4.2, 4.3 |
| 1.2 | Externalize all credentials to environment variables | 4.1 |
| 1.3 | Re-enable CSRF with cookie-to-header pattern | 4.4 |
| 1.4 | Input validation on filenames and sizes | 4.6 |
| 1.5 | Replace System.out.println with structured logging | 5.9 |
| 1.6 | Add `/api/public/health` endpoint | 5.8 |

### Phase 2 — Functional Completeness (2–3 weeks)

| # | Task | Spec Section |
|---|---|---|
| 2.1 | File download endpoint (stream-decrypt) | 5.2 |
| 2.2 | File delete endpoint (secure deletion) | 5.3 |
| 2.3 | Ordered chunk upload (fix corruption bug) | 5.1 |
| 2.4 | Activity events DB persistence | 5.4 |
| 2.5 | V5 + V6 Flyway migrations | 7 |

### Phase 3 — Performance & Operations (3–4 weeks)

| # | Task | Spec Section |
|---|---|---|
| 3.1 | JWT/session token auth for Go client | 5.5 |
| 3.2 | Rate limiting (Nginx + Spring) | 4.7 |
| 3.3 | Configurable chunk size from system_config | 5.6 |
| 3.4 | Retention scheduler (auto-delete expired files) | 5.7 |
| 3.5 | Download progress tracking | 5.11 |
| 3.6 | TLS configuration in Nginx | 9.3 |
| 3.7 | Multi-stage Docker build | 9.2 |

### Phase 4 — Testing & Coverage (ongoing)

| # | Task | Spec Section |
|---|---|---|
| 4.1 | Unit tests for all services | 10.2 |
| 4.2 | Integration tests with Testcontainers | 10.3 |
| 4.3 | Security test suite | 10.4 |
| 4.4 | Load test at 50-worker concurrency | 10.5 |
| 4.5 | CI/CD pipeline (GitHub Actions) | — |

---

*End of SyncForge Technical Specification v2.0*
*Draygen Systems Engineering — March 5, 2026*
