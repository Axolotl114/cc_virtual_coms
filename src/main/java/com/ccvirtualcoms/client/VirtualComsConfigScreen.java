package com.ccvirtualcoms.client;

import com.ccvirtualcoms.content.PortDirection;
import com.ccvirtualcoms.content.PortType;
import com.ccvirtualcoms.content.SerialPort;
import com.ccvirtualcoms.content.VirtualComsBlockEntity;
import com.ccvirtualcoms.net.ConfigPayload;
import com.ccvirtualcoms.net.StatusPayload;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;

/**
 * The in-game configuration screen for a virtual serial port board.
 *
 * <p>Everything the player changes is sent to the server as a complete configuration snapshot; the
 * server validates it, applies it and replies with a {@link StatusPayload} that is shown on the
 * status line. The screen therefore never has to guess whether an edit was legal.
 */
public class VirtualComsConfigScreen extends Screen {

    private static final int PANEL_WIDTH = 430;
    private static final int PANEL_HEIGHT = 276;
    private static final int ROWS_PER_PAGE = 6;
    private static final int ROW_HEIGHT = 20;

    /**
     * How often, in client ticks, the per terminal values are re-read from the block entity.
     * The server mirrors value changes to clients at most every 10 ticks, so polling at the same
     * cadence keeps the display live without busy work.
     */
    private static final int LIVE_REFRESH_INTERVAL = 5;

    private static final int VALUE_COLUMN_X = 312;
    private static final int VALUE_COLUMN_WIDTH = 112;

    private static final int COLOR_PANEL = 0xE6101010;
    private static final int COLOR_BORDER = 0xFF5A5A5A;
    private static final int COLOR_HEADER = 0xFFFFD479;
    private static final int COLOR_TEXT = 0xFFE0E0E0;
    private static final int COLOR_MUTED = 0xFF9A9A9A;
    private static final int COLOR_VALUE = 0xFF7FE08A;

    /** Explains what the direction toggle actually controls, since it no longer gates read/write. */
    private static final Tooltip DIRECTION_TOOLTIP =
            Tooltip.create(Component.translatable("cc_virtual_coms.screen.direction.tooltip"));

    private final BlockPos pos;

    /** Working copy of the terminal list; edits are applied locally and mirrored to the server. */
    private final List<SerialPort> working = new ArrayList<>();

    private int left;
    private int top;
    private int page;

    private PortType newPortType = PortType.REAL;
    private PortDirection newPortDirection = PortDirection.OUT;

    private String comIdDraft = VirtualComsBlockEntity.DEFAULT_COM_ID;
    private String addNameDraft = "";
    private String statusText = "Ready";
    private boolean listenerRunning;
    private int connectionCount;
    private boolean dirty = true;

    /**
     * Live value of each terminal on the current page, refreshed from the client side block entity.
     * Kept separate from {@link #working} so that refreshing never disturbs in-progress edits.
     */
    private final List<String> rowValues = new ArrayList<>();
    private int liveRefreshCounter;

    @Nullable
    private EditBox comIdBox;
    @Nullable
    private EditBox addNameBox;
    @Nullable
    private Button addTypeButton;
    @Nullable
    private Button addDirectionButton;

    public VirtualComsConfigScreen(BlockPos pos, VirtualComsBlockEntity board) {
        super(Component.translatable("cc_virtual_coms.screen.title"));
        this.pos = pos;
        this.adopt(board);
    }

    /** {@code true} when this screen is showing the board at {@code other}. */
    public boolean matches(BlockPos other) {
        return this.pos.equals(other);
    }

    /** Applies a status update pushed by the server. */
    public void applyStatus(StatusPayload payload) {
        this.statusText = payload.status();
        this.listenerRunning = payload.listenerRunning();
        this.connectionCount = payload.connectionCount();
        if (payload.revision() != this.lastSeenRevision) {
            this.lastSeenRevision = payload.revision();
            this.dirty = true;
        }
    }

    private int lastSeenRevision = -1;

    /** Copies the authoritative board state into the working list. */
    private void adopt(VirtualComsBlockEntity board) {
        this.comIdDraft = board.comId();
        this.working.clear();
        this.working.addAll(board.ports());
        this.statusText = board.status();
        this.lastSeenRevision = board.revision();
    }

    /** Re-reads the client side block entity, which the server keeps synchronised. */
    private void refreshFromBoard() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level != null
                && minecraft.level.getBlockEntity(this.pos) instanceof VirtualComsBlockEntity board) {
            this.adopt(board);
        }
    }

    /**
     * Re-reads only the live per terminal values and the status line.
     *
     * <p>Deliberately does <em>not</em> touch {@link #working} or {@link #comIdDraft}: doing so on a
     * timer would fight with whatever the player is currently typing.
     */
    private void refreshLiveValues() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return;
        }
        if (!(minecraft.level.getBlockEntity(this.pos) instanceof VirtualComsBlockEntity board)) {
            return;
        }

        Map<String, String> summary = board.valueSummary();
        this.rowValues.clear();
        for (SerialPort port : this.working) {
            this.rowValues.add(summary.getOrDefault(port.name(), "-"));
        }

        String boardStatus = board.status();
        if (boardStatus != null && !boardStatus.isBlank()) {
            this.statusText = boardStatus;
        }
    }

    @Override
    protected void init() {
        this.left = (this.width - PANEL_WIDTH) / 2;
        this.top = (this.height - PANEL_HEIGHT) / 2;

        if (this.dirty) {
            this.refreshFromBoard();
            this.dirty = false;
        }

        // Populate the value column immediately so the screen is never blank on open.
        if (this.rowValues.isEmpty() && !this.working.isEmpty()) {
            this.refreshLiveValues();
        }

        int x = this.left + 10;
        int y = this.top + 8;

        // ---- header: serial port id -------------------------------------
        this.comIdBox = new EditBox(this.font, x + 100, y, 120, 18,
                Component.translatable("cc_virtual_coms.screen.com"));
        this.comIdBox.setMaxLength(32);
        this.comIdBox.setValue(this.comIdDraft);
        this.comIdBox.setResponder(value -> this.comIdDraft = value);
        this.addRenderableWidget(this.comIdBox);

        this.addRenderableWidget(Button.builder(Component.translatable("cc_virtual_coms.screen.apply"),
                        button -> this.sendConfiguration())
                .bounds(x + 226, y, 60, 18)
                .build());

        // ---- terminal rows ----------------------------------------------
        int rowsTop = this.top + 52;
        int maxPage = Math.max(1, (this.working.size() + ROWS_PER_PAGE - 1) / ROWS_PER_PAGE);
        if (this.page >= maxPage) {
            this.page = maxPage - 1;
        }
        if (this.page < 0) {
            this.page = 0;
        }

        int first = this.page * ROWS_PER_PAGE;
        for (int slot = 0; slot < ROWS_PER_PAGE; slot++) {
            int index = first + slot;
            if (index >= this.working.size()) {
                break;
            }
            SerialPort port = this.working.get(index);
            int rowY = rowsTop + slot * ROW_HEIGHT;

            this.addRenderableWidget(Button.builder(
                            Component.literal(port.type().serializedName()),
                            button -> this.cycleType(index))
                    .bounds(x + 108, rowY, 58, 17)
                    .build());

            this.addRenderableWidget(Button.builder(
                            Component.literal(port.direction().serializedName()),
                            button -> this.cycleDirection(index))
                    .bounds(x + 170, rowY, 52, 17)
                    .tooltip(DIRECTION_TOOLTIP)
                    .build());

            this.addRenderableWidget(Button.builder(
                            Component.literal(port.saveLastValue() ? "keep" : "last"),
                            button -> this.toggleSaveLastValue(index))
                    .bounds(x + 226, rowY, 46, 17)
                    .build());

            this.addRenderableWidget(Button.builder(Component.literal("X"), button -> this.removePort(index))
                    .bounds(x + 276, rowY, 20, 17)
                    .build());
        }

        // ---- pagination --------------------------------------------------
        if (this.working.size() > ROWS_PER_PAGE) {
            this.addRenderableWidget(Button.builder(Component.translatable("cc_virtual_coms.screen.prev"),
                            button -> {
                                this.page = Math.max(0, this.page - 1);
                                this.rebuildWidgets();
                            })
                    .bounds(x, this.top + 178, 52, 17)
                    .build());
            this.addRenderableWidget(Button.builder(Component.translatable("cc_virtual_coms.screen.next"),
                            button -> {
                                this.page++;
                                this.rebuildWidgets();
                            })
                    .bounds(x + 58, this.top + 178, 52, 17)
                    .build());
        }

        // ---- add terminal row --------------------------------------------
        int addY = this.top + 200;
        this.addNameBox = new EditBox(this.font, x, addY, 100, 18,
                Component.translatable("cc_virtual_coms.screen.name"));
        this.addNameBox.setMaxLength(48);
        this.addNameBox.setValue(this.addNameDraft);
        this.addNameBox.setResponder(value -> this.addNameDraft = value);
        this.addRenderableWidget(this.addNameBox);

        this.addTypeButton = this.addRenderableWidget(Button.builder(
                        Component.literal(this.newPortType.serializedName()),
                        button -> {
                            this.newPortType = next(PortType.values(), this.newPortType);
                            button.setMessage(Component.literal(this.newPortType.serializedName()));
                        })
                .bounds(x + 106, addY, 58, 18)
                .build());

        this.addDirectionButton = this.addRenderableWidget(Button.builder(
                        Component.literal(this.newPortDirection.serializedName()),
                        button -> {
                            this.newPortDirection = next(PortDirection.values(), this.newPortDirection);
                            button.setMessage(Component.literal(this.newPortDirection.serializedName()));
                        })
                .bounds(x + 168, addY, 52, 18)
                .tooltip(DIRECTION_TOOLTIP)
                .build());

        this.addRenderableWidget(Button.builder(Component.translatable("cc_virtual_coms.screen.add"),
                        button -> this.addPort())
                .bounds(x + 224, addY, 72, 18)
                .build());

        // ---- done ---------------------------------------------------------
        this.addRenderableWidget(Button.builder(Component.translatable("cc_virtual_coms.screen.done"),
                        button -> this.onClose())
                .bounds(this.left + PANEL_WIDTH - 70, this.top + PANEL_HEIGHT - 22, 60, 18)
                .build());
    }

    /** Cycles to the next constant of an enum, wrapping around. */
    private static <E extends Enum<E>> E next(E[] values, E current) {
        return values[(current.ordinal() + 1) % values.length];
    }

    // ------------------------------------------------------------------ edits

    private void cycleType(int index) {
        SerialPort port = this.working.get(index);
        this.working.set(index, port.withType(next(PortType.values(), port.type())));
        this.sendConfiguration();
        this.rebuildWidgets();
    }

    private void cycleDirection(int index) {
        SerialPort port = this.working.get(index);
        this.working.set(index, port.withDirection(next(PortDirection.values(), port.direction())));
        this.sendConfiguration();
        this.rebuildWidgets();
    }

    private void toggleSaveLastValue(int index) {
        SerialPort port = this.working.get(index);
        this.working.set(index, port.withSaveLastValue(!port.saveLastValue()));
        this.sendConfiguration();
        this.rebuildWidgets();
    }

    private void removePort(int index) {
        if (index >= 0 && index < this.working.size()) {
            this.working.remove(index);
            this.sendConfiguration();
            this.rebuildWidgets();
        }
    }

    private void addPort() {
        String name = this.addNameDraft == null ? "" : this.addNameDraft.trim();
        if (name.isEmpty()) {
            name = this.suggestName();
        }
        if (!SerialPort.isValidName(name)) {
            this.statusText = "Invalid name '" + name + "'";
            return;
        }
        for (SerialPort existing : this.working) {
            if (existing.name().equals(name)) {
                this.statusText = "Terminal '" + name + "' already exists";
                return;
            }
        }
        this.working.add(new SerialPort(name, this.newPortType, this.newPortDirection));
        this.addNameDraft = "";
        if (this.addNameBox != null) {
            this.addNameBox.setValue("");
        }
        this.sendConfiguration();
        this.rebuildWidgets();
    }

    private String suggestName() {
        int index = 1;
        while (this.hasPort("port_" + index)) {
            index++;
        }
        return "port_" + index;
    }

    private boolean hasPort(String name) {
        for (SerialPort port : this.working) {
            if (port.name().equals(name)) {
                return true;
            }
        }
        return false;
    }

    /** Sends the complete working configuration to the server. */
    private void sendConfiguration() {
        PacketDistributor.sendToServer(new ConfigPayload(this.pos, this.comIdDraft, List.copyOf(this.working)));
    }

    // ------------------------------------------------------------------ rendering

    @Override
    public void renderBackground(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(guiGraphics, mouseX, mouseY, partialTick);

        int x0 = this.left;
        int y0 = this.top;
        int x1 = this.left + PANEL_WIDTH;
        int y1 = this.top + PANEL_HEIGHT;

        guiGraphics.fill(x0, y0, x1, y1, COLOR_PANEL);
        guiGraphics.fill(x0, y0, x1, y0 + 1, COLOR_BORDER);
        guiGraphics.fill(x0, y1 - 1, x1, y1, COLOR_BORDER);
        guiGraphics.fill(x0, y0, x0 + 1, y1, COLOR_BORDER);
        guiGraphics.fill(x1 - 1, y0, x1, y1, COLOR_BORDER);

        guiGraphics.drawString(this.font, this.title, x0 + 10, y0 - 12, COLOR_HEADER, true);

        // Header label sits left of the serial port id box.
        guiGraphics.drawString(this.font, Component.translatable("cc_virtual_coms.screen.com"),
                x0 + 10, y0 + 13, COLOR_TEXT, false);

        // Column headers for the terminal table.
        int headerY = y0 + 42;
        guiGraphics.drawString(this.font, Component.translatable("cc_virtual_coms.screen.name"),
                x0 + 12, headerY, COLOR_MUTED, false);
        guiGraphics.drawString(this.font, Component.translatable("cc_virtual_coms.screen.type"),
                x0 + 110, headerY, COLOR_MUTED, false);
        guiGraphics.drawString(this.font, Component.translatable("cc_virtual_coms.screen.direction"),
                x0 + 172, headerY, COLOR_MUTED, false);
        guiGraphics.drawString(this.font, Component.translatable("cc_virtual_coms.screen.value"),
                x0 + VALUE_COLUMN_X, headerY, COLOR_MUTED, false);

        String pageLabel = Component.translatable("cc_virtual_coms.screen.page",
                this.page + 1, Math.max(1, (this.working.size() + ROWS_PER_PAGE - 1) / ROWS_PER_PAGE)).getString();
        guiGraphics.drawString(this.font, Component.translatable("cc_virtual_coms.screen.ports").getString()
                + "  " + pageLabel, x0 + PANEL_WIDTH - 120, headerY, COLOR_HEADER, false);

        // Terminal names.
        int rowsTop = y0 + 52;
        if (this.working.isEmpty()) {
            guiGraphics.drawString(this.font, Component.translatable("cc_virtual_coms.screen.empty"),
                    x0 + 12, rowsTop + 4, COLOR_MUTED, false);
        } else {
            int first = this.page * ROWS_PER_PAGE;
            for (int slot = 0; slot < ROWS_PER_PAGE; slot++) {
                int index = first + slot;
                if (index >= this.working.size()) {
                    break;
                }
                SerialPort port = this.working.get(index);
                int rowY = rowsTop + slot * ROW_HEIGHT;
                guiGraphics.fill(x0 + 8, rowY - 1, x0 + PANEL_WIDTH - 8, rowY + 17,
                        (slot % 2 == 0) ? 0x18FFFFFF : 0x08FFFFFF);
                guiGraphics.drawString(this.font, trim(port.name(), 96), x0 + 12, rowY + 5, COLOR_TEXT, false);

                // Live value, refreshed from the block entity by tick().
                String value = index < this.rowValues.size() ? this.rowValues.get(index) : "-";
                guiGraphics.drawString(this.font, trim(value, VALUE_COLUMN_WIDTH),
                        x0 + VALUE_COLUMN_X, rowY + 5, COLOR_VALUE, false);
            }
        }

        // Usage hint: the direction toggle is about the circuit, not about permissions.
        guiGraphics.drawString(this.font,
                Component.translatable("cc_virtual_coms.screen.hint").getString(),
                x0 + 10, this.top + 224, COLOR_HEADER, false);

        // Status line and socket state.
        int statusY = this.top + 238;
        guiGraphics.drawString(this.font, trim(this.statusText, 260), x0 + 10, statusY, COLOR_MUTED, false);

        String socket;
        if (!this.listenerRunning) {
            socket = Component.translatable("cc_virtual_coms.screen.listenerDown").getString();
        } else if (this.connectionCount > 0) {
            socket = Component.translatable("cc_virtual_coms.screen.connected", this.connectionCount).getString();
        } else {
            socket = Component.translatable("cc_virtual_coms.screen.disconnected").getString();
        }
        guiGraphics.drawString(this.font,
                Component.translatable("cc_virtual_coms.screen.connection", socket).getString(),
                x0 + 10, statusY + 11, COLOR_MUTED, false);
    }

    private String trim(String value, int maxPixels) {
        if (value == null) {
            return "";
        }
        if (this.font.width(value) <= maxPixels) {
            return value;
        }
        return this.font.plainSubstrByWidth(value, maxPixels - 6) + "...";
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        super.render(guiGraphics, mouseX, mouseY, partialTick);
    }

    @Override
    public void tick() {
        super.tick();

        // Values change from outside the game (a Python write, a computer write, the circuit
        // ticking), so poll them on a timer rather than only on configuration changes.
        if (++this.liveRefreshCounter >= LIVE_REFRESH_INTERVAL) {
            this.liveRefreshCounter = 0;
            this.refreshLiveValues();
        }

        // The server answers every configuration change with a status payload; if the revision moved
        // without us asking, another player (or a Lua script) changed the board underneath us.
        if (this.dirty) {
            this.dirty = false;
            this.rebuildWidgets();
        }
    }

    @Override
    public void onClose() {
        VirtualComsClientHooks.forget(this);
        super.onClose();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
