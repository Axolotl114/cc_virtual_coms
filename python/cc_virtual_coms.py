"""
cc_virtual_coms
===============

Python client library for the **CC Synaxis Virtual Coms** Minecraft mod.

The mod opens a local TCP listener (``127.0.0.1:25585`` by default). This module speaks its
framing protocol so an ordinary Python program can exchange values with virtual serial port
boards placed in the world, and through them with Synaxis Cimulink circuits and CC:Tweaked
computers.

Frame layout (all integers big-endian)::

    +--------+-----------+-----------+------------+-----------------+
    |  magic |  version  |  msg type |  payload   |    payload      |
    | 4 byte |  2 byte   |  2 byte   |  length    |   (JSON UTF-8)  |
    | "CCVC" |  uint16   |  uint16   |  uint32    |     N byte      |
    +--------+-----------+-----------+------------+-----------------+

Message types
-------------

======================  ======  ===================================================
Name                    Value   Direction / meaning
======================  ======  ===================================================
``MSG_DATA_PUSH``       0x01    game -> Python; a port value changed
``MSG_DATA_WRITE``      0x02    Python -> game; write a port value
``MSG_HEARTBEAT``       0x03    either direction; liveness probe
``MSG_ERROR``           0x04    either direction; carries ``{"error": "..."}``
======================  ======  ===================================================

Port value types
----------------

===========  ==================  ===========================================
Mod type     Wire type           Python value
===========  ==================  ===========================================
``real``     float64             ``float``
``boolean``  uint8               ``bool``
``vec3``     float64[3]          ``[x, y, z]``
``quad``     float64[4]          ``[x, y, z, w]``
===========  ==================  ===========================================

Quick start
-----------

四行就够：**连接、打包、发送、接收并解包**，再配一个读取方法。

::

    from cc_virtual_coms import Coms

    coms = Coms()                             # 连接 127.0.0.1:25585 / COM1

    frame = coms.pack("drive", 0.25)          # 一行打包
    coms.send(frame)                          # 一行发送

    port, ptype, value = coms.recv()          # 一行接收并解包
    print(coms.get("sense"))                  # 配套读取方法

``send`` 也可以一行搞定打包与发送::

    coms.send("drive", 0.25)
    coms.send("gear", True)
    coms.send("target", [1.0, 64.0, 2.0])

要看游戏推回来的值，直接迭代即可::

    for port, ptype, value in coms:
        print(port, ptype, value)

底层 API（``pack_message`` / ``unpack_message`` / ``VirtualComsClient``）依然可用，
``Coms`` 只是它上面的一层薄封装。
"""

from __future__ import annotations

import collections
import json
import queue
import socket
import struct
import sys
import threading
import time
import weakref
from typing import Any, Callable, Dict, Iterator, List, Optional, Tuple

__all__ = [
    "MAGIC",
    "VERSION",
    "HEADER_SIZE",
    "MSG_DATA_PUSH",
    "MSG_DATA_WRITE",
    "MSG_HEARTBEAT",
    "MSG_ERROR",
    "MESSAGE_TYPE_NAMES",
    "PORT_TYPES",
    "DIRECTION_IN",
    "DIRECTION_OUT",
    "ProtocolError",
    "NotConnectedError",
    "pack_message",
    "unpack_message",
    "pack_port_value",
    "unpack_port_value",
    "encode_port_value_binary",
    "decode_port_value_binary",
    "pack_binary_port_value",
    "unpack_binary_port_value",
    "infer_port_type",
    "coerce_port_value",
    "VirtualComsClient",
    "Coms",
    "VirtualComs",
    "connect",
]

# --------------------------------------------------------------------------------------
# Protocol constants
# --------------------------------------------------------------------------------------

MAGIC = b"CCVC"
VERSION = 1

#: ``>4s H H I`` - magic, version, message type, payload length.
HEADER = struct.Struct(">4sHHI")
HEADER_SIZE = HEADER.size  # 12

MSG_DATA_PUSH = 0x01
MSG_DATA_WRITE = 0x02
MSG_HEARTBEAT = 0x03
MSG_ERROR = 0x04

MESSAGE_TYPE_NAMES = {
    MSG_DATA_PUSH: "DATA_PUSH",
    MSG_DATA_WRITE: "DATA_WRITE",
    MSG_HEARTBEAT: "HEARTBEAT",
    MSG_ERROR: "ERROR",
}

TYPE_REAL = "real"
TYPE_BOOLEAN = "boolean"
TYPE_VEC3 = "vec3"
TYPE_QUAD = "quad"

PORT_TYPES = (TYPE_REAL, TYPE_BOOLEAN, TYPE_VEC3, TYPE_QUAD)

#: Terminal directions. ``out`` means the Cimulink circuit *reads* the terminal, so an out-of-game
#: program may drive it without the circuit ever overwriting the value. ``in`` means the circuit
#: *drives* the terminal and an out-of-game program should read it instead.
DIRECTION_IN = "in"
DIRECTION_OUT = "out"

#: Scalar layout of each port type, matching the documented wire encoding.
PORT_TYPE_STRUCTS = {
    TYPE_REAL: struct.Struct(">d"),
    TYPE_BOOLEAN: struct.Struct(">B"),
    TYPE_VEC3: struct.Struct(">3d"),
    TYPE_QUAD: struct.Struct(">4d"),
}

#: Number of scalar components per type.
PORT_TYPE_ARITY = {TYPE_REAL: 1, TYPE_BOOLEAN: 1, TYPE_VEC3: 3, TYPE_QUAD: 4}

#: ``>H`` length prefix used by the self describing binary port record.
_LENGTH = struct.Struct(">H")

DEFAULT_HOST = "127.0.0.1"
DEFAULT_PORT = 25585

#: Guard against a desynchronised or hostile peer declaring an enormous payload.
MAX_PAYLOAD_SIZE = 8 * 1024 * 1024


class ProtocolError(Exception):
    """Raised when a peer sends something that is not a valid frame."""


class NotConnectedError(Exception):
    """Raised when an operation needs a live connection but none exists."""


# --------------------------------------------------------------------------------------
# Frame level: pack_message / unpack_message
# --------------------------------------------------------------------------------------


def pack_message(msg_type: int, payload: Optional[dict] = None) -> bytes:
    """Pack a complete protocol frame.

    :param msg_type: one of the ``MSG_*`` constants.
    :param payload: JSON-serialisable mapping, or ``None`` for an empty payload.
    :returns: the framed message as ``bytes``.
    :raises ValueError: if ``msg_type`` is not a 16 bit unsigned integer.
    """
    if not 0 <= int(msg_type) <= 0xFFFF:
        raise ValueError("message type must fit in 16 bits, got %r" % (msg_type,))

    if payload is None:
        body = b""
    else:
        body = json.dumps(payload, separators=(",", ":")).encode("utf-8")

    if len(body) > MAX_PAYLOAD_SIZE:
        raise ValueError("payload of %d bytes exceeds the protocol limit" % len(body))

    return HEADER.pack(MAGIC, VERSION, int(msg_type), len(body)) + body


def unpack_message(data: bytes) -> Tuple[int, dict]:
    """Unpack a complete protocol frame.

    :param data: the full frame (header plus payload).
    :returns: ``(message_type, payload_dict)``.
    :raises ProtocolError: on a bad magic number, an unsupported version, a truncated frame or
        malformed JSON.
    """
    if data is None or len(data) < HEADER_SIZE:
        raise ProtocolError("frame is shorter than the %d byte header" % HEADER_SIZE)

    magic, version, msg_type, length = HEADER.unpack_from(data, 0)

    if magic != MAGIC:
        raise ProtocolError("bad frame magic %r, expected %r" % (magic, MAGIC))
    if version != VERSION:
        raise ProtocolError("unsupported protocol version %d, this library speaks %d" % (version, VERSION))
    if length > MAX_PAYLOAD_SIZE:
        raise ProtocolError("declared payload of %d bytes exceeds the protocol limit" % length)

    body = data[HEADER_SIZE:HEADER_SIZE + length]
    if len(body) != length:
        raise ProtocolError("frame declares %d payload bytes but only %d are present" % (length, len(body)))

    if not body:
        return msg_type, {}

    try:
        payload = json.loads(body.decode("utf-8"))
    except (UnicodeDecodeError, ValueError) as exc:
        raise ProtocolError("payload is not valid UTF-8 JSON: %s" % exc)

    if not isinstance(payload, dict):
        raise ProtocolError("payload must be a JSON object, got %s" % type(payload).__name__)

    return msg_type, payload


def read_message(sock: socket.socket) -> Tuple[int, dict]:
    """Read exactly one frame from a socket, blocking until it arrives.

    :raises EOFError: when the peer closed the connection at a frame boundary.
    :raises ProtocolError: when the frame is malformed.
    """
    header = _recv_exactly(sock, HEADER_SIZE)
    magic, version, msg_type, length = HEADER.unpack(header)

    if magic != MAGIC:
        raise ProtocolError("bad frame magic %r, expected %r" % (magic, MAGIC))
    if version != VERSION:
        raise ProtocolError("unsupported protocol version %d, this library speaks %d" % (version, VERSION))
    if length > MAX_PAYLOAD_SIZE:
        raise ProtocolError("declared payload of %d bytes exceeds the protocol limit" % length)

    body = _recv_exactly(sock, length) if length else b""
    if not body:
        return msg_type, {}

    try:
        payload = json.loads(body.decode("utf-8"))
    except (UnicodeDecodeError, ValueError) as exc:
        raise ProtocolError("payload is not valid UTF-8 JSON: %s" % exc)

    return msg_type, payload if isinstance(payload, dict) else {}


def _recv_exactly(sock: socket.socket, count: int) -> bytes:
    """Read exactly ``count`` bytes or raise :class:`EOFError`."""
    if count == 0:
        return b""

    chunks = []
    remaining = count
    while remaining > 0:
        chunk = sock.recv(remaining)
        if not chunk:
            raise EOFError("connection closed after %d of %d bytes" % (count - remaining, count))
        chunks.append(chunk)
        remaining -= len(chunk)
    return b"".join(chunks)


# --------------------------------------------------------------------------------------
# Port value level
# --------------------------------------------------------------------------------------


def infer_port_type(value: Any) -> str:
    """Guess the port type that best fits a Python value.

    ``bool`` -> ``boolean``, ``int``/``float`` -> ``real``, a 3 element sequence -> ``vec3``,
    a 4 element sequence -> ``quad``.
    """
    if isinstance(value, bool):
        return TYPE_BOOLEAN
    if isinstance(value, (int, float)):
        return TYPE_REAL
    if isinstance(value, (list, tuple)):
        if len(value) == 3:
            return TYPE_VEC3
        if len(value) == 4:
            return TYPE_QUAD
    raise ValueError(
        "cannot infer a port type from %r; pass port_type explicitly "
        "(one of %s)" % (value, ", ".join(PORT_TYPES))
    )


def coerce_port_value(port_type: str, value: Any) -> Any:
    """Validate and normalise ``value`` for ``port_type``.

    Returns the JSON facing representation: a float, a bool, or a list of 3/4 floats.
    """
    if port_type not in PORT_TYPE_STRUCTS:
        raise ValueError("unknown port type %r; expected one of %s" % (port_type, ", ".join(PORT_TYPES)))

    if port_type == TYPE_REAL:
        if isinstance(value, bool) or not isinstance(value, (int, float)):
            raise ValueError("port type 'real' needs a number, got %r" % (value,))
        return float(value)

    if port_type == TYPE_BOOLEAN:
        if isinstance(value, bool):
            return value
        if isinstance(value, (int, float)):
            return bool(value)
        raise ValueError("port type 'boolean' needs a bool, got %r" % (value,))

    arity = PORT_TYPE_ARITY[port_type]
    if not isinstance(value, (list, tuple)):
        raise ValueError("port type %r needs a sequence of %d numbers, got %r" % (port_type, arity, value))
    if len(value) != arity:
        raise ValueError("port type %r needs exactly %d components, got %d" % (port_type, arity, len(value)))

    result = []
    for index, component in enumerate(value):
        if isinstance(component, bool) or not isinstance(component, (int, float)):
            raise ValueError("component %d of port type %r must be a number, got %r"
                             % (index, port_type, component))
        result.append(float(component))
    return result


def pack_port_value(port_name: str, port_type: str, value: Any, com: Optional[str] = None) -> bytes:
    """Pack a port value into the JSON payload used by ``DATA_PUSH`` / ``DATA_WRITE``.

    :param port_name: the terminal name configured on the board.
    :param port_type: one of ``real``, ``boolean``, ``vec3``, ``quad``.
    :param value: the value; validated against ``port_type``.
    :param com: optional serial port id (``"COM1"``) so the mod can route to a specific board.
    :returns: UTF-8 encoded JSON bytes, ready to be used as a frame payload.
    """
    if not port_name:
        raise ValueError("port_name is required")

    payload = {
        "port": str(port_name),
        "type": port_type,
        "value": coerce_port_value(port_type, value),
    }
    if com:
        payload["com"] = str(com)
    return json.dumps(payload, separators=(",", ":")).encode("utf-8")


def unpack_port_value(payload: dict) -> Tuple[str, str, Any]:
    """Unpack a port value payload.

    :param payload: a decoded JSON payload mapping.
    :returns: ``(port_name, port_type, value)`` where ``value`` is a ``float``, ``bool`` or a
        list of floats.
    :raises ProtocolError: when required fields are missing or unknown.
    """
    if not isinstance(payload, dict):
        raise ProtocolError("port payload must be a mapping, got %s" % type(payload).__name__)

    port_name = payload.get("port")
    if not port_name:
        raise ProtocolError("port payload is missing the 'port' field")

    port_type = payload.get("type")
    if port_type not in PORT_TYPE_STRUCTS:
        raise ProtocolError("port payload has unknown type %r" % (port_type,))

    raw = payload.get("value")
    try:
        value = coerce_port_value(port_type, raw)
    except ValueError as exc:
        raise ProtocolError("invalid value for port %r: %s" % (port_name, exc))

    return str(port_name), port_type, value


# --------------------------------------------------------------------------------------
# Optional compact binary port records
# --------------------------------------------------------------------------------------


def encode_port_value_binary(port_type: str, value: Any) -> bytes:
    """Encode a value using the documented scalar layout.

    ``real`` -> float64, ``boolean`` -> uint8, ``vec3`` -> float64[3], ``quad`` -> float64[4].
    """
    if port_type not in PORT_TYPE_STRUCTS:
        raise ValueError("unknown port type %r" % (port_type,))

    normalised = coerce_port_value(port_type, value)
    if port_type == TYPE_BOOLEAN:
        return PORT_TYPE_STRUCTS[port_type].pack(1 if normalised else 0)
    if port_type in (TYPE_VEC3, TYPE_QUAD):
        return PORT_TYPE_STRUCTS[port_type].pack(*normalised)
    return PORT_TYPE_STRUCTS[port_type].pack(normalised)


def decode_port_value_binary(port_type: str, data: bytes) -> Any:
    """Decode a value produced by :func:`encode_port_value_binary`."""
    if port_type not in PORT_TYPE_STRUCTS:
        raise ValueError("unknown port type %r" % (port_type,))

    expected = PORT_TYPE_STRUCTS[port_type].size
    if len(data) != expected:
        raise ProtocolError("port type %r needs %d bytes, got %d" % (port_type, expected, len(data)))

    values = PORT_TYPE_STRUCTS[port_type].unpack(data)
    if port_type == TYPE_REAL:
        return values[0]
    if port_type == TYPE_BOOLEAN:
        return bool(values[0])
    return list(values)


def pack_binary_port_value(port_name: str, port_type: str, value: Any, com: Optional[str] = None) -> bytes:
    """Pack a port value into the compact, self describing binary record.

    Layout::

        [uint16 com_len][com utf8][uint16 name_len][name utf8][uint16 type_len][type utf8][value]

    This is an alternative to the JSON payload for latency sensitive loops. The mod's live socket
    protocol uses the JSON payload; this record is provided so both sides can exchange values in
    the documented scalar encoding when a caller wants it.
    """
    if not port_name:
        raise ValueError("port_name is required")
    if port_type not in PORT_TYPE_STRUCTS:
        raise ValueError("unknown port type %r" % (port_type,))

    com_bytes = (com or "").encode("utf-8")
    name_bytes = str(port_name).encode("utf-8")
    type_bytes = port_type.encode("utf-8")
    body = encode_port_value_binary(port_type, value)

    return (
        _LENGTH.pack(len(com_bytes)) + com_bytes
        + _LENGTH.pack(len(name_bytes)) + name_bytes
        + _LENGTH.pack(len(type_bytes)) + type_bytes
        + body
    )


def unpack_binary_port_value(data: bytes) -> Tuple[str, str, Any, str]:
    """Unpack a record produced by :func:`pack_binary_port_value`.

    :returns: ``(port_name, port_type, value, com)``.
    """
    offset = 0

    def read_field() -> bytes:
        nonlocal offset
        if offset + _LENGTH.size > len(data):
            raise ProtocolError("truncated binary port record")
        (length,) = _LENGTH.unpack_from(data, offset)
        offset += _LENGTH.size
        if offset + length > len(data):
            raise ProtocolError("truncated binary port record")
        field = data[offset:offset + length]
        offset += length
        return field

    com = read_field().decode("utf-8")
    name = read_field().decode("utf-8")
    port_type = read_field().decode("utf-8")
    value = decode_port_value_binary(port_type, data[offset:])
    return name, port_type, value, com


# --------------------------------------------------------------------------------------
# High level client
# --------------------------------------------------------------------------------------


class VirtualComsClient:
    """A connection to the mod's local virtual coms listener.

    The client keeps a cache of the most recent value of every port the game has pushed, so
    :meth:`read_port` never blocks. Incoming values are dispatched to callbacks registered with
    :meth:`on_data`, or yielded from :meth:`listen`.

    The object can be used as a context manager::

        with VirtualComsClient() as coms:
            coms.write_port("elevator", 12.5)

    :param host: listener address, ``127.0.0.1`` unless the mod was reconfigured.
    :param port: listener TCP port, ``25585`` by default.
    :param auto_reconnect: when true, :meth:`run_forever` reconnects after a drop.
    :param timeout: socket timeout in seconds used by the background pump.
    """

    def __init__(
        self,
        host: str = DEFAULT_HOST,
        port: int = DEFAULT_PORT,
        auto_reconnect: bool = True,
        timeout: float = 0.5,
    ) -> None:
        self.host = host
        self.port = int(port)
        self.auto_reconnect = auto_reconnect
        self.timeout = timeout

        self._sock: Optional[socket.socket] = None
        self._lock = threading.RLock()
        self._send_lock = threading.Lock()
        # Two threads reading one socket is an error on Windows (WSAEWOULDBLOCK surfaces as
        # BlockingIOError), so all reads are serialised through this lock.
        self._read_lock = threading.RLock()

        self._values: Dict[str, Any] = {}
        self._types: Dict[str, str] = {}
        self._directions: Dict[str, str] = {}
        #: Which serial port (board) each terminal belongs to, as reported by the mod.
        self._coms: Dict[str, str] = {}

        self._data_callbacks: List[Callable[[str, str, Any], None]] = []
        self._error_callbacks: List[Callable[[str], None]] = []
        self._disconnect_callbacks: List[Callable[[], None]] = []

        self._pump_thread: Optional[threading.Thread] = None
        self._stop_event = threading.Event()
        self._last_error: Optional[str] = None

    # -------------------------------------------------------------- connection

    def connect(self, timeout: float = 5.0) -> "VirtualComsClient":
        """Open the socket. Calling this while already connected is a no-op."""
        with self._lock:
            if self._sock is not None:
                return self

            sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            sock.settimeout(timeout)
            try:
                sock.connect((self.host, self.port))
            except OSError:
                sock.close()
                raise
            sock.settimeout(self.timeout)
            sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
            self._sock = sock
            self._last_error = None
            return self

    def close(self) -> None:
        """Stop any background pump and close the socket."""
        self._stop_event.set()
        self.stop_background()

        with self._lock:
            sock = self._sock
            self._sock = None

        if sock is not None:
            try:
                sock.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass
            try:
                sock.close()
            except OSError:
                pass

    @property
    def connected(self) -> bool:
        """Whether the socket is currently open."""
        with self._lock:
            return self._sock is not None

    def is_connected(self) -> bool:
        """Alias of :attr:`connected`, matching the mod's Lua API naming."""
        return self.connected

    def __enter__(self) -> "VirtualComsClient":
        return self.connect()

    def __exit__(self, exc_type, exc, tb) -> None:
        self.close()

    # -------------------------------------------------------------- sending

    def send(self, msg_type: int, payload: Optional[dict] = None) -> None:
        """Send one frame.

        :raises NotConnectedError: when there is no live connection.
        """
        self.send_frame(pack_message(msg_type, payload))

    def send_frame(self, frame: bytes) -> None:
        """Send an already packed frame.

        :raises NotConnectedError: when there is no live connection. The message includes whatever
            the game last reported, which is usually the real reason (for example the mod refusing
            the connection because too many are already open).
        """
        with self._send_lock:
            with self._lock:
                sock = self._sock
            if sock is None:
                raise NotConnectedError(self._not_connected_reason())
            try:
                sock.sendall(frame)
            except OSError as exc:
                self._handle_disconnect("send failed: %s" % exc)
                raise NotConnectedError("send failed: %s" % exc)

    def _not_connected_reason(self) -> str:
        """Build a NotConnectedError message that says *why*, not just *that*."""
        message = "not connected to %s:%d" % (self.host, self.port)
        detail = self._last_error
        if detail:
            message += " - the game said: %s" % detail
        else:
            message += (" - the connection is not open. If you create a client in a loop, create it "
                        "once and reuse it: every new client opens another TCP connection until the "
                        "mod's limit is reached.")
        return message

    def send_raw(self, payload: dict, msg_type: int = MSG_DATA_WRITE) -> None:
        """Send an already built payload mapping."""
        self.send(msg_type, payload)

    def write_port(
        self,
        port_name: str,
        value: Any,
        port_type: Optional[str] = None,
        com: Optional[str] = None,
    ) -> None:
        """Write a value into a port on a board in the game.

        :param port_name: terminal name.
        :param value: the value to write.
        :param port_type: one of the four port types; inferred from ``value`` when omitted.
        :param com: optional serial port id to target a specific board.
        :raises ValueError: when the value does not match the port type.
        """
        if port_type is None:
            port_type = self._types.get(port_name) or infer_port_type(value)

        body = pack_port_value(port_name, port_type, value, com=com)
        self.send(MSG_DATA_WRITE, json.loads(body.decode("utf-8")))

    def write_ports(self, com: Optional[str] = None, **values: Any) -> None:
        """Write several ports in one call, inferring each type individually."""
        for name, value in values.items():
            self.write_port(name, value, com=com)

    def heartbeat(self) -> None:
        """Send a liveness probe; the mod echoes it straight back."""
        self.send(MSG_HEARTBEAT)

    def report_error(self, message: str) -> None:
        """Send an error frame to the game, which logs it."""
        self.send(MSG_ERROR, {"error": str(message)})

    # -------------------------------------------------------------- receiving

    def process_message(self, msg_type: int, payload: dict) -> None:
        """Handle one decoded frame, updating the cache and firing callbacks."""
        if msg_type in (MSG_DATA_PUSH, MSG_DATA_WRITE):
            try:
                name, port_type, value = unpack_port_value(payload)
            except ProtocolError as exc:
                self._emit_error(str(exc))
                return
            self._values[name] = value
            self._types[name] = port_type

            # The mod tags every push with the terminal's direction so we can tell which terminals
            # the circuit reads (safe to drive) from the ones the circuit drives (read only).
            direction = payload.get("direction")
            if direction in (DIRECTION_IN, DIRECTION_OUT):
                self._directions[name] = direction

            # Remember which board the terminal lives on, so a write can be addressed correctly
            # without the caller having to track it.
            board = payload.get("com")
            if board:
                self._coms[name] = board

            for callback in list(self._data_callbacks):
                try:
                    callback(name, port_type, value)
                except Exception as exc:  # noqa: BLE001 - user callbacks must not kill the pump
                    self._emit_error("data callback raised: %s" % exc)

        elif msg_type == MSG_ERROR:
            self._emit_error(str(payload.get("error", "unknown error")))

        elif msg_type == MSG_HEARTBEAT:
            pass

    def pump(self, timeout: Optional[float] = None) -> int:
        """Read and dispatch every frame that arrives within ``timeout`` seconds.

        Safe to call from several threads: reads are serialised internally.

        :returns: the number of frames processed, or ``0`` on timeout.
        """
        with self._read_lock:
            return self._pump_locked(timeout)

    def _pump_locked(self, timeout: Optional[float]) -> int:
        with self._lock:
            sock = self._sock
        if sock is None:
            raise NotConnectedError("not connected to %s:%d" % (self.host, self.port))

        previous = sock.gettimeout()
        sock.settimeout(self.timeout if timeout is None else timeout)
        processed = 0
        try:
            while True:
                try:
                    msg_type, payload = read_message(sock)
                except socket.timeout:
                    break
                except EOFError:
                    self._handle_disconnect("connection closed by the game")
                    break
                except ProtocolError as exc:
                    self._emit_error(str(exc))
                    break
                processed += 1
                self.process_message(msg_type, payload)
        finally:
            try:
                sock.settimeout(previous)
            except OSError:
                pass
        return processed

    def listen(self, timeout: Optional[float] = None, max_messages: Optional[int] = None
               ) -> Iterator[Tuple[str, str, Any]]:
        """Yield ``(port, type, value)`` for values pushed by the game.

        :param timeout: total seconds to wait before stopping; ``None`` means forever.
        :param max_messages: stop after this many values.
        """
        deadline = None if timeout is None else time.monotonic() + timeout
        delivered: List[Tuple[str, str, Any]] = []

        def collect(port: str, port_type: str, value: Any) -> None:
            delivered.append((port, port_type, value))

        self.on_data(collect)
        try:
            while True:
                while delivered:
                    yield delivered.pop(0)
                    if max_messages is not None and max_messages <= 0:
                        return
                if deadline is not None and time.monotonic() >= deadline:
                    return
                self.pump(timeout=0.2)
        finally:
            if collect in self._data_callbacks:
                self._data_callbacks.remove(collect)

    # -------------------------------------------------------------- cache access

    def read_port(self, port_name: str, default: Any = None) -> Any:
        """Return the most recently pushed value for ``port_name``.

        The read is served from a local cache, so it never blocks and does not require the game
        to answer. Use :meth:`pump` or :meth:`listen` to keep the cache fresh.
        """
        return self._values.get(port_name, default)

    def port_type(self, port_name: str, default: Optional[str] = None) -> Optional[str]:
        """Return the last known type of ``port_name``."""
        return self._types.get(port_name, default)

    def port_direction(self, port_name: str, default: Optional[str] = None) -> Optional[str]:
        """Return the last known direction of ``port_name``: ``"out"``, ``"in"`` or ``default``.

        ``"out"`` terminals are the ones the Cimulink circuit *reads*, so they are the terminals an
        out-of-game program can safely drive. ``"in"`` terminals are driven by the circuit.
        """
        return self._directions.get(port_name, default)

    def port_com(self, port_name: str, default: Optional[str] = None) -> Optional[str]:
        """Return the serial port id (board) ``port_name`` lives on, e.g. ``"COM1"``.

        The mod reports this on every push, so a program that talks to several boards does not have
        to keep track of which terminal belongs where.
        """
        return self._coms.get(port_name, default)

    def is_writable(self, port_name: str) -> bool:
        """True when ``port_name`` is a known ``"out"`` terminal.

        This is the check to make before driving a terminal: nothing inside the circuit ever
        overwrites an ``out`` terminal, whereas an ``in`` terminal may be rewritten by the circuit
        on the very next game tick.
        """
        return self._directions.get(port_name) == DIRECTION_OUT

    def out_ports(self) -> List[str]:
        """Names of every known ``"out"`` terminal, sorted."""
        return sorted(name for name, direction in self._directions.items() if direction == DIRECTION_OUT)

    def in_ports(self) -> List[str]:
        """Names of every known ``"in"`` terminal, sorted."""
        return sorted(name for name, direction in self._directions.items() if direction == DIRECTION_IN)

    def list_ports(self) -> List[Dict[str, Any]]:
        """Return every port seen so far as ``{"name", "com", "type", "direction", "value"}``."""
        return [
            {
                "name": name,
                "com": self._coms.get(name),
                "type": self._types.get(name),
                "direction": self._directions.get(name),
                "value": value,
            }
            for name, value in sorted(self._values.items())
        ]

    @property
    def values(self) -> Dict[str, Any]:
        """A copy of the whole value cache."""
        return dict(self._values)

    @property
    def last_error(self) -> Optional[str]:
        """The most recent error reported by the game or by the socket layer."""
        return self._last_error

    # -------------------------------------------------------------- callbacks

    def on_data(self, callback: Callable[[str, str, Any], None]) -> Callable[[str, str, Any], None]:
        """Register ``callback(port_name, port_type, value)`` for pushed values."""
        self._data_callbacks.append(callback)
        return callback

    def on_error(self, callback: Callable[[str], None]) -> Callable[[str], None]:
        """Register ``callback(message)`` for error frames."""
        self._error_callbacks.append(callback)
        return callback

    def on_disconnect(self, callback: Callable[[], None]) -> Callable[[], None]:
        """Register ``callback()`` invoked when the connection drops."""
        self._disconnect_callbacks.append(callback)
        return callback

    def _emit_error(self, message: str) -> None:
        self._last_error = message
        for callback in list(self._error_callbacks):
            try:
                callback(message)
            except Exception:  # noqa: BLE001
                pass

    def _handle_disconnect(self, reason: str) -> None:
        with self._lock:
            sock, self._sock = self._sock, None
        if sock is not None:
            try:
                sock.close()
            except OSError:
                pass
            self._emit_error(reason)
            for callback in list(self._disconnect_callbacks):
                try:
                    callback()
                except Exception:  # noqa: BLE001
                    pass

    # -------------------------------------------------------------- background pump

    def start_background(self) -> "VirtualComsClient":
        """Start a daemon thread that continuously pumps incoming frames.

        The thread holds only a **weak** reference to this client, so an abandoned client can be
        garbage collected; its socket is then closed by :meth:`__del__` and the mod releases the
        session. Without that, creating a client in a loop would leak one live connection per
        iteration until the mod's client limit is reached.
        """
        if self._pump_thread is not None and self._pump_thread.is_alive():
            return self
        if not self.connected:
            self.connect()
        self._stop_event.clear()

        reference = weakref.ref(self)

        def pump_loop() -> None:
            while True:
                client = reference()
                if client is None or client._stop_event.is_set():
                    return
                try:
                    if not client.connected:
                        if not client.auto_reconnect:
                            return
                        time.sleep(0.5)
                        try:
                            client.connect()
                        except OSError:
                            del client
                            continue
                    client.pump(timeout=0.2)
                except NotConnectedError:
                    if not client.auto_reconnect:
                        return
                    time.sleep(0.5)
                except Exception as exc:  # noqa: BLE001 - the pump must stay alive
                    if reference() is None:
                        return
                    client._emit_error("pump error: %s" % exc)
                    time.sleep(0.5)
                finally:
                    # Drop the strong local so an abandoned client becomes collectable; otherwise
                    # this loop would keep it (and its socket) alive forever.
                    del client

        self._pump_thread = threading.Thread(target=pump_loop, name="cc_virtual_coms-pump", daemon=True)
        self._pump_thread.start()
        return self

    def stop_background(self, timeout: float = 2.0) -> None:
        """Stop the background pump thread, if any."""
        thread = self._pump_thread
        self._pump_thread = None
        if thread is not None and thread.is_alive():
            thread.join(timeout=timeout)

    def __del__(self) -> None:
        """Close the socket if the client was dropped without :meth:`close`."""
        try:
            self._stop_event.set()
            sock, self._sock = self._sock, None
            if sock is not None:
                sock.close()
        except Exception:  # noqa: BLE001 - never raise from a finaliser
            pass

    def run_forever(self) -> None:
        """Block, dispatching incoming values, until interrupted or disconnected.

        With ``auto_reconnect`` enabled the client retries the connection every half second.
        """
        try:
            while True:
                if not self.connected:
                    if not self.auto_reconnect:
                        return
                    try:
                        self.connect()
                    except OSError:
                        time.sleep(0.5)
                        continue
                self.pump(timeout=0.5)
        except KeyboardInterrupt:
            return
        finally:
            self.close()


# --------------------------------------------------------------------------------------
# Simple facade: Coms
# --------------------------------------------------------------------------------------


_MISSING = object()

#: Timestamps of recently constructed :class:`Coms` objects, used to spot a client created in a loop.
_recent_client_creations: "collections.deque" = collections.deque()
_loop_warning_issued = False


def _note_client_creation() -> None:
    """Warn once when a program keeps constructing clients instead of reusing one.

    A client created inside a loop opens a fresh TCP connection every iteration and leaves the mod
    juggling many concurrent writers, whose writes arrive out of order - the value the game keeps is
    then whichever write happened to be processed last, not the one sent last. It is also how the
    mod's client limit gets hit. This is a common enough mistake to be worth calling out loudly.
    """
    global _loop_warning_issued

    now = time.monotonic()
    _recent_client_creations.append(now)
    while _recent_client_creations and now - _recent_client_creations[0] > 2.0:
        _recent_client_creations.popleft()

    if len(_recent_client_creations) >= 8 and not _loop_warning_issued:
        _loop_warning_issued = True
        print(
            "\n"
            "  [cc_virtual_coms] %d clients created in the last 2 seconds.\n"
            "  Creating a client in a loop opens a new TCP connection every iteration, so the\n"
            "  game ends up applying writes out of order and the value you see lags badly.\n"
            "\n"
            "  Create ONE client and reuse it:\n"
            "\n"
            "      coms = Coms()          # outside the loop\n"
            "      while True:\n"
            "          coms.send(\"port\", value)\n"
            "\n"
            "  (this message is printed once)\n"
            % len(_recent_client_creations),
            file=sys.stderr,
        )


class Coms:
    """The short, friendly way to talk to the game.

    Wraps :class:`VirtualComsClient` so that a working program is four lines long::

        from cc_virtual_coms import Coms

        coms = Coms()                              # 连接 COM1
        frame = coms.pack("drive", 0.25)           # 打包
        coms.send(frame)                           # 发送
        port, ptype, value = coms.recv()           # 接收并解包
        print(coms.get("sense"))                   # 读取缓存里的最新值

    ``send`` also accepts ``(port, value)`` directly, which packs and sends in one line::

        coms.send("drive", 0.25)

    A background thread pumps the socket, so ``recv`` blocks on an internal queue while
    ``send`` stays usable at the same time.

    :param com: default serial port id (``"COM1"``). ``None`` broadcasts to every board.
    :param host: listener address.
    :param port: listener TCP port.
    :param connect: connect immediately (default). Pass ``False`` to connect later.
    :param background: pump the socket on a daemon thread (default).
    :param timeout: socket timeout in seconds.
    """

    def __init__(
        self,
        com: Optional[str] = "COM1",
        host: str = DEFAULT_HOST,
        port: int = DEFAULT_PORT,
        connect: bool = True,
        background: bool = True,
        timeout: float = 0.5,
    ) -> None:
        self.com = com
        self._background = bool(background)
        self._client = VirtualComsClient(host, port, timeout=timeout)
        self._inbox: "queue.Queue" = queue.Queue()
        self._errors: List[str] = []

        # The callbacks must not close a reference cycle. Storing `self._on_data` (a bound method)
        # would make the client point back at this object, so a Coms dropped by the caller could
        # never be collected - and every abandoned Coms would keep a TCP session open until the mod's
        # client limit was reached. Forwarding through a weak reference keeps the graph one-way:
        # Coms -> client, never the reverse.
        reference = weakref.ref(self)

        def forward_data(port_name: str, port_type: str, value: Any) -> None:
            target = reference()
            if target is not None:
                target._on_data(port_name, port_type, value)

        def forward_error(message: str) -> None:
            target = reference()
            if target is not None:
                target._on_error(message)

        self._client.on_data(forward_data)
        self._client.on_error(forward_error)

        if connect:
            self._client.connect()
            _note_client_creation()
            if background:
                self._client.start_background()

    # -------------------------------------------------------------- internals

    def _on_data(self, port_name: str, port_type: str, value: Any) -> None:
        self._inbox.put((port_name, port_type, value))

    def _on_error(self, message: str) -> None:
        self._errors.append(message)

    @property
    def client(self) -> VirtualComsClient:
        """The underlying :class:`VirtualComsClient`, for advanced use."""
        return self._client

    # -------------------------------------------------------------- 打包

    def pack(self, port_name: str, value: Any, port_type: Optional[str] = None) -> bytes:
        """打包一个端口值为**完整帧**，可直接交给 :meth:`send` 或任意 socket。

        ``port_type`` 的选取顺序：

        1. 显式传入的 ``port_type``；
        2. 游戏已报告过的该端子类型（**优先于按值推断** —— 端子类型是游戏定的，
           按值推断只会推出一个游戏不收的类型）；
        3. 按值推断（``True``→boolean，数字→real，三元组→vec3，四元组→quad）。
        """
        resolved = port_type or self._client.port_type(port_name) or infer_port_type(value)
        # Address the board the terminal actually lives on. Falling back to self.com keeps working
        # for terminals the game has not reported yet.
        board = self._client.port_com(port_name, self.com)
        try:
            payload = json.loads(pack_port_value(port_name, resolved, value, com=board).decode("utf-8"))
        except ValueError as exc:
            known = self._client.port_type(port_name)
            if known is not None and resolved == known and port_type is None:
                raise ValueError(
                    "端子 '%s' 在游戏里是 %s 类型，接受不了 %r。"
                    "（如果你想发别的类型，请显式传 port_type=）" % (port_name, known, value)) from None
            raise
        return pack_message(MSG_DATA_WRITE, payload)

    def pack_payload(self, port_name: str, value: Any, port_type: Optional[str] = None) -> bytes:
        """只打包 JSON 负载（不含帧头），用于自定义传输。"""
        resolved = port_type or self._client.port_type(port_name) or infer_port_type(value)
        board = self._client.port_com(port_name, self.com)
        return pack_port_value(port_name, resolved, value, com=board)

    # -------------------------------------------------------------- 发送

    def send(self, packet: Any, value: Any = _MISSING, port_type: Optional[str] = None) -> int:
        """发送。

        两种用法等价：

        * ``coms.send(frame)``          —— 发送 :meth:`pack` 打包好的帧
        * ``coms.send("drive", 0.25)``  —— 一行完成打包 + 发送

        :returns: 1（发送的帧数）
        """
        if isinstance(packet, (bytes, bytearray, memoryview)):
            self._client.send_frame(bytes(packet))
            return 1
        if value is _MISSING:
            raise TypeError("send(packed_frame) 或 send(port_name, value)")
        self._client.send_frame(self.pack(packet, value, port_type))
        return 1

    def write(self, port_name: str, value: Any, port_type: Optional[str] = None) -> int:
        """``send`` 的别名：``coms.write("drive", 0.25)``。"""
        return self.send(port_name, value, port_type)

    def heartbeat(self) -> None:
        """发送心跳。"""
        self._client.heartbeat()

    # -------------------------------------------------------------- 接收

    def recv(self, timeout: Optional[float] = None) -> Optional[Tuple[str, str, Any]]:
        """接收一条游戏推送的值并解包。

        :returns: ``(port_name, port_type, value)``；超时返回 ``None``。
        """
        try:
            return self._inbox.get(timeout=timeout)
        except queue.Empty:
            return None

    def poll(self, timeout: float = 0.0) -> List[Tuple[str, str, Any]]:
        """把已经到达的推送收下来并解包，返回 ``[(port, type, value), ...]``。

        后台接收模式下只有一个后台线程读 socket，这里只排空内部队列
        （``timeout`` 表示最多等这么久让推送到达）。非后台模式则直接读一次 socket。
        """
        if not self._background:
            self._client.pump(timeout=timeout)
        elif timeout > 0:
            time.sleep(timeout)

        drained = []
        while True:
            try:
                drained.append(self._inbox.get_nowait())
            except queue.Empty:
                return drained

    def recv_all(self, timeout: float = 1.0, max_items: Optional[int] = None) -> List[Tuple[str, str, Any]]:
        """在一段时间内尽量多地接收，返回解包后的列表。"""
        deadline = time.monotonic() + timeout
        collected = []
        while time.monotonic() < deadline:
            item = self.recv(timeout=min(0.1, max(0.0, deadline - time.monotonic())))
            if item is not None:
                collected.append(item)
                if max_items is not None and len(collected) >= max_items:
                    break
        return collected

    def __iter__(self) -> Iterator[Tuple[str, str, Any]]:
        """``for port, ptype, value in coms:`` 持续接收。"""
        while True:
            item = self.recv(timeout=0.2)
            if item is not None:
                yield item

    # -------------------------------------------------------------- 读取

    def get(self, port_name: str, default: Any = None) -> Any:
        """读取该端子最新已知的值（不阻塞，来自本地缓存）。"""
        return self._client.read_port(port_name, default)

    def __getitem__(self, port_name: str) -> Any:
        """``coms["sense"]`` 等价于 ``coms.get("sense")``。"""
        return self._client.read_port(port_name)

    def read(self, port_name: str, default: Any = None, wait: Optional[float] = None) -> Any:
        """读取该端子的值；``wait`` 给定时最多等待这么多秒直到它有值。"""
        if wait is not None and port_name not in self._client.values:
            deadline = time.monotonic() + wait
            while time.monotonic() < deadline and port_name not in self._client.values:
                self.poll(timeout=min(0.1, max(0.0, deadline - time.monotonic())))
        return self._client.read_port(port_name, default)

    # -------------------------------------------------------------- 状态

    @property
    def connected(self) -> bool:
        return self._client.connected

    def is_connected(self) -> bool:
        return self._client.connected

    @property
    def ports(self) -> Dict[str, Any]:
        """最新值缓存 ``{port_name: value}``。"""
        return self._client.values

    def list_ports(self) -> List[Dict[str, Any]]:
        """``[{"name", "type", "direction", "value"}, ...]``。"""
        return self._client.list_ports()

    def out_ports(self) -> List[str]:
        """可安全驱动的 ``out`` 端子（电路只读它们）。"""
        return self._client.out_ports()

    def in_ports(self) -> List[str]:
        """由电路驱动的 ``in`` 端子。"""
        return self._client.in_ports()

    def port_direction(self, port_name: str, default: Optional[str] = None) -> Optional[str]:
        return self._client.port_direction(port_name, default)

    def port_com(self, port_name: str, default: Optional[str] = None) -> Optional[str]:
        """该端子所属电路板的串口号（游戏上报的）。"""
        return self._client.port_com(port_name, default)

    @property
    def last_error(self) -> Optional[str]:
        """最近一次错误（游戏回传的错误帧，或本地 socket 错误）。"""
        if self._errors:
            return self._errors[-1]
        return self._client.last_error

    def take_errors(self) -> List[str]:
        """取出并清空累积的错误。"""
        errors, self._errors = self._errors, []
        return errors

    def close(self) -> None:
        self._client.close()

    def __del__(self) -> None:
        """Close the connection if the object was dropped without :meth:`close`."""
        try:
            self._client.close()
        except Exception:  # noqa: BLE001 - never raise from a finaliser
            pass

    def __enter__(self) -> "Coms":
        return self

    def __exit__(self, exc_type, exc, tb) -> None:
        self.close()

    def __repr__(self) -> str:
        return "<Coms com=%r %s:%d connected=%s ports=%d>" % (
            self.com, self._client.host, self._client.port,
            self._client.connected, len(self._client.values))


#: Readable alias.
VirtualComs = Coms

#: Connect to the default listener and return a :class:`Coms`, as a one-liner.
def connect(com: Optional[str] = "COM1", host: str = DEFAULT_HOST,
            port: int = DEFAULT_PORT, **kwargs: Any) -> Coms:
    """``coms = cc_virtual_coms.connect("COM1")``."""
    return Coms(com=com, host=host, port=port, **kwargs)


# --------------------------------------------------------------------------------------
# Command line helper
# --------------------------------------------------------------------------------------


def _main(argv: Optional[List[str]] = None) -> int:
    """Small diagnostic tool: connect, print values, optionally write one."""
    import argparse

    parser = argparse.ArgumentParser(description="CC Synaxis Virtual Coms client")
    parser.add_argument("--host", default=DEFAULT_HOST)
    parser.add_argument("--port", type=int, default=DEFAULT_PORT)
    parser.add_argument("--write", nargs=2, metavar=("PORT", "VALUE"), action="append",
                        help="write a value before listening (repeatable)")
    parser.add_argument("--com", default=None, help="serial port id to target")
    parser.add_argument("--seconds", type=float, default=10.0, help="how long to listen")
    args = parser.parse_args(argv)

    def on_error(message: str) -> None:
        print("[error] %s" % message)

    with VirtualComsClient(args.host, args.port) as coms:
        coms.on_error(on_error)
        print("connected to %s:%d" % (args.host, args.port))

        for port, raw in (args.write or []):
            try:
                value = json.loads(raw)
            except ValueError:
                value = raw
            coms.write_port(port, value, com=args.com)
            print("-> %s = %r" % (port, value))

        print("listening for %.1fs ..." % args.seconds)
        try:
            for port, port_type, value in coms.listen(timeout=args.seconds):
                print("<- %-20s %-8s %r" % (port, port_type, value))
        except KeyboardInterrupt:
            pass

    return 0


if __name__ == "__main__":
    raise SystemExit(_main())
