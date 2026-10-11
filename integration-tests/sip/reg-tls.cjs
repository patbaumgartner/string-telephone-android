#!/usr/bin/env node
// SIP over TLS registration probe (G5): connects to the PBX TLS transport,
// answers the digest challenge, and asserts a 200 OK. Certificate verification
// is disabled because the prototype uses a throwaway self-signed cert.
//
//   node reg-tls.cjs <host> <port> <user> <pass>
const tls = require('tls');
const { createHash, randomUUID } = require('crypto');

const SERVER = process.argv[2] || '127.0.0.1';
const PORT = parseInt(process.argv[3] || '5061', 10);
const USER = process.argv[4] || 'alice';
const PASS = process.argv[5] || 'secret';

const md5 = (s) => createHash('md5').update(s).digest('hex');
const rnd = () => randomUUID().replace(/-/g, '').slice(0, 8);

let callId = `regtls-${rnd()}@localhost`;
let tag = rnd();
let localAddress = '127.0.0.1';
let localPort = 0;

const requestUri = `sip:${SERVER}:${PORT};transport=tls`;

const register = (cseq, authLine) => {
  const lines = [
    `REGISTER ${requestUri} SIP/2.0`,
    `Via: SIP/2.0/TLS ${localAddress}:${localPort};branch=${rnd()};rport`,
    `From: <sip:${USER}@${SERVER}>;tag=${tag}`,
    `To: <sip:${USER}@${SERVER}>`,
    `Call-ID: ${callId}`,
    `CSeq: ${cseq} REGISTER`,
    `Contact: <sip:${USER}@${localAddress}:${localPort};transport=tls>`,
    'Expires: 120',
    'Max-Forwards: 70',
  ];
  if (authLine) lines.push(`Authorization: ${authLine}`);
  lines.push('Content-Length: 0', '', '');
  return lines.join('\r\n') + '\r\n';
};

const digest = (realm, nonce, opaque) => {
  const a1 = md5(`${USER}:${realm}:${PASS}`);
  const a2 = md5(`REGISTER:${requestUri}`);
  const cnonce = rnd();
  const response = md5(`${a1}:${nonce}:00000001:${cnonce}:auth:${a2}`);
  let line = `Digest username="${USER}",realm="${realm}",nonce="${nonce}",` +
    `uri="${requestUri}",response="${response}",algorithm=MD5,qop=auth,` +
    `nc=00000001,cnonce="${cnonce}"`;
  if (opaque) line += `,opaque="${opaque}"`;
  return line;
};

const fail = (msg) => {
  console.log(msg);
  process.exit(1);
};

const socket = tls.connect(
  { host: SERVER, port: PORT, rejectUnauthorized: false },
  () => {
    localAddress = socket.localAddress || '127.0.0.1';
    localPort = socket.localPort || 0;
    console.log(`-> TLS connected from ${localAddress}:${localPort}`);
    socket.write(register(1));
  },
);

let buffer = '';
socket.on('data', (chunk) => {
  buffer += chunk.toString();
  let end;
  while ((end = buffer.indexOf('\r\n\r\n')) !== -1) {
    const message = buffer.slice(0, end);
    buffer = buffer.slice(end + 4);
    const statusLine = message.split('\r\n')[0];
    console.log('<-', statusLine);

    if (message.includes(' 401 ')) {
      const www = message
        .split('\r\n')
        .find((l) => l.toLowerCase().startsWith('www-authenticate'));
      if (!www) return fail('401 without WWW-Authenticate');
      const realm = www.match(/realm="([^"]+)"/)[1];
      const nonce = www.match(/nonce="([^"]+)"/)[1];
      const opaqueM = www.match(/opaque="([^"]*)"/);
      socket.write(register(2, digest(realm, nonce, opaqueM ? opaqueM[1] : '')));
    } else if (statusLine.startsWith('SIP/2.0 200')) {
      console.log('REGISTERED-TLS');
      socket.end();
      process.exit(0);
    } else {
      fail(`UNEXPECTED: ${statusLine}`);
    }
  }
});

socket.on('error', (err) => fail(`TLS-ERROR: ${err.message}`));
socket.on('close', () => {
  console.log('TLS socket closed without 200 OK');
  process.exit(1);
});
setTimeout(() => fail('TIMEOUT'), 8000);
