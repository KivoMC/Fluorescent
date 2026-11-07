package dev.stillbloom.model;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;

import java.util.*;

public final class BlockCatalog {
    private static BlockSearchIndex current;
    private static int registrySize;
    private static String language;

    static void invalidate() {
        current = null;
    }

    public static BlockSearchIndex current() {
        String selected = Minecraft.getInstance().getLanguageManager().getSelected();
        if (current == null
                || registrySize != BuiltInRegistries.BLOCK.size()
                || !Objects.equals(language, selected)) {
            var names = new TreeMap<String, String>();
            for (var block : BuiltInRegistries.BLOCK)
                names.put(
                        BuiltInRegistries.BLOCK.getKey(block).toString(),
                        block.getName().getString());
            var modNames = new HashMap<String, String>();
            for (var mod : FabricLoader.getInstance().getAllMods())
                modNames.put(mod.getMetadata().getId(), mod.getMetadata().getName());
            current = new BlockSearchIndex(names, modNames);
            registrySize = BuiltInRegistries.BLOCK.size();
            language = selected;
        }
        return current;
    }
}
