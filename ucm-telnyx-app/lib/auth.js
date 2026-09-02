const crypto = require('crypto');

const APP_PASSWORD = process.env.APP_PASSWORD;
const SESSION_SECRET = process.env.SESSION_SECRET;
const COOKIE_NAME = 'ucm_session';
const MAX_AGE_MS = 30 * 24 * 60 * 60 * 1000; // 30 days

if (!APP_PASSWORD || !SESSION_SECRET) {
  throw new Error('APP_PASSWORD and SESSION_SECRET must be set in .env');
}

function sign(value) {
  const mac = crypto.createHmac('sha256', SESSION_SECRET).update(value).digest('hex');
  return `${value}.${mac}`;
}

function verify(token) {
  if (!token || !token.includes('.')) return false;
  const idx = token.lastIndexOf('.');
  const value = token.slice(0, idx);
  const mac = token.slice(idx + 1);
  const expected = crypto.createHmac('sha256', SESSION_SECRET).update(value).digest('hex');
  try {
    return crypto.timingSafeEqual(Buffer.from(mac), Buffer.from(expected)) && Number(value) > Date.now();
  } catch {
    return false;
  }
}

function checkPassword(candidate) {
  const a = Buffer.from(String(candidate || ''));
  const b = Buffer.from(APP_PASSWORD);
  if (a.length !== b.length) return false;
  return crypto.timingSafeEqual(a, b);
}

function issueCookie(res) {
  const expiresAt = Date.now() + MAX_AGE_MS;
  const token = sign(String(expiresAt));
  res.cookie(COOKIE_NAME, token, {
    httpOnly: true,
    sameSite: 'lax',
    secure: process.env.NODE_ENV === 'production',
    maxAge: MAX_AGE_MS,
  });
}

function clearCookie(res) {
  res.clearCookie(COOKIE_NAME);
}

function requireAuth(req, res, next) {
  if (verify(req.cookies?.[COOKIE_NAME])) return next();
  return res.status(401).json({ error: 'unauthorized' });
}

// Same check, usable outside Express middleware (e.g. WebSocket upgrade).
function isAuthenticated(cookieHeader) {
  if (!cookieHeader) return false;
  const match = cookieHeader.match(new RegExp(`${COOKIE_NAME}=([^;]+)`));
  if (!match) return false;
  return verify(decodeURIComponent(match[1]));
}

module.exports = { checkPassword, issueCookie, clearCookie, requireAuth, isAuthenticated, COOKIE_NAME };
