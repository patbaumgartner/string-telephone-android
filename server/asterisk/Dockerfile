FROM andrius/asterisk:22.11.0_debian-trixie

COPY config/pjsip.conf /etc/asterisk/pjsip.conf
COPY config/extensions.conf /etc/asterisk/extensions.conf
COPY docker-entrypoint.sh /usr/local/bin/st-entrypoint.sh
RUN chmod +x /usr/local/bin/st-entrypoint.sh

# UDP 5060 is the unsecured default; TCP 5061 carries SIP over TLS when
# certificates are mounted (see docker-entrypoint.sh).
EXPOSE 5060/udp
EXPOSE 5061/tcp

# The wrapper generates pjsip-tls.conf, then hands the command to the image's
# own entrypoint (which adapts the asterisk user and handles
# ASTERISK_TERMINAL_OPTS). Declaring ENTRYPOINT resets CMD, so the base
# asterisk command is restated here.
ENTRYPOINT ["/usr/local/bin/st-entrypoint.sh"]
CMD ["/usr/sbin/asterisk", "-vvvdddf", "-T", "-W", "-U", "asterisk", "-p"]
