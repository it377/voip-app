require('dotenv').config();

const path = require('path');
const http = require('http');
const express = require('express');
const cookieParser = require('cookie-parser');
const { WebSocketServer } = require('ws');

const store = require('./lib/store');
const telnyx = require('./lib/telnyx');
const auth = require('./lib/auth');

const PORT = process.env.PORT || 3000;

const app = express();
app.use(cookieParser());

// Keep the raw body around for webhook signature verification, while still
// parsing JSON for every other route.
app.use(
  express.json({
    verify: (req, _res, buf) => {
      req.rawBody = buf;
    },
  })
);

// ---- Auth ----
app.post('/api/login', (req, res) => {
  if (!auth.checkPassword(req.body?.password)) {
    return res.status(401).json({ error: 'wrong password' });
  }
  auth.issueCookie(res);
  res.json({ ok: true });
});

app.post('/api/logout', (req, res) => {
  auth.clearCookie(res);
  res.json({ ok: true });
});

app.get('/api/session', (req, res) => {
  res.json({ authenticated: auth.isAuthenticated(req.headers.cookie) });
});

// Client-side SIP defaults, handed to the browser after login so the
// softphone can pre-fill its settings form. None of this is a secret held by
// the server that the browser doesn't already need.
app.get('/api/sip-config', auth.requireAuth, (_req, res) => {
  res.json({
    wssUrl: process.env.SIP_WSS_URL || '',
    domain: process.env.SIP_DOMAIN || '',
    extension: process.env.SIP_EXTENSION || '',
    password: process.env.SIP_PASSWORD || '',
  });
});

// ---- Messaging ----
app.get('/api/messages', auth.requireAuth, (_req, res) => {
  res.json(store.listConversations());
});

app.get('/api/messages/:number', auth.requireAuth, (req, res) => {
  res.json(store.getConversation(req.params.number));
});

app.post('/api/messages/send', auth.requireAuth, async (req, res) => {
  const { to, text } = req.body || {};
  if (!to || !text) return res.status(400).json({ error: 'to and text are required' });

  try {
    const result = await telnyx.sendMessage({ to, text });
    const message = store.addMessage({
      direction: 'outbound',
      to,
      from: process.env.TELNYX_FROM_NUMBER,
      text,
      telnyxId: result?.id,
      status: 'sent',
    });
    broadcast({ type: 'message', message });
    res.json({ ok: true, message });
  } catch (err) {
    console.error('Send failed:', err.message);
    res.status(502).json({ error: err.message });
  }
});

// ---- Telnyx inbound webhook (no shared-password auth - Telnyx can't send it;
// protected instead by the ed25519 signature check) ----
app.post('/webhooks/telnyx', (req, res) => {
  const signatureHeader = req.header('telnyx-signature-ed25519');
  const timestampHeader = req.header('telnyx-timestamp');
  const { valid, skipped } = telnyx.verifyWebhookSignature({
    rawBody: req.rawBody,
    signatureHeader,
    timestampHeader,
  });

  if (!valid) {
    console.warn('Rejected webhook with invalid signature');
    return res.status(401).json({ error: 'invalid signature' });
  }
  if (skipped) {
    console.warn('TELNYX_PUBLIC_KEY not set - webhook signature NOT verified. See README.');
  }

  const event = req.body?.data;
  if (event?.event_type === 'message.received') {
    const payload = event.payload;
    const message = store.addMessage({
      direction: 'inbound',
      to: payload.to?.[0]?.phone_number,
      from: payload.from?.phone_number,
      text: payload.text,
      media: payload.media || [],
      telnyxId: payload.id,
      status: 'received',
    });
    broadcast({ type: 'message', message });
  }

  // Telnyx just needs a 2xx quickly; ignore other event types (delivery
  // receipts etc.) for now.
  res.status(200).json({ ok: true });
});

app.get('/health', (_req, res) => res.json({ ok: true }));

// Static assets last, so /api and /webhooks above take priority.
app.use(express.static(path.join(__dirname, 'public')));

const server = http.createServer(app);

// ---- Live push to the browser over WebSocket ----
const wss = new WebSocketServer({ noServer: true });
const clients = new Set();

server.on('upgrade', (req, socket, head) => {
  if (req.url !== '/ws') return socket.destroy();
  if (!auth.isAuthenticated(req.headers.cookie)) {
    socket.write('HTTP/1.1 401 Unauthorized\r\n\r\n');
    return socket.destroy();
  }
  wss.handleUpgrade(req, socket, head, (ws) => {
    clients.add(ws);
    ws.on('close', () => clients.delete(ws));
  });
});

function broadcast(payload) {
  const data = JSON.stringify(payload);
  for (const ws of clients) {
    if (ws.readyState === ws.OPEN) ws.send(data);
  }
}

server.listen(PORT, () => {
  console.log(`ucm-telnyx-app listening on http://localhost:${PORT}`);
});
