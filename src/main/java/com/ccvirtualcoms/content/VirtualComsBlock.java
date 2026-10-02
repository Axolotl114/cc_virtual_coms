package com.ccvirtualcoms.content;

import com.mojang.serialization.MapCodec;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

/**
 * The virtual serial port circuit board.
 *
 * <p>Visually and geometrically it behaves like a Synaxis Cimulink board: it mounts flat against the
 * face it was placed on. Its model is this mod's own {@code cc_virtual_coms:block/virtual_coms_board}
 * (a Blockbench export shipped with the mod, along with its three textures).
 *
 * <p>Right-clicking with an empty hand opens the configuration screen on the client; the server side
 * simply accepts the interaction.
 */
public class VirtualComsBlock extends BaseEntityBlock {

    public static final MapCodec<VirtualComsBlock> CODEC = simpleCodec(VirtualComsBlock::new);

    /** The face the board is mounted on. */
    public static final DirectionProperty FACING = BlockStateProperties.FACING;

    /** Slightly proud of the mounting surface, matching the Synaxis board silhouette. */
    private static final Map<Direction, VoxelShape> SHAPES = Map.of(
            Direction.UP, Block.box(2.0D, 0.0D, 2.0D, 14.0D, 3.0D, 14.0D),
            Direction.DOWN, Block.box(2.0D, 13.0D, 2.0D, 14.0D, 16.0D, 14.0D),
            Direction.NORTH, Block.box(2.0D, 2.0D, 13.0D, 14.0D, 14.0D, 16.0D),
            Direction.SOUTH, Block.box(2.0D, 2.0D, 0.0D, 14.0D, 14.0D, 3.0D),
            Direction.WEST, Block.box(13.0D, 2.0D, 2.0D, 16.0D, 14.0D, 14.0D),
            Direction.EAST, Block.box(0.0D, 2.0D, 2.0D, 3.0D, 14.0D, 14.0D));

    public VirtualComsBlock(Properties properties) {
        super(properties);
        this.registerDefaultState(this.defaultBlockState().setValue(FACING, Direction.UP));
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new VirtualComsBlockEntity(pos, state);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return this.defaultBlockState().setValue(FACING, context.getClickedFace());
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    /**
     * No block entity ticker: the board is advanced by {@code VirtualComsServer.tickDevices()} from
     * a server tick event instead, so its lifecycle does not depend on when the level happens to
     * attach a ticker to a freshly placed block.
     */
    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return null;
    }

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
                                              Player player, InteractionHand hand, BlockHitResult hit) {
        // Only a bare hand reaches useWithoutItem (and therefore the configuration screen).
        // Holding anything - most importantly the Synaxis tool gun - must fall through to the item
        // so that it can perform its own action (linking the board into a circuit).
        return stack.isEmpty()
                ? ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION
                : ItemInteractionResult.SKIP_DEFAULT_BLOCK_INTERACTION;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (level.isClientSide()) {
            // The configuration screen reads the client side block entity, which the server keeps
            // up to date through the block entity update packet.
            com.ccvirtualcoms.client.VirtualComsClientHooks.openConfigScreen(pos);
            return InteractionResult.SUCCESS;
        }
        return InteractionResult.CONSUME;
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPES.getOrDefault(state.getValue(FACING), SHAPES.get(Direction.UP));
    }

    @Override
    protected boolean propagatesSkylightDown(BlockState state, BlockGetter level, BlockPos pos) {
        return true;
    }
}
