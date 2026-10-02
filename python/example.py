"""
CC Synaxis Virtual Coms - example program
=========================================

Demonstrates the three things an out-of-game program normally wants to do:

1. connect to the running Minecraft instance;
2. continuously read the values the game pushes out of its virtual serial ports;
3. write values back into the game.

Before running this, place a **Virtual Serial Port Board** in the world and configure it
(right-click with an empty hand) with a few terminals, for example::

    name        type      direction
    ----------  --------  ---------
    throttle    real      in          <- we write this from Python
    gear        boolean   in          <- we write this from Python
    target      vec3      in          <- we write this from Python
    altitude    real      out         <- the game pushes this to us
    attitude    quad      out         <- the game pushes this to us

Then start Minecraft (the mod listens on 127.0.0.1:25585) and run::

    python example.py

Optional arguments::

    python example.py --host 127.0.0.1 --port 25585 --com COM1 --seconds 30
"""

from __future__ import annotations

import argparse
import math
import sys
import time

from cc_virtual_coms import (
    MSG_ERROR,
    VirtualComsClient,
    infer_port_type,
    pack_message,
    pack_port_value,
    unpack_message,
)

# Terminal names used by this example. Adjust them to match your board.
PORT_THROTTLE = "throttle"
PORT_GEAR = "gear"
PORT_TARGET = "target"
PORT_ALTITUDE = "altitude"
PORT_ATTITUDE = "attitude"


def header(title: str) -> None:
    print()
    print("=" * 68)
    print(title)
    print("=" * 68)


def demo_framing() -> None:
    """Show the raw framing API without touching a socket.

    This is handy for understanding the protocol and for unit testing a peer implementation.
    """
    header("1. Frame and payload encoding (offline)")

    frame = pack_message(0x02, {"port": "throttle", "type": "real", "value": 0.75})
    print("pack_message(DATA_WRITE, {...}) -> %d bytes" % len(frame))
    print("  hex: %s" % frame.hex(" "))

    msg_type, payload = unpack_message(frame)
    print("unpack_message(...) -> type=0x%02X payload=%r" % (msg_type, payload))

    port_payload = pack_port_value("target", "vec3", [1.0, 64.0, 2.0], com="COM1")
    print("pack_port_value('target', 'vec3', [1, 64, 2]) -> %s" % port_payload.decode())

    print("infer_port_type(0.5)      -> %s" % infer_port_type(0.5))
    print("infer_port_type(True)     -> %s" % infer_port_type(True))
    print("infer_port_type([1,2,3])  -> %s" % infer_port_type([1, 2, 3]))
    print("infer_port_type([1,2,3,4])-> %s" % infer_port_type([1, 2, 3, 4]))


def demo_streaming(client: VirtualComsClient, seconds: float) -> None:
    """Continuously print everything the game pushes, while writing values back."""
    header("2. Streaming values from the game for %.0f seconds" % seconds)
    print("(waiting for the game to push values - move the board's inputs in game)\n")

    pushed = {"count": 0}

    def on_value(port: str, port_type: str, value) -> None:
        pushed["count"] += 1
        print("  <- %-14s %-8s %s" % (port, port_type, value))

    def on_error(message: str) -> None:
        print("  [!] %s" % message)

    client.on_data(on_value)
    client.on_error(on_error)

    # ------------------------------------------------------------------
    # Write loop: drive the "in" terminals with a sine wave so something
    # visibly changes in game while we also read the "out" terminals.
    # ------------------------------------------------------------------
    start = time.monotonic()
    tick = 0
    while time.monotonic() - start < seconds:
        elapsed = time.monotonic() - start

        throttle = 0.5 + 0.5 * math.sin(elapsed)          # 0.0 .. 1.0
        gear = int(elapsed / 3.0) % 2 == 0                # toggles every 3s
        target = [10.0 * math.sin(elapsed), 64.0, 10.0 * math.cos(elapsed)]

        try:
            client.write_port(PORT_THROTTLE, throttle)
            client.write_port(PORT_GEAR, gear)
            client.write_port(PORT_TARGET, target)
        except Exception as exc:  # noqa: BLE001
            print("  [!] write failed: %s" % exc)
            return

        # Drain anything the game has pushed since the last tick.
        client.pump(timeout=0.0)

        tick += 1
        if tick % 20 == 0:
            altitude = client.read_port(PORT_ALTITUDE)
            attitude = client.read_port(PORT_ATTITUDE)
            print("  -> throttle=%.3f gear=%-5s target=%s | altitude=%s attitude=%s"
                  % (throttle, gear, target, altitude, attitude))

        time.sleep(0.1)

    print("\ntotal values received from the game: %d" % pushed["count"])
    print("known ports and their last values:")
    for entry in client.list_ports():
        print("  %-14s %-8s %s" % (entry["name"], entry["type"], entry["value"]))


def demo_raw_socket(host: str, port: int) -> None:
    """Show what the bytes on the wire look like, using a bare socket."""
    header("3. Raw protocol handshake")

    import socket

    sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    sock.settimeout(3.0)
    try:
        sock.connect((host, port))
    except OSError as exc:
        print("could not connect to %s:%d (%s)" % (host, port, exc))
        return

    try:
        # The mod immediately pushes a snapshot of every known port value.
        from cc_virtual_coms import read_message

        print("connected; reading the initial snapshot")
        for _ in range(5):
            try:
                msg_type, payload = read_message(sock)
            except Exception:  # noqa: BLE001 - timeout just ends the snapshot
                break
            print("  frame type=0x%02X payload=%s" % (msg_type, payload))

        # Ask the mod to echo a heartbeat, which proves the link is bidirectional.
        sock.sendall(pack_message(0x03))
        try:
            msg_type, payload = read_message(sock)
            print("  heartbeat reply type=0x%02X payload=%s" % (msg_type, payload))
        except Exception as exc:  # noqa: BLE001
            print("  no heartbeat reply: %s" % exc)

        # An intentional protocol violation shows the ERROR frame path.
        sock.sendall(pack_message(MSG_ERROR, {"error": "hello from the example program"}))
    finally:
        sock.close()


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--host", default="127.0.0.1", help="mod listener host (default 127.0.0.1)")
    parser.add_argument("--port", type=int, default=25585, help="mod listener port (default 25585)")
    parser.add_argument("--com", default=None, help="serial port id to target, e.g. COM1")
    parser.add_argument("--seconds", type=float, default=15.0, help="how long to stream (default 15)")
    parser.add_argument("--offline", action="store_true", help="only run the offline encoding demo")
    args = parser.parse_args()

    demo_framing()

    if args.offline:
        return 0

    header("Connecting to %s:%d" % (args.host, args.port))
    client = VirtualComsClient(args.host, args.port)

    try:
        client.connect(timeout=5.0)
    except OSError as exc:
        print("Could not connect: %s" % exc)
        print()
        print("Check that:")
        print("  * Minecraft is running with this mod loaded;")
        print("  * no other program is already using port %d;" % args.port)
        print("  * the mod logged 'Virtual coms listener started on %s:%d'."
              % (args.host, args.port))
        return 1

    print("Connected. Waiting briefly for the initial port snapshot ...")
    deadline = time.monotonic() + 1.0
    while time.monotonic() < deadline:
        client.pump(timeout=0.2)

    if client.values:
        print("The game reported %d port(s):" % len(client.values))
        for entry in client.list_ports():
            print("  %-14s %-8s %s" % (entry["name"], entry["type"], entry["value"]))
    else:
        print("No ports pushed yet - is a Virtual Serial Port Board placed and configured?")
        print("(continuing anyway; values will appear as soon as the board is active)")

    try:
        demo_streaming(client, args.seconds)
    except KeyboardInterrupt:
        print("\ninterrupted")
    finally:
        client.close()

    demo_raw_socket(args.host, args.port)

    header("Done")
    return 0


if __name__ == "__main__":
    sys.exit(main())
