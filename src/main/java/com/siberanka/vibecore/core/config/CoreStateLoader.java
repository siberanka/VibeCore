package com.siberanka.vibecore.core.config;

import com.siberanka.vibecore.core.text.MessageCatalog;
import com.siberanka.vibecore.core.validation.Problem;
import com.siberanka.vibecore.core.validation.Result;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Reads the configuration and message files of the data folder into a
 * {@link CoreState} candidate. Performs file IO only; it never touches the
 * active state, so it may run on a worker thread.
 */
public final class CoreStateLoader {

    private static final long MAX_FILE_BYTES = 256 * 1024;

    private final Path dataFolder;
    private final Function<String, InputStream> bundledResources;

    public CoreStateLoader(Path dataFolder, Function<String, InputStream> bundledResources) {
        this.dataFolder = dataFolder;
        this.bundledResources = bundledResources;
    }

    public Result<CoreState> load() {
        List<Problem> problems = new ArrayList<>();
        Map<String, Object> config = readFile(dataFolder.resolve("config.yml"), "config.yml", problems);
        Result<CoreSettings> settings = config == null
                ? Result.rejected("config.yml", "unreadable", "configuration could not be read")
                : SettingsParser.parse(config);
        problems.addAll(settings.problems());

        Map<String, Map<String, String>> bundled = new LinkedHashMap<>();
        for (String locale : MessageCatalog.SUPPORTED_LOCALES) {
            bundled.put(locale, readBundledMessages("lang/" + locale + ".yml", problems));
        }
        Map<String, Map<String, String>> overrides = readOverrides(problems);
        Result<MessageCatalog> catalog = MessageCatalog.build(bundled, overrides);
        problems.addAll(catalog.problems());

        if (!problems.isEmpty()) {
            return Result.rejected(problems);
        }
        return Result.accepted(new CoreState(settings.orThrow(), catalog.orThrow()));
    }

    /** Built-in defaults, used only when the very first load is rejected. */
    public Result<CoreState> loadDefaults() {
        List<Problem> problems = new ArrayList<>();
        Map<String, Object> config = readBundled("config.yml", problems);
        Map<String, Map<String, String>> bundled = new LinkedHashMap<>();
        for (String locale : MessageCatalog.SUPPORTED_LOCALES) {
            bundled.put(locale, readBundledMessages("lang/" + locale + ".yml", problems));
        }
        if (!problems.isEmpty() || config == null) {
            return Result.rejected(problems.isEmpty()
                    ? List.of(new Problem("config.yml", "missing", "bundled defaults missing")) : problems);
        }
        Map<String, Object> safe = new LinkedHashMap<>(config);
        safe.put("modules", Map.of());
        Result<CoreSettings> settings = SettingsParser.parse(safe);
        Result<MessageCatalog> catalog = MessageCatalog.build(bundled, Map.of());
        if (!settings.isAccepted() || !catalog.isAccepted()) {
            List<Problem> all = new ArrayList<>(settings.problems());
            all.addAll(catalog.problems());
            return Result.rejected(all);
        }
        return Result.accepted(new CoreState(settings.orThrow(), catalog.orThrow()));
    }

    private Map<String, Map<String, String>> readOverrides(List<Problem> problems) {
        Map<String, Map<String, String>> overrides = new LinkedHashMap<>();
        Path directory = dataFolder.resolve("lang");
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            return overrides;
        }
        try (DirectoryStream<Path> files = Files.newDirectoryStream(directory, "*.yml")) {
            for (Path file : files) {
                String fileName = file.getFileName().toString();
                String locale = fileName.substring(0, fileName.length() - ".yml".length());
                if (!MessageCatalog.SUPPORTED_LOCALES.contains(locale)) {
                    problems.add(new Problem("lang/" + fileName, "unsupported", "unsupported locale file"));
                    continue;
                }
                Map<String, Object> values = readFile(file, "lang/" + fileName, problems);
                if (values != null) {
                    overrides.put(locale, flattenMessages(values, "lang/" + fileName, problems));
                }
            }
        } catch (IOException failure) {
            problems.add(new Problem("lang", "unreadable", "message overrides could not be listed"));
        }
        return overrides;
    }

    private Map<String, Object> readFile(Path file, String label, List<Problem> problems) {
        try {
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                problems.add(new Problem(label, "missing", "file is missing or not a regular file"));
                return null;
            }
            if (Files.size(file) > MAX_FILE_BYTES) {
                problems.add(new Problem(label, "size", "file exceeds " + MAX_FILE_BYTES + " bytes"));
                return null;
            }
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                return parse(reader, label, problems);
            }
        } catch (IOException failure) {
            problems.add(new Problem(label, "unreadable", "file could not be read: " + failure.getClass().getSimpleName()));
            return null;
        }
    }

    private Map<String, Object> readBundled(String name, List<Problem> problems) {
        try (InputStream stream = bundledResources.apply(name)) {
            if (stream == null) {
                problems.add(new Problem(name, "missing", "bundled resource missing"));
                return null;
            }
            return parse(new InputStreamReader(stream, StandardCharsets.UTF_8), name, problems);
        } catch (IOException failure) {
            problems.add(new Problem(name, "unreadable", "bundled resource could not be read"));
            return null;
        }
    }

    private Map<String, String> readBundledMessages(String name, List<Problem> problems) {
        Map<String, Object> values = readBundled(name, problems);
        return values == null ? Map.of() : flattenMessages(values, name, problems);
    }

    private static Map<String, Object> parse(Reader reader, String label, List<Problem> problems) throws IOException {
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            StringBuilder text = new StringBuilder();
            char[] buffer = new char[8192];
            int read;
            while ((read = reader.read(buffer)) != -1) {
                text.append(buffer, 0, read);
                if (text.length() > MAX_FILE_BYTES) {
                    problems.add(new Problem(label, "size", "file exceeds " + MAX_FILE_BYTES + " characters"));
                    return null;
                }
            }
            yaml.loadFromString(text.toString());
        } catch (InvalidConfigurationException invalid) {
            problems.add(new Problem(label, "syntax", "invalid YAML"));
            return null;
        }
        return toMap(yaml);
    }

    /** Converts a configuration section tree into plain nested maps. */
    public static Map<String, Object> toMap(ConfigurationSection section) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (String key : section.getKeys(false)) {
            Object value = section.get(key);
            map.put(key, value instanceof ConfigurationSection child ? toMap(child) : value);
        }
        return map;
    }

    private static Map<String, String> flattenMessages(Map<String, Object> values, String label,
                                                       List<Problem> problems) {
        Map<String, String> flat = new LinkedHashMap<>();
        flatten("", values, flat, label, problems);
        return flat;
    }

    private static void flatten(String prefix, Map<?, ?> values, Map<String, String> out, String label,
                                List<Problem> problems) {
        for (Map.Entry<?, ?> entry : values.entrySet()) {
            String key = prefix.isEmpty() ? String.valueOf(entry.getKey()) : prefix + "." + entry.getKey();
            if (entry.getValue() instanceof Map<?, ?> child) {
                flatten(key, child, out, label, problems);
            } else if (entry.getValue() instanceof String text) {
                out.put(key, text);
            } else {
                problems.add(new Problem(label + "." + key, "type", "message must be text"));
            }
        }
    }
}
