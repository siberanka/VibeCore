package com.siberanka.vibecore.pack;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.siberanka.vibecore.core.validation.Result;
import com.siberanka.vibecore.pack.registry.StableIdRegistry;
import com.siberanka.vibecore.project.Identifier;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Builds the Java Edition resource pack. The output is deterministic: the
 * same source and registry always produce byte-identical ZIP files, with
 * entries in a fixed order and a fixed timestamp, so the pack hash only
 * changes when the content does.
 *
 * <p>Everything lives in the {@code vibecore} namespace. Glyphs form the
 * separate font {@code vibecore:glyphs}, so no vanilla or third-party font is
 * replaced.
 */
public final class PackCompiler {

    /** Resource pack format of Java Edition 1.21.4, the API baseline. */
    public static final int PACK_FORMAT = 46;
    public static final String NAMESPACE = Identifier.DEFAULT_NAMESPACE;
    public static final String FONT = NAMESPACE + ":glyphs";

    private static final LocalDateTime FIXED_TIME = LocalDateTime.of(1980, 1, 1, 0, 0);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    /** A built pack and the glyph code points it assigned. */
    public record Output(byte[] zip, String sha1, Map<String, Integer> glyphs) {
        public Output {
            zip = zip.clone();
            glyphs = Map.copyOf(glyphs);
        }

        @Override
        public byte[] zip() {
            return zip.clone();
        }
    }

    private PackCompiler() {
    }

    /** Builds the pack; glyph code points are allocated in {@code registry}, which keeps them stable. */
    public static Result<Output> compile(PackSource source, StableIdRegistry registry) {
        Map<String, Integer> codes = new TreeMap<>();
        for (PackSource.Glyph glyph : source.glyphs().values()) {
            Result<Integer> code = registry.allocate(new Identifier(NAMESPACE, "glyph/" + glyph.id()));
            if (!code.isAccepted()) {
                return Result.rejected(code.problems());
            }
            codes.put(glyph.id(), code.orThrow());
        }

        Map<String, byte[]> files = new TreeMap<>();
        JsonObject pack = new JsonObject();
        JsonObject meta = new JsonObject();
        meta.addProperty("pack_format", PACK_FORMAT);
        meta.addProperty("description", "VibeCore resources");
        pack.add("pack", meta);
        files.put("pack.mcmeta", json(pack));

        if (!source.glyphs().isEmpty()) {
            JsonArray providers = new JsonArray();
            for (PackSource.Glyph glyph : source.glyphs().values()) {
                JsonObject provider = new JsonObject();
                provider.addProperty("type", "bitmap");
                provider.addProperty("file", NAMESPACE + ":font/" + glyph.id() + ".png");
                provider.addProperty("ascent", glyph.ascent());
                provider.addProperty("height", glyph.height());
                JsonArray chars = new JsonArray();
                chars.add(new String(Character.toChars(codes.get(glyph.id()))));
                provider.add("chars", chars);
                providers.add(provider);
                files.put("assets/" + NAMESPACE + "/textures/font/" + glyph.id() + ".png", glyph.image().bytes());
            }
            JsonObject font = new JsonObject();
            font.add("providers", providers);
            files.put("assets/" + NAMESPACE + "/font/glyphs.json", json(font));
        }

        for (PackSource.Item item : source.items().values()) {
            JsonObject definition = new JsonObject();
            JsonObject model = new JsonObject();
            model.addProperty("type", "minecraft:model");
            model.addProperty("model", NAMESPACE + ":item/" + item.id());
            definition.add("model", model);
            files.put("assets/" + NAMESPACE + "/items/" + item.id() + ".json", json(definition));

            JsonObject itemModel = new JsonObject();
            itemModel.addProperty("parent", "minecraft:item/generated");
            JsonObject textures = new JsonObject();
            textures.addProperty("layer0", NAMESPACE + ":item/" + item.id());
            itemModel.add("textures", textures);
            files.put("assets/" + NAMESPACE + "/models/item/" + item.id() + ".json", json(itemModel));
            files.put("assets/" + NAMESPACE + "/textures/item/" + item.id() + ".png", item.texture().bytes());
        }

        files.put("vibecore-mapping.txt", mapping(source, codes));
        byte[] zip = zip(files);
        return Result.accepted(new Output(zip, sha1(zip), codes));
    }

    /** Human-readable list of every glyph and item in the pack. */
    private static byte[] mapping(PackSource source, Map<String, Integer> codes) {
        StringBuilder text = new StringBuilder("VibeCore pack mapping\n\nGlyphs (font ").append(FONT).append(")\n");
        codes.forEach((id, code) -> text.append(String.format("  %-32s U+%04X  <glyph:%s>%n", id, code, id)));
        text.append("\nItem models\n");
        source.items().keySet().forEach(id ->
                text.append(String.format("  %-32s model: %s:%s%n", id, NAMESPACE, id)));
        return text.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] json(JsonObject object) {
        return (GSON.toJson(object) + "\n").getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] zip(Map<String, byte[]> files) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(buffer, StandardCharsets.UTF_8)) {
            zip.setLevel(9);
            for (Map.Entry<String, byte[]> file : files.entrySet()) {
                ZipEntry entry = new ZipEntry(file.getKey());
                entry.setTimeLocal(FIXED_TIME);
                zip.putNextEntry(entry);
                zip.write(file.getValue());
                zip.closeEntry();
            }
        } catch (IOException impossible) {
            throw new IllegalStateException(impossible);
        }
        return buffer.toByteArray();
    }

    static String sha1(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
