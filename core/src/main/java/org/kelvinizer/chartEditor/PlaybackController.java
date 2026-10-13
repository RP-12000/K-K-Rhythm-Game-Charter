package org.kelvinizer.chartEditor;

/**
 * Timeline clock for chart preview (milliseconds).
 * Audio position can be synced into this from {@link org.kelvinizer.audio.WavMusicPlayer}.
 */
public class PlaybackController {

    private float currentTimeMs;
    private float startTimeMs = 0f;
    private float durationMs = 0f;
    private float speed = 1f;
    private boolean playing;

    public void update(float delta) {
        if (playing) {
            currentTimeMs += delta * 1000f * speed;
            if (durationMs > startTimeMs && currentTimeMs >= durationMs) {
                currentTimeMs = durationMs;
                playing = false;
            }
        }
    }

    public void play() { playing = true; }

    public void pause() { playing = false; }

    public boolean isPlaying() { return playing; }

    public void seek(float timeMs) {
        currentTimeMs = durationMs > startTimeMs
            ? Math.max(startTimeMs, Math.min(timeMs, durationMs))
            : Math.max(startTimeMs, timeMs);
    }

    public void restart() {
        currentTimeMs = startTimeMs;
        playing = false;
    }

    public float getTimeMs() { return currentTimeMs; }

    public float getDurationMs() { return durationMs; }

    public void setDurationMs(float durationMs) {
        this.durationMs = Math.max(startTimeMs, durationMs);
        if (durationMs > 0f && currentTimeMs > durationMs) currentTimeMs = durationMs;
    }

    public float getStartTimeMs() { return startTimeMs; }

    public void setStartTimeMs(float startTimeMs) {
        this.startTimeMs = startTimeMs;
        if (durationMs < startTimeMs) durationMs = startTimeMs;
        if (currentTimeMs < startTimeMs) currentTimeMs = startTimeMs;
    }

    public void setSpeed(float speed) { this.speed = speed; }

    public float getProgress() {
        float span = durationMs - startTimeMs;
        return span > 0f ? (currentTimeMs - startTimeMs) / span : 0f;
    }
}
