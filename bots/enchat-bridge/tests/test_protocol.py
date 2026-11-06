"""Protocol tests. No network access required."""

from __future__ import annotations

import pytest
from cryptography.fernet import Fernet

from enchat_bridge import enchat


def test_room_key_roundtrip_and_wrong_key():
    cipher = enchat.room_cipher("secret-passphrase", "enchat-public-abc")
    token = enchat.encode_message("alice", "hello|with a pipe", cipher, timestamp=123)
    assert enchat.decode_message(token, cipher) == (123, "alice", "hello|with a pipe")
    other = enchat.room_cipher("another-secret", "enchat-public-abc")
    assert enchat.decode_message(token, other) is None


def test_derive_key_is_stable_and_32_bytes():
    first = enchat.derive_room_key("secret", "topic")
    second = enchat.derive_room_key("secret", "topic")
    assert first == second
    assert len(Fernet(first).decrypt(Fernet(first).encrypt(b"x"))) == 1
    assert enchat.derive_room_key("secret", "other") != first


def test_body_format():
    token = "abc"
    assert enchat.encode_body("MSG", token) == "MSG:abc"
    assert enchat.split_body("MSG:abc") == ("MSG", "abc")
    assert enchat.split_body("BAD:abc") is None
    assert enchat.split_body("no-separator") is None


def test_parse_ntfy_event():
    raw = '{"id":"abc","event":"message","message":"MSG:token"}'
    assert enchat.parse_event(raw) == ("abc", "MSG:token")
    assert enchat.parse_event('{"event":"keepalive"}') is None
    assert enchat.parse_event("not json") is None


def test_subscription_url():
    assert enchat.subscription_url("https://relay/", "topic") == "https://relay/topic/json"
    assert (
        enchat.subscription_url("https://relay", "topic", "evt")
        == "https://relay/topic/json?since=evt"
    )


def test_text_is_truncated_and_newlines_removed():
    assert enchat._clean_text("a\nb", 10) == "a b"
    assert len(enchat._clean_text("x" * 100, 10)) == 10


def test_invite_parse():
    assert enchat.parse_invite("https://share.enchat.io/join#abc:key") == ("abc", "key")
    assert enchat.parse_invite("https://bad/url") is None


def test_directory_decode_roundtrip():
    key = Fernet.generate_key().decode("ascii")
    record = {
        "v": 2,
        "id": "abcdefghijkl",
        "name": "Room",
        "topic": "enchat-public-abcdefghijklmnopqrstuv",
        "secret": "A" * 43 + "=",
        "lease": 105,
    }
    message = "ROOM2:" + Fernet(key.encode()).encrypt(
        __import__("json").dumps(record).encode()
    ).decode()
    room = enchat.decode_announcement(message, key, issued_at=1000, now=1000)
    assert room is not None
    assert room.name == "Room"
    assert enchat.decode_announcement(message, key, issued_at=1000, now=2000) is None
