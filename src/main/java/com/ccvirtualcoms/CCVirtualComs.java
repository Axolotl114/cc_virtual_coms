package com.ccvirtualcoms;

import com.ccvirtualcoms.content.CCVirtualComsRegistry;
import com.ccvirtualcoms.content.VirtualComsBlockEntity;
import com.ccvirtualcoms.net.ConfigPayload;
import com.ccvirtualcoms.net.StatusPayload;
import com.ccvirtualcoms.net.VirtualComsServer;
import com.ccvirtualcoms.synaxis.VirtualComsComponent;
import com.verr1.synaxis.foundation.cimulink.game.body.PlantPortProviders;
import com.verr1.synaxis.foundation.cimulink.game.runtime.CimulinkWorldRuntimes;
import dan200.computercraft.api.peripheral.PeripheralCapability;
import java.util.Optional;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * CC Synaxis Virtual Coms.
 *
 * <p>Adds a Synaxis circuit board that presents configurable virtual serial terminals. Values can be
 * exchanged three ways:
 *
 * <ul>
 *   <li>with a Synaxis Cimulink circuit, through a registered {@link VirtualComsComponent};</li>
 *   <li>with a CC:Tweaked computer, through the {@code cc_virtual_coms} peripheral;</li>
 *   <li>with an out-of-game Python program, through the local TCP listener in
 *       {@link VirtualComsServer}.</li>
 * </ul>
 */
@Mod(CCVirtualComs.MOD_ID)
public final class CCVirtualComs {

    public static final String MOD_ID = "cc_virtual_coms";

    public static final Logger LOGGER = LoggerFactory.getLogger("CCVirtualComs");

    public CCVirtualComs(IEventBus modEventBus, ModContainer modContainer) {
        CCVirtualComsRegistry.BLOCKS.register(modEventBus);
        CCVirtualComsRegistry.ITEMS.register(modEventBus);
        CCVirtualComsRegistry.BLOCK_ENTITY_TYPES.register(modEventBus);
        CCVirtualComsRegistry.CREATIVE_TABS.register(modEventBus);

        modEventBus.addListener(this::commonSetup);
        modEventBus.addListener(this::registerCapabilities);
        modEventBus.addListener(this::registerPayloads);

        NeoForge.EVENT_BUS.addListener(this::onServerTick);
        NeoForge.EVENT_BUS.addListener(this::onServerStarted);
        NeoForge.EVENT_BUS.addListener(this::onServerStopped);

        LOGGER.info("CC Synaxis Virtual Coms is loading");
    }

    // ------------------------------------------------------------------ mod bus

    private void commonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(() -> {
            // Make the board usable as a first class Cimulink component.
            CimulinkWorldRuntimes.componentRegistry().register(VirtualComsComponent.INSTANCE);
            LOGGER.info("Registered Cimulink component type {}", VirtualComsComponent.ID);

            // Let Synaxis reach the board from inside a circuit graph.
            PlantPortProviders.register(
                    CCVirtualComsRegistry.VIRTUAL_COMS_BE.get(),
                    (blockEntity, endpointId, requestedName) ->
                            blockEntity instanceof VirtualComsBlockEntity board
                                    ? Optional.of(board.createPlantPort(endpointId, requestedName))
                                    : Optional.empty());

            // Start listening for out-of-game programs as soon as the game is up.
            VirtualComsServer server = VirtualComsServer.get();
            if (!server.start(VirtualComsServer.configuredHost(), VirtualComsServer.configuredPort())) {
                LOGGER.warn("Virtual coms listener is unavailable; the Python bridge will not work. {}",
                        server.lastError());
            }
        });
    }

    private void registerCapabilities(RegisterCapabilitiesEvent event) {
        // Exposes the CC:Tweaked peripheral on every side of the board.
        event.registerBlockEntity(
                PeripheralCapability.get(),
                CCVirtualComsRegistry.VIRTUAL_COMS_BE.get(),
                (board, side) -> board.peripheral());
    }

    private void registerPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");
        registrar.playToServer(ConfigPayload.TYPE, ConfigPayload.STREAM_CODEC,
                (payload, context) -> payload.handle(context));
        registrar.playToClient(StatusPayload.TYPE, StatusPayload.STREAM_CODEC,
                (payload, context) -> payload.handle(context));
    }

    // ------------------------------------------------------------------ game bus

    /**
     * Drains the socket layer's inbound queue on the server thread.
     *
     * <p>This is the single point where out-of-game writes are applied to game state. Driving it
     * from a tick event rather than {@code MinecraftServer#execute} keeps the timing under this
     * mod's control and independent of how the integrated server schedules its own task queue.
     */
    private void onServerTick(ServerTickEvent.Post event) {
        VirtualComsServer listener = VirtualComsServer.get();
        listener.drainGameTasks();
        listener.tickDevices();
    }

    private void onServerStarted(ServerStartedEvent event) {
        VirtualComsServer listener = VirtualComsServer.get();

        // A dedicated server may not have had common setup run with a port free; retry is cheap.
        if (!listener.isRunning()) {
            listener.start(VirtualComsServer.configuredHost(), VirtualComsServer.configuredPort());
        }
        LOGGER.info("Virtual coms ready: {}", listener.describeState());
    }

    private void onServerStopped(ServerStoppedEvent event) {
        // Nothing to release: the listener outlives individual worlds on a client, and the queue is
        // simply drained again the next time a server ticks.
        LOGGER.info("Virtual coms server stopped; final state: {}", VirtualComsServer.get().describeState());
    }
}
