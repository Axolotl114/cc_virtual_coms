package com.ccvirtualcoms.content;

import java.util.Locale;

/**
 * The role a terminal plays in the Synaxis Cimulink graph.
 *
 * <p><b>Direction controls only the Cimulink port role - it does not restrict who may read or
 * write.</b> Both an out-of-game Python program and a CC:Tweaked computer may read and write every
 * terminal; the question of "who wins" when several parties write the same terminal is settled by
 * ownership (see {@code VirtualComsBlockEntity}), not by direction.
 *
 * <ul>
 *   <li>{@link #OUT} - the board is the <em>source</em>. The terminal becomes a Cimulink
 *       <em>output</em> port, so the circuit reads it. This is the terminal to use when you want to
 *       drive something in the game from outside: write it from Python and wire its output port
 *       wherever the value is needed. Nothing inside the circuit ever overwrites it.</li>
 *   <li>{@link #IN} - the board is a <em>sink</em>. The terminal becomes a Cimulink <em>input</em>
 *       port, so the circuit writes it. Use this to observe a circuit value from Python or a
 *       computer. Writing it from outside works too, but the circuit takes over as soon as it
 *       actually drives a non-default value.</li>
 * </ul>
 */
public enum PortDirection {
    IN("in", "input"),
    OUT("out", "output");

    private final String serializedName;
    private final String cimulinkPort;

    PortDirection(String serializedName, String cimulinkPort) {
        this.serializedName = serializedName;
        this.cimulinkPort = cimulinkPort;
    }

    /** The lower-case name used in NBT, the GUI, the Lua API and the Python protocol. */
    public String serializedName() {
        return this.serializedName;
    }

    /** The Cimulink port role this terminal takes: {@code input} or {@code output}. */
    public String cimulinkPort() {
        return this.cimulinkPort;
    }

    /** {@code true} when this terminal is driven by the circuit rather than by external writers. */
    public boolean circuitDriven() {
        return this == IN;
    }

    /** Parses a serialized direction name, returning {@code null} when it is not recognised. */
    public static PortDirection byName(String name) {
        if (name == null) {
            return null;
        }
        String normalized = name.trim().toLowerCase(Locale.ROOT);
        for (PortDirection direction : values()) {
            if (direction.serializedName.equals(normalized)) {
                return direction;
            }
        }
        return switch (normalized) {
            case "input", "synaxis_to_cc", "to_game", "read", "sink" -> IN;
            case "output", "cc_to_synaxis", "from_game", "write", "source" -> OUT;
            default -> null;
        };
    }

    /** Parses a serialized direction name, falling back to {@link #OUT}. */
    public static PortDirection byNameOrDefault(String name) {
        PortDirection direction = byName(name);
        return direction == null ? OUT : direction;
    }
}
