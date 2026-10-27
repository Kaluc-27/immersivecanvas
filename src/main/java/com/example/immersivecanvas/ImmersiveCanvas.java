package com.example.immersivecanvas;

import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.function.Supplier;

@Mod(ImmersiveCanvas.MODID)
public class ImmersiveCanvas {
    public static final String MODID = "immersivecanvas";

    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(MODID);
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MODID);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, MODID);
    public static final DeferredRegister<DataComponentType<?>> COMPONENTS =
            DeferredRegister.create(Registries.DATA_COMPONENT_TYPE, MODID);

    public static final DeferredBlock<Block> CANVAS = BLOCKS.registerBlock("canvas", CanvasBlock::new,
            BlockBehaviour.Properties.of().strength(0.6F).sound(SoundType.WOOL).noOcclusion());

    public static final DeferredItem<BlockItem> CANVAS_ITEM = ITEMS.registerSimpleBlockItem("canvas", CANVAS);

    public static final Supplier<DataComponentType<CanvasData>> CANVAS_DATA = COMPONENTS.register("canvas_data",
            () -> DataComponentType.<CanvasData>builder()
                    .persistent(CanvasData.CODEC)
                    .networkSynchronized(CanvasData.STREAM_CODEC)
                    .build());

    public static final Supplier<BlockEntityType<CanvasBlockEntity>> CANVAS_BE = BLOCK_ENTITIES.register("canvas",
            () -> new BlockEntityType<>(CanvasBlockEntity::new, CANVAS.get()));

    public ImmersiveCanvas(IEventBus modBus) {
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        BLOCK_ENTITIES.register(modBus);
        COMPONENTS.register(modBus);
        modBus.addListener(this::registerPayloads);
        modBus.addListener(this::addToCreativeTab);
    }

    private void registerPayloads(RegisterPayloadHandlersEvent event) {
        event.registrar("1").playToServer(SaveCanvasPayload.TYPE, SaveCanvasPayload.STREAM_CODEC, SaveCanvasPayload::handle);
    }

    private void addToCreativeTab(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES) {
            event.accept(CANVAS_ITEM.get());
        }
    }
}
