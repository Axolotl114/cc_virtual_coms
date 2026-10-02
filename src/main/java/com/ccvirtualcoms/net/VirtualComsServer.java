package com.ccvirtualcoms.net;

import com.ccvirtualcoms.content.PortDirection;
import com.ccvirtualcoms.content.PortType;
import com.ccvirtualcoms.content.PortValues;
import com.ccvirtualcoms.content.SerialPort;
import com.google.gson.JsonObject;
import com.verr1.synaxis.foundation.cimulink.core.signal.SignalValue;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The single local TCP endpoint that out-of-game programs connect to.
 *
 * <p>The listener is a process wide singleton. Boards register themselves as
 * {@link VirtualComsDevice}s; each connected peer receives every {@link Protocol#MSG_DATA_PUSH}
 * frame and may send {@link Protocol#MSG_DATA_WRITE} frames back. Writes are marshalled onto the
 * Minecraft server thread before they touch any game state.
 *
 * <p>All socket threads are daemons, so a running listener never prevents the JVM from exiting.
 */
public final class VirtualComsServer {

    public static final String DEFAULT_HOST = "127.0.0.1";
    public static final int DEFAULT_PORT = 25585;

    /** JVM properties letting a launcher move the listener when the default port is taken. */
    public static final String HOST_PROPERTY = "cc_virtual_coms.host";
    public static final String PORT_PROPERTY = "cc_virtual_coms.port";
    public static final String MAX_CLIENTS_PROPERTY = "cc_virtual_coms.maxClients";

    /**
     * The bind address to use: {@code -Dcc_virtual_coms.host=...} when set, otherwise
     * {@link #DEFAULT_HOST}.
     */
    public static String configuredHost() {
        String raw = System.getProperty(HOST_PROPERTY);
        return raw == null || raw.isBlank() ? DEFAULT_HOST : raw.trim();
    }

    /**
     * The TCP port to use: {@code -Dcc_virtual_coms.port=...} when set, otherwise
     * {@link #DEFAULT_PORT}.
     *
     * <p>Two game instances on one machine cannot share a port, and the second one to start simply
     * logs a warning and carries on without a listener. Overriding this property is the supported
     * way to run them side by side.
     */
    public static int configuredPort() {
        String raw = System.getProperty(PORT_PROPERTY);
        if (raw != null && !raw.isBlank()) {
            try {
                int value = Integer.parseInt(raw.trim());
                if (value > 0 && value < 65536) {
                    return value;
                }
                LOGGER.warn("Ignoring {}={} - not a valid TCP port", PORT_PROPERTY, raw);
            } catch (NumberFormatException ex) {
                LOGGER.warn("Ignoring {}={} - not a number", PORT_PROPERTY, raw);
            }
        }
        return DEFAULT_PORT;
    }

    /** Default upper bound on simultaneously connected peers. */
    public static final int DEFAULT_MAX_SESSIONS = 64;

    /**
     * How many peers may be connected at once: {@code -Dcc_virtual_coms.maxClients=...} when set,
     * otherwise {@link #DEFAULT_MAX_SESSIONS}.
     *
     * <p>A connection costs one socket plus two threads, so the ceiling exists only to stop a
     * runaway client from exhausting file descriptors. A well behaved program opens one connection
     * and reuses it; reconnecting in a loop will still hit this ceiling eventually, just later.
     */
    public static int configuredMaxSessions() {
        String raw = System.getProperty(MAX_CLIENTS_PROPERTY);
        if (raw != null && !raw.isBlank()) {
            try {
                int value = Integer.parseInt(raw.trim());
                if (value > 0 && value <= 1024) {
                    return value;
                }
                LOGGER.warn("Ignoring {}={} - must be between 1 and 1024", MAX_CLIENTS_PROPERTY, raw);
            } catch (NumberFormatException ex) {
                LOGGER.warn("Ignoring {}={} - not a number", MAX_CLIENTS_PROPERTY, raw);
            }
        }
        return DEFAULT_MAX_SESSIONS;
    }

    /** Per session outbound backlog. Beyond this the oldest frames are discarded. */
    private static final int OUTBOUND_QUEUE_CAPACITY = 4096;

    private static final Logger LOGGER = LoggerFactory.getLogger("CCVirtualComs/Server");
    private static final VirtualComsServer INSTANCE = new VirtualComsServer();

    private final Set<ClientSession> sessions = ConcurrentHashMap.newKeySet();
    private final Set<VirtualComsDevice> devices = ConcurrentHashMap.newKeySet();
    private final AtomicLong pushCounter = new AtomicLong();

    private volatile ServerSocket serverSocket;
    private volatile Thread acceptThread;
    private volatile boolean running;
    private volatile String host = DEFAULT_HOST;
    private volatile int port = DEFAULT_PORT;
    private volatile String lastError;

    /**
     * Work handed over by socket threads, waiting to be run on the Minecraft server thread.
     *
     * <p>Socket threads never touch game state directly. They append a task here and the mod drains
     * the queue from a {@code ServerTickEvent.Post} handler, which is a callback whose threading and
     * timing this mod fully controls. (Handing the task to {@code MinecraftServer#execute} instead
     * makes correctness depend on how the integrated server schedules its task queue, which is not
     * something a mod should rely on.)
     */
    private final ConcurrentLinkedQueue<Runnable> pendingGameTasks = new ConcurrentLinkedQueue<>();

    /** When the oldest queued task was submitted, in nanos; 0 when the queue is empty. */
    private final java.util.concurrent.atomic.AtomicLong oldestPendingNanos =
            new java.util.concurrent.atomic.AtomicLong();

    /** Whether a "the server is not ticking" complaint has already gone out for the current stall. */
    private volatile boolean stallReported;

    /** Bound on the pending queue, so a stalled server cannot grow it without limit. */
    private static final int MAX_PENDING_TASKS = 4096;

    private VirtualComsServer() {
    }

    /** The process wide listener. */
    public static VirtualComsServer get() {
        return INSTANCE;
    }

    /**
     * Queues work to be executed on the Minecraft server thread.
     *
     * <p>Safe to call from any thread. The queue is drained by {@link #drainGameTasks()}.
     */
    public void submitGameTask(Runnable task) {
        if (task == null) {
            return;
        }
        // A stalled server must not let the queue grow without limit. Dropping a client-sync task is
        // harmless: the value itself is already stored and a later task will sync it.
        if (this.pendingGameTasks.size() >= MAX_PENDING_TASKS) {
            return;
        }
        this.pendingGameTasks.add(task);
        if (this.oldestPendingNanos.compareAndSet(0L, System.nanoTime())) {
            this.stallReported = false;
        }
    }

    /**
     * Runs every task queued since the last call. Must be called on the server thread once per tick.
     *
     * @return the number of tasks that ran
     */
    public int drainGameTasks() {
        this.oldestPendingNanos.set(0L);
        this.stallReported = false;

        int executed = 0;
        Runnable task;
        while ((task = this.pendingGameTasks.poll()) != null) {
            try {
                task.run();
                executed++;
            } catch (RuntimeException ex) {
                LOGGER.warn("Queued virtual coms task failed: {}", ex.toString());
            }
        }
        return executed;
    }

    /**
     * Reports a stalled server to connected peers instead of leaving them in silence.
     *
     * <p>Queued work only runs while the game server ticks. At the main menu, or on a paused
     * integrated server, that never happens - without this a client would simply see nothing happen
     * and have no way to tell why.
     */
    private void watchdogLoop() {
        while (this.running) {
            try {
                Thread.sleep(1000L);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return;
            }

            long since = this.oldestPendingNanos.get();
            if (since == 0L || this.pendingGameTasks.isEmpty()) {
                continue;
            }
            long stalledSeconds = (System.nanoTime() - since) / 1_000_000_000L;
            if (stalledSeconds >= 3 && !this.stallReported) {
                this.stallReported = true;
                LOGGER.warn("{} queued task(s) have not run for {}s - the Minecraft server is not ticking "
                            + "(main menu, or paused single player?). Writes are being stored but their "
                            + "side effects are deferred.", this.pendingGameTasks.size(), stalledSeconds);
                publishError("the Minecraft server is not ticking, so queued work is deferred. "
                             + "Join a world (and unpause) to apply it.");
            }
        }
    }

    /** Number of tasks waiting for the next server tick - useful for diagnostics. */
    public int pendingGameTaskCount() {
        return this.pendingGameTasks.size();
    }

    /**
     * Gives every registered board a chance to advance, on the server thread.
     *
     * <p>Called once per server tick alongside {@link #drainGameTasks()}.
     */
    public void tickDevices() {
        for (VirtualComsDevice device : this.devices) {
            try {
                device.serverTick();
            } catch (RuntimeException ex) {
                LOGGER.warn("Board {} failed to tick: {}", device.comId(), ex.toString());
            }
        }
    }

    // ------------------------------------------------------------------ lifecycle

    /**
     * Binds and starts listening. Calling this while already running on the same address is a no-op.
     *
     * @param bindHost interface to bind, normally {@code 127.0.0.1}
     * @param bindPort TCP port to bind
     * @return {@code true} when the listener is up
     */
    public synchronized boolean start(String bindHost, int bindPort) {
        if (this.running) {
            return true;
        }
        this.host = bindHost == null || bindHost.isBlank() ? DEFAULT_HOST : bindHost.trim();
        this.port = bindPort <= 0 || bindPort > 65535 ? DEFAULT_PORT : bindPort;

        try {
            ServerSocket socket = new ServerSocket();
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(InetAddress.getByName(this.host), this.port), 16);
            this.serverSocket = socket;
            this.running = true;
            this.lastError = null;
        } catch (IOException ex) {
            this.lastError = ex.getMessage();
            LOGGER.error("""
                    Could not bind the virtual coms listener to {}:{} - {}
                    The Python bridge will NOT work in this instance. Another game instance or program \
                    is probably already using that port. Start this instance with \
                    -Dcc_virtual_coms.port=<other port> to run them side by side.""",
                    this.host, this.port, ex.getMessage());
            return false;
        }

        Thread thread = new Thread(this::acceptLoop, "CCVirtualComs-Accept");
        thread.setDaemon(true);
        thread.start();
        this.acceptThread = thread;

        Thread watchdog = new Thread(this::watchdogLoop, "CCVirtualComs-Watchdog");
        watchdog.setDaemon(true);
        watchdog.start();

        LOGGER.info("Virtual coms listener started on {}:{} (max {} client(s))",
                this.host, this.port, configuredMaxSessions());
        return true;
    }

    /** Stops listening and closes every peer connection. */
    public synchronized void stop() {
        if (!this.running) {
            return;
        }
        this.running = false;

        ServerSocket socket = this.serverSocket;
        this.serverSocket = null;
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // Closing the listener is best effort.
            }
        }

        for (ClientSession session : List.copyOf(this.sessions)) {
            session.close("server stopping");
        }
        this.sessions.clear();

        Thread thread = this.acceptThread;
        this.acceptThread = null;
        if (thread != null) {
            thread.interrupt();
        }

        LOGGER.info("Virtual coms listener stopped");
    }

    public boolean isRunning() {
        return this.running;
    }

    public String host() {
        return this.host;
    }

    public int port() {
        return this.port;
    }

    /** Number of currently connected peers. */
    public int connectionCount() {
        return this.sessions.size();
    }

    /** {@code true} when at least one out-of-game program is connected. */
    public boolean hasConnections() {
        return !this.sessions.isEmpty();
    }

    /** The most recent listener level error, or {@code null}. */
    public String lastError() {
        return this.lastError;
    }

    /** Total number of value pushes published since the listener started. */
    public long pushCount() {
        return this.pushCounter.get();
    }

    // ------------------------------------------------------------------ devices

    /** Registers a board so it can receive external writes and publish values. */
    public void register(VirtualComsDevice device) {
        if (device != null) {
            this.devices.add(device);
            announcePorts(device);
        }
    }

    /**
     * Re-announces every terminal of a board.
     *
     * <p>Out-of-game programs discover terminals purely from pushes, so a program that is already
     * connected would otherwise never learn about a terminal the player just added in the GUI, nor
     * about a board that was placed after it connected. Call this whenever a board's terminal list
     * changes, or when a board becomes active.
     */
    public void announcePorts(VirtualComsDevice device) {
        if (device == null || !device.isActive()) {
            return;
        }
        if (this.sessions.isEmpty()) {
            return;
        }
        int announced = 0;
        for (SerialPort port : device.ports()) {
            SignalValue value = device.readValue(port.name());
            if (value != null) {
                publish(device, port, value);
                announced++;
            }
        }
        LOGGER.info("Announced {} terminal(s) of {} to {} client(s)",
                announced, device.comId(), this.sessions.size());
    }

    /** Removes a board and clears any queued traffic addressed to it. */
    public void unregister(VirtualComsDevice device) {
        if (device != null) {
            this.devices.remove(device);
        }
    }

    // ------------------------------------------------------------------ publishing

    /**
     * Publishes a port value to every connected peer, including the terminal's direction so that a
     * client can tell which terminals the circuit reads without asking.
     */
    public void publish(VirtualComsDevice device, SerialPort port, SignalValue value) {
        publish(device, port.name(), port.type(), port.direction(), value);
    }

    /** Publishes a port value, including its direction. */
    public void publish(VirtualComsDevice device, String portName, PortType type, PortDirection direction,
                        SignalValue value) {
        String com = device == null ? null : device.comId();
        PortMessage message = new PortMessage(com, portName, type, direction, value);
        this.pushCounter.incrementAndGet();
        broadcast(Protocol.MSG_DATA_PUSH, message.toJson());
    }

    /** Publishes a value without direction information; kept for tooling and tests. */
    public void publish(VirtualComsDevice device, String portName, PortType type, SignalValue value) {
        publish(device, portName, type, null, value);
    }

    /** Publishes a free form error frame to every connected peer. */
    public void publishError(String message) {
        JsonObject object = new JsonObject();
        object.addProperty("error", message == null ? "unknown error" : message);
        broadcast(Protocol.MSG_ERROR, object.toString());
    }

    private void broadcast(int messageType, String json) {
        if (this.sessions.isEmpty()) {
            return;
        }
        for (ClientSession session : this.sessions) {
            session.enqueue(messageType, json);
        }
    }

    // ------------------------------------------------------------------ internals

    private void acceptLoop() {
        while (this.running) {
            ServerSocket socket = this.serverSocket;
            if (socket == null) {
                break;
            }
            try {
                Socket client = socket.accept();
                int limit = configuredMaxSessions();
                if (this.sessions.size() >= limit) {
                    LOGGER.warn("Rejecting connection from {} - {} client(s) already connected (limit {}). "
                                + "Open one connection and reuse it, or raise -D{}=.",
                            client.getRemoteSocketAddress(), this.sessions.size(), limit,
                            MAX_CLIENTS_PROPERTY, limit);
                    rejectClient(client, "too many connected clients: " + this.sessions.size()
                            + " already connected, limit is " + limit
                            + ". Open one connection and reuse it instead of reconnecting in a loop,"
                            + " or raise -D" + MAX_CLIENTS_PROPERTY + "=<n>.");
                    continue;
                }
                ClientSession session = new ClientSession(client);
                this.sessions.add(session);
                session.start();
                LOGGER.info("Virtual coms client connected from {}", client.getRemoteSocketAddress());
            } catch (SocketException ex) {
                // Expected when the listener is closed.
                break;
            } catch (IOException ex) {
                if (this.running) {
                    LOGGER.warn("Accept failed: {}", ex.getMessage());
                }
            }
        }
    }

    private void rejectClient(Socket client, String reason) {
        try (Socket socket = client) {
            Protocol.writeJson(socket.getOutputStream(), Protocol.MSG_ERROR,
                    "{\"error\":\"" + reason + "\"}");
        } catch (IOException ignored) {
            // The peer may already have gone away.
        }
    }

    /** Sends the current state of every registered board to a freshly connected peer. */
    private void sendSnapshot(ClientSession session) {
        List<SnapshotEntry> entries = new ArrayList<>();
        for (VirtualComsDevice device : this.devices) {
            if (!device.isActive()) {
                continue;
            }
            for (SerialPort port : device.ports()) {
                SignalValue value = device.readValue(port.name());
                if (value != null) {
                    entries.add(new SnapshotEntry(device.comId(), port, value));
                }
            }
        }

        if (entries.isEmpty()) {
            return;
        }

        // Enqueue straight away rather than hopping to the game thread. Every field read above lives
        // in a concurrent or synchronised structure, and a connecting client must be answered even
        // when the server is not ticking (main menu, paused single player).
        for (SnapshotEntry entry : entries) {
            session.enqueue(Protocol.MSG_DATA_PUSH,
                    new PortMessage(entry.com(), entry.port().name(), entry.port().type(),
                            entry.port().direction(), entry.value()).toJson());
        }
    }

    /** Delivers an external write to every board it addresses. */
    private void handleExternalWrite(ClientSession session, PortMessage message) {
        List<VirtualComsDevice> targets = new ArrayList<>();
        String com = message.com();
        if (com != null && !com.isBlank()) {
            for (VirtualComsDevice device : this.devices) {
                if (device.isActive() && com.equalsIgnoreCase(device.comId())) {
                    targets.add(device);
                }
            }
        } else {
            for (VirtualComsDevice device : this.devices) {
                if (device.isActive()) {
                    targets.add(device);
                }
            }
        }

        if (targets.isEmpty()) {
            session.enqueue(Protocol.MSG_ERROR,
                    "{\"error\":\"no virtual com board with id '" + (com == null ? "*" : com) + "' is loaded\"}");
            return;
        }

        // Applied on the calling (socket) thread, not queued for the server tick.
        //
        // A board's value store is concurrent and VirtualComsBlockEntity.store deliberately keeps
        // every level-touching side effect (setChanged, block updates, computer events) on the server
        // thread via a coalesced task. Queuing the *store* as well would cap an out-of-game write at
        // the tick rate and add up to a full tick - 50ms - of latency, which is fatal for a 60Hz
        // control loop.
        boolean delivered = false;
        for (VirtualComsDevice device : targets) {
            try {
                if (device.writeValue(message.port(), message.value(), "python")) {
                    delivered = true;
                }
            } catch (RuntimeException ex) {
                LOGGER.warn("Board {} rejected a write from outside: {}", device.comId(), ex.toString());
            }
        }
        if (!delivered) {
            session.enqueue(Protocol.MSG_ERROR, "{\"error\":\"no writable port named '"
                    + message.port() + "' on " + (com == null ? "any board" : com) + "\"}");
        }
    }

    private record SnapshotEntry(String com, SerialPort port, SignalValue value) {
    }

    /** One connected out-of-game program. */
    private final class ClientSession {

        private final Socket socket;
        private final LinkedBlockingQueue<Outbound> outbound = new LinkedBlockingQueue<>(OUTBOUND_QUEUE_CAPACITY);
        private final Thread readerThread;
        private final Thread writerThread;
        private volatile boolean open = true;
        private volatile String remote = "unknown";

        ClientSession(Socket socket) throws IOException {
            this.socket = socket;
            this.socket.setTcpNoDelay(true);
            this.socket.setKeepAlive(true);
            this.remote = String.valueOf(socket.getRemoteSocketAddress());
            this.readerThread = new Thread(this::readLoop, "CCVirtualComs-Read");
            this.writerThread = new Thread(this::writeLoop, "CCVirtualComs-Write");
            this.readerThread.setDaemon(true);
            this.writerThread.setDaemon(true);
        }

        void start() {
            this.writerThread.start();
            this.readerThread.start();
            sendSnapshot(this);
        }

        void enqueue(int messageType, String json) {
            if (!this.open) {
                return;
            }
            Outbound frame = new Outbound(messageType, json);
            if (!this.outbound.offer(frame)) {
                // Backlog is full: drop the oldest frame so live data keeps flowing.
                this.outbound.poll();
                this.outbound.offer(frame);
            }
        }

        private void readLoop() {
            try (InputStream raw = this.socket.getInputStream()) {
                BufferedInputStream in = new BufferedInputStream(raw);
                while (this.open && VirtualComsServer.this.running) {
                    Protocol.Frame frame = Protocol.read(in);
                    VirtualComsServer.this.onFrame(this, frame);
                }
            } catch (EOFException ex) {
                // Peer closed cleanly.
            } catch (Protocol.ProtocolException ex) {
                LOGGER.warn("Protocol error from {}: {}", this.remote, ex.getMessage());
                enqueue(Protocol.MSG_ERROR, "{\"error\":\"" + escape(ex.getMessage()) + "\"}");
            } catch (IOException ex) {
                if (this.open) {
                    LOGGER.debug("Connection to {} lost: {}", this.remote, ex.getMessage());
                }
            } catch (RuntimeException ex) {
                LOGGER.warn("Unexpected failure while reading from {}: {}", this.remote, ex.getMessage());
            } finally {
                close("disconnected");
            }
        }

        private void writeLoop() {
            try (OutputStream raw = this.socket.getOutputStream()) {
                BufferedOutputStream out = new BufferedOutputStream(raw);
                while (this.open) {
                    Outbound frame = this.outbound.poll(500, TimeUnit.MILLISECONDS);
                    if (frame == null) {
                        continue;
                    }
                    Protocol.write(out, frame.messageType(), frame.json().getBytes(StandardCharsets.UTF_8));
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            } catch (IOException ex) {
                if (this.open) {
                    LOGGER.debug("Connection to {} lost while writing: {}", this.remote, ex.getMessage());
                }
            } finally {
                close("writer stopped");
            }
        }

        void close(String reason) {
            if (!this.open) {
                return;
            }
            this.open = false;
            VirtualComsServer.this.sessions.remove(this);
            try {
                this.socket.close();
            } catch (IOException ignored) {
                // Closing is best effort.
            }
            this.readerThread.interrupt();
            this.writerThread.interrupt();
            LOGGER.info("Virtual coms client {} closed ({})", this.remote, reason);
        }

        private record Outbound(int messageType, String json) {
        }
    }

    /** Dispatches one inbound frame. */
    private void onFrame(ClientSession session, Protocol.Frame frame) {
        switch (frame.messageType()) {
            case Protocol.MSG_DATA_WRITE -> {
                try {
                    PortMessage message = PortMessage.fromJson(frame.json());
                    handleExternalWrite(session, message);
                } catch (Protocol.ProtocolException ex) {
                    session.enqueue(Protocol.MSG_ERROR, "{\"error\":\"" + escape(ex.getMessage()) + "\"}");
                }
            }
            case Protocol.MSG_HEARTBEAT -> session.enqueue(Protocol.MSG_HEARTBEAT, "");
            case Protocol.MSG_DATA_PUSH -> {
                // Out-of-game programs should not push raw pushes back; treat as a protocol error.
                session.enqueue(Protocol.MSG_ERROR,
                        "{\"error\":\"DATA_PUSH is a server to client message; use DATA_WRITE instead\"}");
            }
            case Protocol.MSG_ERROR -> LOGGER.debug("Peer reported: {}", frame.json());
            default -> session.enqueue(Protocol.MSG_ERROR, "{\"error\":\"unknown message type "
                    + frame.messageType() + "\"}");
        }
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ").replace("\r", " ");
    }

    /** Convenience used by status commands and the GUI. */
    public String describeState() {
        StringBuilder builder = new StringBuilder();
        builder.append(this.running ? "listening" : "stopped")
                .append(" on ").append(this.host).append(':').append(this.port)
                .append(", ").append(this.sessions.size()).append(" client(s), ")
                .append(this.devices.size()).append(" board(s), ")
                .append(this.pushCounter.get()).append(" push(es)");
        if (this.lastError != null) {
            builder.append(" [last error: ").append(this.lastError).append(']');
        }
        return builder.toString();
    }

    /** The com ids of every registered, active board - handy for diagnostics. */
    public List<String> activeComIds() {
        List<String> ids = new ArrayList<>();
        for (VirtualComsDevice device : this.devices) {
            if (device.isActive()) {
                ids.add(device.comId());
            }
        }
        return ids;
    }

    /** Exposes the raw device set for read-only iteration. */
    public Set<VirtualComsDevice> devices() {
        return Set.copyOf(this.devices);
    }

    /** Only used by tests and the loopback helper. */
    public Map<String, Object> stats() {
        return Map.of(
                "running", this.running,
                "host", this.host,
                "port", this.port,
                "clients", this.sessions.size(),
                "boards", this.devices.size(),
                "pushes", this.pushCounter.get());
    }
}
