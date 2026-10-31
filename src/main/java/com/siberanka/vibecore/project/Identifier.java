package com.siberanka.vibecore.project;

import com.siberanka.vibecore.core.validation.Result;
import java.util.regex.Pattern;

/**
 * Namespaced, player-name independent identifier such as
 * {@code vibecore:menus/shop}. The grammar is restricted to lower-case ASCII so
 * identifiers are safe as file names, pack paths and command arguments on
 * every operating system.
 */
public record Identifier(String namespace, String path) implements Comparable<Identifier> {

    public static final String DEFAULT_NAMESPACE = "vibecore";
    private static final Pattern NAMESPACE = Pattern.compile("[a-z0-9_.-]{1,32}");
    private static final Pattern PATH = Pattern.compile("[a-z0-9_.-]+(/[a-z0-9_.-]+)*");
    private static final int MAX_PATH_LENGTH = 128;

    public Identifier {
        if (!isValidNamespace(namespace) || !isValidPath(path)) {
            throw new IllegalArgumentException("Invalid identifier: " + namespace + ":" + path);
        }
    }

    public static boolean isValidNamespace(String namespace) {
        return namespace != null && NAMESPACE.matcher(namespace).matches();
    }

    public static boolean isValidPath(String path) {
        if (path == null || path.length() > MAX_PATH_LENGTH || !PATH.matcher(path).matches()) {
            return false;
        }
        for (String segment : path.split("/")) {
            if (segment.equals(".") || segment.equals("..")) {
                return false;
            }
        }
        return true;
    }

    /** Parses {@code namespace:path}, or a bare path in the default namespace. */
    public static Result<Identifier> parse(String field, String text) {
        if (text == null || text.isEmpty()) {
            return Result.rejected(field, "missing", "an identifier is required");
        }
        int colon = text.indexOf(':');
        String namespace = colon < 0 ? DEFAULT_NAMESPACE : text.substring(0, colon);
        String path = colon < 0 ? text : text.substring(colon + 1);
        if (!isValidNamespace(namespace)) {
            return Result.rejected(field, "namespace", "namespace must match [a-z0-9_.-]{1,32}");
        }
        if (!isValidPath(path)) {
            return Result.rejected(field, "path", "path must use [a-z0-9_.-] segments separated by"
                    + " slashes, at most " + MAX_PATH_LENGTH + " characters");
        }
        return Result.accepted(new Identifier(namespace, path));
    }

    @Override
    public int compareTo(Identifier other) {
        int byNamespace = namespace.compareTo(other.namespace);
        return byNamespace != 0 ? byNamespace : path.compareTo(other.path);
    }

    @Override
    public String toString() {
        return namespace + ":" + path;
    }
}
