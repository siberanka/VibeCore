package com.siberanka.vibecore.pack;

import com.siberanka.vibecore.core.validation.Result;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Iterator;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

/**
 * A validated PNG image. The size is read from the header before any pixel
 * is decoded, so an oversized or malformed file is refused without
 * allocating its pixels. The original bytes are kept and copied into the pack
 * unchanged.
 */
public record AssetImage(byte[] bytes, int width, int height, String sha256) {

    public static final int MAX_BYTES = 1024 * 1024;
    public static final int MAX_DIMENSION = 1024;
    private static final byte[] PNG_SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};

    public AssetImage {
        bytes = bytes.clone();
    }

    @Override
    public byte[] bytes() {
        return bytes.clone();
    }

    public static Result<AssetImage> read(String path, byte[] bytes, int maxWidth, int maxHeight) {
        if (bytes.length > MAX_BYTES) {
            return Result.rejected(path, "size", "larger than " + MAX_BYTES + " bytes");
        }
        if (bytes.length < PNG_SIGNATURE.length) {
            return Result.rejected(path, "format", "not a PNG image");
        }
        for (int index = 0; index < PNG_SIGNATURE.length; index++) {
            if (bytes[index] != PNG_SIGNATURE[index]) {
                return Result.rejected(path, "format", "not a PNG image");
            }
        }
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                return Result.rejected(path, "format", "not a readable PNG image");
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width < 1 || height < 1 || width > Math.min(maxWidth, MAX_DIMENSION)
                        || height > Math.min(maxHeight, MAX_DIMENSION)) {
                    return Result.rejected(path, "dimensions", width + "x" + height + " is outside 1x1 to "
                            + Math.min(maxWidth, MAX_DIMENSION) + "x" + Math.min(maxHeight, MAX_DIMENSION));
                }
                reader.read(0);
                return Result.accepted(new AssetImage(bytes, width, height, sha256(bytes)));
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException malformed) {
            return Result.rejected(path, "format", "the PNG image is damaged");
        }
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
