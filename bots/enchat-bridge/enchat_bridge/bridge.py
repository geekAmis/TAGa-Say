"""Bridge orchestration: TAGa-Say bot updates ⇄ Enchat rooms."""

from __future__ import annotations

import hashlib
import logging
import threading
import time
from collections import deque
from dataclasses import dataclass, field

import requests
from cryptography.fernet import Fernet

from . import enchat
from .config import Config
from .taga import BotApi, BotApiError

log = logging.getLogger(__name__)

HELP = """TAGa-Say ⇄ Enchat Bridge

/connect <название>        подключить публичную комнату
/connect <topic:secret>    подключить напрямую
/connect-link <url>        подключить по invite-ссылке
/disconnect                отключить мост
/rooms                     активные публичные комнаты
/status                    состояние моста
/nick <имя>                сменить ник в Enchat
/bridge on|off             пауза/возобновление
/security                  предупреждение о приватности

⚠️ Мост видит сообщения в открытом виде: E2EE действует до TAGa-Say,
а в Enchat — отдельным ключом комнаты. Не пересылайте секреты."""

SECURITY = """🔐 Безопасность моста

• TAGa-Say шифрует сообщения до сервера; бот получает их уже расшифрованными.
• Мост шифрует их ключом комнаты Enchat; сквозного E2EE через мост нет.
• В публичной комнате любой участник с ключом может читать и писать.
• Enchat не проверяет личность: ник можно подделать.
• Мост не сохраняет текст на диск и не пишет его в логи.
• Отключайте мост командой /disconnect, когда он не нужен."""


@dataclass
class Connection:
    chat_id: str
    topic: str
    secret: str
    label: str
    nick: str
    relay: str
    cipher: Fernet
    stop: threading.Event = field(default_factory=threading.Event)
    last_event_id: str | None = None
    enabled: bool = True


class Bridge:
    def __init__(self, config: Config, api: BotApi | None = None):
        self.config = config
        self.api = api or BotApi(config.tagasay_api, config.tagasay_bot_token)
        self.session = requests.Session()
        if config.tor_proxy:
            self.session.proxies = {"http": config.tor_proxy, "https": config.tor_proxy}
        self.stop_event = threading.Event()
        self._lock = threading.RLock()
        self._connections: dict[str, Connection] = {}
        self._recent_out: dict[str, deque[str]] = {}
        self._offset = 0

    # ------------------------------------------------------------------ run

    def run(self) -> None:
        self.api.set_commands(
            [
                {"command": "connect", "description": "Подключить комнату Enchat"},
                {"command": "disconnect", "description": "Отключить мост"},
                {"command": "rooms", "description": "Публичные комнаты"},
                {"command": "status", "description": "Состояние моста"},
                {"command": "security", "description": "Безопасность"},
            ]
        )
        log.info("Bridge started as %s", self.config.enchat_nick)
        try:
            while not self.stop_event.is_set():
                try:
                    updates = self.api.get_updates(self._offset, timeout=30)
                    for update in updates:
                        self._offset = max(self._offset, int(update.get("update_id", 0)) + 1)
                        try:
                            self.handle_update(update)
                        except Exception:
                            log.exception("Failed to handle update")
                except BotApiError as exc:
                    log.warning("Bot API error: %s", exc)
                    self.stop_event.wait(5)
                except Exception:
                    log.exception("Unexpected polling error")
                    self.stop_event.wait(5)
        finally:
            self.stop()

    def stop(self) -> None:
        self.stop_event.set()
        with self._lock:
            for connection in self._connections.values():
                connection.stop.set()
            self._connections.clear()

    # -------------------------------------------------------------- updates

    def handle_update(self, update: dict) -> None:
        message = update.get("message") or update.get("channel_post")
        if not isinstance(message, dict):
            return
        chat = message.get("chat") or {}
        chat_id = str(chat.get("id", ""))
        text = message.get("text")
        sender = message.get("from") or {}
        if not chat_id or not isinstance(text, str) or not text.strip():
            return

        if text.startswith("/"):
            self.handle_command(chat_id, text.strip(), sender)
        else:
            self.relay_from_taga(chat_id, sender, text.strip())

    def handle_command(self, chat_id: str, text: str, sender: dict) -> None:
        command, _, args = text[1:].partition(" ")
        command = command.split("@", 1)[0].lower()
        args = args.strip()

        if command in {"start", "help"}:
            self.api.send_message(chat_id, HELP)
            return
        if command == "security":
            self.api.send_message(chat_id, SECURITY)
            return
        if command == "status":
            self.api.send_message(chat_id, self._status_text(chat_id))
            return
        if command == "connect":
            self._command_connect(chat_id, args)
            return
        if command in {"connect-link", "connect_link"}:
            self._command_connect_link(chat_id, args)
            return
        if command == "disconnect":
            self._command_disconnect(chat_id)
            return
        if command == "rooms":
            self._command_rooms(chat_id)
            return
        if command == "nick":
            self._command_nick(chat_id, args)
            return
        if command == "bridge":
            self._command_bridge(chat_id, args)
            return
        self.api.send_message(chat_id, "Неизвестная команда. /help — список.")

    def _command_connect(self, chat_id: str, args: str) -> None:
        if not args:
            self.api.send_message(chat_id, "Использование: /connect <название> или /connect <topic:secret>")
            return

        nick = self.config.enchat_nick
        if " --nick " in f" {args}":
            args, _, nick = f" {args}".partition(" --nick ")
            args = args.strip()
            nick = nick.strip() or self.config.enchat_nick

        relay = self.config.enchat_relay
        if ":" in args and not args.startswith("http"):
            topic, _, secret = args.partition(":")
            label = topic
        else:
            room = self._find_public_room(args)
            if room is None:
                self.api.send_message(
                    chat_id,
                    "Комната не найдена. Укажите topic:secret или настройте каталог "
                    "ENCHAT_DIRECTORY_TOPIC/KEY.",
                )
                return
            topic, secret, label = room.topic, room.secret, room.name

        if not topic or not secret:
            self.api.send_message(chat_id, "Нужны topic и secret комнаты.")
            return
        self._connect(chat_id, relay, topic, secret, label, nick)

    def _command_connect_link(self, chat_id: str, args: str) -> None:
        if not args:
            self.api.send_message(chat_id, "Использование: /connect-link <url>")
            return
        resolved = enchat.resolve_invite(args, self.session)
        if not resolved:
            self.api.send_message(chat_id, "Не удалось прочитать invite-ссылку (истекла или неверный ключ).")
            return
        topic, relay, secret = resolved
        self._connect(chat_id, relay, topic, secret, "invite", self.config.enchat_nick)

    def _connect(self, chat_id: str, relay: str, topic: str, secret: str, label: str, nick: str) -> None:
        with self._lock:
            if chat_id in self._connections:
                self.api.send_message(chat_id, "Мост уже подключён. Сначала /disconnect.")
                return
            if len(self._connections) >= 10:
                self.api.send_message(chat_id, "Достигнут лимит: 10 комнат на бота.")
                return
            connection = Connection(
                chat_id=chat_id,
                topic=topic,
                secret=secret,
                label=label,
                nick=nick,
                relay=relay,
                cipher=enchat.room_cipher(secret, topic),
            )
            self._connections[chat_id] = connection
            self._recent_out[chat_id] = deque(maxlen=200)

        thread = threading.Thread(target=self._listen, args=(connection,), daemon=True)
        thread.start()
        self.api.send_message(
            chat_id,
            f"✅ Подключено к Enchat: «{label}»\n"
            f"relay: {relay}\n"
            f"ник: {nick}\n\n"
            "⚠️ Мост видит сообщения в открытом виде. /security — подробнее.",
        )

    def _command_disconnect(self, chat_id: str) -> None:
        with self._lock:
            connection = self._connections.pop(chat_id, None)
            self._recent_out.pop(chat_id, None)
        if connection is None:
            self.api.send_message(chat_id, "Мост не подключён.")
            return
        connection.stop.set()
        self.api.send_message(chat_id, "Мост отключён.")

    def _command_rooms(self, chat_id: str) -> None:
        if not self.config.directory_topic or not self.config.directory_key:
            self.api.send_message(
                chat_id,
                "Каталог публичных комнат не настроен. Используйте /connect <topic:secret>.",
            )
            return
        try:
            rooms = enchat.list_public_rooms(
                self.config.enchat_relay,
                self.config.directory_topic,
                self.config.directory_key,
                self.session,
            )
        except requests.RequestException:
            self.api.send_message(chat_id, "Каталог недоступен. Попробуйте позже.")
            return
        if not rooms:
            self.api.send_message(chat_id, "Активных публичных комнат нет.")
            return
        lines = [f"• {room.name} — /connect {room.room_id}" for room in rooms]
        self.api.send_message(chat_id, "Активные комнаты:\n" + "\n".join(lines[:25]))

    def _command_nick(self, chat_id: str, args: str) -> None:
        nick = enchat._clean_text(args, enchat.MAX_NICK)
        if not nick:
            self.api.send_message(chat_id, "Использование: /nick <имя>")
            return
        with self._lock:
            connection = self._connections.get(chat_id)
            if connection:
                connection.nick = nick
        if connection:
            self.api.send_message(chat_id, f"Ник в Enchat: {nick}")
        else:
            self.api.send_message(chat_id, "Мост не подключён.")

    def _command_bridge(self, chat_id: str, args: str) -> None:
        with self._lock:
            connection = self._connections.get(chat_id)
            if connection is None:
                self.api.send_message(chat_id, "Мост не подключён.")
                return
            connection.enabled = args.lower() != "off"
            state = "включён" if connection.enabled else "выключен"
        self.api.send_message(chat_id, f"Мост {state}.")

    def _status_text(self, chat_id: str) -> str:
        with self._lock:
            connection = self._connections.get(chat_id)
        if connection is None:
            return "Мост не подключён. /connect <комната>."
        return (
            f"Мост: «{connection.label}»\n"
            f"relay: {connection.relay}\n"
            f"topic: {connection.topic}\n"
            f"ник: {connection.nick}\n"
            f"состояние: {'включён' if connection.enabled else 'выключен'}"
        )

    # ------------------------------------------------------------ relaying

    def relay_from_taga(self, chat_id: str, sender: dict, text: str) -> None:
        with self._lock:
            connection = self._connections.get(chat_id)
            if connection is None or not connection.enabled:
                return
        name = sender.get("display_name") or sender.get("username") or "user"
        outgoing = enchat._clean_text(f"[TAGa-Say] {name}: {text}", enchat.MAX_TEXT)
        token = enchat.encode_message(connection.nick, outgoing, connection.cipher)
        body = enchat.encode_body("MSG", token)
        if enchat.publish(connection.relay, connection.topic, body, self.session):
            with self._lock:
                self._recent_out[chat_id].append(hashlib.sha256(token.encode()).hexdigest())
        else:
            log.warning("Could not publish to Enchat room %s", connection.topic)

    def _listen(self, connection: Connection) -> None:
        backoff = 2
        while not connection.stop.is_set() and not self.stop_event.is_set():
            try:
                events = enchat.iter_events(
                    connection.relay,
                    connection.topic,
                    connection.stop,
                    self.session,
                    connection.last_event_id,
                )
                for event_id, body in events:
                    if event_id:
                        connection.last_event_id = event_id
                    self._handle_enchant_body(connection, body)
                backoff = 2
            except requests.RequestException:
                connection.stop.wait(backoff)
                backoff = min(backoff * 2, 60)
            except Exception:
                log.exception("Enchat listener failed")
                connection.stop.wait(backoff)
                backoff = min(backoff * 2, 60)

    def _handle_enchant_body(self, connection: Connection, body: str) -> None:
        parsed = enchat.split_body(body)
        if not parsed:
            return
        kind, token = parsed
        if kind not in {"MSG", "SYS"}:
            return
        decoded = enchat.decode_message(token, connection.cipher)
        if not decoded:
            return
        _, sender, content = decoded
        if sender == connection.nick:
            return
        if kind == "SYS":
            if content.startswith("SYSTEM:"):
                event = content[len("SYSTEM:"):]
                if event in {"ping", "joined", "left"} or event.startswith("LOTTERY_"):
                    return
                text = f"[Enchat] · {event}"
            else:
                return
        else:
            text = f"[Enchat] {sender}: {content}"
        if not connection.enabled:
            return
        try:
            self.api.send_message(connection.chat_id, enchat._clean_text(text, 4000))
        except BotApiError as exc:
            log.warning("Could not deliver Enchat message: %s", exc)

    def _find_public_room(self, name_or_id: str):
        if not self.config.directory_topic or not self.config.directory_key:
            return None
        try:
            rooms = enchat.list_public_rooms(
                self.config.enchat_relay,
                self.config.directory_topic,
                self.config.directory_key,
                self.session,
            )
        except requests.RequestException:
            return None
        wanted = name_or_id.casefold().strip()
        for room in rooms:
            if room.room_id.casefold() == wanted or room.name.casefold() == wanted:
                return room
        return None
