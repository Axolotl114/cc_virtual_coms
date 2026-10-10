# CC Synaxis Virtual Coms

Virtual serial ports that bridge Minecraft circuits, CC:Tweaked computers and
out-of-game Python programs over a local TCP socket.

- **Author**: Axolotl114
- **Version**: 0.0.1
- **MC / Loader**: 1.21.1 / NeoForge 21.1.236

## Requirements

| Mod | Purpose |
| --- | --- |
| Synaxis | Cimulink circuits |
| CC:Tweaked | Computers and peripherals |
| Create / Sable / LDLib2 | Synaxis dependency chain |

## Block and item

### Virtual Serial Port Board `virtual_coms_board`

The only block this mod adds (which is also its item). Found in its own
creative tab.

- **Placement**: mounts on the face you click, like other Synaxis circuit
  boards (`facing` has all six directions).
- **Configuration screen**: right-click with an **empty hand** to open it.
  Right-clicking while holding any item does not open it — the interaction is
  passed to the item instead, so the Synaxis tool gun still works normally for
  wiring the board into a circuit.
- **What the screen does**: set the serial port id (`COM1` etc.); add and
  remove ports; cycle a port's type through `real` / `boolean` / `vec3` /
  `quad`; cycle its direction through `in` / `out`; see each port's current
  value and who last wrote it (`python` / `cc` / `synaxis`); toggle
  "keep last value".
- **Wiring**: registers as the Cimulink component
  `cc_virtual_coms:serial_board`. `in` ports become circuit inputs, `out`
  ports become circuit outputs.

### Port direction

| Direction | Meaning |
| --- | --- |
| `out` | The circuit **reads** this port — write to it from Python or Lua to drive the circuit |
| `in` | The circuit **drives** this port — read it from Python or Lua to observe the circuit |

Read and write are not restricted by direction. If a port is currently being
driven by the circuit (the value column shows `[synaxis]`), a value you write
is overwritten on the next game tick.

## Python API

File `python/cc_virtual_coms.py`. Standard library only, Python 3.8+.

```python
from cc_virtual_coms import Coms

coms = Coms()                              # connects to 127.0.0.1:25585

coms.send("drive", 0.25)                   # pack + send
coms.send("gear", True)                    # type inferred
coms.send("target", [1.0, 64.0, 2.0])      # vec3

port, ptype, value = coms.recv()           # blocking receive, unpacked
print(coms.get("sense"))                   # latest cached value

for port, ptype, value in coms:            # stream incoming values
    print(port, ptype, value)
```

Main `Coms` methods:

| Group | Methods |
| --- | --- |
| Pack | `pack(port, value, port_type=None)` / `pack_payload(...)` |
| Send | `send(frame)` / `send(port, value)` / `write(...)` / `heartbeat()` |
| Receive | `recv(timeout=None)` / `poll(timeout=0)` / `recv_all(...)` / `for ... in coms` |
| Read | `get(port, default=None)` / `coms[port]` / `read(port, default=None, wait=None)` |
| Status | `connected` / `ports` / `list_ports()` / `out_ports()` / `in_ports()` / `port_com(p)` / `port_direction(p)` / `last_error` / `close()` |

Notes:

- `Coms()` connects automatically and starts a background receiver thread, so
  `send()` still works while `recv()` blocks. Use `Coms(background=False)` plus
  `poll()` if you don't want a thread.
- Port types are decided by the game — prefer the reported type. A mismatch is
  reported clearly and can be overridden with `port_type=`.
- Every push carries its serial port id, so `send("spark", 1.0)` routes to the
  board that owns `spark`; multiple boards need no manual targeting.
- **Create one connection and reuse it.** Do not call `Coms()` in a loop — that
  leaks TCP connections until you hit the mod's limit (64 by default).

Lower-level helpers, if you want to drive a socket yourself: `pack_message`,
`unpack_message`, `read_message`, `pack_port_value`, `unpack_port_value`,
`infer_port_type`, `pack_binary_port_value`, `unpack_binary_port_value`
(compact binary encoding), plus the constants `MAGIC`, `VERSION`,
`HEADER_SIZE`, `MSG_*`, `PORT_TYPES`. The underlying `VirtualComsClient` class
exposes `write_port`, `pump`, `listen`, `on_data` and friends.

## Lua API

On a CC:Tweaked computer:

```lua
local coms = peripheral.find("cc_virtual_coms")   -- alias: "virtual_coms"
```

```lua
-- read and write
local value, ptype = coms.readPort("altitude")
coms.writePort("throttle", 42.5)
coms.writePort("gear", true)
coms.writePort("target", {1.0, 2.0, 3.0})         -- vec3, also {x=,y=,z=}
coms.writePort("attitude", {0.0, 0.0, 0.0, 1.0})  -- quad

-- port list and connection state
for _, p in ipairs(coms.listPorts()) do
  print(p.name, p.type, p.direction, p.value)
end
print(coms.isConnected())
```

| Group | Methods |
| --- | --- |
| Core | `readPort(name)` / `writePort(name, value)` / `listPorts()` / `isConnected()` |
| Configure | `getComPort()` / `setComPort(id)` / `addPort([name], type, direction)` / `removePort(name)` / `setPortType(name, type)` / `setPortDirection(name, dir)` / `clearPorts()` |
| Diagnose | `getPortInfo(name)` / `getStatus()` / `getConnectionCount()` / `getServerAddress()` / `getLastError()` / `getDirectionLegend()` |

`type` is `"real"` / `"boolean"` / `"vec3"` / `"quad"`; `direction` is `"in"` /
`"out"`.

Events: when an `in` port receives a new value, attached computers get an
event.

```lua
local event, port, value = os.pullEvent("cc_virtual_coms")
```

## Building from source

Requires JDK 21. Dependencies are supplied as local jars from `libs/`, so the
build needs no mod repository access, and they are not bundled into the output.

```bash
./gradlew build          # output in build/libs/
./gradlew runClient      # client with the mod
./gradlew runServer      # server
```

## Credits

- Built on Synaxis, CC:Tweaked and Create.

## License

MIT — see [LICENSE](LICENSE).
