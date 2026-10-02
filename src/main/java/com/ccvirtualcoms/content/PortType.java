package com.ccvirtualcoms.content;

import com.verr1.synaxis.foundation.cimulink.core.signal.SignalKind;
import com.verr1.synaxis.foundation.cimulink.core.signal.SignalType;
import java.util.Locale;

/**
 * The value type of a virtual serial port terminal.
 *
 * <p>Each constant maps 1:1 onto a Synaxis Cimulink {@link SignalType}, and onto the wire level
 * representation used by the out-of-game Python library:
 *
 * <table>
 *   <caption>Type mapping</caption>
 *   <tr><th>Mod name</th><th>Synaxis signal</th><th>Wire type</th><th>Arity</th></tr>
 *   <tr><td>{@code real}</td><td>{@link SignalType#REAL}</td><td>float64</td><td>1</td></tr>
 *   <tr><td>{@code boolean}</td><td>{@link SignalType#BOOLEAN}</td><td>uint8</td><td>1</td></tr>
 *   <tr><td>{@code vec3}</td><td>{@link SignalType#VEC3}</td><td>float64[3]</td><td>3</td></tr>
 *   <tr><td>{@code quad}</td><td>{@link SignalType#QUATERNION}</td><td>float64[4]</td><td>4</td></tr>
 * </table>
 */
public enum PortType {
    /** A single IEEE-754 double, encoded as a JSON number on the wire. */
    REAL("real", SignalType.REAL, 1),
    /** A single boolean, encoded as a JSON boolean on the wire. */
    BOOLEAN("boolean", SignalType.BOOLEAN, 1),
    /** Three doubles {@code [x, y, z]}. */
    VEC3("vec3", SignalType.VEC3, 3),
    /** Four doubles {@code [x, y, z, w]} - Synaxis models this as a quaternion. */
    QUAD("quad", SignalType.QUATERNION, 4);

    private final String serializedName;
    private final SignalType signalType;
    private final int arity;

    PortType(String serializedName, SignalType signalType, int arity) {
        this.serializedName = serializedName;
        this.signalType = signalType;
        this.arity = arity;
    }

    /** The lower-case name used in NBT, the GUI, the Lua API and the Python protocol. */
    public String serializedName() {
        return this.serializedName;
    }

    /** The Synaxis Cimulink signal type this port type is backed by. */
    public SignalType signalType() {
        return this.signalType;
    }

    /** The Synaxis signal kind this port type is backed by. */
    public SignalKind signalKind() {
        return this.signalType.kind();
    }

    /** Number of scalar components carried by a value of this type. */
    public int arity() {
        return this.arity;
    }

    /** The Synaxis default value for this type (0, false, [0,0,0] or the identity quaternion). */
    public com.verr1.synaxis.foundation.cimulink.core.signal.SignalValue defaultValue() {
        return this.signalType.defaultValue();
    }

    /** {@code true} when the supplied Synaxis value can be stored in a port of this type. */
    public boolean accepts(com.verr1.synaxis.foundation.cimulink.core.signal.SignalValue value) {
        return this.signalType.accepts(value);
    }

    /** Parses a serialized type name, returning {@code null} when it is not recognised. */
    public static PortType byName(String name) {
        if (name == null) {
            return null;
        }
        String normalized = name.trim().toLowerCase(Locale.ROOT);
        for (PortType type : values()) {
            if (type.serializedName.equals(normalized)) {
                return type;
            }
        }
        // Accept the Synaxis spelling as an alias so existing Cimulink terminology keeps working.
        if ("quaternion".equals(normalized)) {
            return QUAD;
        }
        return null;
    }

    /** Parses a serialized type name, falling back to {@link #REAL}. */
    public static PortType byNameOrDefault(String name) {
        PortType type = byName(name);
        return type == null ? REAL : type;
    }
}
