from dotenv import load_dotenv

load_dotenv()  # must run before the auth/telnyx_client modules read os.environ

import json
import os
import threading
from pathlib import Path

from flask import Flask, jsonify, request, send_from_directory
from flask_sock import Sock

import auth
import store
import telnyx_client

PORT = int(os.environ.get("PORT", 3000))
PUBLIC_DIR = Path(__file__).parent / "public"

app = Flask(__name__, static_folder=None)
sock = Sock(app)

# ---- Live push to the browser over WebSocket ----
# NOTE: this in-memory client set (and the JSON file store in store.py) are
# only shared within a single process. Run this app with exactly one worker
# (see README) - adding more workers would silently drop broadcasts to
# clients connected to a different worker.
clients = set()
clients_lock = threading.Lock()


def broadcast(payload):
    data = json.dumps(payload)
    with clients_lock:
        dead = set()
        for ws in clients:
            try:
                ws.send(data)
            except Exception:
                dead.add(ws)
        clients.difference_update(dead)


# flask-sock performs the WebSocket handshake (sends the 101 response) as
# soon as its view function is entered - by then it's too late for a check
# inside that view to refuse the upgrade. before_request runs earlier, so
# rejecting here stops an unauthenticated client before the handshake.
@app.before_request
def reject_unauthenticated_ws():
    if request.path == "/ws" and not auth.is_authenticated_request(request):
        return jsonify({"error": "unauthorized"}), 401


# ---- Auth ----
@app.post("/api/login")
def login():
    body = request.get_json(silent=True) or {}
    if not auth.check_password(body.get("password")):
        return jsonify({"error": "wrong password"}), 401
    resp = jsonify({"ok": True})
    auth.issue_cookie(resp)
    return resp


@app.post("/api/logout")
def logout():
    resp = jsonify({"ok": True})
    auth.clear_cookie(resp)
    return resp


@app.get("/api/session")
def session_status():
    return jsonify({"authenticated": auth.is_authenticated_request(request)})


# Client-side SIP defaults, handed to the browser after login so the
# softphone can pre-fill its settings form. None of this is a secret held by
# the server that the browser doesn't already need.
@app.get("/api/sip-config")
@auth.require_auth
def sip_config():
    return jsonify(
        {
            "wssUrl": os.environ.get("SIP_WSS_URL", ""),
            "domain": os.environ.get("SIP_DOMAIN", ""),
            "extension": os.environ.get("SIP_EXTENSION", ""),
            "password": os.environ.get("SIP_PASSWORD", ""),
        }
    )


# ---- Messaging ----
@app.get("/api/messages")
@auth.require_auth
def list_messages():
    return jsonify(store.list_conversations())


@app.get("/api/messages/<number>")
@auth.require_auth
def get_messages(number):
    return jsonify(store.get_conversation(number))


@app.post("/api/messages/send")
@auth.require_auth
def send_message_route():
    body = request.get_json(silent=True) or {}
    to = body.get("to")
    text = body.get("text")
    if not to or not text:
        return jsonify({"error": "to and text are required"}), 400

    try:
        result = telnyx_client.send_message(to, text)
        message = store.add_message(
            direction="outbound",
            to=to,
            from_=os.environ.get("TELNYX_FROM_NUMBER"),
            text=text,
            telnyx_id=(result or {}).get("id"),
            status="sent",
        )
        broadcast({"type": "message", "message": message})
        return jsonify({"ok": True, "message": message})
    except Exception as err:
        app.logger.error("Send failed: %s", err)
        return jsonify({"error": str(err)}), 502


# ---- Telnyx inbound webhook (no shared-password auth - Telnyx can't send it;
# protected instead by the ed25519 signature check) ----
@app.post("/webhooks/telnyx")
def telnyx_webhook():
    signature_header = request.headers.get("telnyx-signature-ed25519")
    timestamp_header = request.headers.get("telnyx-timestamp")
    result = telnyx_client.verify_webhook_signature(request.get_data(), signature_header, timestamp_header)

    if not result["valid"]:
        app.logger.warning("Rejected webhook with invalid signature")
        return jsonify({"error": "invalid signature"}), 401
    if result.get("skipped"):
        app.logger.warning("TELNYX_PUBLIC_KEY not set - webhook signature NOT verified. See README.")

    body = request.get_json(silent=True) or {}
    event = body.get("data") or {}
    if event.get("event_type") == "message.received":
        payload = event.get("payload") or {}
        to_list = payload.get("to") or [{}]
        message = store.add_message(
            direction="inbound",
            to=to_list[0].get("phone_number"),
            from_=(payload.get("from") or {}).get("phone_number"),
            text=payload.get("text"),
            media=payload.get("media") or [],
            telnyx_id=payload.get("id"),
            status="received",
        )
        broadcast({"type": "message", "message": message})

    # Telnyx just needs a 2xx quickly; ignore other event types (delivery
    # receipts etc.) for now.
    return jsonify({"ok": True})


@app.get("/health")
def health():
    return jsonify({"ok": True})


@sock.route("/ws")
def ws_handler(ws):
    with clients_lock:
        clients.add(ws)
    try:
        while True:
            ws.receive()  # blocks; just used to detect disconnect
    except Exception:
        pass
    finally:
        with clients_lock:
            clients.discard(ws)


# Static assets last, so /api and /webhooks above take priority.
@app.get("/")
@app.get("/<path:filename>")
def static_files(filename="index.html"):
    return send_from_directory(PUBLIC_DIR, filename)


if __name__ == "__main__":
    app.run(host="0.0.0.0", port=PORT, threaded=True)
