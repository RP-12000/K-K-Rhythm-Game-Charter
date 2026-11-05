package org.kelvinizer.charter;

import com.badlogic.gdx.Application;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.audio.Music;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.utils.Disposable;
import org.kelvinizer.audio.Mp3Info;
import org.kelvinizer.audio.WavDurationUtil;

/**
 * 编辑器的播放和时钟：歌曲时间（毫秒）可以在 0 之前开始，音频放完以后接着走到暂停为止，支持变速播放和随意跳转。
 * Editor playback and clock. Song time in ms (negative before the audio starts, running on silently
 * past its end until paused), smoothed against the stream position; plays at 0.25–1× (pitch follows,
 * like most chart editors) and seeks anywhere. MP3 decoder delay on desktop is taken off as in the
 * game's {@code SongClock}.
 */
public final class Playback implements Disposable {
    private final Platform platform;
    private Music music;
    private float delayMs;
    private float durationMs;
    private boolean playing, started;
    private float speed = 1f;
    private double pos;
    private long lastNanos = System.nanoTime();
    private double lastRaw = Double.NaN;

    public Playback(Platform platform) {
        this.platform = platform;
    }

    public void load(FileHandle f) {
        dispose();
        pos = 0;
        durationMs = 0f;
        delayMs = 0f;
        if (f == null || !f.exists()) return;
        try {
            // Android seeks an MP3 by its table of contents, far off the place it reports (Mp3Info#seekableCopy)
            boolean android = Gdx.app.getType() == Application.ApplicationType.Android;
            music = Gdx.audio.newMusic(android ? Mp3Info.seekableCopy(f) : f);
        } catch (Exception e) {
            Gdx.app.error("Charter", "cannot play " + f.name() + ": " + e.getMessage());
            music = null;
        }
        if (Mp3Info.isMp3(f)) {
            durationMs = Mp3Info.durationMs(f);
            delayMs = Mp3Info.startDelayMs(f);
        } else if (f.extension().equalsIgnoreCase("wav")) {
            durationMs = WavDurationUtil.getDurationMillisSafe(f);
        }
    }

    /** @return the song length in ms (0 when unknown). */
    public float durationMs() {
        return durationMs;
    }

    public void setDurationMs(float d) {
        durationMs = d;
    }

    public boolean playing() {
        return playing;
    }

    public double pos() {
        return pos;
    }

    public float speed() {
        return speed;
    }

    public void play() {
        if (playing) return;
        playing = true;
        started = false;
        lastNanos = System.nanoTime();
        lastRaw = Double.NaN;
        if (pos >= 0) startMusic();
    }

    private void startMusic() {
        started = true;
        lastRaw = Double.NaN;
        if (music == null) return;
        // always from a fresh source: near the end only the last buffers are queued, and libGDX's setPosition then
        // leaves a stale one in the queue that keeps playing between the new ones (the end heard over the start)
        music.stop();
        if (durationMs > 0f && pos >= durationMs) return; // past the audio's end the clock runs on silently
        music.play();
        music.setPosition((float) ((pos + delayMs) / 1000.0));
        platform.setMusicSpeed(music, speed);
    }

    public void pause() {
        if (!playing) return;
        update();
        playing = false;
        if (music != null) music.pause();
    }

    public void toggle() {
        if (playing) pause();
        else play();
    }

    public void seek(double ms) {
        pos = ms;
        lastNanos = System.nanoTime();
        lastRaw = Double.NaN;
        if (!playing) return;
        if (ms < 0) {
            if (music != null && started) music.pause();
            started = false;
        } else {
            // also restarts a stream that already ran out (seeking back from a silent tail)
            startMusic();
        }
    }

    public void setSpeed(float s) {
        speed = s;
        if (music != null && playing && started) platform.setMusicSpeed(music, s);
    }

    /** Advances the clock; call once per frame. */
    public void update() {
        long now = System.nanoTime();
        double dt = (now - lastNanos) / 1e6;
        lastNanos = now;
        if (!playing) return;
        double pred = pos + dt * speed;
        if (!started) {
            pos = pred;
            if (pos >= 0) startMusic();
            return;
        }
        if (music != null && music.isPlaying()) {
            double raw = music.getPosition() * 1000.0 - delayMs;
            if (raw != lastRaw) {
                lastRaw = raw;
                double err = raw - pred;
                double next = Math.abs(err) > 80.0 ? raw : pred + 0.15 * err;
                pos = Math.max(pos, next);
            } else {
                pos = pred;
            }
        } else {
            pos = pred;
        }
    }

    @Override
    public void dispose() {
        if (music != null) {
            music.stop();
            music.dispose();
        }
        music = null;
        playing = false;
    }
}
