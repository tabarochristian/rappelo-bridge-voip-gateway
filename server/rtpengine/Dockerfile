FROM debian:bookworm-slim

# Install rtpengine from Sipwise repo
RUN apt-get update && apt-get install -y --no-install-recommends \
    gnupg curl ca-certificates \
    && curl -fsSL https://dfx.at/rtpengine/latest/pool/main/r/rtpengine-dfx-repo-keyring/rtpengine-dfx-repo-keyring_1.0_all.deb \
       -o /tmp/keyring.deb \
    && dpkg -i /tmp/keyring.deb && rm /tmp/keyring.deb \
    && echo "deb [signed-by=/usr/share/keyrings/dfx.at-rtpengine-archive-keyring.gpg] https://dfx.at/rtpengine/latest bookworm main" \
       > /etc/apt/sources.list.d/rtpengine.list \
    && apt-get update && apt-get install -y --no-install-recommends \
    rtpengine \
    && rm -rf /var/lib/apt/lists/*

COPY entrypoint.sh /entrypoint.sh
RUN chmod +x /entrypoint.sh

EXPOSE 22222/udp
EXPOSE 30000-30100/udp

ENTRYPOINT ["/entrypoint.sh"]
