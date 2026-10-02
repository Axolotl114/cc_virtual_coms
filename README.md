# CC Synaxis Virtual Coms

把 Minecraft 电路、CC:Tweaked 电脑和游戏外的 Python 程序,通过本地 TCP 套接字连在一起。

- **作者**:Axolotl114
- **版本**:0.0.1
- **MC / 加载器**:1.21.1 / NeoForge 21.1.236

## 前置模组

| 模组 | 用途 |
| --- | --- |
| Synaxis | Cimulink 电路 |
| CC:Tweaked | 电脑与外设 |
| Create / Sable / LDLib2 | Synaxis 的依赖链 |

## 方块与物品

### 虚拟串口电路板 `virtual_coms_board`

唯一添加的方块(同时是物品),创造模式物品栏「CC Synaxis 虚拟串口」标签页中获取。

- **放置**:像 Synaxis 其他电路板一样,贴附在你点击的那个面上(`facing` 六个方向)。
- **配置界面**:**空手右键**打开(手持任何物品右键不会打开,交互会交给物品本身,因此可以正常用 Synaxis 工具枪连线)。
- **界面**:设置串口号(`COM1` 等);增删端子;切换端子类型 `real` / `boolean` / `vec3` / `quad`;切换方向 `in` / `out`;
  查看每个端子的当前值与最后写入者(`python` / `cc` / `synaxis`);开启「保持最后值」。
- **接线**:注册为 Cimulink 组件 `cc_virtual_coms:serial_board`。`in` 端子是电路输入,`out` 端子是电路输出。

### 端子方向

| 方向 | 含义 |
| --- | --- |
| `out` | 电路**读取**该端子 —— 从 Python / 电脑写入它来驱动电路 |
| `in` | 电路**驱动**该端子 —— 从 Python / 电脑读取它来观测电路 |

读写权限不受方向限制。但若某端子正被电路驱动(值列显示 `[synaxis]`),
写入的值会在下一个游戏刻被电路覆盖。

## Python API

文件 `python/cc_virtual_coms.py`,纯标准库,Python 3.8+。

```python
from cc_virtual_coms import Coms

coms = Coms()                        # 连上 127.0.0.1:25585

coms.send("drive", 0.25)             # 打包 + 发送
coms.send("gear", True)              # 类型自动推断
coms.send("target", [1.0, 64.0, 2.0])   # vec3

port, ptype, value = coms.recv()     # 阻塞接收并解包
print(coms.get("sense"))             # 读本地缓存的最新值

for port, ptype, value in coms:      # 持续接收推送
    print(port, ptype, value)
```

`Coms` 主要方法:

| 分类 | 方法 |
| --- | --- |
| 打包 | `pack(port, value, port_type=None)` / `pack_payload(...)` |
| 发送 | `send(frame)` / `send(port, value)` / `write(...)` / `heartbeat()` |
| 接收 | `recv(timeout=None)` / `poll(timeout=0)` / `recv_all(...)` / `for ... in coms` |
| 读取 | `get(port, default=None)` / `coms[port]` / `read(port, default=None, wait=None)` |
| 状态 | `connected` / `ports` / `list_ports()` / `out_ports()` / `in_ports()` / `port_com(p)` / `port_direction(p)` / `last_error` / `close()` |

要点:

- `Coms()` 自动连接并启动后台接收线程,所以 `recv()` 阻塞时 `send()` 仍可用;不需要时用 `Coms(background=False)` + `poll()`。
- 端子类型由游戏决定,优先用上报的类型;不符时会报错,可用 `port_type=` 覆盖。
- 每次推送都带串口号,因此 `send("spark", 1.0)` 会自动发到对应板子,多板子无需手工指定。
- **只创建一条连接并复用**,不要在循环里反复 `Coms()`(会撞上连接数上限,默认 64)。

底层函数(需要自己控制 socket 时):`pack_message` / `unpack_message` / `read_message` /
`pack_port_value` / `unpack_port_value` / `infer_port_type` /
`pack_binary_port_value` / `unpack_binary_port_value`(紧凑二进制编码),
以及常量 `MAGIC` / `VERSION` / `HEADER_SIZE` / `MSG_*` / `PORT_TYPES`。
更底层的 `VirtualComsClient` 类提供 `write_port` / `pump` / `listen` / `on_data` 等接口。

## Lua API

在 CC:Tweaked 电脑上:

```lua
local coms = peripheral.find("cc_virtual_coms")   -- 别名 "virtual_coms"
```

```lua
-- 读写
local value, ptype = coms.readPort("altitude")
coms.writePort("throttle", 42.5)
coms.writePort("gear", true)
coms.writePort("target", {1.0, 2.0, 3.0})         -- vec3,也接受 {x=,y=,z=}
coms.writePort("attitude", {0.0, 0.0, 0.0, 1.0})  -- quad

-- 端口列表与连接状态
for _, p in ipairs(coms.listPorts()) do
  print(p.name, p.type, p.direction, p.value)
end
print(coms.isConnected())
```

| 分类 | 方法 |
| --- | --- |
| 核心 | `readPort(name)` / `writePort(name, value)` / `listPorts()` / `isConnected()` |
| 配置 | `getComPort()` / `setComPort(id)` / `addPort([name], type, direction)` / `removePort(name)` / `setPortType(name, type)` / `setPortDirection(name, dir)` / `clearPorts()` |
| 诊断 | `getPortInfo(name)` / `getStatus()` / `getConnectionCount()` / `getServerAddress()` / `getLastError()` / `getDirectionLegend()` |

`type` 取 `"real"` / `"boolean"` / `"vec3"` / `"quad"`,`direction` 取 `"in"` / `"out"`。

事件:某个 `in` 端子收到新值时,已连接的电脑会收到事件。

```lua
local event, port, value = os.pullEvent("cc_virtual_coms")
```

## 从源码构建

需要 JDK 21。依赖由 `libs/` 目录中的本地 jar 提供,无需访问模组仓库,也不会被打包进产物。

```bash
./gradlew build          # 产物在 build/libs/
./gradlew runClient      # 带模组的客户端
./gradlew runServer      # 服务端
```

## 许可

MIT
