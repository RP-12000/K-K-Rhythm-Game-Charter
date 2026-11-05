package org.kelvinizer.charter;

import com.badlogic.gdx.files.FileHandle;
import org.kelvinizer.elements.Chart;
import org.kelvinizer.elements.ChartMapper;
import org.kelvinizer.elements.Line;
import org.kelvinizer.elements.Note;
import org.kelvinizer.elements.NoteType;
import org.kelvinizer.elements.Veil;
import org.kelvinizer.library.Difficulty;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 一次编辑：正在改的谱面、撤销/重做、选中的音符和轨道，以及保存（第一次覆盖前先备份）。
 * One editing session of one chart file: the document, undo / redo, the selection, the
 * {@link Chart} the preview plays (built by the game's own loader after every change) and saving,
 * with a backup before the file is first overwritten.
 */
public final class Session {
    /** Beat of the invisible mine that keeps the preview engine running past the last note. */
    public static final float SENTINEL = 100000f;
    private static final int UNDO_CAP = 200;
    /** While dragging, rebuilds wait this long (ms) after the previous one. */
    private static final long DRAG_BUILD_MS = 60L;

    public final Library lib;
    public final Library.Song song;
    public final Difficulty diff;
    public final FileHandle file;
    public Doc doc;
    /** What the preview plays (the doc plus the sentinel); the last good build when the doc is broken. */
    public Chart chart;
    public String buildError;
    /** A note about how the file was read (old format, duplicates removed), or null. */
    public String loadNote;
    public boolean dirty;
    /** Bumped on every change, so screens can cache what they derive from the doc. */
    public int version;
    /** Selected notes by uid, in selection order. */
    public final LinkedHashSet<Integer> sel = new LinkedHashSet<>();
    /** No lane selected ({@link #laneSel}); 0 is a lane id now (an inserted lane). */
    public static final int NO_LANE = Integer.MIN_VALUE;
    /** Selected lane id ({@link #NO_LANE} = none). */
    public int laneSel = NO_LANE;
    /** Lanes picked together (Ctrl / Shift on the lane chips) for a partial scale. */
    public final java.util.TreeSet<Integer> lanePick = new java.util.TreeSet<>();
    /** The line new notes go to. */
    public Line placeLine = Line.LOWER;
    /** Selected veil by uid (0 = none). */
    public int veilSel;

    private final ArrayDeque<Doc> undo = new ArrayDeque<>(), redo = new ArrayDeque<>();
    private final HashMap<Integer, Note> map = new HashMap<>();
    private final HashMap<Integer, Veil> veilMap = new HashMap<>();
    /** {@link #chart} without the sentinel ({@link #liveChart}) and the build it was made for. */
    private Chart liveChart, liveOf;
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

    /** Opens one difficulty of a song; older files arrive converted by the game's loader. */
    public static Session open(Library lib, Library.Song song, Difficulty d) throws Exception {
        FileHandle f = song.diffs[d.ordinal()].file;
        Chart c = ChartMapper.read(f, null, null);
        Session s = new Session(lib, song, d, f, Doc.fromChart(c));
        s.diskStamp = f.lastModified();
        Doc.readRelFlags(s.doc, f);
        StringBuilder note = new StringBuilder();
        if (c.sourceFormat() < 4) {
            note.append("旧格式 v").append(c.sourceFormat()).append(" 已换算成现在的坐标（1920×1200，判定线 1080 / 120），保存后写成 v")
                .append(ChartMapper.FORMAT_VERSION).append("。");
        } else if (c.sourceFormat() < ChartMapper.FORMAT_VERSION) {
            note.append("旧格式 v").append(c.sourceFormat()).append("，保存后写成 v").append(ChartMapper.FORMAT_VERSION)
                .append("（歌名、BPM、offset 写进 song.json）。");
        }
        int lost = c.loadReport().veilsDropped();
        if (lost > 0) note.append("读取时丢掉了 ").append(lost).append(" 块漩涡或水帘（多边形能用的点不到 3 个）。");
        int moved = s.doc.reflow();
        if (moved > 0) note.append("按现在的 BPM 重新对齐了 ").append(moved).append(" 个统一流速的下落，保存后写进文件。");
        s.loadNote = note.length() > 0 ? note.toString() : null;
        s.rebuild();
        s.dirty = moved > 0;
        return s;
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
     * Reads the chart file again, e.g. after another tool changed it (Kelvin 2026-10-13). The editor's
     * version goes to the autosave first and stays one undo step back.
     */
    public void reload() throws Exception {
        Doc d = Doc.fromChart(ChartMapper.read(file, null, null));
        Doc.readRelFlags(d, file);
        int moved = d.reflow();
        lib.autosave(song, diff, doc.toJson());
        mark();
        doc = d;
        diskStamp = file.lastModified();
        afterHistory();
        dirty = moved > 0;
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
        doc.reflow();
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
        if (doc.veilByUid(veilSel) == null) veilSel = 0;
        // unlimited lanes: an undeclared lane is still a lane (default width)
        if (doc.laneCount > 0) {
            if (laneSel != NO_LANE && doc.lane(laneSel) == null) laneSel = NO_LANE;
            lanePick.removeIf(id -> doc.lane(id) == null);
        }
        dirty = true;
        version++;
        rebuild();
    }

    /** Builds the preview chart from the doc with the game's loader. */
    public void rebuild() {
        buildPending = false;
        lastBuild = System.currentTimeMillis();
        try {
            byte[] json = doc.toJson(SENTINEL).getBytes(StandardCharsets.UTF_8);
            chart = ChartMapper.read(new ByteArrayInputStream(json), "editor");
            buildError = null;
            mapNotes();
            mapVeils();
        } catch (Exception e) {
            buildError = e.getMessage() != null ? e.getMessage() : e.toString();
        }
    }

    /**
     * @return what the preview plays in 实机: {@link #chart} without the sentinel, so the game's HUD counts the time
     *         and progress to the last note (built on demand; {@link #chart} itself while the doc does not load)
     */
    public Chart liveChart() {
        if (liveOf != chart) {
            liveOf = chart;
            liveChart = chart;
            try {
                byte[] json = doc.toJson().getBytes(StandardCharsets.UTF_8);
                liveChart = ChartMapper.read(new ByteArrayInputStream(json), "editor");
            } catch (Exception e) {
                // keep the last good build
            }
        }
        return liveChart;
    }

    /** Pairs every doc note with the note the game built from it; stacked notes pair in file order. */
    private void mapNotes() {
        map.clear();
        HashMap<String, ArrayDeque<Note>> byKey = new HashMap<>();
        for (Note n : chart.notes()) {
            if (n.judgement() >= SENTINEL) continue;
            byKey.computeIfAbsent(key(n.type(), n.line(), n.type().inLane() ? n.laneId() : 0, n.judgement()),
                k -> new ArrayDeque<>()).add(n);
        }
        for (Doc.DNote d : doc.notes) {
            ArrayDeque<Note> q = byKey.get(key(d.type, d.line, d.inLane() ? d.lane : 0, d.judgement));
            Note n = q != null ? q.poll() : null;
            if (n != null) map.put(d.uid, n);
        }
    }

    /** Pairs every doc veil and curtain with the one the game built from it (none for a polygon without 3 points). */
    private void mapVeils() {
        veilMap.clear();
        mapVeils(doc.veils, chart.veils());
        mapVeils(doc.curtains, chart.curtains());
    }

    private void mapVeils(List<Doc.DVeil> list, Veil[] built) {
        int k = 0;
        for (Doc.DVeil d : list) {
            if (d.polygon && d.pointCount() < 3) continue;
            if (k < built.length) veilMap.put(d.uid, built[k++]);
        }
    }

    /** @return the game's veil built from {@code d}, or null when the game drops it. */
    public Veil veil(Doc.DVeil d) {
        return veilMap.get(d.uid);
    }

    private static String key(NoteType t, Line l, int lane, float j) {
        return t.ordinal() + "|" + l.ordinal() + "|" + lane + "|" + Float.floatToIntBits(j + 0f);
    }

    /** @return the game's note built from {@code d}, or null when the game drops it (a duplicate). */
    public Note note(Doc.DNote d) {
        return map.get(d.uid);
    }

    // ================================================================== time

    public double beatToMs(double beat) {
        if (chart != null) return chart.beatToMs(beat);
        return doc.offsetMs + beat * 60000.0 / doc.bpms.get(0).bpm;
    }

    public double msToBeat(double ms) {
        if (chart != null) return chart.msToBeat(ms);
        return (ms - doc.offsetMs) * doc.bpms.get(0).bpm / 60000.0;
    }

    // ================================================================== selection

    /** @return the selected notes in time order. */
    public ArrayList<Doc.DNote> selected() {
        ArrayList<Doc.DNote> out = new ArrayList<>();
        if (sel.isEmpty()) return out;
        for (Doc.DNote n : doc.notes) if (sel.contains(n.uid)) out.add(n);
        return out;
    }

    /** @return the one selected note, or null when none or several are selected. */
    public Doc.DNote single() {
        if (sel.size() != 1) return null;
        return doc.byUid(sel.iterator().next());
    }

    public void select(Doc.DNote n, boolean add) {
        if (!add) sel.clear();
        if (n != null) {
            if (add && sel.contains(n.uid)) sel.remove(n.uid);
            else sel.add(n.uid);
        }
    }

    // ================================================================== saving

    /**
     * Writes the chart (after a backup of the old file) and shares the song texts and tempo with the
     * other difficulties. 迭代次数 never drops below the file's own count (写谱辅助 counts its edits
     * there) and grows by one whenever the old file is backed up.
     */
    public void save() {
        doc.iterations = Math.max(doc.iterations, Doc.iterationsIn(file));
        if (lib.backup(song, file)) doc.iterations++;
        try {
            lib.writeChart(song, file, doc);
        } catch (java.io.IOException e) {
            com.badlogic.gdx.Gdx.app.error("Charter", "v5 save failed, kept as v4: " + e.getMessage());
            file.writeString(doc.toJson(), false, "UTF-8");
        }
        diskStamp = file.lastModified();
        lib.shareMeta(song, diff, doc.title, doc.artist, doc.illustrator, doc.bpms);
        // the preview window is set by beat: after a tempo or offset change its ms follow
        // (unchanged keys stay as they are)
        if (!Float.isNaN(song.previewStartBeat) || !Float.isNaN(song.previewEndBeat))
            lib.setPreview(song, song.previewStartBeat, song.previewEndBeat, this::beatToMs);
        Library.Diff d = song.diffs[diff.ordinal()];
        d.constant = doc.constant;
        d.notes = doc.notes.size();
        dirty = false;
    }
}
