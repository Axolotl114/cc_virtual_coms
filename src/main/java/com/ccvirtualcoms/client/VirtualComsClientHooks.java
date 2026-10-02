package com.ccvirtualcoms.client;

import com.ccvirtualcoms.content.VirtualComsBlockEntity;
import com.ccvirtualcoms.net.StatusPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import org.jetbrains.annotations.Nullable;

/**
 * Client-only entry points.
 *
 * <p>Referenced from common code strictly behind a {@code level.isClientSide()} guard, so this class
 * is never loaded on a dedicated server.
 */
public final class VirtualComsClientHooks {

    @Nullable
    private static VirtualComsConfigScreen openScreen;

    private VirtualComsClientHooks() {
    }

    /** Opens (or re-focuses) the configuration screen for the board at {@code pos}. */
    public static void openConfigScreen(BlockPos pos) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return;
        }
        if (minecraft.level.getBlockEntity(pos) instanceof VirtualComsBlockEntity board) {
            VirtualComsConfigScreen screen = new VirtualComsConfigScreen(pos, board);
            openScreen = screen;
            minecraft.setScreen(screen);
        }
    }

    /** Routes a server status update to the screen that is currently showing that board. */
    public static void onStatus(StatusPayload payload) {
        VirtualComsConfigScreen screen = openScreen;
        if (screen != null && screen.matches(payload.pos())) {
            screen.applyStatus(payload);
        }
    }

    /** Clears the tracked screen when it closes. */
    static void forget(VirtualComsConfigScreen screen) {
        if (openScreen == screen) {
            openScreen = null;
        }
    }
}
