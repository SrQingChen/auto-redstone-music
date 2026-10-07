package com.autoredstonemusic;

import com.autoredstonemusic.block.MusicControllerBlock;
import com.autoredstonemusic.block.PrecisionRepeaterBlock;
import com.autoredstonemusic.blockentity.MusicControllerBlockEntity;
import com.autoredstonemusic.item.GeneratorItem;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.properties.NoteBlockInstrument;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 全部注册项。26.1.2 要点：物品必须走 DeferredRegister.Items#registerItem（框架注入注册 id，
 * 直接 new Item.Properties() 注册会 "Item id not set"）；BlockEntityType 直接 new 构造。
 */
public final class ModRegistries {
    private ModRegistries() {}

    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(AutoRedstoneMusicMod.MODID);
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(AutoRedstoneMusicMod.MODID);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(BuiltInRegistries.BLOCK_ENTITY_TYPE, AutoRedstoneMusicMod.MODID);
    public static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, AutoRedstoneMusicMod.MODID);

    /** 轨道基座：默认 HARP 音色（作为竖琴音符的下方方块时推导一致），非红石导体不干扰链路。 */
    public static final DeferredBlock<Block> TRACK_RAIL = BLOCKS.registerSimpleBlock("track_rail",
            () -> BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_LIGHT_GRAY)
                    .instrument(NoteBlockInstrument.HARP)
                    .strength(1.5F));

    /** 音乐控制器：起点圆心，空手右键启动/停止，潜行右键拆除区域。 */
    public static final DeferredBlock<MusicControllerBlock> MUSIC_CONTROLLER =
            BLOCKS.registerBlock("music_controller", MusicControllerBlock::new,
                    p -> p.mapColor(MapColor.COLOR_PURPLE).strength(3.0F, 6.0F).requiresCorrectToolForDrops());

    /** 精密中继器：外观同原版，延迟以游戏刻为单位（1-200），用于逐音精确节奏。 */
    public static final DeferredBlock<PrecisionRepeaterBlock> PRECISION_REPEATER =
            BLOCKS.registerBlock("precision_repeater", PrecisionRepeaterBlock::new,
                    p -> p.mapColor(MapColor.COLOR_GRAY).strength(1.5F));

    /** 生成器：Shift+右键打开 MIDI 浏览器，应用后右键方块放置区域。 */
    public static final DeferredItem<GeneratorItem> GENERATOR = ITEMS.registerItem("generator",
            props -> new GeneratorItem(props.stacksTo(1)));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<MusicControllerBlockEntity>> MUSIC_CONTROLLER_BE =
            BLOCK_ENTITIES.register("music_controller",
                    () -> new BlockEntityType<>(MusicControllerBlockEntity::new, MUSIC_CONTROLLER.get()));

    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> MOD_TAB = CREATIVE_MODE_TABS.register("main_tab",
            () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.auto_redstone_music"))
                    .withTabsBefore(CreativeModeTabs.BUILDING_BLOCKS)
                    .icon(() -> GENERATOR.get().getDefaultInstance())
                    .displayItems((params, output) -> output.accept(GENERATOR.get()))
                    .build());

    public static void register(IEventBus modEventBus) {
        BLOCKS.register(modEventBus);
        ITEMS.register(modEventBus);
        BLOCK_ENTITIES.register(modEventBus);
        CREATIVE_MODE_TABS.register(modEventBus);
    }
}
