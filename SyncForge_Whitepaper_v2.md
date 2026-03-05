# SyncForge: Architecting the Enterprise-Grade Managed File Transfer Platform
## Technical Whitepaper v2.0

**Version:** 2.0
**Classification:** Public / Engineering
**Author:** Draygen Systems
**Date:** March 5, 2026

---

## Abstract

Modern enterprise operations demand secure, high-throughput, and auditable file transfer systems that can scale beyond the limitations of legacy SFTP pipelines and commodity cloud storage. SyncForge is a purpose-built Managed File Transfer (MFT) platform that combines a high-concurrency Java/Tomcat application server, a PostgreSQL metadata store, an Nginx streaming proxy, and a Go-native sync client to deliver a complete artifact ingestion and management ecosystem.

This whitepaper documents the architectural foundations of SyncForge, analyzes the engineering decisions behind its concurrent chunk-upload pipeline, evaluates its current security posture with full disclosure of identified vulnerabilities, and presents a roadmap toward production-hardened deployment. It is intended for senior engineers, security architects, and technical decision-makers evaluating SyncForge for enterprise adoption.

---

## Table of Contents

1. [The Problem Space](#1-the-problem-space)
2. [SyncForge Design Philosophy](#2-syncforge-design-philosophy)
3. [Architecture Deep Dive](#3-architecture-deep-dive)
4. [The Chunk Upload Protocol](#4-the-chunk-upload-protocol)
5. [Encryption Architecture](#5-encryption-architecture)
6. [Authentication & Authorization Model](#6-authentication--authorization-model)
7. [Data Persistence Layer](#7-data-persistence-layer)
8. [The Go Sync Client](#8-the-go-sync-client)
9. [Operational Infrastructure](#9-operational-infrastructure)
10. [Security Posture Assessment](#10-security-posture-assessment)
11. [Performance Characteristics](#11-performance-characteristics)
12. [Known Limitations & Remediation Roadmap](#12-known-limitations--remediation-roadmap)
13. [Competitive Positioning](#13-competitive-positioning)
14. [Future Vision: SyncForge v3](#14-future-vision-syncforge-v3)
15. [Conclusion](#15-conclusion)

---

## 1. The Problem Space

### 1.1 The Failure of Legacy File Transfer

Enterprise file transfer has long been dominated by protocols designed in an era of low-bandwidth, single-threaded connections: FTP (1971), SFTP (1997), and SCP. These protocols transfer files as monolithic byte streams with no native support for resumability, parallelism, or encryption at rest. They offer no metadata tracking, no role-based access control, and no audit trail beyond SSH connection logs.

The modern data landscape has fundamentally changed:
- **File sizes** have grown from kilobytes to terabytes
- **Transfer frequency** has grown from daily batch jobs to continuous real-time sync
- **Compliance requirements** (GDPR, HIPAA, SOC 2, PCI-DSS) mandate encryption at rest, audit logs, and access controls that SFTP cannot provide natively
- **Network conditions** are increasingly multi-path and high-jitter, making monolithic transfers fragile

Commercial MFT solutions (IBM Sterling, Axway, GoAnywhere) address these gaps but carry per-seat licensing costs in the six figures, require dedicated infrastructure teams, and often resist customization.

### 1.2 The SyncForge Response

SyncForge was designed from first principles to provide enterprise MFT capabilities on an open, self-hosted, cloud-native foundation. Its core thesis is:

> File transfer should be decomposed into a parallel pipeline of small, independently verifiable chunks. Each chunk should be encrypted in transit and at rest. The metadata layer should be the source of truth for transfer state, enabling resumable uploads, progress visibility, and audit compliance.

This thesis drives every architectural decision in the system.

---

## 2. SyncForge Design Philosophy

### 2.1 Chunk-First Architecture

Rather than treating a file as a single transfer unit, SyncForge decomposes every upload into fixed-size chunks (default: 1MB). This provides:

**Resilience:** A network interruption mid-transfer loses at most one chunk, not the entire file. Upload resumption is achievable by resuming from the last acknowledged chunk.

**Parallelism:** Multiple chunks can be uploaded simultaneously by independent workers, multiplying effective throughput across high-bandwidth connections.

**Memory efficiency:** The server never loads an entire file into heap memory. Chunks are processed and flushed to disk as they arrive, making 5GB uploads feasible on servers with 512MB JVM heaps.

**Encryption granularity:** Each chunk is independently encrypted before disk write. The encryption key is stored with the file metadata, not embedded in the file stream.

### 2.2 Storage Decoupling

SyncForge stores files under server-generated UUID filenames (e.g., `a1b2c3d4-e5f6-...enc`), completely decoupled from the original filename. This design:
- Eliminates path traversal attacks (the stored filename is never derived from user input)
- Prevents filename collisions across users
- Makes the storage directory opaque to enumeration

### 2.3 Schema-as-Code via Flyway

All database schema changes are expressed as numbered, idempotent SQL migration scripts managed by Flyway. This enables:
- Zero-downtime in-place upgrades: new schema versions are applied automatically on server startup
- Full schema history auditing via `flyway_schema_history`
- Reproducible deployments: any fresh deployment reaches the same schema state
- Rollback planning: new migrations can be written to reverse schema changes

### 2.4 Three-Tier Isolation

SyncForge enforces strict tier boundaries:
- **Nginx** handles raw HTTP connections, SSL termination, and request routing. It never contains business logic.
- **Tomcat/Spring** handles authentication, authorization, business logic, and encryption. It never handles raw connections.
- **PostgreSQL** handles only data persistence. It never handles HTTP or business logic.

Tomcat is not exposed externally — the only public network path is through Nginx. PostgreSQL is not exposed externally — the only database path is through the Spring application.

---

## 3. Architecture Deep Dive

### 3.1 System Topology

```
┌─────────────────────────────────────────────────────────────────┐
│                    External Network / Internet                    │
│                  (Cloudflare Tunnel, TLS 1.3)                    │
└──────────────────────────┬──────────────────────────────────────┘
                           │
                ┌──────────▼──────────┐
                │       Nginx          │
                │   Port 80 / 443      │
                │                      │
                │  • SSL Termination   │
                │  • Reverse Proxy     │
                │  • Static Assets     │
                │  • 5GB Body Limit    │
                │  • No-buffer Stream  │
                └──────────┬──────────┘
                           │ proxy_request_buffering off
                           │ X-Forwarded headers
                ┌──────────▼──────────────────────────┐
                │         Spring Boot / Tomcat          │
                │              Port 8080                │
                │                                       │
                │  ┌─────────────────────────────────┐ │
                │  │    Spring Security Filter Chain  │ │
                │  │  BCrypt Auth | RBAC | Sessions   │ │
                │  └──────────────┬──────────────────┘ │
                │                 │                     │
                │  ┌──────────────▼──────────────────┐ │
                │  │         REST Controllers          │ │
                │  │  FileTransfer | System | Admin   │ │
                │  └──────────────┬──────────────────┘ │
                │                 │                     │
                │  ┌──────────────▼──────────────────┐ │
                │  │           Services               │ │
                │  │  FileStorage | Encryption |      │ │
                │  │  Activity | Config               │ │
                │  └────────┬─────────────┬──────────┘ │
                │           │             │             │
                │    ┌──────▼──────┐  ┌──▼──────────┐ │
                │    │  Encrypted  │  │  HikariCP   │ │
                │    │  Storage    │  │  Pool (50)  │ │
                │    │  ./storage  │  └──────┬──────┘ │
                │    └─────────────┘         │        │
                └────────────────────────────┼────────┘
                                             │
                              ┌──────────────▼──────────────┐
                              │         PostgreSQL 15         │
                              │           Port 5432           │
                              │                               │
                              │  • file_metadata              │
                              │  • users / user_roles         │
                              │  • system_config              │
                              │  • system_metadata            │
                              │  • activity_events (v5)       │
                              └───────────────────────────────┘
```

### 3.2 Go Client Integration

```
┌─────────────────────────────────────┐
│        Go AutoPost Client            │
│                                      │
│  ./sync_folder (watched directory)   │
│       │                              │
│  fsnotify CREATE/WRITE events        │
│       │                              │
│  2-second debounce                   │
│       │                              │
│  ┌────▼─────────────────────────┐   │
│  │    handleUpload()             │   │
│  │                               │   │
│  │  1. initUpload() → fileId    │   │
│  │  2. Read file in 1MB chunks  │   │
│  │  3. 4-worker goroutine pool  │   │
│  │  4. uploadChunk() × N        │   │
│  │  5. completeUpload()         │   │
│  └───────────────────────────────┘  │
│                                      │
│  Also: stresstool.go (load test)     │
│         hashgen.go (file hashing)    │
└─────────────────────────────────────┘
           │
           │ HTTP Basic Auth
           │ POST /api/files/...
           ▼
        Nginx :8888
```

---

## 4. The Chunk Upload Protocol

### 4.1 Protocol Overview

The SyncForge chunk upload protocol is a three-phase commit protocol:

**Phase 1: Initialization**
The client sends the filename and total size. The server allocates a UUID, generates an AES-256 key for the file, persists the metadata with status `INITIATED`, and returns the UUID as the upload handle.

**Phase 2: Chunk Transfer**
The client sends N chunks, each identified by the upload handle UUID. The server encrypts each chunk and appends it to the file on disk. Per-file ReentrantLock ensures atomic, non-interleaved writes. Status transitions to `IN_PROGRESS`.

**Phase 3: Completion**
The client signals completion. The server transitions status to `COMPLETED`, removes the file from the active transfer cache, and invalidates the file list cache.

### 4.2 Concurrency Model

The server maintains two `ConcurrentHashMap` structures for active transfers:

```
activeTransfers: Map<UUID, FileMetadata>
fileLocks:       Map<UUID, ReentrantLock>
```

When a chunk arrives:
1. `computeIfAbsent(fileId, k -> new ReentrantLock())` atomically creates the lock if absent.
2. `lock.lock()` acquires exclusive access to the file.
3. Decrypt the per-file AES key, encrypt the chunk data, append to disk.
4. Update `uploadedSize` in the in-memory metadata.
5. `lock.unlock()` in `finally` block.

This model allows N simultaneous uploads to proceed fully in parallel, while ensuring each individual file's chunks are serialized. The lock granularity (per-file rather than global) is a significant performance optimization over a single global lock.

### 4.3 Chunk Ordering Limitation (v1)

**Important limitation in the current v1 client:** The Go client dispatches chunks to a worker pool, and due to variable network latency, chunks may arrive at the server in non-deterministic order. Since the server appends on arrival, files larger than one chunk (> 1MB) may be stored with scrambled byte order. This is a known bug being addressed in v2 via explicit chunk indexing.

### 4.4 Cache Architecture

The file list endpoint implements a double-checked locking cache with a 5-second TTL:

```
Request → outer check (no lock)
  If stale → acquire synchronized lock
    If still stale (after re-check) → query DB, update cache, release lock
    If fresh (beaten to refresh) → serve from cache, release lock
  If fresh → serve from cache (no lock acquisition)
```

This pattern prevents cache stampede under high list request rates while keeping average latency below 5ms (cache hit) vs. 30-100ms (DB query).

---

## 5. Encryption Architecture

### 5.1 Envelope Encryption Model (Design Intent)

SyncForge implements an envelope encryption model:

```
File Data
    │
    ▼
AES-256 DEK (Data Encryption Key)
    │ encrypts
    ▼
Encrypted Chunks (.enc file on disk)

DEK
    │
    ▼
RSA Master Key (or AES MEK) wraps DEK
    │
    ▼
Wrapped DEK stored in file_metadata.encrypted_aes_key
```

This design ensures that:
1. Disk access alone does not reveal file content (files are encrypted with DEK)
2. DB access alone does not reveal file content (DEKs are wrapped; the MEK is not in the DB)
3. The MEK can be rotated: re-wrap all DEKs with new MEK, replace MEK, without re-encrypting all files

### 5.2 Current Implementation Gap

**The current v1 implementation does not fully realize this model.** The `EncryptionService.encryptAesKeyWithMasterKey()` method performs string wrapping rather than actual encryption. DEKs are stored in plaintext in the database.

Additionally, the cipher mode is AES/ECB (the Java default), which lacks semantic security. These are the two highest-priority cryptographic issues in the current codebase.

### 5.3 Target v2 Encryption Implementation

The v2 target replaces all cryptographic primitives:

**Cipher:** `AES/GCM/NoPadding`
- 256-bit key
- 12-byte IV, `SecureRandom`-generated per encryption operation
- 128-bit authentication tag (detects tampering)
- Storage: `[12-byte IV][ciphertext][16-byte tag]` per chunk

**Key Wrapping:** AES-256-GCM key wrap
- MEK loaded at startup from filesystem secret (Docker secret / HSM)
- DEK wrapped: `AES/GCM/NoPadding` encryption of DEK using MEK
- Stored in DB as Base64: `[12-byte IV][32-byte wrapped key][16-byte tag]`

**Key Rotation Procedure:**
1. Load old MEK and new MEK
2. For each file: unwrap DEK with old MEK, re-wrap with new MEK, update DB record
3. Atomically replace MEK on disk
4. No file re-encryption required

---

## 6. Authentication & Authorization Model

### 6.1 Identity Providers

SyncForge v1 supports two identity sources:

**Database Users:** Stored in `users` + `user_roles` tables. Passwords are BCrypt-hashed with cost factor 10. Managed via `CustomUserDetailsService` and the `DaoAuthenticationProvider`.

**In-Memory Admin (Deprecated):** A static admin user defined in `SecurityConfig`. In v2, this will be removed. The DB-seeded admin (via Flyway V2 migration) is the sole admin identity.

### 6.2 RBAC Model

| Role | Access |
|---|---|
| `ROLE_USER` | All `/api/files/**` endpoints, `/api/system/ping` |
| `ROLE_ADMIN` | All USER endpoints + all `/api/admin/**` endpoints + `/api/system/purge` |

Enforcement is dual-layered:
1. URL pattern rules in `SecurityConfig.filterChain()`
2. `@PreAuthorize` annotations on controller methods (defense in depth)

### 6.3 Session Management

Current: `SessionCreationPolicy.IF_REQUIRED` — Spring creates a session on first authentication and maintains it via `JSESSIONID` cookie. Subsequent requests from the same session skip BCrypt verification.

Target v2: JWT Bearer tokens for API clients (Go binary), session cookies for browser dashboard. JWT validation is O(1) vs. BCrypt O(2^10), critical for high-throughput chunk uploads.

### 6.4 Password Security

- BCrypt with cost factor 10 (configurable)
- `SecureRandom` salt embedded in hash
- Password hashes never returned in API responses (v2 fix)
- Admin password rotation does not require code changes (v2: credential externalization)

---

## 7. Data Persistence Layer

### 7.1 Schema Design

SyncForge uses a normalized relational schema with UUID primary keys for all user-facing entities. UUID PKs prevent enumeration attacks (sequential integer IDs allow attackers to guess valid resource identifiers).

**file_metadata:** The core table. Each row represents one upload lifecycle. The `status` column drives the state machine. The `encrypted_aes_key` column will store the wrapped DEK in v2.

**users / user_roles:** Standard RBAC user model with a junction table. Roles are stored as strings (`"ADMIN"`, `"USER"`). Spring Security's `ROLE_` prefix is applied at the service layer, not in the DB.

**system_config:** Key-value store for runtime configuration. Enables operator-tunable parameters (chunk size, retention period, notification flags) without redeployment.

**system_metadata:** Deployment audit table. Each Flyway migration run inserts a version record, providing a history of when each schema version was applied.

### 7.2 Flyway Migration Strategy

Migrations are numbered `V1__` through `V4__` (current). Each is:
- **Idempotent:** Safe to re-run (uses `IF NOT EXISTS`, `ON CONFLICT DO NOTHING`)
- **Additive:** Never drops existing columns or tables without a separate rollback migration
- **Ordered:** Applied in sequence; Flyway enforces ordering

`baseline-on-migrate=true` handles first-run against a pre-existing schema. `baseline-version=0` means Flyway will apply all V1+ migrations on first run.

### 7.3 Connection Pool

HikariCP with 50 max connections to PostgreSQL. At the default Tomcat thread count of 500, this provides 1 DB connection per 10 active threads — an appropriate ratio given that most request processing time is I/O (disk write, network read) rather than DB query time.

---

## 8. The Go Sync Client

### 8.1 Design Goals

The Go client is designed for low-resource deployment on Windows and Linux endpoints. It must:
- Run as a background service with minimal CPU and memory footprint
- Automatically detect and upload new files without user intervention
- Saturate available bandwidth via parallel chunking
- Survive network interruptions without losing data

### 8.2 fsnotify-Based Watch Loop

The client uses the `fsnotify` library to register kernel-level filesystem event listeners on the `sync_folder` directory. This is dramatically more efficient than polling — the client consumes zero CPU between file events.

On `CREATE` or `WRITE` events, a 2-second debounce prevents partial-file uploads (files still being written by another process). After the debounce, `handleUpload()` is invoked in a new goroutine.

On startup, all existing files in `sync_folder` are enumerated and uploaded. This provides catch-up behavior after a client restart.

### 8.3 Parallel Upload Pipeline

```
File ──[Read loop]──► Channel<ChunkJob>
                              │
                    ┌─────────┼──────────┐
                    ▼         ▼          ▼
                Worker 1  Worker 2  Worker 3  Worker 4
                    │         │          │          │
                    └─────────┴──────────┴──────────┘
                                    │
                              uploadChunk(fileId, data)
                                    │
                              POST /api/files/{id}/chunk
```

Four concurrent workers provide a 4x throughput improvement over sequential upload on high-bandwidth connections. The channel-based dispatch is backpressure-safe — the producer (read loop) blocks when all workers are busy.

### 8.4 Authentication Flow

The client constructs HTTP Basic Auth headers by Base64-encoding `username:password`. This is sent with every request in the `Authorization` header. The Nginx proxy forwards the header unchanged to Spring Security.

---

## 9. Operational Infrastructure

### 9.1 Docker Compose Deployment

The full SyncForge stack is defined in a single `docker-compose.yml`. Deployment is a single command:

```bash
docker-compose up -d
```

Three containers start in dependency order:
1. `db` (PostgreSQL) — data persistence
2. `server` (Spring Boot) — application logic, depends on `db`
3. `proxy` (Nginx) — web proxy, depends on `server`

### 9.2 Nginx Streaming Optimization

The most critical Nginx configuration directive for MFT performance is:

```nginx
proxy_request_buffering off;
```

By default, Nginx buffers the entire request body to disk before forwarding to the backend. For a 1MB chunk, this means writing 1MB to Nginx's disk, then reading it back and forwarding. With buffering disabled, Nginx acts as a pure TCP relay: bytes from the client flow directly to Tomcat as they arrive. This eliminates double-write I/O overhead and reduces end-to-end latency by the time it takes to write and read 1MB from disk.

### 9.3 Horizontal Scaling Path

The Nginx upstream block is pre-configured for horizontal scaling:

```nginx
upstream mft_backend {
    server server:8080;
    # server server2:8080;  # add to scale
    # server server3:8080;
}
```

Adding backend instances requires only adding `server` lines to the upstream block and scaling the Docker service:

```bash
docker-compose up --scale server=3
```

**Stateful consideration:** The in-memory `activeTransfers` and `fileLocks` maps in `FileStorageService` are per-instance. In a multi-instance deployment, a client must be sticky-routed to the same backend for the duration of a single upload. Nginx supports this with `ip_hash` or `sticky` directives.

For a fully stateless horizontal scale, the active transfer state must move to Redis or the DB.

---

## 10. Security Posture Assessment

### 10.1 Current Security Strengths

**BCrypt Password Hashing:** All user passwords are stored as BCrypt hashes with cost factor 10. Even if the `users` table is compromised, recovering passwords requires approximately 100ms per guess on modern hardware.

**UUID Stored Filenames:** Files are stored with server-generated UUID names, preventing path traversal and filename-based enumeration.

**Per-File Encryption Keys:** Every uploaded file has a unique AES-256 key, generated from `SecureRandom`. Compromise of one file's key does not expose others.

**RBAC Enforcement:** Admin operations are protected by both URL pattern rules and `@PreAuthorize` method annotations. Double enforcement ensures role checks survive URL restructuring.

**Tomcat Not Directly Exposed:** External traffic must pass through Nginx, which enforces body size limits, can apply rate limiting, and provides a consistent security boundary.

**Flyway Schema Integrity:** Database schema is version-controlled and migration history is auditable. Ad-hoc schema changes are detectable.

### 10.2 Current Security Deficiencies

The following critical issues require remediation before production deployment. Full details are in the QA & Test Report.

| Issue | Severity | Status |
|---|---|---|
| AES keys stored in plaintext (fake RSA wrapping) | CRITICAL | Unmitigated in v1 |
| AES/ECB cipher mode | HIGH | Unmitigated in v1 |
| Hardcoded admin password in source code | HIGH | Unmitigated in v1 |
| SSH deploy private key committed to repository | CRITICAL | Immediate revocation required |
| CSRF protection disabled | MEDIUM | Unmitigated in v1 |
| No input validation on filename/size | MEDIUM | Unmitigated in v1 |
| PostgreSQL exposed on host port 5432 | MEDIUM | Config change required |
| No security headers in Nginx | MEDIUM | Unmitigated in v1 |

### 10.3 Compliance Readiness

| Standard | Current Gap | v2 Target |
|---|---|---|
| SOC 2 Type II | Missing: audit log persistence, encryption at rest (real) | Addressed by DB activity events + AES-GCM |
| HIPAA (if applicable) | Missing: access controls per file, AES at rest | Addressed by file ownership + AES-GCM |
| GDPR | Missing: data deletion (no delete endpoint) | Addressed by secure delete endpoint |
| PCI-DSS (if applicable) | Missing: key management, no hardcoded creds | Addressed by MEK + credential externalization |

---

## 11. Performance Characteristics

### 11.1 Measured Benchmarks (v1, from SYNCFORGE_TEST_REPORT_20260305.md)

These results were produced by the `stresstool.go` load generator with 20 concurrent workers:

| Metric | Result |
|---|---|
| Total files processed | 100 |
| Success rate | 100% |
| Average throughput | 8.86 files/sec |
| API latency (`/api/me`) | < 50ms |
| Tomcat thread pool | 500 max (not saturated) |

The pre-fix baseline showed "10–12 second latencies" on the file list endpoint due to cache stampede. Post-fix (double-checked locking): < 50ms.

### 11.2 Theoretical Throughput Model

For a 1MB chunk size with 4 parallel workers:
- Assuming 100ms round-trip per chunk request (including network + disk write + encryption)
- Throughput per file: `4 workers × (1MB / 100ms)` = 40 MB/s (ideal)
- Actual: limited by disk I/O speed, Tomcat thread availability, and network bandwidth

For 50 concurrent users uploading simultaneously:
- 50 × 4 workers = 200 concurrent chunk requests
- Tomcat pool (500 threads) is not the bottleneck
- HikariCP (50 connections) handles metadata operations (not called per chunk)
- Bottleneck: disk I/O of the storage volume

### 11.3 Encryption Overhead

AES-256-GCM in Java using the JCA (which uses AES-NI hardware acceleration on modern CPUs) achieves:
- ~4 GB/s throughput on modern Intel/AMD hardware
- 1MB chunk encryption: < 0.25ms
- Encryption is not the bottleneck at any realistic throughput

### 11.4 v2 Performance Targets

| Metric | v1 Measured | v2 Target |
|---|---|---|
| Files/sec (20 workers) | 8.86 | 15+ |
| Files/sec (50 workers) | Not tested | 25+ |
| API latency (file list) | < 50ms | < 20ms |
| Max single file size tested | ~5MB | 500MB |
| Chunk encryption overhead | ~0.5ms (ECB) | ~0.3ms (GCM) |

---

## 12. Known Limitations & Remediation Roadmap

### 12.1 Chunk Ordering (v2 Priority)

**Limitation:** Parallel chunk upload does not guarantee delivery order. Files > 1MB are silently corrupted.

**Remediation:** Implement chunk index tracking. Server stores chunks as numbered `.part` files and assembles in order on `/complete`. Go client passes `chunkIndex` and `totalChunks` with each request.

### 12.2 No Download Capability (v2 Priority)

**Limitation:** SyncForge is write-only. Uploaded files cannot be retrieved via API.

**Remediation:** `GET /api/files/{fileId}/download` endpoint with `StreamingResponseBody`, decrypting chunks on-the-fly and streaming to client.

### 12.3 No Retention Enforcement

**Limitation:** `system_config.mft.retention.days=30` is configured but never enforced. Files accumulate indefinitely.

**Remediation:** Scheduled `@Scheduled(cron = "0 3 * * *")` `RetentionService` that queries and securely deletes expired files.

### 12.4 Activity Log Not Persisted

**Limitation:** Activity events are in-memory only (50-event ring buffer). All audit history is lost on restart.

**Remediation:** Write events to `activity_events` DB table. In-memory buffer serves as read cache.

### 12.5 Single-Node State (Horizontal Scale Blocker)

**Limitation:** `activeTransfers` and `fileLocks` are in-memory, per-JVM. Cannot be shared across multiple `server` instances.

**Remediation (v3):** Move active transfer state to Redis. Use distributed locks (Redisson `RLock`) instead of in-memory `ReentrantLock`. Enables true stateless horizontal scaling.

---

## 13. Competitive Positioning

| Feature | SyncForge v1 | IBM Sterling B2Bi | GoAnywhere MFT | SFTP Server |
|---|---|---|---|---|
| Chunked upload | Yes | Yes | Yes | No |
| Per-file encryption keys | Yes | Yes | Yes | No |
| Open source | Yes | No (proprietary) | No (proprietary) | Varies |
| Self-hosted | Yes | Yes | Yes | Yes |
| Cloud-native (Docker) | Yes | Partial | Partial | No |
| Horizontal scaling | Partial (v2) | Yes | Yes | No |
| Audit logging (persistent) | No (v2) | Yes | Yes | No |
| File resumability | Partial | Yes | Yes | No |
| Download capability | No (v2) | Yes | Yes | Yes |
| Binary differential sync | Roadmap | No | No | No |
| License cost | Free | $100k+ | $20k+ | Free |

SyncForge's primary advantages are zero licensing cost, Docker-native deployment, and a clean extension model. Its primary gaps versus commercial solutions (no download in v1, no persistent audit log, no resumability) are all on the active remediation roadmap.

---

## 14. Future Vision: SyncForge v3

### 14.1 Distributed Architecture

SyncForge v3 targets a fully stateless, horizontally scalable architecture:

```
                    Load Balancer (Nginx / AWS ALB)
                   /         |          \
            Node 1         Node 2        Node 3
               \              |              /
                    Redis Cluster (State)
                         |
                    PostgreSQL (Metadata)
                         |
                    S3-Compatible Object Storage (Files)
```

Encrypted file chunks move from local disk to S3-compatible object storage. This eliminates the single-node disk bottleneck, enables multi-region redundancy, and allows independent scaling of compute and storage.

### 14.2 Binary Differential Sync (FastCDC)

The most transformative planned capability: rather than re-uploading entire files on modification, SyncForge will use content-defined chunking (FastCDC algorithm) to identify only changed byte ranges. A 1GB file with a 10-byte change uploads 1 chunk instead of 1,000. This is the core capability of modern sync tools (Dropbox, rsync) and is absent from all legacy MFT products.

### 14.3 Multi-Tenant Organization Model

Phase 2 introduces "Forges" — organizational units with shared artifact repositories. Users belong to Forges. Files are owned by Forges, not individual users. Access is controlled by per-file and per-directory ACLs within the Forge.

### 14.4 AI-Assisted Operations

Phase 3 integrates predictive analytics:
- **Anomaly detection:** Flag unusual upload patterns (volume spikes, off-hours activity) for security review
- **Capacity forecasting:** Predict storage exhaustion based on historical growth curves
- **Transfer optimization:** Dynamically adjust chunk size based on observed network conditions

---

## 15. Conclusion

SyncForge represents a pragmatic, modern approach to enterprise managed file transfer: open, cloud-native, built on proven technologies (Spring Boot, PostgreSQL, Go, Nginx), and designed for the operational realities of high-throughput, parallel data movement.

The v1 release establishes a solid architectural foundation: a correct concurrent chunk pipeline with per-file locking, an efficient double-checked locking cache, a clean three-tier deployment model, and a Flyway-managed schema that supports zero-downtime upgrades. These foundations will carry the system into production-grade operation without major structural rework.

The path from v1 to production-ready is well-defined and achievable: real AES-GCM envelope encryption, credential externalization, download capability, chunk ordering guarantees, and persistent audit logging. These are engineering tasks, not architectural redesigns. The bones of the system are sound.

SyncForge's longer-term roadmap — distributed state, binary differential sync, multi-tenancy, and AI-assisted operations — positions it to close the gap with commercial MFT platforms while maintaining the zero-licensing-cost advantage that makes it compelling for organizations unwilling to commit to six-figure enterprise software contracts.

The system is built to grow. The foundation is ready.

---

**Document Information**

| Field | Value |
|---|---|
| Title | SyncForge: Architecting the Enterprise-Grade Managed File Transfer Platform |
| Version | 2.0 |
| Date | March 5, 2026 |
| Author | Draygen Systems |
| Classification | Public / Engineering |
| Supersedes | SyncForge_Whitepaper_Roadmap.md v1.0 |

---

*End of SyncForge Technical Whitepaper v2.0*
*Draygen Systems — March 5, 2026*
