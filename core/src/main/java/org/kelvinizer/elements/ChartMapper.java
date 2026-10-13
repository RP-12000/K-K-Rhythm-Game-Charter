package org.kelvinizer.elements;

import com.badlogic.gdx.files.FileHandle;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;

/**
 * Loads {@link Chart} instances from on-disk chart folders.
 *
 * <p>Expected folder layout:
 * <pre>
 *   Charts/MySong/chart.json
 *   Charts/MySong/track.mp3    (first .mp3 found)
 *   Charts/MySong/cover.jpg    (first .jpg/.jpeg found)
 * </pre>
 *
 * Jackson deserializes JSON; {@link Chart#setAssetFiles} wires LibGDX handles
 * for music and jacket discovery so screens never hard-code filenames.
 */
public final class ChartMapper {
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private ChartMapper() {}

    public static Chart read(InputStream inputStream) throws IOException {
        return MAPPER.readValue(inputStream, Chart.class);
    }

    public static Chart read(FileHandle fileHandle) throws IOException {
        FileHandle chartFile = fileHandle.isDirectory() ? findRequired(fileHandle) : fileHandle;
        Chart chart = read(chartFile.read());
        FileHandle folder = chartFile.parent();
        FileHandle musicFile = findOptional(folder, "wav");
        FileHandle jacketFile = findOptional(folder, "jpg", "jpeg", "png");
        chart.setAssetFiles(chartFile, musicFile, jacketFile);
        return chart;
    }

    public static Chart readFolder(FileHandle folder) throws IOException {
        return read(folder);
    }

    private static FileHandle findRequired(FileHandle folder) throws IOException {
        FileHandle file = findOptional(folder, "json");
        if (file == null) {
            throw new IOException("No .json file found in " + folder.path());
        }
        return file;
    }

    private static FileHandle findOptional(FileHandle folder, String... extensions) {
        for (FileHandle file : folder.list()) {
            if (!file.isDirectory() && hasExtension(file, extensions)) {
                return file;
            }
        }
        return null;
    }

    private static boolean hasExtension(FileHandle file, String... extensions) {
        String actual = file.extension().toLowerCase(Locale.ROOT);
        for (String extension : extensions) {
            if (actual.equals(extension.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }
}
