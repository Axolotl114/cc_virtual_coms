package com.ccvirtualcoms.net;

import com.ccvirtualcoms.CCVirtualComs;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Server to client: the board's status line plus the live socket state.
 *
 * <p>Sent after every configuration change so the GUI can show whether the request was accepted and
 * whether an out-of-game program is currently attached.
 *
 * @param pos             the board this status belongs to
 * @param status          free-form status text (validation failures land here)
 * @param revision        the board's configuration revision after the change
 * @param listenerRunning whether the TCP listener is up
 * @param connectionCount number of connected out-of-game programs
 */
public record StatusPayload(BlockPos pos, String status, int revision, boolean listenerRunning, int connectionCount)
        implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<StatusPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(CCVirtualComs.MOD_ID, "status"));

    public static final StreamCodec<ByteBuf, StatusPayload> STREAM_CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, StatusPayload::pos,
            ByteBufCodecs.STRING_UTF8, StatusPayload::status,
            ByteBufCodecs.VAR_INT, StatusPayload::revision,
            ByteBufCodecs.BOOL, StatusPayload::listenerRunning,
            ByteBufCodecs.VAR_INT, StatusPayload::connectionCount,
            StatusPayload::new);

    public StatusPayload {
        status = status == null ? "" : status;
    }

    /** Pushes a status update to a single player. */
    public static void sendTo(ServerPlayer player, BlockPos pos, String status, int revision,
                              boolean listenerRunning, int connectionCount) {
        PacketDistributor.sendToPlayer(player,
                new StatusPayload(pos, status, revision, listenerRunning, connectionCount));
    }

    /** Client side handling: hands the status to the open screen, if any. */
    public void handle(IPayloadContext context) {
        context.enqueueWork(() -> com.ccvirtualcoms.client.VirtualComsClientHooks.onStatus(this));
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** Convenience for the client screen: is the player still near enough to be editing this board? */
    public boolean isNear(Player player) {
        return player.distanceToSqr(this.pos.getX() + 0.5D, this.pos.getY() + 0.5D, this.pos.getZ() + 0.5D) < 64.0D;
    }
}
