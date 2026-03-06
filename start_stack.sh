#!/bin/bash
# SyncForge Nexus - Complete Stable Stack Starter

# 1. Kill old tunnel
pkill -f "cloudflared tunnel"

# 2. Re-check Docker status (Nginx and Backend)
docker ps | grep -E "mft-server-proxy|mft-server-server" || {
  echo "[!] Docker containers missing. Restarting them..."
  cd mft-server && docker-compose up -d && cd ..
}

# 3. Start Tunnel pointing to Nginx (127.0.0.1:8888) with HTTP2
echo "[*] Launching Tunnel with HTTP2 protocol..."
nohup cloudflared tunnel --protocol http2 --config cloudflared-config.yml run > tunnel.log 2>&1 &
TUN_PID=$!

echo "[*] Tunnel launched (PID: $TUN_PID). Waiting for connection..."
sleep 15

# 4. Verify
curl -sI https://drayhub.org | grep "HTTP/" && echo "[+] drayhub.org is LIVE" || echo "[-] drayhub.org is still DOWN"
