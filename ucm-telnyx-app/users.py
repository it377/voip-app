# User accounts and global PBX settings.
#
# Same flat-JSON approach as store.py (atomic writes, single process - see the
# worker note in app.py). Fine for an org's worth of staff; if this ever grows
# to hundreds of users or needs auditing, this is the first thing to move to
# SQLite.
#
# SECURITY NOTE on sip_password: unlike login passwords, the SIP secret CANNOT
# be hashed. The phone/browser has to present the real secret to the PBX to
# register, so the server must be able to hand it back to an authenticated
# user. It is therefore stored recoverably in data/accounts.json. Keep that
# file readable only by the app's user (chmod 600) and back it up as you would
# any credential store. Encrypting it at rest with a key from .env is a
# worthwhile follow-up - it would protect a leaked file, though not a fully
# compromised server.
import hashlib
import hmac
import json
import os
import secrets
import tempfile
import threading
from datetime import datetime, timezone
from pathlib import Path

DATA_DIR = Path(__file__).parent / "data"
DATA_FILE = DATA_DIR / "accounts.json"

_lock = threading.RLock()

PBKDF2_ITERATIONS = 240_000

DEFAULT_PBX = {
    "domain": "",
    "wssUrl": "",
    "sipPort": 5061,
    "sipTransport": "tls",
}


def _now():
    return datetime.now(timezone.utc).isoformat()


def _load():
    try:
        with open(DATA_FILE, "r") as f:
            data = json.load(f)
    except FileNotFoundError:
        data = {}
    except json.JSONDecodeError as err:
        print(f"Failed to read accounts store, starting empty: {err}")
        data = {}

    data.setdefault("users", [])
    pbx = dict(DEFAULT_PBX)
    pbx.update(data.get("pbx") or {})
    data["pbx"] = pbx
    return data


def _save_atomic(data):
    DATA_DIR.mkdir(parents=True, exist_ok=True)
    fd, tmp_path = tempfile.mkstemp(dir=DATA_DIR, suffix=".tmp")
    try:
        with os.fdopen(fd, "w") as f:
            json.dump(data, f, indent=2)
        os.chmod(tmp_path, 0o600)  # credentials live here - keep it private
        os.replace(tmp_path, DATA_FILE)
    finally:
        if os.path.exists(tmp_path):
            os.remove(tmp_path)


_db = _load()


# ---- Password hashing (stdlib pbkdf2, no extra dependency) ----
def hash_password(password: str) -> str:
    salt = secrets.token_bytes(16)
    dk = hashlib.pbkdf2_hmac("sha256", password.encode(), salt, PBKDF2_ITERATIONS)
    return f"pbkdf2_sha256${PBKDF2_ITERATIONS}${salt.hex()}${dk.hex()}"


def verify_password(password: str, stored: str) -> bool:
    try:
        algo, iterations, salt_hex, hash_hex = (stored or "").split("$")
        if algo != "pbkdf2_sha256":
            return False
        dk = hashlib.pbkdf2_hmac(
            "sha256", (password or "").encode(), bytes.fromhex(salt_hex), int(iterations)
        )
    except (ValueError, AttributeError):
        return False
    return hmac.compare_digest(dk.hex(), hash_hex)


# ---- Users ----
def _public(user, include_sip_secret=False):
    """The shape handed to clients - never includes the password hash."""
    out = {
        "id": user["id"],
        "username": user["username"],
        "displayName": user.get("display_name") or user["username"],
        "role": user.get("role", "user"),
        "canMessage": bool(user.get("can_message")),
        "active": bool(user.get("active", True)),
        "extension": user.get("sip_extension", ""),
        "createdAt": user.get("created_at"),
    }
    if include_sip_secret:
        out["sipPassword"] = user.get("sip_password", "")
    return out


def list_users():
    with _lock:
        return [_public(u) for u in _db["users"]]


def get_user(user_id):
    with _lock:
        for user in _db["users"]:
            if user["id"] == user_id:
                return dict(user)
    return None


def find_by_username(username):
    target = (username or "").strip().lower()
    with _lock:
        for user in _db["users"]:
            if user["username"].lower() == target:
                return dict(user)
    return None


def authenticate(username, password):
    """Returns the user dict on success, else None. Inactive users can't log in."""
    user = find_by_username(username)
    if not user or not user.get("active", True):
        return None
    if not verify_password(password, user.get("password_hash", "")):
        return None
    return user


def create_user(
    username,
    password,
    display_name="",
    role="user",
    can_message=False,
    sip_extension="",
    sip_password="",
):
    username = (username or "").strip()
    if not username or not password:
        raise ValueError("username and password are required")
    if find_by_username(username):
        raise ValueError(f"user '{username}' already exists")
    if role not in ("admin", "user"):
        raise ValueError("role must be 'admin' or 'user'")

    user = {
        "id": f"u_{secrets.token_hex(8)}",
        "username": username,
        "display_name": display_name or username,
        "password_hash": hash_password(password),
        "role": role,
        "can_message": bool(can_message),
        "active": True,
        "sip_extension": (sip_extension or "").strip(),
        "sip_password": sip_password or "",
        "created_at": _now(),
    }
    with _lock:
        _db["users"].append(user)
        _save_atomic(_db)
    return _public(user)


def update_user(user_id, **fields):
    """Partial update. Only known fields are applied; unknown keys are ignored."""
    with _lock:
        for user in _db["users"]:
            if user["id"] != user_id:
                continue

            if "display_name" in fields:
                user["display_name"] = fields["display_name"] or user["username"]
            if "role" in fields and fields["role"] in ("admin", "user"):
                user["role"] = fields["role"]
            if "can_message" in fields:
                user["can_message"] = bool(fields["can_message"])
            if "active" in fields:
                user["active"] = bool(fields["active"])
            if "sip_extension" in fields:
                user["sip_extension"] = (fields["sip_extension"] or "").strip()
            if "sip_password" in fields:
                user["sip_password"] = fields["sip_password"] or ""
            if fields.get("password"):
                user["password_hash"] = hash_password(fields["password"])

            _save_atomic(_db)
            return _public(user)
    return None


def delete_user(user_id):
    with _lock:
        before = len(_db["users"])
        _db["users"] = [u for u in _db["users"] if u["id"] != user_id]
        if len(_db["users"]) == before:
            return False
        _save_atomic(_db)
        return True


def count_active_admins(excluding_id=None):
    with _lock:
        return sum(
            1
            for u in _db["users"]
            if u.get("role") == "admin" and u.get("active", True) and u["id"] != excluding_id
        )


def has_any_users():
    with _lock:
        return len(_db["users"]) > 0


# ---- Global PBX settings (one UCM6301, shared by every user) ----
def get_pbx():
    with _lock:
        return dict(_db["pbx"])


def update_pbx(**fields):
    with _lock:
        pbx = _db["pbx"]
        if "domain" in fields:
            pbx["domain"] = (fields["domain"] or "").strip()
        if "wssUrl" in fields:
            pbx["wssUrl"] = (fields["wssUrl"] or "").strip()
        if "sipPort" in fields:
            try:
                pbx["sipPort"] = int(fields["sipPort"])
            except (TypeError, ValueError):
                pass
        if "sipTransport" in fields and fields["sipTransport"] in ("tls", "tcp", "udp"):
            pbx["sipTransport"] = fields["sipTransport"]
        _save_atomic(_db)
        return dict(pbx)


def sip_config_for(user):
    """Everything a client needs to register, merged from global PBX + this user."""
    pbx = get_pbx()
    return {
        "wssUrl": pbx["wssUrl"],
        "domain": pbx["domain"],
        "sipPort": pbx["sipPort"],
        "sipTransport": pbx["sipTransport"],
        "extension": user.get("sip_extension", ""),
        "password": user.get("sip_password", ""),
    }


def bootstrap_admin_if_empty():
    """
    First run: seed one admin so there's a way in. Credentials come from
    ADMIN_USERNAME / ADMIN_PASSWORD in .env. Returns the username if it created
    one, else None.
    """
    if has_any_users():
        return None

    username = os.environ.get("ADMIN_USERNAME", "admin").strip() or "admin"
    password = os.environ.get("ADMIN_PASSWORD", "")
    generated = False
    if not password:
        password = secrets.token_urlsafe(12)
        generated = True

    create_user(
        username=username,
        password=password,
        display_name="Administrator",
        role="admin",
        can_message=True,
    )

    if generated:
        print("=" * 68)
        print(" No users existed, so an admin account was created for you:")
        print(f"   username: {username}")
        print(f"   password: {password}")
        print(" Save this now - it is not stored anywhere in readable form.")
        print(" Set ADMIN_USERNAME/ADMIN_PASSWORD in .env to choose your own.")
        print("=" * 68)
    else:
        print(f"Created first admin account '{username}' from ADMIN_USERNAME/ADMIN_PASSWORD.")

    return username
