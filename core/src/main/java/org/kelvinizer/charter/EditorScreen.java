package org.kelvinizer.charter;

import com.badlogic.gdx.Input;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Cursor;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Rectangle;
import org.kelvinizer.elements.Chart;
import org.kelvinizer.elements.LaneLayout;
import org.kelvinizer.elements.Line;
import org.kelvinizer.elements.Note;
import org.kelvinizer.elements.NoteOrbit;
import org.kelvinizer.elements.NoteType;
import org.kelvinizer.elements.Veil;
import org.kelvinizer.gameplay.CharterPreview;
import org.kelvinizer.gameplay.Field;
import org.kelvinizer.gameplay.GameEngine;
import org.kelvinizer.library.Difficulty;
import org.kelvinizer.ui.Theme;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;

/**
 * 谱面编辑器主界面：顶栏（播放、吸附、保存）、左边工具栏、中间的游戏预览、下面的时间轴和右边的属性面板。
 * The editor screen: the top bar (transport, snap, speed, save), the tool rail,
 * the live game preview where notes are placed and dragged, the timeline (waveform, tempo
 * markers, note rows, field events; 假键 only with 时间轴假键 on), the status bar and the {@link Inspector} on the
 * right. On a tablet
 * ({@link Gfx#TABLET}) the same parts sit on the 1344 × 840 canvas, with a row of buttons under the preview in place
 * of the keyboard shortcuts.
 */
final class EditorScreen implements CharterApp.View {
    // ---- layout (top-left based on the 1920 × 1080 canvas, or the tablet's 1344 × 840)
    private static final boolean TAB = Gfx.TABLET;
    /**
     * The preview: the game's 16 : 10 reference frame (1920 × 1200, Kelvin 2026-10-19) at scale {@link #K},
     * from (92, 68) where the 16 : 9 preview started; the inspector takes the width the narrower frame frees.
     * On a tablet it is 744 × 465, so that the inspector keeps the desktop's 518 width.
     */
    static final float PH = TAB ? 465f : 801f, PW = PH * 1.6f, PX = TAB ? 72f : 92f, PY = TAB ? 64f : 68f,
        K = PW / 1920f;
    /**
     * World y of the preview's top edge: the preview fits the game's field to a 1920 × 1200 world centred at
     * (960, 540) as a 16 : 10 screen does, so reference y is world y + 60 ({@link Field#refY}).
     */
    static final float WTOP = Field.WORLD_CY + Field.REF_H * 0.5f;
    /**
     * The timeline and status bar end at {@link #TR}, where the inspector starts, 28 px right of the preview (1402;
     * 826 on a tablet, under the touch bar {@link #AB_Y}, with taller rows for fingers).
     */
    static final float TX = TAB ? 136f : 150f, TR = Math.round(PX + PW) + (TAB ? 10f : 28f), TW = TR - TX,
        TL_TOP = TAB ? 589f : 882f, TL_BOTTOM = TAB ? 836f : 1052f;
    static final float R_AUDIO = TL_TOP + 4f, R_UP = R_AUDIO + 34f, R_UP_H = TAB ? 64f : 38f,
        R_LOW = R_UP + R_UP_H + 4f, R_LOW_H = TAB ? 68f : 42f;
    static final float R_FIELD = R_LOW + R_LOW_H + 2f, R_FIELD_H = TAB ? 34f : 22f, R_RULER = R_FIELD + R_FIELD_H + 2f;
    /** The tablet's touch bar under the preview (undo, copy, delete, …: what the keyboard does on the desktop). */
    private static final float AB_Y = PY + PH + 8f, AB_H = 44f;
    /** Hit distances grow this much for a finger. */
    private static final float TOUCH = TAB ? 2.5f : 1f;
    /** 统计: in the status bar, or at the end of the tablet's touch bar. */
    private static final float SB_X = TAB ? TR - 66f : TR - 64f, SB_Y = TAB ? AB_Y : TL_BOTTOM + 4f,
        SB_W = 56f, SB_H = TAB ? AB_H : 20f;

    /**
     * Tools (Kelvin 2026-11-01): select (N), the eight note kinds (keys 1–8), 漩涡 (9) and 水帘 (0); lanes and
     * lines live in the 场地 tab.
     */
    static final int T_SELECT = 0, T_TAP = 1, T_HOLD = 2, T_MINE = 3, T_LMINE = 4, T_TIDE = 5, T_TSTREAM = 6,
        T_TMINE = 7, T_TSMINE = 8, T_VEIL = 9, T_CURTAIN = 10;
    private static final String[] TOOL_NAMES = {"选择", "水滴", "水流", "水雷", "轨道水雷", "浪花", "潮汐", "浪花水雷",
        "潮汐水雷", "漩涡", "水帘"};
    private static final String[] TOOL_KEYS = {"N", "1", "2", "3", "4", "5", "6", "7", "8", "9", "0"};
    private static final float[] TOOL_Y = {64f, 124f, 172f, 220f, 268f, 316f, 364f, 412f, 460f, 520f, 568f};
    private static final float TOOL_H = 46f;
    static final int[] SNAPS = {1, 2, 3, 4, 6, 8, 12, 16, 0};
    static final String[] SNAP_LABELS = {"1/1", "1/2", "1/3", "1/4", "1/6", "1/8", "1/12", "1/16", "关"};
    static final float[] SPEEDS = {0.25f, 0.5f, 0.75f, 1f};
    static final String[] SPEED_LABELS = {"0.25×", "0.5×", "0.75×", "1.0×"};
    private static final Color PV_BG = Gfx.c(0x050920), UP_INK = Gfx.c(0xE6ECFF);
    private static final float AUTOSAVE_S = 120f;

    /** Copied notes; static so they can be pasted into another difficulty. */
    private static final ArrayList<Doc.DNote> clipboard = new ArrayList<>();

    final CharterApp app;
    final Gfx g;
    final Im im;
    Session s;
    final Playback pb;
    final Waveform wave;
    final CharterPreview pv;
    final Checks checks = new Checks();
    final Inspector insp;
    int tool = T_SELECT;
    /** The tool N goes back to from 选择: the last other tool picked, 水滴 at first. */
    private int lastTool = T_TAP;
    int snapIdx = 3, speedIdx = 3;
    /** The editor's marks over the preview; off is 实机, the game's HUD and hit effects (Kelvin 2026-10-27). */
    boolean overlays = true;
    boolean zone = true;
    /** Shows 假键 on the timeline (Kelvin 2026-11-02: off by default, they crowd the rows). */
    boolean timelineFakes;
    /** Preview sync hint, in the order of the game's Settings.syncHint values (高亮 is the game default). */
    static final String[] SYNC_LABELS = {"sync：高亮", "sync：增强", "sync：关"};
    /** The four preview buttons sit in a 2 × 2 grid of equal cells (Kelvin 2026-11-02). */
    static final float PV_BTN_W = 118f, PV_BTN_GAP = 6f, PV_GRID_W = PV_BTN_W * 2f + PV_BTN_GAP;
    /** Timeline view: first visible beat and visible span in beats. */
    double tlStart = -4.0, tlSpan = 16.0;
    /** Playhead beat this frame. */
    float beat;
    private float autosaveT, wheelAcc, diskCheckT;
    boolean mantleCurtain;
    /** The tablet's 多选 button: a tap adds to (or takes from) the selection, as Ctrl / Shift + click does. */
    private boolean multi;
    /** The statistics panel above the status bar's 统计 button (Kelvin 2026-10-13). */
    final StatsPanel stats = new StatsPanel();
    boolean statsOpen;
    private static final float STATS_X = (TAB ? SB_X : TR) - 8f - StatsPanel.W, STATS_Y = TL_BOTTOM - 6f - StatsPanel.H;
    private final Matrix4 pvMatrix = new Matrix4();
    /** Rectangles of the preview's world as clips of the canvas (the 实机 corner HUD around the pause control). */
    private final CharterPreview.Clip pvClip = new CharterPreview.Clip() {
        @Override
        public void push(float x0, float y0, float x1, float y1) {
            im.pushClip(ux(x0), uy(y1), (x1 - x0) * K, (y1 - y0) * K);
        }

        @Override
        public void pop() {
            im.popClip();
        }
    };
    private final Rectangle box = new Rectangle();

    // ---- drag state
    private static final int D_NONE = 0, D_PLACE = 1, D_PRESS_NOTE = 2, D_MOVE = 3, D_LENGTH = 4, D_SEEK = 7, D_BOX = 8,
        D_TL_PRESS = 9, D_TL_MOVE = 10, D_PAN = 11,
        D_PV_START = 12, D_PV_END = 13, D_VEIL = 14, D_VEIL_PT = 15;
    /** Veil handles: a polygon point's index, the circle's radius dot, or none. */
    private static final int H_NONE = -1, H_RADIUS = -2;
    private int drag;
    /** Audio-row grab: playhead and view at the press, whether it moved, whether it was playing. */
    private double dPanBeat, dPanStart;
    private boolean dPanMoved, dPanPlay;
    /** Preview-band edge drag: the window in beats while dragging (written to song.json on release). */
    private float dPv0, dPv1;
    private boolean dMarked;
    private float dBeat, dApplied, dPressRx, dPressRy;
    private int dLane, dLaneApplied;
    private Line dLine;
    private Doc.DNote dNote;
    /** The dragged veil, its handle and the centre shift applied so far (reference units). */
    private Doc.DVeil dVeil;
    private int dHandle;
    private float dVx, dVy;
    /** The veil frame {@link #veilFrame} last set: screen centre, cos / sin of the angle, pixels per unit. */
    private float vcx, vcy, vco, vsn, vks;
    private final ArrayList<Doc.DNote> dSel = new ArrayList<>();
    private final HashMap<Integer, Integer> dLanes = new HashMap<>();
    /** Field-row bars of this frame: {x0, x1, lane id or LINE_BAR − line channel}. */
    static final int LINE_BAR = -1000;
    private final ArrayList<float[]> bars = new ArrayList<>();
    private final com.badlogic.gdx.math.Vector2 pivot = new com.badlogic.gdx.math.Vector2();

    EditorScreen(CharterApp app, Session s) {
        this.app = app;
        this.g = app.g;
        this.im = app.im;
        this.s = s;
        pb = new Playback(app.platform);
        pb.load(s.song.audio);
        if (pb.durationMs() <= 0f) pb.setDurationMs((float) Math.max(30000.0, s.beatToMs(s.doc.lastBeat() + 8f)));
        pb.setSpeed(SPEEDS[speedIdx]);
        wave = new Waveform(s.song.audio);
        pv = app.preview;
        pv.setChart(s.chart);
        insp = new Inspector(this);
        insp.tab = Inspector.NOTE;
    }

    // ================================================================== helpers

    static float ux(float rx) {
        return PX + rx * K;
    }

    static float uy(float ry) {
        return PY + (WTOP - ry) * K;
    }

    static float rxOf(float x) {
        return (x - PX) / K;
    }

    static float ryOf(float y) {
        return WTOP - (y - PY) / K;
    }

    float bx(double b) {
        return (float) (TX + (b - tlStart) / tlSpan * TW);
    }

    double beatAtX(float x) {
        return tlStart + (x - TX) / TW * tlSpan;
    }

    /** @return the snap step in beats (0 when snapping is off). */
    float step() {
        int d = SNAPS[snapIdx];
        return d > 0 ? 1f / d : 0f;
    }

    float snap(float b) {
        float st = step();
        return st > 0f ? Math.round(b / st) * st : Math.round(b * 1000f) / 1000f;
    }

    void seekBeat(float b) {
        pb.seek(s.beatToMs(b));
    }

    static String laneName(int id) {
        return id > 0 ? "+" + id : Integer.toString(id);
    }

    static String lineZh(Line l) {
        return l == Line.UPPER ? "上线" : "下线";
    }

    static String fmtTime(double ms) {
        boolean neg = ms < 0;
        long t = Math.round(Math.abs(ms));
        return (neg ? "-" : "") + String.format(Locale.ROOT, "%02d:%02d.%03d", t / 60000, (t / 1000) % 60, t % 1000);
    }

    // ================================================================== frame

    @Override
    public void frame(float dt) {
        pb.update();
        if (!Double.isNaN(listenTo) && (!pb.playing() || pb.pos() >= listenTo)) {
            pb.pause();
            listenTo = Double.NaN;
        }
        s.update();
        Chart shown = overlays ? s.chart : s.liveChart();
        if (pv.chart() != shown) pv.setChart(shown);
        checks.update(s, pv);
        double pos = pb.pos();
        beat = (float) s.msToBeat(pos);
        pv.setLive(!overlays, s.diff);
        pv.seek(pos, dt, pb.playing());
        follow();
        keys();
        diskCheckT += dt;
        if (diskCheckT >= 1f && !im.modalOpen()) {
            diskCheckT = 0f;
            if (s.changedOnDisk()) askReload();
        }
        if (s.dirty) {
            autosaveT += dt;
            if (autosaveT >= AUTOSAVE_S) {
                autosaveT = 0f;
                s.lib.autosave(s.song, s.diff, s.doc.toJson());
            }
        }
        if (statsOpen) im.block(STATS_X, STATS_Y, StatsPanel.W, StatsPanel.H);
        preview();
        timeline();
        rail();
        if (TAB) touchBar();
        else statusBar();
        insp.draw();
        topBar();
        if (statsOpen) {
            stats.draw(g, s, STATS_X, STATS_Y);
            // a press anywhere else closes it (the 统计 button toggles it itself)
            if (im.pressed && !im.inside(STATS_X, STATS_Y, StatsPanel.W, StatsPanel.H)
                && !im.inside(SB_X, SB_Y, SB_W, SB_H)) statsOpen = false;
        }
        if (im.released || !im.down) endDrag();
    }

    /** End of the preview window being listened to ({@link #listenPreview}), else NaN. */
    private double listenTo = Double.NaN;

    /** Plays the song's preview window once, like the game's song list (without its fades), then pauses. */
    void listenPreview() {
        pb.pause();
        pb.seek(s.beatToMs(pvBeat(false)));
        pb.play();
        listenTo = s.beatToMs(pvBeat(true));
    }

    /**
     * @return the preview window's start or end in beats: song.json's beat, else its ms (or the game's default) on this
     * tempo.
     */
    float pvBeat(boolean end) {
        float b = end ? s.song.previewEndBeat : s.song.previewStartBeat;
        if (!Float.isNaN(b)) return b;
        return (float) (Math.round(s.msToBeat(end ? s.song.previewEnd() : s.song.previewStart()) * 1000.0) / 1000.0);
    }

    /** Writes the preview window to song.json by beat (Kelvin 2026-10-21); NaN keeps that end where it is shown. */
    void previewTo(float startBeat, float endBeat) {
        float a = Float.isNaN(startBeat) ? pvBeat(false) : startBeat, b = Float.isNaN(endBeat) ? pvBeat(true) : endBeat;
        if (b <= a) im.toast("预览终点要在起点之后");
        else if (!s.lib.setPreview(s.song, a, b, s::beatToMs)) im.toast("song.json 写不进去");
    }

    /** Keeps the playhead inside the timeline view. */
    private void follow() {
        if (pb.playing() && beat > tlStart + tlSpan * 0.85) tlStart = beat - tlSpan * 0.15;
        if (beat < tlStart || beat > tlStart + tlSpan) tlStart = beat - tlSpan * 0.25;
    }

    private void keys() {
        if (im.modalOpen() || im.textFocus()) return;
        boolean c = im.ctrl;
        if (c) {
            if (im.key(Input.Keys.S)) save(null);
            else if (im.key(Input.Keys.Z)) {
                if (im.shift) redo();
                else undo();
            } else if (im.key(Input.Keys.Y)) redo();
            else if (im.key(Input.Keys.C)) copy(false);
            else if (im.key(Input.Keys.X)) copy(true);
            else if (im.key(Input.Keys.V)) paste();
            else if (im.key(Input.Keys.A)) {
                s.sel.clear();
                for (Doc.DNote n : s.doc.notes) s.sel.add(n.uid);
            }
            return;
        }
        if (im.key(Input.Keys.SPACE)) pb.toggle();
        if (im.key(Input.Keys.N)) setTool(tool == T_SELECT ? lastTool : T_SELECT);
        for (int t = T_TAP; t <= T_VEIL; t++) {
            if (im.key(Input.Keys.NUM_0 + t) || im.key(Input.Keys.NUMPAD_0 + t)) setTool(t);
        }
        if (im.key(Input.Keys.NUM_0) || im.key(Input.Keys.NUMPAD_0)) setTool(T_CURTAIN);
        if (im.key(Input.Keys.TAB)) s.placeLine = s.placeLine == Line.UPPER ? Line.LOWER : Line.UPPER;
        if (im.key(Input.Keys.FORWARD_DEL) || im.key(Input.Keys.DEL)) {
            if (veilTab() && s.veilSel != 0) deleteVeil();
            else deleteSel();
        }
        if (im.key(Input.Keys.M)) {
            if (im.shift) mirrorUpDown();
            else mirror();
        }
        if (im.key(Input.Keys.D)) insp.applyTemplate(s, s.selected(), 0);
        if (im.key(Input.Keys.ESCAPE) && statsOpen) statsOpen = false;
        else if (im.key(Input.Keys.ESCAPE) && !im.popupOpen()) {
            if (!s.sel.isEmpty()) s.sel.clear();
            else tool = T_SELECT;
        }
        if (im.key(Input.Keys.LEFT)) stepTime(-1);
        if (im.key(Input.Keys.RIGHT)) stepTime(1);
        if (im.key(Input.Keys.UP)) nudge(1);
        if (im.key(Input.Keys.DOWN)) nudge(-1);
        if (im.key(Input.Keys.HOME)) pb.seek(0);
        if (im.key(Input.Keys.BACK)) back();
    }

    /** Writes the crash-safety copy now (the app goes to the background, where Android may close it). */
    void autosaveNow() {
        if (s.dirty) s.lib.autosave(s.song, s.diff, s.doc.toJson());
    }

    /** Ctrl or Shift held, or the tablet's 多选 on: a click adds to the selection. */
    private boolean adding() {
        return im.shift || im.ctrl || multi;
    }

    /** Moves the playhead to the previous / next snap line. */
    void stepTime(int dir) {
        float st = step() > 0f ? step() : 0.25f;
        float b = Math.round(beat / st) * st;
        if (dir > 0 && b <= beat + 1e-4f) b += st;
        if (dir < 0 && b >= beat - 1e-4f) b -= st;
        seekBeat(b);
    }

    private void wheelTime() {
        if (im.ctrl) {
            float f = (float) Math.pow(1.25, im.wheel);
            double px = (beat - tlStart) / tlSpan;
            tlSpan = Math.max(2.0, Math.min(512.0, tlSpan * f));
            tlStart = beat - px * tlSpan;
            return;
        }
        wheelAcc += im.wheel;
        while (wheelAcc >= 1f) {
            stepTime(1);
            wheelAcc -= 1f;
        }
        while (wheelAcc <= -1f) {
            stepTime(-1);
            wheelAcc += 1f;
        }
    }

    // ================================================================== commands

    void undo() {
        if (s.undo()) im.toast("撤销");
    }

    void redo() {
        if (s.redo()) im.toast("重做");
    }

    void save(Runnable after) {
        if (s.changedOnDisk()) {
            Im.Modal m = new Im.Modal("谱面文件在编辑器外被改过", "现在保存会盖掉别的程序（比如写谱辅助）对「" + s.file.name() + "」的改动。",
                new String[]{"重新载入", "仍然保存", "取消"}, (b, t) -> {
                    if (b == 0) reload();
                    else if (b == 1) {
                        s.keepMine();
                        save(after);
                    }
                });
            m.danger = 1;
            im.open(m);
            return;
        }
        try {
            s.save();
            autosaveT = 0f;
            im.toast(checks.problems > 0 ? "已保存 · 有 " + checks.problems + " 项检查提示（见「谱面」页）" : "已保存");
            if (after != null) after.run();
        } catch (RuntimeException e) {
            im.open(new Im.Modal("保存失败", String.valueOf(e.getMessage()), new String[]{"好"}, (b, t) -> { }));
        }
    }

    /** The chart file changed outside the editor (Kelvin 2026-10-13): reload it or keep the editor's version. */
    void askReload() {
        String msg = "「" + s.file.name() + "」被别的程序改过（比如写谱辅助）。"
            + (s.dirty ? "重新载入会换成文件里的版本，你没保存的改动按 Ctrl+Z 能找回，也存在自动保存里。" : "重新载入就能看到新的内容。");
        im.open(new Im.Modal("谱面文件在编辑器外被改过", msg, new String[]{"重新载入", "保留编辑器里的"}, (b, t) -> {
            if (b == 0) reload();
            else s.keepMine();
        }));
    }

    /** The top bar's 重新载入: asks first when there are unsaved changes. */
    void reloadButton() {
        if (!s.dirty) {
            reload();
            return;
        }
        im.open(new Im.Modal("重新载入谱面文件", "会换成文件里的版本。你没保存的改动按 Ctrl+Z 能找回，也存在自动保存里。",
            new String[]{"重新载入", "取消"}, (b, t) -> {
                if (b == 0) reload();
            }));
    }

    void reload() {
        try {
            s.reload();
            autosaveT = 0f;
            im.toast("已重新载入 " + s.file.name());
        } catch (Exception e) {
            im.open(new Im.Modal("载入失败", String.valueOf(e.getMessage()), new String[]{"好"}, (b, t) -> { }));
        }
    }

    void copy(boolean cut) {
        ArrayList<Doc.DNote> sel = s.selected();
        if (sel.isEmpty()) return;
        clipboard.clear();
        for (Doc.DNote n : sel) clipboard.add(n.copy(false));
        if (cut) deleteSel();
        im.toast((cut ? "已剪切 " : "已复制 ") + sel.size() + " 个音符");
    }

    /**
     * Pastes the copied notes so that the earliest lands on the snap line at (or after) the playhead.
     * Like moving a judgement, relative events follow and absolute events keep their beats (Kelvin 2026-10-13).
     */
    void paste() {
        if (clipboard.isEmpty()) return;
        float base = Float.MAX_VALUE;
        for (Doc.DNote n : clipboard) base = Math.min(base, n.judgement);
        float at = snap(beat);
        if (at < beat - 1e-4f && step() > 0f) at += step();
        s.mark();
        s.sel.clear();
        for (Doc.DNote c : clipboard) {
            Doc.DNote n = c.copy(false);
            n.retime(at - base);
            if (n.inLane() && s.doc.lane(n.lane) == null) {
                if (s.doc.laneCount == 0) s.doc.ensureLane(n.lane);
                else n.lane = s.doc.lanes.get(s.doc.lanes.size() / 2).id;
            }
            s.doc.notes.add(n);
            s.sel.add(n.uid);
        }
        s.changed(false);
        im.toast("已粘贴 " + clipboard.size() + " 个音符");
    }

    void deleteSel() {
        if (s.sel.isEmpty()) return;
        s.mark();
        s.doc.notes.removeIf(n -> s.sel.contains(n.uid));
        s.sel.clear();
        s.changed(false);
    }

    /** Deletes the selected veil or curtain (漩涡 / 水帘 page). */
    void deleteVeil() {
        Doc.DVeil v = selVeil();
        if (v == null) return;
        s.mark();
        if (!s.doc.veils.remove(v)) s.doc.curtains.remove(v);
        s.veilSel = 0;
        s.changed(false);
    }

    /** Mirrors the selected notes left ↔ right (lane order, rotation and the pivot's x). */
    void mirror() {
        ArrayList<Doc.DNote> sel = s.selected();
        if (sel.isEmpty()) {
            im.toast("先选中音符");
            return;
        }
        s.mark();
        int count = s.doc.lanes.size();
        for (Doc.DNote n : sel) {
            if (n.inLane()) {
                int i = s.doc.laneIndex(n.lane);
                if (i >= 0) n.lane = s.doc.lanes.get(count - 1 - i).id;
            }
            for (Doc.Ev e : n.rot) {
                e.from = -e.from;
                e.to = -e.to;
            }
            for (Doc.Ev e : n.offEv) {
                e.from = -e.from;
                e.to = -e.to;
            }
            // the orbit centre is a point of the 1920-wide reference frame
            n.orbitX = Field.REF_W - n.orbitX;
            for (Doc.Ev e : n.orbXEv) {
                e.from = Field.REF_W - e.from;
                e.to = Field.REF_W - e.to;
            }
            // the orbit's angle (0° right, counter-clockwise) mirrors to 180° − a
            if (!Float.isNaN(n.orbitAngle)) n.orbitAngle = 180f - n.orbitAngle;
            for (Doc.Ev e : n.orbAngEv) {
                e.from = 180f - e.from;
                e.to = 180f - e.to;
            }
            n.pivotX = -n.pivotX;
            n.waveSpeed = -n.waveSpeed;
            n.offsetX = -n.offsetX;
        }
        s.changed(false);
    }

    /**
     * Mirrors the selected notes top ↔ bottom (Kelvin 2026-10-22): sky ↔ ground with the same lane, beat and fall.
     * A sky note's frame is the ground one turned 180°, so the rotation and the pivot's x change sign as well.
     */
    void mirrorUpDown() {
        ArrayList<Doc.DNote> sel = s.selected();
        if (sel.isEmpty()) {
            im.toast("先选中音符");
            return;
        }
        s.mark();
        for (Doc.DNote n : sel) {
            n.line = n.line == Line.UPPER ? Line.LOWER : Line.UPPER;
            for (Doc.Ev e : n.rot) {
                e.from = -e.from;
                e.to = -e.to;
            }
            n.pivotX = -n.pivotX;
            // the orbit centre is a point of the 1200-high reference frame
            n.orbitY = Field.REF_H - n.orbitY;
            for (Doc.Ev e : n.orbYEv) {
                e.from = Field.REF_H - e.from;
                e.to = Field.REF_H - e.to;
            }
            if (!Float.isNaN(n.orbitAngle)) n.orbitAngle = -n.orbitAngle;
            for (Doc.Ev e : n.orbAngEv) {
                e.from = -e.from;
                e.to = -e.to;
            }
        }
        s.changed(false);
    }

    /** Moves the selected notes by one snap step in time. */
    private void nudge(int dir) {
        ArrayList<Doc.DNote> sel = s.selected();
        if (sel.isEmpty()) return;
        float st = step() > 0f ? step() : 0.25f;
        s.mark();
        for (Doc.DNote n : sel) n.retime(dir * st);
        s.changed(false);
    }

    void jump(Checks.Item it) {
        if (!it.uids.isEmpty()) {
            s.sel.clear();
            s.sel.addAll(it.uids);
        }
        if (it.veil != 0) {
            s.veilSel = it.veil;
            insp.tab = Inspector.VEIL;
        }
        if (!Float.isNaN(it.beat)) {
            pb.pause();
            seekBeat(it.beat - 0.5f);
        }
    }

    // ================================================================== leaving

    void back() {
        confirmLeave(() -> app.show(new LibraryScreen(app, s.song)));
    }

    /** Runs {@code go} now, or after asking what to do with unsaved changes. */
    void confirmLeave(Runnable go) {
        if (!s.dirty) {
            go.run();
            return;
        }
        Im.Modal m = new Im.Modal("还没保存", "「" + s.song.name() + " · " + Gfx.diffName(s.diff) + "」有没保存的修改。",
            new String[]{"保存", "不保存", "取消"}, (b, t) -> {
                if (b == 0) save(go);
                else if (b == 1) {
                    s.lib.autosave(s.song, s.diff, s.doc.toJson());
                    go.run();
                }
            });
        m.danger = 1;
        im.open(m);
    }

    @Override
    public void close(Runnable quit) {
        confirmLeave(quit);
    }

    /** Switches to another difficulty of the song (offers to create a missing one). */
    void switchDiff(Difficulty d) {
        if (d == s.diff) return;
        confirmLeave(() -> {
            if (s.song.diffs[d.ordinal()].exists()) {
                openDiff(d);
                return;
            }
            Im.Modal m = new Im.Modal("新建 " + Gfx.diffName(d), "这首歌还没有 " + Gfx.diffName(d) + " 难度，要新建一个吗？",
                new String[]{"空白", "复制 " + Gfx.diffName(s.diff), "取消"}, (b, t) -> {
                    if (b != 0 && b != 1) return;
                    try {
                        s.lib.createDiff(s.song, d, b == 1 ? s.diff : null);
                        openDiff(d);
                    } catch (Exception e) {
                        im.toast("新建失败：" + e.getMessage());
                    }
                });
            m.primary = 1;
            im.open(m);
        });
    }

    private void openDiff(Difficulty d) {
        try {
            s = Session.open(s.lib, s.song, d);
            pv.setChart(s.chart);
            im.unfocus();
        } catch (Exception e) {
            im.open(new Im.Modal("打不开", d.fileName() + "：" + e.getMessage(), new String[]{"好"}, (b, t) -> { }));
        }
    }

    @Override
    public void dispose() {
        pb.dispose();
    }

    // ================================================================== preview

    /**
     * @return the note's box at beat {@code b} in preview world coordinates (y up, reference y − 60), or false when not
     * on screen.
     */
    private boolean noteBox(Doc.DNote d, float b, Rectangle r) {
        Note n = s.note(d);
        if (n == null || s.chart == null) return false;
        if (!(b < d.judgement || (d.sustained() && b < d.end()))) return false;
        float pos = b < d.judgement ? n.positionAt(b) : 0f;
        if (pos >= Note.HIDDEN_POSITION - 1f || pos < -16000f / 9f) return false;
        GameEngine e = pv.engine();
        LaneLayout lay = e.layout();
        boolean up = d.line == Line.UPPER;
        float ly = e.lineY(d.line), dp = pos - pv.hitShift(), far = dp + pv.bodyShift(d.type, dp);
        float y = up ? ly - far : ly + far;
        float x0, w;
        if (d.inLane()) {
            int i = lay.indexOf(d.lane);
            if (i < 0) return false;
            w = Math.max(pv.headWidth(lay.width(i)), 30f);
            x0 = lay.centre(i) + n.offsetXAt(b) * Field.kx() - w * 0.5f;
        } else {
            x0 = lay.fieldLeft();
            w = lay.fieldRight() - x0;
        }
        float h = Math.max(pv.noteHeight(), 28f);
        float tail = d.sustained() ? n.tailHeightAt(b) : 0f;
        // the head sits on the far side of its drawn position (like the game draws it), the tail beyond it
        float lo = up ? y - h - tail : y;
        float hi = up ? y : y + h + tail;
        r.set(x0, lo, w, hi - lo);
        NoteOrbit o = n.orbit();
        if (o != null && o.polar()) {
            // 公转 with a radius: while it lasts the head's centre is the radius from the centre at the orbit's angle
            if (n.inOrbitSpan(b)) {
                float r0 = o.radiusAt(b), a0 = o.angleAt(b);
                float cx = Field.x(o.xAt(b)) + r0 * MathUtils.cosDeg(a0) * Field.kx();
                float cy = o.conflict() == NoteOrbit.CONFLICT_MOVE ? r.y + r.height * 0.5f
                    : Field.y(o.yAt(b)) + r0 * MathUtils.sinDeg(a0) * Field.ky();
                r.setPosition(cx - w * 0.5f, cy - r.height * 0.5f);
            }
        } else if (o != null) {
            // 公转: the box's centre turns about the orbit's starting centre and moves with it (NoteShapes.anchorX)
            float vx = (r.x + w * 0.5f - Field.x(o.x())) / Field.kx(), vy = (r.y + r.height * 0.5f - Field.y(o.y()))
                / Field.ky();
            float a = n.angleAt(b), cos = MathUtils.cosDeg(a), sin = MathUtils.sinDeg(a);
            r.setPosition(Field.x(o.xAt(b)) + (cos * vx - sin * vy) * Field.kx() - w * 0.5f,
                Field.y(o.yAt(b)) + (sin * vx + cos * vy) * Field.ky() - r.height * 0.5f);
        }
        return true;
    }

    /**
     * @return whether the point note {@code d} turns about (its pivot, CHART_FORMAT §6.1.1) is on screen at beat
     *         {@code b}, set into {@code out} in preview world coordinates (y up): its origin (head centre, MINE
     *         body or LANE MINE base centre, a TIDE bar's middle on screen) plus the pivot in its frame, turned
     *         180° on the sky line, as the game places it at note size 1
     */
    private boolean pivotPoint(Doc.DNote d, float b, com.badlogic.gdx.math.Vector2 out) {
        Note n = s.note(d);
        if (n == null || s.chart == null) return false;
        if (!(b < d.judgement || (d.sustained() && b < d.end()))) return false;
        float pos = b < d.judgement ? n.positionAt(b) : 0f;
        if (pos >= Note.HIDDEN_POSITION - 1f || pos < -16000f / 9f) return false;
        GameEngine e = pv.engine();
        LaneLayout lay = e.layout();
        float x, h;
        if (d.inLane()) {
            int i = lay.indexOf(d.lane);
            if (i < 0) return false;
            x = lay.centre(i) + n.offsetXAt(b) * Field.kx();
            h = d.type == NoteType.MINE ? pv.mineHeight() : pv.noteHeight();
        } else {
            float l = Math.max(lay.fieldLeft(), LaneLayout.VIEW_LEFT),
                r = Math.min(lay.fieldRight(), LaneLayout.VIEW_RIGHT);
            x = l < r ? 0.5f * (l + r) : 0.5f * (lay.fieldLeft() + lay.fieldRight());
            h = pv.tideHeight();
        }
        NoteOrbit o = n.orbit();
        if (o != null) {
            out.set(Field.x(o.xAt(b)), Field.y(o.yAt(b)));
            return true;
        }
        boolean up = d.line == Line.UPPER;
        float ly = e.lineY(d.line), dp = pos - pv.hitShift(), off = dp + pv.bodyShift(d.type, dp) + h * 0.5f + d.pivotY;
        out.set(up ? x - d.pivotX : x + d.pivotX, up ? ly - off : ly + off);
        return true;
    }

    /** @return the note under the preview world point (smallest box wins), or null. */
    private Doc.DNote noteAt(float rx, float ry) {
        Doc.DNote best = null;
        float bestArea = Float.MAX_VALUE, pad = 6f * TOUCH / K;
        for (Doc.DNote n : s.doc.notes) {
            if (!noteBox(n, beat, box)) continue;
            if (rx < box.x - pad || rx > box.x + box.width + pad || ry < box.y - pad
                || ry > box.y + box.height + pad) continue;
            float area = box.width * box.height;
            if (area < bestArea) {
                best = n;
                bestArea = area;
            }
        }
        return best;
    }

    /** @return distance from {@code line} towards where its notes come from. */
    private float dist(Line line, float ry) {
        float ly = pv.engine().lineY(line);
        return line == Line.UPPER ? ly - ry : ry - ly;
    }

    /** @return the beat a default-approach note at {@code ry} has (the placement mapping). */
    private float beatAtRef(Line line, float ry) {
        return beat + Math.max(0f, dist(line, ry)) / spd();
    }

    /**
     * @return the default fall's speed at the playhead (ref units per beat; slower under 统一流速 with a lower flow BPM).
     */
    private float spd() {
        return s.doc.speed(beat);
    }

    private void laneMark(LaneLayout lay, int id, float upY, float lowY, float fill) {
        int i = lay.indexOf(id);
        if (i < 0) return;
        float x0 = ux(lay.left(i)), w = lay.width(i) * K;
        float yt = uy(Math.max(upY, lowY)), yb = uy(Math.min(upY, lowY));
        g.rect(x0, yt, w, yb - yt, Gfx.GND, fill);
        g.line(x0, yt, x0, yb, 2f, Gfx.GND, 0.8f);
        g.line(x0 + w, yt, x0 + w, yb, 2f, Gfx.GND, 0.8f);
    }

    /** Picks a tool; 漩涡 / 水帘 also open their tab, whose list and card they work with. */
    void setTool(int t) {
        tool = t;
        if (t != T_SELECT) lastTool = t;
        if (t == T_VEIL || t == T_CURTAIN) {
            mantleCurtain = t == T_CURTAIN;
            insp.tab = Inspector.VEIL;
        }
    }

    /** @return true for the 漩涡 and 水帘 tools. */
    private boolean veilTool() {
        return tool == T_VEIL || tool == T_CURTAIN;
    }

    private NoteType placeType() {
        switch (tool) {
            case T_TAP: return NoteType.TAP;
            case T_HOLD: return NoteType.HOLD;
            case T_MINE:
            case T_LMINE: return NoteType.MINE;
            case T_TMINE:
            case T_TSMINE: return NoteType.TIDE_MINE;
            default: return NoteType.UNIVERSAL;
        }
    }

    /** @return true for the tools that place a long note (水流 and the three long kinds); drag sets the length. */
    private boolean longTool() {
        return tool == T_HOLD || tool == T_LMINE || tool == T_TSTREAM || tool == T_TSMINE;
    }

    private boolean placing() {
        return tool >= T_TAP && tool <= T_TSMINE;
    }

    /** @return true for the tools whose notes take a line's whole zone (the four 浪花 kinds), not a lane. */
    private boolean laneless() {
        return tool >= T_TIDE && tool <= T_TSMINE;
    }

    private void preview() {
        g.rect(PX, PY, PW, PH, PV_BG);
        boolean ready = s.chart != null;
        im.pushClip(PX, PY, PW, PH);
        if (ready) {
            pvMatrix.set(app.cam.combined).translate(PX, Gfx.H - PY - PH, 0f).scale(K, K, 1f)
                .translate(0f, Field.REF_H - WTOP, 0f);
            g.batch.setProjectionMatrix(pvMatrix);
            pv.draw(g.batch, zone ? 4f : 1f);
            pv.drawCorners(g.batch, pvClip);
            pv.drawCurtains(g.batch);
            g.batch.setProjectionMatrix(app.cam.combined);
        }
        float bTagX = PX + PW - 12f - PV_GRID_W, bTagY = PY + 12f, bCol2 = bTagX + PV_BTN_W + PV_BTN_GAP;
        boolean overUi = im.inside(bTagX, bTagY, PV_GRID_W, 54f)
            || overlays && im.inside(PX + 12f, PY + 12f, 260f, 24f);
        boolean inCanvas = im.hover(PX, PY, PW, PH) && !overUi;
        float rx = rxOf(im.mx), ry = ryOf(im.my);
        GameEngine e = pv.engine();
        LaneLayout lay = e.layout();
        Doc.DNote hot = ready && inCanvas && drag == D_NONE ? noteAt(rx, ry) : null;
        if (ready && inCanvas && drag == D_NONE && veilPage() && (handleAt() != H_NONE || veilAt(rx, ry) != 0)) {
            hot = null;                                         // on the 漩涡 page the veil is on top
            im.cursor(Cursor.SystemCursor.Hand);
        }

        if (ready) {
            float upY = e.lineY(Line.UPPER), lowY = e.lineY(Line.LOWER);
            // selected lane
            if (insp.tab == Inspector.FIELD) {
                for (int id : s.lanePick) if (id != s.laneSel) laneMark(lay, id, upY, lowY, 0.05f);
                if (s.laneSel != Session.NO_LANE) laneMark(lay, s.laneSel, upY, lowY, 0.07f);
            }
            if (overlays) {
                for (int i = 0; i < lay.count(); i++) {
                    if (!lay.active(i)) continue;
                    int id = lay.idAt(i);
                    g.text(laneName(id), ux(lay.centre(i)), uy(Field.y(100f)), 14,
                        id == s.laneSel || s.lanePick.contains(id) ? Gfx.GND : Gfx.INK3, 1);
                }
                float rEdge = PX + PW - 12f;
                g.text("上线 upper · y " + Math.round(Field.refY(upY)), rEdge, uy(upY) + 15f, 12, Gfx.INK1, 2);
                g.text("下线 lower · y " + Math.round(Field.refY(lowY)), rEdge, uy(lowY) - 12f, 12, Gfx.GND, 2);
                if (placing()) grid(e, lay);
            }
            noteOverlays(hot);
            if (veilTab() && overlays) veilOverlays();
            if (placing() && overlays && (drag == D_PLACE || (inCanvas && hot == null && drag == D_NONE))) {
                if (drag == D_PLACE) {
                    float t1 = snap(beatAtRef(dLine, ry));
                    float dur = longTool() ? Math.max(0f, t1 - dBeat) : 0f;
                    ghost(dBeat, dur, dLane, dLine);
                } else {
                    int i = lay.indexAtX(rx);
                    if (i >= 0 || laneless()) ghost(snap(beatAtRef(s.placeLine, ry)), 0f,
                        i >= 0 ? lay.idAt(i) : 0, s.placeLine);
                }
            }
        } else {
            g.text("谱面暂时无法预览：" + s.buildError, PX + PW * 0.5f, PY + PH * 0.5f, 14, Gfx.BAD, 1);
        }
        im.popClip();
        g.stroke(PX, PY, PW, PH, 6f, 1f, Gfx.LINE);

        // tag (not in 实机, whose score is there) and view buttons
        if (overlays) {
            String tag = "预览 · 第 " + String.format(Locale.ROOT, "%.2f", beat) + " 拍";
            if (s.laneSel != Session.NO_LANE && insp.tab == Inspector.FIELD && lay.indexOf(s.laneSel) >= 0) {
                tag += " · 轨道 " + laneName(s.laneSel) + " 宽 " + Math.round(lay.width(lay.indexOf(s.laneSel)));
            }
            float tw = g.width(tag, 12) + 20f;
            g.box(PX + 12f, PY + 12f, tw, 24f, 4f, Gfx.BAR, Gfx.LINE);
            g.text(tag, PX + 22f, PY + 24f, 12, Gfx.INK3);
        }
        if (im.button("pv-sync", bTagX, bTagY, PV_BTN_W, 24f, SYNC_LABELS[pv.syncHint()], Im.NORMAL, 12)) {
            pv.setSyncHint((pv.syncHint() + 1) % SYNC_LABELS.length);
        }
        if (im.button("pv-view", bCol2, bTagY, PV_BTN_W, 24f, "⇆ 实机", overlays ? Im.NORMAL : Im.ON, 12)) {
            overlays = !overlays;
        }
        if (im.button("pv-zone", bTagX, bTagY + 30f, PV_BTN_W, 24f, "显示：判定区 " + (zone ? "✓" : "×"), Im.NORMAL, 12)) {
            zone = !zone;
        }
        if (im.button("pv-fake", bCol2, bTagY + 30f, PV_BTN_W, 24f, "时间轴假键 " + (timelineFakes ? "✓" : "×"),
            Im.NORMAL, 12)) {
            timelineFakes = !timelineFakes;
        }

        if (!ready || im.modalOpen()) return;
        if (inCanvas && im.wheel != 0f) wheelTime();
        if (inCanvas && hot != null) im.cursor(Cursor.SystemCursor.Hand);
        if (im.pressed && inCanvas && drag == D_NONE && !im.popupOpen()) pressPreview(rx, ry, hot);
        if (im.rightPressed && inCanvas && hot != null) {
            s.mark();
            s.doc.notes.remove(hot);
            s.sel.remove(hot.uid);
            s.changed(false);
        }
        if (im.down) dragPreview(rx, ry);
    }

    /** Placement grid: snap lines along the approach of the placement line. */
    private void grid(GameEngine e, LaneLayout lay) {
        float st = step() > 0f ? step() : 0.25f;
        float ly = e.lineY(s.placeLine);
        boolean up = s.placeLine == Line.UPPER;
        float x0 = ux(lay.fieldLeft()), x1 = ux(lay.fieldRight());
        Color c = up ? UP_INK : Gfx.GND;
        int k0 = (int) Math.ceil(beat / st - 1e-4), k1 = (int) Math.floor((beat + 2.2f) / st);
        if (k1 - k0 > 80) k1 = k0 + 80;
        for (int k = k0; k <= k1; k++) {
            float b = k * st;
            float d = (b - beat) * spd();
            float y = uy(up ? ly - d : ly + d);
            boolean whole = Math.abs(b - Math.round(b)) < 1e-3f;
            g.line(x0, y, x1, y, 1f, c, whole ? 0.22f : 0.09f);
        }
    }

    private void noteOverlays(Doc.DNote hot) {
        Doc.DNote one = s.single();
        for (Doc.DNote n : s.doc.notes) {
            boolean sel = s.sel.contains(n.uid);
            Note gn = s.note(n);
            boolean warn = gn != null && gn.usesDefaultApproach() && overlays;
            if (!sel && !warn && n != hot) continue;
            if (!noteBox(n, beat, box)) continue;
            float x = ux(box.x), y = uy(box.y + box.height), w = box.width * K, h = box.height * K;
            if (sel) g.stroke(x - 4f, y - 4f, w + 8f, h + 8f, 5f, 2f, Color.WHITE);
            // the rotation pivot of a selected note that turns or has one (Kelvin 10-16)
            if (sel && (!n.rot.isEmpty() || n.pivotX != 0f || n.pivotY != 0f || n.orbit)
                && pivotPoint(n, beat, pivot)) {
                float cx = ux(pivot.x), cy = uy(pivot.y);
                g.rect(cx - 9f, cy - 1f, 18f, 2f, Color.WHITE);
                g.rect(cx - 1f, cy - 9f, 2f, 18f, Color.WHITE);
                g.disc(cx, cy, 3.5f, Gfx.SYNC);
            }
            if (n == hot && !sel) g.stroke(x - 3f, y - 3f, w + 6f, h + 6f, 5f, 1.5f, Color.WHITE, 0.45f);
            if (warn) {
                g.disc(x + w + 2f, y - 2f, 8f, Gfx.SYNC);
                g.text("!", x + w + 2f, y - 2f, 12, Gfx.PRI_INK, 1);
            }
            if (n == one) {
                String tip = n.nameZh() + (n.inLane() ? " · 轨 " + laneName(n.lane) : "") + " · 拍 "
                    + Im.fmt(n.judgement);
                float tw = g.width(tip, 12) + 18f;
                float tx = x + w + 14f > PX + PW - tw ? x - tw - 14f : x + w + 14f;
                g.box(tx, y + h * 0.5f - 13f, tw, 26f, 4f, Gfx.BAR, Gfx.SEL_LINE);
                g.text(tip, tx + 9f, y + h * 0.5f, 12, Gfx.INK1);
                if (n.sustained()) {
                    float fy = n.line == Line.UPPER ? uy(box.y) : y;
                    g.disc(x + w * 0.5f, fy, 6f, Color.WHITE);
                    g.text("拖动改时长", x + w * 0.5f + 12f, fy, 12, Gfx.INK1);
                }
            }
        }
    }

    // ---- 漩涡 page (Kelvin 2026-10-23)

    /** @return true when the preview edits veils: the 漩涡 or 水帘 tab with the select tool. */
    private boolean veilPage() {
        return veilTab() && tool == T_SELECT || veilTool();
    }

    /** @return true on the 漩涡 or 水帘 tab. */
    private boolean veilTab() {
        return insp.tab == Inspector.VEIL || insp.tab == Inspector.CURTAIN;
    }

    /** @return the selected veil when it belongs to the open tab, else null. */
    private Doc.DVeil selVeil() {
        Doc.DVeil v = s.doc.veilByUid(s.veilSel);
        return v != null && veilList().contains(v) ? v : null;
    }

    /** @return the objects shown by the open veil or curtain tab. */
    List<Doc.DVeil> veilList() {
        return insp.tab == Inspector.CURTAIN ? s.doc.curtains : s.doc.veils;
    }

    /**
     * The exact outline (what blocks) of every veil showing at the playhead; the selected one in the accent colour,
     * dimmed outside its time, with its centre and the handles to drag.
     */
    private void veilOverlays() {
        List<Doc.DVeil> list = veilList();
        for (int i = 0; i < list.size(); i++) {
            Doc.DVeil dv = list.get(i);
            Veil v = s.veil(dv);
            boolean sel = dv.uid == s.veilSel, shows = v != null && v.existsAt(beat);
            if (v == null || !sel && !shows) continue;
            Color c = !shows ? Gfx.INK3 : sel ? Gfx.GND : Gfx.INK2;
            float w = sel ? 2f : 1.5f;
            veilFrame(v);
            if (v.shape() == Veil.Shape.CIRCLE) {
                g.circle(vcx, vcy, v.radius() * vks, w, c);
                if (sel) dot(hx(v.radius(), 0f), hy(v.radius(), 0f));
            } else {
                for (int k = 0, n = v.pointCount(); k < n; k++) {
                    int j = (k + 1) % n;
                    g.line(hx(v.pointX(k), v.pointY(k)), hy(v.pointX(k), v.pointY(k)), hx(v.pointX(j), v.pointY(j)),
                        hy(v.pointX(j), v.pointY(j)), w, c);
                }
                for (int k = 0; sel && k < v.pointCount(); k++) {
                    dot(hx(v.pointX(k), v.pointY(k)), hy(v.pointX(k), v.pointY(k)));
                }
            }
            if (sel) {
                g.rect(vcx - 9f, vcy - 1f, 18f, 2f, Color.WHITE);
                g.rect(vcx - 1f, vcy - 9f, 2f, 18f, Color.WHITE);
            }
            g.text((s.doc.curtains.contains(dv) ? "水帘" : "漩涡") + (i + 1), vcx, vcy + 20f, 12, c, 1);
        }
    }

    private void dot(float x, float y) {
        g.disc(x, y, 6f, Gfx.GND);
        g.disc(x, y, 3.5f, Color.WHITE);
    }

    /** Sets the screen frame of veil {@code v} at the playhead for {@link #hx} / {@link #hy}. */
    private void veilFrame(Veil v) {
        vcx = ux(Field.x(v.xAt(beat)));
        vcy = uy(Field.y(v.yAt(beat)));
        double a = Math.toRadians(v.rotationAt(beat));
        vco = (float) Math.cos(a);
        vsn = (float) Math.sin(a);
        vks = Field.shapeScale() * K;
    }

    /** @return the screen x of point (lx, ly) of the veil {@link #veilFrame} set (units from its centre, y up). */
    private float hx(float lx, float ly) {
        return vcx + (lx * vco - ly * vsn) * vks;
    }

    private float hy(float lx, float ly) {
        return vcy - (lx * vsn + ly * vco) * vks;
    }

    /** @return the selected veil's handle under the pointer: a point's index, {@link #H_RADIUS} or {@link #H_NONE}. */
    private int handleAt() {
        Doc.DVeil dv = selVeil();
        Veil v = dv != null ? s.veil(dv) : null;
        if (v == null) return H_NONE;
        veilFrame(v);
        if (v.shape() == Veil.Shape.CIRCLE) return near(hx(v.radius(), 0f), hy(v.radius(), 0f)) ? H_RADIUS : H_NONE;
        for (int k = v.pointCount() - 1; k >= 0; k--) {
            if (near(hx(v.pointX(k), v.pointY(k)), hy(v.pointX(k), v.pointY(k)))) return k;
        }
        return H_NONE;
    }

    private boolean near(float x, float y) {
        return Math.abs(im.mx - x) < 10f * TOUCH && Math.abs(im.my - y) < 10f * TOUCH;
    }

    /** @return the uid of the veil under world point (wx, wy), the selected one first, else one showing; 0 = none. */
    private int veilAt(float wx, float wy) {
        Doc.DVeil sel = selVeil();
        if (sel != null && inVeil(sel, wx, wy)) return sel.uid;
        List<Doc.DVeil> list = veilList();
        for (int i = list.size() - 1; i >= 0; i--) {
            Doc.DVeil dv = list.get(i);
            Veil v = s.veil(dv);
            if (v != null && v.existsAt(beat) && inVeil(dv, wx, wy)) return dv.uid;
        }
        return 0;
    }

    /** @return true when world point (wx, wy) lies inside the veil's outline at the playhead. */
    private boolean inVeil(Doc.DVeil dv, float wx, float wy) {
        Veil v = s.veil(dv);
        if (v == null) return false;
        float k = Field.shapeScale();
        float dx = (wx - Field.x(v.xAt(beat))) / k, dy = (wy - Field.y(v.yAt(beat))) / k;
        double a = Math.toRadians(v.rotationAt(beat));
        float c = (float) Math.cos(a), sn = (float) Math.sin(a);
        return v.contains(dx * c + dy * sn, dy * c - dx * sn);
    }

    /** A press on a handle of the selected veil or inside a veil selects it and starts a drag; false = neither. */
    private boolean pressVeil(float rx, float ry) {
        int h = handleAt();
        int uid = h != H_NONE ? s.veilSel : veilAt(rx, ry);
        if (uid == 0) return false;
        s.veilSel = uid;
        dVeil = s.doc.veilByUid(uid);
        dHandle = h;
        drag = h != H_NONE ? D_VEIL_PT : D_VEIL;
        dPressRx = rx;
        dPressRy = ry;
        dVx = 0f;
        dVy = 0f;
        return true;
    }

    /** 漩涡 / 水帘 tool: a click off the others adds a round one centred there, from the playhead for 8 beats. */
    private void placeVeil(float rx, float ry) {
        s.mark();
        Doc.DVeil v = new Doc.DVeil();
        v.x = Math.round(Field.refX(rx));
        v.y = Math.round(Field.refY(ry));
        v.start = snap(beat);
        v.end = v.start + 8f;
        (mantleCurtain ? s.doc.curtains : s.doc.veils).add(v);
        s.veilSel = v.uid;
        insp.tab = Inspector.VEIL;
        s.changed(false);
    }

    /** Moves the dragged veil with the pointer in whole reference units, its x / y events too. */
    private void veilTo(float rx, float ry) {
        float dx = Math.round(Field.refX(rx) - Field.refX(dPressRx));
        float dy = Math.round(Field.refY(ry) - Field.refY(dPressRy));
        if (dx == dVx && dy == dVy) return;
        lazyMark();
        dVeil.shift(dx - dVx, dy - dVy);
        dVx = dx;
        dVy = dy;
        s.changed(true);
    }

    /** Puts the dragged point of a polygon, or the edge of a circle, under the pointer (in the veil's own frame). */
    private void handleTo(float rx, float ry) {
        Veil v = s.veil(dVeil);
        if (v == null) return;
        float k = Field.shapeScale();
        float dx = (rx - Field.x(v.xAt(beat))) / k, dy = (ry - Field.y(v.yAt(beat))) / k;
        if (dHandle == H_RADIUS) {
            float r = Math.max(Veil.MIN_SIZE, Math.min(Veil.MAX_SIZE, Math.round((float) Math.hypot(dx, dy))));
            if (r == dVeil.radius) return;
            lazyMark();
            dVeil.radius = r;
        } else {
            if (2 * dHandle + 1 >= dVeil.points.length) return;
            double a = Math.toRadians(v.rotationAt(beat));
            float c = (float) Math.cos(a), sn = (float) Math.sin(a);
            float lx = Math.max(-Veil.MAX_SIZE, Math.min(Veil.MAX_SIZE, Math.round(dx * c + dy * sn)));
            float ly = Math.max(-Veil.MAX_SIZE, Math.min(Veil.MAX_SIZE, Math.round(dy * c - dx * sn)));
            if (lx == dVeil.points[2 * dHandle] && ly == dVeil.points[2 * dHandle + 1]) return;
            lazyMark();
            dVeil.points[2 * dHandle] = lx;
            dVeil.points[2 * dHandle + 1] = ly;
        }
        s.changed(true);
    }

    private boolean handleHit(Doc.DNote n, float rx, float ry) {
        if (!noteBox(n, beat, box)) return false;
        float fy = n.line == Line.UPPER ? box.y : box.y + box.height;
        float cx = box.x + box.width * 0.5f;
        return Math.abs(rx - cx) * K < 14f * TOUCH && Math.abs(ry - fy) * K < 14f * TOUCH;
    }

    private void ghost(float tb, float dur, int laneId, Line line) {
        GameEngine e = pv.engine();
        LaneLayout lay = e.layout();
        NoteType type = placeType();
        boolean up = line == Line.UPPER;
        float ly = e.lineY(line);
        float pos = (tb - beat) * spd() - pv.hitShift();
        float y = up ? ly - pos : ly + pos;
        float x0, w;
        if (!type.inLane()) {
            x0 = lay.fieldLeft();
            w = lay.fieldRight() - x0;
        } else {
            int i = lay.indexOf(laneId);
            if (i < 0) return;
            w = Math.max(pv.headWidth(lay.width(i)), 30f);
            x0 = lay.centre(i) - w * 0.5f;
        }
        float h = Math.max(pv.noteHeight(), 28f), tail = dur * spd();
        float lo = up ? y - h - tail : y, hi = up ? y : y + h + tail;
        float gx = ux(x0), gy = uy(hi), gw = w * K, gh = (hi - lo) * K;
        g.dashedRect(gx, gy, gw, gh, Color.WHITE, 0.85f);
        String name = type.displayZh(longTool() && type != NoteType.HOLD);
        g.text((drag == D_PLACE ? "松开放置" : "点击放置") + name + "（" + lineZh(line) + "）",
            gx + gw * 0.5f, gy + gh + 16f, 12, Gfx.INK2, 1);
    }

    private void pressPreview(float rx, float ry, Doc.DNote hot) {
        LaneLayout lay = pv.engine().layout();
        dMarked = false;
        if (veilPage() && pressVeil(rx, ry)) return;
        if (veilTool()) {
            placeVeil(rx, ry);
            return;
        }
        Doc.DNote one = s.single();
        if (one != null && one.sustained() && handleHit(one, rx, ry)) {
            drag = D_LENGTH;
            dNote = one;
            return;
        }
        if (hot != null) {
            if (adding()) s.select(hot, true);
            else if (!s.sel.contains(hot.uid)) s.select(hot, false);
            insp.tab = Inspector.NOTE;
            drag = D_PRESS_NOTE;
            dNote = hot;
            dPressRx = rx;
            dPressRy = ry;
            return;
        }
        if (tool == T_SELECT) {
            if (!adding()) s.sel.clear();
            int i = lay.indexAtX(rx);
            if (i >= 0) s.laneSel = lay.idAt(i);
            return;
        }
        int i = lay.indexAtX(rx);
        if (i < 0 && !laneless()) return;
        drag = D_PLACE;
        dLine = s.placeLine;
        dBeat = snap(beatAtRef(dLine, ry));
        dLane = i >= 0 ? lay.idAt(i) : s.doc.lanes.get(0).id;
    }

    private void dragPreview(float rx, float ry) {
        switch (drag) {
            case D_PRESS_NOTE:
                if (Math.abs(rx - dPressRx) * K > 4f || Math.abs(ry - dPressRy) * K > 4f) startMove();
                break;
            case D_MOVE:
                moveTo(rx, ry);
                break;
            case D_LENGTH:
                lengthTo(ry);
                break;
            case D_VEIL:
                veilTo(rx, ry);
                break;
            case D_VEIL_PT:
                handleTo(rx, ry);
                break;
            default:
        }
    }

    private void lazyMark() {
        if (!dMarked) {
            s.mark();
            dMarked = true;
        }
    }

    private void startMove() {
        drag = D_MOVE;
        dApplied = 0f;
        dLaneApplied = 0;
        dSel.clear();
        dSel.addAll(s.selected());
        dLanes.clear();
        for (Doc.DNote n : dSel) dLanes.put(n.uid, s.doc.laneIndex(n.lane));
        dLane = pv.engine().layout().indexAtX(dPressRx);
    }

    private void moveTo(float rx, float ry) {
        float db = snapDelta((dist(dNote.line, ry) - dist(dNote.line, dPressRy)) / spd());
        int li = pv.engine().layout().indexAtX(rx);
        int dl = li >= 0 && dLane >= 0 ? li - dLane : dLaneApplied;
        int count = s.doc.lanes.size();
        for (Doc.DNote n : dSel) {
            Integer o = dLanes.get(n.uid);
            if (!n.inLane() || o == null || o < 0) continue;
            dl = Math.max(dl, -o);
            dl = Math.min(dl, count - 1 - o);
        }
        applyMove(db, dl);
    }

    private void applyMove(float db, int dl) {
        if (db == dApplied && dl == dLaneApplied) return;
        lazyMark();
        for (Doc.DNote n : dSel) {
            n.retime(db - dApplied);
            Integer o = dLanes.get(n.uid);
            if (n.inLane() && o != null && o >= 0) n.lane = s.doc.lanes.get(o + dl).id;
        }
        dApplied = db;
        dLaneApplied = dl;
        s.changed(true);
    }

    float snapDelta(float d) {
        float st = step();
        return st > 0f ? Math.round(d / st) * st : Math.round(d * 1000f) / 1000f;
    }

    private void lengthTo(float ry) {
        float st = step() > 0f ? step() : 0.05f;
        float end = snap(beat + dist(dNote.line, ry) / spd());
        float dur = Math.max(st, end - dNote.judgement);
        if (dur == dNote.duration) return;
        lazyMark();
        boolean auto = dNote.height > 0f && s.doc.autoTail(dNote);
        dNote.duration = dur;
        if (auto) s.doc.setAutoTail(dNote);
        s.changed(true);
    }

    private void endDrag() {
        if (drag == D_NONE) return;
        switch (drag) {
            case D_PLACE:
                if (im.released) commitPlace();
                break;
            case D_BOX:
                if (im.released) commitBox();
                break;
            case D_PAN:
                if (!dPanMoved) seekBeat(snap((float) beatAtX(im.mx)));
                else if (dPanPlay) pb.play();
                break;
            case D_PV_START:
            case D_PV_END:
                if (dPv0 != pvBeat(false) || dPv1 != pvBeat(true)) previewTo(dPv0, dPv1);
                else seekBeat(drag == D_PV_START ? dPv0 : dPv1);
                break;
            case D_MOVE:
            case D_LENGTH:
            case D_TL_MOVE:
            case D_VEIL:
            case D_VEIL_PT:
                if (dMarked) s.changed(false);
                break;
            default:
        }
        drag = D_NONE;
    }

    private void commitPlace() {
        float st = step() > 0f ? step() : 0.05f;
        float t1 = snap(beatAtRef(dLine, ryOf(im.my)));
        float dur = longTool() ? t1 - dBeat : 0f;
        if (dur < st * 0.5f) dur = longTool() ? 1f : 0f;
        NoteType type = placeType();
        Doc.DNote n = new Doc.DNote(type, dLine, dLane, dBeat);
        n.approach(Note.DEFAULT_APPROACH_BEATS, Doc.SPAWN, 1f);
        if (dur > 0f) {
            n.duration = dur;
            s.doc.setAutoTail(n);
        }
        s.doc.linkFlow(n);
        s.mark();
        s.doc.notes.add(n);
        s.select(n, false);
        s.changed(false);
        insp.tab = Inspector.NOTE;
    }

    // ================================================================== timeline

    /** @return true for a 假键 the timeline leaves out (its 假键 switch is off). */
    private boolean hiddenFake(Doc.DNote n) {
        return !timelineFakes && n.fake && n.canFake();
    }

    private float rowTop(Line l) {
        return l == Line.UPPER ? R_UP : R_LOW;
    }

    private float rowH(Line l) {
        return l == Line.UPPER ? R_UP_H : R_LOW_H;
    }

    private float markerY(Doc.DNote n) {
        float top = rowTop(n.line), h = rowH(n.line);
        if (!n.inLane()) return top + h * 0.5f;
        int li = Math.max(0, s.doc.laneIndex(n.lane));
        int lc = Math.max(1, s.doc.lanes.size());
        return top + 4f + (li + 0.5f) / lc * (h - 8f);
    }

    private Doc.DNote markerAt(float mx, float my) {
        Doc.DNote best = null;
        float bd = 7f * TOUCH;
        for (Doc.DNote n : s.doc.notes) {
            if (hiddenFake(n)) continue;
            float x = bx(n.judgement), xe = n.sustained() ? bx(n.end()) : x;
            if (xe < TX - 8f || x > TR + 8f) continue;
            float dy = n.inLane() ? Math.abs(my - markerY(n))
                : (my >= rowTop(n.line) && my <= rowTop(n.line) + rowH(n.line) ? 0f : 99f);
            float dx = mx < x ? x - mx : mx > xe ? mx - xe : 0f;
            float dd = Math.max(dx, dy) + (mx >= x - 4f && mx <= x + 4f ? 0f : 1.5f);
            if (dd < bd) {
                bd = dd;
                best = n;
            }
        }
        return best;
    }

    private void timeline() {
        g.rect(64f, TL_TOP, TR - 64f, TL_BOTTOM - TL_TOP, Gfx.BAR2);
        g.line(64f, TL_TOP, TR, TL_TOP, 1f, Gfx.LINE);
        g.text("音频", 76f, R_AUDIO + 15f, 13, Gfx.INK3);
        g.text("上线", 76f, R_UP + R_UP_H * 0.5f, 13, Gfx.INK1);
        g.text("下线", 76f, R_LOW + R_LOW_H * 0.5f, 13, Gfx.GND);
        g.text("场地", 76f, R_FIELD + R_FIELD_H * 0.5f, 13, Gfx.INK3);
        if (TAB) zoomButtons();
        g.rect(TX, R_UP, TW, R_UP_H, Gfx.ITEM, 0.45f);
        g.rect(TX, R_LOW, TW, R_LOW_H, Gfx.ITEM, 0.45f);
        im.pushClip(TX, TL_TOP, TW, TL_BOTTOM - TL_TOP);

        // beat grid
        double ppb = TW / tlSpan;
        int b0 = (int) Math.floor(tlStart), b1 = (int) Math.ceil(tlStart + tlSpan);
        float st = step();
        if (st > 0f && ppb * st >= 10.0) {
            for (int k = (int) Math.floor(tlStart / st); k * st <= tlStart + tlSpan; k++) {
                float x = bx(k * st);
                g.line(x, R_UP, x, R_FIELD, 1f, Gfx.LINE2, 0.5f);
            }
        }
        int labelEvery = 1;
        while (ppb * labelEvery < 44.0) labelEvery *= 2;
        for (int b = b0; b <= b1; b++) {
            float x = bx(b);
            boolean bar = Math.floorMod(b, 4) == 0;
            g.line(x, R_UP, x, R_RULER, 1f, Gfx.LINE, bar ? 0.9f : 0.45f);
            if (Math.floorMod(b, labelEvery) == 0) {
                g.line(x, R_RULER, x, R_RULER + 5f, 1f, Gfx.INK3);
                g.text(Integer.toString(b), x + 3f, R_RULER + 11f, 11, Gfx.INK3);
            }
        }

        // waveform
        if (!wave.ready()) g.text("正在读取波形…", TX + 12f, R_AUDIO + 15f, 12, Gfx.INK3);
        else if (s.song.audio != null) {
            for (float x = TX; x < TR; x += 3f) {
                double m0 = s.beatToMs(beatAtX(x)), m1 = s.beatToMs(beatAtX(x + 3f));
                float p = wave.peak(m0, m1);
                float hh = Math.max(1f, p * 13f);
                g.rect(x, R_AUDIO + 15f - hh, 2f, hh * 2f, Gfx.BTN_LINE, 1f);
            }
        } else g.text("这首歌没有音频文件", TX + 12f, R_AUDIO + 15f, 12, Gfx.INK3);

        // the song list's preview window (song.json) over the waveform; its edges drag (Kelvin 2026-10-21)
        if (!s.song.tutorial) {
            boolean dragging = drag == D_PV_START || drag == D_PV_END;
            float w0 = dragging ? dPv0 : pvBeat(false), w1 = dragging ? dPv1 : pvBeat(true);
            float pv0 = bx(w0), pv1 = bx(w1), h = R_UP - 4f - R_AUDIO;
            if (pv1 > TX && pv0 < TR) {
                g.rect(pv0, R_AUDIO, pv1 - pv0, h, Gfx.TIDE, 0.16f);
                for (float x : new float[]{pv0, pv1}) {
                    g.line(x, R_AUDIO, x, R_AUDIO + h, 2f, Gfx.TIDE, 0.9f);
                    g.rrect(x - 4f, R_AUDIO + h * 0.5f - 7f, 8f, 14f, 2f, Gfx.TIDE);
                }
                String l = "预览 " + Im.fmt(w0) + " – " + Im.fmt(w1) + " 拍";
                float lx = Math.max(pv0, TX) + 8f;
                g.rect(lx - 3f, R_UP - 19f, g.width(l, 11) + 6f, 14f, Gfx.BAR2, 0.85f);
                g.text(l, lx, R_UP - 12f, 11, Gfx.TIDE);
            }
        }

        // notes
        for (Doc.DNote n : s.doc.notes) {
            if (hiddenFake(n)) continue;
            float x = bx(n.judgement), xe = n.sustained() ? bx(n.end()) : x;
            if (xe < TX - 8f || x > TR + 8f) continue;
            boolean sel = s.sel.contains(n.uid);
            boolean dup = s.note(n) == null && s.buildError == null;
            boolean up = n.line == Line.UPPER;
            boolean mine = n.type == NoteType.MINE || n.type == NoteType.TIDE_MINE;
            Color c = dup ? Gfx.BAD : mine ? Gfx.MINE : n.type == NoteType.UNIVERSAL ? Gfx.WAVE : up ? UP_INK : Gfx.GND;
            if (!n.inLane()) {
                float top = rowTop(n.line) + 2f, h = rowH(n.line) - 4f;
                if (n.duration > 0f) g.rect(x, top, xe - x, h, c, 0.25f);
                g.rect(x - 1f, top, 2f, h, c);
                if (sel) g.stroke(x - 3f, top - 1f, xe - x + 6f, h + 2f, 2f, 1.5f, Color.WHITE);
                continue;
            }
            float y = markerY(n);
            if (n.sustained()) g.rect(x, y - 1.5f, xe - x, 3f, c, 0.85f);
            if (n.type == NoteType.MINE) g.stroke(x - 3f, y - 3f, 6f, 6f, 1f, 1.3f, c);
            else g.rect(x - 2.5f, y - 2.5f, 5f, 5f, c);
            if (sel) g.stroke(x - 5f, y - 5f, xe - x + 10f, 10f, 2f, 1.5f, Color.WHITE);
        }

        // field events
        bars.clear();
        Doc d = s.doc;
        for (Doc.DLane l : d.lanes) {
            if (s.laneSel != Session.NO_LANE && l.id != s.laneSel) continue;
            for (Doc.Ev ev : l.widthEv) fieldBar(ev, "宽 " + laneName(l.id), l.id);
            for (Doc.Ev ev : l.opacityEv) fieldBar(ev, "透 " + laneName(l.id), l.id);
            if (l.insert != null) fieldBar(l.insert, "插入 " + laneName(l.id), l.id);
            if (l.delete != null) fieldBar(l.delete, "删除 " + laneName(l.id), l.id);
        }
        String[] ln = {"上线", "下线", "镜头", "缩放"};
        ArrayList<java.util.List<Doc.Ev>> lists = Checks.lineLists(d);
        for (int i = 0; i < lists.size(); i++) for (Doc.Ev ev : lists.get(i)) fieldBar(ev, ln[i], LINE_BAR - i);

        // tempo markers
        Doc.Seg pinned = d.bpms.get(0);
        for (Doc.Seg sg : d.bpms) if (sg.beat <= tlStart) pinned = sg;
        for (Doc.Seg sg : d.bpms) {
            float x = bx(sg.beat);
            if (sg == pinned && sg.beat <= tlStart) x = TX + 2f;
            else if (x < TX - 70f || x > TR) continue;
            else g.dashed(x, R_AUDIO + 14f, x, R_RULER, 1f, 4f, Gfx.BPM, 0.8f);
            g.rrect(x, R_AUDIO - 2f, 62f, 16f, 3f, Gfx.BPM);
            g.text("♩ " + Im.fmt(sg.bpm), x + 6f, R_AUDIO + 6f, 11, Gfx.PRI_INK);
        }

        // playhead
        float px = bx(beat);
        g.line(px, R_AUDIO - 2f, px, R_RULER + 20f, 2f, Color.WHITE);
        g.tri(px - 6f, R_AUDIO - 4f, px + 6f, R_AUDIO - 4f, px, R_AUDIO + 4f, Color.WHITE, 1f);

        if (drag == D_BOX) {
            float x0 = Math.min(im.pressX, im.mx), y0 = Math.min(im.pressY, im.my);
            g.rect(x0, y0, Math.abs(im.mx - im.pressX), Math.abs(im.my - im.pressY), Gfx.GND, 0.12f);
            g.stroke(x0, y0, Math.abs(im.mx - im.pressX), Math.abs(im.my - im.pressY), 1f, 1f, Gfx.GND);
        }
        im.popClip();
        timelineInput();
    }

    private void fieldBar(Doc.Ev ev, String label, int id) {
        float x0 = bx(ev.start), x1 = Math.max(bx(ev.end), x0 + 3f);
        if (x1 < TX || x0 > TR) return;
        g.rrect(x0, R_FIELD + 4f, x1 - x0, R_FIELD_H - 8f, 3f, Gfx.FX, 0.45f);
        if (x1 - x0 > 36f) g.text(label, Math.max(x0, TX) + 5f, R_FIELD + R_FIELD_H * 0.5f, 10, Gfx.INK1);
        bars.add(new float[]{x0, x1, id});
    }

    private void timelineInput() {
        if (im.modalOpen()) return;
        boolean in = im.hover(TX, TL_TOP, TW, TL_BOTTOM - TL_TOP);
        if (in && im.wheel != 0f) wheelTime();
        boolean onEdge = drag == D_NONE && in && im.my < R_UP - 2f && pvEdge(im.mx) != D_NONE;
        if (onEdge || drag == D_PV_START || drag == D_PV_END) im.cursor(Cursor.SystemCursor.HorizontalResize);
        if (in && im.pressed && drag == D_NONE && !im.popupOpen()) {
            float my = im.my;
            dMarked = false;
            if (my < R_AUDIO + 14f && hitTempo(im.mx)) {
                insp.tab = Inspector.CHART;
            } else if (onEdge) {
                drag = pvEdge(im.mx);
                dPv0 = pvBeat(false);
                dPv1 = pvBeat(true);
            } else if (my < R_UP - 2f) {
                drag = D_PAN;
                dPanBeat = beat;
                dPanStart = tlStart;
                dPanMoved = false;
                dPanPlay = pb.playing();
            } else if (my >= R_RULER) {
                drag = D_SEEK;
            } else if (my < R_FIELD) {
                Doc.DNote hit = markerAt(im.mx, my);
                if (hit != null) {
                    if (adding()) s.select(hit, true);
                    else if (!s.sel.contains(hit.uid)) s.select(hit, false);
                    insp.tab = Inspector.NOTE;
                    drag = D_TL_PRESS;
                    dNote = hit;
                } else {
                    if (!adding()) s.sel.clear();
                    drag = D_BOX;
                }
            } else {
                drag = D_SEEK;
                for (float[] b : bars) {
                    if (im.mx >= b[0] - 2f && im.mx <= b[1] + 2f) {
                        int id = (int) b[2];
                        if (id > LINE_BAR) s.laneSel = id;
                        insp.tab = Inspector.FIELD;
                        insp.openLine(id);
                        drag = D_NONE;
                        break;
                    }
                }
            }
        }
        if (!im.down) return;
        if (drag == D_PAN) {
            float dx = im.mx - im.pressX;
            if (!dPanMoved && Math.abs(dx) > 3f) {
                dPanMoved = true;
                if (dPanPlay) pb.pause();
            }
            if (dPanMoved) {
                double db = dx * tlSpan / TW;
                tlStart = dPanStart - db;
                seekBeat((float) (dPanBeat - db));
            }
        } else if (drag == D_SEEK) {
            if (im.mx < TX + 24f) tlStart -= tlSpan * 0.02;
            else if (im.mx > TR - 24f) tlStart += tlSpan * 0.02;
            float b = (float) beatAtX(Math.max(TX, Math.min(TR, im.mx)));
            seekBeat(im.shift || pb.playing() ? b : snap(b));
        } else if (drag == D_PV_START || drag == D_PV_END) {
            if (im.mx < TX + 24f) tlStart -= tlSpan * 0.02;
            else if (im.mx > TR - 24f) tlStart += tlSpan * 0.02;
            float b = (float) beatAtX(Math.max(TX, Math.min(TR, im.mx)));
            b = im.shift ? Math.round(b * 1000f) / 1000f : snap(b);
            if (drag == D_PV_START) dPv0 = Math.min(b, dPv1 - 1f);
            else dPv1 = Math.max(b, dPv0 + 1f);
        } else if (drag == D_TL_PRESS && Math.abs(im.mx - im.pressX) > 4f) {
            drag = D_TL_MOVE;
            dApplied = 0f;
            dLaneApplied = 0;
            dSel.clear();
            dSel.addAll(s.selected());
            dLanes.clear();
        } else if (drag == D_TL_MOVE) {
            applyMove(snapDelta((float) (beatAtX(im.mx) - beatAtX(im.pressX))), 0);
        }
    }

    /**
     * @return D_PV_START / D_PV_END when x is within 6 px of that edge of the preview band (the nearer one), else
     * D_NONE.
     */
    private int pvEdge(float x) {
        if (s.song.tutorial) return D_NONE;
        float d0 = Math.abs(x - bx(pvBeat(false))), d1 = Math.abs(x - bx(pvBeat(true)));
        return Math.min(d0, d1) > 6f * TOUCH ? D_NONE : d1 <= d0 ? D_PV_END : D_PV_START;
    }

    private boolean hitTempo(float mx) {
        for (Doc.Seg sg : s.doc.bpms) {
            float x = sg.beat <= tlStart ? TX + 2f : bx(sg.beat);
            if (mx >= x && mx <= x + 62f) return true;
        }
        return false;
    }

    private void commitBox() {
        float x0 = Math.min(im.pressX, im.mx), x1 = Math.max(im.pressX, im.mx);
        float y0 = Math.min(im.pressY, im.my), y1 = Math.max(im.pressY, im.my);
        if (x1 - x0 < 3f && y1 - y0 < 3f) return;
        for (Doc.DNote n : s.doc.notes) {
            if (hiddenFake(n)) continue;
            float x = bx(n.judgement);
            if (x < x0 || x > x1) continue;
            boolean hit;
            if (n.inLane()) {
                float y = markerY(n);
                hit = y >= y0 && y <= y1;
            } else {
                hit = y1 >= rowTop(n.line) && y0 <= rowTop(n.line) + rowH(n.line);
            }
            if (hit) s.sel.add(n.uid);
        }
    }

    // ================================================================== bars

    private void topBar() {
        // the tablet's bar holds the same things in 1344 (no reload, no file name), its buttons 38 high
        float by = TAB ? 9f : 11f, bh = TAB ? 38f : 34f, dy = TAB ? 11f : 13f, dh = TAB ? 34f : 30f;
        g.rect(0f, 0f, Gfx.W, 56f, Gfx.BAR);
        g.line(0f, 56f, Gfx.W, 56f, 1f, Gfx.LINE);
        if (im.button("back", 14f, by, TAB ? 86f : 82f, bh, "← 曲库", Im.GHOST, TAB ? 15 : 14)) back();
        float sx = TAB ? 108f : 104f;
        g.line(sx, 14f, sx, 42f, 1f, Gfx.LINE);
        String title = s.doc.title.isEmpty() ? s.song.name() : s.doc.title;
        float x = sx + 16f + g.text(g.fit(title, 18, TAB ? 200f : 300f), sx + 16f, 28f, 18, Gfx.INK1) + 14f;
        Color dc = Gfx.DIFF[s.diff.ordinal()];
        String dl = Theme.difficultyName(s.diff);
        float bw = g.width(dl, 13) + 22f;
        g.rrect(x, 17f, bw, 22f, 4f, dc, 0.14f);
        g.stroke(x, 17f, bw, 22f, 4f, 1.6f, dc);
        g.text(dl, x + bw * 0.5f, 28f, 13, Gfx.INK1, 1);
        if (!TAB) g.text(s.file.name(), x + bw + 12f, 28f, 13, Gfx.INK3);

        float rx = TAB ? 436f : 657f, px = rx + (TAB ? 46f : 48f), pw = TAB ? 52f : 46f;
        if (im.button("rew", rx, by, bh, bh, "", Im.NORMAL)) pb.seek(0);
        g.rewindIcon(rx + bh * 0.5f, 28f, 6f, Gfx.INK1);
        if (im.button("play", px, by, pw, bh, "", Im.PRIMARY)) pb.toggle();
        g.playIcon(px + pw * 0.5f, 28f, 8f, pb.playing(), Gfx.PRI_INK);
        float tx0 = TAB ? 548f : 771f, tx = tx0 + g.text(fmtTime(pb.pos()), tx0, 28f, 18, Gfx.INK1);
        g.text("/ " + fmtTime(pb.durationMs()), tx + 12f, 28f, 14, Gfx.INK3);
        float bx = TAB ? 736f : 997f;
        g.line(bx, 14f, bx, 42f, 1f, Gfx.LINE);
        g.text("拍", bx + (TAB ? 12f : 14f), 28f, 12, Gfx.INK3);
        g.text(String.format(Locale.ROOT, "%.2f", beat), bx + (TAB ? 32f : 40f), 28f, 16, Gfx.INK1);
        g.text("BPM", bx + (TAB ? 90f : 118f), 28f, 12, Gfx.INK3);
        g.text(Im.fmt(s.doc.bpmAt(beat)), bx + (TAB ? 122f : 158f), 28f, 16, Gfx.BPM);

        if (!TAB && im.button("reload", 1336f, 13f, 100f, 30f, "重新载入", Im.NORMAL, 12)) reloadButton();
        float sn = TAB ? 906f : 1456f; // 吸附 … 未保存 keep the desktop's spacing
        g.text("吸附", sn, 28f, 12, Gfx.INK3);
        snapIdx = im.dropdown("snap", sn + 30f + (TAB ? 0f : 8f), dy, 58f, dh, SNAP_LABELS[snapIdx], SNAP_LABELS,
            snapIdx);
        float sp0 = TAB ? 1006f : 1564f;
        g.text("速度", sp0, 28f, 12, Gfx.INK3);
        int sp = im.dropdown("speed", sp0 + 30f + (TAB ? 0f : 8f), dy, 70f, dh, SPEED_LABELS[speedIdx], SPEED_LABELS,
            speedIdx);
        if (sp != speedIdx) {
            speedIdx = sp;
            pb.setSpeed(SPEEDS[sp]);
        }
        float wx = TAB ? 1116f : 1683f;
        g.line(wx, 14f, wx, 42f, 1f, Gfx.LINE);
        String w = checks.problems > 0 ? "⚠ " + checks.problems : "✓";
        Color wc = checks.problems > 0 ? Gfx.SYNC : Gfx.OK;
        if (im.click("warn", wx + 9f, 14f, g.width(w, 13) + 12f, 28f)) insp.tab = Inspector.CHART;
        if (im.hover(wx + 9f, 14f, g.width(w, 13) + 12f, 28f)) im.cursor(Cursor.SystemCursor.Hand);
        g.text(w, wx + 15f, 28f, 13, wc);
        Color sc = s.dirty ? Gfx.SYNC : Gfx.OK;
        g.disc(wx + 58f, 28f, 4f, sc);
        g.text(s.dirty ? "未保存" : "已保存", wx + 67f, 28f, 12, sc);
        if (TAB) {
            if (im.button("save", Gfx.W - 108f, by, 96f, bh, "保存", Im.NORMAL, 15)) save(null);
            return;
        }
        if (im.button("save", 1800f, 11f, 104f, 34f, "", Im.NORMAL)) save(null);
        g.text("保存", 1813f, 28f, 14, Gfx.INK1);
        g.text("Ctrl+S", 1892f, 28f, 11, Gfx.INK3, 2);
    }

    private void rail() {
        g.rect(0f, 56f, 64f, Gfx.H - 56f, Gfx.BAR);
        g.line(64f, 56f, 64f, Gfx.H, 1f, Gfx.LINE);
        for (int t = 0; t < TOOL_NAMES.length; t++) {
            float y = TOOL_Y[t];
            boolean on = tool == t;
            boolean hv = im.hover(6f, y, 52f, TOOL_H);
            if (on) g.box(6f, y, 52f, TOOL_H, 8f, Gfx.SEL, Gfx.GND);
            else if (hv) g.rrect(6f, y, 52f, TOOL_H, 8f, Gfx.BTN);
            icon(t, 32f, y + 16f);
            String n = TOOL_NAMES[t];
            g.text(n, 32f, y + 35f, n.length() > 4 ? 8 : n.length() > 3 ? 10 : 11,
                on ? Gfx.INK1 : Gfx.INK2, 1);
            if (!TAB) g.text(TOOL_KEYS[t], 52f, y + 8f, 9, Gfx.INK3, 1);
            if (hv) im.cursor(Cursor.SystemCursor.Hand);
            if (im.click(new Im.Pair("tool", t), 6f, y, 52f, TOOL_H)) setTool(t);
        }
        g.line(14f, 116f, 50f, 116f, 1f, Gfx.LINE);
        g.line(14f, 513f, 50f, 513f, 1f, Gfx.LINE);
        g.line(14f, 621f, 50f, 621f, 1f, Gfx.LINE);
        boolean up = s.placeLine == Line.UPPER;
        if (im.click("rail-line", 6f, 628f, 52f, 36f)) s.placeLine = up ? Line.LOWER : Line.UPPER;
        g.text(up ? "放上线" : "放下线", 32f, 638f, 11, up ? Gfx.INK1 : Gfx.GND, 1);
        g.text(TAB ? "点这里换" : "Tab", 32f, 654f, 10, Gfx.INK3, 1);
    }

    /**
     * The tablet's row of buttons under the preview: what the keyboard does on the desktop (Ctrl+Z / Y / C / V / A,
     * Del, Ctrl / Shift + click, ← →), then the note count and 统计.
     */
    private void touchBar() {
        float x = PX, y = AB_Y, h = AB_H;
        if (im.button("t-undo", x, y, 60f, h, "撤销", Im.NORMAL, 14)) undo();
        if (im.button("t-redo", x + 66f, y, 60f, h, "重做", Im.NORMAL, 14)) redo();
        x += 138f;
        if (im.button("t-copy", x, y, 56f, h, "复制", Im.NORMAL, 14)) copy(false);
        if (im.button("t-paste", x + 62f, y, 56f, h, "粘贴", Im.NORMAL, 14)) paste();
        if (im.button("t-del", x + 124f, y, 56f, h, "删除", Im.DANGER, 14)) {
            if (veilTab() && s.veilSel != 0) deleteVeil();
            else deleteSel();
        }
        if (im.button("t-all", x + 186f, y, 56f, h, "全选", Im.NORMAL, 14)) {
            s.sel.clear();
            for (Doc.DNote n : s.doc.notes) s.sel.add(n.uid);
        }
        if (im.button("t-multi", x + 248f, y, 56f, h, "多选", multi ? Im.ON : Im.NORMAL, 14)) multi = !multi;
        x += 316f;
        if (im.button("t-prev", x, y, 40f, h, "◀", Im.NORMAL, 14)) stepTime(-1);
        if (im.button("t-next", x + 46f, y, 40f, h, "▶", Im.NORMAL, 14)) stepTime(1);
        String n = (s.sel.isEmpty() ? "" : "已选 " + s.sel.size() + " · ") + "共 " + s.doc.notes.size();
        g.text(n, SB_X - 10f, y + h * 0.5f, 12, Gfx.INK3, 2);
        if (im.button("stats", SB_X, SB_Y, SB_W, SB_H, "统计", statsOpen ? Im.ON : Im.NORMAL, 13)) statsOpen = !statsOpen;
    }

    /** The tablet's − / + by the timeline's ruler: zoom about the playhead, as Ctrl + wheel does. */
    private void zoomButtons() {
        float f = 0f;
        if (im.button("t-out", 68f, R_RULER + 2f, 30f, 30f, "−", Im.NORMAL, 16)) f = 1.25f;
        if (im.button("t-in", 102f, R_RULER + 2f, 30f, 30f, "+", Im.NORMAL, 16)) f = 0.8f;
        if (f == 0f) return;
        double px = (beat - tlStart) / tlSpan;
        tlSpan = Math.max(2.0, Math.min(512.0, tlSpan * f));
        tlStart = beat - px * tlSpan;
    }

    private void icon(int t, float cx, float cy) {
        switch (t) {
            case T_SELECT:
                g.tri(cx - 5f, cy - 9f, cx - 5f, cy + 6f, cx + 6f, cy + 2f, Gfx.INK1, 1f);
                g.line(cx - 1f, cy + 2f, cx + 2f, cy + 8f, 2f, Gfx.INK1);
                break;
            case T_TAP:
                g.rect(cx - 12f, cy - 3f, 24f, 6f, Gfx.GND);
                g.rect(cx - 12f, cy - 4f, 24f, 1.5f, Color.WHITE);
                break;
            case T_HOLD:
                g.rect(cx - 9f, cy - 9f, 18f, 13f, Gfx.GND, 0.55f);
                g.rect(cx - 12f, cy + 3f, 24f, 5f, Gfx.GND);
                break;
            case T_LMINE:                                           // a mine with a long red body
                g.rect(cx - 9f, cy - 10f, 18f, 12f, Gfx.MINE, 0.45f);
                g.stroke(cx - 11f, cy + 1f, 22f, 8f, 3f, 2f, Gfx.MINE);
                break;
            case T_MINE:
                g.stroke(cx - 11f, cy - 5f, 22f, 10f, 3f, 2f, Gfx.MINE);
                for (int i = -1; i <= 1; i++) {
                    g.rect(cx + i * 6f - 1f, cy - 8f, 2f, 3f, Gfx.MINE);
                    g.rect(cx + i * 6f - 1f, cy + 5f, 2f, 3f, Gfx.MINE);
                }
                break;
            case T_TIDE:
            case T_TSTREAM:
            case T_TMINE:                                           // the same wave, in the mine red
            case T_TSMINE: {
                Color c = t == T_TMINE || t == T_TSMINE ? Gfx.MINE : Gfx.WAVE;
                boolean longKind = t == T_TSTREAM || t == T_TSMINE;
                if (longKind) g.rect(cx - 13f, cy - 10f, 26f, 8f, c, 0.4f);
                float wy = longKind ? cy + 3f : cy;
                for (int i = 0; i < 12; i++) {
                    float x0 = cx - 13f + i * 26f / 12f, x1 = x0 + 26f / 12f;
                    g.line(x0, wy + (float) Math.sin(i * 0.9) * 3f, x1, wy + (float) Math.sin((i + 1) * 0.9) * 3f,
                        2.5f, c);
                }
                break;
            }
            case T_VEIL:
                g.disc(cx, cy, 10f, Gfx.TIDE, 0.18f);
                g.circle(cx, cy, 10f, 1.6f, Gfx.TIDE);
                break;
            default:
                for (int i = 3; i >= 1; i--) g.disc(cx, cy, 4f + i * 3f, Gfx.FX, 0.16f);
                g.disc(cx, cy, 5f, Gfx.FX, 0.6f);
                break;
        }
    }

    private void statusBar() {
        g.rect(65f, TL_BOTTOM, TR - 65f, Gfx.H - TL_BOTTOM, Gfx.BAR);
        g.line(64f, TL_BOTTOM, TR, TL_BOTTOM, 1f, Gfx.LINE2);
        float x = 80f;
        x = keycap(x, "空格", "播放");
        x = keycap(x, "点击", "放置");
        x = keycap(x, "拖", "长按/移动");
        x = keycap(x, "滚轮", "走时间");
        x = keycap(x, "Ctrl+滚轮", "缩放");
        x = keycap(x, "Ctrl+C/X/V/Z", "");
        x = keycap(x, "Del", "删除");
        keycap(x, "Tab", "上/下线");
        String right;
        Doc.DLane l = s.doc.lane(s.laneSel);
        if (insp.tab == Inspector.FIELD && l != null) {
            right = "轨道 " + laneName(l.id) + " · 宽度事件 " + l.widthEv.size() + " 段";
        } else {
            right = "共 " + s.doc.notes.size() + " 个音符" + (s.sel.isEmpty() ? "" : " · 已选 " + s.sel.size());
        }
        g.text(right, TR - 74f, TL_BOTTOM + 14f, 12, Gfx.INK3, 2);
        if (im.button("stats", SB_X, SB_Y, SB_W, SB_H, "统计", statsOpen ? Im.ON : Im.NORMAL, 12)) {
            statsOpen = !statsOpen;
        }
    }

    private float keycap(float x, String key, String label) {
        float kw = g.width(key, 12) + 14f;
        g.box(x, TL_BOTTOM + 5f, kw, 18f, 4f, Gfx.BTN, Gfx.BTN_LINE);
        g.text(key, x + kw * 0.5f, TL_BOTTOM + 14f, 12, Gfx.INK2, 1);
        float lw = label.isEmpty() ? 0f : g.text(label, x + kw + 6f, TL_BOTTOM + 14f, 12, Gfx.INK3);
        return x + kw + 6f + lw + 18f;
    }
}
