package com.ccvirtualcoms.content;

import com.ccvirtualcoms.synaxis.VirtualComsComponent;
import com.ccvirtualcoms.synaxis.VirtualComsPlantPort;
import com.ccvirtualcoms.net.VirtualComsDevice;
import com.ccvirtualcoms.net.VirtualComsServer;
import com.verr1.synaxis.foundation.cimulink.core.component.ComponentSchema;
import com.verr1.synaxis.foundation.cimulink.core.component.ExecutionDomain;
import com.verr1.synaxis.foundation.cimulink.core.signal.PortDef;
import com.verr1.synaxis.foundation.cimulink.core.signal.SignalValue;
import com.verr1.synaxis.foundation.cimulink.game.endpoint.CimulinkEndpoint;
import com.verr1.synaxis.foundation.cimulink.game.endpoint.CimulinkEndpointProvider;
import com.verr1.synaxis.foundation.cimulink.game.endpoint.EndpointAddress;
import com.verr1.synaxis.foundation.cimulink.game.endpoint.EndpointDefinition;
import com.verr1.synaxis.foundation.cimulink.game.endpoint.EndpointId;
import com.verr1.synaxis.foundation.cimulink.game.endpoint.EndpointRuntimeBinding;
import com.verr1.synaxis.foundation.cimulink.game.endpoint.EndpointUiHints;
import com.verr1.synaxis.foundation.cimulink.game.runtime.CimulinkLevelRuntime;
import com.verr1.synaxis.foundation.cimulink.game.runtime.CimulinkWorldRuntimes;
import com.verr1.synaxis.foundation.physics.SynaxisPhysics;
import dan200.computercraft.api.lua.LuaException;
import dan200.computercraft.api.peripheral.IPeripheral;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Pattern;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/**
 * The block entity behind the virtual serial port board.
 *
 * <p>It plays three roles at once:
 *
 * <ol>
 *   <li>A <b>Synaxis Cimulink endpoint</b> - its terminals appear as real circuit ports via
 *       {@link VirtualComsComponent} and {@link VirtualComsPlantPort}.</li>
 *   <li>A <b>CC:Tweaked peripheral</b> - {@link #peripheral()} exposes {@code readPort},
 *       {@code writePort}, {@code listPorts} and friends to attached computers.</li>
 *   <li>A <b>virtual serial device</b> - registered with {@link VirtualComsServer} so an
 *       out-of-game Python program can exchange values with it.</li>
 * </ol>
 */
public class VirtualComsBlockEntity extends BlockEntity
        implements CimulinkEndpoint, CimulinkEndpointProvider, VirtualComsDevice {

    // ------------------------------------------------------------------ NBT keys

    private static final String COM_ID_TAG = "ComId";
    private static final String ENDPOINT_ID_TAG = "EndpointId";
    private static final String PORTS_TAG = "Ports";
    private static final String VALUES_TAG = "Values";
    private static final String LAST_WRITE_TICKS_TAG = "LastWriteTicks";
    private static final String LAST_WRITERS_TAG = "LastWriters";
    private static final String STATUS_TAG = "Status";
    private static final String REVISION_TAG = "Revision";

    /** Writer identities, used to decide who owns a terminal's current value. */
    public static final String SOURCE_SYNAXIS = "synaxis";
    public static final String SOURCE_PYTHON = "python";
    public static final String SOURCE_COMPUTER = "cc";
    public static final String SOURCE_DEFAULT = "default";

    /**
     * How often (in ticks) value-only changes are mirrored to watching clients.
     *
     * <p>This only affects the in-game GUI, never the value store: a write is applied immediately and
     * a CC computer or the circuit sees it right away. It is kept short so that staring at the GUI
     * while a fast producer runs still shows something close to live.
     */
    private static final long CLIENT_VALUE_SYNC_INTERVAL = 2L;

    private static final EndpointRuntimeBinding BINDING = EndpointRuntimeBinding.allowing(new ExecutionDomain[0]);

    private static final Pattern VALID_COM_ID = Pattern.compile("[A-Za-z0-9_.:-]{1,32}");

    public static final String DEFAULT_COM_ID = "COM1";

    // ------------------------------------------------------------------ state

    private final LinkedHashMap<String, SerialPort> ports = new LinkedHashMap<>();
    private final ConcurrentMap<String, SignalValue> values = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Long> lastWriteTicks = new ConcurrentHashMap<>();

    /**
     * Who last wrote each terminal. A Cimulink input that the graph stops driving is normally reset
     * to its default, but that must only happen when the graph itself was the writer - otherwise an
     * out-of-game program's value would be wiped on the very next game tick.
     */
    private final ConcurrentMap<String, String> lastWriters = new ConcurrentHashMap<>();

    private final VirtualComsPeripheral peripheral = new VirtualComsPeripheral(this);

    private String comId = DEFAULT_COM_ID;
    private String status = "Ready";
    private int revision;

    private EndpointId endpointId = EndpointId.random();
    private boolean endpointRegistered;
    private boolean endpointDirty = true;
    private long lastClientValueSyncTick = Long.MIN_VALUE;

    /**
     * Whether the terminal list has been announced to connected out-of-game programs since the board
     * was loaded. Announcement cannot happen in {@link #setLevel} because the terminal list only
     * exists once NBT has been read, so it is deferred to the first server tick instead.
     */
    private boolean portsAnnounced;

    /**
     * Set while a client-sync task is queued for this board, so that a producer running at hundreds
     * of hertz collapses into one pending task instead of flooding the server task queue.
     */
    private final java.util.concurrent.atomic.AtomicBoolean clientSyncPending =
            new java.util.concurrent.atomic.AtomicBoolean();

    public VirtualComsBlockEntity(BlockPos pos, BlockState state) {
        super(CCVirtualComsRegistry.VIRTUAL_COMS_BE.get(), pos, state);
    }

    // ------------------------------------------------------------------ peripheral / device

    /** The CC:Tweaked peripheral view of this board. */
    public IPeripheral peripheral() {
        return this.peripheral;
    }

    @Override
    public String comId() {
        return this.comId;
    }

    @Override
    public boolean isActive() {
        return !isRemoved() && this.level != null;
    }

    @Override
    public synchronized List<SerialPort> ports() {
        return List.copyOf(this.ports.values());
    }

    @Override
    public SignalValue readValue(String portName) {
        SerialPort port = portName == null ? null : this.ports.get(portName);
        return port == null ? null : this.valueOrDefault(port);
    }

    @Override
    public boolean writeValue(String portName, SignalValue value, String source) {
        SerialPort port = portName == null ? null : this.ports.get(portName);
        if (port == null || value == null || !port.accepts(value)) {
            return false;
        }
        // Every terminal is writable from outside; direction only chooses the Cimulink port role.
        // A terminal whose value the circuit is actively driving will simply be overwritten by the
        // circuit again on the next tick, which the GUI shows as a different writer.
        this.store(port, value, source);
        return true;
    }

    // ------------------------------------------------------------------ configuration

    public String status() {
        return this.status;
    }

    /** Monotonic counter bumped whenever the configuration changes; the GUI uses it to refresh. */
    public int revision() {
        return this.revision;
    }

    /**
     * Sets the logical serial port identifier used to address this board from outside the game.
     *
     * @return {@code true} when the id was accepted
     */
    public boolean setComId(String requested) {
        if (requested == null) {
            return false;
        }
        String trimmed = requested.trim();
        if (!VALID_COM_ID.matcher(trimmed).matches()) {
            this.setStatus("Invalid com id '" + requested + "'");
            return false;
        }
        if (trimmed.equals(this.comId)) {
            return true;
        }
        this.comId = trimmed;
        this.setStatus("Com id set to " + trimmed);
        this.markConfigurationChanged();
        return true;
    }

    /** Validates a candidate com id without applying it. */
    public static boolean isValidComId(String value) {
        return value != null && VALID_COM_ID.matcher(value.trim()).matches();
    }

    public synchronized Optional<SerialPort> port(String name) {
        return Optional.ofNullable(name == null ? null : this.ports.get(name));
    }

    /**
     * Adds a terminal.
     *
     * @throws LuaException when the name is invalid or already taken
     */
    public synchronized SerialPort addPort(String name, PortType type, PortDirection direction) throws LuaException {
        String normalized = requirePortName(name);
        if (this.ports.containsKey(normalized)) {
            throw new LuaException("Port '" + normalized + "' already exists");
        }
        SerialPort port = new SerialPort(normalized, type, direction);
        this.ports.put(normalized, port);
        this.values.put(normalized, port.defaultValue());
        this.setStatus("Added " + normalized);
        this.markConfigurationChanged();
        return port;
    }

    /** Adds a terminal, generating a unique name when {@code requestedName} is blank. */
    public synchronized SerialPort addPortOrGenerate(String requestedName, PortType type, PortDirection direction)
            throws LuaException {
        String name = requestedName == null || requestedName.isBlank() ? this.generatePortName() : requestedName;
        return this.addPort(name, type, direction);
    }

    /** Removes a terminal. */
    public synchronized boolean removePort(String name) throws LuaException {
        String normalized = requirePortName(name);
        if (this.ports.remove(normalized) == null) {
            return false;
        }
        this.values.remove(normalized);
        this.lastWriteTicks.remove(normalized);
        this.setStatus("Removed " + normalized);
        this.markConfigurationChanged();
        return true;
    }

    /** Replaces the type and/or direction of an existing terminal. */
    public synchronized boolean updatePort(String name, @Nullable PortType newType, @Nullable PortDirection newDirection)
            throws LuaException {
        String normalized = requirePortName(name);
        SerialPort existing = this.ports.get(normalized);
        if (existing == null) {
            return false;
        }
        SerialPort updated = existing;
        if (newType != null) {
            updated = updated.withType(newType);
        }
        if (newDirection != null) {
            updated = updated.withDirection(newDirection);
        }
        if (updated.equals(existing)) {
            return true;
        }
        this.ports.put(normalized, updated);
        // A type change invalidates the stored value, so reset to the new default.
        this.values.put(normalized, updated.defaultValue());
        this.setStatus("Updated " + normalized);
        this.markConfigurationChanged();
        return true;
    }

    /** Removes every terminal. */
    public synchronized void clearPorts() {
        this.ports.clear();
        this.values.clear();
        this.lastWriteTicks.clear();
        this.setStatus("All ports cleared");
        this.markConfigurationChanged();
    }

    /**
     * Replaces the whole configuration from a validated client snapshot.
     *
     * <p>Values whose terminal type changed, or whose terminal disappeared, are dropped and reset to
     * the type default so the store can never hold a value the schema disagrees with.
     *
     * @return {@code null} on success, otherwise a human readable reason the change was rejected
     */
    public String applyConfiguration(@Nullable String newComId, List<SerialPort> newPorts) {
        if (!isValidComId(newComId)) {
            return "Invalid serial port id '" + newComId + "'";
        }
        List<SerialPort> safePorts = newPorts == null ? List.of() : newPorts;
        if (safePorts.size() > 256) {
            return "Too many terminals (limit 256)";
        }

        Set<String> seen = new LinkedHashSet<>();
        for (SerialPort port : safePorts) {
            if (port == null) {
                continue;
            }
            if (!SerialPort.isValidName(port.name())) {
                return "Invalid terminal name '" + port.name() + "'";
            }
            if (!seen.add(port.name())) {
                return "Duplicate terminal name '" + port.name() + "'";
            }
        }

        this.comId = newComId.trim();
        synchronized (this) {
            this.ports.clear();
            for (SerialPort port : safePorts) {
                if (port != null) {
                    this.ports.put(port.name(), port);
                }
            }
            this.values.keySet().retainAll(this.ports.keySet());
            this.lastWriteTicks.keySet().retainAll(this.ports.keySet());
            this.lastWriters.keySet().retainAll(this.ports.keySet());
            for (SerialPort port : this.ports.values()) {
                SignalValue existing = this.values.get(port.name());
                if (existing == null || !port.accepts(existing)) {
                    this.values.put(port.name(), port.defaultValue());
                    this.lastWriters.remove(port.name());
                }
            }
        }

        this.setStatus("Configuration applied");
        this.markConfigurationChanged();
        return null;
    }

    private synchronized String generatePortName() {
        int index = 1;
        while (this.ports.containsKey("port_" + index)) {
            index++;
        }
        return "port_" + index;
    }

    private static String requirePortName(String name) throws LuaException {
        try {
            return SerialPort.normalizeName(name);
        } catch (IllegalArgumentException ex) {
            throw new LuaException(ex.getMessage());
        }
    }

    // ------------------------------------------------------------------ CC facing access

    /** Reads a terminal on behalf of a CC:Tweaked computer. Every terminal is readable. */
    public SignalValue readForComputer(String name) throws LuaException {
        return this.valueOrDefault(this.requirePort(name));
    }

    /** Writes a terminal on behalf of a CC:Tweaked computer. Every terminal is writable. */
    public void writeFromComputer(String name, SignalValue value) throws LuaException {
        SerialPort port = this.requirePort(name);
        if (!port.accepts(value)) {
            throw new LuaException("Value does not match port type '" + port.type().serializedName() + "'");
        }
        this.store(port, value, SOURCE_COMPUTER);
    }

    /** The tick at which a terminal last changed, or {@code -1}. */
    public long lastWriteTick(String name) throws LuaException {
        return this.lastWriteTicks.getOrDefault(this.requirePort(name).name(), -1L);
    }

    private SerialPort requirePort(String name) throws LuaException {
        String normalized = requirePortName(name);
        SerialPort port = this.ports.get(normalized);
        if (port == null) {
            throw new LuaException("Unknown port '" + normalized + "'");
        }
        return port;
    }

    // ------------------------------------------------------------------ Synaxis facing access

    /** Called from {@link VirtualComsComponent#evaluate} to feed the Cimulink graph. */
    public SignalValue readForSynaxis(String name) {
        SerialPort port = this.ports.get(name);
        if (port == null) {
            return null;
        }
        return this.valueOrDefault(port);
    }

    /**
     * Called from {@link VirtualComsComponent#step} and the plant port when the graph writes.
     *
     * <p>An input port that nothing in the circuit drives still reports its <em>default</em> value on
     * every tick. Treating that idle reading as "the circuit is driving this terminal" would
     * silently erase whatever an out-of-game program just wrote - the value would appear to be
     * accepted and then vanish on the next game tick.
     *
     * <p>So a value equal to the terminal's default only takes ownership when the circuit already
     * owned the terminal. The moment the circuit produces anything that is not the default, it
     * takes over; conversely an external write immediately reclaims ownership, which lets a Python
     * program override a circuit that is parked on a constant.
     */
    public void writeFromSynaxis(String name, SignalValue value) {
        SerialPort port = this.ports.get(name);
        if (port == null || value == null || !port.accepts(value)) {
            return;
        }

        // An 'out' terminal is by definition one the circuit READS. If the graph (or a plant port
        // handshake) ever tries to write it back, that is a wiring mistake on the circuit side and
        // must not be allowed to clobber the value an out-of-game program is holding. This is the
        // guarantee that makes 'out' terminals safe to drive from Python.
        if (port.direction() == PortDirection.OUT) {
            return;
        }

        boolean circuitOwns = SOURCE_SYNAXIS.equals(this.lastWriters.get(name));
        if (!circuitOwns && Objects.equals(value, port.defaultValue())) {
            return;
        }

        this.store(port, value, SOURCE_SYNAXIS);
        this.peripheral.queueInputEvent(port.name(), value);
    }

    /**
     * Resets an input terminal to its default when the graph stops driving it.
     *
     * <p>Crucially this only applies to values the <em>graph</em> produced. A value written by an
     * out-of-game Python program or by a computer belongs to that writer and must survive the
     * circuit being silent, otherwise every external write would be undone on the next game tick.
     */
    public void clearMissingSynaxisInput(String name) {
        SerialPort port = this.ports.get(name);
        if (port == null || port.direction() == PortDirection.OUT || port.saveLastValue()) {
            return;
        }
        if (!SOURCE_SYNAXIS.equals(this.lastWriters.get(name))) {
            return;
        }
        SignalValue current = this.values.get(name);
        SignalValue fallback = port.defaultValue();
        if (!Objects.equals(current, fallback)) {
            this.values.put(name, fallback);
            this.lastWriters.put(name, SOURCE_SYNAXIS);
            Level level = this.getLevel();
            this.lastWriteTicks.put(name, level == null ? -1L : level.getGameTime());
            this.setStatus(SOURCE_SYNAXIS + " withdrew " + name);
            this.requestClientSync();
            this.peripheral.queueInputEvent(port.name(), fallback);
        }
    }

    // ------------------------------------------------------------------ schema / endpoint

    /** Builds the Cimulink port layout from the configured terminals. */
    public synchronized ComponentSchema schema() {
        List<PortDef> inputs = new ArrayList<>();
        List<PortDef> outputs = new ArrayList<>();
        for (SerialPort port : this.ports.values()) {
            if (port.direction() == PortDirection.IN) {
                inputs.add(port.portDef());
            } else {
                outputs.add(port.portDef());
            }
        }
        return ComponentSchema.of(inputs, outputs);
    }

    /** Short human readable labels for the Synaxis circuit editor. */
    public synchronized Map<String, String> portLabels() {
        Map<String, String> labels = new LinkedHashMap<>();
        for (SerialPort port : this.ports.values()) {
            labels.put(port.name(), port.name() + " [" + port.direction().serializedName()
                    + "] " + port.type().serializedName());
        }
        return labels;
    }

    /** Per-port metadata for the Lua {@code listPorts} call and the Python handshake. */
    public synchronized Map<String, Object> portInfo(SerialPort port) {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("name", port.name());
        info.put("type", port.type().serializedName());
        info.put("direction", port.direction().serializedName());
        info.put("saveLastValue", port.saveLastValue());
        info.put("cimulinkPort", port.direction().cimulinkPort());
        info.put("value", PortValues.toLua(this.valueOrDefault(port)));
        info.put("lastWriteTick", this.lastWriteTicks.getOrDefault(port.name(), -1L));
        info.put("lastWriter", this.lastWriters.getOrDefault(port.name(), ""));
        return info;
    }

    /** Every port's metadata, in declaration order. */
    public synchronized List<Map<String, Object>> portInfoList() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (SerialPort port : this.ports.values()) {
            result.add(this.portInfo(port));
        }
        return result;
    }

    /** Instantiates the Synaxis plant port for this board. */
    public VirtualComsPlantPort createPlantPort(EndpointId id, String requestedName) {
        String name = requestedName != null && !requestedName.isBlank() ? requestedName : "Virtual Coms " + this.comId;
        return new VirtualComsPlantPort(this, id, name);
    }

    @Override
    public CimulinkEndpoint endpoint() {
        return this;
    }

    @Override
    public EndpointId id() {
        return this.endpointId;
    }

    @Override
    public EndpointAddress address() {
        Level current = this.getLevel();
        return current == null
                ? EndpointAddress.of(Level.OVERWORLD, this.getBlockPos())
                : EndpointAddress.of(current, this.getBlockPos())
                        .withBody(SynaxisPhysics.bodyAt(current, this.getBlockPos()));
    }

    @Override
    public String displayName() {
        return "Virtual Coms " + this.comId;
    }

    @Override
    public EndpointDefinition definition() {
        ComponentSchema schema = this.schema();
        return new EndpointDefinition(
                VirtualComsComponent.ID,
                new VirtualComsComponent.Config(this, schema),
                schema,
                new EndpointUiHints(this.portLabels()));
    }

    @Override
    public EndpointRuntimeBinding binding() {
        return BINDING;
    }

    // ------------------------------------------------------------------ ticking

    /**
     * Advances the board once per server tick. Driven by {@link VirtualComsServer#tickDevices()},
     * not by a block entity ticker, so a board placed into an already-loaded chunk behaves the same
     * as one that was loaded from disk.
     */
    @Override
    public void serverTick() {
        if (this.level == null || this.level.isClientSide()) {
            return;
        }

        // Announce the terminal list once the board is fully loaded. This is deferred until the list
        // is non-empty because setLevel() - and therefore register() - runs BEFORE NBT is read, so at
        // that moment the board still looks empty and would announce nothing.
        if (!this.portsAnnounced && !this.ports.isEmpty()) {
            this.portsAnnounced = true;
            VirtualComsServer.get().announcePorts(this);
        }

        if (this.level instanceof ServerLevel serverLevel) {
            CimulinkLevelRuntime runtime = CimulinkWorldRuntimes.forLevel(serverLevel);
            if (!this.endpointRegistered) {
                runtime.registerEndpoint(this);
                this.endpointRegistered = true;
                this.endpointDirty = false;
            } else if (this.endpointDirty) {
                runtime.refreshEndpoint(this);
                this.endpointDirty = false;
            }
        }
    }

    // ------------------------------------------------------------------ lifecycle

    @Override
    public void setLevel(Level level) {
        super.setLevel(level);
        this.portsAnnounced = false;
        if (level != null && !level.isClientSide()) {
            VirtualComsServer.get().register(this);
        }
    }

    @Override
    public void setRemoved() {
        this.unregisterEndpoint();
        VirtualComsServer.get().unregister(this);
        super.setRemoved();
    }

    @Override
    public void onChunkUnloaded() {
        this.unregisterEndpoint();
        VirtualComsServer.get().unregister(this);
        super.onChunkUnloaded();
    }

    private void unregisterEndpoint() {
        if (this.endpointRegistered && this.level instanceof ServerLevel serverLevel) {
            CimulinkWorldRuntimes.forLevel(serverLevel).unregisterEndpoint(this.endpointId);
        }
        this.endpointRegistered = false;
    }

    // ------------------------------------------------------------------ value plumbing

    private SignalValue valueOrDefault(SerialPort port) {
        SignalValue value = this.values.get(port.name());
        return value == null ? port.defaultValue() : value;
    }

    /**
     * Stores a value and publishes it outward.
     *
     * <p>This runs on whatever thread produced the value, which for an out-of-game write is a socket
     * thread. That is deliberate: gating the store on the server tick would cap an out-of-game write
     * at the tick rate and add up to a full tick of latency, which is exactly what a real-time
     * control loop cannot afford.
     *
     * <p>Everything stored lives in concurrent maps, so the store itself is safe from any thread.
     * Only the side effects that touch level state ({@code setChanged}, block updates) are handed
     * back to the server thread, coalesced so a 60 Hz producer cannot flood the queue.
     */
    private void store(SerialPort port, SignalValue value, String source) {
        this.values.put(port.name(), value);
        this.lastWriters.put(port.name(), source);

        Level current = this.getLevel();
        this.lastWriteTicks.put(port.name(), current == null ? -1L : current.getGameTime());
        this.setStatus(source + " wrote " + port.name());

        // Pure socket work: safe here, and doing it now keeps the round trip short.
        if (!SOURCE_PYTHON.equals(source)) {
            VirtualComsServer.get().publish(this, port, value);
        }

        this.requestClientSync();
    }

    /** Coalesces client-sync requests so at most one is queued per board at a time. */
    private void requestClientSync() {
        if (this.clientSyncPending.compareAndSet(false, true)) {
            VirtualComsServer.get().submitGameTask(this::flushClientSync);
        }
    }

    private void flushClientSync() {
        this.clientSyncPending.set(false);
        if (this.isRemoved()) {
            return;
        }
        this.setChanged();
        this.syncValueToClient(this.getLevel());
    }

    private void setStatus(String newStatus) {
        this.status = newStatus;
    }

    /** Marks the board configuration dirty and pushes a full snapshot to watching clients. */
    private void markConfigurationChanged() {
        this.revision++;
        this.endpointDirty = true;
        this.setChanged();
        if (this.level != null && !this.level.isClientSide()) {
            this.level.sendBlockUpdated(this.getBlockPos(), this.getBlockState(), this.getBlockState(), 3);
            // Out-of-game programs learn about terminals only from pushes, so tell them about the
            // new layout immediately instead of waiting for some value to happen to change.
            VirtualComsServer.get().announcePorts(this);
        }
    }

    private void syncValueToClient(@Nullable Level current) {
        if (current == null || current.isClientSide()) {
            return;
        }
        long now = current.getGameTime();
        if (this.lastClientValueSyncTick == Long.MIN_VALUE
                || now - this.lastClientValueSyncTick >= CLIENT_VALUE_SYNC_INTERVAL) {
            this.lastClientValueSyncTick = now;
            current.sendBlockUpdated(this.getBlockPos(), this.getBlockState(), this.getBlockState(), 3);
        }
    }

    // ------------------------------------------------------------------ persistence

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putString(COM_ID_TAG, this.comId);
        tag.putString(STATUS_TAG, this.status);
        tag.putInt(REVISION_TAG, this.revision);
        tag.putUUID(ENDPOINT_ID_TAG, this.endpointId.value());

        ListTag portTags = new ListTag();
        synchronized (this) {
            for (SerialPort port : this.ports.values()) {
                portTags.add(port.toTag());
            }
        }
        tag.put(PORTS_TAG, portTags);

        CompoundTag valueTags = new CompoundTag();
        this.values.forEach((name, value) -> valueTags.put(name, PortValues.toTag(value)));
        tag.put(VALUES_TAG, valueTags);

        CompoundTag tickTags = new CompoundTag();
        this.lastWriteTicks.forEach(tickTags::putLong);
        tag.put(LAST_WRITE_TICKS_TAG, tickTags);

        CompoundTag writerTags = new CompoundTag();
        this.lastWriters.forEach(writerTags::putString);
        tag.put(LAST_WRITERS_TAG, writerTags);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        this.comId = tag.contains(COM_ID_TAG) ? tag.getString(COM_ID_TAG) : DEFAULT_COM_ID;
        this.status = tag.contains(STATUS_TAG) ? tag.getString(STATUS_TAG) : "Ready";
        this.revision = tag.getInt(REVISION_TAG);
        if (tag.hasUUID(ENDPOINT_ID_TAG)) {
            this.endpointId = EndpointId.of(tag.getUUID(ENDPOINT_ID_TAG));
        }

        synchronized (this) {
            this.ports.clear();
            Set<String> seen = new LinkedHashSet<>();
            for (Tag entry : tag.getList(PORTS_TAG, Tag.TAG_COMPOUND)) {
                if (entry instanceof CompoundTag portTag) {
                    SerialPort port = SerialPort.fromTag(portTag);
                    if (port != null && seen.add(port.name())) {
                        this.ports.put(port.name(), port);
                    }
                }
            }
        }

        this.values.clear();
        CompoundTag valueTags = tag.getCompound(VALUES_TAG);
        for (String name : valueTags.getAllKeys()) {
            SerialPort port = this.ports.get(name);
            if (port != null) {
                SignalValue value = PortValues.fromTag(valueTags.getCompound(name));
                if (value != null && port.accepts(value)) {
                    this.values.put(name, value);
                }
            }
        }
        for (SerialPort port : this.ports.values()) {
            this.values.putIfAbsent(port.name(), port.defaultValue());
        }

        this.lastWriteTicks.clear();
        CompoundTag tickTags = tag.getCompound(LAST_WRITE_TICKS_TAG);
        for (String name : tickTags.getAllKeys()) {
            this.lastWriteTicks.put(name, tickTags.getLong(name));
        }

        this.lastWriters.clear();
        CompoundTag writerTags = tag.getCompound(LAST_WRITERS_TAG);
        for (String name : writerTags.getAllKeys()) {
            this.lastWriters.put(name, writerTags.getString(name));
        }

        this.endpointDirty = true;
        this.portsAnnounced = false;
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        return this.saveWithoutMetadata(registries);
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    // ------------------------------------------------------------------ helpers used by the GUI

    /** A stable textual snapshot of the configuration, used to detect real changes. */
    public synchronized String configurationSignature() {
        StringBuilder builder = new StringBuilder(this.comId).append('|');
        for (SerialPort port : this.ports.values()) {
            builder.append(port.name()).append(',')
                    .append(port.type().serializedName()).append(',')
                    .append(port.direction().serializedName()).append(',')
                    .append(port.saveLastValue()).append(';');
        }
        return builder.toString();
    }

    /**
     * The current value of every terminal, annotated with whoever last wrote it.
     *
     * <p>The writer tag is what makes a stale looking value diagnosable at a glance: a terminal
     * still showing the default because the circuit has not driven it yet reads differently from one
     * an out-of-game program is actively holding.
     */
    public synchronized Map<String, String> valueSummary() {
        Map<String, String> summary = new LinkedHashMap<>();
        for (SerialPort port : this.ports.values()) {
            String value = PortValues.describe(this.valueOrDefault(port));
            String writer = this.lastWriters.get(port.name());
            summary.put(port.name(), writer == null ? value + "  [-]" : value + "  [" + writer + "]");
        }
        return summary;
    }

    /** All configured port names, in order. */
    public synchronized Collection<String> portNames() {
        return List.copyOf(this.ports.keySet());
    }

    /** Lower-cases a com id for case-insensitive comparison in diagnostics. */
    public String normalizedComId() {
        return this.comId.toLowerCase(Locale.ROOT);
    }
}
