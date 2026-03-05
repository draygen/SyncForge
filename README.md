# SyncForge - Enterprise Managed File Transfer (MFT) System

SyncForge is a modern, 3-tier Managed File Transfer system designed for high-concurrency, secure file synchronization.

## Architecture
- **Web Tier:** Nginx Reverse Proxy (Load Balanced ready)
- **App Tier:** Java Spring Boot (Apache Tomcat) with AES-256 Encryption at rest.
- **Data Tier:** PostgreSQL with Flyway automated migrations.
- **Client:** High-performance Go (Golang) directory watcher and chunked uploader.

## Key Features
- **In-Place Upgradable:** Automated database migrations via Flyway.
- **Zero-Trust Security:** BCrypt password hashing and AES-256 file encryption.
- **Modern Dashboard:** Mobile-responsive UI for real-time transfer tracking.
- **High Concurrency:** Verified to handle multiple simultaneous uploads with 100% success rate.

## Getting Started
### Running the Server
```bash
cd mft-server
docker-compose up --build -d
```
Access the dashboard at `http://localhost:8888` (User: `admin` / Pass: `<YOUR_PASSWORD>`)

### Running the Client
```bash
cd autopost-client
go run main.go
```
Any file dropped in `./sync_folder` will be automatically synchronized.
