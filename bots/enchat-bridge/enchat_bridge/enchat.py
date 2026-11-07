"""Independent Enchat wire-protocol client.

Only the public network formats are used:
  * room key  = Fernet(PBKDF2-HMAC-SHA256(secret, salt=SHA256(topic), 480000))
  * message   = "V2|<unix_ts>|<nick>|<content>" encrypted with the room key
  * transport = ntfy HTTP/JSON (POST <relay>/<topic>, GET <relay>/<topic>/json)
  * public directory = encrypted ROOM2 records in a separate ntfy topic

No code from the Enchat project is copied. See ../README.md for licensing notes.
"""

from __future__ import annotations

import base64
import hashlib
import json
import time
from dataclasses import dataclass
from typing import Iterator

import requests
from cryptography.fernet import Fernet, InvalidToken
from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.kdf.pbkdf2 import PBKDF2HMAC

WIRE_PREFIX = "V2|"
MAX_TEXT = 500
MAX_NICK = 40
ASCII_HEADERS = {"Accept": "application/x-ndjson", "Cache-Control": "no-cache"}


def derive_room_key(secret: str, topic: str) -> bytes:
    """Derive the same Fernet key Enchat derives from a room secret."""
    salt = hashlib.sha256(topic.encode("utf-8")).digest()
    kdf = PBKDF2HMAC(
        algorithm=hashes.SHA256(),
        length=32,
        salt=salt,
        iterations=480_000,
    )
    return base64.urlsafe_b64encode(kdf.derive(secret.encode("utf-8")))


def room_cipher(secret: str, topic: str) -> Fernet:
    return Fernet(derive_room_key(secret, topic))


def _clean_text(text: str, limit: int = MAX_TEXT) -> str:
    text = " ".join(text.replace("\r", " ").replace("\n", " ").split())
    return text[:limit]


def encode_message(
    nick: str,
    content: str,
    cipher: Fernet,
    timestamp: int | None = None,
) -> str:
    ts = int(time.time()) if timestamp is None else int(timestamp)
    plaintext = f"{WIRE_PREFIX}{ts}|{_clean_text(nick, MAX_NICK)}|{content}"
    return cipher.encrypt(plaintext.encode("utf-8")).decode("ascii")


def decode_message(token: str, cipher: Fernet) -> tuple[int, str, str] | None:
    try:
        plaintext = cipher.decrypt(token.encode("ascii")).decode("utf-8")
    except (InvalidToken, ValueError, UnicodeDecodeError):
        return None
    if not plaintext.startswith(WIRE_PREFIX):
        return None
    parts = plaintext[len(WIRE_PREFIX):].split("|", 2)
    if len(parts) != 3:
        return None
    try:
        ts = int(parts[0])
    except ValueError:
        return None
    return ts, parts[1], parts[2]


def encode_body(kind: str, token: str) -> str:
    if kind not in {"MSG", "SYS", "FILEMETA", "FILECHUNK"}:
        raise ValueError(f"unsupported Enchat body kind: {kind}")
    return f"{kind}:{token}"


def split_body(body: str) -> tuple[str, str] | None:
    kind, sep, token = body.partition(":")
    if not sep or kind not in {"MSG", "SYS", "FILEMETA", "FILECHUNK"}:
        return None
    return kind, token


def parse_event(raw: str) -> tuple[str, str] | None:
    try:
        event = json.loads(raw)
    except (TypeError, json.JSONDecodeError):
        return None
    if not isinstance(event, dict) or event.get("event") != "message":
        return None
    message = event.get("message")
    if not isinstance(message, str):
        return None
    return str(event.get("id", "")), message


def subscription_url(relay: str, topic: str, last_event_id: str | None = None) -> str:
    base = f"{relay.rstrip('/')}/{topic}/json"
    return f"{base}?since={last_event_id}" if last_event_id else base


def publish(relay: str, topic: str, body: str, session: requests.Session | None = None) -> bool:
    transport = session or requests
    try:
        response = transport.post(f"{relay.rstrip('/')}/{topic}", data=body, timeout=15)
        return response.status_code == 200
    except requests.RequestException:
        return False


def iter_events(
    relay: str,
    topic: str,
    stop,
    session: requests.Session | None = None,
    since: str | None = None,
) -> Iterator[tuple[str, str]]:
    """Yield (event_id, body) with reconnect handled by the caller."""
    transport = session or requests
    url = subscription_url(relay, topic, since)
    with transport.get(url, stream=True, timeout=(5, 90), headers=ASCII_HEADERS) as response:
        response.raise_for_status()
        for raw in response.iter_lines(decode_unicode=True, chunk_size=1):
            if stop.is_set():
                return
            if not raw:
                continue
            parsed = parse_event(raw)
            if parsed:
                yield parsed


# --------------------------------------------------------------------------
# Public directory (optional, credentials supplied by the operator)
# --------------------------------------------------------------------------

DIRECTORY_PREFIX = "ROOM2:"


@dataclass(frozen=True)
class PublicRoom:
    room_id: str
    name: str
    topic: str
    secret: str
    expires_at: int = 0


def decode_announcement(
    message: str,
    directory_key: str,
    issued_at: int | None = None,
    now: int | None = None,
) -> PublicRoom | None:
    if not message.startswith(DIRECTORY_PREFIX):
        return None
    try:
        plain = Fernet(directory_key.encode("ascii")).decrypt(
            message[len(DIRECTORY_PREFIX):].encode("ascii")
        )
        record = json.loads(plain)
    except (InvalidToken, ValueError, TypeError, json.JSONDecodeError):
        return None
    if not isinstance(record, dict) or record.get("v") != 2:
        return None
    required = ("id", "name", "topic", "secret", "lease")
    if any(key not in record for key in required):
        return None
    current = int(time.time()) if now is None else int(now)
    issued = current if issued_at is None else int(issued_at)
    expires_at = issued + int(record["lease"])
    if expires_at <= current:
        return None
    return PublicRoom(
        room_id=str(record["id"]),
        name=str(record["name"]),
        topic=str(record["topic"]),
        secret=str(record["secret"]),
        expires_at=expires_at,
    )


def list_public_rooms(
    relay: str,
    directory_topic: str,
    directory_key: str,
    session: requests.Session | None = None,
    limit: int = 50,
) -> list[PublicRoom]:
    transport = session or requests
    response = transport.get(
        f"{relay.rstrip('/')}/{directory_topic}/json",
        params={"poll": "1", "since": "3m"},
        headers={"Accept": "application/x-ndjson"},
        timeout=8,
    )
    response.raise_for_status()
    latest: dict[str, PublicRoom] = {}
    now = int(time.time())
    for raw in response.iter_lines(decode_unicode=True):
        if not raw:
            continue
        try:
            event = json.loads(raw)
        except (TypeError, json.JSONDecodeError):
            continue
        if not isinstance(event, dict) or event.get("event") != "message":
            continue
        issued_at = event.get("time")
        if not isinstance(issued_at, int) or isinstance(issued_at, bool):
            continue
        room = decode_announcement(event.get("message", ""), directory_key, issued_at, now)
        if room and (room.room_id not in latest or room.expires_at > latest[room.room_id].expires_at):
            latest[room.room_id] = room
    return sorted(latest.values(), key=lambda item: item.name.casefold())[:limit]


# --------------------------------------------------------------------------
# Invite links (share service; key lives in the URL fragment)
# --------------------------------------------------------------------------

def parse_invite(url: str) -> tuple[str, str] | None:
    try:
        _, fragment = url.split("/join#", 1)
        session_id, key = fragment.split(":", 1)
        return session_id, key
    except ValueError:
        return None


def resolve_invite(url: str, session: requests.Session | None = None) -> tuple[str, str, str] | None:
    parsed = parse_invite(url)
    if not parsed:
        return None
    session_id, key = parsed
    base = url.split("/join#", 1)[0].rstrip("/")
    transport = session or requests
    try:
        response = transport.get(f"{base}/get/{session_id}", timeout=8)
        response.raise_for_status()
        payload = response.json().get("payload")
        if not payload:
            return None
        fernet = Fernet(base64.urlsafe_b64decode(key))
        plaintext = fernet.decrypt(base64.urlsafe_b64decode(payload)).decode("utf-8")
        room, relay, secret = plaintext.split("|", 2)
        return room, relay, secret
    except (requests.RequestException, ValueError, InvalidToken, KeyError):
        return None
