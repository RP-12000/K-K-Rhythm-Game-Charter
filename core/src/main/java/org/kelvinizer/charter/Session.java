package org.kelvinizer.charter;

import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.Gdx;
import org.kelvinizer.elements.Chart;
import org.kelvinizer.elements.Chart7;
import org.kelvinizer.elements.ChartFormatV7;
import org.kelvinizer.elements.ChartV7Runtime;
import org.kelvinizer.library.ChartHeaderReader;
import org.kelvinizer.library.Difficulty;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashSet;

/**
 * 一次编辑：正在改的谱面、撤销/重做、选中的键、轨道和水幕水帘，以及保存（第一次覆盖前先备份）。
 * One editing session of one chart file: the document, undo / redo, the selection (keys by uid, a lane by id, a veil
 * or curtain by uid), the chart the preview plays ({@link #chart}, the doc as the game's model after every change)
 * and saving, with a backup before the file is first overwritten.
 */
public final class Session {
    private static final int UNDO_CAP = 200;
    /** While dragging, rebuilds wait this long (ms) after the previous one. */
    private static final long DRAG_BUILD_MS = 60L;

    public final Library lib;
    public final Library.Song song;
    public final Difficulty diff;
    public final FileHandle file;
    public Doc doc;
    /** What the preview plays: a copy of the doc as the game's model, rebuilt after every change. */
    public Chart7 chart;
    /** The chart the game plays, built from {@link #chart} by the game ({@link ChartV7Runtime}); null if it failed. */
    public Chart runtime;
    /** A note about how the file was read (an older format converted), or null. */
    public String loadNote;
    public boolean dirty;
    /** Bumped on every change, so screens can cache what they derive from the doc. */
    public int version;
    /** Selected keys by uid, in selection order. */
    public final LinkedHashSet<Integer> sel = new LinkedHashSet<>();
    /** No lane selected ({@link #laneSel}). */
    public static final int NO_LANE = Integer.MIN_VALUE;
    /** Selected lane id ({@link #NO_LANE} = none). */
    public int laneSel = NO_LANE;
    /** New keys go on the sky face (Tab), else on the base. */
    public boolean placeSky;
    /** Selected veil or curtain by uid (0 = none). */
    public int volSel;
    /** 组 (chart 2.1): the selected group by id (0 = none), and the group entered (进组: its things picked one by one). */
    public int groupSel, groupIn;
    /** What 成组 (Ctrl+G) takes: lanes and groups picked together (Shift + click), by id. */
    public final LinkedHashSet<Integer> pickLanes = new LinkedHashSet<>(), pickGroups = new LinkedHashSet<>();

    private final ArrayDeque<Doc> undo = new ArrayDeque<>(), redo = new ArrayDeque<>();
    private boolean buildPending;
    private long lastBuild;
    /** The file's modification time when it was read, saved or kept, to notice edits made outside the editor. */
    private long diskStamp;

    private Session(Library lib, Library.Song song, Difficulty diff, FileHandle file, Doc doc) {
        this.lib = lib;
        this.song = song;
        this.diff = diff;
        this.file = file;
        this.doc = doc;
    }

    /** Opens one difficulty of a song; older files arrive converted to format v7 ({@link Library#readDoc}). */
    public static Session open(Library lib, Library.Song song, Difficulty d) throws Exception {
        FileHandle f = song.diffs[d.ordinal()].file;
        ChartHeaderReader.Header was = header(f);
        Library.Read r = lib.readDoc(f, d);
        Session s = new Session(lib, song, d, f, r.doc);
        s.diskStamp = f.lastModified();
        s.loadNote = r.note;
        s.rebuild();
        s.dirty = r.note != null;
        if (needsRefresh(was)) s.refresh(was, r.note);
        return s;
    }

    /**
     * @return whether a chart file is saved at once as the current version when it is first opened: anything but a
     *         {@code "format": "2.1"} file (Kelvin 2026-11-11 04:14 「文件里的format也改成2.0…所有对应位置全部修改」), so also a
     *         2.1 file whose format is still a number (files before 7.239) and a {@code "2.2"} one, read as 2.1
     *         (2026-11-12 06:06「2.2文件记得读取的时候全部把版本调回2.1」); a newer version is left as it is
     */
    static boolean needsRefresh(ChartHeaderReader.Header h) {
        return h != null && !h.current && h.format <= ChartFormatV7.WRITE_FORMAT;
    }

    /** @return the chart file's header (format, chart version); null when it cannot be read */
    static ChartHeaderReader.Header header(FileHandle f) {
        try {
            return ChartHeaderReader.read(f);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 第一次打开自动刷新成最新版本 (Kelvin 2026-11-11 02:27「就是说别人换新编辑器的时候，第一次打开自动刷新成最新版本」): a file
     * {@link #needsRefresh} picks is saved at once as the current version, after the usual backup.
     */
    private void refresh(ChartHeaderReader.Header was, String converted) {
        String ver = Library.version();
        try {
            save();
        } catch (RuntimeException e) {
            Gdx.app.error("Charter", "could not refresh " + file.path() + ": " + e.getMessage());
            if (converted != null) loadNote = converted + "保存时写成 " + ver + "，原文件先备份。";
            return;
        }
        String backup = "原文件备份在 Charter 的 backups 文件夹。", saved = "第一次打开已自动存成 " + ver + "，" + backup;
        if (converted != null) loadNote = converted + saved;
        else if (was.format < ChartFormatV7.WRITE_FORMAT) loadNote = "这张谱是谱面版本 " + was.version + "，" + saved;
        else loadNote = "这张谱文件里的 format 不是 \"" + ver + "\"，第一次打开已自动改写成 \"format\": \"" + ver + "\"，" + backup;
    }

    // ================================================================== outside edits

    /** @return whether the chart file changed outside the editor since it was read, saved, reloaded or kept. */
    public boolean changedOnDisk() {
        return file.exists() && file.lastModified() != diskStamp;
    }

    /** Keeps the editor's version after an outside change; asks again only after the next one. */
    public void keepMine() {
        diskStamp = file.lastModified();
    }

    /**
     * Reads the chart file again, e.g. after another tool changed it (Kelvin 2026-10-13). The editor's version goes
     * to the autosave first and stays one undo step back.
     */
    public void reload() throws Exception {
        Library.Read r = lib.readDoc(file, diff);
        lib.autosave(song, diff, doc.toJson());
        mark();
        doc = r.doc;
        diskStamp = file.lastModified();
        afterHistory();
        dirty = r.note != null;
    }

    // ================================================================== changes

    /** Call before changing the doc: keeps a copy for undo. */
    public void mark() {
        undo.push(doc.copy());
        if (undo.size() > UNDO_CAP) undo.removeLast();
        redo.clear();
    }

    /** Call after changing the doc; rebuilds the preview (at most every 60 ms while {@code dragging}). */
    public void changed(boolean dragging) {
        dirty = true;
        version++;
        if (dragging && System.currentTimeMillis() - lastBuild < DRAG_BUILD_MS) buildPending = true;
        else rebuild();
    }

    /** Once per frame: runs a rebuild a drag postponed. */
    public void update() {
        if (buildPending && System.currentTimeMillis() - lastBuild >= DRAG_BUILD_MS) rebuild();
    }

    public boolean undo() {
        if (undo.isEmpty()) return false;
        redo.push(doc);
        doc = undo.pop();
        doc.iterations = redo.peek().iterations;               // a count, not an edit: undo keeps it
        afterHistory();
        return true;
    }

    public boolean redo() {
        if (redo.isEmpty()) return false;
        undo.push(doc);
        doc = redo.pop();
        doc.iterations = undo.peek().iterations;
        afterHistory();
        return true;
    }

    private void afterHistory() {
        sel.removeIf(u -> doc.byUid(u) == null);
        if (doc.volByUid(volSel) == null) volSel = 0;
        if (laneSel != NO_LANE && doc.lane(laneSel) == null) laneSel = NO_LANE;
        if (doc.group(groupSel) == null) groupSel = 0;
        if (doc.group(groupIn) == null) groupIn = 0;
        pickLanes.removeIf(id -> doc.lane(id) == null);
        pickGroups.removeIf(id -> doc.group(id) == null);
        dirty = true;
        version++;
        rebuild();
    }

    /** Builds what the preview plays from the doc. */
    public void rebuild() {
        buildPending = false;
        lastBuild = System.currentTimeMillis();
        chart = doc.toChart();
        try {
            runtime = ChartV7Runtime.build(chart, doc.songChart());
        } catch (RuntimeException e) {
            runtime = null;
            Gdx.app.error("Charter", "preview: " + e);
        }
    }

    // ================================================================== time

    public double beatToMs(double beat) {
        return doc.offsetMs + doc.msOf(beat);
    }

    public double msToBeat(double ms) {
        return doc.beatOf(ms - doc.offsetMs);
    }

    // ================================================================== selection

    /** @return the selected keys in time order. */
    public ArrayList<Doc.DKey> selected() {
        ArrayList<Doc.DKey> out = new ArrayList<>();
        if (sel.isEmpty()) return out;
        for (Doc.DKey k : doc.keys) if (sel.contains(k.uid)) out.add(k);
        return out;
    }

    /** @return the one selected key, or null when none or several are selected. */
    public Doc.DKey single() {
        if (sel.size() != 1) return null;
        return doc.byUid(sel.iterator().next());
    }

    public void select(Doc.DKey k, boolean add) {
        if (!add) sel.clear();
        if (k != null) {
            if (add && sel.contains(k.uid)) sel.remove(k.uid);
            else sel.add(k.uid);
        }
    }

    // ================================================================== saving

    /**
     * Writes the chart (after a backup of the old file) and the song values ({@code song.json}). 迭代次数 never drops
     * below the file's own count and grows by one whenever the old file is backed up.
     */
    public void save() {
        doc.iterations = Math.max(doc.iterations, Doc.iterationsIn(file));
        if (lib.backup(song, file)) doc.iterations++;
        lib.writeChart(song, file, doc);
        diskStamp = file.lastModified();
        lib.shareMeta(song, diff, doc.title, doc.artist, doc.illustrator, doc.bpms);
        // the preview window is set by beat: after a tempo or offset change its ms follow
        if (!Float.isNaN(song.previewStartBeat) || !Float.isNaN(song.previewEndBeat))
            lib.setPreview(song, song.previewStartBeat, song.previewEndBeat, this::beatToMs);
        Library.Diff d = song.diffs[diff.ordinal()];
        d.constant = doc.constant;
        d.notes = doc.keys.size();
        dirty = false;
    }
}
