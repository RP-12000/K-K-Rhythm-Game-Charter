package org.kelvinizer.charter;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.PixmapIO;
import com.badlogic.gdx.graphics.Texture;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import org.kelvinizer.audio.Mp3Info;
import org.kelvinizer.audio.WavDurationUtil;
import org.kelvinizer.elements.Chart;
import org.kelvinizer.elements.ChartMapper;
import org.kelvinizer.elements.Chart7;
import org.kelvinizer.elements.ChartFormatV7;
import org.kelvinizer.elements.ChartV7Convert;
import org.kelvinizer.elements.Seg;
import org.kelvinizer.elements.SongInfo;
import org.kelvinizer.library.ChartHeaderReader;
import org.kelvinizer.library.Difficulty;
import org.kelvinizer.library.SongLibrary;

import java.io.File;
import java.io.FileInputStream;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.function.DoubleUnaryOperator;

/**
 * 曲库：默认直接编辑游戏的 assets/Charts（每首歌一个文件夹，每个难度一个文件），保存前把原文件备份到 Charter/backups；
 * 旁边没有游戏时用程序旁边的 Charts 文件夹。
 * The song library. By default it edits the game's own {@code assets/Charts} (one folder per
 * song, one file per difficulty, CHART_FORMAT §12), so a saved chart can be played in the game
 * right away. Before a file is overwritten for the first time in a session, the old version is
 * copied to {@code backups/} in the Charter folder; deleted songs go to {@code trash/}.
 * The folder can be changed with {@code charts=} in {@code charter.properties}; without the game
 * next door (a packaged Charter on someone else's computer) it is {@code Charts/} next to the program.
 */
public final class Library {
    /** The Charts folder being edited. */
    public final FileHandle charts;
    /** The Charter's own folder (backups, trash, charter.properties). */
    public final FileHandle home;
    public final ArrayList<Song> songs = new ArrayList<>();
    private final HashSet<String> backedUp = new HashSet<>();

    /** One song folder. */
    public static final class Song {
        public final FileHandle dir;
        public final String id;
        public FileHandle audio, jacket;
        public String title = "", artist = "", illustrator = "";
        public float bpm, offsetMs, durationMs;
        /**
         * The song list's preview window from {@code song.json} (Kelvin 2026-10-21), in ms of music time;
         * NaN where the file sets none (the game's default, see {@link #previewStart} / {@link #previewEnd}).
         */
        public float previewStartMs = Float.NaN, previewEndMs = Float.NaN;
        /**
         * The same window in beats of the chart (set by beat; the ms follow the tempo when a chart is saved), NaN = not
         * set.
         */
        public float previewStartBeat = Float.NaN, previewEndBeat = Float.NaN;
        /** The same keys from the song's {@code Charts/index.json} entry: song.json wins key by key. */
        float indexStartMs = Float.NaN, indexEndMs = Float.NaN;
        /**
         * The jacket's 16:9 area and the square around its circle from {@code song.json} (Kelvin 2026-10-21):
         * {x, y, w, h} as fractions of the picture's width and height, y from the top; null = the game's
         * centre crop (the largest centred box).
         */
        public float[] jacketWide, jacketRound;
        public final Diff[] diffs = new Diff[Difficulty.count()];
        /**
         * The game's tutorial (Kelvin 2026-10-17): its own folder next to Charts (normally
         * {@code assets/Introduction}), edited in place with its music; listed last, never trashed.
         */
        public boolean tutorial;
        /** Listed under 练习谱 below the singles (Kelvin 2026-11-02): see {@link #PRACTICE}. */
        public boolean practice;
        /** The folder kept the old chart file names ({@link Difficulty#oldNames}): see {@link Library#renameCharts}. */
        boolean oldNames;
        /** Jacket texture, loaded on first use by the screens (GL thread). */
        public Texture jacketTex;
        public boolean jacketTried;

        Song(FileHandle dir) {
            this.dir = dir;
            this.id = dir.name();
            for (Difficulty d : Difficulty.values()) diffs[d.ordinal()] = new Diff(d);
        }

        public String name() {
            return title.isEmpty() ? id : title;
        }

        /**
         * @return where the game's preview starts: song.json's, else 30% into the music (0 when its length is unknown).
         */
        public float previewStart() {
            if (!Float.isNaN(previewStartMs)) return previewStartMs;
            if (!Float.isNaN(indexStartMs)) return indexStartMs;
            return durationMs > 0f ? Math.round(durationMs * 0.3f) : 0f;
        }

        /** @return where the game's preview ends: song.json's when after the start, else 20 s after the start. */
        public float previewEnd() {
            float st = previewStart(), en = !Float.isNaN(previewEndMs) ? previewEndMs : indexEndMs;
            return !Float.isNaN(en) && en > st ? en : st + 20000f;
        }

        /** @return the square around the jacket's circle as texture coordinates {u0, v0, u1, v1} of a w × h picture */
        public float[] roundUv(float w, float h) {
            float[] b = jacketRound;
            if (b != null) return new float[]{b[0], b[1], b[0] + b[2], b[1] + b[3]};
            float s = Math.min(w, h), u = (w - s) * 0.5f / w, v = (h - s) * 0.5f / h;
            return new float[]{u, v, 1f - u, 1f - v};
        }

        public void disposeJacket() {
            if (jacketTex != null) jacketTex.dispose();
            jacketTex = null;
            jacketTried = false;
        }
    }

    /** One difficulty slot of a song; {@code file} is null when it does not exist yet. */
    public static final class Diff {
        public final Difficulty d;
        public FileHandle file;
        public String constant = "";
        public volatile int notes = -1;
        public volatile String error;

        Diff(Difficulty d) {
            this.d = d;
        }

        public boolean exists() {
            return file != null && file.exists();
        }
    }

    private Library(FileHandle charts, FileHandle home) {
        this.charts = charts;
        this.home = home;
    }

    /**
     * Finds the Charts folder: {@code charter.properties}, {@code -Dkk.charts}, the game next door, else
     * {@code Charts/} in the Charter's folder (next to the jar when packaged, wherever it was started from;
     * {@code -Dkk.home} on Android, where the app's own folder is).
     */
    public static Library open() {
        File wd = new File("").getAbsoluteFile();
        String homeProp = System.getProperty("kk.home");
        File homeDir = homeProp != null ? new File(homeProp) : programDir();
        if (homeDir == null) homeDir = wd.getName().equalsIgnoreCase("assets") && wd.getParentFile() != null
            ? wd.getParentFile() : wd;
        FileHandle home = Gdx.files.absolute(homeDir.getPath());
        String chosen = null;
        Properties p = new Properties();
        File cfg = new File(homeDir, "charter.properties");
        if (cfg.isFile()) {
            try (FileInputStream in = new FileInputStream(cfg)) {
                p.load(new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8));
                chosen = p.getProperty("charts");
            } catch (Exception e) {
                Gdx.app.error("Charter", "charter.properties: " + e.getMessage());
            }
        }
        if (chosen == null || chosen.trim().isEmpty()) chosen = System.getProperty("kk.charts");
        File dir = null;
        if (chosen != null && !chosen.trim().isEmpty()) {
            File f = new File(chosen.trim());
            if (!f.isAbsolute()) f = new File(homeDir, chosen.trim());
            dir = f;
        }
        if (dir == null) {
            String game = System.getProperty("kk.gameDir");
            ArrayList<File> cands = new ArrayList<>();
            if (game != null) cands.add(new File(game, "assets/Charts"));
            cands.add(new File(homeDir.getParentFile() != null ? homeDir.getParentFile() : homeDir,
                "K and K Rhythm Game/assets/Charts"));
            for (File c : cands) {
                if (c.isDirectory()) {
                    dir = c;
                    break;
                }
            }
        }
        if (dir == null) dir = new File(homeDir, "Charts");
        if (!dir.isDirectory()) dir.mkdirs();
        try {
            dir = dir.getCanonicalFile();
        } catch (Exception ignored) {
        }
        Gdx.app.log("Charter", "charts folder: " + dir.getPath());
        return new Library(Gdx.files.absolute(dir.getPath()), home);
    }

    /**
     * @return the folder of a packaged Charter (the jar that holds the launcher), or null when the launcher runs
     *         from class folders (gradle run, the IDE: the Charter folder then comes from the working directory;
     *         core's own classes come from core's build jar there, so they cannot tell)
     */
    private static File programDir() {
        try {
            Class<?> launcher = Class.forName("org.kelvinizer.lwjgl3.Lwjgl3Launcher", false,
                Library.class.getClassLoader());
            File f = new File(launcher.getProtectionDomain().getCodeSource().getLocation().toURI());
            return f.isFile() ? f.getParentFile() : null;
        } catch (Exception | LinkageError e) {
            return null;
        }
    }

    /** @return the Charts path for the header, shortened. */
    public String label() {
        String s = charts.path().replace('\\', '/');
        int i = s.indexOf("/K and K Rhythm Game/");
        return i >= 0 ? s.substring(i + 1) : s;
    }

    // ================================================================== scanning

    /**
     * Song folders the Charter does not list (Kelvin 2026-11-01): the old main-line placeholders Song 1–5. Their
     * folders stay in the game's Charts; the Charter only leaves them out of its list.
     */
    static final java.util.Set<String> HIDDEN = new java.util.HashSet<>(java.util.Arrays.asList(
        "Song 1", "Song 2", "Song 3", "Song 4", "Song 5"));
    /**
     * 练习谱 (Kelvin 2026-11-02): the main-line songs in the game's Charts, listed apart from the singles, together
     * with the songs in the Charter's own {@code practice/} folder and the tutorial (last).
     */
    static final java.util.List<String> PRACTICE = java.util.Arrays.asList("Aleph-0", "marsh mally");
    /** The Charter's folder of practice songs that are not in the game (Altale). */
    static final String PRACTICE_DIR = "practice";

    /** Re-reads the folder list and every song's headers; counts notes on a worker thread. */
    public void scan() {
        for (Song s : songs) s.disposeJacket();
        songs.clear();
        FileHandle[] dirs = charts.list();
        java.util.Arrays.sort(dirs, (a, b) -> a.name().compareToIgnoreCase(b.name()));
        boolean selfTest = System.getProperty("kk.autotest") != null;   // the test copy still uses them
        ArrayList<Song> practice = new ArrayList<>();
        for (FileHandle d : dirs) {
            if (!d.isDirectory() || !selfTest && HIDDEN.contains(d.name())) continue;
            Song s = readSong(d);
            s.practice = !selfTest && PRACTICE.contains(d.name());
            (s.practice ? practice : songs).add(s);
        }
        practice.sort((a, b) -> PRACTICE.indexOf(a.id) - PRACTICE.indexOf(b.id));
        FileHandle[] own = home.child(PRACTICE_DIR).list();
        java.util.Arrays.sort(own, (a, b) -> a.name().compareToIgnoreCase(b.name()));
        for (FileHandle d : own) {
            if (!d.isDirectory() || selfTest) continue;
            Song s = readSong(d);
            s.practice = true;
            practice.add(s);
        }
        songs.addAll(practice);
        FileHandle tut = tutorialDir();
        if (tut != null) {
            Song s = readSong(tut);
            s.tutorial = true;
            s.practice = !selfTest;
            songs.add(s);
        }
        final ArrayList<Song> list = new ArrayList<>(songs);
        Thread t = new Thread(() -> {
            for (Song s : list) countNotes(s);
        }, "charter-scan");
        t.setDaemon(true);
        t.start();
    }

    public Song rescan(Song old) {
        int i = songs.indexOf(old);
        old.disposeJacket();
        Song s = readSong(old.dir);
        s.tutorial = old.tutorial;
        s.practice = old.practice;
        if (i >= 0) songs.set(i, s);
        else songs.add(s);
        Thread t = new Thread(() -> countNotes(s), "charter-scan");
        t.setDaemon(true);
        t.start();
        return s;
    }

    /**
     * @return the game's tutorial folder, a sibling of Charts named by {@code Charts/index.json}
     *         {@code "tutorial"} (default {@link SongLibrary#DEFAULT_TUTORIAL}), or null when it is missing
     */
    private FileHandle tutorialDir() {
        String id = SongLibrary.DEFAULT_TUTORIAL;
        FileHandle index = charts.child("index.json");
        if (index.exists()) {
            try {
                String t = new com.badlogic.gdx.utils.JsonReader().parse(index).getString("tutorial", id);
                if (t != null && !t.trim().isEmpty()) id = t.trim();
            } catch (Exception e) {
                Gdx.app.error("Charter", "index.json: " + e.getMessage());
            }
        }
        FileHandle dir = charts.parent().child(id);
        return dir.isDirectory() ? dir : null;
    }

    /**
     * 旧文件名的歌第一次读到时改成新文件名（Kelvin 2026-11-10 05:50「第一次在旧谱那边打开的时候，把谱面文件的名字也都统一改了，
     * 改成shallow/medium/deep」）：先 Shallow.json → Medium.json，再 Surface.json → Shallow.json，游戏 assets.txt 里的这两行跟着改。
     * Renames an old-named song folder's charts ({@link Difficulty#oldNames}) to the new names, and the matching lines
     * of the game's {@code assets.txt}. The folder keeps the old names, and is read that way, when a rename fails or it
     * already holds a {@code Medium.json}, which the old naming never read.
     *
     * @return whether the folder now has the new names
     */
    boolean renameCharts(FileHandle dir) {
        File d = dir.file(), surface = null, shallow = null;
        String[] names = d.list();
        if (names == null) return false;
        for (String n : names) {
            if (n.equalsIgnoreCase("Medium.json")) return false;
            if (n.equalsIgnoreCase("Surface.json")) surface = new File(d, n);
            if (n.equalsIgnoreCase("Shallow.json")) shallow = new File(d, n);
        }
        File medium = new File(d, "Medium.json");
        if (surface == null || shallow != null && !shallow.renameTo(medium)) return false;
        if (!surface.renameTo(new File(d, "Shallow.json"))) {
            if (shallow != null) medium.renameTo(shallow);              // back to the old names
            return false;
        }
        File root = charts.file().getAbsoluteFile().getParentFile(), up = d.getAbsoluteFile().getParentFile();
        String rel = up.equals(root) ? d.getName()                      // the tutorial, beside Charts
            : up.equals(charts.file().getAbsoluteFile()) ? charts.name() + "/" + d.getName() : null;
        FileHandle list = new FileHandle(new File(root, "assets.txt"));
        if (rel != null && root != null && list.exists()) {
            try {
                String text = list.readString("UTF-8"), q = "(?mi)^" + java.util.regex.Pattern.quote(rel + "/");
                if (java.util.regex.Pattern.compile(q + "Surface\\.json$").matcher(text).find()) {
                    list.writeString(text
                        .replaceAll(q + "Shallow\\.json$", java.util.regex.Matcher.quoteReplacement(rel + "/Medium.json"))
                        .replaceAll(q + "Surface\\.json$", java.util.regex.Matcher.quoteReplacement(rel + "/Shallow.json")),
                        false, "UTF-8");
                }
            } catch (RuntimeException e) {
                Gdx.app.error("Charter", "assets.txt: " + e.getMessage());
            }
        }
        Gdx.app.log("Charter", "chart files renamed: " + dir.name());
        return true;
    }

    private Song readSong(FileHandle dir) {
        Song s = new Song(dir);
        s.audio = ChartMapper.findMusic(dir);
        s.jacket = ChartMapper.findJacket(dir);
        FileHandle[] files = dir.list();
        ArrayList<String> names = new ArrayList<>();
        for (FileHandle f : files) names.add(f.name());
        s.oldNames = Difficulty.oldNames(names) && !renameCharts(dir);
        if (!s.oldNames) files = dir.list();
        for (Difficulty d : Difficulty.values()) {
            for (FileHandle f : files) {
                if (!f.isDirectory() && f.name().equalsIgnoreCase(d.fileName(s.oldNames))) s.diffs[d.ordinal()].file = f;
            }
        }
        // song-level text comes from Shallow, else Surface, else the first chart (CHART_FORMAT §1)
        Difficulty[] pref = {Difficulty.SHALLOW, Difficulty.SURFACE, Difficulty.DEEP, Difficulty.ABYSS};
        boolean named = false;
        for (Difficulty d : pref) {
            Diff df = s.diffs[d.ordinal()];
            if (!df.exists()) continue;
            try {
                ChartHeaderReader.Header h = ChartHeaderReader.read(df.file);
                df.constant = h.chartConstant != null ? h.chartConstant : "";
                SongInfo si = h.format >= 5 ? SongInfo.besides(df.file) : null;   // v5: the song values are there
                if (si != null) {
                    h.title = si.title;
                    h.artist = si.artist;
                    h.illustrator = si.illustrator;
                    h.bpm = Float.isNaN(si.bpm) ? 0f : si.bpm;
                }
                if (!named) {
                    s.title = h.title != null ? h.title : "";
                    s.artist = h.artist != null ? h.artist : "";
                    s.illustrator = h.illustrator != null ? h.illustrator : "";
                    s.bpm = h.bpm;
                    named = true;
                }
            } catch (Exception e) {
                df.error = e.getMessage();
            }
        }
        if (s.audio != null) {
            if (Mp3Info.isMp3(s.audio)) {
                s.durationMs = Mp3Info.durationMs(s.audio);
            } else if (s.audio.extension().equalsIgnoreCase("wav")) {
                s.durationMs = WavDurationUtil.getDurationMillisSafe(s.audio);
            }
        }
        com.badlogic.gdx.utils.JsonValue ie = indexEntry(s.id);
        if (ie != null) {
            s.indexStartMs = ie.getFloat("previewStartMs", Float.NaN);
            s.indexEndMs = ie.getFloat("previewEndMs", Float.NaN);
        }
        FileHandle sj = dir.child(SONG_JSON);
        if (sj.exists()) {
            try {
                com.badlogic.gdx.utils.JsonValue v = new com.badlogic.gdx.utils.JsonReader().parse(sj);
                s.previewStartMs = v.getFloat("previewStartMs", Float.NaN);
                s.previewEndMs = v.getFloat("previewEndMs", Float.NaN);
                s.previewStartBeat = v.getFloat(START_BEAT, Float.NaN);
                s.previewEndBeat = v.getFloat(END_BEAT, Float.NaN);
                s.jacketWide = box(v.get(WIDE_KEY));
                s.jacketRound = box(v.get(ROUND_KEY));
            } catch (Exception e) {
                Gdx.app.error("Charter", s.id + "/" + SONG_JSON + ": " + e.getMessage());
            }
        }
        return s;
    }

    /**
     * @return the song's object entry in a {@code "songs"} list of {@code Charts/index.json} (often a plain name), or
     * null
     */
    private com.badlogic.gdx.utils.JsonValue indexEntry(String id) {
        FileHandle index = charts.child("index.json");
        if (!index.exists()) return null;
        try {
            return entry(new com.badlogic.gdx.utils.JsonReader().parse(index), id);
        } catch (Exception e) {
            return null;
        }
    }

    private static com.badlogic.gdx.utils.JsonValue entry(com.badlogic.gdx.utils.JsonValue v, String id) {
        for (com.badlogic.gdx.utils.JsonValue c = v.child; c != null; c = c.next) {
            if (c.isObject() && "songs".equals(v.name) && id.equals(c.getString("id", null))) return c;
            com.badlogic.gdx.utils.JsonValue r = c.isObject() || c.isArray() ? entry(c, id) : null;
            if (r != null) return r;
        }
        return null;
    }

    /** @return a jacket box {x, y, w, h} inside the picture (fractions), or null when missing or out of range */
    private static float[] box(com.badlogic.gdx.utils.JsonValue v) {
        if (v == null || !v.isObject()) return null;
        float[] b = {v.getFloat("x", Float.NaN), v.getFloat("y", Float.NaN), v.getFloat("w", Float.NaN),
            v.getFloat("h", Float.NaN)};
        boolean ok = b[0] >= 0f && b[1] >= 0f && b[2] > 0f && b[3] > 0f && b[0] + b[2] <= 1.001f
            && b[1] + b[3] <= 1.001f;
        return ok ? b : null;
    }

    /** The song folder's own settings for the game (preview window, jacket boxes …); other keys in it are kept. */
    static final String SONG_JSON = "song.json";
    /** song.json keys of the jacket's 16:9 area and of the square around its circle. */
    static final String WIDE_KEY = "jacket16x9", ROUND_KEY = "jacketCircle";
    /** song.json keys of the preview window in beats (the Charter's own; the game reads the ms keys). */
    static final String START_BEAT = "previewStartBeat", END_BEAT = "previewEndBeat";

    /**
     * Sets the song's preview window in {@code song.json} by beat (Kelvin 2026-10-21): the beats for the Charter,
     * the ms of music time from {@code beatToMs} for the game; NaN removes that end's keys (the game's default).
     * @return false when the file could not be written
     */
    public boolean setPreview(Song s, float startBeat, float endBeat, DoubleUnaryOperator beatToMs) {
        float a = beats(startBeat), b = beats(endBeat);
        float am = Float.isNaN(a) ? Float.NaN : Math.round(beatToMs.applyAsDouble(a));
        float bm = Float.isNaN(b) ? Float.NaN : Math.round(beatToMs.applyAsDouble(b));
        Map<String, Object> m = new HashMap<>();
        m.put(START_BEAT, number(a));
        m.put(END_BEAT, number(b));
        m.put("previewStartMs", number(am));
        m.put("previewEndMs", number(bm));
        if (!patchSongJson(s, m)) return false;
        s.previewStartBeat = a;
        s.previewEndBeat = b;
        s.previewStartMs = am;
        s.previewEndMs = bm;
        return true;
    }

    /** @return {@code b} to a thousandth of a beat (NaN stays NaN). */
    private static float beats(float b) {
        return Float.isNaN(b) ? b : Math.round(b * 1000f) / 1000f;
    }

    /** @return the song.json value: a whole number as an int, NaN as {@link #REMOVE}. */
    private static Object number(float v) {
        return Float.isNaN(v) ? REMOVE : v == Math.rint(v) ? (Object) (int) v : (Object) v;
    }

    /**
     * Sets the jacket's 16:9 area and the square around its circle in {@code song.json} ({x, y, w, h}
     * fractions of the picture; null removes the key: the game's centre crop). @return false when the file
     * could not be written
     */
    public boolean setJacketBoxes(Song s, float[] wide, float[] round) {
        Map<String, Object> m = new HashMap<>();
        m.put(WIDE_KEY, wide == null ? REMOVE : wide);
        m.put(ROUND_KEY, round == null ? REMOVE : round);
        if (!patchSongJson(s, m)) return false;
        s.jacketWide = wide;
        s.jacketRound = round;
        return true;
    }

    /** Writes the given top-level keys of the song's {@code song.json} (created when missing). */
    private boolean patchSongJson(Song s, Map<String, Object> m) {
        FileHandle f = s.dir.child(SONG_JSON);
        try {
            String out = patchTopLevel(f.exists() ? f.readString("UTF-8") : "{}", m);
            if (out != null) f.writeString(out, false, "UTF-8");
            return true;
        } catch (Exception e) {
            Gdx.app.error("Charter", "could not write " + f.path() + ": " + e.getMessage());
            return false;
        }
    }

    private static void countNotes(Song s) {
        boolean first = true;
        for (Diff d : s.diffs) {
            if (!d.exists()) continue;
            try {
                byte[] bytes = d.file.readBytes();
                if (ChartFormatV7.isV7(bytes)) {
                    d.notes = ChartFormatV7.read(new java.io.ByteArrayInputStream(bytes)).keys.size();
                    SongInfo si = first ? SongInfo.besides(d.file) : null;
                    if (si != null) {
                        s.offsetMs = si.offsetMs;
                        if (s.bpm <= 0f && si.bpm > 0f) s.bpm = si.bpm;
                    }
                    first = false;
                    continue;
                }
                Chart c = ChartMapper.read(d.file);
                d.notes = c.noteCount();
                if (first) {
                    s.offsetMs = c.offsetMs();
                    if (s.bpm <= 0f) s.bpm = c.bpm();
                    first = false;
                }
            } catch (Exception e) {
                d.error = e.getMessage();
                d.notes = 0;
            }
        }
    }

    // ================================================================== changes

    private static String stamp() {
        return new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new Date());
    }

    /**
     * Copies a file the first time it is about to be overwritten in this session.
     * @return whether a copy was made (the file then counts one more 迭代, {@link Doc#iterations})
     */
    public boolean backup(Song s, FileHandle f) {
        if (f == null || !f.exists() || !backedUp.add(f.path())) return false;
        try {
            f.copyTo(home.child("backups").child(s.id).child(f.nameWithoutExtension() + "-" + stamp() + ".json"));
            return true;
        } catch (Exception e) {
            Gdx.app.error("Charter", "backup failed: " + e.getMessage());
            return false;
        }
    }

    /** A chart file read for editing: the document and a note about how it was read (null = as it was). */
    public static final class Read {
        public final Doc doc;
        public final String note;

        Read(Doc doc, String note) {
            this.doc = doc;
            this.note = note;
        }
    }

    /**
     * Reads a difficulty file as a v7 document (Kelvin 2026-11-09 18:44): a v7 file as it is, with the song values
     * of its {@code song.json}; an older one converted to v7 by the game ({@link #convertOld}).
     */
    public Read readDoc(FileHandle f, Difficulty d) throws Exception {
        byte[] bytes = f.readBytes();
        SongInfo song = SongInfo.besides(f);
        if (ChartFormatV7.isV7(bytes)) {
            return new Read(Doc.of(ChartFormatV7.read(new java.io.ByteArrayInputStream(bytes)), song, d.label()), null);
        }
        return convertOld(f, bytes, song, d);
    }

    /**
     * An older chart (format 1 to 6) as a v7 document: the game's converter does the work, so the editor opens it
     * as the game plays it. Saving writes it as v7.
     */
    private Read convertOld(FileHandle f, byte[] bytes, SongInfo song, Difficulty d) throws Exception {
        Chart old = ChartMapper.readOld(f);
        Doc doc = Doc.of(ChartV7Convert.convert(old), song, d.label());
        if (song == null) {                                         // formats 1–4 carry their own song values
            doc.title = nz(old.title());
            doc.artist = nz(old.artist());
            doc.illustrator = nz(old.illustrator());
            doc.offsetMs = old.offsetMs();
            doc.bpms.clear();
            float[] b = old.bpmListBeats(), v = old.bpmListValues();
            if (b != null && v != null) {
                for (int i = 0; i < Math.min(b.length, v.length); i++) doc.bpms.add(new Doc.Tempo(b[i], v[i]));
            }
            doc.sortBpms();
            if (doc.bpms.isEmpty() || doc.bpms.get(0).beat > 0f) doc.bpms.add(0, new Doc.Tempo(0f, old.bpm()));
        }
        if (doc.charter.isEmpty() && old.charter() != null) doc.charter = old.charter();
        return new Read(doc, "这张谱是旧格式 v" + old.sourceFormat() + "，已按游戏的方法转成谱面版本 2.0（海面等 2.0 没有的东西去掉了）。"
            + "保存时写成 2.0，原文件先备份。");
    }

    private static String nz(String s) {
        return s != null ? s : "";
    }

    /**
     * Writes a chart as v7 and its song values (title, artist, illustrator, tempo, offset) into the song's
     * {@code song.json}, keeping that file's other keys.
     */
    public void writeChart(Song s, FileHandle f, Doc doc) {
        FileHandle sj = s.dir.child(SONG_JSON);
        try {
            String old = sj.exists() ? sj.readString("UTF-8") : null;
            String out = doc.songInfo().mergeInto(old);
            if (!out.equals(old)) sj.writeString(out, false, "UTF-8");
        } catch (java.io.IOException e) {
            Gdx.app.error("Charter", sj.path() + ": " + e.getMessage());
        }
        f.writeString(doc.toJson(), false, "UTF-8");
    }

    // ================================================================== 保存的曲线

    /**
     * 保存按键轨迹曲线（Kelvin 2026-11-09 05:27「记得加“保存按键轨迹曲线”」）：存在 Charter 文件夹的 move-curves.json，所有谱都能套用。
     * A saved 移动 curve: its name and its events, beats from the key's judgement (0 = 判定).
     */
    public static final class Curve {
        public final String name;
        /** Each segment: start and end (beats from the judge beat), from, to, exponent (older files: more after). */
        public final ArrayList<float[]> events = new ArrayList<>();

        Curve(String name) {
            this.name = name;
        }
    }

    /** Not curves.json: older Charters kept their 动画曲线 templates there. */
    private static final String CURVES = "move-curves.json";
    private ArrayList<Curve> curves;

    /** @return the saved 移动 curves, in the order saved (move-curves.json, read once) */
    public List<Curve> curves() {
        if (curves != null) return curves;
        curves = new ArrayList<>();
        FileHandle f = home.child(CURVES);
        if (!f.exists()) return curves;
        try {
            com.badlogic.gdx.utils.JsonValue all = new com.badlogic.gdx.utils.JsonReader().parse(f.readString("UTF-8"))
                .get("curves");
            for (com.badlogic.gdx.utils.JsonValue c = all != null ? all.child : null; c != null; c = c.next) {
                Curve k = new Curve(c.getString("name", "曲线"));
                com.badlogic.gdx.utils.JsonValue ev = c.get("events");
                for (com.badlogic.gdx.utils.JsonValue e = ev != null ? ev.child : null; e != null; e = e.next) {
                    float[] v = e.asFloatArray();
                    if (v.length >= 5) k.events.add(v);
                }
                if (!k.events.isEmpty()) curves.add(k);
            }
        } catch (Exception e) {
            Gdx.app.error("Charter", CURVES + ": " + e.getMessage());
        }
        return curves;
    }

    /**
     * Saves a key's h segments {@code move} as curve {@code name}, beats from {@code zero} (its judge beat; one of that
     * name is replaced).
     *
     * @return false when move-curves.json could not be written
     */
    public boolean saveCurve(String name, List<Seg> move, float zero) {
        Curve k = new Curve(name);
        for (Seg e : move) k.events.add(new float[] {e.ti - zero, e.tf - zero, e.xi, e.xf, e.e});
        List<Curve> all = curves();
        all.removeIf(c -> c.name.equals(name));
        all.add(k);
        StringBuilder b = new StringBuilder("{ \"curves\": [\n");
        for (int i = 0; i < all.size(); i++) {
            Curve c = all.get(i);
            b.append(i > 0 ? ",\n" : "").append("  { \"name\": ").append(Doc.str(c.name)).append(", \"events\": [");
            for (int j = 0; j < c.events.size(); j++) {
                float[] v = c.events.get(j);
                b.append(j > 0 ? ", [" : "[");
                for (int q = 0; q < v.length; q++) b.append(q > 0 ? ", " : "").append(Doc.num(v[q]));
                b.append(']');
            }
            b.append("] }");
        }
        b.append("\n] }\n");
        try {
            home.child(CURVES).writeString(b.toString(), false, "UTF-8");
            return true;
        } catch (Exception e) {
            Gdx.app.error("Charter", CURVES + ": " + e.getMessage());
            return false;
        }
    }

    /** Writes a crash-safety copy of unsaved work (backups/autosave). */
    public void autosave(Song s, Difficulty d, String json) {
        try {
            home.child("backups").child("autosave").child(s.id + "-" + d.label() + ".json")
                .writeString(json, false, "UTF-8");
        } catch (Exception e) {
            Gdx.app.error("Charter", "autosave failed: " + e.getMessage());
        }
    }

    /** Copies an audio file into a new song folder named {@code id}. @return the new song */
    public Song importAudio(File src, String id) {
        String clean = id.replaceAll("[\\\\/:*?\"<>|]", "").trim();
        if (clean.isEmpty()) clean = "New Song";
        String name = clean;
        for (int i = 2; charts.child(name).exists(); i++) name = clean + " " + i;
        FileHandle dir = charts.child(name);
        dir.mkdirs();
        String ext = src.getName().contains(".")
            ? src.getName().substring(src.getName().lastIndexOf('.') + 1).toLowerCase(Locale.ROOT) : "wav";
        Gdx.files.absolute(src.getAbsolutePath()).copyTo(dir.child(name + "." + ext));
        Song s = readSong(dir);
        s.title = name;
        int k = 0;                                          // the singles come before 练习谱 and the tutorial
        while (k < songs.size() && !songs.get(k).practice && !songs.get(k).tutorial) k++;
        songs.add(k, s);
        songs.subList(0, k + 1).sort((a, b) -> a.id.compareToIgnoreCase(b.id));
        return s;
    }

    /** Creates a difficulty file: blank (the song's tempo from song.json), or a copy of {@code from}. */
    public void createDiff(Song s, Difficulty d, Difficulty from) throws Exception {
        Doc doc;
        if (from != null && s.diffs[from.ordinal()].exists()) {
            doc = readDoc(s.diffs[from.ordinal()].file, from).doc;
            doc.iterations = 0;                                  // a new chart starts its own count
        } else {
            doc = Doc.blank(s.name(), s.artist, s.illustrator, d.label(), s.bpm > 0f ? s.bpm : 120f, s.offsetMs);
            SongInfo si = SongInfo.in(s.dir);                    // the song's shared tempo, if any
            if (si != null) {
                Doc t = Doc.of(new Chart7(), si, d.label());
                doc.bpms.clear();
                doc.bpms.addAll(t.bpms);
                doc.offsetMs = t.offsetMs;
                if (!t.title.isEmpty()) doc.title = t.title;
                doc.artist = t.artist;
                doc.illustrator = t.illustrator;
            }
            doc.constant = blankConstant(d);
        }
        doc.level = d.label();
        FileHandle f = s.dir.child(d.fileName(s.oldNames));
        writeChart(s, f, doc);
        s.diffs[d.ordinal()].file = f;
        s.diffs[d.ordinal()].constant = doc.constant;
        s.diffs[d.ordinal()].notes = doc.keys.size();
    }

    /** 定数 of a new blank chart: Surface 1, Shallow 2, Deep 3 (Kelvin 2026-10-22), Abyss the old default 1. */
    static String blankConstant(Difficulty d) {
        switch (d) {
            case SURFACE: return "1";
            case SHALLOW: return "2";
            case DEEP: return "3";
            default: return "1";
        }
    }

    /** Moves the song folder to trash/ (not deleted, so it can be restored). */
    public void trash(Song s) {
        s.disposeJacket();
        FileHandle dst = home.child("trash").child(s.id + "-" + stamp());
        dst.parent().mkdirs();
        File from = s.dir.file(), to = dst.file();
        if (!from.renameTo(to)) {
            s.dir.copyTo(dst.parent());
            dst.parent().child(s.id).moveTo(dst);
            s.dir.deleteDirectory();
        }
        songs.remove(s);
    }

    /** Replaces the jacket with a copy of {@code src} named jacket.<ext>; older jacket.* files go to trash/. */
    public void setJacket(Song s, File src) {
        String ext = src.getName().substring(src.getName().lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        byte[] data = Gdx.files.absolute(src.getAbsolutePath()).readBytes(); // src may be the old jacket itself
        trashJackets(s);
        FileHandle dst = s.dir.child("jacket." + ext);
        dst.writeBytes(data, false);
        s.disposeJacket();
        s.jacket = dst;
    }

    /** Saves {@code px} (cut by the crop dialog) as the song's jacket.png; older jacket.* files go to trash/. */
    public void setJacket(Song s, Pixmap px) {
        trashJackets(s);
        FileHandle dst = s.dir.child("jacket.png");
        PixmapIO.writePNG(dst, px);
        s.disposeJacket();
        s.jacket = dst;
    }

    private void trashJackets(Song s) {
        for (FileHandle f : s.dir.list()) {
            if (f.nameWithoutExtension().equalsIgnoreCase("jacket")) {
                f.moveTo(home.child("trash").child(s.id + "-jacket-" + stamp() + "." + f.extension()));
            }
        }
    }

    /**
     * Sets the song-level texts (title, artist, illustrator) and the tempo ({@code bpm},
     * {@code bpmList}: all four difficulties share it, Kelvin 2026-10-13) in the song's other difficulty files that
     * are still in an old format, changing nothing else in them (token-by-token copy); v5 files read them from
     * {@code song.json} ({@link #writeChart}).
     */
    public void shareMeta(Song s, Difficulty except, String title, String artist, String illustrator,
            List<Doc.Tempo> bpms) {
        Map<String, Object> m = new HashMap<>();
        m.put("title", title);
        m.put("artist", artist);
        m.put("illustrator", illustrator);
        m.put("bpm", bpms.get(0).bpm);
        if (bpms.size() >= 2) {
            float[][] list = new float[bpms.size()][];
            for (int i = 0; i < list.length; i++) list[i] = new float[]{bpms.get(i).beat, bpms.get(i).bpm};
            m.put("bpmList", list);
        } else {
            m.put("bpmList", REMOVE);
        }
        for (Diff d : s.diffs) {
            if (d.d == except || !d.exists()) continue;
            try {
                if (ChartHeaderReader.read(d.file).format >= 5) continue;   // a v5 chart takes them from song.json
                String text = d.file.readString("UTF-8");
                String out = patchTopLevel(text, m);
                if (out != null && !out.equals(text)) {
                    if (backup(s, d.file)) {
                        out = patchTopLevel(out, java.util.Collections.<String,
                            Object>singletonMap("iterations", Doc.iterationsIn(text) + 1));
                    }
                    d.file.writeString(out, false, "UTF-8");
                }
            } catch (Exception e) {
                Gdx.app.error("Charter", "could not update " + d.file.name() + ": " + e.getMessage());
            }
        }
        s.title = title;
        s.artist = artist;
        s.illustrator = illustrator;
        s.bpm = bpms.get(0).bpm;
    }

    /** A {@link #patchTopLevel} value that removes the key. */
    static final Object REMOVE = new Object();

    /**
     * @return {@code json} with the given top-level keys set to a String, a Number, a float[][]
     *         of {beat, bpm} pairs (written as bpmList objects), a float[4] box (an {x, y, w, h}
     *         object), or removed ({@link #REMOVE});
     *         null when nothing changes
     */
    static String patchTopLevel(String json, Map<String, Object> values) throws Exception {
        JsonFactory f = new JsonFactory();
        boolean differs = false;
        try (JsonParser p = f.createParser(json)) {
            int depth = 0;
            HashSet<String> seen = new HashSet<>();
            JsonToken t;
            while ((t = p.nextToken()) != null) {
                if (t == JsonToken.START_OBJECT || t == JsonToken.START_ARRAY) depth++;
                else if (t == JsonToken.END_OBJECT || t == JsonToken.END_ARRAY) depth--;
                else if (t == JsonToken.FIELD_NAME && depth == 1 && values.containsKey(p.getCurrentName())) {
                    String k = p.getCurrentName();
                    seen.add(k);
                    p.nextToken();
                    if (!same(p, values.get(k))) differs = true;
                    p.skipChildren();
                }
            }
            for (Map.Entry<String, Object> e : values.entrySet()) {
                if (!seen.contains(e.getKey()) && e.getValue() != REMOVE) differs = true;
            }
        }
        if (!differs) return null;
        StringWriter w = new StringWriter(json.length() + 64);
        try (JsonParser p = f.createParser(json); JsonGenerator g = f.createGenerator(w)) {
            g.useDefaultPrettyPrinter();
            int depth = 0;
            HashSet<String> written = new HashSet<>();
            JsonToken t;
            while ((t = p.nextToken()) != null) {
                if (t == JsonToken.FIELD_NAME && depth == 1 && values.containsKey(p.getCurrentName())) {
                    String k = p.getCurrentName();
                    write(g, k, values.get(k));
                    written.add(k);
                    p.nextToken();
                    p.skipChildren();
                    continue;
                }
                if (t == JsonToken.END_OBJECT && depth == 1) {
                    for (Map.Entry<String, Object> e : values.entrySet()) {
                        if (!written.contains(e.getKey())) write(g, e.getKey(), e.getValue());
                    }
                }
                if (t == JsonToken.START_OBJECT || t == JsonToken.START_ARRAY) depth++;
                else if (t == JsonToken.END_OBJECT || t == JsonToken.END_ARRAY) depth--;
                g.copyCurrentEvent(p);
            }
        }
        return w.toString() + "\n";
    }

    /** @return whether the value at the parser equals {@code v}; a bpmList array is read to its end. */
    private static boolean same(JsonParser p, Object v) throws Exception {
        JsonToken t = p.currentToken();
        if (v == REMOVE) return false;
        if (v instanceof String) return v.equals(p.getValueAsString());
        if (v instanceof Number) return t.isNumeric()
            && Math.abs(p.getFloatValue() - ((Number) v).floatValue()) < 1e-4f;
        if (v instanceof float[]) {
            if (t != JsonToken.START_OBJECT) return false;
            float[] want = (float[]) v, got = {Float.NaN, Float.NaN, Float.NaN, Float.NaN};
            while (p.nextToken() == JsonToken.FIELD_NAME) {
                int i = "xywh".indexOf(p.getCurrentName());
                JsonToken vt = p.nextToken();
                if (vt.isNumeric() && p.getCurrentName().length() == 1 && i >= 0) got[i] = p.getFloatValue();
                else p.skipChildren();
            }
            for (int i = 0; i < 4; i++) if (!(Math.abs(got[i] - want[i]) < 1e-5f)) return false;
            return true;
        }
        if (t != JsonToken.START_ARRAY) return false;
        ArrayList<float[]> got = new ArrayList<>();
        while (p.nextToken() != JsonToken.END_ARRAY) {
            if (p.currentToken() != JsonToken.START_OBJECT) {
                p.skipChildren();
                got.add(null);
                continue;
            }
            float[] e = {Float.NaN, Float.NaN};
            while (p.nextToken() == JsonToken.FIELD_NAME) {
                String n = p.getCurrentName();
                JsonToken vt = p.nextToken();
                if (vt.isNumeric() && "beat".equals(n)) e[0] = p.getFloatValue();
                else if (vt.isNumeric() && "bpm".equals(n)) e[1] = p.getFloatValue();
                else p.skipChildren();
            }
            got.add(e);
        }
        float[][] want = (float[][]) v;
        if (got.size() != want.length) return false;
        for (int i = 0; i < want.length; i++) {
            float[] e = got.get(i);
            if (e == null || !(Math.abs(e[0] - want[i][0]) < 1e-4f)
                || !(Math.abs(e[1] - want[i][1]) < 1e-4f)) return false;
        }
        return true;
    }

    private static void write(JsonGenerator g, String k, Object v) throws Exception {
        if (v == REMOVE) return;
        if (v instanceof String) {
            g.writeStringField(k, (String) v);
        } else if (v instanceof Integer) {
            g.writeNumberField(k, (Integer) v);
        } else if (v instanceof Number) {
            g.writeNumberField(k, ((Number) v).floatValue());
        } else if (v instanceof float[]) {
            float[] b = (float[]) v;
            g.writeObjectFieldStart(k);
            g.writeNumberField("x", b[0]);
            g.writeNumberField("y", b[1]);
            g.writeNumberField("w", b[2]);
            g.writeNumberField("h", b[3]);
            g.writeEndObject();
        } else {
            g.writeArrayFieldStart(k);
            for (float[] e : (float[][]) v) {
                g.writeStartObject();
                g.writeNumberField("beat", e[0]);
                g.writeNumberField("bpm", e[1]);
                g.writeEndObject();
            }
            g.writeEndArray();
        }
    }
}
