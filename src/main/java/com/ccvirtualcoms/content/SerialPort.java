package com.ccvirtualcoms.content;

import com.verr1.synaxis.foundation.cimulink.core.signal.PortDef;
import com.verr1.synaxis.foundation.cimulink.core.signal.SignalValue;
import java.util.regex.Pattern;
import net.minecraft.nbt.CompoundTag;

/**
 * A single configurable terminal (channel) of a virtual serial port board.
 *
 * <p>The record is deliberately immutable: the owning block entity replaces entries instead of
 * mutating them, which keeps the client/server snapshot comparison cheap and free of aliasing
 * bugs.
 *
 * @param name          unique, user visible port name
 * @param type          value type carried by the terminal
 * @param direction     whether the terminal faces into or out of the game
 * @param saveLastValue when {@code true} the last value survives a Cimulink input disappearing
 */
public record SerialPort(String name, PortType type, PortDirection direction, boolean saveLastValue) {

    private static final String NAME_TAG = "Name";
    private static final String TYPE_TAG = "Type";
    private static final String DIRECTION_TAG = "Direction";
    private static final String SAVE_LAST_VALUE_TAG = "SaveLastValue";

    /**
     * Port names are used as Cimulink port identifiers, as Lua table keys and as JSON field
     * values. The Synaxis name rules are the most restrictive of the three, so they are used here.
     */
    private static final Pattern VALID_NAME = Pattern.compile("[A-Za-z_@$][A-Za-z0-9_@$.:-]*");

    public SerialPort(String name, PortType type, PortDirection direction) {
        this(name, type, direction, false);
    }

    /** Validates and normalises the components of a port specification. */
    public SerialPort {
        name = normalizeName(name);
        if (type == null) {
            type = PortType.REAL;
        }
        if (direction == null) {
            direction = PortDirection.OUT;
        }
    }

    /** The Cimulink port definition this terminal contributes to the board's component schema. */
    public PortDef portDef() {
        return this.direction == PortDirection.IN
                ? PortDef.input(this.name, this.type.signalType())
                : PortDef.output(this.name, this.type.signalType());
    }

    /** The Synaxis default value for this terminal. */
    public SignalValue defaultValue() {
        return this.type.defaultValue();
    }

    /** {@code true} when the supplied Synaxis value may be stored in this terminal. */
    public boolean accepts(SignalValue value) {
        return this.type.accepts(value);
    }

    /** Returns a copy of this port with a different name. */
    public SerialPort withName(String newName) {
        return new SerialPort(newName, this.type, this.direction, this.saveLastValue);
    }

    /** Returns a copy of this port with a different type. */
    public SerialPort withType(PortType newType) {
        return new SerialPort(this.name, newType, this.direction, this.saveLastValue);
    }

    /** Returns a copy of this port with a different direction. */
    public SerialPort withDirection(PortDirection newDirection) {
        return new SerialPort(this.name, this.type, newDirection, this.saveLastValue);
    }

    /** Returns a copy of this port with a different save-last-value flag. */
    public SerialPort withSaveLastValue(boolean save) {
        return new SerialPort(this.name, this.type, this.direction, save);
    }

    public CompoundTag toTag() {
        CompoundTag tag = new CompoundTag();
        tag.putString(NAME_TAG, this.name);
        tag.putString(TYPE_TAG, this.type.serializedName());
        tag.putString(DIRECTION_TAG, this.direction.serializedName());
        tag.putBoolean(SAVE_LAST_VALUE_TAG, this.saveLastValue);
        return tag;
    }

    /** Reads a port from NBT, returning {@code null} when the tag is malformed. */
    public static SerialPort fromTag(CompoundTag tag) {
        if (tag == null || !tag.contains(NAME_TAG)) {
            return null;
        }
        try {
            PortType type = PortType.byNameOrDefault(tag.getString(TYPE_TAG));
            PortDirection direction = PortDirection.byNameOrDefault(tag.getString(DIRECTION_TAG));
            boolean save = !tag.contains(SAVE_LAST_VALUE_TAG) || tag.getBoolean(SAVE_LAST_VALUE_TAG);
            return new SerialPort(tag.getString(NAME_TAG), type, direction, save);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    /** {@code true} when {@code name} is a legal port name. */
    public static boolean isValidName(String name) {
        return name != null && VALID_NAME.matcher(name.trim()).matches();
    }

    /** Trims and validates a port name. */
    public static String normalizeName(String name) {
        if (name == null) {
            throw new IllegalArgumentException("Port name is required");
        }
        String trimmed = name.trim();
        if (!VALID_NAME.matcher(trimmed).matches()) {
            throw new IllegalArgumentException(
                    "Invalid port name '" + name + "': must start with a letter, '_', '@' or '$' and "
                            + "may continue with letters, digits, '_', '@', '$', '.', ':' or '-'");
        }
        return trimmed;
    }
}
