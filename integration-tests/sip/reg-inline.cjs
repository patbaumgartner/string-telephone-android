#!/usr/bin/env node
const dgram = require('dgram');
const { createHash, randomUUID } = require('crypto');

const SERVER = process.argv[2] || '127.0.0.1';
const PORT = parseInt(process.argv[3] || '5060', 10);
const USER = process.argv[4] || 'alice';
const PASS = process.argv[5] || 'secret';

const md5 = s => createHash('md5').update(s).digest('hex');
const rnd = () => randomUUID().replace(/-/g, '').slice(0, 8);

const socket = dgram.createSocket('udp4');
let callId = `reg-${rnd()}@172.19.0.3`;
let tag = rnd();
let challenged = false;

socket.on('message', (m) => {
  const str = m.toString();
  const statusLine = str.split('\r\n')[0];
  console.log('<-', statusLine);
  if (statusLine.startsWith('OPTIONS ')) {
    const lines = str.split('\r\n');
    socket.send([
      'SIP/2.0 200 OK',
      ...['Via:', 'From:', 'To:', 'Call-ID:', 'CSeq:'].map(name => lines.find(line => line.startsWith(name))),
      'Content-Length: 0', '', '',
    ].join('\r\n'), PORT, SERVER);
    return;
  }
  if (str.includes(' 401 ')) {
    if (challenged) {
      console.log('REGISTRATION-REJECTED');
      socket.close();
      process.exit(1);
    }
    challenged = true;
    const www = str.split('\r\n').find(l => l.toLowerCase().startsWith('www-authenticate'));
    const realm = www.match(/realm="([^"]+)"/)[1];
    const nonce = www.match(/nonce="([^"]+)"/)[1];
    const opaqueM = www.match(/opaque="([^"]*)"/);
    const opaque = opaqueM ? opaqueM[1] : '';
    const a1 = md5(`${USER}:${realm}:${PASS}`);
    const a2 = md5(`REGISTER:sip:${SERVER}:${PORT}`);
    const cnonce = rnd();
    const response = md5(`${a1}:${nonce}:00000001:${cnonce}:auth:${a2}`);
    const authLine = `Digest username="${USER}",realm="${realm}",nonce="${nonce}",uri="sip:${SERVER}:${PORT}",response="${response}",algorithm=MD5,qop=auth,nc=00000001,cnonce="${cnonce}",opaque="${opaque}"`;
    const msg2 = ['REGISTER sip:' + SERVER + ':' + PORT + ' SIP/2.0',
      'Via: SIP/2.0/UDP 172.19.0.3:' + socket.address().port + ';branch=' + rnd() + ';rport',
      'From: <sip:' + USER + '@172.19.0.3>;tag=' + tag,
      'To: <sip:' + USER + '@' + SERVER + '>',
      'Call-ID: ' + callId,
      'CSeq: 2 REGISTER',
      'Contact: <sip:' + USER + '@172.19.0.3:' + socket.address().port + '>',
      'Authorization: ' + authLine,
      'Expires: 120',
      'Max-Forwards: 70',
      'Content-Length: 0', '', ''].join('\r\n') + '\r\n';
    socket.send(msg2, PORT, SERVER, () => {});
  } else if (str.startsWith('SIP/2.0 200')) {
    console.log('REGISTERED');
    socket.close();
    process.exit(0);
  } else {
    console.log('UNEXPECTED:', str.split('\r\n')[0]);
    socket.close();
    process.exit(1);
  }
});

socket.bind(0, () => {
  const local = socket.address();
  const msg1 = ['REGISTER sip:' + SERVER + ':' + PORT + ' SIP/2.0',
    'Via: SIP/2.0/UDP 172.19.0.3:' + local.port + ';branch=' + rnd() + ';rport',
    'From: <sip:' + USER + '@172.19.0.3>;tag=' + tag,
    'To: <sip:' + USER + '@' + SERVER + '>',
    'Call-ID: ' + callId,
    'CSeq: 1 REGISTER',
    'Contact: <sip:' + USER + '@172.19.0.3:' + local.port + '>',
    'Expires: 120',
    'Max-Forwards: 70',
    'Content-Length: 0', '', ''].join('\r\n') + '\r\n';
  socket.send(msg1, PORT, SERVER, (err) => { if (err) console.log('send err', err); });
});
setTimeout(() => { console.log('TIMEOUT'); process.exit(1); }, 8000);
