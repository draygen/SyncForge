#!/bin/bash
# SyncForge Nexus - Complete Stack Starter

# 1. Kill old processes
pkill -f "serve -s dist"
pkill -f "cloudflared"

# 2. Build and Start UI
cd syncforge-ui
npm run build
npx serve -s dist -l 3000 > ../serve.log 2>&1 &
echo "[*] UI started on port 3000"

# 3. Start Tunnel
cd ..
cloudflared tunnel --config cloudflared-config.yml run > tunnel.log 2>&1 &
echo "[*] Tunnel started"

# 4. Wait and Verify
sleep 15
curl -sI http://localhost:3000 > /dev/null && echo "[+] Local UI is UP" || echo "[-] Local UI is DOWN"
tail -n 5 tunnel.log
