package org.kelvinizer.charter;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;
import org.kelvinizer.audio.Mp3Info;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;

/**
 * 时间轴上的音频波形：后台线程读出每 10 毫秒的峰值（WAV 直接读，MP3 用 {@link Mp3Peaks} 解码）。
 * The audio waveform on the timeline: peak amplitude per 10 ms, read on a worker thread
 * (WAV here, MP3 through {@link Mp3Peaks}).
 */
public final class Waveform {
    public static final int PER_SECOND = 100;
    private volatile float[] peaks;
    private volatile boolean done;

    public Waveform(FileHandle file) {
        if (file == null || !file.exists()) {
            done = true;
            return;
        }
        Thread t = new Thread(() -> {
            try {
                float[] p = file.extension().equalsIgnoreCase("wav") ? wav(file)
                    : Mp3Info.isMp3(file) ? Mp3Peaks.decode(file, PER_SECOND) : null;
                if (p != null) normalise(p);
                peaks = p;
            } catch (Throwable e) {
                Gdx.app.error("Charter", "waveform: " + e);
            }
            done = true;
        }, "charter-wave");
        t.setDaemon(true);
        t.start();
    }

    public boolean ready() {
        return done;
    }

    /** @return the peak (0..1) between song times {@code fromMs} and {@code toMs}. */
    public float peak(double fromMs, double toMs) {
        float[] p = peaks;
        if (p == null) return 0f;
        int a = (int) Math.floor(fromMs * PER_SECOND / 1000.0), b = (int) Math.ceil(toMs * PER_SECOND / 1000.0);
        a = Math.max(0, a);
        b = Math.min(p.length, Math.max(b, a + 1));
        float m = 0f;
        for (int i = a; i < b; i++) m = Math.max(m, p[i]);
        return m;
    }

    private static void normalise(float[] p) {
        float m = 1e-6f;
        for (float v : p) m = Math.max(m, v);
        for (int i = 0; i < p.length; i++) p[i] = Math.min(1f, p[i] / m);
    }

    private static int le16(DataInputStream in) throws IOException {
        int a = in.readUnsignedByte(), b = in.readUnsignedByte();
        return a | (b << 8);
    }

    private static int le32(DataInputStream in) throws IOException {
        return le16(in) | (le16(in) << 16);
    }

    /** Reads 8/16/24/32-bit PCM or 32-bit float WAV. */
    static float[] wav(FileHandle f) throws IOException {
        try (InputStream raw = f.read();
            DataInputStream in = new DataInputStream(new BufferedInputStream(raw, 1 << 16))) {
            byte[] id = new byte[4];
            in.readFully(id);
            if (id[0] != 'R' || id[1] != 'I' || id[2] != 'F' || id[3] != 'F') return null;
            le32(in);
            in.readFully(id);
            int format = 1, channels = 2, rate = 44100, bits = 16;
            while (true) {
                try {
                    in.readFully(id);
                } catch (EOFException e) {
                    return null;
                }
                long size = le32(in) & 0xffffffffL;
                String name = new String(id, java.nio.charset.StandardCharsets.US_ASCII);
                if (name.equals("fmt ")) {
                    format = le16(in);
                    channels = Math.max(1, le16(in));
                    rate = le32(in);
                    le32(in);
                    le16(in);
                    bits = le16(in);
                    if (format == 0xFFFE && size >= 26) {
                        le16(in);
                        le16(in);
                        le32(in);
                        format = le16(in);
                        in.skipBytes((int) size - 26);
                    } else {
                        in.skipBytes((int) size - 16);
                    }
                } else if (name.equals("data")) {
                    int bytes = Math.max(1, bits / 8);
                    int frame = bytes * channels;
                    long frames = size / frame;
                    int perBucket = Math.max(1, rate / PER_SECOND);
                    float[] out = new float[(int) (frames / perBucket) + 1];
                    byte[] buf = new byte[frame * 4096];
                    long done = 0;
                    float peak = 0f;
                    int inBucket = 0, bucket = 0;
                    while (done < frames) {
                        int want = (int) Math.min(4096, frames - done);
                        int n = 0;
                        while (n < want * frame) {
                            int r = in.read(buf, n, want * frame - n);
                            if (r < 0) break;
                            n += r;
                        }
                        int got = n / frame;
                        if (got == 0) break;
                        for (int i = 0; i < got; i++) {
                            int o = i * frame;
                            float v;
                            if (format == 3 && bits == 32) {
                                int b = (buf[o] & 255) | (buf[o + 1] & 255) << 8 | (buf[o + 2] & 255) << 16
                                    | buf[o + 3] << 24;
                                v = Math.abs(Float.intBitsToFloat(b));
                            } else if (bits == 16) {
                                v = Math.abs((short) ((buf[o] & 255) | buf[o + 1] << 8)) / 32768f;
                            } else if (bits == 24) {
                                v = Math.abs(((buf[o] & 255) | (buf[o + 1] & 255) << 8 | buf[o + 2] << 16)) / 8388608f;
                            } else if (bits == 32) {
                                v = Math.abs((float) ((buf[o] & 255) | (buf[o + 1] & 255) << 8
                                    | (buf[o + 2] & 255) << 16 | buf[o + 3] << 24)) / 2147483648f;
                            } else {
                                v = Math.abs((buf[o] & 255) - 128) / 128f;
                            }
                            if (v > peak) peak = v;
                            if (++inBucket >= perBucket) {
                                if (bucket < out.length) out[bucket++] = peak;
                                peak = 0f;
                                inBucket = 0;
                            }
                        }
                        done += got;
                        if (got < want) break;
                    }
                    return out;
                } else {
                    in.skipBytes((int) (size + (size & 1)));
                }
            }
        }
    }
}
