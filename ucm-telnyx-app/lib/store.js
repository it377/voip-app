// Flat-file JSON message store, grouped by conversation (the other party's
// phone number). Good enough for one phone number's worth of texting; if
// history grows into the tens of thousands of messages or you need search,
// swap this for SQLite (better-sqlite3) - the read/write API below is small
// enough to reimplement against a real DB without touching server.js.
const fs = require('fs');
const path = require('path');

const DATA_DIR = path.join(__dirname, '..', 'data');
const DATA_FILE = path.join(DATA_DIR, 'messages.json');

function load() {
  try {
    const raw = fs.readFileSync(DATA_FILE, 'utf8');
    return JSON.parse(raw);
  } catch (err) {
    if (err.code === 'ENOENT') return {};
    console.error('Failed to read message store, starting empty:', err.message);
    return {};
  }
}

function saveAtomic(data) {
  fs.mkdirSync(DATA_DIR, { recursive: true });
  const tmpFile = `${DATA_FILE}.${process.pid}.${Date.now()}.tmp`;
  fs.writeFileSync(tmpFile, JSON.stringify(data, null, 2));
  fs.renameSync(tmpFile, DATA_FILE); // atomic on the same filesystem
}

let db = load();

function addMessage({ direction, to, from, text, media = [], telnyxId = null, status = 'sent' }) {
  const other = direction === 'outbound' ? to : from;
  const key = normalizeNumber(other);
  if (!db[key]) db[key] = { number: key, messages: [] };

  const message = {
    id: telnyxId || `local-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`,
    direction,
    to,
    from,
    text,
    media,
    status,
    timestamp: new Date().toISOString(),
  };

  db[key].messages.push(message);
  saveAtomic(db);
  return message;
}

function listConversations() {
  return Object.values(db)
    .map((c) => ({
      number: c.number,
      lastMessage: c.messages[c.messages.length - 1] || null,
      count: c.messages.length,
    }))
    .sort((a, b) => {
      const at = a.lastMessage ? a.lastMessage.timestamp : '';
      const bt = b.lastMessage ? b.lastMessage.timestamp : '';
      return bt.localeCompare(at);
    });
}

function getConversation(number) {
  const key = normalizeNumber(number);
  return db[key] || { number: key, messages: [] };
}

function normalizeNumber(n) {
  return String(n || '').trim();
}

module.exports = { addMessage, listConversations, getConversation };
