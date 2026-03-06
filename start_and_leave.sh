#!/bin/bash
# SyncForge Nexus - Persistent Background Starter

pkill -f "serve -s dist"
pkill -f "cloudflared"

# Start UI
cd syncforge-ui
npm run build
nohup npx serve -s dist -l 3000 > ../serve.log 2>&1 &
UI_PID=$!
disown $UI_PID

# Start Tunnel
cd ..
nohup cloudflared tunnel --config cloudflared-config.yml run > tunnel.log 2>&1 &
TUN_PID=$!
disown $TUN_PID

echo "[*] Processes detached. UI:$UI_PID Tunnel:$TUN_PID"
sleep 15
curl -sI https://drayhub.org | grep "HTTP/"
