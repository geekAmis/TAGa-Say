"""Entrypoint for the Enchat bridge service."""

from __future__ import annotations

import logging
import signal

from .bridge import Bridge
from .config import Config


def main() -> None:
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s %(levelname)s %(name)s: %(message)s",
    )
    config = Config.from_env()
    bridge = Bridge(config)

    def shutdown(_signum, _frame):
        bridge.stop()

    signal.signal(signal.SIGINT, shutdown)
    signal.signal(signal.SIGTERM, shutdown)
    bridge.run()


if __name__ == "__main__":
    main()
