'use strict';

/* ---------------------------------------------------------------------- */
/* Auth / screen switching                                                 */
/* ---------------------------------------------------------------------- */

const loginScreen = document.getElementById('login-screen');
const appScreen = document.getElementById('app');

async function checkSession() {
  const res = await fetch('/api/session');
  const { authenticated } = await res.json();
  if (authenticated) {
    showApp();
  } else {
    showLogin();
  }
}

function showLogin() {
  loginScreen.classList.remove('hidden');
  appScreen.classList.add('hidden');
}

function showApp() {
  loginScreen.classList.add('hidden');
  appScreen.classList.remove('hidden');
  initApp();
}

document.getElementById('login-form').addEventListener('submit', async (e) => {
  e.preventDefault();
  const password = document.getElementById('login-password').value;
  const errorEl = document.getElementById('login-error');
  errorEl.classList.add('hidden');
  const res = await fetch('/api/login', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ password }),
  });
  if (res.ok) {
    showApp();
  } else {
    errorEl.textContent = 'Wrong password';
    errorEl.classList.remove('hidden');
  }
});

document.getElementById('logout-btn').addEventListener('click', async () => {
  await fetch('/api/logout', { method: 'POST' });
  location.reload();
});

let appInitialized = false;

function initApp() {
  if (appInitialized) return;
  appInitialized = true;
  initTabs();
  initPhone();
  initMessaging();
}

/* ---------------------------------------------------------------------- */
/* Tabs                                                                    */
/* ---------------------------------------------------------------------- */

function initTabs() {
  document.querySelectorAll('.tab-btn').forEach((btn) => {
    btn.addEventListener('click', () => {
      document.querySelectorAll('.tab-btn').forEach((b) => b.classList.remove('active'));
      document.querySelectorAll('.tab-panel').forEach((p) => p.classList.remove('active'));
      btn.classList.add('active');
      document.getElementById(`tab-${btn.dataset.tab}`).classList.add('active');
    });
  });
}

/* ---------------------------------------------------------------------- */
/* SIP softphone (SIP.js)                                                  */
/* ---------------------------------------------------------------------- */

const SIP_SETTINGS_KEY = 'ucm_sip_settings';

let userAgent = null;
let registerer = null;
let activeSession = null;
let callTimerInterval = null;
let callStartedAt = null;

function loadSipSettings() {
  try {
    return JSON.parse(localStorage.getItem(SIP_SETTINGS_KEY)) || {};
  } catch {
    return {};
  }
}

function saveSipSettings(settings) {
  localStorage.setItem(SIP_SETTINGS_KEY, JSON.stringify(settings));
}

async function initPhone() {
  const form = document.getElementById('sip-settings-form');
  const statusEl = document.getElementById('sip-settings-status');

  let settings = loadSipSettings();

  // First run: prefill from server-provided defaults (from .env), if any.
  if (!settings.wssUrl) {
    try {
      const res = await fetch('/api/sip-config');
      if (res.ok) {
        const defaults = await res.json();
        if (defaults.wssUrl) settings = defaults;
      }
    } catch {
      /* ignore - fall back to empty form */
    }
  }

  document.getElementById('sip-wss').value = settings.wssUrl || '';
  document.getElementById('sip-domain').value = settings.domain || '';
  document.getElementById('sip-extension').value = settings.extension || '';
  document.getElementById('sip-password').value = settings.password || '';

  if (settings.wssUrl && settings.extension && settings.password) {
    registerSip(settings, statusEl);
  }

  form.addEventListener('submit', (e) => {
    e.preventDefault();
    const newSettings = {
      wssUrl: document.getElementById('sip-wss').value.trim(),
      domain: document.getElementById('sip-domain').value.trim(),
      extension: document.getElementById('sip-extension').value.trim(),
      password: document.getElementById('sip-password').value,
    };
    saveSipSettings(newSettings);
    registerSip(newSettings, statusEl);
  });

  document.getElementById('sip-unregister-btn').addEventListener('click', async () => {
    if (registerer) await registerer.unregister().catch(() => {});
    if (userAgent) await userAgent.stop().catch(() => {});
    userAgent = null;
    registerer = null;
    setRegStatus(false, 'Unregistered');
  });

  initDialpad();
}

function setRegStatus(ok, text) {
  document.getElementById('reg-dot').classList.toggle('registered', ok);
  document.getElementById('reg-text').textContent = text;
}

async function registerSip(settings, statusEl) {
  const { wssUrl, domain, extension, password } = settings;
  if (!wssUrl || !domain || !extension || !password) {
    statusEl.textContent = 'Fill in all fields.';
    return;
  }

  statusEl.textContent = 'Connecting…';
  setRegStatus(false, 'Connecting…');

  if (registerer) await registerer.unregister().catch(() => {});
  if (userAgent) await userAgent.stop().catch(() => {});

  const uri = SIP.UserAgent.makeURI(`sip:${extension}@${domain}`);
  if (!uri) {
    statusEl.textContent = 'Invalid extension/domain.';
    setRegStatus(false, 'Registration failed');
    return;
  }

  userAgent = new SIP.UserAgent({
    uri,
    transportOptions: { server: wssUrl },
    authorizationUsername: extension,
    authorizationPassword: password,
    displayName: extension,
    sessionDescriptionHandlerFactoryOptions: {
      constraints: { audio: true, video: false },
    },
    delegate: {
      onInvite(invitation) {
        handleIncomingCall(invitation);
      },
      onDisconnect(error) {
        setRegStatus(false, error ? `Disconnected: ${error.message}` : 'Disconnected');
      },
    },
  });

  try {
    await userAgent.start();
    registerer = new SIP.Registerer(userAgent);
    registerer.stateChange.addListener((state) => {
      if (state === SIP.RegistererState.Registered) {
        setRegStatus(true, `Registered as ${extension}`);
        statusEl.textContent = `Registered as ${extension}.`;
      } else if (state === SIP.RegistererState.Unregistered) {
        setRegStatus(false, 'Unregistered');
      } else if (state === SIP.RegistererState.Terminated) {
        setRegStatus(false, 'Registration terminated');
      }
    });
    await registerer.register();
  } catch (err) {
    console.error('SIP registration failed:', err);
    setRegStatus(false, 'Registration failed');
    statusEl.textContent =
      `Could not connect: ${err.message}. Check the WSS URL/port, that WebRTC is ` +
      `enabled on the extension, and that your browser trusts the PBX's TLS certificate ` +
      `(see README troubleshooting).`;
  }
}

function initDialpad() {
  const dialInput = document.getElementById('dial-number');
  document.querySelectorAll('.key').forEach((key) => {
    key.addEventListener('click', () => {
      if (activeSession) {
        sendDtmf(key.dataset.key);
      } else {
        dialInput.value += key.dataset.key;
      }
    });
  });

  document.getElementById('call-btn').addEventListener('click', () => {
    const number = dialInput.value.trim();
    if (number) startCall(number);
  });

  document.getElementById('hangup-btn').addEventListener('click', endCall);
  document.getElementById('answer-btn').addEventListener('click', () => acceptIncoming());
  document.getElementById('reject-btn').addEventListener('click', () => rejectIncoming());
  document.getElementById('mute-btn').addEventListener('click', toggleMute);
  document.getElementById('hold-btn').addEventListener('click', toggleHold);
}

function startCall(number) {
  if (!userAgent) {
    alert('Not registered to the PBX yet. Check Settings.');
    return;
  }
  const settings = loadSipSettings();
  const target = SIP.UserAgent.makeURI(`sip:${number}@${settings.domain}`);
  const inviter = new SIP.Inviter(userAgent, target, {
    sessionDescriptionHandlerOptions: { constraints: { audio: true, video: false } },
  });

  activeSession = inviter;
  showCallUI('active', number);

  inviter.stateChange.addListener((state) => onSessionStateChange(state));
  inviter.invite().catch((err) => {
    console.error('Call failed:', err);
    endCall();
  });
}

let pendingInvitation = null;

function handleIncomingCall(invitation) {
  if (activeSession) {
    invitation.reject();
    return;
  }
  pendingInvitation = invitation;
  const from = invitation.remoteIdentity?.uri?.user || 'Unknown';
  document.getElementById('incoming-from').textContent = from;
  showCallUI('incoming');

  invitation.stateChange.addListener((state) => {
    if (state === SIP.SessionState.Terminated && pendingInvitation === invitation) {
      pendingInvitation = null;
      if (!activeSession) showCallUI('idle');
    }
  });
}

function acceptIncoming() {
  if (!pendingInvitation) return;
  const invitation = pendingInvitation;
  pendingInvitation = null;
  activeSession = invitation;

  const from = invitation.remoteIdentity?.uri?.user || 'Unknown';
  showCallUI('active', from);
  invitation.stateChange.addListener((state) => onSessionStateChange(state));
  invitation
    .accept({ sessionDescriptionHandlerOptions: { constraints: { audio: true, video: false } } })
    .catch((err) => {
      console.error('Accept failed:', err);
      endCall();
    });
}

function rejectIncoming() {
  if (!pendingInvitation) return;
  pendingInvitation.reject().catch(() => {});
  pendingInvitation = null;
  showCallUI('idle');
}

function onSessionStateChange(state) {
  if (state === SIP.SessionState.Established) {
    attachRemoteAudio(activeSession);
    startCallTimer();
  } else if (state === SIP.SessionState.Terminated) {
    endCall();
  }
}

function endCall() {
  if (activeSession) {
    const state = activeSession.state;
    if (state === SIP.SessionState.Established) {
      activeSession.bye().catch(() => {});
    } else if (state === SIP.SessionState.Establishing) {
      if (typeof activeSession.cancel === 'function') activeSession.cancel().catch(() => {});
    }
  }
  activeSession = null;
  stopCallTimer();
  showCallUI('idle');
}

function attachRemoteAudio(session) {
  const remoteAudio = document.getElementById('remote-audio');
  const remoteStream = new MediaStream();
  session.sessionDescriptionHandler.peerConnection.getReceivers().forEach((receiver) => {
    if (receiver.track) remoteStream.addTrack(receiver.track);
  });
  remoteAudio.srcObject = remoteStream;
  remoteAudio.play().catch(() => {});
}

function sendDtmf(tone) {
  if (!activeSession || !activeSession.sessionDescriptionHandler) return;
  activeSession.sessionDescriptionHandler.sendDtmf(tone);
}

function toggleMute() {
  if (!activeSession) return;
  const pc = activeSession.sessionDescriptionHandler.peerConnection;
  pc.getSenders().forEach((sender) => {
    if (sender.track && sender.track.kind === 'audio') sender.track.enabled = !sender.track.enabled;
  });
  document.getElementById('mute-btn').classList.toggle('on');
}

function toggleHold() {
  if (!activeSession) return;
  const btn = document.getElementById('hold-btn');
  const held = btn.classList.toggle('on');
  activeSession
    .invite({
      sessionDescriptionHandlerOptions: {
        hold: held,
      },
    })
    .catch((err) => console.error('Hold failed:', err));
}

function startCallTimer() {
  callStartedAt = Date.now();
  const timerEl = document.getElementById('call-timer');
  callTimerInterval = setInterval(() => {
    const secs = Math.floor((Date.now() - callStartedAt) / 1000);
    const mm = String(Math.floor(secs / 60)).padStart(2, '0');
    const ss = String(secs % 60).padStart(2, '0');
    timerEl.textContent = `${mm}:${ss}`;
  }, 1000);
}

function stopCallTimer() {
  clearInterval(callTimerInterval);
  document.getElementById('call-timer').textContent = '00:00';
}

function showCallUI(mode, remote) {
  document.getElementById('call-idle').classList.toggle('hidden', mode !== 'idle');
  document.getElementById('call-active').classList.toggle('hidden', mode !== 'active');
  document.getElementById('call-incoming').classList.toggle('hidden', mode !== 'incoming');
  if (mode === 'active' && remote) {
    document.getElementById('call-remote').textContent = remote;
  }
  if (mode === 'idle') {
    document.getElementById('dial-number').value = '';
    document.getElementById('mute-btn').classList.remove('on');
    document.getElementById('hold-btn').classList.remove('on');
  }
}

/* ---------------------------------------------------------------------- */
/* Messaging                                                               */
/* ---------------------------------------------------------------------- */

let currentConversation = null;
let conversationsCache = [];
let ws = null;

function initMessaging() {
  loadConversations();
  connectWebSocket();

  document.getElementById('new-convo-btn').addEventListener('click', () => {
    const number = prompt('Enter phone number in E.164 format (e.g. +15551234567):');
    if (number) openConversation(number.trim());
  });

  document.getElementById('compose-form').addEventListener('submit', async (e) => {
    e.preventDefault();
    const textInput = document.getElementById('compose-text');
    const toInput = document.getElementById('compose-to');
    const text = textInput.value.trim();
    const to = currentConversation || toInput.value.trim();
    if (!text || !to) return;

    textInput.value = '';
    try {
      await fetch('/api/messages/send', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ to, text }),
      });
      if (!currentConversation) openConversation(to);
    } catch (err) {
      alert(`Failed to send: ${err.message}`);
    }
  });
}

async function loadConversations() {
  const res = await fetch('/api/messages');
  conversationsCache = await res.json();
  renderConversationList();
}

function renderConversationList() {
  const container = document.getElementById('conversations');
  container.innerHTML = '';
  conversationsCache.forEach((c) => {
    const item = document.createElement('div');
    item.className = 'conversation-item' + (c.number === currentConversation ? ' active' : '');
    const preview = c.lastMessage ? c.lastMessage.text : '';
    item.innerHTML = `<div class="conversation-number">${escapeHtml(c.number)}</div>
      <div class="conversation-preview">${escapeHtml(preview)}</div>`;
    item.addEventListener('click', () => openConversation(c.number));
    container.appendChild(item);
  });
}

async function openConversation(number) {
  currentConversation = number;
  document.getElementById('conversation-title').textContent = number;
  document.getElementById('compose-to').classList.add('hidden');
  renderConversationList();

  const res = await fetch(`/api/messages/${encodeURIComponent(number)}`);
  const conversation = await res.json();
  renderMessages(conversation.messages);
}

function renderMessages(messages) {
  const container = document.getElementById('conversation-messages');
  container.innerHTML = '';
  messages.forEach((m) => container.appendChild(renderMessageBubble(m)));
  container.scrollTop = container.scrollHeight;
}

function renderMessageBubble(m) {
  const bubble = document.createElement('div');
  bubble.className = `bubble ${m.direction}`;
  bubble.innerHTML = `<div class="bubble-text">${escapeHtml(m.text || '')}</div>
    <div class="bubble-time">${new Date(m.timestamp).toLocaleString()}</div>`;
  return bubble;
}

function connectWebSocket() {
  const protocol = location.protocol === 'https:' ? 'wss:' : 'ws:';
  ws = new WebSocket(`${protocol}//${location.host}/ws`);

  ws.addEventListener('message', (event) => {
    const payload = JSON.parse(event.data);
    if (payload.type === 'message') {
      handleIncomingMessage(payload.message);
    }
  });

  ws.addEventListener('close', () => {
    setTimeout(connectWebSocket, 3000); // reconnect
  });
}

function handleIncomingMessage(message) {
  const other = message.direction === 'outbound' ? message.to : message.from;
  loadConversations();
  if (other === currentConversation) {
    document.getElementById('conversation-messages').appendChild(renderMessageBubble(message));
  }
}

function escapeHtml(str) {
  const div = document.createElement('div');
  div.textContent = str;
  return div.innerHTML;
}

/* ---------------------------------------------------------------------- */

checkSession();
