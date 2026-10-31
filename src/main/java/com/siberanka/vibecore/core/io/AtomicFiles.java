package com.siberanka.vibecore.core.io;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/**
 * Crash-tolerant file replacement. Content is written to a sibling temporary
 * file, flushed to disk and moved over the target, so a reader observes either
 * the previous or the new complete file, never a partial one.
 */
public final class AtomicFiles {

    private AtomicFiles() {
    }

    public static void writeString(Path target, String content) throws IOException {
        write(target, content.getBytes(StandardCharsets.UTF_8));
    }

    public static void write(Path target, byte[] content) throws IOException {
        Path absolute = target.toAbsolutePath().normalize();
        Path directory = absolute.getParent();
        if (directory == null) {
            throw new IOException("Target has no parent directory: " + absolute);
        }
        if (Files.isSymbolicLink(absolute)) {
            throw new IOException("Refusing to replace a symbolic link: " + absolute.getFileName());
        }
        Files.createDirectories(directory);
        Path temporary = Files.createTempFile(directory, "." + absolute.getFileName(), ".tmp");
        try {
            try (FileChannel channel = FileChannel.open(temporary,
                    StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer buffer = ByteBuffer.wrap(content);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }
            try {
                Files.move(temporary, absolute,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, absolute, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    /**
     * Resolves a relative, slash-separated name below {@code root} and rejects
     * anything that could escape it: absolute or drive-qualified names,
     * {@code ..} segments, empty segments and existing symbolic links.
     */
    public static Path resolveContained(Path root, String relativeName) throws IOException {
        if (relativeName == null || relativeName.isEmpty() || relativeName.length() > 255) {
            throw new IOException("Invalid relative name");
        }
        if (relativeName.indexOf('\\') >= 0 || relativeName.indexOf(':') >= 0
                || relativeName.startsWith("/") || relativeName.indexOf('\0') >= 0) {
            throw new IOException("Name must be a plain relative path");
        }
        for (String segment : relativeName.split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                throw new IOException("Name contains an unsafe segment");
            }
        }
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path resolved = normalizedRoot.resolve(relativeName).normalize();
        if (!resolved.startsWith(normalizedRoot) || resolved.equals(normalizedRoot)) {
            throw new IOException("Name escapes its root");
        }
        Path cursor = resolved;
        while (cursor != null && !cursor.equals(normalizedRoot)) {
            if (Files.exists(cursor, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(cursor)) {
                throw new IOException("Path traverses a symbolic link");
            }
            cursor = cursor.getParent();
        }
        return resolved;
    }
}
