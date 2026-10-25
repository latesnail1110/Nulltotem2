package net.nullmod.totem;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.file.*;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class NullData {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type TYPE = new TypeToken<Map<String, PlayerData>>() {}.getType();
    private static Map<String, PlayerData> map = new HashMap<>();
    private static Path file;
    private static boolean dirty;

    private NullData() {}

    public static synchronized void load(MinecraftServer server) {
        file = server.getWorldPath(LevelResource.ROOT).resolve("nulltotem_state.json");
        map = new HashMap<>();
        dirty = false;
        if (!Files.exists(file)) return;
        try (Reader r = Files.newBufferedReader(file)) {
            Map<String, PlayerData> m = GSON.fromJson(r, TYPE);
            if (m != null) map = m;
        } catch (Exception e) {
            NullTotemMod.LOGGER.error("Could not read state file, moving it to .corrupt and starting empty", e);
            try { Files.move(file, file.resolveSibling("nulltotem_state.json.corrupt"), StandardCopyOption.REPLACE_EXISTING); }
            catch (Exception ignored) {}
            map = new HashMap<>();
        }
    }

    public static synchronized PlayerData get(UUID id) {
        return map.computeIfAbsent(id.toString(), k -> new PlayerData());
    }

    public static synchronized void markDirty() { dirty = true; }

    public static synchronized void save() {
        if (!dirty || file == null) return;
        Path tmp = file.resolveSibling("nulltotem_state.json.tmp");
        try (Writer w = Files.newBufferedWriter(tmp)) {
            GSON.toJson(map, TYPE, w);
        } catch (Exception e) {
            NullTotemMod.LOGGER.error("Could not write state file", e);
            return;
        }
        try {
            try { Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException e) { Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING); }
            dirty = false;
        } catch (Exception e) {
            NullTotemMod.LOGGER.error("Could not replace state file", e);
        }
    }

    public static synchronized void saveNow() { dirty = true; save(); }
}
