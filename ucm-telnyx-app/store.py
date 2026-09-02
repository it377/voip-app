# Flat-file JSON message store, grouped by conversation (the other party's
# phone number). Good enough for one phone number's worth of texting; if
# history grows into the tens of thousands of messages or you need search,
# swap this for SQLite - the read/write functions below are small enough to
# reimplement against a real DB without touching app.py.
import json
import os
import tempfile
import threading
import time
import uuid
from datetime import datetime, timezone
from pathlib import Path

DATA_DIR = Path(__file__).parent / "data"
DATA_FILE = DATA_DIR / "messages.json"

_lock = threading.Lock()


def _load():
    try:
        with open(DATA_FILE, "r") as f:
            return json.load(f)
    except FileNotFoundError:
        return {}
    except json.JSONDecodeError as err:
        print(f"Failed to read message store, starting empty: {err}")
        return {}


def _save_atomic(data):
    DATA_DIR.mkdir(parents=True, exist_ok=True)
    fd, tmp_path = tempfile.mkstemp(dir=DATA_DIR, suffix=".tmp")
    try:
        with os.fdopen(fd, "w") as f:
            json.dump(data, f, indent=2)
        os.replace(tmp_path, DATA_FILE)  # atomic on the same filesystem
    finally:
        if os.path.exists(tmp_path):
            os.remove(tmp_path)


_db = _load()


def _normalize(number):
    return str(number or "").strip()


def add_message(direction, to=None, from_=None, text=None, media=None, telnyx_id=None, status="sent"):
    with _lock:
        other = to if direction == "outbound" else from_
        key = _normalize(other)
        if key not in _db:
            _db[key] = {"number": key, "messages": []}

        message = {
            "id": telnyx_id or f"local-{int(time.time() * 1000)}-{uuid.uuid4().hex[:6]}",
            "direction": direction,
            "to": to,
            "from": from_,
            "text": text,
            "media": media or [],
            "status": status,
            "timestamp": datetime.now(timezone.utc).isoformat(),
        }
        _db[key]["messages"].append(message)
        _save_atomic(_db)
        return message


def list_conversations():
    conversations = [
        {
            "number": c["number"],
            "lastMessage": c["messages"][-1] if c["messages"] else None,
            "count": len(c["messages"]),
        }
        for c in _db.values()
    ]
    conversations.sort(key=lambda c: c["lastMessage"]["timestamp"] if c["lastMessage"] else "", reverse=True)
    return conversations


def get_conversation(number):
    key = _normalize(number)
    return _db.get(key, {"number": key, "messages": []})
