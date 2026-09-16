import asyncio
import logging

from bluez_peripheral.agent import NoIoAgent
from bluez_peripheral.util import Adapter, get_message_bus
from pydantic import ValidationError

from bluetooth.messages import AppMessage

from .ble_service import BleService, RATService, RATAdvertisement
from .env import BLE_CMD_SOCK_PATH, BLE_NOTIF_SOCK_PATH, DEBUG_LOGGING, SERVICE_UUID

import zmq.asyncio

logging.basicConfig(level=logging.DEBUG if DEBUG_LOGGING else logging.INFO)
logger = logging.getLogger(__name__)


async def loop(service: BleService, sys_cmd_sock: zmq.asyncio.Socket):
    logger.info("Main loop started")
    while True:
        raw_message = await sys_cmd_sock.recv_string()
        try:
            message = AppMessage.model_validate_json(raw_message)
            await service.send_notification(message.model_dump_json())
            logger.debug(
                f"Successfully forwarded message {raw_message} [controller -> bluetooth]")
        except ValidationError:
            logger.warning("Received a message that wasn't valid, skipping")
            logger.debug(f"Message was {raw_message}")
        except KeyboardInterrupt:
            # gracefully shutdown
            break


def create_sockets(ctx: zmq.asyncio.Context) -> dict[str, zmq.asyncio.Socket]:
    cmd_out_sock = ctx.socket(zmq.PULL)  # controller -> app
    ble_in_sock = ctx.socket(zmq.PUSH)  # app -> controller

    return {
        "cmd_out": cmd_out_sock,
        "ble_in": ble_in_sock,
    }


async def run():
    logger.info("Starting BLE server")
    bus = await get_message_bus()
    ctx = zmq.asyncio.Context()
    sockets = create_sockets(ctx)
    cmd_out_sock = sockets["cmd_out"]
    ble_in_sock = sockets["ble_in"]
    cmd_out_sock.bind(BLE_CMD_SOCK_PATH)
    ble_in_sock.connect(BLE_NOTIF_SOCK_PATH)

    introspection = await bus.introspect("org.bluez", "/org/bluez/hci0")
    proxy = bus.get_proxy_object("org.bluez", "/org/bluez/hci0", introspection)
    adapter = Adapter(proxy)
    await adapter.set_powered(True)

    service = RATService(ble_in_sock)
    await service.register(bus, adapter=adapter)

    agent = NoIoAgent()
    await agent.register(bus)

    advert = RATAdvertisement(
        "RAT", [SERVICE_UUID], 0x0000, 0, discoverable=True)
    await advert.register(bus, adapter)

    logger.info("Advertising as 'RAT'")

    await loop(service, cmd_out_sock)


def main() -> None:
    asyncio.run(run())
