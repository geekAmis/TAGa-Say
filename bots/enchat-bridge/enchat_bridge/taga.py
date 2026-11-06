"""Minimal TAGa-Say Bot API client (long polling + sendMessage)."""

from __future__ import annotations

import logging
from typing import Any

import requests

log = logging.getLogger(__name__)


class BotApiError(RuntimeError):
    def __init__(self, message: str, status: int = 0):
        super().__init__(message)
        self.status = status


class BotApi:
    def __init__(self, base_url: str, token: str, timeout: int = 40):
        self.base_url = base_url.rstrip("/")
        self.timeout = timeout
        self.session = requests.Session()
        self.session.headers.update(
            {
                "Authorization": f"Bearer {token}",
                "User-Agent": "TAGa-Say-EnchatBridge/0.1",
            }
        )

    def _call(self, method: str, payload: dict[str, Any] | None = None) -> Any:
        url = f"{self.base_url}/{method}"
        try:
            response = self.session.post(url, json=payload or {}, timeout=self.timeout)
        except requests.RequestException as exc:
            raise BotApiError(f"network error: {exc.__class__.__name__}") from exc
        if response.status_code == 429:
            retry_after = response.headers.get("Retry-After", "1")
            raise BotApiError(f"rate limited, retry after {retry_after}", 429)
        if response.status_code >= 400:
            raise BotApiError(f"bot API HTTP {response.status_code}", response.status_code)
        if not response.content:
            return None
        return response.json()

    def get_me(self) -> dict[str, Any]:
        return self._call("getMe")

    def get_updates(self, offset: int = 0, timeout: int = 30) -> list[dict[str, Any]]:
        result = self._call(
            "getUpdates",
            {"offset": offset, "limit": 100, "timeout": timeout},
        )
        return result if isinstance(result, list) else []

    def send_message(
        self,
        chat_id: str,
        text: str,
        reply_to: str | None = None,
        disable_preview: bool = True,
    ) -> dict[str, Any]:
        payload: dict[str, Any] = {
            "chat_id": chat_id,
            "text": text,
            "disable_web_page_preview": disable_preview,
        }
        if reply_to:
            payload["reply_to_message_id"] = reply_to
        return self._call("sendMessage", payload)

    def send_chat_action(self, chat_id: str, action: str = "typing") -> None:
        try:
            self._call("sendChatAction", {"chat_id": chat_id, "action": action})
        except BotApiError:
            pass

    def set_commands(self, commands: list[dict[str, str]]) -> None:
        try:
            self._call("setMyCommands", {"commands": commands})
        except BotApiError:
            log.warning("Could not publish bot commands")
