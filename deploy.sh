#!/bin/bash
# SyncForge — one-command redeploy
# Usage:  bash deploy.sh [ssh_host] [ssh_port]
# Example: bash deploy.sh ssh8.vast.ai 14256
# Defaults to the last known vast.ai instance if no args given.

set -euo pipefail

SSH_HOST="${1:-ssh8.vast.ai}"
SSH_PORT="${2:-14256}"
SSH_KEY="$HOME/.ssh/id_ed25519"
REMOTE="root@$SSH_HOST"
SSH_OPTS="-i $SSH_KEY -p $SSH_PORT -o StrictHostKeyChecking=no -o ConnectTimeout=15"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
SERVER_DIR="$SCRIPT_DIR/mft-server"
JAR="$SERVER_DIR/target/server-0.0.1-SNAPSHOT.jar"

echo "================================================================"
echo "  SyncForge Deploy  →  $SSH_HOST:$SSH_PORT"
echo "================================================================"

# ── 1. Build ──────────────────────────────────────────────────────────
echo "[1/5] Building JAR..."
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
export JAVA_HOME
cd "$SERVER_DIR"
./mvnw package -DskipTests -q
echo "      JAR: $(du -sh $JAR | cut -f1)"

# ── 2. Provision remote dirs ──────────────────────────────────────────
echo "[2/5] Provisioning remote directories..."
ssh $SSH_OPTS "$REMOTE" "mkdir -p /app/syncforge/html /app/syncforge/logs /app/syncforge/storage"

# ── 3. Upload artefacts ───────────────────────────────────────────────
echo "[3/5] Uploading files..."
scp $SSH_OPTS "$JAR" "$REMOTE:/app/syncforge/syncforge.jar"
scp $SSH_OPTS "$SERVER_DIR/nginx/html/index.html"         "$REMOTE:/app/syncforge/html/index.html"
scp $SSH_OPTS "$SERVER_DIR/nginx/html/maintenance.html"   "$REMOTE:/app/syncforge/html/maintenance.html" 2>/dev/null || true
scp $SSH_OPTS "$SERVER_DIR/images/draygen_avatar.jpg"     "$REMOTE:/app/syncforge/html/draygen_avatar.jpg" 2>/dev/null || true

# ── 4. Deploy start.sh + .env (only if .env doesn't already exist) ───
echo "[4/5] Deploying start.sh..."
scp $SSH_OPTS "$SCRIPT_DIR/server_config/start.sh" "$REMOTE:/app/syncforge/start.sh" 2>/dev/null || \
ssh $SSH_OPTS "$REMOTE" 'cat > /app/syncforge/start.sh << '"'"'EOF'"'"'
#!/bin/bash
pkill -f syncforge.jar 2>/dev/null || true
sleep 2
ENV_FILE="$(dirname "$0")/.env"
if [ -f "$ENV_FILE" ]; then
  set -a; source "$ENV_FILE"; set +a
else
  echo "ERROR: .env not found at $ENV_FILE" >&2; exit 1
fi
mkdir -p "$(dirname "$0")/logs"
nohup java -jar /app/syncforge/syncforge.jar > /app/syncforge/logs/server.log 2>&1 &
echo "Started PID $!"
EOF
chmod +x /app/syncforge/start.sh'

# Create .env only if it doesn't exist
ssh $SSH_OPTS "$REMOTE" '
if [ ! -f /app/syncforge/.env ]; then
  echo "WARN: /app/syncforge/.env not found — creating template. FILL IN PASSWORDS BEFORE STARTING."
  cat > /app/syncforge/.env << EOF
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/mft_db
SPRING_DATASOURCE_USERNAME=mft_user
SPRING_DATASOURCE_PASSWORD=CHANGEME
SERVER_PORT=8081
EOF
  chmod 600 /app/syncforge/.env
  echo "Created .env template at /app/syncforge/.env"
fi'

# ── 5. Configure nginx ────────────────────────────────────────────────
echo "[5/5] Configuring nginx..."
ssh $SSH_OPTS "$REMOTE" '
# Install nginx if missing
which nginx >/dev/null 2>&1 || apt-get install -y nginx -q

# Write nginx config
cat > /etc/nginx/sites-available/syncforge << '"'"'NGINX'"'"'
server {
    listen 8080;
    server_name _;
    root /app/syncforge/html;
    index index.html;

    client_max_body_size 0;
    proxy_request_buffering off;

    location / {
        try_files $uri $uri/ /index.html;
    }

    location /api/ {
        proxy_pass http://127.0.0.1:8081;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_read_timeout 3600s;
        proxy_send_timeout 3600s;
        proxy_buffering off;
    }
}
NGINX

ln -sf /etc/nginx/sites-available/syncforge /etc/nginx/sites-enabled/syncforge
rm -f /etc/nginx/sites-enabled/default
nginx -t && systemctl reload nginx 2>/dev/null || nginx -s reload 2>/dev/null || true'

# ── Start ─────────────────────────────────────────────────────────────
echo ""
echo "Starting server..."
ssh $SSH_OPTS "$REMOTE" "bash /app/syncforge/start.sh"
echo "Waiting for startup..."
sleep 12
HEALTH=$(ssh $SSH_OPTS "$REMOTE" "curl -s http://localhost:8080/api/system/public/health" 2>/dev/null)
echo ""
echo "================================================================"
echo "  Health: $HEALTH"
echo "  URL:    http://$SSH_HOST:8080"
echo "================================================================"
