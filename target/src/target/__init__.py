import asyncio
import logging
import signal
from typing import Any

import zmq.asyncio

from target.messages import TargetMessage

from .env import DEBUG_LOGGING, TARGET_CMD_SOCK_PATH
from .servos import Servos

logging.basicConfig(level=logging.DEBUG if DEBUG_LOGGING else logging.INFO)
logger = logging.getLogger(__name__)


async def loop(servos: Servos, sock: zmq.asyncio.Socket):
    logger.info("Main loop started")
    while True:
        raw_message = await sock.recv_string()
        try:
            message = TargetMessage.model_validate_json(raw_message)
            handle_target_message(servos, message.root)
        except Exception:
            logger.warning("Received a message that wasn't valid, skipping")
            logger.debug(f"Message was {raw_message}")


def handle_target_message(servos: Servos, message: Any) -> None:
    kind = message.kind
    angle = message.angle
    if kind == "set-horizontal-servo":
        servos.set_horizontal_angle(angle)
    elif kind == "set-vertical-servo":
        servos.set_vertical_angle(angle)
    logger.info(f"Set {kind} to {angle}°")


async def run():
    ctx = zmq.asyncio.Context()
    sock = ctx.socket(zmq.PULL)
    sock.bind(TARGET_CMD_SOCK_PATH)
    logger.info(f"Listening for commands on {TARGET_CMD_SOCK_PATH}")

    servos = Servos()
    event_loop = asyncio.get_running_loop()
    for sig in (signal.SIGTERM, signal.SIGINT):
        event_loop.add_signal_handler(sig, servos.close)

    try:
        await loop(servos, sock)
    finally:
        servos.close()


def main() -> None:
    asyncio.run(run())