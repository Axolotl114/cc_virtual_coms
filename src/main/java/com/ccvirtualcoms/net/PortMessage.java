package com.ccvirtualcoms.net;

import com.ccvirtualcoms.content.PortDirection;
import com.ccvirtualcoms.content.PortType;
import com.ccvirtualcoms.content.PortValues;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import com.verr1.synaxis.foundation.cimulink.core.signal.SignalValue;

/**
 * The JSON payload carried by {@link Protocol#MSG_DATA_PUSH} and {@link Protocol#MSG_DATA_WRITE}.
 *
 * <p>Shape on the wire:
 *
 * <pre>
 * {
 *   "com":       "COM1",      // logical serial port the value belongs to
 *   "port":      "throttle",  // terminal name
 *   "type":      "real",      // one of real | boolean | vec3 | quad
 *   "direction": "out",       // one of out | in (optional, see below)
 *   "value":     42.5         // number | boolean | [x,y,z] | [x,y,z,w]
 * }
 * </pre>
 *
 * <p>{@code direction} is always present on {@code DATA_PUSH} frames so that an out-of-game program
 * can tell which terminals the circuit reads (and therefore which ones it may safely drive) without
 * any extra round trip. It is optional on {@code DATA_WRITE} frames, where the mod already knows the
 * direction of the terminal being written.
 */
public record PortMessage(String com, String port, PortType type, PortDirection direction, SignalValue value) {

    private static final String COM = "com";
    private static final String PORT = "port";
    private static final String TYPE = "type";
    private static final String DIRECTION = "direction";
    private static final String VALUE = "value";

    /** Serialises this message to compact JSON. */
    public String toJson() {
        return toJsonObject().toString();
    }

    /** Serialises this message into a JSON object that also carries extra fields. */
    public JsonObject toJsonObject() {
        JsonObject object = new JsonObject();
        if (this.com != null) {
            object.addProperty(COM, this.com);
        }
        object.addProperty(PORT, this.port);
        object.addProperty(TYPE, this.type == null ? PortType.REAL.serializedName() : this.type.serializedName());
        if (this.direction != null) {
            object.addProperty(DIRECTION, this.direction.serializedName());
        }
        object.add(VALUE, PortValues.toJson(this.value));
        return object;
    }

    /**
     * Parses a payload.
     *
     * @throws Protocol.ProtocolException when the JSON is malformed or the value does not match the
     *                                    declared type
     */
    public static PortMessage fromJson(String json) throws Protocol.ProtocolException {
        if (json == null || json.isBlank()) {
            throw new Protocol.ProtocolException("Empty port message payload");
        }
        JsonObject object;
        try {
            JsonElement parsed = JsonParser.parseString(json);
            if (!parsed.isJsonObject()) {
                throw new Protocol.ProtocolException("Port message payload must be a JSON object");
            }
            object = parsed.getAsJsonObject();
        } catch (JsonSyntaxException ex) {
            throw new Protocol.ProtocolException("Malformed JSON payload: " + ex.getMessage());
        }

        if (!object.has(PORT) || object.get(PORT).isJsonNull()) {
            throw new Protocol.ProtocolException("Port message is missing the 'port' field");
        }
        String port = object.get(PORT).getAsString();

        String com = object.has(COM) && !object.get(COM).isJsonNull() ? object.get(COM).getAsString() : null;

        PortType type = PortType.byName(object.has(TYPE) && !object.get(TYPE).isJsonNull()
                ? object.get(TYPE).getAsString()
                : null);
        if (type == null) {
            throw new Protocol.ProtocolException("Port message has an unknown 'type' value");
        }

        // Direction is advisory on inbound writes; an unknown spelling is simply ignored rather than
        // rejecting the whole frame, since the board decides the real direction anyway.
        PortDirection direction = object.has(DIRECTION) && !object.get(DIRECTION).isJsonNull()
                ? PortDirection.byName(object.get(DIRECTION).getAsString())
                : null;

        SignalValue value;
        try {
            value = PortValues.fromJson(type, object.get(VALUE));
        } catch (IllegalArgumentException ex) {
            throw new Protocol.ProtocolException("Invalid value for port '" + port + "': " + ex.getMessage());
        }

        return new PortMessage(com, port, type, direction, value);
    }
}
