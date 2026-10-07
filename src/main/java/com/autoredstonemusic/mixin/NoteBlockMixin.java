package com.autoredstonemusic.mixin;

import com.autoredstonemusic.data.PitchChannel;
import com.autoredstonemusic.registry.ModSounds;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.NoteBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.GameEvent;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;

import java.util.List;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 音符盒发声拦截：铺设过的音符盒（音高通道有登记且 NOTE 校验通过）改播 Salamander 钢琴采样
 * （真实 MIDI 音高 + 力度音量），并取消原版发声；未登记的音符盒 100% 原版行为。
 *
 * <p>双端语义：服务端在此播放（playSeededSound 广播给所有人）；客户端仅取消原版本地发声
 * （自定义声经服务端广播送达，避免双声）。
 */
@Mixin(NoteBlock.class)
public abstract class NoteBlockMixin {

    /**
     * 登记过的音符盒豁免"上方必须为空气"的原版限制：
     * 原版 {@code playNote} 在 pos.above() 非空气时直接不触发 blockEvent，
     * 而我们的铺设会在音符盒上方放置红石灯（装饰选项）——若不豁免则全曲静音。
     * 只对音高通道登记过的音符盒生效，其余 100% 原版。
     */
    @Inject(method = "playNote", at = @At("HEAD"), cancellable = true)
    private void arm$forceTriggerForRegistered(Entity source, BlockState state, Level level, BlockPos pos, CallbackInfo ci) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return; // 客户端保持原版（上方有声源时客户端本就不播，正合取消原版声之意）
        }
        if (PitchChannel.lookup(serverLevel, pos) == null) {
            return; // 未登记：原版行为
        }
        level.blockEvent(pos, (NoteBlock) (Object) this, 0, 0);
        level.gameEvent(source, GameEvent.NOTE_BLOCK_PLAY, pos);
        ci.cancel();
    }

    @Inject(method = "triggerEvent(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;II)Z",
            at = @At("HEAD"), cancellable = true)
    private void arm$playCustomSample(BlockState state, Level level, BlockPos pos, int b0, int b1,
                                      CallbackInfoReturnable<Boolean> cir) {
        if (level instanceof ServerLevel serverLevel) {
            Long packed = PitchChannel.lookup(serverLevel, pos);
            if (packed == null) {
                return; // 未登记：原版逻辑
            }
            if (state.getValue(NoteBlock.NOTE) != PitchChannel.foldedNote(packed)) {
                // NOTE 属性与登记不符（手动调音/活塞移动）：条目失效，回落原版
                PitchChannel.removeAll(serverLevel, List.of(pos.asLong()));
                return;
            }
            int family = PitchChannel.familyId(packed);
            int key = PitchChannel.midiKey(packed);
            float volume = PitchChannel.volumeFor(PitchChannel.velocity(packed), PitchChannel.gainIdx(packed));
            float pitch = ModSounds.pitchFor(family, key);
            serverLevel.playSeededSound(null, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
                    ModSounds.holder(family, key), SoundSource.RECORDS, volume, pitch, serverLevel.getRandom().nextLong());
            cir.setReturnValue(true); // 取消原版发声；返回 true 让 BlockEventPacket 正常广播
        } else {
            // 客户端：有登记则取消本地原版发声（自定义声由服务端广播）
            Long packed = com.autoredstonemusic.data.ClientPitchStore.get(pos.asLong());
            if (packed != null && state.getValue(NoteBlock.NOTE) == PitchChannel.foldedNote(packed)) {
                cir.setReturnValue(true);
            }
        }
    }
}
