package org.kelvinizer.charter;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.utils.FloatArray;
import javazoom.jl.decoder.Bitstream;
import javazoom.jl.decoder.Header;
import javazoom.jl.decoder.MP3Decoder;
import javazoom.jl.decoder.OutputBuffer;
import org.kelvinizer.audio.Mp3Info;

import java.io.BufferedInputStream;
import java.io.InputStream;

/**
 * 把 MP3 解码成波形的峰值（电脑和平板都用 libGDX 自带的 JLayer），时间和游戏里听到的对齐。
 * Decodes an MP3 into the waveform's peaks with JLayer (shipped with libGDX, pure Java, so the desktop and the
 * Android tablet decode alike). JLayer's output starts later than the original audio (Info frame, encoder and decoder
 * delay); that much is skipped, so peak 0 is music time 0 on both.
 */
final class Mp3Peaks {
    private Mp3Peaks() {
    }

    /** @return peak amplitudes in 0..1, {@code perSecond} per second, or null when nothing could be read. */
    static float[] decode(FileHandle file, int perSecond) {
        Mp3Info info = Mp3Info.read(file);
        float delayMs = info != null ? info.startDelayMs(false) : 0f;
        FloatArray out = new FloatArray(perSecond * 240);
        try (InputStream in = new BufferedInputStream(file.read(), 1 << 16)) {
            Bitstream bs = new Bitstream(in);
            MP3Decoder dec = new MP3Decoder();
            OutputBuffer ob = null;
            long frames = 0, skip = 0;
            int perBucket = 1, ch = 2;
            float cur = 0f;
            Header h;
            while ((h = bs.readFrame()) != null) {
                if (ob == null) {
                    // Same set-up as libGDX's own MP3 music: 16-bit little-endian interleaved output.
                    ch = h.mode() == Header.SINGLE_CHANNEL ? 1 : 2;
                    ob = new OutputBuffer(ch, false);
                    dec.setOutputBuffer(ob);
                    int rate = h.getSampleRate();
                    perBucket = Math.max(1, rate / perSecond);
                    skip = Math.round(delayMs * rate / 1000.0);
                }
                try {
                    dec.decodeFrame(h, bs);
                } catch (Exception ignored) {
                    // JLayer sometimes throws on a damaged frame; skip it like libGDX does
                }
                bs.closeFrame();
                int len = ob.reset();
                byte[] b = ob.getBuffer();
                for (int i = 0; i + 2 * ch - 1 < len; i += 2 * ch) {
                    if (frames++ < skip) continue;
                    int v = Math.abs((short) ((b[i] & 0xff) | (b[i + 1] << 8)));
                    if (ch > 1) v = Math.max(v, Math.abs((short) ((b[i + 2] & 0xff) | (b[i + 3] << 8))));
                    cur = Math.max(cur, v / 32768f);
                    if ((frames - skip) % perBucket == 0) {
                        out.add(cur);
                        cur = 0f;
                    }
                }
            }
            bs.close();
        } catch (Exception e) {
            Gdx.app.error("Charter", "mp3 waveform: " + e.getMessage());
            return out.size > 0 ? out.toArray() : null;
        }
        return out.toArray();
    }
}
