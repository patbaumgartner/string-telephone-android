#!/usr/bin/env node
// Two-party call prototype — CALLEE side (auto-answer).
// Registers USER, waits for INVITE, answers 200 OK with SDP, replies 200 to
// BYE. Exit 0 after clean BYE handling.
const dgram = require('dgram');
const os = require('os');
const { createHash, randomUUID } = require('crypto');

const SERVER = process.argv[2] || 'st-proto-asterisk';
const PORT = parseInt(process.argv[3] || '5060', 10);
const USER = process.argv[4] || 'bob';
const PASS = process.argv[5] || 'prototest-bob-pass';

const md5 = s => createHash('md5').update(s).digest('hex');
const rnd = () => randomUUID().replace(/-/g, '').slice(0, 12);

const socket = dgram.createSocket('udp4');
const callId = `reg-${rnd()}@local`;
const fromTag = rnd();
let answered = false;
let gotBye = false;
let peerUri = null;
let toHeaderFromInvite = null;

function hostIp() {
  for (const list of Object.values(os.networkInterfaces())) {
    for (const i of list) if (!i.internal && i.family === 'IPv4') return i.address;
  }
  return '127.0.0.1';
}
const IP = () => hostIp();
const me = () => `${IP()}:${socket.address().port}`;

function send(lines) {
  socket.send(lines.join('\r\n') + '\r\n', PORT, SERVER, (err) => {
    if (err) { console.error('send error', err.message); process.exit(1); }
  });
}

function sendRegister(cseqNum, challenge) {
  const uri = `sip:${SERVER}:${PORT}`;
  const lines = [
    `REGISTER ${uri} SIP/2.0`,
    `Via: SIP/2.0/UDP ${me()};branch=${rnd()};rport`,
    `From: <sip:${USER}@${IP()}>;tag=${fromTag}`,
    `To: <sip:${USER}@${SERVER}>`,
    `Call-ID: ${callId}`,
    `CSeq: ${cseqNum} REGISTER`,
    `Contact: <sip:${USER}@${me()}>`,
    'Expires: 120',
    'Max-Forwards: 70',
  ];
  if (challenge) {
    const a1 = md5(`${USER}:${challenge.realm}:${PASS}`);
    const a2 = md5(`REGISTER:${uri}`);
    const cnonce = rnd();
    const response = md5(`${a1}:${challenge.nonce}:00000001:${cnonce}:auth:${a2}`);
    lines.push(`Authorization: Digest username="${USER}",realm="${challenge.realm}",nonce="${challenge.nonce}",uri="${uri}",response="${response}",algorithm=MD5,qop=auth,nc=00000001,cnonce="${cnonce}",opaque="${challenge.opaque}"`);
  }
  lines.push('Content-Length: 0', '', '');
  send(lines);
}

function answer(inviteStr) {
  const viaHeader = inviteStr.split('\r\n').find(l => l.startsWith('Via: '));
  const fromHeader = inviteStr.split('\r\n').find(l => l.startsWith('From: '));
  const callIdHeader = inviteStr.split('\r\n').find(l => l.startsWith('Call-ID: '));
  const cseqHeader = inviteStr.split('\r\n').find(l => l.startsWith('CSeq: '));
  const fromTagM = fromHeader.match(/tag=([^;\r\n]+)/);
  const lines = [
    'v=0',
    `o=- 0 0 IN IP4 ${IP()}`,
    's=answer',
    `c=IN IP4 ${IP()}`,
    't=0 0',
    'm=audio 49172 RTP/AVP 0 8',
    'a=rtpmap:0 PCMU/8000',
    'a=rtpmap:8 PCMA/8000',
  ];
  // Answer a video stream only when Asterisk offered one, so the caller leg
  // can keep VP8 active end to end. An unknown codec offer stays `m=video 0`.
  const offer = inviteStr.split('\r\n\r\n')[1] || '';
  const video = offer.split('\r\n').find(line => line.startsWith('m=video '));
  if (video && !/^m=video 0 /.test(video)) {
    const payloadType = (offer.split('\r\n').find(line => /^a=rtpmap:\d+ VP8\/90000/i.test(line)) || '').match(/a=rtpmap:(\d+)/);
    const pt = payloadType ? payloadType[1] : '96';
    lines.push(`m=video 49174 RTP/AVP ${pt}`);
    lines.push(`a=rtpmap:${pt} VP8/90000`);
    lines.push('a=sendrecv');
  }
  const sdp = lines.join('\r\n') + '\r\n';
  send([
    'SIP/2.0 200 OK',
    viaHeader,
    fromHeader,
    `To: <sip:${USER}@${SERVER}>;tag=${rnd()}`,
    callIdHeader,
    cseqHeader,
    `Contact: <sip:${USER}@${me()}>`,
    'Content-Type: application/sdp',
    `Content-Length: ${Buffer.byteLength(sdp)}`,
    '',
    sdp,
  ]);
  answered = true;
  console.log('ANSWERED');
}

function replyBye(byeStr) {
  const viaHeader = byeStr.split('\r\n').find(l => l.startsWith('Via: '));
  const fromHeader = byeStr.split('\r\n').find(l => l.startsWith('From: '));
  const callIdHeader = byeStr.split('\r\n').find(l => l.startsWith('Call-ID: '));
  const cseqHeader = byeStr.split('\r\n').find(l => l.startsWith('CSeq: '));
  send([
    'SIP/2.0 200 OK',
    viaHeader,
    fromHeader,
    byeStr.split('\r\n').find(l => l.startsWith('To: ')),
    callIdHeader,
    cseqHeader,
    'Content-Length: 0', '', '',
  ]);
  gotBye = true;
  console.log('BYE-OK');
  setTimeout(() => { socket.close(); process.exit(0); }, 300);
}

socket.on('message', (m) => {
  const str = m.toString();
  const first = str.split('\r\n')[0];
  if (first.startsWith('INVITE')) {
    console.log(`<-- ${first}`);
    peerUri = (str.split('\r\n').find(l => l.startsWith('Contact: ')) || '').replace('Contact: ', '');
    setTimeout(() => answer(str), 300);
  } else if (first.startsWith('ACK')) {
    console.log('<-- ACK');
  } else if (first.startsWith('BYE')) {
    console.log(`<-- ${first}`);
    replyBye(str);
  } else if (first.startsWith('OPTIONS')) {
    const viaHeader = str.split('\r\n').find(l => l.startsWith('Via: '));
    const fromHeader = str.split('\r\n').find(l => l.startsWith('From: '));
    const toHeader = str.split('\r\n').find(l => l.startsWith('To: '));
    const callIdHeader = str.split('\r\n').find(l => l.startsWith('Call-ID: '));
    const cseqHeader = str.split('\r\n').find(l => l.startsWith('CSeq: '));
    send([
      'SIP/2.0 200 OK',
      viaHeader,
      fromHeader,
      toHeader ? toHeader + ';tag=' + rnd() : `To: <sip:${USER}@${SERVER}>;tag=` + rnd(),
      callIdHeader,
      cseqHeader,
      `Contact: <sip:${USER}@${me()}>`,
      'Content-Length: 0', '', '',
    ]);
    console.log('<-- OPTIONS -> 200');
  } else if (first.startsWith('SIP/2.0 401') && !answered) {
    console.log('<-- 401 challenge; re-registering');
    const www = str.split('\r\n').find(l => l.toLowerCase().startsWith('www-authenticate'));
    const realm = www.match(/realm="([^"]+)"/)[1];
    const nonce = www.match(/nonce="([^"]+)"/)[1];
    const opq = (www.match(/opaque="([^"]*)"/) || [, ''])[1];
    sendRegister(2, { realm, nonce, opaque: opq });
  } else if (first.startsWith('SIP/2.0 200')) {
    console.log('REGISTERED');
  }
});

socket.bind(0, () => sendRegister(1));
setTimeout(() => { console.log(`TIMEOUT answered=${answered} bye=${gotBye}`); process.exit(1); }, 20000);
