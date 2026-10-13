package org.kelvinizer.audio;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.utils.Disposable;

import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.sound.sampled.FloatControl;
import java.io.File;

/**
 * Lightweight WAV playback using the standard Java Sound API (no MP3SPI).
 * Suitable for chart preview in {@link org.kelvinizer.chartEditor.ChartVisualizer}.
 */
public class WavMusicPlayer implements Disposable {

    private Clip clip;
    private long durationMicros;

    public void load(File wavFile) throws Exception {
        dispose();
        AudioInputStream stream = AudioSystem.getAudioInputStream(wavFile);
        durationMicros = stream.getFrameLength() * stream.getFormat().getFrameSize() * 1_000_000L
            / (long) stream.getFormat().getFrameRate() / stream.getFormat().getChannels();
        if (durationMicros <= 0) {
            durationMicros = (long) (WavDurationUtil.getDurationSeconds(wavFile) * 1_000_000L);
        }
        clip = AudioSystem.getClip();
        clip.open(stream);
        stream.close();
    }

    public void load(FileHandle handle) throws Exception {
        if (handle == null || !handle.exists()) return;
        load(handle.file());
    }

    public boolean isLoaded() {
        return clip != null;
    }

    public void play() {
        if (clip == null) return;
        if (!clip.isRunning()) clip.start();
    }

    public void pause() {
        if (clip == null) return;
        clip.stop();
        clip.setMicrosecondPosition(clip.getMicrosecondPosition());
    }

    public void stop() {
        if (clip == null) return;
        clip.stop();
        clip.setFramePosition(0);
    }

    public boolean isPlaying() {
        return clip != null && clip.isRunning();
    }

    /** @return position in milliseconds */
    public float getPositionMs() {
        if (clip == null) return 0f;
        return clip.getMicrosecondPosition() / 1000f;
    }

    /** @return total length in milliseconds */
    public float getDurationMs() {
        if (clip == null) return 0f;
        return durationMicros / 1000f;
    }

    public void setPositionMs(float ms) {
        if (clip == null) return;
        long max = durationMicros;
        long pos = (long) (Math.max(0f, ms) * 1000f);
        if (max > 0) pos = Math.min(pos, max);
        clip.setMicrosecondPosition(pos);
    }

    public void setVolume(float linear01) {
        if (clip == null || !clip.isControlSupported(FloatControl.Type.MASTER_GAIN)) return;
        FloatControl gain = (FloatControl) clip.getControl(FloatControl.Type.MASTER_GAIN);
        float dB = (float) (20.0 * Math.log10(Math.max(0.0001f, linear01)));
        gain.setValue(Math.max(gain.getMinimum(), Math.min(gain.getMaximum(), dB)));
    }

    @Override
    public void dispose() {
        if (clip != null) {
            try {
                clip.stop();
                clip.close();
            } catch (Exception e) {
                Gdx.app.error("WavMusicPlayer", e.getMessage());
            }
            clip = null;
        }
    }
}
