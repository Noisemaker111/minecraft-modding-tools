package mmt.standin.java.nio.file;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Java 11's Files.readString and writeString, from the JDK's documented contract: readString(path[, cs]) "reads all
 * content from a file into a string, decoding from bytes to characters using the UTF-8 charset" (or cs), throwing
 * MalformedInputException for malformed or unmappable bytes; writeString(path, csq[, cs], options) writes the characters
 * encoded in UTF-8 (or cs) as Files.write(path, bytes, options) does, throwing UnmappableCharacterException for a
 * character it cannot encode, and returns the path.
 */
public final class Files {

    private Files() {}

    public static String readString(Path path) throws IOException {
        return readString(path, StandardCharsets.UTF_8);
    }

    public static String readString(Path path, Charset cs) throws IOException {
        Objects.requireNonNull(cs);
        byte[] bytes = java.nio.file.Files.readAllBytes(path);
        return cs.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString();
    }

    public static Path writeString(Path path, CharSequence csq, OpenOption... options) throws IOException {
        return writeString(path, csq, StandardCharsets.UTF_8, options);
    }

    public static Path writeString(Path path, CharSequence csq, Charset cs, OpenOption... options) throws IOException {
        Objects.requireNonNull(path);
        Objects.requireNonNull(csq);
        Objects.requireNonNull(cs);
        ByteBuffer encoded = cs.newEncoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .encode(CharBuffer.wrap(csq));
        byte[] bytes = new byte[encoded.remaining()];
        encoded.get(bytes);
        java.nio.file.Files.write(path, bytes, options);
        return path;
    }
}
