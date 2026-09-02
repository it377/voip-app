import base64
import os

import requests
from nacl.exceptions import BadSignatureError
from nacl.signing import VerifyKey

TELNYX_API_KEY = os.environ.get("TELNYX_API_KEY")
TELNYX_FROM_NUMBER = os.environ.get("TELNYX_FROM_NUMBER")
TELNYX_MESSAGING_PROFILE_ID = os.environ.get("TELNYX_MESSAGING_PROFILE_ID")
TELNYX_PUBLIC_KEY = os.environ.get("TELNYX_PUBLIC_KEY")


def send_message(to, text):
    if not TELNYX_API_KEY or not TELNYX_FROM_NUMBER:
        raise RuntimeError("TELNYX_API_KEY / TELNYX_FROM_NUMBER not configured in .env")

    payload = {"from": TELNYX_FROM_NUMBER, "to": to, "text": text}
    if TELNYX_MESSAGING_PROFILE_ID:
        payload["messaging_profile_id"] = TELNYX_MESSAGING_PROFILE_ID

    resp = requests.post(
        "https://api.telnyx.com/v2/messages",
        headers={"Authorization": f"Bearer {TELNYX_API_KEY}", "Content-Type": "application/json"},
        json=payload,
        timeout=15,
    )

    try:
        body = resp.json()
    except ValueError:
        body = {}

    if not resp.ok:
        errors = body.get("errors") or [{}]
        detail = errors[0].get("detail", resp.reason)
        raise RuntimeError(f"Telnyx send failed ({resp.status_code}): {detail}")

    return body.get("data")


# Telnyx signs webhooks with ed25519. Headers: telnyx-signature-ed25519 (base64)
# and telnyx-timestamp (unix seconds). The signed payload is `${timestamp}|${rawBody}`.
# Verifying this stops randoms on the internet from POSTing fake "inbound texts"
# to your open webhook endpoint.
def verify_webhook_signature(raw_body: bytes, signature_header, timestamp_header):
    if not TELNYX_PUBLIC_KEY:
        return {"valid": True, "skipped": True}  # not configured - see README
    if not signature_header or not timestamp_header:
        return {"valid": False, "skipped": False}

    try:
        signed_payload = f"{timestamp_header}|".encode("utf-8") + raw_body
        signature = base64.b64decode(signature_header)
        public_key = base64.b64decode(TELNYX_PUBLIC_KEY)
        VerifyKey(public_key).verify(signed_payload, signature)
        return {"valid": True, "skipped": False}
    except BadSignatureError:
        return {"valid": False, "skipped": False}
    except Exception as err:  # malformed headers/keys, etc.
        return {"valid": False, "skipped": False, "error": str(err)}
