package com.ccvirtualcoms.synaxis;

import com.ccvirtualcoms.content.PortDirection;
import com.ccvirtualcoms.content.SerialPort;
import com.ccvirtualcoms.content.VirtualComsBlockEntity;
import com.verr1.synaxis.foundation.cimulink.core.component.ComponentConfig;
import com.verr1.synaxis.foundation.cimulink.core.component.ComponentMemory;
import com.verr1.synaxis.foundation.cimulink.core.component.ComponentSchema;
import com.verr1.synaxis.foundation.cimulink.core.component.ComponentSemantics;
import com.verr1.synaxis.foundation.cimulink.core.component.ComponentType;
import com.verr1.synaxis.foundation.cimulink.core.component.ComponentTypeId;
import com.verr1.synaxis.foundation.cimulink.core.component.EmptyComponentMemory;
import com.verr1.synaxis.foundation.cimulink.core.component.EvalContext;
import com.verr1.synaxis.foundation.cimulink.core.component.ExecutionDomain;
import com.verr1.synaxis.foundation.cimulink.core.signal.InputPort;
import com.verr1.synaxis.foundation.cimulink.core.signal.OutputPort;
import com.verr1.synaxis.foundation.cimulink.core.signal.PortDef;
import com.verr1.synaxis.foundation.cimulink.core.signal.SignalReader;
import com.verr1.synaxis.foundation.cimulink.core.signal.SignalValue;
import com.verr1.synaxis.foundation.cimulink.core.signal.SignalWriter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The Synaxis Cimulink {@link ComponentType} contributed by this mod.
 *
 * <p>Registering a component type is what lets a virtual serial port board behave like any other
 * Cimulink block: its terminals show up as input and output ports in the Synaxis circuit editor and
 * can be wired into a graph with no special casing.
 *
 * <p>Direction mapping (mirrors the conventions used by CC: Synaxis Connector so both mods compose
 * predictably):
 *
 * <ul>
 *   <li>{@link PortDirection#IN} - a Cimulink <em>input</em> port. The graph drives it and the value
 *       is stored inside the game in {@link #step}.</li>
 *   <li>{@link PortDirection#OUT} - a Cimulink <em>output</em> port. The graph reads it, and it is
 *       fed from the value stored inside the game in {@link #evaluate}.</li>
 * </ul>
 */
public final class VirtualComsComponent implements ComponentType {

    /** Stable component identifier used by Synaxis serialisation and the circuit editor. */
    public static final ComponentTypeId ID = ComponentTypeId.of("cc_virtual_coms:serial_board");

    public static final VirtualComsComponent INSTANCE = new VirtualComsComponent();

    private static final ComponentSchema EMPTY_SCHEMA = ComponentSchema.of(List.of(), List.of());

    private VirtualComsComponent() {
    }

    @Override
    public ComponentTypeId id() {
        return ID;
    }

    @Override
    public ComponentSchema schema(ComponentConfig config) {
        return config instanceof Config boardConfig ? boardConfig.schema() : EMPTY_SCHEMA;
    }

    @Override
    public ComponentConfig defaultConfig() {
        return Config.empty();
    }

    @Override
    public ComponentMemory createMemory(ComponentConfig config) {
        return EmptyComponentMemory.INSTANCE;
    }

    @Override
    public ComponentSemantics semantics(ComponentConfig config) {
        ComponentSchema schema = schema(config);
        Map<String, Set<String>> dependencies = new LinkedHashMap<>();
        schema.outputs().forEach(output -> dependencies.put(output.name(), Set.of()));
        return new ComponentSemantics(true, true, true, Set.of(ExecutionDomain.GAME_TICK), dependencies);
    }

    /**
     * Pulls values out of the board and onto the graph's output ports. Runs before the graph is
     * evaluated, so anything wired downstream sees this tick's value.
     *
     * <p><b>Must iterate {@code schema.outputs()}, never {@code board.ports()}.</b>
     * The schema is the contract the circuit runtime knows about; the board's terminal list can be
     * ahead of it. Concretely: the player adds a terminal in the GUI, {@code board.ports()} gains it
     * immediately, but the endpoint's schema is only rebuilt on the next {@code refreshEndpoint}.
     * Synaxis ticks circuits from its own {@code ServerTickEvent.Post} listener, which can run before
     * ours - writing a port the schema has not heard of yet throws
     * {@code IllegalArgumentException: Unknown output port: <name>} and takes the server down.
     *
     * <p>Iterating the schema makes that window harmless: a terminal added this tick simply starts
     * being published on the next one.
     */
    @Override
    public void evaluate(EvalContext ctx, ComponentConfig config, ComponentMemory memory,
                         SignalReader in, SignalWriter out) {
        if (!(config instanceof Config boardConfig)) {
            return;
        }
        VirtualComsBlockEntity board = boardConfig.board();
        ComponentSchema schema = boardConfig.schema();

        if (board == null) {
            writeDefaults(schema, out);
            return;
        }

        for (PortDef def : schema.outputs()) {
            SerialPort port = board.port(def.name()).orElse(null);
            if (port == null) {
                // 端子在 schema 之后被删掉了：写默认值，别让下游悬空
                out.write(new OutputPort(def.name()), def.defaultValue());
                continue;
            }
            SignalValue value = board.readForSynaxis(def.name());
            out.write(new OutputPort(def.name()), value == null ? def.defaultValue() : value);
        }
    }

    /**
     * Pushes graph values into the board for every {@link PortDirection#IN} terminal.
     *
     * <p>Same rule as {@link #evaluate}: drive off the schema, not the live terminal list.
     */
    @Override
    public void step(EvalContext ctx, ComponentConfig config, ComponentMemory memory,
                     SignalReader in, SignalWriter out) {
        if (!(config instanceof Config boardConfig) || boardConfig.board() == null) {
            return;
        }
        VirtualComsBlockEntity board = boardConfig.board();
        ComponentSchema schema = boardConfig.schema();

        for (PortDef def : schema.inputs()) {
            SerialPort port = board.port(def.name()).orElse(null);
            if (port == null) {
                continue;
            }
            SignalValue value = in.read(new InputPort(def.name()));
            if (value != null) {
                board.writeFromSynaxis(def.name(), value);
            } else {
                board.clearMissingSynaxisInput(def.name());
            }
        }
    }

    private static void writeDefaults(ComponentSchema schema, SignalWriter out) {
        schema.outputs().forEach(output -> out.write(new OutputPort(output.name()), output.defaultValue()));
    }

    /**
     * Per-instance component configuration.
     *
     * @param board  the owning block entity, or {@code null} for the detached default config
     * @param schema the port layout derived from the board's terminal list
     */
    public record Config(VirtualComsBlockEntity board, ComponentSchema schema) implements ComponentConfig {

        public static Config empty() {
            return new Config(null, EMPTY_SCHEMA);
        }
    }
}
