package mmt.standin.com.mojang.blaze3d.audio;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import javax.sound.sampled.AudioFormat;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.client.sounds.JOrbisAudioStream;

/**
 * 1.20.4's OggAudioStream on 1.20.5.
 *
 * Read from both releases: 1.20.4's OggAudioStream(input) decoded an Ogg Vorbis stream through LWJGL's stb_vorbis into
 * 16-bit PCM: getFormat() gave its AudioFormat, read(size) the next chunk of at least size bytes, readAll() the rest,
 * close() closed the input. 1.20.5 decodes Ogg Vorbis with JOrbisAudioStream, which the game's SoundBufferLibrary and
 * streams use where 1.20.4 made an OggAudioStream, giving the same 16-bit PCM through the same AudioStream methods
 * (read and readAll from FloatSampleSource). The class is gone, so this is the class: an AudioStream over 1.20.5's
 * decoder.
 *
 * Agrees with NeoForge's 1.20.4 -> 1.20.5 primer (https://github.com/neoforged/.github/blob/main/primers/1.20.5/index.md, CC BY 4.0): "com.mojang.blaze3d.audio.OggAudioStream replaced by net.minecraft.client.sounds.FiniteAudioStream, FloatSampleSource, JOrbisAudioStream".
 */
public class OggAudioStream implements AudioStream {

    private final JOrbisAudioStream stream;

    public OggAudioStream(InputStream input) throws IOException {
        this.stream = new JOrbisAudioStream(input);
    }

    public AudioFormat getFormat() {
        return stream.getFormat();
    }

    public ByteBuffer read(int size) {
        try {
            return stream.read(size);
        } catch (Exception failed) {
            throw OggAudioStream.<RuntimeException>rethrow(failed);
        }
    }

    public ByteBuffer readAll() {
        try {
            return stream.readAll();
        } catch (Exception failed) {
            throw OggAudioStream.<RuntimeException>rethrow(failed);
        }
    }

    public void close() {
        try {
            stream.close();
        } catch (Exception failed) {
            throw OggAudioStream.<RuntimeException>rethrow(failed);
        }
    }

    /** The decoder's IOException as itself: the old methods threw it (declared in the game, which the port's stubs leave out). */
    private static <E extends Throwable> E rethrow(Throwable failed) throws E {
        throw (E) failed;
    }
}
