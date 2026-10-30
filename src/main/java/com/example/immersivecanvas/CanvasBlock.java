package com.example.immersivecanvas;

import com.example.immersivecanvas.client.CanvasClient;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.ScheduledTickAccess;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.List;

public class CanvasBlock extends BaseEntityBlock {
    public static final MapCodec<CanvasBlock> CODEC = simpleCodec(CanvasBlock::new);
    public static final EnumProperty<Direction> FACING = BlockStateProperties.FACING;
    /** Position de ce bloc dans un grand tableau (0,0 = bloc maître). */
    public static final IntegerProperty PX = IntegerProperty.create("px", 0, CanvasData.MAX_BLOCKS - 1);
    public static final IntegerProperty PY = IntegerProperty.create("py", 0, CanvasData.MAX_BLOCKS - 1);

    // Boîte de sélection très fine, collée au mur (la peinture n'a pas d'épaisseur)
    private static final VoxelShape NORTH = box(0, 0, 15, 16, 16, 16);
    private static final VoxelShape SOUTH = box(0, 0, 0, 16, 16, 1);
    private static final VoxelShape EAST = box(0, 0, 0, 1, 16, 16);
    private static final VoxelShape WEST = box(15, 0, 0, 16, 16, 16);
    private static final VoxelShape UP = box(0, 0, 0, 16, 1, 16);
    private static final VoxelShape DOWN = box(0, 15, 0, 16, 16, 16);

    public CanvasBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(PX, 0).setValue(PY, 0));
    }

    @Override protected MapCodec<? extends BaseEntityBlock> codec() { return CODEC; }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, PX, PY);
    }

    public static boolean isMaster(BlockState s) {
        return s.getValue(PX) == 0 && s.getValue(PY) == 0;
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext ctx) {
        Direction f = ctx.getClickedFace();
        BlockState s = defaultBlockState().setValue(FACING, f);
        Level level = ctx.getLevel();
        BlockPos pos = ctx.getClickedPos();
        if (!s.canSurvive(level, pos)) return null;
        CanvasData d = ctx.getItemInHand().get(ImmersiveCanvas.CANVAS_DATA.get());
        if (d != null && d.isValid() && (d.bw() > 1 || d.bh() > 1)) {
            if (!CanvasGroup.canFit(level, pos, f, d.bw(), d.bh(), 1, 1)) return null;
        }
        return s;
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (!level.isClientSide()) {
            CanvasData d = stack.get(ImmersiveCanvas.CANVAS_DATA.get());
            if (d != null && d.isValid() && (d.bw() > 1 || d.bh() > 1)) {
                CanvasGroup.placeParts(level, pos, state.getValue(FACING), d.bw(), d.bh(), 1, 1);
            }
        }
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        return switch (state.getValue(FACING)) {
            case NORTH -> NORTH;
            case SOUTH -> SOUTH;
            case EAST -> EAST;
            case WEST -> WEST;
            case UP -> UP;
            case DOWN -> DOWN;
        };
    }

    @Override
    protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        return Shapes.empty();
    }

    @Override
    protected boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        Direction facing = state.getValue(FACING);
        BlockPos support = pos.relative(facing.getOpposite());
        return level.getBlockState(support).isFaceSturdy(level, support, facing);
    }

    @Override
    protected BlockState updateShape(BlockState state, LevelReader level, ScheduledTickAccess ticks, BlockPos pos,
                                     Direction dir, BlockPos neighborPos, BlockState neighborState, RandomSource random) {
        if (dir == state.getValue(FACING).getOpposite() && !state.canSurvive(level, pos)) {
            return Blocks.AIR.defaultBlockState();
        }
        return super.updateShape(state, level, ticks, pos, dir, neighborPos, neighborState, random);
    }

    @Override protected RenderShape getRenderShape(BlockState state) { return RenderShape.MODEL; }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new CanvasBlockEntity(pos, state);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (level.isClientSide()) {
            CanvasClient.openEditor(pos);
        }
        return InteractionResult.SUCCESS;
    }

    /** Une partie ne rend jamais d'objet (sauf si on la casse à la main : elle reprend alors l'image du maître). */
    @Override
    protected List<ItemStack> getDrops(BlockState state, LootParams.Builder params) {
        if (!isMaster(state)) {
            BlockEntity be = params.getOptionalParameter(LootContextParams.BLOCK_ENTITY);
            if (!(be instanceof CanvasBlockEntity c) || !c.isPromoted()) return List.of();
        }
        return super.getDrops(state, params);
    }

    /** Casse par un joueur : tout le groupe disparaît, un seul objet (avec l'image) est lâché. */
    @Override
    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        if (!level.isClientSide()) {
            BlockPos master = CanvasGroup.masterOf(state, pos);
            Direction f = state.getValue(FACING);
            if (!master.equals(pos)
                    && level.getBlockEntity(master) instanceof CanvasBlockEntity mbe
                    && level.getBlockEntity(pos) instanceof CanvasBlockEntity pbe) {
                pbe.promote(mbe.getData());
            }
            CanvasGroup.removeAllExcept(level, master, f, pos);
        }
        return super.playerWillDestroy(level, pos, state, player);
    }

    /** Casse "naturelle" (mur retiré, explosion...) : on nettoie le reste du groupe. */
    @Override
    protected void affectNeighborsAfterRemoval(BlockState state, ServerLevel level, BlockPos pos, boolean movedByPiston) {
        super.affectNeighborsAfterRemoval(state, level, pos, movedByPiston);
        if (CanvasGroup.GUARD) return;
        BlockPos master = CanvasGroup.masterOf(state, pos);
        Direction f = state.getValue(FACING);
        CanvasGroup.GUARD = true;
        try {
            if (!master.equals(pos)) {
                BlockState ms = level.getBlockState(master);
                if (CanvasGroup.isMember(ms, f, 0, 0)) {
                    Block.dropResources(ms, level, master, level.getBlockEntity(master));
                }
            }
            CanvasGroup.removeAllExcept(level, master, f, pos);
        } finally {
            CanvasGroup.GUARD = false;
        }
    }
}
