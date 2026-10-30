package com.example.immersivecanvas;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Un grand tableau = un bloc "maître" (px=0, py=0, en bas à gauche vu de face) + des blocs "parties".
 * Seul le maître porte l'image et la dessine sur tout le tableau.
 */
public final class CanvasGroup {
    /** Vrai pendant qu'on retire/pose des blocs du groupe nous-mêmes (évite les réactions en cascade). */
    static boolean GUARD = false;

    private static final int SILENT = 2 | 16 | 32; // clients + pas de mise à jour de forme + pas de drops

    private CanvasGroup() {}

    public static Direction rightOf(Direction f) {
        return switch (f) {
            case UP, DOWN -> Direction.WEST;
            default -> f.getCounterClockWise();
        };
    }

    public static Direction upOf(Direction f) {
        return switch (f) {
            case UP -> Direction.SOUTH;
            case DOWN -> Direction.NORTH;
            default -> Direction.UP;
        };
    }

    public static BlockPos partPos(BlockPos master, Direction f, int px, int py) {
        return master.relative(rightOf(f), px).relative(upOf(f), py);
    }

    public static BlockPos masterOf(BlockState s, BlockPos pos) {
        Direction f = s.getValue(CanvasBlock.FACING);
        return pos.relative(rightOf(f), -s.getValue(CanvasBlock.PX)).relative(upOf(f), -s.getValue(CanvasBlock.PY));
    }

    public static BlockState partState(Direction f, int px, int py) {
        return ImmersiveCanvas.CANVAS.get().defaultBlockState()
                .setValue(CanvasBlock.FACING, f)
                .setValue(CanvasBlock.PX, px)
                .setValue(CanvasBlock.PY, py);
    }

    public static boolean isMember(BlockState s, Direction f, int px, int py) {
        return s.getBlock() instanceof CanvasBlock
                && s.getValue(CanvasBlock.FACING) == f
                && s.getValue(CanvasBlock.PX) == px
                && s.getValue(CanvasBlock.PY) == py;
    }

    /** Peut-on avoir un groupe bw x bh ? Les blocs déjà membres de l'ancien groupe (oldBw x oldBh) sont acceptés. */
    public static boolean canFit(Level level, BlockPos master, Direction f, int bw, int bh, int oldBw, int oldBh) {
        for (int px = 0; px < bw; px++) {
            for (int py = 0; py < bh; py++) {
                if (px == 0 && py == 0) continue;
                BlockPos p = partPos(master, f, px, py);
                BlockState cur = level.getBlockState(p);
                if (px < oldBw && py < oldBh && isMember(cur, f, px, py)) continue;
                if (!cur.canBeReplaced()) return false;
                if (!partState(f, px, py).canSurvive(level, p)) return false;
            }
        }
        return true;
    }

    /** Retire les parties en trop de l'ancien groupe et pose les nouvelles parties. */
    public static void placeParts(Level level, BlockPos master, Direction f, int bw, int bh, int oldBw, int oldBh) {
        GUARD = true;
        try {
            for (int px = 0; px < oldBw; px++) {
                for (int py = 0; py < oldBh; py++) {
                    if (px == 0 && py == 0) continue;
                    if (px >= bw || py >= bh) {
                        BlockPos p = partPos(master, f, px, py);
                        if (isMember(level.getBlockState(p), f, px, py)) {
                            level.setBlock(p, Blocks.AIR.defaultBlockState(), SILENT);
                        }
                    }
                }
            }
            for (int px = 0; px < bw; px++) {
                for (int py = 0; py < bh; py++) {
                    if (px == 0 && py == 0) continue;
                    BlockPos p = partPos(master, f, px, py);
                    if (px < oldBw && py < oldBh && isMember(level.getBlockState(p), f, px, py)) continue;
                    level.setBlock(p, partState(f, px, py), 3);
                }
            }
        } finally {
            GUARD = false;
        }
    }

    /** Retire tout le groupe sauf le bloc "except" (sans drops). */
    public static void removeAllExcept(Level level, BlockPos master, Direction f, BlockPos except) {
        boolean old = GUARD;
        GUARD = true;
        try {
            for (int px = 0; px < CanvasData.MAX_BLOCKS; px++) {
                for (int py = 0; py < CanvasData.MAX_BLOCKS; py++) {
                    BlockPos p = partPos(master, f, px, py);
                    if (p.equals(except)) continue;
                    if (isMember(level.getBlockState(p), f, px, py)) {
                        level.setBlock(p, Blocks.AIR.defaultBlockState(), SILENT);
                    }
                }
            }
        } finally {
            GUARD = old;
        }
    }

    /** Appliqué côté serveur quand un joueur enregistre un dessin. */
    public static void apply(ServerPlayer player, BlockPos master, CanvasData data) {
        Level level = player.level();
        BlockState ms = level.getBlockState(master);
        if (!(ms.getBlock() instanceof CanvasBlock)) return;
        if (ms.getValue(CanvasBlock.PX) != 0 || ms.getValue(CanvasBlock.PY) != 0) return;
        if (!(level.getBlockEntity(master) instanceof CanvasBlockEntity be)) return;

        Direction f = ms.getValue(CanvasBlock.FACING);
        CanvasData old = be.getData();
        int oldBw = old.isValid() ? old.bw() : 1;
        int oldBh = old.isValid() ? old.bh() : 1;

        if (!canFit(level, master, f, data.bw(), data.bh(), oldBw, oldBh)) {
            player.displayClientMessage(Component.translatable("message.immersivecanvas.no_space"), true);
            return;
        }
        placeParts(level, master, f, data.bw(), data.bh(), oldBw, oldBh);
        be.setData(data);
    }

    @SuppressWarnings("unused")
    private static void unused(Player p) {}
}
