package com.autoredstonemusic.data;

import com.autoredstonemusic.AutoRedstoneMusicMod;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 世界级歌曲库：songHash → {名称, 编排字节}。控制器 BlockEntity 只存 hash，
 * 世界重载后仍能重建编配并执行启停/清拆。
 */
public class SongLibrary extends SavedData {

    /**
     * 歌曲条目。base64 数据【分块】存储：NBT 单个字符串硬上限 65535 字节
     * （NbtIo 用 writeUTF），整首歌单串存会在世界保存时抛 UTFDataFormatException
     * 导致存档写入失败——这是实测踩坑，务必保持分块。
     */
    public record Entry(String name, List<String> dataChunks) {
        /** 单块字符数（base64 为 ASCII，字符数=字节数，40k 远低于 65k 上限）。 */
        private static final int CHUNK_CHARS = 40_000;

        /** 兼容单字符串旧格式（Either 左）/ 分块新格式（Either 右）。 */
        private static final Codec<List<String>> CHUNKED = Codec
                .either(Codec.STRING, Codec.STRING.listOf())
                .xmap(either -> either.map(List::of, list -> list),
                        list -> list.size() == 1
                                ? com.mojang.datafixers.util.Either.left(list.get(0))
                                : com.mojang.datafixers.util.Either.right(list));

        public static final Codec<Entry> CODEC = RecordCodecBuilder.create(inst -> inst.group(
                Codec.STRING.fieldOf("name").forGetter(Entry::name),
                CHUNKED.fieldOf("data").forGetter(Entry::dataChunks)
        ).apply(inst, Entry::new));

        public static Entry of(String name, byte[] data) {
            String b64 = Base64.getEncoder().encodeToString(data);
            List<String> chunks = new ArrayList<>();
            for (int i = 0; i < b64.length(); i += CHUNK_CHARS) {
                chunks.add(b64.substring(i, Math.min(b64.length(), i + CHUNK_CHARS)));
            }
            return new Entry(name, chunks);
        }

        public byte[] decode() {
            return Base64.getDecoder().decode(String.join("", dataChunks));
        }
    }

    public static final Codec<SongLibrary> CODEC = RecordCodecBuilder.create(inst -> inst.group(
            Codec.unboundedMap(Codec.STRING, Entry.CODEC).optionalFieldOf("songs", Map.of()).forGetter(d -> d.songs)
    ).apply(inst, SongLibrary::new));

    public static final SavedDataType<SongLibrary> TYPE = new SavedDataType<>(
            Identifier.fromNamespaceAndPath(AutoRedstoneMusicMod.MODID, "songs"), SongLibrary::new, CODEC);

    private final Map<String, Entry> songs = new HashMap<>();

    public SongLibrary() {
    }

    private SongLibrary(Map<String, Entry> songs) {
        this.songs.putAll(songs);
    }

    public static SongLibrary get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(TYPE);
    }

    public void put(String hash, String name, byte[] data) {
        songs.put(hash, Entry.of(name, data));
        setDirty();
    }

    public byte[] get(String hash) {
        Entry entry = songs.get(hash);
        return entry != null ? entry.decode() : null;
    }
}
