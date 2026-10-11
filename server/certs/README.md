# Certificates

This directory is a mount point, not a source of committed secrets. Nothing
here is tracked; the stack starts unsecured when the directory is empty.

## Asterisk (SIP over TLS, port 5061)

The Asterisk container reads a PEM certificate chain and its private key from
`/certs` (mounted read-only from here by default). Drop in the files matching
your Traefik/ACME certificate for the SIP domain:

```
server/certs/fullchain.pem   # certificate chain (leaf first)
server/certs/privkey.pem     # matching private key, PKCS#8/RSA
```

Paths are configurable with `PJSIP_TLS_CERT_FILE` / `PJSIP_TLS_KEY_FILE`. The
container enables TLS automatically when both files are readable; set
`PJSIP_TLS_ENABLE=true` to require them, or `=false` to force UDP-only.

Both files must be readable by the container user (uid/gid 1000 by default, or
`PUID`/`PGID`). If your proxy keeps the key at mode `0600` under root, relax the
mode or copy the pair into this directory before starting the stack.

## Companion API (HTTPS/WSS, port 8443)

The API takes a Java keystore rather than raw PEMs:

```
server/certs/api-keystore.p12   # PKCS12 containing the server key + chain
```

Then set `API_TLS_KEYSTORE=/certs/api-keystore.p12` plus
`API_TLS_KEYSTORE_PASSWORD` (and `API_TLS_KEY_ALIAS` if the store holds more
than one key). The plain HTTP listener on 8080 stays up either way. When a
TLS-terminating proxy fronts the API, leave `API_TLS_KEYSTORE` unset.
