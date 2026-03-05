#!/bin/bash

# SyncForge Vast.ai Deployment Utility
API_KEY=$(cat key)

if [ -z "$API_KEY" ]; then
    echo "Error: 'key' file is empty or missing."
    exit 1
fi

echo "Authenticating with Vast.ai..."
vastai set api-key "$API_KEY"

echo "Searching for suitable instances..."
echo "Criteria: < $0.05/hr, > 10GB Disk, High Reliability, Good Bandwidth"
echo "---------------------------------------------------------------------"

# Search for instances
# dph < 0.05: Max $0.05 per hour
# disk_space > 10: At least 10GB
# reliability > 0.95: Stable hosts
# inet_down > 100: Good download speed for uploads
vastai search offers 'dph < 0.05 disk_space > 10 reliability > 0.95 inet_down > 100' --order 'dph'

echo "---------------------------------------------------------------------"
echo "To deploy SyncForge to one of these, run:"
echo "vastai create instance <ID> --image ubuntu:22.04 --disk 10"
