#!/bin/bash
set -e

# Auto-detect the container's actual IP (0.0.0.0 is rejected by newer rtpengine)
LOCAL_IP=$(hostname -I | awk '{print $1}')
echo "Detected local IP: ${LOCAL_IP}"

# Build interface specification
if [ -n "$PUBLIC_IP" ]; then
    # NAT mode: listen on local IP, advertise public IP
    IFACE="${LOCAL_IP}!${PUBLIC_IP}"
    echo "NAT mode: ${IFACE}"
else
    IFACE="${LOCAL_IP}"
fi

ARGS=(
    --interface="${IFACE}"
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

echo "Starting RTPEngine..."
# Binary name varies by package: rtpengine or rtpengine-daemon
if command -v rtpengine &>/dev/null; then
    exec rtpengine "${ARGS[@]}" --foreground --table=-1
else
    exec rtpengine-daemon "${ARGS[@]}" --foreground --table=-1
fi
