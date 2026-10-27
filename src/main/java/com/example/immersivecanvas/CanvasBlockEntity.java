package com.example.immersivecanvas;

import net.minecraft.core.BlockPos;
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

public class CanvasBlockEntity extends BlockEntity {
    private CanvasData data = CanvasData.EMPTY;
    private int revision = 0; // sert côté client à savoir quand regénérer la texture

    public CanvasBlockEntity(BlockPos pos, BlockState state) {
        super(ImmersiveCanvas.CANVAS_BE.get(), pos, state);
    }

    public CanvasData getData() { return data; }
    public int getRevision() { return revision; }

    /** Appelé côté serveur. */
    public void setData(CanvasData newData) {
        this.data = newData;
        this.revision++;
        setChanged();
        if (level != null) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
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

    // --- le canevas garde son dessin quand on le casse / le replace (via un data component sur l'item)
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
}
