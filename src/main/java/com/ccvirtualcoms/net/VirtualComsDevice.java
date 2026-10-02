package com.ccvirtualcoms.net;

import com.ccvirtualcoms.content.SerialPort;
import com.verr1.synaxis.foundation.cimulink.core.signal.SignalValue;
import java.util.List;

/**
 * A virtual serial port board as seen by the socket layer.
 *
 * <p>Implemented by the block entity. Every method is invoked on the Minecraft server thread, so
 * implementations do not need to be internally synchronised for the benefit of the socket threads;
 * {@link VirtualComsServer} marshals calls across the thread boundary itself.
 */
public interface VirtualComsDevice {

    /** The logical serial port identifier, for example {@code COM1}. */
    String comId();

    /** {@code true} while the board is loaded in a level and still valid. */
    boolean isActive();

    /** An immutable snapshot of the configured terminals. */
    List<SerialPort> ports();

    /** The current value of a terminal, or {@code null} when no such terminal exists. */
    SignalValue readValue(String portName);

    /**
     * Called once per server tick, on the server thread, for every registered board.
     *
     * <p>Driving the board's lifecycle from here rather than from a block entity ticker means a board
     * placed into an already-loaded chunk behaves exactly like one loaded from disk. It also removes
     * any dependence on when the level chooses to attach a ticker.
     */
    default void serverTick() {
    }

    /**
     * Stores a value received from outside the game.
     *
     * @param portName terminal to write
     * @param value    the value to store
     * @param source   short description of the writer, used for status reporting
     * @return {@code true} when the value was accepted
     */
    boolean writeValue(String portName, SignalValue value, String source);
}
