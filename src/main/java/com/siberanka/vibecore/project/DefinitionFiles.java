package com.siberanka.vibecore.project;

import com.siberanka.vibecore.core.config.CoreStateLoader;
import com.siberanka.vibecore.core.validation.Problem;
import com.siberanka.vibecore.core.validation.Result;
import java.io.IOException;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Reads a folder of YAML definitions such as {@code menus/} or {@code huds/}.
 * The file {@code shop/main.yml} becomes {@code vibecore:shop/main}. Symbolic
 * links are not followed, nesting and file count are bounded, and every file
 * problem is reported with the folder-relative file name.
 */
public final class DefinitionFiles {

    public static final int MAX_FILES = 256;
    private static final int MAX_DEPTH = 4;

    private DefinitionFiles() {
    }

    /** Parsed documents by identifier, in identifier order. A missing folder has none. */
    public static Map<Identifier, Map<String, Object>> read(Path root, String folder, List<Problem> problems) {
        Map<Identifier, Map<String, Object>> documents = new TreeMap<>();
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            return documents;
        }
        List<Path> files;
        try (Stream<Path> walk = Files.walk(root, MAX_DEPTH, new FileVisitOption[0])) {
            files = walk.filter(path -> path.getFileName().toString().endsWith(".yml"))
                    .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .sorted().limit(MAX_FILES + 1L).toList();
        } catch (IOException | RuntimeException failure) {
            problems.add(new Problem(folder, "unreadable", "folder could not be listed"));
            return documents;
        }
        if (files.size() > MAX_FILES) {
            problems.add(new Problem(folder, "size-limit", "more than " + MAX_FILES + " files"));
            return documents;
        }
        for (Path file : files) {
            String relative = root.relativize(file).toString().replace('\\', '/');
            String label = folder + "/" + relative;
            Result<Identifier> id = Identifier.parse(label, relative.substring(0, relative.length() - ".yml".length()));
            if (!id.isAccepted()) {
                problems.add(new Problem(label, "file-name", "file names must be lower-case [a-z0-9_.-] segments"));
                continue;
            }
            Map<String, Object> document = CoreStateLoader.readYaml(file, label, problems);
            if (document != null) {
                documents.put(id.orThrow(), document);
            }
        }
        return documents;
    }

    /** Prefixes problems of one document with its file label. */
    public static List<Problem> label(String folder, Identifier id, List<Problem> problems) {
        String label = folder + "/" + id.path() + ".yml";
        return problems.stream()
                .map(problem -> new Problem(label + ": " + problem.path(), problem.code(), problem.detail()))
                .toList();
    }
}
