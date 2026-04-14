FROM kamailio/kamailio-ci:5.8-bookworm

# Install PostgreSQL client module and required modules
RUN apt-get update && apt-get install -y --no-install-recommends \
    kamailio-postgres-modules \
    kamailio-extra-modules \
    kamailio-tls-modules \
    kamailio-utils-modules \
    kamailio-json-modules \
    netcat-openbsd \
    && rm -rf /var/lib/apt/lists/*

# Create runtime directories
RUN mkdir -p /run/kamailio && chown kamailio:kamailio /run/kamailio

# Copy configuration
COPY kamailio.cfg /etc/kamailio/kamailio.cfg
COPY tls.cfg /etc/kamailio/tls.cfg

# Copy and setup entrypoint
COPY entrypoint.sh /entrypoint.sh
RUN chmod +x /entrypoint.sh

EXPOSE 5060/udp 5060/tcp 5061/tcp 5080/udp 5080/tcp

ENTRYPOINT ["/entrypoint.sh"]
