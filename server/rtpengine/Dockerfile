FROM droopygit/rtpengine:latest

COPY entrypoint.sh /entrypoint.sh
RUN chmod +x /entrypoint.sh

# RTP port range + control port
EXPOSE 22222/udp
EXPOSE 30000-40000/udp

ENTRYPOINT ["/entrypoint.sh"]
