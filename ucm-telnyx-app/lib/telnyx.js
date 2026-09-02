const nacl = require('tweetnacl');

const TELNYX_API_KEY = process.env.TELNYX_API_KEY;
const TELNYX_FROM_NUMBER = process.env.TELNYX_FROM_NUMBER;
const TELNYX_MESSAGING_PROFILE_ID = process.env.TELNYX_MESSAGING_PROFILE_ID;
const TELNYX_PUBLIC_KEY = process.env.TELNYX_PUBLIC_KEY;

async function sendMessage({ to, text }) {
  if (!TELNYX_API_KEY || !TELNYX_FROM_NUMBER) {
    throw new Error('TELNYX_API_KEY / TELNYX_FROM_NUMBER not configured in .env');
  }

  const res = await fetch('https://api.telnyx.com/v2/messages', {
    method: 'POST',
    headers: {
      Authorization: `Bearer ${TELNYX_API_KEY}`,
      'Content-Type': 'application/json',
    },
    body: JSON.stringify({
      from: TELNYX_FROM_NUMBER,
      to,
      text,
      ...(TELNYX_MESSAGING_PROFILE_ID ? { messaging_profile_id: TELNYX_MESSAGING_PROFILE_ID } : {}),
    }),
  });

  const body = await res.json().catch(() => ({}));
  if (!res.ok) {
    const detail = body?.errors?.[0]?.detail || res.statusText;
    throw new Error(`Telnyx send failed (${res.status}): ${detail}`);
  }
  return body.data;
}

// Telnyx signs webhooks with ed25519. Headers: telnyx-signature-ed25519 (base64)
// and telnyx-timestamp (unix seconds). The signed payload is `${timestamp}|${rawBody}`.
// Verifying this stops randoms on the internet from POSTing fake "inbound texts"
// to your open webhook endpoint.
function verifyWebhookSignature({ rawBody, signatureHeader, timestampHeader }) {
  if (!TELNYX_PUBLIC_KEY) return { valid: true, skipped: true }; // not configured - see README
  if (!signatureHeader || !timestampHeader) return { valid: false, skipped: false };

  try {
    const signedPayload = Buffer.from(`${timestampHeader}|${rawBody}`, 'utf8');
    const signature = Buffer.from(signatureHeader, 'base64');
    const publicKey = Buffer.from(TELNYX_PUBLIC_KEY, 'base64');
    const valid = nacl.sign.detached.verify(signedPayload, signature, publicKey);
    return { valid, skipped: false };
  } catch (err) {
    return { valid: false, skipped: false, error: err.message };
  }
}

module.exports = { sendMessage, verifyWebhookSignature };
