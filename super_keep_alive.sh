#!/bin/bash
# SyncForge Tight Keep-Alive

while true; do
  if ! pgrep -f "vite" > /dev/null; then
    cd syncforge-ui && npm run dev -- --host --port 3000 > ../serve.log 2>&1 &
    cd ..
  fi
  if ! pgrep -f "cloudflared" > /dev/null; then
    cloudflared tunnel --config cloudflared-config.yml run > tunnel.log 2>&1 &
  fi
  sleep 5
done
