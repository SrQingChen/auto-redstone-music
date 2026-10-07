package com.autoredstonemusic.registry;

import com.autoredstonemusic.AutoRedstoneMusicMod;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.ArrayList;
import java.util.List;

/**
 * 全乐器采样库注册（Salamander 钢琴 + VSCO-2-CE / VCSL CC0 采样）。
 * 声音注册名 = 族名_<三根音 MIDI>，如 piano_060、violin_055、drum_035；
 * 播放音高 = 2^((真实MIDI键 − 最近根音)/12)，无界，全音域精确。
 */
public final class ModSounds {
    private ModSounds() {}

    public static final DeferredRegister<SoundEvent> SOUNDS =
            DeferredRegister.create(Registries.SOUND_EVENT, AutoRedstoneMusicMod.MODID);

    /** FAMILIES.get(i) 的声音句柄，与 {@link SampleTable.Family#roots()} 一一对应。 */
    public static final List<List<DeferredHolder<SoundEvent, SoundEvent>>> SOUNDS_BY_FAMILY = new ArrayList<>();

    static {
        for (SampleTable.Family family : SampleTable.FAMILIES) {
            List<DeferredHolder<SoundEvent, SoundEvent>> list = new ArrayList<>();
            for (int root : family.roots()) {
                final String name = soundName(family.name(), root);
                list.add(SOUNDS.register(name,
                        () -> SoundEvent.createVariableRangeEvent(
                                Identifier.fromNamespaceAndPath(AutoRedstoneMusicMod.MODID, name))));
            }
            SOUNDS_BY_FAMILY.add(list);
        }
    }

    public static String soundName(String family, int rootKey) {
        return family + "_" + String.format("%03d", rootKey);
    }

    /** (族序号, 真实MIDI键) → 最近根音在该族中的声音句柄。 */
    public static Holder<SoundEvent> holder(int familyId, int midiKey) {
        SampleTable.Family family = SampleTable.byId(familyId);
        int idx = SampleTable.nearestIndex(family, midiKey);
        return SOUNDS_BY_FAMILY.get(familyId).get(idx);
    }

    /** 播放音高（相对最近根音，无界）。 */
    public static float pitchFor(int familyId, int midiKey) {
        return SampleTable.pitchFor(SampleTable.byId(familyId), midiKey);
    }

    public static void register(IEventBus modEventBus) {
        SOUNDS.register(modEventBus);
    }
}
