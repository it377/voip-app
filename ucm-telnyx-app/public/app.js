'use strict';

/* ---------------------------------------------------------------------- */
/* Auth / screen switching                                                 */
/* ---------------------------------------------------------------------- */

const loginScreen = document.getElementById('login-screen');
const appScreen = document.getElementById('app');

// Who's logged in: {id, username, displayName, role, canMessage, isAdmin}
let me = null;

async function checkSession() {
  const res = await fetch('/api/session');
  const data = await res.json();
  if (data.authenticated) {
    me = data.user;
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
  const username = document.getElementById('login-username').value.trim();
  const password = document.getElementById('login-password').value;
  const errorEl = document.getElementById('login-error');
  errorEl.classList.add('hidden');
  const res = await fetch('/api/login', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username, password }),
  });
  const data = await res.json().catch(() => ({}));
  if (res.ok) {
    me = data.user;
    showApp();
  } else {
    errorEl.textContent = data.error || 'Sign in failed';
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
  applyPermissions();
  initTabs();
  initPhone();
  if (me && me.canMessage) initMessaging();
  if (me && me.isAdmin) initAdmin();
}

// Show only the tabs this account is allowed to use. The server enforces all
// of this too - hiding tabs is just so people aren't shown dead ends.
function applyPermissions() {
  if (!me) return;
  const messagesTab = document.querySelector('.tab-btn[data-tab="messages"]');
  if (messagesTab) messagesTab.classList.toggle('hidden', !me.canMessage);
  const adminTab = document.getElementById('admin-tab-btn');
  if (adminTab) adminTab.classList.toggle('hidden', !me.isAdmin);
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

let userAgent = null;
let registerer = null;
let activeSession = null;
let callTimerInterval = null;
let callStartedAt = null;
// Provisioned by the server from the extension an admin assigned to this
// account - nothing SIP-related is typed in or stored in this browser.
let sipSettings = {};

async function fetchSipConfig() {
  const res = await fetch('/api/sip-config');
  if (!res.ok) throw new Error('Could not load your extension settings');
  return res.json();
}

async function initPhone() {
  const statusEl = document.getElementById('sip-settings-status');

  document.getElementById('account-name').textContent = me
    ? `${me.displayName} (${me.username})`
    : '—';
  document.getElementById('account-messaging').textContent =
    me && me.canMessage ? 'Enabled' : 'Not enabled for this account';

  await connectSip(statusEl);

  document.getElementById('sip-reconnect-btn').addEventListener('click', () => connectSip(statusEl));

  document.getElementById('sip-unregister-btn').addEventListener('click', async () => {
    if (registerer) await registerer.unregister().catch(() => {});
    if (userAgent) await userAgent.stop().catch(() => {});
    userAgent = null;
    registerer = null;
    setRegStatus(false, 'Unregistered');
  });

  initDialpad();
}

async function connectSip(statusEl) {
  try {
    sipSettings = await fetchSipConfig();
  } catch (err) {
    statusEl.textContent = err.message;
    setRegStatus(false, 'No extension assigned');
    return;
  }

  document.getElementById('account-extension').textContent = sipSettings.extension || 'Not assigned';
  document.getElementById('account-pbx').textContent = sipSettings.domain || 'Not configured';

  if (!sipSettings.extension || !sipSettings.password) {
    setRegStatus(false, 'No extension assigned');
    statusEl.textContent =
      'An administrator has not assigned you an extension yet. Calling is unavailable until they do.';
    return;
  }
  if (!sipSettings.wssUrl || !sipSettings.domain) {
    setRegStatus(false, 'PBX not configured');
    statusEl.textContent =
      'An administrator has not filled in the PBX settings yet (Admin tab).';
    return;
  }

  registerSip(sipSettings, statusEl);
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
  const backspaceBtn = document.getElementById('dial-backspace');

  const updateBackspaceVisibility = () => {
    backspaceBtn.classList.toggle('hidden', dialInput.value.length === 0);
  };

  document.querySelectorAll('.key').forEach((key) => {
    key.addEventListener('click', () => {
      if (activeSession) {
        sendDtmf(key.dataset.key);
      } else {
        dialInput.value += key.dataset.key;
        updateBackspaceVisibility();
      }
    });
  });

  backspaceBtn.addEventListener('click', () => {
    dialInput.value = dialInput.value.slice(0, -1);
    updateBackspaceVisibility();
  });
  dialInput.addEventListener('input', updateBackspaceVisibility);

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
  const target = SIP.UserAgent.makeURI(`sip:${number}@${sipSettings.domain}`);
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
    document.getElementById('dial-backspace').classList.add('hidden');
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

  document.getElementById('back-to-conversations').addEventListener('click', () => {
    currentConversation = null;
    document.querySelector('.messages-layout').classList.remove('thread-open');
    renderConversationList();
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
  document.getElementById('conversation-title-text').textContent = number;
  document.getElementById('compose-to').classList.add('hidden');
  document.querySelector('.messages-layout').classList.add('thread-open');
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
/* Admin panel                                                             */
/* ---------------------------------------------------------------------- */

let editingUserId = null; // null while adding a new user

function initAdmin() {
  loadPbxSettings();
  loadUsers();

  document.getElementById('pbx-save-btn').addEventListener('click', savePbxSettings);
  document.getElementById('add-user-btn').addEventListener('click', () => openUserDialog(null));
  document.getElementById('user-cancel-btn').addEventListener('click', closeUserDialog);
  document.getElementById('user-form').addEventListener('submit', submitUserForm);
}

async function loadPbxSettings() {
  const res = await fetch('/api/admin/pbx');
  if (!res.ok) return;
  const pbx = await res.json();
  document.getElementById('pbx-domain').value = pbx.domain || '';
  document.getElementById('pbx-wss').value = pbx.wssUrl || '';
  document.getElementById('pbx-port').value = pbx.sipPort || 5061;
  document.getElementById('pbx-transport').value = pbx.sipTransport || 'tls';
}

async function savePbxSettings() {
  const statusEl = document.getElementById('pbx-status');
  const res = await fetch('/api/admin/pbx', {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      domain: document.getElementById('pbx-domain').value.trim(),
      wssUrl: document.getElementById('pbx-wss').value.trim(),
      sipPort: document.getElementById('pbx-port').value.trim(),
      sipTransport: document.getElementById('pbx-transport').value,
    }),
  });
  statusEl.textContent = res.ok
    ? 'Saved. Users pick this up next time they sign in or reconnect.'
    : 'Could not save PBX settings.';
}

async function loadUsers() {
  const container = document.getElementById('users-list');
  const res = await fetch('/api/admin/users');
  if (!res.ok) return;
  const list = await res.json();

  container.innerHTML = '';
  list.forEach((user) => {
    const row = document.createElement('div');
    row.className = 'user-row';
    const badges = [
      user.role === 'admin' ? '<span class="badge admin">Admin</span>' : '',
      user.canMessage ? '<span class="badge">Texts</span>' : '',
      user.active ? '' : '<span class="badge off">Disabled</span>',
    ].join('');
    row.innerHTML = `
      <div class="user-main">
        <div class="user-name">${escapeHtml(user.displayName)} ${badges}</div>
        <div class="user-sub">${escapeHtml(user.username)} · ${
          user.extension ? `ext ${escapeHtml(user.extension)}` : 'no extension'
        }</div>
      </div>`;

    const actions = document.createElement('div');
    actions.className = 'user-actions';

    const editBtn = document.createElement('button');
    editBtn.className = 'ghost-btn small';
    editBtn.textContent = 'Edit';
    editBtn.addEventListener('click', () => openUserDialog(user));
    actions.appendChild(editBtn);

    const deleteBtn = document.createElement('button');
    deleteBtn.className = 'ghost-btn small danger';
    deleteBtn.textContent = 'Delete';
    deleteBtn.addEventListener('click', () => deleteUser(user));
    actions.appendChild(deleteBtn);

    row.appendChild(actions);
    container.appendChild(row);
  });
}

async function openUserDialog(user) {
  editingUserId = user ? user.id : null;
  document.getElementById('user-dialog-title').textContent = user ? 'Edit user' : 'Add user';
  document.getElementById('user-form-error').classList.add('hidden');

  document.getElementById('user-username').value = user ? user.username : '';
  document.getElementById('user-username').disabled = Boolean(user); // usernames are stable
  document.getElementById('user-display-name').value = user ? user.displayName : '';
  document.getElementById('user-password').value = '';
  document.getElementById('user-password-label').firstChild.textContent = user
    ? 'New password (leave blank to keep current)'
    : 'Password';
  document.getElementById('user-can-message').checked = user ? user.canMessage : false;
  document.getElementById('user-is-admin').checked = user ? user.role === 'admin' : false;
  document.getElementById('user-extension').value = user ? user.extension : '';

  // The SIP secret isn't in the list payload; fetch it only when editing.
  const sipPasswordInput = document.getElementById('user-sip-password');
  sipPasswordInput.value = '';
  if (user) {
    const res = await fetch(`/api/admin/users/${user.id}/sip`);
    if (res.ok) sipPasswordInput.value = (await res.json()).sipPassword || '';
  }

  document.getElementById('user-dialog').classList.remove('hidden');
}

function closeUserDialog() {
  document.getElementById('user-dialog').classList.add('hidden');
  editingUserId = null;
}

async function submitUserForm(e) {
  e.preventDefault();
  const errorEl = document.getElementById('user-form-error');
  errorEl.classList.add('hidden');

  const payload = {
    displayName: document.getElementById('user-display-name').value.trim(),
    role: document.getElementById('user-is-admin').checked ? 'admin' : 'user',
    canMessage: document.getElementById('user-can-message').checked,
    extension: document.getElementById('user-extension').value.trim(),
    sipPassword: document.getElementById('user-sip-password').value,
  };
  const password = document.getElementById('user-password').value;

  let res;
  if (editingUserId) {
    if (password) payload.password = password;
    res = await fetch(`/api/admin/users/${editingUserId}`, {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload),
    });
  } else {
    payload.username = document.getElementById('user-username').value.trim();
    payload.password = password;
    res = await fetch('/api/admin/users', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload),
    });
  }

  if (res.ok) {
    closeUserDialog();
    loadUsers();
  } else {
    const { error } = await res.json().catch(() => ({}));
    errorEl.textContent = error || 'Could not save user';
    errorEl.classList.remove('hidden');
  }
}

async function deleteUser(user) {
  if (!confirm(`Delete ${user.displayName}? They'll lose access immediately.`)) return;
  const res = await fetch(`/api/admin/users/${user.id}`, { method: 'DELETE' });
  if (res.ok) {
    loadUsers();
  } else {
    const { error } = await res.json().catch(() => ({}));
    alert(error || 'Could not delete user');
  }
}

/* ---------------------------------------------------------------------- */

checkSession();
