package com.github.minecraft_ta.totalDebugCompanion.resource;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public final class ResourceLoader {

    public static final int MAXIMUM_TEXT_BYTES = 16 * 1024 * 1024;
    public static final int MAXIMUM_PNG_BYTES = 64 * 1024 * 1024;
    public static final long MAXIMUM_IMAGE_PIXELS = 32L * 1024 * 1024;

    private static final byte[] PNG_SIGNATURE = {
            (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
    };

    private ResourceLoader() {
    }

    public static LoadedResource load(ContentSource source, ResourceFileType fileType) throws IOException {
        if (fileType.kind() == ResourceFileType.Kind.CLASS || fileType.kind() == ResourceFileType.Kind.BINARY) {
            throw new UnsupportedResourceException(fileType.description() + " preview is not supported");
        }

        int limit = fileType.kind() == ResourceFileType.Kind.PNG
                ? MAXIMUM_PNG_BYTES
                : MAXIMUM_TEXT_BYTES;
        byte[] bytes = source.read(limit);
        if (fileType.kind() == ResourceFileType.Kind.PNG || isPng(bytes)) {
            return loadPng(bytes, source.displayName());
        }
        return loadText(bytes, fileType, source.displayName());
    }

    static LoadedResource.Text loadText(byte[] bytes, ResourceFileType fileType, String displayName)
            throws UnsupportedResourceException {
        DecodedText decoded = decodeBom(bytes);
        if (decoded == null) {
            if (containsNul(bytes) || !looksTextual(bytes)) {
                throw new UnsupportedResourceException(displayName + " does not look like a text file");
            }
            try {
                decoded = new DecodedText(decodeUtf8(bytes), "UTF-8");
            } catch (CharacterCodingException exception) {
                if (fileType.kind() == ResourceFileType.Kind.UNKNOWN) {
                    throw new UnsupportedResourceException(displayName + " is not valid UTF-8 text");
                }
                decoded = new DecodedText(new String(bytes, StandardCharsets.ISO_8859_1), "ISO-8859-1");
            }
        }

        String syntaxStyle = fileType.syntaxStyle() == null
                ? org.fife.ui.rsyntaxtextarea.RSyntaxTextArea.SYNTAX_STYLE_NONE
                : fileType.syntaxStyle();
        return new LoadedResource.Text(decoded.value(), syntaxStyle, decoded.charsetName(), bytes.length);
    }

    private static LoadedResource.Image loadPng(byte[] bytes, String displayName) throws IOException {
        if (!isPng(bytes)) {
            throw new UnsupportedResourceException(displayName + " is not a valid PNG file");
        }

        try (var input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            if (input == null) {
                throw new IOException("Unable to create an image input stream");
            }
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                throw new UnsupportedResourceException(displayName + " is not a readable PNG file");
            }

            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                long pixels = Math.multiplyExact((long) width, height);
                if (width <= 0 || height <= 0 || pixels > MAXIMUM_IMAGE_PIXELS) {
                    throw new UnsupportedResourceException(
                            displayName + " is " + width + " x " + height + ", above the image viewer limit"
                    );
                }
                BufferedImage image = reader.read(0);
                if (image == null) {
                    throw new UnsupportedResourceException(displayName + " is not a readable PNG file");
                }
                return new LoadedResource.Image(image, bytes.length);
            } catch (ArithmeticException exception) {
                throw new UnsupportedResourceException(displayName + " has invalid image dimensions");
            } finally {
                reader.dispose();
            }
        }
    }

    static boolean isPng(byte[] bytes) {
        return bytes.length >= PNG_SIGNATURE.length
                && Arrays.equals(PNG_SIGNATURE, Arrays.copyOf(bytes, PNG_SIGNATURE.length));
    }

    private static DecodedText decodeBom(byte[] bytes) throws UnsupportedResourceException {
        try {
            if (startsWith(bytes, (byte) 0xEF, (byte) 0xBB, (byte) 0xBF)) {
                return new DecodedText(decodeUtf8(Arrays.copyOfRange(bytes, 3, bytes.length)), "UTF-8");
            }
            if (startsWith(bytes, (byte) 0xFE, (byte) 0xFF)) {
                return new DecodedText(new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16BE), "UTF-16BE");
            }
            if (startsWith(bytes, (byte) 0xFF, (byte) 0xFE)) {
                return new DecodedText(new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16LE), "UTF-16LE");
            }
            return null;
        } catch (CharacterCodingException exception) {
            throw new UnsupportedResourceException("The text has an invalid byte sequence");
        }
    }

    private static String decodeUtf8(byte[] bytes) throws CharacterCodingException {
        CharBuffer decoded = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes));
        return decoded.toString();
    }

    private static boolean containsNul(byte[] bytes) {
        for (byte value : bytes) {
            if (value == 0) {
                return true;
            }
        }
        return false;
    }

    private static boolean looksTextual(byte[] bytes) {
        if (bytes.length == 0) {
            return true;
        }
        int controls = 0;
        int sampleLength = Math.min(bytes.length, 8192);
        for (int i = 0; i < sampleLength; i++) {
            int value = bytes[i] & 0xFF;
            if (value < 0x20 && value != '\n' && value != '\r' && value != '\t' && value != '\f') {
                controls++;
            }
        }
        return controls * 100 <= sampleLength;
    }

    private static boolean startsWith(byte[] bytes, byte... prefix) {
        if (bytes.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (bytes[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    private record DecodedText(String value, String charsetName) {
    }
}
