import hmac
import hashlib
import os
import time
from functools import wraps

from flask import jsonify, request

APP_PASSWORD = os.environ.get("APP_PASSWORD")
SESSION_SECRET = os.environ.get("SESSION_SECRET")
COOKIE_NAME = "ucm_session"
MAX_AGE_SECONDS = 30 * 24 * 60 * 60  # 30 days

if not APP_PASSWORD or not SESSION_SECRET:
    raise RuntimeError("APP_PASSWORD and SESSION_SECRET must be set in .env")


def _mac(value: str) -> str:
    return hmac.new(SESSION_SECRET.encode(), value.encode(), hashlib.sha256).hexdigest()


def _sign(value: str) -> str:
    return f"{value}.{_mac(value)}"


def _verify(token) -> bool:
    if not token or "." not in token:
        return False
    value, _, mac = token.rpartition(".")
    if not hmac.compare_digest(mac, _mac(value)):
        return False
    try:
        return float(value) > time.time() * 1000
    except ValueError:
        return False


def check_password(candidate) -> bool:
    return hmac.compare_digest((candidate or "").encode(), APP_PASSWORD.encode())


def issue_cookie(resp):
    expires_at_ms = int((time.time() + MAX_AGE_SECONDS) * 1000)
    resp.set_cookie(
        COOKIE_NAME,
        _sign(str(expires_at_ms)),
        httponly=True,
        samesite="Lax",
        secure=os.environ.get("FLASK_ENV") == "production",
        max_age=MAX_AGE_SECONDS,
    )


def clear_cookie(resp):
    resp.delete_cookie(COOKIE_NAME)


def is_authenticated_request(req) -> bool:
    return _verify(req.cookies.get(COOKIE_NAME))


def require_auth(fn):
    @wraps(fn)
    def wrapper(*args, **kwargs):
        if not is_authenticated_request(request):
            return jsonify({"error": "unauthorized"}), 401
        return fn(*args, **kwargs)

    return wrapper
