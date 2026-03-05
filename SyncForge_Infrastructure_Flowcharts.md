# SyncForge Infrastructure & Security Flowcharts

SyncForge utilizes a high-security, multi-layered architecture designed to mask latency and protect data integrity.

## 1. Infrastructure & Firewall Topology
This diagram illustrates the flow of data through the security tiers and firewall layers.

```mermaid
graph TD
    subgraph "External Network"
        User["External User / Go Client"]
    end

    subgraph "Cloudflare Edge (WAF Tier)"
        CF["Cloudflare Tunnel<br/>(TLS 1.3 Termination)"]
        Firewall1["DDoS Protection &<br/>IP Rate Limiting"]
    end

    subgraph "DMZ Tier (Web Tier)"
        Nginx["Nginx Reverse Proxy<br/>(Internal Port 8080)"]
        Firewall2["Local IPTables<br/>(Only Allow CF IPs)"]
    end

    subgraph "Internal Network (App Tier)"
        Tomcat["Spring Boot / Tomcat<br/>(Internal Port 8081)"]
        Forge["Parallel Forge Engine<br/>(1MB Chunk Chainer)"]
    end

    subgraph "Data Persistence Tier"
        Postgres[(PostgreSQL 14)]
        Storage[(AES-256 Encrypted<br/>Object Storage)]
    end

    User -->|HTTPS| CF
    CF --> Firewall1
    Firewall1 -->|Secure Tunnel| Nginx
    Nginx --> Firewall2
    Firewall2 --> Tomcat
    Tomcat --> Forge
    Forge --> Postgres
    Forge --> Storage
```

## 2. Authentication & Session Security Flow
SyncForge uses a hybrid BCrypt/Session model to optimize high-concurrency performance.

```mermaid
sequenceDiagram
    participant Client as Client Browser / Go
    participant Nginx as Nginx Proxy
    participant Auth as Security Module (BCrypt)
    participant Session as Session Cache
    participant DB as PostgreSQL

    Client->>Nginx: POST /api/me (Credentials)
    Nginx->>Auth: Forward Auth Header
    Auth->>DB: Fetch User Hash
    DB-->>Auth: Hash Data
    Auth->>Auth: BCrypt Verify (CPU Heavy)
    Auth-->>Session: Initialize JSESSIONID
    Session-->>Client: 200 OK + Session Cookie

    Note over Client, Session: Subsequent Chunks use Session (Fast)
    
    Client->>Nginx: POST /api/files/chunk (Session Cookie)
    Nginx->>Session: Validate Cookie
    Session-->>Nginx: Valid
    Nginx->>Auth: Bypass BCrypt
    Auth-->>Client: 200 OK (Chunk Forged)
```

## 3. Parallel Data Forging Pipeline (1MB)
Visualizing the high-speed ingestion logic.

```mermaid
graph LR
    Input[Source File] --> Slicer[HTML5/Go Slicer]
    
    subgraph "Parallel Workers"
        Slicer --> W1[Worker 1]
        Slicer --> W2[Worker 2]
        Slicer --> W3[Worker 3]
        Slicer --> W4[Worker 4]
    end

    W1 -->|128KB Chunks| AES[AES-256 NI Encryption]
    W2 -->|128KB Chunks| AES
    W3 -->|128KB Chunks| AES
    W4 -->|128KB Chunks| AES

    AES --> Disk[(Encrypted Artifact Forge)]
```
