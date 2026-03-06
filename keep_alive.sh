#!/bin/bash
# SyncForge Keep-Alive Loop (Vite Dev Mode for Proxying)

while true; do
  if ! pgrep -f "vite" > /dev/null; then
    echo "[!] Restarting Vite..."
    cd syncforge-ui
    npm run dev -- --host --port 3000 > ../serve.log 2>&1 &
    cd ..
  fi
  
  if ! pgrep -f "cloudflared tunnel" > /dev/null; then
    echo "[!] Restarting Tunnel..."
    cloudflared tunnel --config cloudflared-config.yml run > tunnel.log 2>&1 &
  fi
  
  sleep 30
done
