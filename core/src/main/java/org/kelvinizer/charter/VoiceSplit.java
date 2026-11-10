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
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * 声部分析（Kelvin 2026-11-07 19:51「分离比较显著的声部」）：把歌分成鼓、低音、旋律三个声部，找出每个声部的起音点和
 * 响度，旋律还有音高；三个声部写成 WAV，可以单独播放。后台线程算，结果存在编辑器旁边的 voices 文件夹，音频不变就直接读。
 * Splits a song into drums, bass and melody: median-filter harmonic/percussive separation (drums = the percussive
 * part), the harmonic part split at 250 Hz (bass below, melody above). The soft masks add up to one, so the three
 * voices add back up to the song. Each voice gets its loudness per frame and its onsets (peaks of the spectral flux),
 * the melody its strongest pitch; the voices are written as 16-bit mono WAV files to play alone (a quiet one lifted).
 * Runs on a worker thread; the results are cached in {@code voices/<song>/} beside the editor and reused while the
 * audio file is unchanged.
 */
public final class VoiceSplit {
    public static final int DRUMS = 0, BASS = 1, MELODY = 2, VOICES = 3;
    public static final String[] NAMES = {"鼓", "低音", "旋律"};
    /** The melody's pitch range (MIDI notes, 165–1319 Hz). */
    public static final int LOW_MIDI = 52, HIGH_MIDI = 88;
    private static final String[] FILES = {"drums.wav", "bass.wav", "melody.wav"};
    /**
     * Window and hop at the analysis rate (about 22 kHz): 93 ms and 11.6 ms. The harmonic median spans MT frames
     * (360 ms: shorter sounds count as hits), the percussive one MF bins (180 Hz).
     */
    static final int N = 2048, HOP = 256, MT = 31, MF = 17, BINS = N / 2 + 1;
    /**
     * Added to each flux peak's time. Clean clicks peak about 18 ms early, but on real songs the drum onsets already
     * sit 1–4 ms after the charted notes (Dash from my eyes, Confusion: 2150 and 2218 notes), so none.
     */
    static final float LAG_MS = 0f;
    private static final int MAGIC = 0x4B4B5653, VERSION = 1, HARMONICS = 6;
    private static final float[] COS = new float[N / 2], SIN = new float[N / 2];

    static {
        for (int k = 0; k < N / 2; k++) {
            COS[k] = (float) Math.cos(2 * Math.PI * k / N);
            SIN[k] = (float) Math.sin(2 * Math.PI * k / N);
        }
    }

    /** One analysis at a time, so a second one of the same song waits and then reads the first one's files. */
    private static final Object ONE = new Object();
    private final FileHandle audio, dir;
    private volatile float progress;
    private volatile int state;          // 0 working, 1 ready, -1 failed
    private volatile String error = "";
    private int rate, frames;
    private float[][] env;               // loudness per voice and frame, 0..1
    private float[][] onMs, onAmp;       // onsets per voice: music time (ms) and strength (0..1)
    private byte[] pitch;                // the melody's MIDI note per frame, 0 = none

    /** Starts the analysis of {@code audio} (results kept in {@code dir}) on a worker thread. */
    public VoiceSplit(FileHandle audio, FileHandle dir) {
        this.audio = audio;
        this.dir = dir;
        Thread t = new Thread(this::run, "charter-voices");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    private void run() {
        try {
            synchronized (ONE) {
                if (!readCache()) {
                    analyse(decode());
                    writeCache();
                }
            }
            progress = 1f;
            state = 1;
        } catch (Throwable e) {
            error = String.valueOf(e.getMessage());
            Gdx.app.error("Charter", "voices: " + e);
            state = -1;
        }
    }

    public boolean ready() {
        return state == 1;
    }

    public boolean failed() {
        return state == -1;
    }

    public String error() {
        return error;
    }

    /** @return how far the analysis is, 0..1 */
    public float progress() {
        return progress;
    }

    /** @return the voice's WAV file (once ready) */
    public FileHandle wav(int v) {
        return dir.child(FILES[v]);
    }

    /** @return the voice's loudness (0..1) between music times {@code fromMs} and {@code toMs}: its loudest frame. */
    public float level(int v, double fromMs, double toMs) {
        int a = Math.max(0, frame(fromMs)), b = Math.min(frames, Math.max(frame(toMs), a + 1));
        float m = 0f;
        for (int i = a; i < b; i++) m = Math.max(m, env[v][i]);
        return m;
    }

    /** @return the melody's MIDI note at music time {@code ms}, 0 where it has none. */
    public int pitchAt(double ms) {
        int i = frame(ms);
        return i >= 0 && i < frames ? pitch[i] : 0;
    }

    /** @return the voice's onsets, music time in ms, ascending */
    public float[] onsets(int v) {
        return onMs[v];
    }

    /** @return the strengths (0..1) of {@link #onsets(int)} */
    public float[] strengths(int v) {
        return onAmp[v];
    }

    private int frame(double ms) {
        return (int) Math.floor(ms * rate / 1000.0 / HOP + 0.5);
    }

    // ================================================================== decoding

    /** @return mono samples from music time 0 (MP3 through JLayer like the waveform, else PCM WAV) */
    private float[] decode() throws IOException {
        return Mp3Info.isMp3(audio) ? mp3() : wav();
    }

    /** Collects mono samples; above 32 kHz keeps every second one after a [1/4, 1/2, 1/4] low-pass. */
    private final class Pcm {
        float[] a = new float[1 << 20];
        int n, half, odd;
        float p1, p2;

        void start(int srcRate) {
            half = srcRate > 32000 ? 1 : 0;
            rate = srcRate >> half;
        }

        void add(float v) {
            if (half == 1) {
                float f = 0.25f * p2 + 0.5f * p1 + 0.25f * v;
                p2 = p1;
                p1 = v;
                if ((odd ^= 1) == 0) return;
                v = f;
            }
            if (n == a.length) a = Arrays.copyOf(a, n * 2);
            a[n++] = v;
        }

        float[] done() throws IOException {
            if (rate <= 0) throw new IOException("unknown sample rate");
            return Arrays.copyOf(a, n);
        }
    }

    private float[] mp3() throws IOException {
        Mp3Info info = Mp3Info.read(audio);
        float delayMs = info != null ? info.startDelayMs(false) : 0f;   // so sample 0 is music time 0
        Pcm out = new Pcm();
        try (InputStream in = new BufferedInputStream(audio.read(), 1 << 16)) {
            Bitstream bs = new Bitstream(in);
            MP3Decoder dec = new MP3Decoder();
            OutputBuffer ob = null;
            int ch = 2;
            long skip = 0;
            Header h;
            while ((h = bs.readFrame()) != null) {
                if (ob == null) {
                    ch = h.mode() == Header.SINGLE_CHANNEL ? 1 : 2;
                    ob = new OutputBuffer(ch, false);
                    dec.setOutputBuffer(ob);
                    out.start(h.getSampleRate());
                    skip = Math.round(delayMs * h.getSampleRate() / 1000.0);
                }
                try {
                    dec.decodeFrame(h, bs);
                } catch (Exception ignored) {
                    // a damaged frame is skipped, as libGDX does
                }
                bs.closeFrame();
                int len = ob.reset();
                byte[] b = ob.getBuffer();
                for (int i = 0; i + 2 * ch - 1 < len; i += 2 * ch) {
                    if (skip > 0) {
                        skip--;
                        continue;
                    }
                    float v = (short) ((b[i] & 0xff) | (b[i + 1] << 8));
                    if (ch > 1) v = 0.5f * (v + (short) ((b[i + 2] & 0xff) | (b[i + 3] << 8)));
                    out.add(v / 32768f);
                }
            }
            bs.close();
        } catch (Exception e) {
            if (out.n == 0) throw new IOException("mp3: " + e.getMessage(), e);
        }
        return out.done();
    }

    /** Reads 8/16/24/32-bit PCM or 32-bit float WAV, all channels mixed. */
    private float[] wav() throws IOException {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(audio.read(), 1 << 16))) {
            byte[] id = new byte[4];
            in.readFully(id);
            if (id[0] != 'R' || id[1] != 'I' || id[2] != 'F' || id[3] != 'F') throw new IOException("not MP3 or WAV");
            in.readInt();
            in.readFully(id);
            int format = 1, ch = 1, bits = 16;
            Pcm out = new Pcm();
            while (true) {
                in.readFully(id);
                long size = Integer.reverseBytes(in.readInt()) & 0xffffffffL;
                String name = new String(id, StandardCharsets.US_ASCII);
                if (name.equals("fmt ")) {
                    byte[] f = new byte[(int) (size + (size & 1))];
                    in.readFully(f);
                    ByteBuffer b = ByteBuffer.wrap(f).order(ByteOrder.LITTLE_ENDIAN);
                    format = b.getShort(0) & 0xffff;
                    ch = Math.max(1, b.getShort(2));
                    out.start(b.getInt(4));
                    bits = b.getShort(14);
                    if (format == 0xFFFE && size >= 26) format = b.getShort(24) & 0xffff;
                } else if (name.equals("data")) {
                    int bytes = Math.max(1, bits / 8), frame = bytes * ch;
                    byte[] buf = new byte[frame * 4096];
                    for (long left = size / frame; left > 0; ) {
                        int want = (int) Math.min(4096, left), got = 0;
                        while (got < want * frame) {
                            int r = in.read(buf, got, want * frame - got);
                            if (r < 0) break;
                            got += r;
                        }
                        int fr = got / frame;
                        for (int i = 0; i < fr; i++) {
                            float sum = 0f;
                            for (int c = 0; c < ch; c++) sum += sample(buf, i * frame + c * bytes, bits, format);
                            out.add(sum / ch);
                        }
                        if (fr < want) break;
                        left -= fr;
                    }
                    return out.done();
                } else {
                    in.skipBytes((int) (size + (size & 1)));
                }
            }
        }
    }

    private static float sample(byte[] b, int o, int bits, int format) {
        switch (bits) {
            case 8:
                return ((b[o] & 255) - 128) / 128f;
            case 16:
                return (short) ((b[o] & 255) | b[o + 1] << 8) / 32768f;
            case 24:
                return ((b[o] & 255) | (b[o + 1] & 255) << 8 | b[o + 2] << 16) / 8388608f;
            default:
                int v = (b[o] & 255) | (b[o + 1] & 255) << 8 | (b[o + 2] & 255) << 16 | b[o + 3] << 24;
                return format == 3 ? Float.intBitsToFloat(v) : v / 2147483648f;
        }
    }

    // ================================================================== analysis

    private void analyse(float[] x) throws IOException {
        int len = x.length;
        if (len == 0) throw new IOException("no samples");
        frames = (len + N / 2) / HOP + 1;
        float[] win = new float[N];
        for (int i = 0; i < N; i++) win[i] = (float) (0.5 - 0.5 * Math.cos(2 * Math.PI * i / N));
        float ola = 0f;                              // sum of the squared windows over any sample
        for (int i = 0; i < N; i += HOP) ola += win[i] * win[i];
        float[] wb = new float[BINS];                // the bass share of the harmonic part
        for (int k = 0; k < BINS; k++) {
            float f = (float) k * rate / N;
            wb[k] = f <= 200f ? 1f : f >= 300f ? 0f : 0.5f + 0.5f * (float) Math.cos(Math.PI * (f - 200f) / 100f);
        }
        float[][] hpos = new float[HIGH_MIDI - LOW_MIDI + 1][HARMONICS];
        for (int m = 0; m < hpos.length; m++) {
            for (int h = 0; h < HARMONICS; h++) {
                hpos[m][h] = (float) ((h + 1) * 440.0 * Math.pow(2, (m + LOW_MIDI - 69) / 12.0) * N / rate);
            }
        }
        float[][] mag = new float[MT][BINS], fre = new float[MT][BINS], fim = new float[MT][BINS];
        float[][] sorted = new float[BINS][MT];      // per bin, the last MT frames' magnitudes in order
        float[] re = new float[N], im = new float[N], h = new float[BINS], p = new float[BINS], kw = new float[MF];
        float[][] gain = new float[VOICES][BINS], prev = new float[VOICES][BINS], acc = new float[VOICES][N];
        float[][] flux = new float[VOICES][frames];
        float[] sal = new float[frames];
        env = new float[VOICES][frames];
        pitch = new byte[frames];
        if (!dir.exists()) dir.mkdirs();
        Wav[] out = new Wav[VOICES];
        try {
            for (int v = 0; v < VOICES; v++) out[v] = new Wav(dir.child(FILES[v]), len, rate);
            for (int tp = 0; tp < frames + MT / 2; tp++) {
                // the newest frame goes into the ring and each bin's sorted window (out goes the one MT back)
                int slot = tp % MT, s0 = tp * HOP - N / 2;
                float[] m = mag[slot], r = fre[slot], q = fim[slot];
                if (tp < frames) {
                    for (int i = 0; i < N; i++) {
                        int j = s0 + i;
                        re[i] = j >= 0 && j < len ? x[j] * win[i] : 0f;
                        im[i] = 0f;
                    }
                    fft(re, im, false);
                }
                for (int k = 0; k < BINS; k++) {
                    boolean in = tp < frames;
                    float nm = in ? (float) Math.sqrt(re[k] * re[k] + im[k] * im[k]) : 0f;
                    replace(sorted[k], m[k], nm);
                    m[k] = nm;
                    r[k] = in ? re[k] : 0f;
                    q[k] = in ? im[k] : 0f;
                }
                int tc = tp - MT / 2;
                if (tc < 0) continue;
                // the middle frame: harmonic = median over time, percussive = median over frequency
                int cs = tc % MT;
                float[] cm = mag[cs], cr = fre[cs], ci = fim[cs];
                Arrays.fill(kw, 0f);
                for (int k = 0; k <= MF / 2; k++) replace(kw, 0f, cm[k]);
                for (int k = 0; k < BINS; k++) {
                    p[k] = kw[MF / 2];
                    h[k] = sorted[k][MT / 2];
                    int drop = k - MF / 2, add = k + MF / 2 + 1;
                    replace(kw, drop >= 0 ? cm[drop] : 0f, add < BINS ? cm[add] : 0f);
                }
                for (int k = 0; k < BINS; k++) {
                    float h2 = h[k] * h[k], p2 = p[k] * p[k], s = h2 + p2, mh = s > 1e-12f ? h2 / s : 0.5f;
                    gain[DRUMS][k] = 1f - mh;
                    gain[BASS][k] = mh * wb[k];
                    gain[MELODY][k] = mh * (1f - wb[k]);
                }
                for (int v = 0; v < VOICES; v++) {
                    float e = 0f, f = 0f;
                    float[] g = gain[v], pv = prev[v];
                    for (int k = 1; k < BINS; k++) {
                        float a = g[k] * cm[k], l = (float) Math.log1p(a);
                        e += a * a;
                        if (l > pv[k]) f += l - pv[k];
                        pv[k] = l;
                    }
                    env[v][tc] = (float) Math.sqrt(e);
                    flux[v][tc] = f;
                }
                // the melody's pitch: the note whose first harmonics carry the most of it
                float best = 0f;
                int bm = 0;
                float[] gm = gain[MELODY];
                for (int mi = 0; mi < hpos.length; mi++) {
                    float s = 0f;
                    for (int k = 0; k < HARMONICS; k++) {
                        float b = hpos[mi][k];
                        if (b >= BINS - 1) break;
                        int b0 = (int) b;
                        float fr = b - b0;
                        s += (1f - 0.1f * k) * (gm[b0] * cm[b0] * (1f - fr) + gm[b0 + 1] * cm[b0 + 1] * fr);
                    }
                    if (s > best) {
                        best = s;
                        bm = mi + LOW_MIDI;
                    }
                }
                sal[tc] = best;
                pitch[tc] = (byte) bm;
                // drums + i * bass through one inverse transform; the melody is the rest of the frame
                float[] gd = gain[DRUMS], gb = gain[BASS];
                for (int k = 0; k < BINS; k++) {
                    float dr = gd[k] * cr[k], di = gd[k] * ci[k], br = gb[k] * cr[k], bi = gb[k] * ci[k];
                    re[k] = dr - bi;
                    im[k] = di + br;
                    if (k > 0 && k < N / 2) {
                        re[N - k] = dr + bi;
                        im[N - k] = br - di;
                    }
                }
                fft(re, im, true);
                int c0 = tc * HOP - N / 2;
                for (int i = 0; i < N; i++) {
                    int j = c0 + i;
                    float xw = j >= 0 && j < len ? x[j] * win[i] : 0f, d = re[i] / N, b = im[i] / N;
                    acc[DRUMS][i] += d * win[i];
                    acc[BASS][i] += b * win[i];
                    acc[MELODY][i] += (xw - d - b) * win[i];
                }
                for (int v = 0; v < VOICES; v++) {
                    out[v].write(acc[v], HOP, c0, 1f / ola);
                    System.arraycopy(acc[v], HOP, acc[v], 0, N - HOP);
                    Arrays.fill(acc[v], N - HOP, N, 0f);
                }
                progress = 0.97f * tc / frames;
            }
        } finally {
            for (Wav w : out) if (w != null) w.close();
        }
        // loudness 0..1 (against a high percentile, so one spike does not flatten the rest), onsets, voiced pitch
        onMs = new float[VOICES][];
        onAmp = new float[VOICES][];
        for (int v = 0; v < VOICES; v++) {
            float top = Math.max(1e-6f, percentile(env[v], 0.995f));
            for (int i = 0; i < frames; i++) env[v][i] = Math.min(1f, env[v][i] / top);
            pick(v, flux[v]);
        }
        float voiced = 0.25f * percentile(sal, 0.95f);
        for (int i = 0; i < frames; i++) if (sal[i] < voiced || env[MELODY][i] < 0.05f) pitch[i] = 0;
    }

    /** Keeps a sorted window sorted while {@code old} (one of its values) is swapped for {@code nw}. */
    private static void replace(float[] a, float old, float nw) {
        int i = Arrays.binarySearch(a, old);
        if (i < 0) i = Math.min(a.length - 1, -i - 1);
        if (nw > old) {
            for (; i + 1 < a.length && a[i + 1] < nw; i++) a[i] = a[i + 1];
        } else {
            for (; i > 0 && a[i - 1] > nw; i--) a[i] = a[i - 1];
        }
        a[i] = nw;
    }

    private static float percentile(float[] a, float q) {
        if (a.length == 0) return 0f;
        float[] c = a.clone();
        Arrays.sort(c);
        return c[Math.min(c.length - 1, (int) (q * c.length))];
    }

    /** Onsets: flux peaks highest within ±30 ms, above the local mean and a floor, at least 60 ms apart. */
    private void pick(int v, float[] nov) {
        int n = nov.length;
        float hopMs = 1000f * HOP / rate, top = Math.max(1e-6f, percentile(nov, 0.99f));
        int w = Math.max(1, Math.round(30f / hopMs)), m = Math.round(200f / hopMs), gap = Math.round(60f / hopMs);
        double[] pre = new double[n + 1];
        for (int i = 0; i < n; i++) pre[i + 1] = pre[i] + nov[i];
        FloatArray t = new FloatArray(), a = new FloatArray();
        int last = -gap;
        for (int i = 0; i < n; i++) {
            float x = nov[i];
            int lo = Math.max(0, i - m), hi = Math.min(n, i + m + 1);
            if (x < 0.08f * top || x < 1.3f * (float) ((pre[hi] - pre[lo]) / (hi - lo)) + 0.04f * top) continue;
            boolean peak = i - last >= gap;
            for (int j = Math.max(0, i - w); peak && j <= Math.min(n - 1, i + w); j++) {
                if (nov[j] > x || nov[j] == x && j < i) peak = false;
            }
            if (!peak) continue;
            last = i;
            t.add(i * hopMs + LAG_MS);
            a.add(Math.min(1f, x / top));
        }
        onMs[v] = t.toArray();
        onAmp[v] = a.toArray();
    }

    /** In-place radix-2 FFT of length {@link #N}; {@code inv} runs the inverse (unscaled). */
    private static void fft(float[] re, float[] im, boolean inv) {
        for (int i = 1, j = 0; i < N; i++) {
            int bit = N >> 1;
            for (; (j & bit) != 0; bit >>= 1) j ^= bit;
            j ^= bit;
            if (i < j) {
                float t = re[i];
                re[i] = re[j];
                re[j] = t;
                t = im[i];
                im[i] = im[j];
                im[j] = t;
            }
        }
        for (int len = 2; len <= N; len <<= 1) {
            int half = len >> 1, step = N / len;
            for (int i = 0; i < N; i += len) {
                for (int k = 0; k < half; k++) {
                    float wr = COS[k * step], wi = inv ? SIN[k * step] : -SIN[k * step];
                    int a = i + k, b = a + half;
                    float xr = re[b] * wr - im[b] * wi, xi = re[b] * wi + im[b] * wr;
                    re[b] = re[a] - xr;
                    im[b] = im[a] - xi;
                    re[a] += xr;
                    im[a] += xi;
                }
            }
        }
    }

    /** A 16-bit mono WAV written front to back; closing pads it to its length and lifts a quiet one to 0.9. */
    private static final class Wav {
        private final FileHandle file;
        private final int len;
        private final OutputStream out;
        private final byte[] buf = new byte[2 * N];
        private int done;
        private float peak;

        Wav(FileHandle file, int len, int rate) {
            this.file = file;
            this.len = len;
            out = new BufferedOutputStream(file.write(false), 1 << 16);
            ByteBuffer h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
            h.put(ascii("RIFF")).putInt(36 + 2 * len).put(ascii("WAVEfmt ")).putInt(16).putShort((short) 1)
                .putShort((short) 1).putInt(rate).putInt(rate * 2).putShort((short) 2).putShort((short) 16)
                .put(ascii("data")).putInt(2 * len);
            try {
                out.write(h.array());
            } catch (IOException e) {
                throw new com.badlogic.gdx.utils.GdxRuntimeException(e);
            }
        }

        /** Writes {@code n} samples of {@code a} (times {@code scale}) that start at sample {@code start}. */
        void write(float[] a, int n, int start, float scale) throws IOException {
            int k = 0;
            for (int i = Math.max(0, done - start); i < n && start + i < len; i++) {
                float v = a[i] * scale;
                peak = Math.max(peak, Math.abs(v));
                int s = Math.round(Math.max(-1f, Math.min(1f, v)) * 32767f);
                buf[k++] = (byte) s;
                buf[k++] = (byte) (s >> 8);
                done++;
            }
            out.write(buf, 0, k);
        }

        void close() throws IOException {
            for (; done < len; done++) out.write(new byte[2]);
            out.close();
            if (peak <= 1e-4f || peak >= 0.5f) return;
            float g = Math.min(8f, 0.9f / peak);
            try (RandomAccessFile f = new RandomAccessFile(file.file(), "rw")) {
                byte[] b = new byte[1 << 16];
                for (long pos = 44; pos < 44 + 2L * len; pos += b.length) {
                    int n = (int) Math.min(b.length, 44 + 2L * len - pos);
                    f.seek(pos);
                    f.readFully(b, 0, n);
                    for (int i = 0; i + 1 < n; i += 2) {
                        int s = Math.round((short) ((b[i] & 255) | b[i + 1] << 8) * g);
                        s = Math.max(-32768, Math.min(32767, s));
                        b[i] = (byte) s;
                        b[i + 1] = (byte) (s >> 8);
                    }
                    f.seek(pos);
                    f.write(b, 0, n);
                }
            }
        }

        private static byte[] ascii(String s) {
            return s.getBytes(StandardCharsets.US_ASCII);
        }
    }

    // ================================================================== cache

    private boolean readCache() {
        FileHandle f = dir.child("analysis.bin");
        if (!f.exists()) return false;
        for (String w : FILES) if (!dir.child(w).exists()) return false;
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(f.read(), 1 << 16))) {
            if (in.readInt() != MAGIC || in.readInt() != VERSION || in.readLong() != audio.length()
                || in.readLong() != audio.lastModified()) return false;
            rate = in.readInt();
            frames = in.readInt();
            env = new float[VOICES][frames];
            for (float[] e : env) for (int i = 0; i < frames; i++) e[i] = in.readUnsignedShort() / 65535f;
            pitch = new byte[frames];
            in.readFully(pitch);
            onMs = new float[VOICES][];
            onAmp = new float[VOICES][];
            for (int v = 0; v < VOICES; v++) {
                int n = in.readInt();
                onMs[v] = new float[n];
                onAmp[v] = new float[n];
                for (int i = 0; i < n; i++) {
                    onMs[v][i] = in.readFloat();
                    onAmp[v][i] = in.readFloat();
                }
            }
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private void writeCache() throws IOException {
        try (DataOutputStream o = new DataOutputStream(new BufferedOutputStream(
            dir.child("analysis.bin").write(false), 1 << 16))) {
            o.writeInt(MAGIC);
            o.writeInt(VERSION);
            o.writeLong(audio.length());
            o.writeLong(audio.lastModified());
            o.writeInt(rate);
            o.writeInt(frames);
            for (float[] e : env) for (float v : e) o.writeShort(Math.round(v * 65535f));
            o.write(pitch);
            for (int v = 0; v < VOICES; v++) {
                o.writeInt(onMs[v].length);
                for (int i = 0; i < onMs[v].length; i++) {
                    o.writeFloat(onMs[v][i]);
                    o.writeFloat(onAmp[v][i]);
                }
            }
        }
    }
}
