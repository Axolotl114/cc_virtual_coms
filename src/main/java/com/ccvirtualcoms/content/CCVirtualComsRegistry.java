package com.ccvirtualcoms.content;

import com.ccvirtualcoms.CCVirtualComs;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * All registry objects owned by this mod.
 *
 * <p>Kept in a dedicated class so that the main mod class stays focused on event wiring and so the
 * block entity can reference its own type without a circular initialisation hazard.
 */
public final class CCVirtualComsRegistry {

    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(CCVirtualComs.MOD_ID);
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(CCVirtualComs.MOD_ID);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITY_TYPES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, CCVirtualComs.MOD_ID);
    public static final DeferredRegister<CreativeModeTab> CREATIVE_TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, CCVirtualComs.MOD_ID);

    /** The virtual serial port circuit board. */
    public static final DeferredBlock<VirtualComsBlock> VIRTUAL_COMS_BLOCK = BLOCKS.register(
            "virtual_coms_board",
            () -> new VirtualComsBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.METAL)
                    .strength(1.5F)
                    .sound(net.minecraft.world.level.block.SoundType.METAL)
                    .noOcclusion()
                    .isRedstoneConductor((state, level, pos) -> false)
                    .isViewBlocking((state, level, pos) -> false)));

    public static final DeferredItem<BlockItem> VIRTUAL_COMS_ITEM = ITEMS.register(
            "virtual_coms_board",
            () -> new BlockItem(VIRTUAL_COMS_BLOCK.get(), new Item.Properties()));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<VirtualComsBlockEntity>> VIRTUAL_COMS_BE =
            BLOCK_ENTITY_TYPES.register(
                    "virtual_coms_board",
                    () -> BlockEntityType.Builder
                            .of(VirtualComsBlockEntity::new, VIRTUAL_COMS_BLOCK.get())
                            .build(null));

    /** Dedicated creative tab holding every item this mod adds. */
    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> CREATIVE_TAB = CREATIVE_TABS.register(
            "main",
            () -> CreativeModeTab.builder()
                    .title(net.minecraft.network.chat.Component.translatable("itemGroup." + CCVirtualComs.MOD_ID))
                    .icon(() -> VIRTUAL_COMS_ITEM.get().getDefaultInstance())
                    .displayItems((parameters, output) -> output.accept(VIRTUAL_COMS_ITEM.get()))
                    .build());

    private CCVirtualComsRegistry() {
    }
}
