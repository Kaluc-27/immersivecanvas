package com.example.immersivecanvas;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentGetter;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;

public class CanvasBlockEntity extends BlockEntity {
    private CanvasData data = CanvasData.EMPTY;
    private int revision = 0;      // côté client : détecte quand regénérer la texture
    private boolean promoted = false; // une partie cassée à la main qui doit lâcher l'image du groupe

    public CanvasBlockEntity(BlockPos pos, BlockState state) {
        super(ImmersiveCanvas.CANVAS_BE.get(), pos, state);
    }

    public CanvasData getData() { return data; }
    public int getRevision() { return revision; }
    public boolean isPromoted() { return promoted; }

    /** Côté serveur (bloc maître uniquement). */
    public void setData(CanvasData newData) {
        this.data = newData;
        this.revision++;
        setChanged();
        if (level != null) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }

    public void promote(CanvasData masterData) {
        this.data = masterData;
        this.promoted = true;
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        if (data.isValid()) output.store("canvas", CanvasData.CODEC, data);
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        CanvasData d = input.read("canvas", CanvasData.CODEC).orElse(CanvasData.EMPTY);
        this.data = d.isValid() ? d : CanvasData.EMPTY;
        this.revision++;
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        return saveCustomOnly(registries);
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    protected void collectImplicitComponents(DataComponentMap.Builder builder) {
        super.collectImplicitComponents(builder);
        if (data.isValid()) builder.set(ImmersiveCanvas.CANVAS_DATA.get(), data);
    }

    @Override
    protected void applyImplicitComponents(DataComponentGetter components) {
        super.applyImplicitComponents(components);
        CanvasData d = components.getOrDefault(ImmersiveCanvas.CANVAS_DATA.get(), CanvasData.EMPTY);
        this.data = d.isValid() ? d : CanvasData.EMPTY;
        this.revision++;
    }

    /** Boîte de rendu étendue à tout le grand tableau (pas d'@Override : selon la version l'API est ici ou dans le renderer). */
    public AABB getRenderBoundingBox() {
        AABB box = new AABB(worldPosition);
        if (data.isValid() && (data.bw() > 1 || data.bh() > 1)) {
            Direction f = getBlockState().getValue(CanvasBlock.FACING);
            BlockPos far = CanvasGroup.partPos(worldPosition, f, data.bw() - 1, data.bh() - 1);
            box = box.minmax(new AABB(far));
        }
        return box.inflate(0.05);
    }
}
