package org.kelvinizer.audio;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioSystem;
import java.io.File;
import java.util.Map;

/**
 * Reads WAV duration via Java Sound API (works without third-party codecs).
 */
public final class WavDurationUtil {

    private WavDurationUtil() {}

    public static long getDurationMillis(File file) throws Exception {
        AudioFileFormat format = AudioSystem.getAudioFileFormat(file);
        Map<String, Object> props = format.properties();
        if (props != null && props.containsKey("duration")) {
            return (Long) props.get("duration") / 1000L;
        }
        throw new UnsupportedOperationException(
            "Cannot read WAV duration: " + file.getAbsolutePath());
    }

    public static float getDurationSeconds(File file) throws Exception {
        return getDurationMillis(file) / 1000f;
    }

    public static float getDurationSecondsSafe(File file) {
        try {
            return getDurationSeconds(file);
        } catch (Exception e) {
            Gdx.app.error("WavDurationUtil", "Failed: " + file.getAbsolutePath(), e);
            return 0f;
        }
    }

    public static float getDurationSecondsSafe(FileHandle handle) {
        if (handle == null || !handle.exists()) return 0f;
        return getDurationSecondsSafe(handle.file());
    }
}
