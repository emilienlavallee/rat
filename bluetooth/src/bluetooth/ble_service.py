
import json
import logging
from typing import Protocol

from dbus_next.constants import PropertyAccess
from dbus_next.service import dbus_property

from bluez_peripheral.advert import Advertisement
from bluez_peripheral.gatt.characteristic import (
    CharacteristicFlags,
    characteristic,
)
from bluez_peripheral.gatt.service import Service
from pydantic import ValidationError

from bluetooth.messages import AppMessage

from .env import (
    SERVICE_UUID,
    RECEIVE_CMD_CHARACTERISTIC_UUID,
    SEND_NOTIF_CHARACTERISTIC_UUID,
)

import zmq.asyncio

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

# ATT write/header overhead: a notification payload can carry MTU - 3 bytes.
ATT_HEADER_BYTES = 3
MAX_ATT_MTU = 517
MIN_ATT_MTU = 23


def chunk_budget_for_mtu(mtu: int) -> int:
    clamped = max(MIN_ATT_MTU, min(mtu, MAX_ATT_MTU))
    return clamped - ATT_HEADER_BYTES


def handle_mtu_announce(service: "BleService", raw_message: str) -> bool:
    """Intercepts MTU announcements from the app. Returns True when the
    message was an (attempted) mtu announce and must not be forwarded."""
    try:
        parsed = json.loads(raw_message)
    except json.JSONDecodeError:
        return False
    if not isinstance(parsed, dict):
        return False
    if parsed.get("kind") != "mtu":
        return False
    mtu = parsed.get("mtu")
    if isinstance(mtu, int) and not isinstance(mtu, bool):
        service.set_client_mtu(mtu)
    else:
        logger.warning("Received malformed mtu announcement, ignoring")
    return True


class RATAdvertisement(Advertisement):
    _tx_power = 0

    @dbus_property(PropertyAccess.READWRITE)
    def TxPower(self) -> "n":  # pyright: ignore[reportUndefinedVariable]
        return self._tx_power

    @TxPower.setter
    def _set_tx_power(self, val):
        self._tx_power = val


class RATService(Service):

    def __init__(self, notif_sock: zmq.asyncio.Socket):
        super().__init__(SERVICE_UUID, True)
        self.notif_sock = notif_sock
        self.notif_chunk_bytes = chunk_budget_for_mtu(MIN_ATT_MTU)

    def set_client_mtu(self, mtu: int) -> None:
        self.notif_chunk_bytes = chunk_budget_for_mtu(mtu)
        logger.info(
            "Client negotiated MTU %s, chunk budget now %s bytes",
            mtu, self.notif_chunk_bytes)

    async def send_notification(self, msg: str) -> None:
        self.user_notifier.changed(msg.encode())

    @characteristic(SEND_NOTIF_CHARACTERISTIC_UUID, CharacteristicFlags.NOTIFY)
    def user_notifier(self, _) -> bytes:
        return b""

    @characteristic(RECEIVE_CMD_CHARACTERISTIC_UUID, CharacteristicFlags.WRITE_WITHOUT_RESPONSE)
    def user_cmd(self, _) -> bytes:
        return b""

    @user_cmd.setter  # pyright: ignore[reportFunctionMemberAccess]
    def _user_cmd_setter(service, data: bytes, _):
        raw_message = data.decode()
        if handle_mtu_announce(service, raw_message):
            return
        logger.info(f"User sent command, forwarding: {raw_message}")
        service.forward_message(raw_message)

    def forward_message(self, msg: str) -> None:
        try:
            message = AppMessage.model_validate_json(msg)
            self.notif_sock.send_string(message.model_dump_json())
        except ValidationError:
            logger.warning(
                "Received invalid message from bluetooth connection, skipping")


class BleService(Protocol):
    async def send_notification(self, msg: str) -> None: ...
    def set_client_mtu(self, mtu: int) -> None: ...
    def forward_message(self, msg: str) -> None: ...
