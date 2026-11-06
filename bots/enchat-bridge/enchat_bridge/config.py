"""Configuration from environment variables."""

from __future__ import annotations

import os
from dataclasses import dataclass


@dataclass(frozen=True)
class Config:
    tagasay_api: str
    tagasay_bot_token: str
    enchat_relay: str
    enchat_nick: str
    directory_topic: str = ""
    directory_key: str = ""
    tor_proxy: str = ""
    poll_timeout: int = 30
    max_text: int = 500

    @staticmethod
    def from_env() -> "Config":
        def required(name: str) -> str:
            value = os.environ.get(name, "").strip()
            if not value:
                raise SystemExit(f"Environment variable {name} is required")
            return value

        return Config(
            tagasay_api=required("TAGASAY_API").rstrip("/"),
            tagasay_bot_token=required("TAGASAY_BOT_TOKEN"),
            enchat_relay=required("ENCHAT_RELAY").rstrip("/"),
            enchat_nick=required("ENCHAT_NICK"),
            directory_topic=os.environ.get("ENCHAT_DIRECTORY_TOPIC", "").strip(),
            directory_key=os.environ.get("ENCHAT_DIRECTORY_KEY", "").strip(),
            tor_proxy=os.environ.get("TOR_PROXY", "").strip(),
        )
