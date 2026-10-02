package com.ccvirtualcoms.content;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonPrimitive;
import com.verr1.synaxis.foundation.cimulink.core.signal.SignalValue;
import dan200.computercraft.api.lua.LuaException;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.nbt.CompoundTag;

/**
 * Conversions between Synaxis {@link SignalValue}s and the three representations this mod has to
 * speak: JSON (the Python wire protocol), Lua (the CC:Tweaked API) and NBT (persistence).
 *
 * <p>Wire level mapping follows the protocol contract:
 * {@code real -> float64}, {@code boolean -> uint8}, {@code vec3 -> float64[3]},
 * {@code quad -> float64[4]}.
 */
public final class PortValues {

    private static final String TYPE_TAG = "Type";

    private PortValues() {
    }

    // ------------------------------------------------------------------ JSON

    /** Serialises a Synaxis value into its JSON representation. */
    public static JsonElement toJson(SignalValue value) {
        if (value == null) {
            return JsonNull.INSTANCE;
        }
        return switch (value) {
            case SignalValue.Real real -> new JsonPrimitive(real.value());
            case SignalValue.Bool bool -> new JsonPrimitive(bool.value());
            case SignalValue.Vec3 vec3 -> vector(vec3.x(), vec3.y(), vec3.z());
            case SignalValue.Quaternion quaternion -> {
                JsonArray array = new JsonArray(4);
                array.add(quaternion.x());
                array.add(quaternion.y());
                array.add(quaternion.z());
                array.add(quaternion.w());
                yield array;
            }
            default -> JsonNull.INSTANCE;
        };
    }

    private static JsonArray vector(double x, double y, double z) {
        JsonArray array = new JsonArray(3);
        array.add(x);
        array.add(y);
        array.add(z);
        return array;
    }

    /**
     * Parses a JSON value into the Synaxis value expected by {@code type}.
     *
     * @throws IllegalArgumentException when the element does not match the requested type
     */
    public static SignalValue fromJson(PortType type, JsonElement element) {
        if (type == null) {
            type = PortType.REAL;
        }
        if (element == null || element.isJsonNull()) {
            return type.defaultValue();
        }
        return switch (type) {
            case REAL -> new SignalValue.Real(finite(element, "real"));
            case BOOLEAN -> new SignalValue.Bool(bool(element, "boolean"));
            case VEC3 -> new SignalValue.Vec3(
                    finite(component(element, 0), "vec3[0]"),
                    finite(component(element, 1), "vec3[1]"),
                    finite(component(element, 2), "vec3[2]"));
            case QUAD -> new SignalValue.Quaternion(
                    finite(component(element, 0), "quad[0]"),
                    finite(component(element, 1), "quad[1]"),
                    finite(component(element, 2), "quad[2]"),
                    finite(component(element, 3), "quad[3]"));
        };
    }

    private static JsonElement component(JsonElement element, int index) {
        if (element.isJsonArray()) {
            JsonArray array = element.getAsJsonArray();
            if (index >= array.size()) {
                throw new IllegalArgumentException("Expected at least " + (index + 1) + " components");
            }
            return array.get(index);
        }
        if (element.isJsonObject() && index < 4) {
            // Also accept the named form {"x":..,"y":..,"z":..,"w":..}
            String[] keys = {"x", "y", "z", "w"};
            JsonElement named = element.getAsJsonObject().get(keys[index]);
            if (named != null) {
                return named;
            }
        }
        throw new IllegalArgumentException("Expected an array of numbers");
    }

    private static double finite(JsonElement element, String context) {
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException("Expected a number for " + context);
        }
        double value = element.getAsDouble();
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("Expected a finite number for " + context);
        }
        return value;
    }

    private static boolean bool(JsonElement element, String context) {
        if (element == null || !element.isJsonPrimitive()) {
            throw new IllegalArgumentException("Expected a boolean for " + context);
        }
        JsonPrimitive primitive = element.getAsJsonPrimitive();
        if (primitive.isBoolean()) {
            return primitive.getAsBoolean();
        }
        if (primitive.isNumber()) {
            return primitive.getAsDouble() != 0.0D;
        }
        if (primitive.isString()) {
            String raw = primitive.getAsString().trim();
            if ("true".equalsIgnoreCase(raw) || "1".equals(raw)) {
                return true;
            }
            if ("false".equalsIgnoreCase(raw) || "0".equals(raw)) {
                return false;
            }
        }
        throw new IllegalArgumentException("Expected a boolean for " + context);
    }

    // ------------------------------------------------------------------ Lua

    /** Converts a Synaxis value into the Lua facing representation. */
    public static Object toLua(SignalValue value) {
        if (value == null) {
            return null;
        }
        return switch (value) {
            case SignalValue.Real real -> real.value();
            case SignalValue.Bool bool -> bool.value();
            case SignalValue.Vec3 vec3 -> {
                Map<Integer, Object> table = new LinkedHashMap<>(3);
                table.put(1, vec3.x());
                table.put(2, vec3.y());
                table.put(3, vec3.z());
                yield table;
            }
            case SignalValue.Quaternion quaternion -> {
                Map<Integer, Object> table = new LinkedHashMap<>(4);
                table.put(1, quaternion.x());
                table.put(2, quaternion.y());
                table.put(3, quaternion.z());
                table.put(4, quaternion.w());
                yield table;
            }
            default -> null;
        };
    }

    /**
     * Parses a Lua argument into the Synaxis value expected by {@code type}.
     *
     * <p>Accepts both the array form ({@code {1,2,3}}) and the named form
     * ({@code {x=1,y=2,z=3}}) so scripts can pick whichever reads better.
     */
    public static SignalValue fromLua(PortType type, Object value) throws LuaException {
        if (type == null) {
            type = PortType.REAL;
        }
        return switch (type) {
            case REAL -> new SignalValue.Real(luaNumber(value, "real"));
            case BOOLEAN -> new SignalValue.Bool(luaBoolean(value, "boolean"));
            case VEC3 -> new SignalValue.Vec3(
                    luaNumber(luaField(value, "x", 1, "vec3"), "vec3.x"),
                    luaNumber(luaField(value, "y", 2, "vec3"), "vec3.y"),
                    luaNumber(luaField(value, "z", 3, "vec3"), "vec3.z"));
            case QUAD -> new SignalValue.Quaternion(
                    luaNumber(luaField(value, "x", 1, "quad"), "quad.x"),
                    luaNumber(luaField(value, "y", 2, "quad"), "quad.y"),
                    luaNumber(luaField(value, "z", 3, "quad"), "quad.z"),
                    luaNumber(luaField(value, "w", 4, "quad"), "quad.w"));
        };
    }

    private static Object luaField(Object value, String name, int index, String context) throws LuaException {
        if (!(value instanceof Map<?, ?> table)) {
            throw new LuaException("Expected a table for " + context);
        }
        Object result = table.get(name);
        if (result == null) {
            result = table.get((double) index);
        }
        if (result == null) {
            result = table.get(index);
        }
        if (result == null) {
            result = table.get((long) index);
        }
        if (result == null) {
            result = table.get(String.valueOf(index));
        }
        if (result == null) {
            throw new LuaException("Missing field '" + name + "' in " + context + " table");
        }
        return result;
    }

    private static double luaNumber(Object value, String context) throws LuaException {
        if (value instanceof Number number) {
            double result = number.doubleValue();
            if (Double.isFinite(result)) {
                return result;
            }
        }
        throw new LuaException("Expected a finite number for " + context);
    }

    private static boolean luaBoolean(Object value, String context) throws LuaException {
        if (value instanceof Boolean bool) {
            return bool;
        }
        throw new LuaException("Expected a boolean for " + context);
    }

    // ------------------------------------------------------------------ NBT

    /** Serialises a Synaxis value to NBT. */
    public static CompoundTag toTag(SignalValue value) {
        CompoundTag tag = new CompoundTag();
        if (value == null) {
            tag.putString(TYPE_TAG, "none");
            return tag;
        }
        switch (value) {
            case SignalValue.Real real -> {
                tag.putString(TYPE_TAG, "real");
                tag.putDouble("Value", real.value());
            }
            case SignalValue.Bool bool -> {
                tag.putString(TYPE_TAG, "boolean");
                tag.putBoolean("Value", bool.value());
            }
            case SignalValue.Vec3 vec3 -> {
                tag.putString(TYPE_TAG, "vec3");
                tag.putDouble("X", vec3.x());
                tag.putDouble("Y", vec3.y());
                tag.putDouble("Z", vec3.z());
            }
            case SignalValue.Quaternion quaternion -> {
                tag.putString(TYPE_TAG, "quad");
                tag.putDouble("X", quaternion.x());
                tag.putDouble("Y", quaternion.y());
                tag.putDouble("Z", quaternion.z());
                tag.putDouble("W", quaternion.w());
            }
            default -> tag.putString(TYPE_TAG, "none");
        }
        return tag;
    }

    /** Reads a Synaxis value from NBT, returning {@code null} when unsupported. */
    public static SignalValue fromTag(CompoundTag tag) {
        if (tag == null) {
            return null;
        }
        return switch (tag.getString(TYPE_TAG)) {
            case "real" -> new SignalValue.Real(tag.getDouble("Value"));
            case "boolean" -> new SignalValue.Bool(tag.getBoolean("Value"));
            case "vec3" -> new SignalValue.Vec3(tag.getDouble("X"), tag.getDouble("Y"), tag.getDouble("Z"));
            case "quad", "quaternion" -> new SignalValue.Quaternion(
                    tag.getDouble("X"), tag.getDouble("Y"), tag.getDouble("Z"), tag.getDouble("W"));
            default -> null;
        };
    }

    /** Renders a Synaxis value for chat / tooltip display. */
    public static String describe(SignalValue value) {
        if (value == null) {
            return "<none>";
        }
        return switch (value) {
            case SignalValue.Real real -> format(real.value());
            case SignalValue.Bool bool -> Boolean.toString(bool.value());
            case SignalValue.Vec3 vec3 -> "[" + format(vec3.x()) + ", " + format(vec3.y()) + ", " + format(vec3.z()) + "]";
            case SignalValue.Quaternion quaternion -> "[" + format(quaternion.x()) + ", " + format(quaternion.y())
                    + ", " + format(quaternion.z()) + ", " + format(quaternion.w()) + "]";
            default -> String.valueOf(value);
        };
    }

    private static String format(double value) {
        if (value == Math.rint(value) && Math.abs(value) < 1.0E15D) {
            return Long.toString((long) value);
        }
        return String.format(java.util.Locale.ROOT, "%.4f", value);
    }
}
