package com.ccvirtualcoms.content;

import com.ccvirtualcoms.net.VirtualComsServer;
import com.verr1.synaxis.foundation.cimulink.core.signal.SignalValue;
import dan200.computercraft.api.lua.IArguments;
import dan200.computercraft.api.lua.ILuaContext;
import dan200.computercraft.api.lua.LuaException;
import dan200.computercraft.api.lua.MethodResult;
import dan200.computercraft.api.peripheral.AttachedComputerSet;
import dan200.computercraft.api.peripheral.IComputerAccess;
import dan200.computercraft.api.peripheral.IDynamicPeripheral;
import dan200.computercraft.api.peripheral.IPeripheral;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The {@code cc_virtual_coms} peripheral exposed to CC:Tweaked computers.
 *
 * <p>Method naming follows the CC:Tweaked getter/setter convention so the peripheral composes with
 * {@code peripheral.find}, {@code peripheral.getMethods} and the generic peripheral tooling.
 *
 * <p>Values crossing this boundary use the mod's four terminal types:
 * {@code real} (number), {@code boolean}, {@code vec3} ({@code {x,y,z}}) and
 * {@code quad} ({@code {x,y,z,w}}).
 */
public final class VirtualComsPeripheral implements IDynamicPeripheral {

    /** Primary peripheral type name. */
    public static final String TYPE = "cc_virtual_coms";

    /** Additional type alias, mirroring the naming used by CC: Synaxis Connector. */
    public static final String LEGACY_TYPE = "virtual_coms";

    /** Event fired on attached computers whenever an inbound terminal receives a value. */
    public static final String EVENT_INPUT = "cc_virtual_coms";

    private static final String[] METHODS = {
            // -- documented API (see README) -------------------------------------
            "readPort",          // 0
            "writePort",         // 1
            "listPorts",         // 2
            "isConnected",       // 3
            // -- configuration ---------------------------------------------------
            "getComPort",        // 4
            "setComPort",        // 5
            "addPort",           // 6
            "removePort",        // 7
            "setPortType",       // 8
            "setPortDirection",  // 9
            "clearPorts",        // 10
            // -- diagnostics -----------------------------------------------------
            "getPortInfo",       // 11
            "getStatus",         // 12
            "getConnectionCount",// 13
            "getServerAddress",  // 14
            "getLastError",      // 15
            "getDirectionLegend",// 16
    };

    private final VirtualComsBlockEntity board;
    private final AttachedComputerSet computers = new AttachedComputerSet();

    VirtualComsPeripheral(VirtualComsBlockEntity board) {
        this.board = board;
    }

    @Override
    public String getType() {
        return TYPE;
    }

    @Override
    public Set<String> getAdditionalTypes() {
        return Set.of(LEGACY_TYPE);
    }

    @Override
    public String[] getMethodNames() {
        return METHODS;
    }

    @Override
    public MethodResult callMethod(IComputerAccess computer, ILuaContext context, int method, IArguments arguments)
            throws LuaException {
        return switch (method) {
            case 0 -> this.readPort(arguments);
            case 1 -> this.writePort(arguments);
            case 2 -> MethodResult.of(luaArray(this.board.portInfoList()));
            case 3 -> MethodResult.of(VirtualComsServer.get().hasConnections());
            case 4 -> MethodResult.of(this.board.comId());
            case 5 -> MethodResult.of(this.board.setComId(arguments.getString(0)));
            case 6 -> this.addPort(arguments);
            case 7 -> MethodResult.of(this.board.removePort(arguments.getString(0)));
            case 8 -> this.setPortType(arguments);
            case 9 -> this.setPortDirection(arguments);
            case 10 -> {
                this.board.clearPorts();
                yield MethodResult.of(true);
            }
            case 11 -> MethodResult.of(this.board.portInfo(this.requirePort(arguments.getString(0))));
            case 12 -> MethodResult.of(this.board.status());
            case 13 -> MethodResult.of(VirtualComsServer.get().connectionCount());
            case 14 -> MethodResult.of(VirtualComsServer.get().host() + ":" + VirtualComsServer.get().port());
            case 15 -> {
                String error = VirtualComsServer.get().lastError();
                yield MethodResult.of(error == null ? "" : error);
            }
            case 16 -> MethodResult.of("in = produced outside the game, readable with readPort; "
                    + "out = produced inside the game, writable with writePort");
            default -> throw new LuaException("Unknown method");
        };
    }

    // ------------------------------------------------------------------ documented API

    /** {@code readPort(name) -> value, type} */
    private MethodResult readPort(IArguments arguments) throws LuaException {
        String name = arguments.getString(0);
        SerialPort port = this.requirePort(name);
        SignalValue value = this.board.readForComputer(name);
        return MethodResult.of(PortValues.toLua(value), port.type().serializedName());
    }

    /** {@code writePort(name, value) -> true} */
    private MethodResult writePort(IArguments arguments) throws LuaException {
        String name = arguments.getString(0);
        if (arguments.count() < 2) {
            throw new LuaException("writePort requires a value");
        }
        SerialPort port = this.requirePort(name);
        SignalValue value = PortValues.fromLua(port.type(), arguments.get(1));
        this.board.writeFromComputer(name, value);
        return MethodResult.of(true);
    }

    // ------------------------------------------------------------------ configuration

    /** {@code addPort(name, type, direction) -> info} */
    private MethodResult addPort(IArguments arguments) throws LuaException {
        String name;
        String typeName;
        String directionName;

        if (arguments.count() >= 3) {
            name = arguments.getString(0);
            typeName = arguments.getString(1);
            directionName = arguments.getString(2);
        } else if (arguments.count() == 2) {
            name = null;
            typeName = arguments.getString(0);
            directionName = arguments.getString(1);
        } else {
            throw new LuaException("Expected addPort([name], type, direction)");
        }

        PortType type = this.requireType(typeName);
        PortDirection direction = this.requireDirection(directionName);
        SerialPort port = this.board.addPortOrGenerate(name, type, direction);
        return MethodResult.of(this.board.portInfo(port));
    }

    /** {@code setPortType(name, type) -> true} */
    private MethodResult setPortType(IArguments arguments) throws LuaException {
        String name = arguments.getString(0);
        PortType type = this.requireType(arguments.getString(1));
        return MethodResult.of(this.board.updatePort(name, type, null));
    }

    /** {@code setPortDirection(name, direction) -> true} */
    private MethodResult setPortDirection(IArguments arguments) throws LuaException {
        String name = arguments.getString(0);
        PortDirection direction = this.requireDirection(arguments.getString(1));
        return MethodResult.of(this.board.updatePort(name, null, direction));
    }

    private SerialPort requirePort(String name) throws LuaException {
        return this.board.port(name)
                .orElseThrow(() -> new LuaException("Unknown port '" + name + "'"));
    }

    private PortType requireType(String name) throws LuaException {
        PortType type = PortType.byName(name);
        if (type == null) {
            throw new LuaException("Unknown port type '" + name + "'; expected real, boolean, vec3 or quad");
        }
        return type;
    }

    private PortDirection requireDirection(String name) throws LuaException {
        PortDirection direction = PortDirection.byName(name);
        if (direction == null) {
            throw new LuaException("Unknown port direction '" + name + "'; expected in or out");
        }
        return direction;
    }

    // ------------------------------------------------------------------ IPeripheral plumbing

    @Override
    public void attach(IComputerAccess computer) {
        this.computers.add(computer);
    }

    @Override
    public void detach(IComputerAccess computer) {
        this.computers.remove(computer);
    }

    @Override
    public Object getTarget() {
        return this.board;
    }

    @Override
    public boolean equals(IPeripheral other) {
        return other instanceof VirtualComsPeripheral peripheral && peripheral.board == this.board;
    }

    /** Notifies every attached computer that an inbound terminal changed. */
    void queueInputEvent(String portName, SignalValue value) {
        this.computers.queueEvent(EVENT_INPUT, portName, PortValues.toLua(value));
    }

    private static Map<Integer, Object> luaArray(List<Map<String, Object>> entries) {
        Map<Integer, Object> result = new LinkedHashMap<>();
        int index = 1;
        for (Map<String, Object> entry : entries) {
            result.put(index++, entry);
        }
        return result;
    }
}
