#!/bin/bash
set -e

ARGS=(
    --interface="0.0.0.0"
    --listen-ng="0.0.0.0:22222"
    --port-min=30000
    --port-max=30100
    --log-level=5
    --delete-delay=0
    --timeout=60
    --silent-timeout=600
    --final-timeout=7200
    --no-fallback
    --tos=184
)

# If PUBLIC_IP is set, use it for external media relay
if [ -n "$PUBLIC_IP" ]; then
    ARGS+=(--interface="pub/${PUBLIC_IP}")
fi

echo "Starting RTPEngine..."
exec rtpengine "${ARGS[@]}" --foreground --table=-1
