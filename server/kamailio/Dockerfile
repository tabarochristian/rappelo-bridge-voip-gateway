FROM debian:bookworm-slim

# Install Kamailio 5.7 from official repo (latest stable in bookworm)
RUN apt-get update && apt-get install -y --no-install-recommends \
    gnupg curl ca-certificates \
    && curl -fsSL https://deb.kamailio.org/kamailiodebkey.gpg \
       | gpg --dearmor -o /usr/share/keyrings/kamailio.gpg \
    && echo "deb [signed-by=/usr/share/keyrings/kamailio.gpg] http://deb.kamailio.org/kamailio57 bookworm main" \
       > /etc/apt/sources.list.d/kamailio.list \
    && apt-get update && apt-get install -y --no-install-recommends \
    kamailio \
    kamailio-postgres-modules \
    kamailio-extra-modules \
    kamailio-tls-modules \
    kamailio-utils-modules \
    kamailio-json-modules \
    netcat-openbsd \
    && rm -rf /var/lib/apt/lists/*

# Create runtime directories
RUN mkdir -p /run/kamailio \
    && adduser --system --group --no-create-home kamailio || true \
    && chown kamailio:kamailio /run/kamailio

# Copy configuration (keep .orig as pristine template for entrypoint)
COPY kamailio.cfg /etc/kamailio/kamailio.cfg.orig
COPY kamailio.cfg /etc/kamailio/kamailio.cfg
COPY tls.cfg /etc/kamailio/tls.cfg

# Copy and setup entrypoint
COPY entrypoint.sh /entrypoint.sh
RUN chmod +x /entrypoint.sh

EXPOSE 5060/udp 5060/tcp 5061/tcp 5080/udp 5080/tcp

ENTRYPOINT ["/entrypoint.sh"]
