package com.ccvirtualcoms.net;

import com.ccvirtualcoms.CCVirtualComs;
import com.ccvirtualcoms.content.PortDirection;
import com.ccvirtualcoms.content.PortType;
import com.ccvirtualcoms.content.SerialPort;
import com.ccvirtualcoms.content.VirtualComsBlockEntity;
import io.netty.buffer.ByteBuf;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Client to server: replace a board's whole configuration.
 *
 * <p>The GUI is declarative - it always sends the complete desired state rather than a stream of
 * add/remove diffs. That keeps the two sides trivially consistent and makes the server the single
 * point of validation.
 */
public record ConfigPayload(BlockPos pos, String comId, List<SerialPort> ports) implements CustomPacketPayload {

    private static final Logger LOGGER = LoggerFactory.getLogger("CCVirtualComs/Config");

    /** Upper bound on terminals per board, protecting the server from a malicious client. */
    public static final int MAX_PORTS = 256;

    /** Maximum distance a player may be from the board while editing it. */
    private static final double MAX_EDIT_DISTANCE_SQ = 64.0D * 64.0D;

    public static final CustomPacketPayload.Type<ConfigPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(CCVirtualComs.MOD_ID, "config"));

    public static final StreamCodec<ByteBuf, ConfigPayload> STREAM_CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, ConfigPayload::pos,
            ByteBufCodecs.STRING_UTF8, ConfigPayload::comId,
            SerialPortCodec.LIST, ConfigPayload::ports,
            ConfigPayload::new);

    /** Server side application of a configuration request. */
    public void handle(IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            ServerLevel level = player.serverLevel();
            BlockEntity blockEntity = level.getBlockEntity(this.pos);
            if (!(blockEntity instanceof VirtualComsBlockEntity board)) {
                return;
            }
            if (player.distanceToSqr(this.pos.getX() + 0.5D, this.pos.getY() + 0.5D, this.pos.getZ() + 0.5D)
                    > MAX_EDIT_DISTANCE_SQ) {
                LOGGER.debug("Ignoring configuration from {} - out of range", player.getGameProfile().getName());
                return;
            }

            String error = board.applyConfiguration(this.comId, this.ports);
            if (error != null) {
                LOGGER.debug("Rejected configuration from {}: {}", player.getGameProfile().getName(), error);
            }
            StatusPayload.sendTo(player, this.pos, board.status(), board.revision(),
                    VirtualComsServer.get().isRunning(), VirtualComsServer.get().connectionCount());
        });
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    // ------------------------------------------------------------------ port list codec

    /**
     * Codec for the terminal list. Kept here rather than on the record so that
     * {@link SerialPort} stays a pure domain type.
     */
    private static final class SerialPortCodec {

        private static final StreamCodec<ByteBuf, SerialPort> INSTANCE = StreamCodec.of(
                SerialPortCodec::encode, SerialPortCodec::decode);

        private static final StreamCodec<ByteBuf, List<SerialPort>> LIST =
                new StreamCodec<>() {
                    @Override
                    public List<SerialPort> decode(ByteBuf buffer) {
                        int size = Math.min(ByteBufCodecs.VAR_INT.decode(buffer), MAX_PORTS);
                        List<SerialPort> result = new ArrayList<>(size);
                        for (int index = 0; index < size; index++) {
                            result.add(INSTANCE.decode(buffer));
                        }
                        return result;
                    }

                    @Override
                    public void encode(ByteBuf buffer, List<SerialPort> value) {
                        List<SerialPort> safe = value == null ? List.of() : value;
                        int size = Math.min(safe.size(), MAX_PORTS);
                        ByteBufCodecs.VAR_INT.encode(buffer, size);
                        for (int index = 0; index < size; index++) {
                            INSTANCE.encode(buffer, safe.get(index));
                        }
                    }
                };

        private static void encode(ByteBuf buffer, SerialPort port) {
            ByteBufCodecs.STRING_UTF8.encode(buffer, port.name());
            ByteBufCodecs.VAR_INT.encode(buffer, port.type().ordinal());
            ByteBufCodecs.VAR_INT.encode(buffer, port.direction().ordinal());
            buffer.writeBoolean(port.saveLastValue());
        }

        private static SerialPort decode(ByteBuf buffer) {
            String name = ByteBufCodecs.STRING_UTF8.decode(buffer);
            PortType type = ordinal(PortType.values(), ByteBufCodecs.VAR_INT.decode(buffer), PortType.REAL);
            PortDirection direction =
                    ordinal(PortDirection.values(), ByteBufCodecs.VAR_INT.decode(buffer), PortDirection.OUT);
            boolean save = buffer.readBoolean();
            try {
                return new SerialPort(name, type, direction, save);
            } catch (IllegalArgumentException ex) {
                // A malformed name from a client is replaced by a safe placeholder so that one bad
                // entry cannot abort the whole configuration update.
                return new SerialPort("port_" + Integer.toHexString(name == null ? 0 : name.hashCode()),
                        type, direction, save);
            }
        }

        private static <E> E ordinal(E[] values, int index, E fallback) {
            return index >= 0 && index < values.length ? values[index] : fallback;
        }
    }

    /** Drops duplicates, {@code null}s and anything past {@link #MAX_PORTS}. */
    private static List<SerialPort> sanitize(List<SerialPort> ports) {
        if (ports == null) {
            return List.of();
        }
        List<SerialPort> result = new ArrayList<>(Math.min(ports.size(), MAX_PORTS));
        Set<String> seen = new LinkedHashSet<>();
        for (SerialPort port : ports) {
            if (port == null || result.size() >= MAX_PORTS) {
                continue;
            }
            if (seen.add(port.name())) {
                result.add(port);
            }
        }
        return result;
    }

    public ConfigPayload {
        ports = sanitize(ports);
        comId = comId == null ? VirtualComsBlockEntity.DEFAULT_COM_ID : comId;
    }
}
