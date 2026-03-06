#!/bin/bash
# SyncForge Tunnel Keep-Alive (Direct Nginx)

while true; do
  if ! pgrep -f "cloudflared tunnel" > /dev/null; then
    echo "[!] Restarting Tunnel..."
    cloudflared tunnel --config cloudflared-config.yml run > tunnel.log 2>&1 &
  fi
  sleep 5
done
