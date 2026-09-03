# ucm-telnyx-app

A self-hosted, single-page web app that combines:

1. **A SIP/WebRTC softphone** ([SIP.js](https://sipjs.com/) in the browser) that
   registers as an extension on your Grandstream **UCM6301** over a secure
   WebSocket (WSS), so you can make/receive calls from a tab.
2. **Telnyx SMS/MMS messaging** — a Flask backend that sends outbound texts
   via the Telnyx Messages API and receives inbound texts via a Telnyx
   webhook, pushing new messages to the browser live over WebSocket.

Users sign in with their own username and password; an admin assigns each of
them an extension on the UCM, and their softphone configures itself. The
browser then talks to the PBX directly for calls, while the server talks to
Telnyx for texts.

## 1. Local setup

Requires Python 3.10+.

```bash
python3 -m venv venv
source venv/bin/activate      # Windows: venv\Scripts\activate
pip install -r requirements.txt
cp .env.example .env
```

Edit `.env`:

| Variable | Where to get it |
|---|---|
| `SESSION_SECRET` | `python3 -c "import secrets; print(secrets.token_hex(32))"` |
| `ADMIN_USERNAME` / `ADMIN_PASSWORD` | Used **once**, on first run, to create the first admin account. Leave the password blank and a random one is generated and printed to the console. After that, users are managed in the app. |
| `TELNYX_API_KEY` | [Telnyx Portal → API Keys](https://portal.telnyx.com/#/app/api-keys) |
| `TELNYX_FROM_NUMBER` | The Telnyx number you're texting from, E.164 (`+15555550123`) |
| `TELNYX_MESSAGING_PROFILE_ID` | [Telnyx Portal → Messaging → Messaging Profiles](https://portal.telnyx.com/#/app/messaging) — the profile your number is assigned to |
| `TELNYX_PUBLIC_KEY` | Same messaging profile page, "Public Key" — used to verify inbound webhooks are really from Telnyx. Fill this in; see [Security notes](#security-notes). |

There are no SIP settings in `.env` — PBX details and each user's extension are
managed in the app's **Admin** tab and handed to clients at login (see part 2).

Start it:

```bash
python app.py
```

(`app.py` runs Flask's built-in dev server, which is fine for local use. For
anything always-on, part 4 switches to gunicorn instead.)

Open **http://localhost:3000** and sign in with the admin account created on
first run (see the console output). You'll see Phone / Messages / Settings
plus an **Admin** tab.

First thing to do there: fill in **PBX settings**, then add users and assign
each one an extension.

### If you can't sign in (forgotten admin password)

The first-run password is printed once and hashed immediately, so it can't be
looked up later. Use the account CLI instead — **stop the server first**, since
it keeps accounts in memory and would overwrite the change:

```bash
python manage.py list                 # see what accounts exist
python manage.py passwd admin         # prompts for a new password
python manage.py create-admin <name>  # add another administrator
python manage.py activate <name>      # re-enable a disabled account
```

Then start the server again and sign in. This edits `data/accounts.json`
directly, so it works even when nobody can log in — no need to delete accounts
and start over.

Sending a text won't require any tunnel — that's a normal outbound API call.
Receiving one does (part 3).

## 2. Configuring the SIP connection against your UCM6301

Users don't configure anything. An admin sets this up once in the **Admin**
tab, and every client (browser or Android) pulls its settings at login.

**Admin tab → PBX settings** (shared by everyone, one UCM6301):

- **SIP domain / PBX host** — your UCM's hostname or IP.
- **WebSocket URL** — `wss://<ucm-host>:8089/ws`, used by the browser
  softphone (WebRTC).
- **SIP port / transport** — used by the Android app, which speaks plain SIP
  rather than WebRTC. Typically `5061` + TLS, or `5060` + UDP/TCP.

**Admin tab → Users**: add a person, and assign them an extension that already
exists on your UCM plus that extension's SIP secret. That's the whole
provisioning step — they sign in with their username and password and their
softphone registers itself.

Per user you can also set:
- **Can send/read texts** — the Telnyx inbox is shared (there's one number), so
  this decides who sees it. People without it get the phone half only.
- **Administrator** — can manage users and PBX settings. The app won't let you
  remove or demote the last active admin, so you can't lock yourself out.

Accounts live in `data/accounts.json`. Login passwords are hashed (PBKDF2);
extension SIP secrets can't be hashed, because clients must present the real
secret to register with the PBX — so that file is written `chmod 600` and
should be treated as a credential store (see Security notes).

### Where these values come from on the UCM6301

You need four things:

- **WebSocket URL** — `wss://<ucm-host-or-ip>:8089/ws`. `8089` is the UCM6301's
  default WSS port. On some firmware/configs the path is `/ws`; check
  **PBX Settings → SIP Settings → TLS** on the UCM for the exact port if you've
  changed it.
- **SIP domain** — usually the same host/IP as above (no `wss://`, no port).
- **Extension** — the extension number, e.g. `1001`.
- **SIP password** — the extension's **SIP secret**, found on the UCM under
  **PBX Settings → Extension/Trunk → Extensions → (edit extension) → SIP/IAX
  Settings → Password**. This is *not* the extension's web/User Portal login
  password.

### Enabling WebRTC on the extension

On the UCM6301 web admin:

1. **PBX Settings → Extension/Trunk → Extensions**, edit the extension.
2. Under the extension's settings there is a toggle to enable it for WebRTC/
   browser-based SIP — enable it and save. (Location varies slightly by
   firmware version — search the page for "WebRTC" if you don't see it
   immediately in Basic Settings.)
3. Apply changes.

### Troubleshooting registration failures

Open the browser console (the app logs SIP errors there) and work through
this list:

1. **Wrong port / connection refused** — confirm WSS is enabled and get the
   exact port from **PBX Settings → SIP Settings → TLS** (default `8089`).
   If you changed the PBX's HTTPS port, the WSS port may have moved too.
2. **TLS certificate not trusted** — browsers refuse a WebSocket handshake to
   a host with an untrusted TLS cert, and unlike a regular page load there's
   no "click through the warning" UI for it. Two fixes:
   - Open `https://<ucm-host>:8089/` directly in the same browser first and
     accept/proceed past the certificate warning, *then* reload the app — this
     works because the browser now has a manual exception for that host:port.
   - Better long-term: install a real certificate on the UCM (it has a built-in
     Let's Encrypt integration under **PBX Settings → TLS**, or you can upload
     your own), so this isn't needed at all.
3. **WebRTC not enabled on the extension** — see above; if it's off, the PBX
   may accept the transport connection but reject the REGISTER.
4. **Firewall / NAT** — the browser needs a direct route to the PBX on the WSS
   port. If you're off the UCM's LAN, you need port-forwarding, a VPN, or to
   be on the same network — this is unrelated to the Telnyx tunnel in part 3,
   which is a completely separate, outbound-only connection.
5. **Wrong domain vs. IP** — if the UCM's cert is issued for a hostname, using
   the bare IP as the PBX host will both fail cert validation *and* may not
   match the SIP domain the PBX expects. Use the same hostname everywhere.
6. **Check the PBX's own logs** — **Maintenance → System Events** (or
   **PBX Settings → SIP Settings → SIP Debug**) will show the REGISTER
   attempt and exactly why it was rejected (auth failure vs. transport issue
   are easy to tell apart there).

If registration still fails after checking these, paste the exact browser
console error and I can narrow it down further.

## 3. Testing inbound SMS with a tunnel

Telnyx needs to reach your machine over HTTPS to deliver inbound-message
webhooks, so for local testing, tunnel port 3000:

```bash
ngrok http 3000
```

Take the `https://xxxx.ngrok-free.app` URL it gives you, then in the
[Telnyx Portal → Messaging → Messaging Profiles](https://portal.telnyx.com/#/app/messaging),
open the profile your number uses and set:

- **Inbound webhook URL**: `https://xxxx.ngrok-free.app/webhooks/telnyx`
- Leave "Failover URL" blank for now.

Send a text to your Telnyx number from your phone — it should appear live in
the app's Messages tab within a second or two. Watch the server console; it
logs a warning if `TELNYX_PUBLIC_KEY` isn't set (meaning signatures aren't
being verified yet — fine for this test, fix before going live).

Every time you restart ngrok on the free tier the URL changes, so you'll need
to update the webhook URL in the Telnyx portal again — that's expected and is
exactly why part 4 gives this a stable URL.

## 4. Deploying somewhere always-on

Once it works locally, move it to something with a stable public URL. Two
straightforward options, either works fine for a single-user app like this:

### Option A: Small VPS + Docker (recommended)

Any $5-6/mo VPS (DigitalOcean, Hetzner, Linode, etc.) with Docker installed:

```bash
git clone <your-repo> && cd ucm-telnyx-app
cp .env.example .env   # fill in real values
docker compose up -d --build
```

The image runs the app with `gunicorn` (a production WSGI server), pinned to
a single worker — see the note in `app.py` about why (the in-memory
WebSocket client set and the JSON store are per-process; more workers would
silently drop broadcasts and risk write races).

Put a reverse proxy in front for HTTPS + your domain. [Caddy](https://caddyserver.com/)
is the least fuss (automatic Let's Encrypt certs, ~5-line config) — see
`deploy/Caddyfile.example`. Point your domain's A record at the VPS, install
Caddy, drop in the Caddyfile, `systemctl reload caddy`, done.

Then in the Telnyx portal, point the messaging profile's inbound webhook at:

```
https://your-domain.example.com/webhooks/telnyx
```

That URL never changes again, unlike the ngrok one.

### Option B: VPS without Docker

Install Python 3.10+, create a venv and `pip install -r requirements.txt` in
the app directory, then use the provided `deploy/ucm-telnyx-app.service`
systemd unit (copy it into `/etc/systemd/system/`, adjust paths/user,
`systemctl enable --now`) so it restarts on boot/crash — it runs the same
single-worker gunicorn command as the Docker image. Same Caddy/reverse-proxy
step as above for HTTPS.

### Either way

- Back up `data/messages.json` periodically (it's the entire message
  history — see the storage note below).
- The SIP side needs no server-side deployment changes: it's still your
  browser talking directly to your UCM6301. If you're accessing the app itself
  from outside your LAN, make sure your browser can still reach the UCM's WSS
  port from wherever you are (VPN back into your LAN is the common answer here).

## Suggested improvements (not yet done — ask before I make any of these)

These don't change the core architecture (browser SIP.js softphone + Telnyx
webhook backend), just harden pieces of it:

- **Storage**: `data/messages.json` is a single flat file rewritten on every
  message — fine at low volume, but it'll get slower and riskier (one bad
  write) as history grows, and it's also why this app must run as a single
  process/worker (see the note in `app.py`). Python's built-in `sqlite3`
  would be a drop-in-ish swap behind the same `store.py` functions, buys you
  real querying/search, and would let you scale past one worker later.
- **Auth**: there are now real per-user accounts with hashed passwords and
  an admin role, but still no 2FA and no rate limiting on login attempts. If
  this is reachable from the public internet long-term, TOTP and a login
  throttle are the next things worth adding - or put it behind something like
  Tailscale so it isn't internet-facing at all.
- **Mobile**: the UI is responsive (see the phone-tab layout and the mobile
  breakpoint in `styles.css`), but browsers restrict background mic/audio
  access — a backgrounded mobile tab won't reliably ring for incoming calls.
  If that matters day-to-day, it's worth revisiting (e.g. push notifications
  for incoming calls, or accepting that the softphone is a desktop-primarily
  feature and mobile is for texting).
- **Webhook signature verification**: implemented and on by default once
  `TELNYX_PUBLIC_KEY` is set (see `telnyx_client.py`) — just make sure it's
  actually set before exposing the webhook publicly.

## Security notes

- `/webhooks/telnyx` is intentionally reachable without the app password
  (Telnyx can't provide it) — it's protected instead by verifying Telnyx's
  ed25519 webhook signature. Set `TELNYX_PUBLIC_KEY` before deploying
  publicly, or anyone who finds the URL can inject fake "received" messages.
- Login passwords are hashed with PBKDF2-SHA256. Extension SIP secrets are
  **not** hashed and cannot be: a client has to present the real secret to
  register with the PBX, so the server must hand it back. They live in
  `data/accounts.json`, which is written with `chmod 600` - back it up and
  guard it like any credential store. Encrypting it at rest with a key from
  `.env` is a sensible follow-up.
- Each client only ever receives its own extension's secret; the admin user
  list never includes password hashes or other people's SIP secrets.
- Don't commit your real `.env` — `.gitignore` already excludes it.
