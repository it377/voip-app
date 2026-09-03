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
import users

PORT = int(os.environ.get("PORT", 3000))
PUBLIC_DIR = Path(__file__).parent / "public"

app = Flask(__name__, static_folder=None)
sock = Sock(app)

# First run with an empty store seeds an admin account so there's a way in.
users.bootstrap_admin_if_empty()

# ---- Live push to the browser over WebSocket ----
# NOTE: this in-memory client set (and the JSON file store in store.py) are
# only shared within a single process. Run this app with exactly one worker
# (see README) - adding more workers would silently drop broadcasts to
# clients connected to a different worker.
#
# Each entry is {"ws": <socket>, "can_message": bool} - the permission is
# captured at connect time so message pushes only reach users allowed to see
# the shared inbox.
clients = []
clients_lock = threading.Lock()


def broadcast(payload, messaging_only=False):
    data = json.dumps(payload)
    with clients_lock:
        alive = []
        for client in clients:
            if messaging_only and not client["can_message"]:
                alive.append(client)  # still connected, just not a recipient
                continue
            try:
                client["ws"].send(data)
                alive.append(client)
            except Exception:
                pass  # dropped below by not being re-added
        clients[:] = alive


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
    user = users.authenticate(body.get("username"), body.get("password"))
    if not user:
        # Deliberately vague: don't reveal whether the username exists.
        return jsonify({"error": "wrong username or password"}), 401
    resp = jsonify({"ok": True, "user": _me_payload(user)})
    auth.issue_cookie(resp, user)
    return resp


@app.post("/api/logout")
def logout():
    resp = jsonify({"ok": True})
    auth.clear_cookie(resp)
    return resp


@app.get("/api/session")
def session_status():
    user = auth.current_user()
    if not user:
        return jsonify({"authenticated": False})
    return jsonify({"authenticated": True, "user": _me_payload(user)})


def _me_payload(user):
    return {
        "id": user["id"],
        "username": user["username"],
        "displayName": user.get("display_name") or user["username"],
        "role": user.get("role", "user"),
        "canMessage": bool(user.get("can_message")),
        "isAdmin": user.get("role") == "admin",
    }


@app.get("/api/me")
@auth.require_auth
def me():
    return jsonify(_me_payload(auth.current_user()))


# The logged-in user's SIP registration details: global PBX settings merged
# with the extension the admin assigned them. This is what lets a client
# register with nothing typed in by hand.
@app.get("/api/sip-config")
@auth.require_auth
def sip_config():
    return jsonify(users.sip_config_for(auth.current_user()))


# ---- Admin: user management ----
@app.get("/api/admin/users")
@auth.require_admin
def admin_list_users():
    return jsonify(users.list_users())


@app.post("/api/admin/users")
@auth.require_admin
def admin_create_user():
    body = request.get_json(silent=True) or {}
    try:
        created = users.create_user(
            username=body.get("username"),
            password=body.get("password"),
            display_name=body.get("displayName", ""),
            role=body.get("role", "user"),
            can_message=body.get("canMessage", False),
            sip_extension=body.get("extension", ""),
            sip_password=body.get("sipPassword", ""),
        )
    except ValueError as err:
        return jsonify({"error": str(err)}), 400
    return jsonify(created), 201


@app.patch("/api/admin/users/<user_id>")
@auth.require_admin
def admin_update_user(user_id):
    body = request.get_json(silent=True) or {}
    target = users.get_user(user_id)
    if not target:
        return jsonify({"error": "no such user"}), 404

    # Don't let the last active admin lock everyone out by demoting or
    # deactivating themselves.
    demoting = body.get("role") == "user" and target.get("role") == "admin"
    deactivating = body.get("active") is False and target.get("active", True)
    if (demoting or deactivating) and users.count_active_admins(excluding_id=user_id) == 0:
        return jsonify({"error": "this is the last active admin"}), 400

    fields = {}
    for api_name, store_name in (
        ("displayName", "display_name"),
        ("role", "role"),
        ("canMessage", "can_message"),
        ("active", "active"),
        ("extension", "sip_extension"),
        ("sipPassword", "sip_password"),
        ("password", "password"),
    ):
        if api_name in body:
            fields[store_name] = body[api_name]

    updated = users.update_user(user_id, **fields)
    if not updated:
        return jsonify({"error": "no such user"}), 404
    return jsonify(updated)


@app.delete("/api/admin/users/<user_id>")
@auth.require_admin
def admin_delete_user(user_id):
    if user_id == auth.current_user()["id"]:
        return jsonify({"error": "you can't delete your own account"}), 400
    if not users.get_user(user_id):
        return jsonify({"error": "no such user"}), 404
    if users.count_active_admins(excluding_id=user_id) == 0:
        return jsonify({"error": "this is the last active admin"}), 400
    users.delete_user(user_id)
    return jsonify({"ok": True})


# Admins need the SIP secret back to show/edit it in the panel.
@app.get("/api/admin/users/<user_id>/sip")
@auth.require_admin
def admin_user_sip(user_id):
    target = users.get_user(user_id)
    if not target:
        return jsonify({"error": "no such user"}), 404
    return jsonify(
        {"extension": target.get("sip_extension", ""), "sipPassword": target.get("sip_password", "")}
    )


# ---- Admin: global PBX settings (one UCM6301 for everyone) ----
@app.get("/api/admin/pbx")
@auth.require_admin
def admin_get_pbx():
    return jsonify(users.get_pbx())


@app.put("/api/admin/pbx")
@auth.require_admin
def admin_update_pbx():
    body = request.get_json(silent=True) or {}
    return jsonify(users.update_pbx(**body))


# ---- Messaging ----
# One shared inbox (there's a single Telnyx number), visible only to users the
# admin has enabled messaging for.
@app.get("/api/messages")
@auth.require_messaging
def list_messages():
    return jsonify(store.list_conversations())


@app.get("/api/messages/<number>")
@auth.require_messaging
def get_messages(number):
    return jsonify(store.get_conversation(number))


@app.post("/api/messages/send")
@auth.require_messaging
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
        broadcast({"type": "message", "message": message}, messaging_only=True)
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
        broadcast({"type": "message", "message": message}, messaging_only=True)

    # Telnyx just needs a 2xx quickly; ignore other event types (delivery
    # receipts etc.) for now.
    return jsonify({"ok": True})


@app.get("/health")
def health():
    return jsonify({"ok": True})


@sock.route("/ws")
def ws_handler(ws):
    # before_request already rejected unauthenticated upgrades, so there is a
    # user here. Capture their messaging permission for the life of the socket;
    # revoking it takes effect on their next connect.
    user = auth.current_user()
    client = {"ws": ws, "can_message": bool(user and user.get("can_message"))}
    with clients_lock:
        clients.append(client)
    try:
        while True:
            ws.receive()  # blocks; just used to detect disconnect
    except Exception:
        pass
    finally:
        with clients_lock:
            if client in clients:
                clients.remove(client)


# Static assets last, so /api and /webhooks above take priority.
@app.get("/")
@app.get("/<path:filename>")
def static_files(filename="index.html"):
    return send_from_directory(PUBLIC_DIR, filename)


if __name__ == "__main__":
    app.run(host="0.0.0.0", port=PORT, threaded=True)
