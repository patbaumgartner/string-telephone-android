#!/usr/bin/env node
// Two-party call prototype — CALLER side.
// Registers USER, INVITEs TARGET (callee runs auto-answer.cjs), expects
// 100/180 -> 200 OK, ACKs, waits 2 s of "active" call, then BYE -> 200.
// Exit 0 = complete signalling call. Exit 2 = callee rejected/busy/404.
const dgram = require('dgram');
const os = require('os');
const { createHash, randomUUID } = require('crypto');

const SERVER = process.argv[2] || 'st-proto-asterisk';
const PORT = parseInt(process.argv[3] || '5060', 10);
const USER = process.argv[4] || 'alice';
const PASS = process.argv[5] || 'prototest-alice-pass';
const TARGET = process.argv[6] || 'bob';
// Optional video offer: `vp8` (allowed) or a codec the PBX does not allow
// (`vp9`), which must be answered with an inactive stream. Empty = audio only.
const VIDEO = (process.argv[7] || process.env.CALL_VIDEO || '').trim().toLowerCase();
const VIDEO_PAYLOAD_TYPES = { vp8: 96, vp9: 98 };

/** Builds the offer SDP; audio stays identical to the audio-only prototype. */
function offerSdp() {
  const lines = [
    'v=0',
    `o=- 0 0 IN IP4 ${IP()}`,
    's=call',
    `c=IN IP4 ${IP()}`,
    't=0 0',
    'm=audio 49170 RTP/AVP 0 8',
    'a=rtpmap:0 PCMU/8000',
    'a=rtpmap:8 PCMA/8000',
  ];
  if (VIDEO) {
    const payloadType = VIDEO_PAYLOAD_TYPES[VIDEO];
    if (!payloadType) throw new Error(`Unsupported video codec: ${VIDEO}`);
    lines.push(`m=video 49172 RTP/AVP ${payloadType}`);
    lines.push(`a=rtpmap:${payloadType} ${VIDEO.toUpperCase()}/90000`);
    lines.push('a=sendrecv');
  }
  return lines.join('\r\n') + '\r\n';
}

/** Reports what the 200 OK SDP did with the video stream. */
function reportVideo(answer) {
  const lines = answer.split('\r\n');
  const video = lines.find(line => line.startsWith('m=video '));
  if (!VIDEO) {
    if (video) console.log(`UNEXPECTED-VIDEO ${video}`);
    return;
  }
  if (!video || /^m=video 0 /.test(video)) {
    console.log('VIDEO-REJECTED');
    return;
  }
  const codec = lines.find(line => line.startsWith('a=rtpmap:') && /VP8\/90000/i.test(line));
  console.log(codec ? `VIDEO-NEGOTIATED ${video}` : `VIDEO-WITHOUT-VP8 ${video}`);
}

const md5 = s => createHash('md5').update(s).digest('hex');
const rnd = () => randomUUID().replace(/-/g, '').slice(0, 12);

const socket = dgram.createSocket('udp4');
const callId = `call-${rnd()}@local`;
const fromTag = rnd();
let cseq = 1;
let phase = 'registering';
let toTag = null;
let contactUri = null;

function hostIp() {
  for (const list of Object.values(os.networkInterfaces())) {
    for (const i of list) if (!i.internal && i.family === 'IPv4') return i.address;
  }
  return '127.0.0.1';
}
const IP = () => hostIp();
const me = () => `${IP()}:${socket.address().port}`;

function authHeader(challenge, method, uri) {
  const a1 = md5(`${USER}:${challenge.realm}:${PASS}`);
  const a2 = md5(`${method}:${uri}`);
  const cnonce = rnd();
  const response = md5(`${a1}:${challenge.nonce}:00000001:${cnonce}:auth:${a2}`);
  return `Authorization: Digest username="${USER}",realm="${challenge.realm}",nonce="${challenge.nonce}",uri="${uri}",response="${response}",algorithm=MD5,qop=auth,nc=00000001,cnonce="${cnonce}",opaque="${challenge.opaque}"`;
}

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
  if (challenge) lines.push(authHeader(challenge, 'REGISTER', uri));
  lines.push('Content-Length: 0', '', '');
  send(lines);
}

function sendInvite() {
  const uri = `sip:${TARGET}@${SERVER}:${PORT}`;
  const sdp = offerSdp();
  send([
    `INVITE ${uri} SIP/2.0`,
    `Via: SIP/2.0/UDP ${me()};branch=${rnd()};rport`,
    `From: <sip:${USER}@${IP()}>;tag=${fromTag}`,
    `To: <sip:${TARGET}@${SERVER}>`,
    `Call-ID: ${callId}`,
    `CSeq: ${++cseq} INVITE`,
    `Contact: <sip:${USER}@${me()}>`,
    'Max-Forwards: 70',
    'Content-Type: application/sdp',
    `Content-Length: ${Buffer.byteLength(sdp)}`,
    '',
    sdp,
  ]);
}

function sendInviteAuth(challenge) {
  const uri = `sip:${TARGET}@${SERVER}:${PORT}`;
  const sdp = offerSdp();
  send([
    `INVITE ${uri} SIP/2.0`,
    `Via: SIP/2.0/UDP ${me()};branch=${rnd()};rport`,
    `From: <sip:${USER}@${IP()}>;tag=${fromTag}`,
    `To: <sip:${TARGET}@${SERVER}>`,
    `Call-ID: ${callId}`,
    `CSeq: ${cseq} INVITE`,
    `Contact: <sip:${USER}@${me()}>`,
    authHeader(challenge, 'INVITE', uri),
    'Max-Forwards: 70',
    'Content-Type: application/sdp',
    `Content-Length: ${Buffer.byteLength(sdp)}`,
    '',
    sdp,
  ]);
}

function sendAck() {
  const uri = `sip:${TARGET}@${SERVER}:${PORT}`;
  send([
    `ACK ${uri} SIP/2.0`,
    `Via: SIP/2.0/UDP ${me()};branch=${rnd()};rport`,
    `From: <sip:${USER}@${IP()}>;tag=${fromTag}`,
    `To: <sip:${TARGET}@${SERVER}>${toTag ? ';tag=' + toTag : ''}`,
    `Call-ID: ${callId}`,
    `CSeq: ${cseq} ACK`,
    'Max-Forwards: 70',
    'Content-Length: 0', '', '',
  ]);
}

function sendBye() {
  const uri = contactUri || `sip:${TARGET}@${SERVER}:${PORT}`;
  send([
    `BYE ${uri} SIP/2.0`,
    `Via: SIP/2.0/UDP ${me()};branch=${rnd()};rport`,
    `From: <sip:${USER}@${IP()}>;tag=${fromTag}`,
    `To: <sip:${TARGET}@${SERVER}>${toTag ? ';tag=' + toTag : ''}`,
    `Call-ID: ${callId}`,
    `CSeq: ${++cseq} BYE`,
    'Max-Forwards: 70',
    'Content-Length: 0', '', '',
  ]);
}

socket.on('message', (m) => {
  const str = m.toString();
  const first = str.split('\r\n')[0];
  if (first.startsWith('BYE ')) {
    send([
      'SIP/2.0 200 OK',
      ...['Via:', 'From:', 'To:', 'Call-ID:', 'CSeq:'].map(name => str.split('\r\n').find(line => line.startsWith(name))),
      'Content-Length: 0', '', '',
    ]);
    console.log('REVOKED-BYE');
    setTimeout(() => { socket.close(); process.exit(0); }, 100);
    return;
  }
  if (first.startsWith('OPTIONS')) {
    const viaHeader = str.split('\r\n').find(l => l.startsWith('Via: '));
    const toHeader = str.split('\r\n').find(l => l.startsWith('To: '));
    send([
      'SIP/2.0 200 OK',
      viaHeader,
      str.split('\r\n').find(l => l.startsWith('From: ')),
      toHeader ? toHeader + ';tag=' + rnd() : `To: <sip:${USER}@${SERVER}>;tag=` + rnd(),
      str.split('\r\n').find(l => l.startsWith('Call-ID: ')),
      str.split('\r\n').find(l => l.startsWith('CSeq: ')),
      `Contact: <sip:${USER}@${me()}>`,
      'Content-Length: 0', '', '',
    ]);
    return;
  }
  const statusMatch = str.split('\r\n')[0].match(/SIP\/2\.0 (\d+)/);
  if (!statusMatch) { console.log(`<-- non-response: ${str.split('\r\n')[0]}`); return; }
  const status = parseInt(statusMatch[1]);
  console.log(`<-- ${str.split('\r\n')[0]}`);

  const isRegister = (str.split('\r\n').find(l => l.startsWith('CSeq')) || '').includes('REGISTER');

  if (phase === 'registering' && status === 401 && isRegister) {
    const www = str.split('\r\n').find(l => l.toLowerCase().startsWith('www-authenticate'));
    const realm = www.match(/realm="([^"]+)"/)[1];
    const nonce = www.match(/nonce="([^"]+)"/)[1];
    const opq = (www.match(/opaque="([^"]*)"/) || [, ''])[1];
    sendRegister(2, { realm, nonce, opaque: opq });
  } else if (phase === 'registering' && status === 200 && isRegister) {
    console.log('REGISTERED');
    phase = 'inviting';
    setTimeout(sendInvite, 200);
  } else if (phase === 'inviting' && (status === 100 || status === 180 || status === 183)) {
    if (status === 180) console.log('RINGING');
  } else if (phase === 'inviting' && status === 200) {
    const toHeader = str.split('\r\n').find(l => l.startsWith('To: ')) || '';
    const t = toHeader.match(/tag=([^;\r\n]+)/);
    toTag = t ? t[1] : null;
    const c = str.split('\r\n').find(l => l.startsWith('Contact: '));
    if (c) contactUri = c.slice('Contact: '.length).trim().replace(/<|>/g, '').split(';')[0];
    reportVideo(str.split('\r\n\r\n')[1] || '');
    console.log('ANSWERED');
    sendAck();
    phase = 'active';
    setTimeout(() => { phase = 'bye'; sendBye(); }, Number(process.env.CALL_HOLD_SECONDS || 2) * 1000);
  } else if (phase === 'bye' && status === 200) {
    console.log('CALL-COMPLETE');
    socket.close();
    process.exit(0);
  } else if (phase === 'inviting' && status === 401) {
    const www = str.split('\r\n').find(l => l.toLowerCase().startsWith('www-authenticate'));
    const realm = www.match(/realm="([^"]+)"/)[1];
    const nonce = www.match(/nonce="([^"]+)"/)[1];
    const opq = (www.match(/opaque="([^"]*)"/) || [, ''])[1];
    console.log('INVITE-AUTH-CHALLENGE');
    sendInviteAuth({ realm, nonce, opaque: opq });
  } else if (status >= 400 && !isRegister) {
    console.log(`CALL-FAILED ${status}`);
    socket.close();
    process.exit(2);
  } else if (status >= 400) {
    console.log(`REGISTER-FAILED ${status}`);
    socket.close();
    process.exit(3);
  }
});

socket.bind(0, () => sendRegister(1));
setTimeout(() => { console.log(`TIMEOUT phase=${phase}`); process.exit(1); }, 15000);
