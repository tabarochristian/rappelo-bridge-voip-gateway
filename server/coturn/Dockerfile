FROM coturn/coturn:latest

COPY turnserver.conf /etc/turnserver.conf.template
COPY entrypoint.sh /entrypoint.sh

USER root
RUN chmod +x /entrypoint.sh

EXPOSE 3478/udp 3478/tcp 5349/udp 5349/tcp
EXPOSE 49152-65535/udp

ENTRYPOINT ["/entrypoint.sh"]
