import hmac
import hashlib
import os
import time
from functools import wraps

from flask import g, jsonify, request

import users

SESSION_SECRET = os.environ.get("SESSION_SECRET")
COOKIE_NAME = "ucm_session"
MAX_AGE_SECONDS = 30 * 24 * 60 * 60  # 30 days

if not SESSION_SECRET:
    raise RuntimeError("SESSION_SECRET must be set in .env")


def _mac(value: str) -> str:
    return hmac.new(SESSION_SECRET.encode(), value.encode(), hashlib.sha256).hexdigest()


def _sign(value: str) -> str:
    return f"{value}.{_mac(value)}"


def _unsign(token):
    """Returns the signed payload if the signature is valid and fresh, else None."""
    if not token or "." not in token:
        return None
    value, _, mac = token.rpartition(".")
    if not hmac.compare_digest(mac, _mac(value)):
        return None
    # payload is "<expires_ms>:<user_id>"
    expires_str, _, user_id = value.partition(":")
    if not user_id:
        return None
    try:
        if float(expires_str) <= time.time() * 1000:
            return None
    except ValueError:
        return None
    return user_id


def current_user():
    """
    The logged-in user for this request, or None. Cached on flask.g so repeated
    checks in one request don't re-read the store. Re-reads the user each
    request on purpose: deactivating or demoting someone takes effect on their
    next request rather than whenever their cookie happens to expire.
    """
    if "current_user" in g:
        return g.current_user

    user = None
    user_id = _unsign(request.cookies.get(COOKIE_NAME))
    if user_id:
        candidate = users.get_user(user_id)
        if candidate and candidate.get("active", True):
            user = candidate

    g.current_user = user
    return user


def issue_cookie(resp, user):
    expires_at_ms = int((time.time() + MAX_AGE_SECONDS) * 1000)
    resp.set_cookie(
        COOKIE_NAME,
        _sign(f"{expires_at_ms}:{user['id']}"),
        httponly=True,
        samesite="Lax",
        secure=os.environ.get("FLASK_ENV") == "production",
        max_age=MAX_AGE_SECONDS,
    )


def clear_cookie(resp):
    resp.delete_cookie(COOKIE_NAME)


def is_authenticated_request(_req=None) -> bool:
    return current_user() is not None


def require_auth(fn):
    @wraps(fn)
    def wrapper(*args, **kwargs):
        if current_user() is None:
            return jsonify({"error": "unauthorized"}), 401
        return fn(*args, **kwargs)

    return wrapper


def require_admin(fn):
    @wraps(fn)
    def wrapper(*args, **kwargs):
        user = current_user()
        if user is None:
            return jsonify({"error": "unauthorized"}), 401
        if user.get("role") != "admin":
            return jsonify({"error": "admin only"}), 403
        return fn(*args, **kwargs)

    return wrapper


def require_messaging(fn):
    """Texting is opt-in per user - the phone half stays available to everyone."""

    @wraps(fn)
    def wrapper(*args, **kwargs):
        user = current_user()
        if user is None:
            return jsonify({"error": "unauthorized"}), 401
        if not user.get("can_message"):
            return jsonify({"error": "messaging not enabled for this account"}), 403
        return fn(*args, **kwargs)

    return wrapper
