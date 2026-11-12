package org.kelvinizer.charter;

import com.badlogic.gdx.Input;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Cursor;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Matrix4;
import org.kelvinizer.elements.Fn;
import org.kelvinizer.elements.Fn3;
import org.kelvinizer.elements.Group7;
import org.kelvinizer.elements.Key7;
import org.kelvinizer.elements.KeyKind;
import org.kelvinizer.elements.Lane7;
import org.kelvinizer.elements.Seg;
import org.kelvinizer.elements.Volume7;
import org.kelvinizer.gameplay.CharterPreview;
import org.kelvinizer.gameplay.Field;
import org.kelvinizer.library.Difficulty;
import org.kelvinizer.ui.Theme;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Locale;

/**
 * 谱面编辑器主界面：顶栏（播放、吸附、保存）、左边工具栏、中间的预览、下面的时间轴和右边的属性面板（谱面格式 v7）。
 * The editor screen (chart format v7, Kelvin 2026-11-09 18:44, on the old layout): the top bar (transport, snap,
 * speed, save), the tool rail, the preview where keys are placed and dragged along their lanes, lanes picked and
 * moved and veils and curtains placed, the timeline (waveform, tempo markers, 天面 and 底面 key rows, the picked
 * thing's function segments), the status bar and the {@link Inspector} on the right. The preview is drawn with the
 * editor's own 3D view ({@link Scene}: the chart's camera P, turned by the editor's own view when wanted); the game's
 * picture goes under it once the game reads v7. On a tablet ({@link Gfx#TABLET}) the same parts sit on the 1344 × 840
 * canvas, with a row of buttons under the preview in place of the keyboard shortcuts.
 */
final class EditorScreen implements CharterApp.View {
    // ---- layout (top-left based on the 1920 × 1080 canvas, or the tablet's 1344 × 840)
    private static final boolean TAB = Gfx.TABLET;
    /** The preview at its biggest (the timeline then as short as it gets): the game's 16 : 10 frame (1920 × 1200). */
    private static final float PH_MAX = TAB ? 465f : 801f, PW_MAX = PH_MAX * 1.6f, PX0 = TAB ? 72f : 92f;
    static final float PY = TAB ? 64f : 68f;
    /**
     * The timeline: its names end at {@link #TX}; it and the status bar end at {@link #TR}, where the inspector
     * starts.
     */
    static final float TX = TAB ? 260f : 330f, TR = Math.round(PX0 + PW_MAX) + (TAB ? 10f : 28f), TW = TR - TX,
        TL_BOTTOM = TAB ? 836f : 1052f;
    /** Between the preview and the timeline: a gap, on a tablet with the touch bar in it. */
    private static final float UNDER_PV = TAB ? 60f : 13f;
    /**
     * Where the line between the preview and the timeline can be dragged (Kelvin 2026-11-11 16:15「我要它有点像3D动画制作
     * 的那种」, SPEC §2): from a preview 360 (tablet 200) tall to the biggest one; at first as in the mockup.
     */
    private static final float TOP_MIN = PY + (TAB ? 200f : 360f) + UNDER_PV, TOP_MAX = PY + PH_MAX + UNDER_PV,
        TOP_FIRST = TAB ? TOP_MAX : 760f;
    /** The timeline's ruler and 音频 strip, above its rows ({@link Layers}). */
    static final float RULER_H = TAB ? 30f : 20f, AUDIO_H = TAB ? 34f : 30f;
    /**
     * The preview now: as tall as the line leaves room for, 16 : 10, at the left of its column (Kelvin 2026-11-11 21:10
     * 「16：10好像会导致中间显示区域有留白。把其中一些元素挪过去」: the room left over is one strip on its right), at scale K.
     */
    static float PH, PW, PX, K;
    /** The timeline's top (the line), its ruler, its 音频 strip and its rows (总览 first). */
    static float TL_TOP, R_RULER, R_AUDIO, R_ROWS;
    /** The tablet's touch bar under the preview (undo, copy, delete, …: what the keyboard does on the desktop). */
    private static float AB_Y;
    private static final float AB_H = 44f;
    /** Hit distances grow this much for a finger. */
    static final float TOUCH = TAB ? 2.5f : 1f;
    /** 统计: in the status bar, or at the end of the tablet's touch bar. */
    private static final float SB_X = TAB ? TR - 66f : TR - 64f, SB_W = 56f, SB_H = TAB ? AB_H : 20f;
    private static float SB_Y;

    static {
        layout(TOP_FIRST);
    }

    /** Lays the preview and the timeline out around the line at {@code top} (kept where it can go). */
    static void layout(float top) {
        TL_TOP = Math.round(Math.max(TOP_MIN, Math.min(TOP_MAX, top)));
        PH = TL_TOP - UNDER_PV - PY;
        PW = PH * 1.6f;
        PX = PX0;
        K = PW / 1920f;
        AB_Y = PY + PH + 8f;
        SB_Y = TAB ? AB_Y : TL_BOTTOM + 4f;
        float sx = PX + PW, sw = TR - sx, both = CUBE_BOX + 8f + PV_BTN_W;
        SIDE = sw >= Math.max(CUBE_BOX, PV_BTN_W) + 24f;
        if (!SIDE) {                                                    // too narrow: over the preview's top right
            PV_COL_X = PX + PW - 12f - PV_BTN_W;
            CUBE_X = PV_COL_X - 8f - CUBE_BOX;
            PV_COL_Y = CUBE_Y = PY + 12f;
        } else if (sw >= both + 24f) {                                  // the cube, then the buttons beside it
            CUBE_X = sx + (sw - both) * 0.5f;
            PV_COL_X = CUBE_X + CUBE_BOX + 8f;
            PV_COL_Y = CUBE_Y = PY;
        } else {                                                        // the cube over the buttons
            CUBE_X = sx + (sw - CUBE_BOX) * 0.5f;
            PV_COL_X = sx + (sw - PV_BTN_W) * 0.5f;
            CUBE_Y = PY;
            PV_COL_Y = PY + CUBE_BOX + 40f;
        }
        R_RULER = TL_TOP + 6f;
        R_AUDIO = R_RULER + RULER_H + 2f;
        R_ROWS = R_AUDIO + AUDIO_H + 2f;
    }

    /**
     * Tools: select (N), the eight key kinds (keys 1–8), 漩涡 (9) and 水帘 (0); Drag (G) sits under 水流 on the rail;
     * 方块 (B) is the 场景 page's ({@link SceneTools}).
     */
    static final int T_SELECT = 0, T_TAP = 1, T_HOLD = 2, T_MINE = 3, T_LMINE = 4, T_TIDE = 5, T_TSTREAM = 6,
        T_TMINE = 7, T_TSMINE = 8, T_VEIL = 9, T_CURTAIN = 10, T_DRAG = 11, T_BOX = 12;
    private static final String[] TOOL_NAMES = {"选择", "水滴", "水流", "水雷", "轨道水雷", "浪花", "潮汐", "浪花水雷",
        "潮汐水雷", "漩涡", "水帘", "Drag"};
    private static final String[] TOOL_KEYS = {"N", "1", "2", "3", "4", "5", "6", "7", "8", "9", "0", "G"};
    /** The key kind each key tool places. */
    static final KeyKind[] TOOL_KIND = {null, KeyKind.TAP, KeyKind.STREAM, KeyKind.MINE, KeyKind.STREAM_MINE,
        KeyKind.WAVE, KeyKind.TIDE, KeyKind.WAVE_MINE, KeyKind.TIDE_MINE, null, null, KeyKind.DRAG};
    /** The rail from the top: the tools in this order, at these heights (a gap before 水滴 and before 漩涡). */
    private static final int[] TOOL_ORDER = {T_SELECT, T_TAP, T_HOLD, T_DRAG, T_MINE, T_LMINE, T_TIDE, T_TSTREAM,
        T_TMINE, T_TSMINE, T_VEIL, T_CURTAIN};
    private static final float[] TOOL_Y = {64f, 124f, 172f, 220f, 268f, 316f, 364f, 412f, 460f, 508f, 568f, 616f};
    private static final float TOOL_H = 46f;
    static final int[] SNAPS = {1, 2, 3, 4, 6, 8, 12, 16, 0};
    static final String[] SNAP_LABELS = {"1/1", "1/2", "1/3", "1/4", "1/6", "1/8", "1/12", "1/16", "关"};
    static final float[] SPEEDS = {0.25f, 0.5f, 0.75f, 1f};
    static final String[] SPEED_LABELS = {"0.25×", "0.5×", "0.75×", "1.0×"};
    static final Color PV_BG = Gfx.c(0x050920);
    private static final Color UP_INK = Gfx.c(0xE6ECFF);
    private static final float AUTOSAVE_S = 120f;
    /** How thick a key is drawn along its lane, and how long a new long key is when it was not dragged out (beats). */
    private static final float KEY_T = 30f;
    static final float LONG_DEFAULT = 1f;
    /** A new veil or curtain lasts this many beats. */
    private static final float VOL_BEATS = 4f;

    /** Copied keys; static so they can be pasted into another difficulty. */
    private static final ArrayList<Doc.DKey> clipboard = new ArrayList<>();
    /** Copied lanes (操作 F) with their names, the keys bound to them in {@link #clipboard}; empty for keys alone. */
    private static final ArrayList<Lane7> clipLanes = new ArrayList<>();
    private static final HashMap<Integer, String> clipNames = new HashMap<>();

    final CharterApp app;
    final Gfx g;
    final Im im;
    Session s;
    final Playback pb;
    final Waveform wave;
    /** 声部分析 (Kelvin 2026-11-07 19:51): made on the first pick in the 音频 row; voice -1 = the whole song. */
    VoiceSplit voices;
    int voice = -1, wantVoice = -1;
    static final String[] VOICE_ITEMS = {"原曲", "鼓", "低音", "旋律"};
    private static final Color[] VOICE_COLOURS = {Gfx.BPM, Gfx.FX, Gfx.TIDE};
    final CharterPreview pv;
    final Checks checks = new Checks();
    final Inspector insp;
    /** 曲线窗口: the open function's segments, in place of the timeline's rows. */
    final CurvePanel curves;
    /** The timeline's layers and rows (Kelvin 2026-11-11 16:39). */
    final Layers layers;
    /** The 场景 page's tools: 方块, 阵列, 镜像, the handle, the grid, models. */
    final SceneTools scene;
    /** The line between the preview and the timeline is being dragged. */
    private boolean splitting;
    /** The editor's 3D view of the chart this frame. */
    final Scene sc = new Scene();
    int tool = T_SELECT;
    /** The tool N goes back to from 选择: the last other tool picked, 水滴 at first. */
    private int lastTool = T_TAP;
    int snapIdx = 3, speedIdx = 3;
    /** The editor's marks over the preview; off is 实机, the game's HUD and hit effects (Kelvin 2026-10-27). */
    boolean overlays = true;
    /**
     * The edit view's own turn about the origin on top of the chart's camera, degrees (Kelvin 2026-11-08 01:00):
     * Ctrl+Alt + a drag, two fingers, or a drag on 「拖这里转视角」; 归位 = 0, 0. Not saved; 实机 is the chart's own.
     */
    float orbitYaw, orbitPitch;
    /** Ctrl+Alt + the wheel: the editor's own zoom, 1 = the chart's. */
    float orbitZoom = 1f;
    /** 六个基本朝向 (Kelvin 2026-11-09 04:54): the edit view from the front, back, left, right, above or below; −1 none. */
    int viewPreset = -1;
    /**
     * The edit view's own move (Kelvin 2026-11-10 20:26「ctrl+alt+箭头可以前后左右移动camera位置」): right, up and
     * forward along the view, reference units, after the turn; what it looks at goes along, so the view keeps its
     * direction. Ctrl+Alt + ← → / PageUp PageDown / ↑ ↓, Shift a longer step, as the chart notes' preview. Not saved.
     */
    final float[] viewMove = new float[3];
    static final float MOVE_STEP = 400f, MOVE_FAR = 2000f;
    private static final int[] MOVE_KEYS = {Input.Keys.RIGHT, Input.Keys.LEFT, Input.Keys.PAGE_UP,
        Input.Keys.PAGE_DOWN, Input.Keys.UP, Input.Keys.DOWN};
    private float orbitX, orbitY;
    private boolean altPress, turning, twoTurning;
    static final float ORBIT_DEG = 0.25f;
    /** Shows the 坐标系 (the world's x, y and z axes from the origin) over the preview. */
    boolean axes = true;
    /**
     * 「显示：假键」 (Kelvin 2026-11-09 20:37「假键的逻辑倒是可以保留。在显示坐标系下面可以再加一行」): 假键 on the
     * timeline and picked in the preview; off by default, as the old 时间轴假键. The game draws them either way.
     */
    boolean showFake;
    /**
     * 「显示：编号 / 名称」 (Kelvin 2026-11-10 04:54「有点乱，显示的时候可以选择显示编号或者名称吧」): lanes labelled by
     * their 编号 instead of their names, in the preview and in the lane chips; names stay in the lane card.
     */
    boolean laneIds;
    private final Color fakeInk = new Color();
    static final Color AX_X = Gfx.c(0xFF6B81), AX_Y = Gfx.c(0x7BE0A0), AX_Z = Gfx.c(0x6FB8FF);
    /** Preview sync hint, in the order of the game's Settings.syncHint values. */
    static final String[] SYNC_LABELS = {"sync：高亮", "sync：增强", "sync：关"};
    static final float PV_BTN_W = 118f, PV_BTN_GAP = 6f;
    /**
     * 视角方块 (Kelvin 2026-11-09 20:02): its square in the preview's top-right corner, its half edge on screen and its
     * bevel (20:35「稍微做的圆滑一点点」), a fraction of the half edge: the edges and corners between the faces.
     */
    static final float CUBE_BOX = 124f, CUBE_HALF = 34f, CUBE_BEVEL = 0.26f;
    /**
     * The button column (实机, 镜头节点, sync, 坐标系, 假键, 名称) and the view cube left of it (Kelvin 2026-11-10 02:15
     * 「右上角的那些实机那一列5个按钮和那个立方体的位置交换」): in the strip right of the preview ({@link #SIDE}; the
     * cube over the buttons when the strip is too narrow for both side by side, the preview's 拍 and 视角 under them),
     * else over the preview's top right as before (the strip too narrow even for one).
     */
    static float PV_COL_X, PV_COL_Y, CUBE_X, CUBE_Y;
    static boolean SIDE;
    private static final String[] CUBE_LABELS = {"前", "后", "左", "右", "上", "下"};
    private final float[] cubePts = new float[8];
    private final double[] cubeW = new double[3];
    private boolean cubeHeld, cubeTurning;
    /** What a press on the cube hit: a view ({@link Scene#VIEW_DIR}, 0–25), an arrow (100 + face), or nothing (−1). */
    private int cubePress = -1;
    private float cubePressX, cubePressY;
    /** Timeline view: first visible beat and visible span in beats. */
    double tlStart = -4.0, tlSpan = 16.0;
    /** Playhead beat this frame. */
    float beat;
    private float autosaveT, wheelAcc, diskCheckT;
    /** The tablet's 多选 button: a tap adds to (or takes from) the selection, as Ctrl / Shift + click does. */
    private boolean multi;
    /** The statistics panel above the status bar's 统计 button. */
    final StatsPanel stats = new StatsPanel();
    boolean statsOpen;
    private static final float STATS_X = (TAB ? SB_X : TR) - 8f - StatsPanel.W, STATS_Y = TL_BOTTOM - 6f - StatsPanel.H;

    // ---- this frame's lanes in the preview: their frames at the playhead, by index of the doc's lanes
    private float[][] lfs = new float[0][];
    private final double[] dir = new double[3], p3 = new double[3], q3 = new double[3];
    private final float[] pa = new float[2], pb2 = new float[2], quad = new float[16], hit2 = new float[2];

    // ---- drag state
    private static final int D_NONE = 0, D_PLACE = 1, D_PRESS_KEY = 2, D_MOVE_KEY = 3, D_LENGTH = 4, D_SEEK = 5,
        D_BOX = 6, D_TL_PRESS = 7, D_TL_MOVE = 8, D_PAN = 9, D_PV_START = 10, D_PV_END = 11, D_LANE = 12, D_VOL = 13,
        D_ORBIT = 14, D_GROUP = 15;
    private int drag;
    private boolean dMarked;
    /** Audio-row grab: playhead and view at the press, whether it moved, whether it was playing. */
    private double dPanBeat, dPanStart;
    private boolean dPanMoved, dPanPlay;
    /** Preview-band edge drag: the window in beats while dragging (written to song.json on release). */
    private float dPv0, dPv1;
    /** The key pressed or being placed, its lane's index, where the press was along it (up), the beats moved so far. */
    private Doc.DKey dKey;
    private int dLaneIx = -1;
    private float dUp0, dApplied;
    private final ArrayList<Doc.DKey> dSel = new ArrayList<>();
    /** A lane or volume being moved: its id or uid, the facing-plane point of the press, its centre at the press. */
    private int dId;
    private final double[] dGrab = new double[3];
    private final float[] dAt0 = new float[3];
    /** What a lane or volume drag moves from (a grouped lane's origin in its group's axes), and that group's pose. */
    private final float[] dV0 = new float[3];
    private Group7.Pose dPose;
    /** The last press on the preview (a second one soon after, near it: a double click), and the picks last seen. */
    long lastPress;
    private float lastPressX, lastPressY;
    private int seenLaneSel = Session.NO_LANE, seenGroupSel;
    private static final long DOUBLE_MS = 400L;
    private boolean dUnpick;

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
        insp = new Inspector(this);
        curves = new CurvePanel(this);
        layers = new Layers(this);
        scene = new SceneTools(this);
        insp.tab = Inspector.NOTE;
    }

    // ================================================================== helpers

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

    /** @return {@code d} beats snapped as a distance (a move) */
    float snapDelta(float d) {
        float st = step();
        return st > 0f ? Math.round(d / st) * st : Math.round(d * 1000f) / 1000f;
    }

    void seekBeat(float b) {
        pb.seek(s.beatToMs(b));
    }

    static String fmtTime(double ms) {
        boolean neg = ms < 0;
        long t = Math.round(Math.abs(ms));
        return (neg ? "-" : "") + String.format(Locale.ROOT, "%02d:%02d.%03d", t / 60000, (t / 1000) % 60, t % 1000);
    }

    static float round2(float v) {
        return Math.round(v * 100f) / 100f;
    }

    // ================================================================== frame

    @Override
    public void frame(float dt) {
        if (voices != null && wantVoice != voice) followVoice();
        pb.update();
        if (!Double.isNaN(listenTo) && (!pb.playing() || pb.pos() >= listenTo)) {
            pb.pause();
            listenTo = Double.NaN;
        }
        s.update();
        checks.update(s);
        double pos = pb.pos();
        beat = (float) s.msToBeat(pos);
        im.slashBeat = snap(beat);                                  // what "/" means in a 拍 field
        if (pv.chart() != s.runtime) pv.setChart(s.runtime);
        pv.setLive(!overlays, s.diff);
        pv.seek(pos, dt, pb.playing());
        follow();
        syncPicks();
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
        curves.draw();
        topBar();
        if (statsOpen) {
            stats.draw(g, s, STATS_X, STATS_Y);
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

    /** @return the preview window's start or end in beats: song.json's beat, else its ms on this tempo. */
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

    /** Ctrl+Alt + an arrow or PageUp / PageDown moves the edit view ({@link #viewMove}); 0 归位. @return taken */
    private boolean moveView() {
        for (int i = 0; i < MOVE_KEYS.length; i++) {
            if (!im.key(MOVE_KEYS[i])) continue;
            float st = im.shift ? MOVE_FAR : MOVE_STEP;
            viewMove[i / 2] += i % 2 == 0 ? st : -st;
            return true;
        }
        if (!im.key(Input.Keys.NUM_0) && !im.key(Input.Keys.NUMPAD_0)) return false;
        homeView();
        return true;
    }

    /** 归位: the edit view back to the chart's camera (no turn, zoom, plain view or move). */
    void homeView() {
        orbitYaw = orbitPitch = 0f;
        orbitZoom = 1f;
        viewPreset = -1;
        Arrays.fill(viewMove, 0f);
    }

    /** Keeps the playhead inside the timeline view. */
    private void follow() {
        if (pb.playing() && beat > tlStart + tlSpan * 0.85) tlStart = beat - tlSpan * 0.15;
        if (beat < tlStart || beat > tlStart + tlSpan) tlStart = beat - tlSpan * 0.25;
    }

    private void keys() {
        if (im.modalOpen() || im.textFocus()) return;
        if (im.ctrl) {
            if (im.alt && overlays && moveView()) return;
            if (im.key(Input.Keys.S)) save(null);
            else if (im.key(Input.Keys.Z)) {
                if (im.shift) redo();
                else undo();
            } else if (im.key(Input.Keys.Y)) redo();
            else if (im.key(Input.Keys.C)) copy(false);
            else if (im.key(Input.Keys.X)) copy(true);
            else if (im.key(Input.Keys.V)) paste();
            else if (im.key(Input.Keys.A)) selectAll();
            else if (im.key(Input.Keys.G)) makeGroup();
            else if (im.key(Input.Keys.D) && insp.tab == Inspector.SCENE) scene.duplicate();
            else if (!im.alt && im.key(Input.Keys.UP)) toFace(true);
            else if (!im.alt && im.key(Input.Keys.DOWN)) toFace(false);
            return;
        }
        if (im.key(Input.Keys.SPACE)) pb.toggle();
        if (im.key(Input.Keys.N)) setTool(tool != T_SELECT ? T_SELECT : insp.tab == Inspector.SCENE ? T_BOX : lastTool);
        if (im.key(Input.Keys.B)) setTool(T_BOX);
        if (scene.arrayOn && (im.key(Input.Keys.ENTER) || im.key(Input.Keys.NUMPAD_ENTER))) scene.applyArray();
        for (int t = T_TAP; t <= T_VEIL; t++) {
            if (im.key(Input.Keys.NUM_0 + t) || im.key(Input.Keys.NUMPAD_0 + t)) setTool(t);
        }
        if (im.key(Input.Keys.NUM_0) || im.key(Input.Keys.NUMPAD_0)) setTool(T_CURTAIN);
        if (im.key(Input.Keys.G)) setTool(T_DRAG);
        if (im.key(Input.Keys.TAB)) s.placeSky = !s.placeSky;
        if (im.key(Input.Keys.K)) keyframe();
        if (im.key(Input.Keys.FORWARD_DEL) || im.key(Input.Keys.DEL)) {
            if (curves.on && curves.focus && curves.deleteKey()) return;
            if (insp.tab == Inspector.VOLUME && s.volSel != 0) deleteVol();
            else deleteSel();
        }
        if (im.key(Input.Keys.M)) {
            if (im.shift) flipFace();
            else mirror();
        }
        if (im.key(Input.Keys.D)) insp.applyFall(s, s.selected(), 0);
        if (im.key(Input.Keys.ESCAPE) && scene.arrayOn) scene.arrayOn = false;
        else if (im.key(Input.Keys.ESCAPE) && layers.stripOn()) layers.closeStrip();
        else if (im.key(Input.Keys.ESCAPE) && statsOpen) statsOpen = false;
        else if (im.key(Input.Keys.ESCAPE) && !im.popupOpen()) {
            if (!s.sel.isEmpty()) s.sel.clear();
            else if (s.groupIn != 0) leaveGroup();
            else tool = T_SELECT;
        }
        if (im.alt) {                                               // Alt+← →: the selected keys a lane across
            if (im.key(Input.Keys.LEFT)) moveKeysLane(-1);
            if (im.key(Input.Keys.RIGHT)) moveKeysLane(1);
        } else {
            if (im.key(Input.Keys.LEFT)) stepTime(-1);
            if (im.key(Input.Keys.RIGHT)) stepTime(1);
            if (im.key(Input.Keys.UP)) nudge(1);
            if (im.key(Input.Keys.DOWN)) nudge(-1);
        }
        if (im.key(Input.Keys.HOME)) pb.seek(0);
        if (im.key(Input.Keys.BACK)) back();
    }

    void setTool(int t) {
        if (t != T_SELECT && t != T_BOX) lastTool = t;
        tool = t;
        if (t == T_VEIL || t == T_CURTAIN) insp.tab = Inspector.VOLUME;
        else if (t == T_BOX) insp.tab = Inspector.SCENE;
        else if (t != T_SELECT && insp.tab == Inspector.SCENE) insp.tab = Inspector.NOTE;     // where key tools are
    }

    /** Writes the crash-safety copy now (the app goes to the background, where Android may close it). */
    void autosaveNow() {
        if (s.dirty) s.lib.autosave(s.song, s.diff, s.doc.toJson());
    }

    /** Ctrl or Shift held, or the tablet's 多选 on: a click adds to the selection. */
    boolean adding() {
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

    void wheelTime() {
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

    void reload() {
        try {
            s.reload();
            autosaveT = 0f;
            im.toast("已重新载入 " + s.file.name());
        } catch (Exception e) {
            im.open(new Im.Modal("载入失败", String.valueOf(e.getMessage()), new String[]{"好"}, (b, t) -> { }));
        }
    }

    /** @return whether 复制 takes the picked lane with its keys (操作 F): the 轨道 page shows it, or no key is selected */
    boolean lanesPicked() {
        return s.doc.lane(s.laneSel) != null && (insp.tab == Inspector.LANE || s.sel.isEmpty());
    }

    void copy(boolean cut) {
        if (lanesPicked()) {
            copyLane(cut);
            return;
        }
        ArrayList<Doc.DKey> sel = s.selected();
        if (sel.isEmpty()) return;
        clipboard.clear();
        clipLanes.clear();
        clipNames.clear();
        for (Doc.DKey k : sel) clipboard.add(k.dup());
        if (cut) deleteSel();
        im.toast((cut ? "已剪切 " : "已复制 ") + sel.size() + " 个键");
    }

    /** 复制轨道 (Kelvin 2026-11-09 17:09): the picked lane as it is and the keys that go with it; 剪切 takes them out. */
    private void copyLane(boolean cut) {
        Doc d = s.doc;
        Lane7 l = d.lane(s.laneSel);
        ArrayList<Doc.DKey> bound = d.boundTo(l.id);
        clipboard.clear();
        clipLanes.clear();
        clipNames.clear();
        clipLanes.add(Doc.copyLane(l));
        if (d.names.containsKey(l.id)) clipNames.put(l.id, d.names.get(l.id));
        for (Doc.DKey k : bound) clipboard.add(k.dup());
        String what = "轨道「" + d.laneLabel(l.id) + "」和上面的 " + bound.size() + " 个键";
        if (cut) {
            s.mark();
            d.removeLane(l.id);
            s.laneSel = Session.NO_LANE;
            s.changed(false);
        }
        im.toast((cut ? "已剪切" : "已复制") + what);
    }

    /** @return the snap line at or after the playhead: where a paste puts the earliest thing */
    private float pasteAt() {
        float at = snap(beat);
        if (at < beat - 1e-4f && step() > 0f) at += step();
        return at;
    }

    /** Pastes the copied keys so that the earliest is judged at the snap line at (or after) the playhead. */
    void paste() {
        if (!clipLanes.isEmpty()) {
            pasteLanes();
            return;
        }
        if (clipboard.isEmpty()) return;
        Doc d = s.doc;
        if (d.lanes.isEmpty()) {
            im.toast("还没有轨道，键没地方贴");
            return;
        }
        float base = Float.MAX_VALUE;
        for (Doc.DKey k : clipboard) if (Float.isFinite(k.judge())) base = Math.min(base, k.judge());
        float by = base == Float.MAX_VALUE ? 0f : pasteAt() - base;
        s.mark();
        s.sel.clear();
        for (Doc.DKey c : clipboard) {
            Doc.DKey k = c.dup();
            Doc.shiftKey(k.k, by);
            if (k.k.kind.spans()) {
                int n = 0;
                for (int id : k.k.lanes) if (d.lane(id) != null) n++;
                int[] keep = new int[Math.max(1, n)];
                n = 0;
                for (int id : k.k.lanes) if (d.lane(id) != null) keep[n++] = id;
                if (n == 0) keep[0] = d.lanes.get(d.lanes.size() / 2).id;
                k.k.lanes = keep;
                k.k.lane = keep[0];
            } else if (d.lane(k.k.lane) == null) k.k.lane = d.lanes.get(d.lanes.size() / 2).id;
            d.keys.add(k);
            s.sel.add(k.uid);
        }
        d.sortKeys();
        s.changed(false);
        insp.tab = Inspector.NOTE;
        im.toast("已粘贴 " + s.sel.size() + " 个键");
    }

    /**
     * Pastes the copied lane as a new one with its keys, at the outer end as 新建轨道 adds one; everything moves so
     * that the earliest key lands where {@link #paste} puts it (without keys its 出现 does). The new lane is picked.
     */
    private void pasteLanes() {
        Doc d = s.doc;
        Lane7 src = clipLanes.get(0);
        float base = Float.MAX_VALUE;
        for (Doc.DKey k : clipboard) if (Float.isFinite(k.judge())) base = Math.min(base, k.judge());
        if (base == Float.MAX_VALUE && Float.isFinite(Doc.appearBeat(src))) base = Doc.appearBeat(src);
        float by = base == Float.MAX_VALUE ? 0f : pasteAt() - base;
        s.mark();
        float at = Float.isFinite(Doc.appearBeat(src)) ? Doc.appearBeat(src) + by : pasteAt();
        boolean right = d.addsRight(at);
        Lane7 l = Doc.copyLane(src);
        l.id = d.nextLaneId();
        l.g = 0;                                                        // the outer end is in the chart's axes
        for (Fn f : Doc.fns(l)) Doc.shift(f, by);
        Doc.offset(l.v.x, d.outerX(right, at) - Doc.xAt(l, at));
        if (right) d.lanes.add(l);
        else d.lanes.add(0, l);
        String name = clipNames.get(src.id);
        String stem = (name != null ? name : "轨道").replaceAll("[0-9]+$", "");
        if (stem.isEmpty() || stem.startsWith("-")) stem = "轨道";
        for (int i = 2; ; i++) {
            if (d.laneCalled(stem + i, null) == null) {
                d.names.put(l.id, stem + i);
                break;
            }
        }
        int keys = 0;
        for (Doc.DKey c : clipboard) {
            Doc.DKey k = c.dup();
            Doc.shiftKey(k.k, by);
            if (k.k.kind.spans()) k.k.lanes = new int[]{l.id};
            k.k.lane = l.id;
            d.keys.add(k);
            keys++;
        }
        d.sortKeys();
        s.sel.clear();
        s.laneSel = l.id;
        insp.tab = Inspector.LANE;
        s.changed(false);
        im.toast("已粘贴轨道「" + d.laneLabel(l.id) + "」和上面的 " + keys + " 个键");
    }

    // ================================================================== groups (组, chart 2.1)

    /**
     * Keeps the 成组 picks with what is picked: a lane or group picked some other way (its card, the timeline, the
     * checks) is then picked alone.
     */
    private void syncPicks() {
        Doc d = s.doc;
        if (d.group(s.groupSel) == null) s.groupSel = 0;
        if (d.group(s.groupIn) == null) s.groupIn = 0;
        if (s.laneSel != seenLaneSel && s.laneSel != Session.NO_LANE && !s.pickLanes.contains(s.laneSel)) {
            s.pickLanes.clear();
            s.pickGroups.clear();
            s.groupSel = 0;
            s.pickLanes.add(s.laneSel);
        } else if (s.groupSel != seenGroupSel && s.groupSel != 0 && !s.pickGroups.contains(s.groupSel)) {
            s.pickLanes.clear();
            s.pickGroups.clear();
            s.laneSel = Session.NO_LANE;
            s.pickGroups.add(s.groupSel);
        }
        if (s.laneSel == Session.NO_LANE && s.groupSel == 0) {
            s.pickLanes.clear();
            s.pickGroups.clear();
        }
        seenLaneSel = s.laneSel;
        seenGroupSel = s.groupSel;
    }

    /** Picks lane {@code id} (its card); {@code add}: one more for 成组, again: one less. */
    void pickLane(int id, boolean add) {
        if (!add) {
            s.pickLanes.clear();
            s.pickGroups.clear();
            s.groupSel = 0;
        }
        if (add && s.pickLanes.remove(id)) {
            s.laneSel = Session.NO_LANE;
            for (int x : s.pickLanes) s.laneSel = x;                    // the last one left
        } else {
            s.pickLanes.add(id);
            s.laneSel = id;
        }
        seenLaneSel = s.laneSel;
        seenGroupSel = s.groupSel;
        if (insp.tab != Inspector.SCENE) insp.tab = Inspector.LANE;     // on the 场景 page its card shows there
    }

    /** Picks group {@code id} (its card on the 场景 page); {@code add}: one more for 成组, again: one less. */
    void pickGroup(int id, boolean add) {
        if (!add) {
            s.pickLanes.clear();
            s.pickGroups.clear();
            s.laneSel = Session.NO_LANE;
        }
        if (add && s.pickGroups.remove(id)) {
            s.groupSel = 0;
            for (int x : s.pickGroups) s.groupSel = x;
        } else {
            s.pickGroups.add(id);
            s.groupSel = id;
        }
        seenLaneSel = s.laneSel;
        seenGroupSel = s.groupSel;
        insp.tab = Inspector.SCENE;
    }

    /** @return whether a lane in group g (0: none) is in a picked group */
    private boolean pickedGroup(int g) {
        if (g == 0) return false;
        Doc d = s.doc;
        if (s.groupSel != 0 && d.within(g, s.groupSel)) return true;
        for (int id : s.pickGroups) if (d.within(g, id)) return true;
        return false;
    }

    /** 进组: a click then picks what is right in group {@code id} one by one (Esc: out again). */
    void enterGroup(int id) {
        Group7 gr = s.doc.group(id);
        if (gr == null) return;
        s.groupIn = id;
        im.toast("已进组「" + Doc.groupLabel(gr) + "」：点一下是里面的单条轨道（Esc 出组）");
    }

    /** 出组: up out of the entered group, which stays picked. */
    void leaveGroup() {
        int was = s.groupIn;
        s.groupIn = s.doc.parentOf(was);
        if (s.doc.group(was) != null) pickGroup(was, false);
    }

    /**
     * 成组 (Ctrl+G, Kelvin 2026-11-11 17:32): the picked lanes and groups (all right in one group, or none) become a new
     * group there, its centre among them; it is picked.
     */
    void makeGroup() {
        Doc d = s.doc;
        ArrayList<Lane7> ls = new ArrayList<>();
        ArrayList<Group7> gs = new ArrayList<>();
        for (int id : s.pickLanes) if (d.lane(id) != null) ls.add(d.lane(id));
        for (int id : s.pickGroups) if (d.group(id) != null) gs.add(d.group(id));
        if (ls.isEmpty() && gs.isEmpty()) {
            if (d.lane(s.laneSel) != null) ls.add(d.lane(s.laneSel));
            if (d.group(s.groupSel) != null) gs.add(d.group(s.groupSel));
        }
        if (ls.isEmpty() && gs.isEmpty()) {
            im.toast("先选中轨道或组（按住 Shift 点可以选几个），再按 Ctrl+G 成组");
            return;
        }
        int parent = !ls.isEmpty() ? (d.group(ls.get(0).g) != null ? ls.get(0).g : 0) : d.parentOf(gs.get(0).id);
        boolean one = true;
        for (Lane7 l : ls) one &= (d.group(l.g) != null ? l.g : 0) == parent;
        for (Group7 gr : gs) one &= d.parentOf(gr.id) == parent;
        if (!one) {
            im.toast("选中的轨道和组不在同一个组里，成不了组（先进到同一个组里再选）");
            return;
        }
        s.mark();
        Group7 gr = d.makeGroup(ls, gs, parent, snap(beat));
        s.changed(false);
        pickGroup(gr.id, false);
        im.toast("已成组「" + Doc.groupLabel(gr) + "」：" + ls.size() + " 条轨道" + (gs.isEmpty() ? "" : "、" + gs.size() + " 个组")
            + "（中心在它们中间）");
    }

    /** 解组: group {@code id}'s lanes and groups go into the group it is in, where they are; or why it cannot. */
    void ungroup(int id) {
        Doc d = s.doc;
        Group7 gr = d.group(id);
        if (gr == null) return;
        String why = d.copy().ungroup(id);                              // try it on a copy first: no empty undo step
        if (why != null) {
            im.toast(why);
            return;
        }
        String name = Doc.groupLabel(gr);
        int up = d.parentOf(id);
        s.mark();
        d.ungroup(id);
        s.changed(false);
        if (s.groupIn == id) s.groupIn = up;
        s.groupSel = 0;
        s.pickGroups.clear();
        im.toast("已解组「" + name + "」：里面的东西留在原地");
    }

    /** The picked group's centre on the preview: a ring and its name. */
    private void groupMark() {
        Group7 gr = s.doc.group(s.groupSel);
        if (gr == null) return;
        float px = gr.P.x.at(beat), py = gr.P.y.at(beat), pz = gr.P.z.at(beat);
        float[] w = {px, py, pz};
        Group7.Pose pp = Scene.pose(s.doc.parentOf(gr.id));
        if (pp != null) pp.at(beat).point(px, py, pz, w);
        for (int i = 0; i < 3; i++) q3[i] = w[i];
        if (!px3(q3, pa)) return;
        g.circle(pa[0], pa[1], 7f, 2f, Gfx.GND);
        g.text("组「" + Doc.groupLabel(gr) + "」", pa[0] + 12f, pa[1] - 10f, 12, Gfx.GND);
    }

    /** 全选: every key, shown on the 音符 page. */
    void selectAll() {
        s.sel.clear();
        for (Doc.DKey k : s.doc.keys) s.sel.add(k.uid);
        if (!s.sel.isEmpty()) insp.tab = Inspector.NOTE;
    }

    void deleteSel() {
        if (s.sel.isEmpty()) return;
        s.mark();
        s.doc.keys.removeIf(k -> s.sel.contains(k.uid));
        s.sel.clear();
        s.changed(false);
    }

    /** Deletes the selected veil or curtain. */
    void deleteVol() {
        Doc.DVol v = s.doc.volByUid(s.volSel);
        if (v == null) return;
        s.mark();
        s.doc.vols.remove(v);
        s.volSel = 0;
        s.changed(false);
    }

    /** 删除轨道: lane {@code id} and its keys (asks first when it has keys). */
    void removeLane(int id) {
        Doc d = s.doc;
        int n = d.keysOn(id).size();
        Runnable go = () -> {
            s.mark();
            d.removeLane(id);
            s.sel.removeIf(u -> d.byUid(u) == null);
            s.laneSel = Session.NO_LANE;
            s.changed(false);
        };
        if (n == 0) {
            go.run();
            return;
        }
        Im.Modal m = new Im.Modal("删除轨道「" + d.laneLabel(id) + "」", "上面的 " + n + " 个键也会删掉（浪花、潮汐只是不再跨这条）。可以撤销。",
            new String[]{"删除", "取消"}, (b, t) -> {
                if (b == 0) go.run();
            });
        m.danger = 0;
        im.open(m);
    }

    /** Mirrors the selected keys left ↔ right: each to the lane nearest the other side ({@link Doc#mirrorLane}). */
    void mirror() {
        ArrayList<Doc.DKey> sel = s.selected();
        if (sel.isEmpty()) {
            im.toast("先选中键");
            return;
        }
        Doc d = s.doc;
        s.mark();
        for (Doc.DKey k : sel) {
            float b = Float.isFinite(k.judge()) ? k.judge() : beat;
            if (k.k.kind.spans()) {
                for (int i = 0; i < k.k.lanes.length; i++) k.k.lanes[i] = d.mirrorLane(k.k.lanes[i], b);
                k.k.lane = k.k.lanes[0];
            } else k.k.lane = d.mirrorLane(k.k.lane, b);
        }
        s.changed(false);
    }

    /** 天地互换 (Kelvin 2026-10-22): the selected keys onto the other face. */
    void flipFace() {
        ArrayList<Doc.DKey> sel = s.selected();
        if (sel.isEmpty()) {
            im.toast("先选中键");
            return;
        }
        s.mark();
        for (Doc.DKey k : sel) k.k.sky = !k.k.sky;
        s.changed(false);
    }

    /** Ctrl+↑ / ↓: the selected keys onto the 天面 / 底面. */
    private void toFace(boolean sky) {
        ArrayList<Doc.DKey> sel = s.selected();
        if (sel.isEmpty()) return;
        s.mark();
        for (Doc.DKey k : sel) k.k.sky = sky;
        s.changed(false);
    }

    /** ↑ / ↓: the selected keys later / earlier by one snap step (Shift: a quarter of it). */
    private void nudge(int dir) {
        ArrayList<Doc.DKey> sel = s.selected();
        if (sel.isEmpty()) return;
        float st = (step() > 0f ? step() : 0.25f) * (im.shift ? 0.25f : 1f);
        s.mark();
        for (Doc.DKey k : sel) Doc.shiftKey(k.k, dir * st);
        s.doc.sortKeys();
        s.changed(false);
    }

    /** Alt+← / →: the selected keys one lane across (in the lanes' order). */
    void moveKeysLane(int dir) {
        ArrayList<Doc.DKey> sel = s.selected();
        Doc d = s.doc;
        if (sel.isEmpty() || d.lanes.isEmpty()) return;
        s.mark();
        for (Doc.DKey k : sel) {
            if (k.k.kind.spans()) {
                for (int i = 0; i < k.k.lanes.length; i++) {
                    int ix = d.laneIndex(k.k.lanes[i]);
                    if (ix >= 0) k.k.lanes[i] = d.lanes.get(MathUtils.clamp(ix + dir, 0, d.lanes.size() - 1)).id;
                }
                k.k.lane = k.k.lanes[0];
            } else {
                int ix = d.laneIndex(k.k.lane);
                if (ix >= 0) k.k.lane = d.lanes.get(MathUtils.clamp(ix + dir, 0, d.lanes.size() - 1)).id;
            }
        }
        s.changed(false);
    }

    /**
     * 以当前视角添加镜头节点 (Kelvin 2026-11-08 04:02): the view shown now (the chart's camera turned and zoomed by the
     * editor) becomes the camera's position P at the playhead, a point of its x, y and z; the turn and zoom go back.
     */
    void cameraNode() {
        float t = snap(beat);
        double d = Math.sqrt(sc.eye[0] * sc.eye[0] + sc.eye[1] * sc.eye[1] + sc.eye[2] * sc.eye[2]);
        float k = orbitZoom > 0f ? 1f / orbitZoom : 1f;
        float[] p = {Math.round(sc.eye[0] * k * 100.0) / 100f, Math.round(sc.eye[1] * k * 100.0) / 100f,
            Math.round(sc.eye[2] * k * 100.0) / 100f};
        if (!(d > 1.0)) return;
        Fn[] fs = {s.doc.camera.x, s.doc.camera.y, s.doc.camera.z};
        s.mark();
        for (int i = 0; i < 3; i++) {
            if (fs[i].segs.isEmpty()) fs[i].segs.add(new Seg(t, t, p[i], p[i], 1f));
            else Doc.setAt(fs[i], t, p[i]);
        }
        homeView();
        s.changed(false);
        im.toast("已在第 " + Im.fmt(t) + " 拍加镜头节点：(" + Im.fmt(p[0]) + ", " + Im.fmt(p[1]) + ", " + Im.fmt(p[2]) + ")");
    }

    /** 新建漩涡 / 水帘: at the origin, from the playhead for {@link #VOL_BEATS} beats; it is selected. */
    /**
     * @return what the 关键帧 button keys now (SPEC §1): the selected lane's 原点 v, 下落向量 u and 宽长 a, the camera's P
     *         and 视角, the selected veil's or curtain's 中心 and 大小, or the one selected key's h; null with none
     */
    Keyframes.Group[] keyGroups() {
        Doc d = s.doc;
        Group7 gr = insp.tab == Inspector.SCENE ? d.group(s.groupSel) : null;
        if (gr != null) return Keyframes.position(Keyframes.group(gr), 3);
        if (insp.tab == Inspector.LANE || insp.tab == Inspector.SCENE) {
            Lane7 l = d.lane(s.laneSel);
            return l != null ? Keyframes.position(Keyframes.lane(l), 3) : null;
        }
        if (insp.tab == Inspector.CAMERA) return Keyframes.camera(d);
        if (insp.tab == Inspector.VOLUME) {
            Doc.DVol v = d.volByUid(s.volSel);
            return v != null ? Keyframes.position(Keyframes.volume(v), 2) : null;
        }
        Doc.DKey k = insp.tab == Inspector.NOTE ? s.single() : null;
        return k != null ? Keyframes.position(Keyframes.key(k.k), 1) : null;
    }

    /**
     * 「◇+ 关键帧」 (K, Kelvin 2026-11-11 16:39): a keyframe at the snapped playhead on what {@link #keyGroups} names,
     * its values there kept; on a keyframe 「◆− 关键帧」 takes those off again (the segments around it become one).
     */
    void keyframe() {
        Keyframes.Group[] gs = keyGroups();
        if (gs == null) {
            im.toast("先选中一条轨道、一个组、镜头、漩涡 / 水帘或者一个键");
            return;
        }
        float t = snap(beat);
        boolean on = Keyframes.on(gs, t), can = false;
        for (Keyframes.Group gr : gs) can |= on ? Keyframes.canRemove(gr, t) : Keyframes.canAdd(gr, t);
        if (!can) {
            im.toast(on ? "判定拍的关键帧在这里去不掉（判定拍在音符页改）"
                : "第 " + Im.fmt(t) + " 拍加不了关键帧（键的判定拍以后，或者正在出现 / 消失）");
            return;
        }
        s.mark();
        for (Keyframes.Group gr : gs) {
            if (on) Keyframes.remove(gr, t);
            else Keyframes.add(gr, t);
        }
        s.changed(false);
        layers.revealSelection();
    }

    /** The 关键帧 button: ◇ + when the playhead is not on a keyframe of what it keys, ◆ − when it is. */
    private void keyButton(float x, float y, float w, float h) {
        Keyframes.Group[] gs = keyGroups();
        boolean on = gs != null && Keyframes.on(gs, snap(beat)), hv = im.hover(x, y, w, h);
        if (hv) im.cursor(Cursor.SystemCursor.Hand);
        g.box(x, y, w, h, 6f, hv ? Gfx.BTN_HI : Gfx.BTN, on ? Gfx.SYNC : Gfx.BTN_LINE);
        float cx = x + 16f, cy = y + h * 0.5f, r = 6f;
        Color c = gs == null ? Gfx.INK3 : Gfx.SYNC;
        if (on) {
            g.tri(cx - r, cy, cx, cy - r, cx + r, cy, c, 1f);
            g.tri(cx - r, cy, cx, cy + r, cx + r, cy, c, 1f);
        } else {
            g.line(cx - r, cy, cx, cy - r, 1.5f, c);
            g.line(cx, cy - r, cx + r, cy, 1.5f, c);
            g.line(cx + r, cy, cx, cy + r, 1.5f, c);
            g.line(cx, cy + r, cx - r, cy, 1.5f, c);
        }
        g.text((on ? "− " : "+ ") + "关键帧", x + 28f, cy, TAB ? 14 : 13, gs == null ? Gfx.INK3 : Gfx.INK1);
        if (im.click("kf", x, y, w, h)) keyframe();
    }

    void newVolume(boolean curtain) {
        newVolumeAt(curtain, 0f, 0f, 0f);
    }

    private void newVolumeAt(boolean curtain, float x, float y, float z) {
        float t = snap(beat);
        s.mark();
        Doc.DVol v = new Doc.DVol(Doc.newVolume(curtain, x, y, z, t, VOL_BEATS));
        s.doc.vols.add(v);
        s.volSel = v.uid;
        insp.tab = Inspector.VOLUME;
        s.changed(false);
    }

    void jump(Checks.Item it) {
        if (!it.uids.isEmpty()) {
            s.sel.clear();
            s.sel.addAll(it.uids);
        }
        if (it.lane != Session.NO_LANE) s.laneSel = it.lane;
        if (it.vol != 0) {
            s.volSel = it.vol;
            insp.tab = Inspector.VOLUME;
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
            im.unfocus();
        } catch (Exception e) {
            im.open(new Im.Modal("打不开", d.fileName() + "：" + e.getMessage(), new String[]{"好"}, (b, t) -> { }));
        }
    }

    @Override
    public void dispose() {
        pb.dispose();
    }

    // ================================================================== preview: geometry

    /** Projects world point (x, y, z) into {@code out}; false when it is behind the eye. */
    private boolean px3(double[] w, float[] out) {
        return sc.project(w[0], w[1], w[2], out, 0);
    }

    /** Draws the world segment a–b, cut where it goes behind the eye. */
    void line3(double[] a, double[] b, float w, Color c, float alpha) {
        double da = sc.depth(a[0], a[1], a[2]), db = sc.depth(b[0], b[1], b[2]), near = 2.0;
        if (da < near && db < near) return;
        double ax = a[0], ay = a[1], az = a[2], bx = b[0], by = b[1], bz = b[2];
        if (da < near) {
            double k = (near - da) / (db - da);
            ax += (bx - ax) * k;
            ay += (by - ay) * k;
            az += (bz - az) * k;
        } else if (db < near) {
            double k = (near - db) / (da - db);
            bx += (ax - bx) * k;
            by += (ay - by) * k;
            bz += (az - bz) * k;
        }
        if (!sc.project(ax, ay, az, pa, 0) || !sc.project(bx, by, bz, pb2, 0)) return;
        g.line(pa[0], pa[1], pb2[0], pb2[1], w, c, alpha);
    }

    /** @return lane {@code ix}'s frame at the playhead, or null when it is not there (no width) */
    private float[] frame(int ix) {
        if (ix < 0 || ix >= lfs.length) return null;
        float[] lf = lfs[ix];
        return Math.abs(lf[Scene.W]) > 0.5f ? lf : null;
    }

    /** Lane frames of every lane at the playhead (this frame). */
    private void lanes() {
        Doc d = s.doc;
        Scene.groups(d.groups);
        if (lfs.length != d.lanes.size()) {
            lfs = new float[d.lanes.size()][];
            for (int i = 0; i < lfs.length; i++) lfs[i] = new float[Scene.LF];
        }
        for (int i = 0; i < lfs.length; i++) Scene.laneFrame(d.lanes.get(i), beat, lfs[i]);
    }

    /**
     * The four corners of key k on lane frame lf at the playhead, projected into {@link #quad}: across −w/2 and +w/2
     * at its face end, then at its far end (a long key's tail, else {@link #KEY_T} further). @return false when one is
     * behind the eye
     */
    private boolean keyQuad(Key7 k, float[] lf) {
        float up = Scene.keyUp(k, beat, lf), len = Scene.keyLength(k, beat, lf), dirUp = k.sky ? -1f : 1f;
        float u0 = up - dirUp * KEY_T * 0.5f, u1 = up + dirUp * Math.max(KEY_T * 0.5f, len);
        double hw = Math.abs(lf[Scene.W]) * 0.5;
        Scene.at(lf, -hw, u0, 0, p3, 0);
        if (!px3(p3, hit2)) return false;
        quad[0] = hit2[0];
        quad[1] = hit2[1];
        Scene.at(lf, hw, u0, 0, p3, 0);
        if (!px3(p3, hit2)) return false;
        quad[2] = hit2[0];
        quad[3] = hit2[1];
        Scene.at(lf, hw, u1, 0, p3, 0);
        if (!px3(p3, hit2)) return false;
        quad[4] = hit2[0];
        quad[5] = hit2[1];
        Scene.at(lf, -hw, u1, 0, p3, 0);
        if (!px3(p3, hit2)) return false;
        quad[6] = hit2[0];
        quad[7] = hit2[1];
        return true;
    }

    /** @return the lane frames key k is drawn on: its lane's, or each spanned lane's (their indices) */
    private int[] laneIxs(Key7 k) {
        Doc d = s.doc;
        if (!k.kind.spans()) return new int[]{d.laneIndex(k.lane)};
        int[] r = new int[k.lanes.length];
        for (int i = 0; i < r.length; i++) r[i] = d.laneIndex(k.lanes[i]);
        return r;
    }

    Color keyColour(Key7 k) {
        Color c = k.kind.mine() ? Gfx.MINE : k.kind.spans() ? Gfx.WAVE : k.kind == KeyKind.DRAG ? Theme.DRAG
            : k.sky ? UP_INK : Gfx.GND;
        return k.fake ? fakeInk.set(c).lerp(Gfx.INK3, 0.55f) : c;              // 假键: greyed
    }

    /** @return how lane {@code id} is labelled where many lanes are shown: its 编号, or its name (「显示：编号 / 名称」) */
    String laneTag(int id) {
        return laneIds ? String.valueOf(id) : s.doc.laneLabel(id);
    }

    /** @return whether key k is a 假键 while 「显示：假键」 is off: not on the timeline, not picked */
    boolean hiddenFake(Doc.DKey k) {
        return k.k.fake && !showFake;
    }

    /** @return the key under the screen point (the nearest), or null */
    private Doc.DKey keyAt(float mx, float my) {
        Doc.DKey best = null;
        double bd = Double.POSITIVE_INFINITY;
        for (Doc.DKey dk : s.doc.keys) {
            Key7 k = dk.k;
            if (!Scene.keyShows(k, beat) || hiddenFake(dk)) continue;
            for (int ix : laneIxs(k)) {
                float[] lf = frame(ix);
                if (lf == null || !keyQuad(k, lf)) continue;
                if (!Scene.inHull(quad, 4, mx, my, 6f * TOUCH)) continue;
                Scene.at(lf, 0, Scene.keyUp(k, beat, lf), 0, p3, 0);
                double dep = sc.depth(p3[0], p3[1], p3[2]);
                if (dep < bd) {
                    bd = dep;
                    best = dk;
                }
            }
        }
        return best;
    }

    /** @return the index of the lane whose column the ray {@link #dir} meets first, −1 none */
    private int laneUnder() {
        int best = -1;
        double bt = Double.POSITIVE_INFINITY;
        for (int i = 0; i < lfs.length; i++) {
            float[] lf = frame(i);
            if (lf == null) continue;
            double t = sc.rayLane(dir, lf);
            if (t < bt) {
                bt = t;
                best = i;
            }
        }
        return best;
    }

    /** @return the uid of the veil or curtain the ray {@link #dir} meets first, 0 none */
    private int volUnder() {
        int best = 0;
        double bt = Double.POSITIVE_INFINITY;
        for (Doc.DVol v : s.doc.vols) {
            double t = sc.rayVolume(dir, v.v, beat);
            if (t < bt) {
                bt = t;
                best = v.uid;
            }
        }
        return best;
    }

    /** @return how far up lane frame lf the ray {@link #dir} meets its middle plane, NaN when it does not */
    private float upOn(float[] lf) {
        return sc.onLane(dir, lf, hit2) ? hit2[1] : Float.NaN;
    }

    /**
     * @return the beat a new key is judged at: the playhead's, on the snap (Alt: as it is). Where the press lands only
     *         picks the lane (Kelvin 2026-11-08 19:31「底下时间线光标指在哪里，按下去的按键就会被放在那一拍」).
     */
    private float placeBeat() {
        return im.alt ? round3(beat) : snap(beat);
    }

    // ================================================================== preview: drawing and input

    private void preview() {
        sc.k = K;                                                   // the line under the preview may have moved
        sc.cx = PX + PW * 0.5f;
        sc.cy = PY + PH * 0.5f;
        g.rect(PX, PY, PW, PH, PV_BG);
        Doc d = s.doc;
        if (viewPreset >= 0) {
            orbitYaw = orbitPitch = 0f;
        }
        sc.look(d.camera, d.fov, beat, overlays ? orbitYaw : 0f, overlays ? orbitPitch : 0f, overlays ? orbitZoom : 1f,
            overlays ? viewPreset : -1, overlays ? viewMove : null);
        lanes();
        im.pushClip(PX, PY, PW, PH);
        boolean real = s.runtime != null;
        if (real) gamePicture();
        boolean sceneOn = insp.tab == Inspector.SCENE;
        boolean overUi = im.inside(PV_COL_X, PV_COL_Y, PV_BTN_W, overlays ? (sceneOn ? 204f : 174f) : 54f)
            || overlays && (im.inside(CUBE_X, CUBE_Y, CUBE_BOX, CUBE_BOX + 28f)
            || !SIDE && im.inside(PX + 12f, PY + 12f, 260f, 24f));
        boolean inCanvas = im.hover(PX, PY, PW, PH) && !overUi && !curvesOver();
        if (inCanvas) sc.ray(im.mx, im.my, dir);
        Doc.DKey hot = inCanvas && drag == D_NONE && !sceneOn ? keyAt(im.mx, im.my) : null;
        int hotVol = inCanvas && drag == D_NONE && insp.tab == Inspector.VOLUME && hot == null ? volUnder() : 0;
        int hotLane = inCanvas && drag == D_NONE && hot == null && hotVol == 0 ? laneUnder() : -1;

        if (overlays && axes) axesOverlay();
        if (overlays && sceneOn) scene.grid();
        if (overlays || !real) {
            drawLanes(hotLane, real);
            drawVolumes(hotVol, real);
            drawKeys(hot, real);
        }
        if (overlays) keyGhosts();
        if (overlays) groupMark();
        if (overlays && sceneOn) scene.draw(inCanvas && drag == D_NONE);
        if (overlays && placing() && inCanvas && drag == D_NONE && hot == null) ghost();
        if (d.lanes.isEmpty()) {
            g.text("还没有轨道：在右边「轨道」页新建", PX + PW * 0.5f, PY + PH * 0.5f, 14, Gfx.INK3, 1);
        }
        im.popClip();
        g.stroke(PX, PY, PW, PH, 6f, 1f, Gfx.LINE);
        previewButtons(CUBE_X, CUBE_Y, PV_COL_X, PV_COL_Y);
        if (im.modalOpen()) return;
        if (inCanvas && im.wheel != 0f && im.ctrl && im.alt) {      // Ctrl+Alt + the wheel: the editor's zoom
            orbitZoom = MathUtils.clamp(round2(orbitZoom * (float) Math.pow(1.1, -im.wheel)), 0.25f, 4f);
        } else if (inCanvas && im.wheel != 0f) wheelTime();
        if (inCanvas && (hot != null || hotVol != 0 || hotLane >= 0 && tool == T_SELECT)) {
            im.cursor(Cursor.SystemCursor.Hand);
        }
        if (im.pressed && im.ctrl && im.alt && inCanvas && drag == D_NONE && !im.popupOpen()) {
            altPress = true;
            turning = true;
            orbitDrag(true, im.mx, im.my);
        } else if (im.pressed && !im.rightPressed && inCanvas && drag == D_NONE && !im.popupOpen()) {
            pressPreview(hot, hotVol, hotLane);
        }
        if (im.rightPressed && inCanvas && hot != null) {           // right click: delete the key
            s.mark();
            s.doc.keys.remove(hot);
            s.sel.remove(hot.uid);
            s.changed(false);
        }
        if (altPress) {
            if (im.down) orbitDrag(false, im.mx, im.my);
            else altPress = false;
        }
        if (im.two) {                                               // two fingers (the tablet) turn it
            if (!twoTurning) {
                twoTurning = true;
                drag = D_NONE;
                orbitDrag(true, im.twoX, im.twoY);
            } else orbitDrag(false, im.twoX, im.twoY);
        } else if (twoTurning && !im.down) twoTurning = false;
        if (im.down && !altPress && !twoTurning) dragPreview();
    }

    private final Matrix4 pvMatrix = new Matrix4();
    /** Rectangles of the game's world (y up, top at 1140) as clips of the screen: 实机's corner HUD. */
    private final CharterPreview.Clip pvClip = new CharterPreview.Clip() {
        @Override
        public void push(float x0, float y0, float x1, float y1) {
            float top = Field.WORLD_CY + Field.REF_H * 0.5f;
            im.pushClip(PX + x0 * K, PY + (top - y1) * K, (x1 - x0) * K, (y1 - y0) * K);
        }

        @Override
        public void pop() {
            im.popClip();
        }
    };

    /**
     * The game's own picture of the chart at the playhead, by its renderers, through the same eye as {@link #sc}: the
     * chart's camera, turned by the editor's view (the game turns its yaw the other way round) and zoomed alike.
     */
    private void gamePicture() {
        // the game's world is the 1200 frame centred at y 540 (−60 … 1140): its bottom edge on the preview's
        pvMatrix.set(app.cam.combined).translate(PX, Gfx.H - PY - PH, 0f).scale(K, K, 1f)
            .translate(0f, Field.REF_H * 0.5f - Field.WORLD_CY, 0f);
        g.batch.setProjectionMatrix(pvMatrix);
        float yaw = 0f, pitch = 0f;
        if (overlays && (viewPreset >= 0 || orbitYaw != 0f || orbitPitch != 0f)) {
            Fn3 cam = s.doc.camera;
            double px = cam.x.at(beat), py = cam.y.at(beat), pz = cam.z.at(beat);
            double d = Math.sqrt(px * px + py * py + pz * pz);
            float y0 = (float) Scene.baseYaw(px, pz);
            float p0 = d > 1e-3 ? (float) Math.toDegrees(Math.asin(MathUtils.clamp((float) (py / d), -1f, 1f))) : 0f;
            float ya = viewPreset >= 0 ? Scene.VIEW_YAW[viewPreset] : y0 + orbitYaw;
            float pa = viewPreset >= 0 ? Scene.VIEW_PITCH[viewPreset] : p0 + orbitPitch;
            pa = MathUtils.clamp(pa, (float) -Scene.MAX_PITCH, (float) Scene.MAX_PITCH);
            yaw = y0 - ya;
            pitch = pa - p0;
        }
        pv.orbit(yaw, pitch);
        pv.zoom(overlays ? orbitZoom : 1f);
        pv.pan((float) sc.moved[0], (float) sc.moved[1], (float) sc.moved[2]);
        pv.draw(g.batch, 1f);
        if (!overlays) pv.drawCorners(g.batch, pvClip);
        pv.drawCurtains(g.batch);
        g.batch.setProjectionMatrix(app.cam.combined);
    }

    /** @return whether the curve window covers the press (it draws over the timeline only, never the preview) */
    private boolean curvesOver() {
        return false;
    }

    /** The world's axes from the origin, 960 each way along x and y and 960 deep (坐标系). */
    private void axesOverlay() {
        double[] o = {0, 0, 0};
        double[][] ends = {{960, 0, 0}, {0, 960, 0}, {0, 0, 960}};
        Color[] cs = {AX_X, AX_Y, AX_Z};
        String[] names = {"x", "y", "z"};
        for (int i = 0; i < 3; i++) {
            line3(o, ends[i], 1.5f, cs[i], 0.55f);
            if (px3(ends[i], pa)) g.text(names[i], pa[0] + 4f, pa[1] - 6f, 11, cs[i]);
        }
    }

    /** Every lane that is there: its column's edges, its two faces brighter, its name under it. */
    private void drawLanes(int hotLane, boolean real) {
        Doc d = s.doc;
        for (int i = 0; i < lfs.length; i++) {
            float[] lf = frame(i);
            if (lf == null) continue;
            Lane7 l = d.lanes.get(i);
            boolean on = l.id == s.laneSel || s.pickLanes.contains(l.id) || pickedGroup(l.g), hot = i == hotLane;
            boolean out = s.groupIn != 0 && !d.within(l.g, s.groupIn);       // outside the entered group: faint
            if (real && !on && !hot) {                                  // the game draws it: its name only
                Scene.at(lf, 0, lf[Scene.GND] - lf[Scene.GND_EXT], 0, p3, 0);
                if (px3(p3, pa)) g.text(laneTag(l.id), pa[0], pa[1] + 12f, 11, Gfx.INK3, 1);
                continue;
            }
            Color c = on ? Gfx.GND : hot ? Gfx.INK1 : Gfx.INK3;
            float a = (on ? 0.9f : hot ? 0.6f : 0.3f) * (out ? 0.4f : 1f), w = on ? 2f : 1f;
            double hw = Math.abs(lf[Scene.W]) * 0.5, hd = Math.abs(lf[Scene.H]) * 0.5;
            double lo = lf[Scene.GND] - lf[Scene.GND_EXT], hi = lf[Scene.SKY] + lf[Scene.SKY_EXT];
            for (int sa = -1; sa <= 1; sa += 2) {
                for (int sd = -1; sd <= 1; sd += 2) {
                    Scene.at(lf, sa * hw, lo, sd * hd, p3, 0);
                    Scene.at(lf, sa * hw, hi, sd * hd, q3, 0);
                    line3(p3, q3, w, c, a * 0.6f);
                }
            }
            for (float face : new float[]{lf[Scene.SKY], lf[Scene.GND]}) {      // the judge faces
                face(lf, face, hw, hd, on ? Gfx.GND : face == lf[Scene.SKY] ? UP_INK : Gfx.GND, on ? 2.5f : 1.5f,
                    on ? 1f : 0.7f);
            }
            Scene.at(lf, 0, lo, 0, p3, 0);
            if (overlays && px3(p3, pa)) {
                g.text(laneTag(l.id), pa[0], pa[1] + 12f, 11, on ? Gfx.GND : Gfx.INK3, 1);
            }
        }
    }

    /** The rectangle of lane frame lf at height {@code up}: across ±hw, deep ±hd. */
    private void face(float[] lf, float up, double hw, double hd, Color c, float w, float a) {
        double[][] p = new double[4][3];
        Scene.at(lf, -hw, up, -hd, p[0], 0);
        Scene.at(lf, hw, up, -hd, p[1], 0);
        Scene.at(lf, hw, up, hd, p[2], 0);
        Scene.at(lf, -hw, up, hd, p[3], 0);
        for (int i = 0; i < 4; i++) line3(p[i], p[(i + 1) % 4], w, c, a);
    }

    private void drawKeys(Doc.DKey hot, boolean real) {
        for (Doc.DKey dk : s.doc.keys) {
            Key7 k = dk.k;
            if (!Scene.keyShows(k, beat)) continue;
            boolean sel = s.sel.contains(dk.uid);
            Color c = keyColour(k);
            for (int ix : laneIxs(k)) {
                float[] lf = frame(ix);
                if (lf == null || !keyQuad(k, lf)) continue;
                boolean longKey = Scene.keyLength(k, beat, lf) > KEY_T * 0.5f;
                if (real) {
                    // the game draws the key: only its outline when picked or under the mouse
                } else if (longKey) {                                          // its body, then its head brighter
                    g.tri(quad[0], quad[1], quad[2], quad[3], quad[4], quad[5], c, 0.28f);
                    g.tri(quad[0], quad[1], quad[4], quad[5], quad[6], quad[7], c, 0.28f);
                    g.line(quad[0], quad[1], quad[2], quad[3], 4f, c, 1f);
                } else {
                    g.tri(quad[0], quad[1], quad[2], quad[3], quad[4], quad[5], c, 0.9f);
                    g.tri(quad[0], quad[1], quad[4], quad[5], quad[6], quad[7], c, 0.9f);
                }
                if (k.kind.mine() && !real) {
                    for (int q = 0; q < 4; q++) {
                        g.line(quad[q * 2], quad[q * 2 + 1], quad[(q + 1) % 4 * 2], quad[(q + 1) % 4 * 2 + 1], 1.5f,
                            Gfx.MINE, 1f);
                    }
                }
                if (sel || dk == hot) {
                    Color o = sel ? Color.WHITE : Gfx.INK2;
                    for (int q = 0; q < 4; q++) {
                        g.line(quad[q * 2], quad[q * 2 + 1], quad[(q + 1) % 4 * 2], quad[(q + 1) % 4 * 2 + 1],
                            sel ? 2f : 1.2f, o, 1f);
                    }
                }
                if (sel && s.sel.size() == 1 && k.kind.hasHeight()) {  // the tail's handle: drag for the length
                    float hx = (quad[4] + quad[6]) * 0.5f, hy = (quad[5] + quad[7]) * 0.5f;
                    g.disc(hx, hy, 6f, Gfx.BG);
                    g.disc(hx, hy, 4.5f, Gfx.SYNC);
                }
            }
        }
    }

    /** @return the tail handle of the one selected long key on screen ({x, y}), or null */
    private float[] tailHandle() {
        Doc.DKey dk = s.single();
        if (dk == null || !dk.k.kind.hasHeight() || !Scene.keyShows(dk.k, beat)) return null;
        for (int ix : laneIxs(dk.k)) {
            float[] lf = frame(ix);
            if (lf != null && keyQuad(dk.k, lf)) {
                return new float[]{(quad[4] + quad[6]) * 0.5f, (quad[5] + quad[7]) * 0.5f};
            }
        }
        return null;
    }

    /** Veils and curtains there now: a box's edges or an ellipsoid's three rings; the selected one brighter. */
    private void drawVolumes(int hotVol, boolean real) {
        boolean page = insp.tab == Inspector.VOLUME;
        for (Doc.DVol dv : s.doc.vols) {
            Volume7 v = dv.v;
            boolean on = dv.uid == s.volSel && page, hot = dv.uid == hotVol;
            if (real && !on && !hot) continue;                          // the game draws it
            Color c = v.curtain ? Gfx.FX : Gfx.TIDE;
            float a = on ? 1f : hot ? 0.8f : page ? 0.5f : 0.3f, w = on ? 2f : 1.2f;
            volEdges(v, beat, c, a, w);
        }
    }

    /** Volume v's edges at beat t (nothing while it has no size): a box's twelve, an ellipsoid's three rings. */
    private void volEdges(Volume7 v, float t, Color c, float a, float w) {
        double cx = v.p.x.at(t), cy = v.p.y.at(t), cz = v.p.z.at(t);
        double ex = Math.abs(v.V.x.at(t)) * 0.5, ey = Math.abs(v.V.y.at(t)) * 0.5, ez = Math.abs(v.V.z.at(t)) * 0.5;
        if (!(ex > 0.5 && ey > 0.5 && ez > 0.5)) return;
        double[] ax = Scene.volAxes(v, t);                              // turned by its o
        if (!v.ellipsoid) {
            double[][] p = new double[8][3];
            for (int i = 0; i < 8; i++) {
                double lx = (i & 1) == 0 ? -ex : ex, ly = (i & 2) == 0 ? -ey : ey, lz = (i & 4) == 0 ? -ez : ez;
                for (int j = 0; j < 3; j++) p[i][j] = (j == 0 ? cx : j == 1 ? cy : cz) + lx * ax[j] + ly * ax[3 + j]
                    + lz * ax[6 + j];
            }
            for (int i = 0; i < 8; i++) {
                for (int b = 1; b <= 4; b <<= 1) if ((i & b) == 0) line3(p[i], p[i | b], w, c, a);
            }
        } else {
            double[] prev = new double[3], cur = new double[3];
            for (int ring = 0; ring < 3; ring++) {
                for (int q = 0; q <= 48; q++) {
                    double ang = q * Math.PI * 2 / 48, cs = Math.cos(ang), sn = Math.sin(ang);
                    double lx = ring == 2 ? 0 : ex * cs, ly = ring == 0 ? ey * sn : ring == 2 ? ey * cs : 0,
                        lz = ring == 0 ? 0 : ez * sn;
                    for (int j = 0; j < 3; j++) cur[j] = (j == 0 ? cx : j == 1 ? cy : cz) + lx * ax[j]
                        + ly * ax[3 + j] + lz * ax[6 + j];
                    if (q > 0) line3(prev, cur, w, c, a);
                    System.arraycopy(cur, 0, prev, 0, 3);
                }
            }
        }
    }

    /**
     * 关键帧的影子 (Kelvin 2026-11-11 16:39, SPEC §3): while a lane, veil or curtain is selected, it at each of its
     * keyframes in amber (a lane by its 底面), a label with each one's number and beat (two at one place share it:
     * 「① 0 拍 · ④ 6 拍」) and the dashed path its 底面 or centre takes between them, arrows showing which way.
     */
    private void keyGhosts() {
        Doc d = s.doc;
        Lane7 l = insp.tab == Inspector.LANE ? d.lane(s.laneSel) : null;
        Doc.DVol v = insp.tab == Inspector.VOLUME && l == null ? d.volByUid(s.volSel) : null;
        if (l == null && v == null) return;
        float[] ts = Keyframes.points(l != null ? Keyframes.position(Keyframes.lane(l), 3)
            : Keyframes.position(Keyframes.volume(v), 2));
        if (ts.length < 2 || ts.length > 64) return;
        float[] lf = new float[Scene.LF];
        double[] a = new double[3], b = new double[3];
        for (int i = 0; i + 1 < ts.length; i++) {                     // the path: every other of 12 steps, an arrow
            float t0 = ts[i], t1 = ts[i + 1];
            spot(l, v, t0, lf, a);
            for (int q = 1; q <= 12; q++) {
                spot(l, v, t0 + (t1 - t0) * q / 12f, lf, b);
                if (q % 2 == 1) line3(a, b, 1.5f, Gfx.SYNC, 0.9f);
                if (q == 7) arrow(a, b);
                System.arraycopy(b, 0, a, 0, 3);
            }
        }
        for (float t : ts) {                                            // the ghosts
            if (l == null) {
                volEdges(v.v, t, Gfx.SYNC, 0.55f, 1f);
                continue;
            }
            Scene.laneFrame(l, t, lf);
            if (Math.abs(lf[Scene.W]) < 0.5f) continue;
            face(lf, lf[Scene.GND], Math.abs(lf[Scene.W]) * 0.5, Math.abs(lf[Scene.H]) * 0.5, Gfx.SYNC, 1.5f, 0.85f);
        }
        ArrayList<float[]> at = new ArrayList<>();                      // the labels
        ArrayList<StringBuilder> text = new ArrayList<>();
        for (int i = 0; i < ts.length; i++) {
            spot(l, v, ts[i], lf, a);
            if (!px3(a, pa)) continue;
            String one = Keyframes.circled(i) + " " + Im.fmt(ts[i]) + " 拍";
            int k = -1;
            for (int j = 0; j < at.size() && k < 0; j++) {
                if (Math.hypot(at.get(j)[0] - pa[0], at.get(j)[1] - pa[1]) < 8f) k = j;
            }
            if (k >= 0) text.get(k).append(" · ").append(one);
            else {
                at.add(new float[]{pa[0], pa[1]});
                text.add(new StringBuilder(one));
            }
        }
        for (int j = 0; j < at.size(); j++) {
            float[] p = at.get(j);
            String tx = text.get(j).toString();
            float w = g.width(tx, 11) + 14f;
            g.disc(p[0], p[1], 4f, Gfx.SYNC);
            g.box(p[0] - w * 0.5f, p[1] + 9f, w, 18f, 9f, Gfx.BAR, Gfx.SYNC);
            g.text(tx, p[0], p[1] + 18f, 11, Gfx.SYNC, 1);
        }
    }

    /** Where the keyframe path is at beat t: lane l's 底面 centre, else volume v's centre. */
    private static void spot(Lane7 l, Doc.DVol v, float t, float[] lf, double[] out) {
        if (l != null) Keyframes.laneSpot(l, t, lf, out);
        else Keyframes.volSpot(v.v, t, out);
    }

    /** An arrowhead at b on the screen, pointing the way from a to b. */
    private void arrow(double[] a, double[] b) {
        if (!px3(a, pa) || !px3(b, pb2)) return;
        float dx = pb2[0] - pa[0], dy = pb2[1] - pa[1], n = (float) Math.hypot(dx, dy);
        if (n < 1f) return;
        dx /= n;
        dy /= n;
        float x = pb2[0], y = pb2[1], r = 7f;
        float sx = dy * r * 0.6f, sy = dx * r * 0.6f;
        g.tri(x + dx * r, y + dy * r, x - sx, y + sy, x + sx, y - sy, Gfx.SYNC, 0.95f);
    }

    /** The key a press would place now, faint, where it would be. */
    private void ghost() {
        if (tool == T_VEIL || tool == T_CURTAIN) return;
        int ix = laneUnder();
        float[] lf = frame(ix);
        if (lf == null) return;
        if (Float.isNaN(upOn(lf))) return;
        float b = placeBeat();
        Key7 k = Doc.newKey(TOOL_KIND[tool], s.doc.lanes.get(ix).id, s.placeSky, b, 0f);
        if (!keyQuad(k, lf)) return;
        Color c = keyColour(k);
        g.tri(quad[0], quad[1], quad[2], quad[3], quad[4], quad[5], c, 0.4f);
        g.tri(quad[0], quad[1], quad[4], quad[5], quad[6], quad[7], c, 0.4f);
        g.text("第 " + Im.fmt(b) + " 拍", quad[2] + 6f, quad[3], 11, Gfx.INK2);
    }

    static float round3(float v) {
        return Math.round(v * 1000f) / 1000f;
    }

    private boolean placing() {
        return tool != T_SELECT && tool != T_BOX;
    }

    /** The preview's buttons: sync, ⇆ 实机, 坐标系; 拖这里转视角, 归位, 镜头节点 and the six views (see {@link #SIDE}). */
    private void previewButtons(float cubeX, float cubeY, float colB, float colY) {
        boolean moved = viewMove[0] != 0f || viewMove[1] != 0f || viewMove[2] != 0f;
        boolean turned = orbitYaw != 0f || orbitPitch != 0f || orbitZoom != 1f || viewPreset >= 0 || moved;
        if (overlays) {
            String tag = "预览 · 第 " + String.format(Locale.ROOT, "%.2f", beat) + " 拍";
            if (viewPreset >= 0) tag += " · " + Scene.VIEW_NAMES[viewPreset];
            else if (orbitYaw != 0f || orbitPitch != 0f || orbitZoom != 1f) {
                tag += " · 视角 " + Math.round(orbitYaw) + "° " + Math.round(orbitPitch) + "°"
                    + (orbitZoom != 1f ? " ×" + Im.fmt(round2(orbitZoom)) : "");
            }
            if (moved) {
                tag += " · 挪动 右 " + Math.round(viewMove[0]) + " 上 " + Math.round(viewMove[1]) + " 前 "
                    + Math.round(viewMove[2]);
            }
            Lane7 l = s.doc.lane(s.laneSel);
            if (l != null && insp.tab == Inspector.LANE) {
                tag += " · 轨道 " + s.doc.laneLabel(l.id) + " 宽 " + Math.round(l.w.at(beat)) + " 长 "
                    + Math.round(l.h.at(beat));
            }
            if (SIDE) {                                                 // in the strip: a line each, under its buttons
                String[] parts = tag.split(" · ");
                float y = Math.max(cubeY + CUBE_BOX + 26f, colY + (insp.tab == Inspector.SCENE ? 204f : 174f)) + 22f;
                im.pushClip(PX + PW, PY, TR - PX - PW, PH);
                for (int i = 1; i < parts.length; i++) g.text(parts[i], cubeX, y + (i - 1) * 20f, 12, Gfx.INK3);
                im.popClip();
            } else {
                float tw = g.width(tag, 12) + 20f;
                g.box(PX + 12f, PY + 12f, tw, 24f, 4f, Gfx.BAR, Gfx.LINE);
                g.text(tag, PX + 22f, PY + 24f, 12, Gfx.INK3);
            }
        }
        float top = overlays ? colY : Math.min(colY, cubeY);            // 实机: no cube, the two buttons at the top
        // one column (Kelvin 2026-11-09 20:16「sync高亮和显示坐标系放在镜头节点下面」): 实机, 镜头节点, sync, 坐标系, 假键
        if (im.button("pv-view", colB, top, PV_BTN_W, 24f, "⇆ 实机", overlays ? Im.NORMAL : Im.ON, 12)) {
            overlays = !overlays;
        }
        if (overlays && im.button("pv-cam", colB, top + 30f, PV_BTN_W, 24f, "+镜头节点", Im.NORMAL, 12)) cameraNode();
        if (im.button("pv-sync", colB, top + (overlays ? 60f : 30f), PV_BTN_W, 24f, SYNC_LABELS[pv.syncHint()],
            Im.NORMAL, 12)) {
            pv.setSyncHint((pv.syncHint() + 1) % SYNC_LABELS.length);
        }
        if (!overlays) return;
        if (im.button("pv-axes", colB, top + 90f, PV_BTN_W, 24f, "显示：坐标系 " + (axes ? "✓" : "×"), Im.NORMAL, 12)) {
            axes = !axes;
        }
        if (im.button("pv-fake", colB, top + 120f, PV_BTN_W, 24f, "显示：假键 " + (showFake ? "✓" : "×"), Im.NORMAL,
            12)) {
            showFake = !showFake;
        }
        if (im.button("pv-names", colB, top + 150f, PV_BTN_W, 24f, "显示：" + (laneIds ? "编号" : "名称"), Im.NORMAL,
            12)) {
            laneIds = !laneIds;
        }
        if (insp.tab == Inspector.SCENE) scene.gridButton(colB, top + 180f);
        viewCube(cubeX, cubeY);
        if (im.button("pv-home", cubeX + (CUBE_BOX - 64f) * 0.5f, cubeY + CUBE_BOX + 2f, 64f, 24f, "归位",
            turned ? Im.NORMAL : Im.GHOST, 12)) {
            homeView();
        }
    }

    /**
     * 视角方块 (Kelvin 2026-11-09 20:02「六个视角并成一个魔方一样的东西放在右上角」, 20:35「可以点击边和角，稍微做的圆滑
     * 一点点」): a bevelled cube turned as the view is, faces 前 后 左 右 上 下. A click on a face, an edge or a corner looks
     * from that side ({@link Scene#VIEW_DIR}); looking straight at a face, four arrows around it go to the faces next
     * to it; a drag on the cube turns the view.
     */
    private void viewCube(float x0, float y0) {
        float cx = x0 + CUBE_BOX * 0.5f, cy = y0 + CUBE_BOX * 0.5f;
        boolean hover = im.hover(x0, y0, CUBE_BOX, CUBE_BOX) && !im.modalOpen() && !im.popupOpen();
        g.disc(cx, cy, CUBE_BOX * 0.47f, Gfx.BAR, 0.6f);
        int under = -1;
        float most = 0f;
        // a convex solid: the sides facing the eye never overlap, so no order is needed
        for (int k = 0; k < Scene.VIEW_DIR.length; k++) {
            float facing = cubeFacing(k);
            if (k < 6) most = Math.max(most, facing);
            if (facing <= 1e-3f) continue;
            int n = cubeRegion(k, cx, cy);
            boolean hot = hover && Scene.inHull(cubePts, n, im.mx, im.my, 0f);
            if (hot) under = k;
            boolean on = viewPreset == k, face = k < 6;
            boolean lit = hot || cubeHeld && !cubeTurning && cubePress == k;
            Color fill = on ? Gfx.PRI_BOT : lit ? Gfx.BTN_HI : Gfx.BTN;
            // the bevels darken as they turn away: a rounded edge
            float a = on || lit ? 1f : face ? 0.6f + 0.4f * facing : 0.35f + 0.6f * facing;
            g.tri(cubePts[0], cubePts[1], cubePts[2], cubePts[3], cubePts[4], cubePts[5], fill, a);
            if (n == 4) g.tri(cubePts[0], cubePts[1], cubePts[4], cubePts[5], cubePts[6], cubePts[7], fill, a);
            if (!face) continue;
            for (int i = 0; i < 4; i++) {
                int j = (i + 1) % 4;
                g.line(cubePts[i * 2], cubePts[i * 2 + 1], cubePts[j * 2], cubePts[j * 2 + 1], 1f,
                    on ? Gfx.PRI_TOP : lit ? Gfx.INK2 : Gfx.SEL_LINE, 0.7f);
            }
            if (facing > 0.3f) {
                float lx = (cubePts[0] + cubePts[2] + cubePts[4] + cubePts[6]) * 0.25f;
                float ly = (cubePts[1] + cubePts[3] + cubePts[5] + cubePts[7]) * 0.25f;
                g.text(CUBE_LABELS[k], lx, ly, 14, on ? Gfx.PRI_INK : Gfx.INK1, Math.min(1f, facing * 1.4f), 1);
            }
        }
        // straight at a face: arrows to the faces above, below, left and right of it
        if (most > 0.98f) {
            float d = CUBE_HALF + 16f;
            for (int i = 0; i < 4; i++) {
                float ax = i == 2 ? -1f : i == 3 ? 1f : 0f, ay = i == 0 ? -1f : i == 1 ? 1f : 0f;
                int k = cubeToward(ax, -ay);
                float px = cx + ax * d, py = cy + ay * d;
                boolean hot = hover && Math.hypot(im.mx - px, im.my - py) < 11f;
                if (hot) under = 100 + k;
                Color c = hot || cubeHeld && !cubeTurning && cubePress == 100 + k ? Gfx.INK1 : Gfx.INK3;
                float r = 7f, bx = ay, by = -ax;                    // the arrow's base runs across its direction
                g.tri(px + ax * r, py + ay * r, px - ax * r * 0.4f + bx * r, py - ay * r * 0.4f + by * r,
                    px - ax * r * 0.4f - bx * r, py - ay * r * 0.4f - by * r, c, 1f);
            }
        }
        if (hover && im.pressed) {
            cubeHeld = true;
            cubeTurning = false;
            cubePress = under;
            cubePressX = im.mx;
            cubePressY = im.my;
        }
        if (cubeHeld) {
            if (im.down) {
                if (!cubeTurning && Math.hypot(im.mx - cubePressX, im.my - cubePressY) > 4f) {
                    cubeTurning = true;
                    orbitDrag(true, cubePressX, cubePressY);
                }
                if (cubeTurning) orbitDrag(false, im.mx, im.my);
            } else {
                if (!cubeTurning && cubePress >= 0) {
                    viewPreset = cubePress >= 100 ? cubePress - 100 : cubePress;
                    orbitZoom = 1f;
                    Arrays.fill(viewMove, 0f);
                }
                cubeHeld = false;
            }
        }
        if (hover || cubeHeld) im.cursor(Cursor.SystemCursor.Hand);
    }

    /** @return how squarely the cube's side k ({@link Scene#VIEW_DIR}) faces the eye (1 = straight, ≤ 0 = away) */
    private float cubeFacing(int k) {
        int[] n = Scene.VIEW_DIR[k];
        double len = Math.sqrt(n[0] * n[0] + n[1] * n[1] + n[2] * n[2]);
        return -(float) ((n[0] * sc.f[0] + n[1] * sc.f[1] + n[2] * sc.f[2]) / len);
    }

    /** @return the face (0–5) whose normal points most along the screen direction (sx right, sy up) */
    private int cubeToward(float sx, float sy) {
        int best = 0;
        double bestDot = -2;
        for (int k = 0; k < 6; k++) {
            int[] n = Scene.VIEW_DIR[k];
            double dot = sx * (n[0] * sc.r[0] + n[1] * sc.r[1] + n[2] * sc.r[2])
                + sy * (n[0] * sc.u[0] + n[1] * sc.u[1] + n[2] * sc.u[2]);
            if (dot > bestDot) {
                bestDot = dot;
                best = k;
            }
        }
        return best;
    }

    /**
     * The cube's side k on screen (an orthographic view of the bevelled cube turned as the eye is) into cubePts: a face
     * is its square inset by the bevel, an edge the strip between two faces, a corner the triangle between three.
     *
     * @return the number of corners, 4 or 3
     */
    private int cubeRegion(int k, float cx, float cy) {
        int[] d = Scene.VIEW_DIR[k];
        int a = -1, b = -1, m = 0;
        for (int i = 0; i < 3; i++) {
            if (d[i] == 0) continue;
            if (a < 0) a = i;
            else b = i;
            m++;
        }
        float q = 1f - CUBE_BEVEL;
        int n = m == 3 ? 3 : 4;
        for (int i = 0; i < n; i++) {
            if (m == 1) {                                               // a face: its inset square
                int u = (a + 1) % 3, v = (a + 2) % 3;
                cubeW[a] = d[a];
                cubeW[u] = i < 2 ? q : -q;
                cubeW[v] = i == 0 || i == 3 ? q : -q;
            } else if (m == 2) {                                        // an edge: from face a's border to face b's
                int c = 3 - a - b;
                boolean onA = i == 0 || i == 3;
                cubeW[a] = onA ? d[a] : d[a] * q;
                cubeW[b] = onA ? d[b] * q : d[b];
                cubeW[c] = i < 2 ? q : -q;
            } else {                                                    // a corner: a point on each of three faces
                for (int j = 0; j < 3; j++) cubeW[j] = j == i ? d[j] : d[j] * q;
            }
            double sx = cubeW[0] * sc.r[0] + cubeW[1] * sc.r[1] + cubeW[2] * sc.r[2];
            double sy = cubeW[0] * sc.u[0] + cubeW[1] * sc.u[1] + cubeW[2] * sc.u[2];
            cubePts[i * 2] = cx + (float) (sx * CUBE_HALF);
            cubePts[i * 2 + 1] = cy - (float) (sy * CUBE_HALF);
        }
        return n;
    }

    /** Self-test: where a click on the cube's side k lands, or null while it faces away (the cube at its place). */
    float[] cubeCentre(int k) {
        if (cubeFacing(k) <= 0.05f) return null;
        float cx = CUBE_X + CUBE_BOX * 0.5f, cy = CUBE_Y + CUBE_BOX * 0.5f;
        int n = cubeRegion(k, cx, cy);
        float x = 0f, y = 0f;
        for (int i = 0; i < n; i++) {
            x += cubePts[i * 2];
            y += cubePts[i * 2 + 1];
        }
        return new float[]{x / n, y / n};
    }

    /**
     * Turns the edit view by a drag: {@code start} takes the press point; a view preset becomes the turn it shows,
     * so the drag goes on from there.
     */
    private void orbitDrag(boolean start, float x, float y) {
        if (start) {
            if (viewPreset >= 0) {                                      // go on from the preset's angles
                double px = s.doc.camera.x.at(beat), py = s.doc.camera.y.at(beat), pz = s.doc.camera.z.at(beat);
                double dd = Math.sqrt(px * px + py * py + pz * pz);
                float y0 = (float) Scene.baseYaw(px, pz);
                float p0 = dd > 1e-3
                    ? (float) Math.toDegrees(Math.asin(MathUtils.clamp((float) (py / dd), -1f, 1f))) : 0f;
                orbitYaw = Scene.VIEW_YAW[viewPreset] - y0;
                orbitPitch = Scene.VIEW_PITCH[viewPreset] - p0;
                viewPreset = -1;
            }
            orbitX = x;
            orbitY = y;
            return;
        }
        orbitYaw += (x - orbitX) * ORBIT_DEG;
        orbitPitch = MathUtils.clamp(orbitPitch + (y - orbitY) * ORBIT_DEG, -180f, 180f);
        orbitYaw = ((orbitYaw + 180f) % 360f + 360f) % 360f - 180f;
        orbitX = x;
        orbitY = y;
    }

    /** A press on the preview: place with a key or volume tool, else pick (and start dragging) what is under it. */
    private void pressPreview(Doc.DKey hot, int hotVol, int hotLane) {
        Doc d = s.doc;
        dMarked = false;
        if (insp.tab == Inspector.SCENE && scene.press()) return;      // 方块, or the handle
        float[] th = tool == T_SELECT ? tailHandle() : null;
        if (th != null && Math.hypot(th[0] - im.mx, th[1] - im.my) < 10f * TOUCH) {
            dKey = s.single();
            dLaneIx = laneIxs(dKey.k)[0];
            drag = D_LENGTH;
            return;
        }
        if (tool == T_VEIL || tool == T_CURTAIN) {
            if (sc.onFacing(dir, 0, 0, 0, p3)) {
                newVolumeAt(tool == T_CURTAIN, Math.round(p3[0]), Math.round(p3[1]), Math.round(p3[2]));
            }
            return;
        }
        if (placing()) {
            int ix = laneUnder();
            float[] lf = frame(ix);
            if (lf == null) {
                if (!d.lanes.isEmpty()) im.toast("点在一条轨道上放键");
                return;
            }
            if (Float.isNaN(upOn(lf))) return;
            float b = placeBeat();
            KeyKind kind = TOOL_KIND[tool];
            s.mark();
            Doc.DKey k = new Doc.DKey(Doc.newKey(kind, d.lanes.get(ix).id, s.placeSky, b,
                kind.hasHeight() ? LONG_DEFAULT : 0f));
            d.keys.add(k);
            d.sortKeys();
            s.sel.clear();
            s.sel.add(k.uid);
            insp.tab = Inspector.NOTE;
            s.changed(false);
            dKey = k;
            dLaneIx = ix;
            drag = kind.hasHeight() ? D_PLACE : D_NONE;
            dMarked = true;
            return;
        }
        if (hot != null) {
            if (adding()) s.select(hot, true);
            else if (!s.sel.contains(hot.uid)) s.select(hot, false);
            insp.tab = Inspector.NOTE;
            dKey = hot;
            dLaneIx = laneIxs(hot.k)[0];
            float[] lf = frame(dLaneIx);
            dUp0 = lf != null ? upOn(lf) : Float.NaN;
            dApplied = 0f;
            drag = D_PRESS_KEY;
            return;
        }
        if (hotVol != 0) {
            s.volSel = hotVol;
            Volume7 v = d.volByUid(hotVol).v;
            startMove(hotVol, v.p.x.at(beat), v.p.y.at(beat), v.p.z.at(beat), D_VOL);
            return;
        }
        if (hotLane >= 0) {
            Lane7 l = d.lanes.get(hotLane);
            long now = System.currentTimeMillis();
            boolean dbl = now - lastPress < DOUBLE_MS
                && Math.hypot(im.mx - lastPressX, im.my - lastPressY) < 8f * TOUCH;
            lastPress = now;
            lastPressX = im.mx;
            lastPressY = im.my;
            int item = d.pickIn(l.g, s.groupIn);
            while (item == Integer.MIN_VALUE && s.groupIn != 0) {         // outside the entered group: out of it
                s.groupIn = d.parentOf(s.groupIn);
                item = d.pickIn(l.g, s.groupIn);
            }
            if (dbl && item != 0 && item != Integer.MIN_VALUE) {          // 双击进组: then the thing in it
                enterGroup(item);
                item = d.pickIn(l.g, s.groupIn);
            }
            if (item != 0 && item != Integer.MIN_VALUE) {                 // a whole group (组, chart 2.1)
                Group7 gr = d.group(item);
                pickGroup(gr.id, adding());
                if (!adding()) s.sel.clear();
                Group7.Pose pp = Scene.pose(d.parentOf(gr.id));
                float px = gr.P.x.at(beat), py = gr.P.y.at(beat), pz = gr.P.z.at(beat);
                float[] w = {px, py, pz};
                if (pp != null) pp.at(beat).point(px, py, pz, w);
                startMove(gr.id, w[0], w[1], w[2], D_GROUP);
                dV0[0] = px;
                dV0[1] = py;
                dV0[2] = pz;
                dPose = pp;
                return;
            }
            dUnpick = !adding() && l.id == s.laneSel;
            pickLane(l.id, adding());
            if (!adding()) s.sel.clear();
            float[] lf = lfs[hotLane];                                  // its origin where its group takes it
            startMove(l.id, lf[Scene.O], lf[Scene.O + 1], lf[Scene.O + 2], D_LANE);
            dV0[0] = l.v.x.at(beat);
            dV0[1] = l.v.y.at(beat);
            dV0[2] = l.v.z.at(beat);
            dPose = Scene.pose(l.g);
            return;
        }
        if (!adding()) {
            s.sel.clear();
            if (insp.tab == Inspector.VOLUME) s.volSel = 0;
            s.groupSel = s.groupIn = 0;                                  // and out of any group
            s.pickGroups.clear();
            s.pickLanes.clear();
            if (insp.tab == Inspector.SCENE) s.laneSel = Session.NO_LANE;  // the 场景 page: nothing picked
            else if (s.laneSel != Session.NO_LANE) s.pickLanes.add(s.laneSel);
        }
    }

    /** Starts moving a lane's origin or a volume's centre (at x, y, z now) in the plane through it facing the eye. */
    private void startMove(int id, float x, float y, float z, int kind) {
        dId = id;
        dAt0[0] = dV0[0] = x;
        dAt0[1] = dV0[1] = y;
        dAt0[2] = dV0[2] = z;
        dPose = null;
        if (!sc.onFacing(dir, x, y, z, dGrab)) return;
        drag = kind;
        dMarked = false;
        moved = false;
    }

    private boolean moved;

    private void dragPreview() {
        if (scene.handle != 0) {
            scene.drag();
            return;
        }
        if (drag == D_NONE) return;
        if (drag == D_PRESS_KEY && Math.hypot(im.mx - im.pressX, im.my - im.pressY) > 4f) {
            drag = D_MOVE_KEY;
            dSel.clear();
            dSel.addAll(s.selected());
        }
        float[] lf = frame(dLaneIx);
        sc.ray(im.mx, im.my, dir);
        if (drag == D_MOVE_KEY && lf != null && !Float.isNaN(dUp0)) {
            float up = upOn(lf);
            if (Float.isNaN(up)) return;
            float dh = (up - dUp0) * (dKey.k.sky ? -1f : 1f) / Math.max(1e-6f, lf[Scene.KS]);
            float by = im.alt ? round3(dh / Doc.APPROACH) : snapDelta(dh / Doc.APPROACH);
            if (by != dApplied) {
                lazyMark();
                for (Doc.DKey k : dSel) Doc.shiftKey(k.k, by - dApplied);
                dApplied = by;
                s.doc.sortKeys();
                s.changed(true);
            }
        } else if ((drag == D_PLACE || drag == D_LENGTH) && lf != null) {
            float up = upOn(lf);
            if (Float.isNaN(up)) return;
            Key7 k = dKey.k;
            float hNow = (k.sky ? lf[Scene.SKY] - up : up - lf[Scene.GND]) / Math.max(1e-6f, lf[Scene.KS]);
            float len = (hNow - k.h.at(beat)) / Doc.APPROACH;
            len = im.alt ? round3(len) : snapDelta(len);
            float min = step() > 0f ? step() : 0.25f;
            if (drag == D_PLACE && len < min) len = LONG_DEFAULT;
            len = Math.max(min, len);
            if (Math.abs(len - Doc.length(k)) > 1e-4f) {
                lazyMark();
                Doc.setLength(k, len);
                s.changed(true);
            }
        } else if (drag == D_LANE || drag == D_VOL || drag == D_GROUP) {
            if (!sc.onFacing(dir, dAt0[0], dAt0[1], dAt0[2], p3)) return;
            float[] to = new float[3];
            for (int i = 0; i < 3; i++) q3[i] = p3[i] - dGrab[i];
            if (dPose != null) toGroup(dPose.at(beat), q3);
            for (int i = 0; i < 3; i++) to[i] = scene.snapLen(dV0[i] + (float) q3[i]);  // on the grid (场景 page)
            if (!moved && Math.hypot(im.mx - im.pressX, im.my - im.pressY) < 4f) return;
            Fn[] fs;
            if (drag == D_LANE) {
                Lane7 l = s.doc.lane(dId);
                if (l == null) return;
                fs = new Fn[]{l.v.x, l.v.y, l.v.z};
            } else if (drag == D_GROUP) {
                Group7 gr = s.doc.group(dId);
                if (gr == null) return;
                fs = new Fn[]{gr.P.x, gr.P.y, gr.P.z};
            } else {
                Doc.DVol v = s.doc.volByUid(dId);
                if (v == null) return;
                fs = new Fn[]{v.v.p.x, v.v.p.y, v.v.p.z};
            }
            lazyMark();
            float t = snap(beat);
            for (int i = 0; i < 3; i++) if (fs[i].at(beat) != to[i]) Doc.setAt(fs[i], t, to[i]);
            moved = true;
            s.changed(true);
        }
    }

    /** Turns move q (chart axes) into the axes of the group whose pose is p: back through its turn, over its size. */
    static void toGroup(Group7.Pose p, double[] q) {
        double x = q[0], y = q[1], z = q[2], k = Math.max(1e-6, p.k);
        for (int i = 0; i < 3; i++) q[i] = (p.m[i] * x + p.m[3 + i] * y + p.m[6 + i] * z) / k;
    }

    private void lazyMark() {
        if (dMarked) return;
        dMarked = true;
        s.mark();
    }

    private void endDrag() {
        layers.endDrag();
        scene.release();
        if (drag == D_LANE && !moved && dUnpick && im.released) s.laneSel = Session.NO_LANE;
        if (drag == D_PV_START || drag == D_PV_END) previewTo(dPv0, dPv1);
        if ((drag == D_PAN) && !dPanMoved && im.released) seekBeat(snap((float) beatAtX(im.mx)));
        if (dMarked && drag != D_NONE) s.changed(false);
        drag = D_NONE;
        dMarked = false;
        dKey = null;
    }

    // ================================================================== timeline

    /** 声部分析: shows and plays voice {@code v} (-1 = the whole song); the first pick starts the analysis. */
    void pickVoice(int v) {
        if (v >= 0 && s.song.audio == null) {
            im.toast("这首歌没有音频文件");
            return;
        }
        if (v >= 0 && voices == null) {
            voices = new VoiceSplit(s.song.audio, s.lib.home.child("voices").child(s.song.id));
        }
        wantVoice = v;
    }

    /** Puts the picked voice (or the whole song) on the player once its analysis is there, keeping time and play. */
    private void followVoice() {
        if (voices.failed()) {
            im.toast("声部分析没做成：" + voices.error());
            voices = null;
            wantVoice = -1;
        } else if (wantVoice >= 0 && !voices.ready()) {
            return;
        }
        voice = wantVoice;
        double p = pb.pos();
        boolean was = pb.playing();
        pb.load(voice < 0 || voices == null ? s.song.audio : voices.wav(voice));
        pb.seek(p);
        if (was) pb.play();
    }

    /** The picked voice in the 音频 row: its loudness, its onsets (brighter = stronger), the melody's pitch (dots). */
    private void voiceRow() {
        Color c = VOICE_COLOURS[voice];
        for (float x = TX; x < TR; x += 3f) {
            double m0 = s.beatToMs(beatAtX(x)), m1 = s.beatToMs(beatAtX(x + 3f));
            float hh = Math.max(1f, voices.level(voice, m0, m1) * 13f);
            g.rect(x, R_AUDIO + 15f - hh, 2f, hh * 2f, c, 0.35f);
            int m = voice == VoiceSplit.MELODY ? voices.pitchAt((m0 + m1) * 0.5) : 0;
            float span = VoiceSplit.HIGH_MIDI - VoiceSplit.LOW_MIDI;
            if (m > 0) g.rect(x, R_AUDIO + 28f - 26f * (m - VoiceSplit.LOW_MIDI) / span, 3f, 2f, Gfx.INK1, 0.9f);
        }
        float[] on = voices.onsets(voice), st = voices.strengths(voice);
        int i = java.util.Arrays.binarySearch(on, (float) s.beatToMs(tlStart));
        double end = s.beatToMs(tlStart + tlSpan);
        for (i = i < 0 ? -i - 1 : i; i < on.length && on[i] <= end; i++) {
            float x = bx(s.msToBeat(on[i]));
            g.line(x, R_AUDIO + 1f, x, R_AUDIO + 29f, 2f, c, 0.15f + 0.85f * st[i]);
        }
    }

    /**
     * The line between the preview and the timeline (SPEC §2): dragged up the timeline grows and the preview gets
     * smaller (16 : 10 kept), dragged down the other way, at most back to the biggest preview.
     */
    private void splitter() {
        float hz = TAB ? 8f : 4f;
        boolean on = drag == D_NONE && !im.modalOpen() && !im.popupOpen()
            && im.hover(64f, TL_TOP - hz, TR - 64f, hz * 2f);
        if (on || splitting) im.cursor(Cursor.SystemCursor.VerticalResize);
        if (on && im.pressed && !im.rightPressed) splitting = true;
        if (splitting) {
            if (im.down) layout(im.my);
            else splitting = false;
        }
        float cx = TX + TW * 0.5f;
        g.rrect(cx - 22f, TL_TOP - 2f, 44f, 4f, 2f, on || splitting ? Gfx.INK2 : Gfx.SEL_LINE);
    }

    /**
     * The timeline (Kelvin 2026-11-11 16:39, SPEC §2): the ruler on top (the playhead's beat in a box; a drag moves
     * the playhead), the 音频 strip (waveform or a voice, the song list's preview window, BPM tags) and the layers
     * ({@link Layers}); on the left 只看选中, 搜索轨道, 曲线 › and 音频.
     */
    private void timeline() {
        layers.block();
        g.rect(64f, TL_TOP, TR - 64f, TL_BOTTOM - TL_TOP, Gfx.BAR2);
        g.line(64f, TL_TOP, TR, TL_TOP, 1f, Gfx.LINE);
        splitter();
        if (curves.on) return;                                       // the curve window draws here after the inspector
        header();
        im.pushClip(TX, TL_TOP, TW, TL_BOTTOM - TL_TOP);
        grid();
        audioStrip();
        im.popClip();
        layers.draw();
        im.pushClip(TX, TL_TOP, TW, TL_BOTTOM - TL_TOP);
        float px = bx(beat);                                          // the playhead, its beat on the ruler
        g.line(px, R_RULER + RULER_H, px, TL_BOTTOM, 2f, Color.WHITE);
        String bl = String.format(Locale.ROOT, "%.2f", beat);
        float bw = g.width(bl, 11) + 14f;
        g.rrect(px - bw * 0.5f, R_RULER + 1f, bw, RULER_H - 2f, 4f, Gfx.PRI_TOP);
        g.text(bl, px, R_RULER + RULER_H * 0.5f, 11, Gfx.PRI_INK, 1);
        im.popClip();
        timelineInput();
    }

    /** Left of the ruler: 只看选中, 搜索轨道 (a lane's name or number) and 曲线 ›; left of the strip: 音频. */
    private void header() {
        float y = R_RULER, h = RULER_H, x = 68f;
        float w1 = TAB ? 64f : 78f, w2 = TAB ? 72f : 110f, w3 = TAB ? 40f : 54f, fx = x + w1 + 4f;
        if (im.button("tl-only", x, y, w1, h, "只看选中", layers.onlySel ? Im.ON : Im.GHOST, 11)) {
            layers.onlySel = !layers.onlySel;
        }
        String typed = im.field("tl-find", fx, y, w2, h, layers.filter, "", false);
        if (typed != null) layers.filter = typed.trim();
        String live = im.live("tl-find");
        layers.find = live != null ? live : layers.filter;
        if (layers.find.isEmpty() && live == null) g.text("搜索轨道", fx + 8f, y + h * 0.5f, 11, Gfx.INK3);
        if (im.button("tl-curves", fx + w2 + 4f, y, w3, h, "曲线 ›", Im.GHOST, 11)) curves.show(insp.openId());
        int vp = im.dropdown("tl-voice", 68f, R_AUDIO + 2f, TAB ? 80f : 84f, AUDIO_H - 4f,
            wantVoice < 0 ? "音频" : VoiceSplit.NAMES[wantVoice], VOICE_ITEMS, wantVoice + 1);
        if (vp >= 0 && vp - 1 != wantVoice) pickVoice(vp - 1);
        if (TAB) zoomButtons();
    }

    /** The ruler's beats and the beat and snap lines down through the rows. */
    private void grid() {
        double ppb = TW / tlSpan;
        int b0 = (int) Math.floor(tlStart), b1 = (int) Math.ceil(tlStart + tlSpan);
        float st = step();
        if (st > 0f && ppb * st >= 10.0) {
            for (int k = (int) Math.floor(tlStart / st); k * st <= tlStart + tlSpan; k++) {
                float x = bx(k * st);
                g.line(x, R_ROWS, x, TL_BOTTOM, 1f, Gfx.LINE2, 0.5f);
            }
        }
        int labelEvery = 1;
        while (ppb * labelEvery < 44.0) labelEvery *= 2;
        for (int b = b0; b <= b1; b++) {
            float x = bx(b);
            boolean bar = Math.floorMod(b, 4) == 0;
            g.line(x, R_ROWS, x, TL_BOTTOM, 1f, Gfx.LINE, bar ? 0.9f : 0.45f);
            g.line(x, R_RULER + RULER_H - 4f, x, R_RULER + RULER_H, 1f, Gfx.INK3, bar ? 0.9f : 0.5f);
            if (Math.floorMod(b, labelEvery) == 0) {
                g.text(Integer.toString(b), x + 3f, R_RULER + RULER_H * 0.5f - 1f, 11, Gfx.INK3);
            }
        }
        g.line(TX, R_RULER + RULER_H, TR, R_RULER + RULER_H, 1f, Gfx.LINE);
    }

    /** The 音频 strip: the waveform or the picked voice, the song list's preview window, the BPM tags. */
    private void audioStrip() {
        if (wantVoice >= 0 && voices != null && !voices.ready()) {
            g.text("正在分析声部… " + Math.round(voices.progress() * 100f) + "%（每首歌只算一次，之后直接读）",
                TX + 12f, R_AUDIO + 15f, 12, Gfx.INK3);
        } else if (voice >= 0 && voices != null) voiceRow();
        else if (!wave.ready()) g.text("正在读取波形…", TX + 12f, R_AUDIO + 15f, 12, Gfx.INK3);
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
            float pv0 = bx(w0), pv1 = bx(w1), h = AUDIO_H;
            if (pv1 > TX && pv0 < TR) {
                g.rect(pv0, R_AUDIO, pv1 - pv0, h, Gfx.TIDE, 0.16f);
                for (float x : new float[]{pv0, pv1}) {
                    g.line(x, R_AUDIO, x, R_AUDIO + h, 2f, Gfx.TIDE, 0.9f);
                    g.rrect(x - 4f, R_AUDIO + h * 0.5f - 7f, 8f, 14f, 2f, Gfx.TIDE);
                }
                String l = "预览 " + Im.fmt(w0) + " – " + Im.fmt(w1) + " 拍";
                float lx = Math.max(pv0, TX) + 8f;
                g.rect(lx - 3f, R_AUDIO + h - 15f, g.width(l, 11) + 6f, 14f, Gfx.BAR2, 0.85f);
                g.text(l, lx, R_AUDIO + h - 8f, 11, Gfx.TIDE);
            }
        }

        // tempo markers
        Doc d = s.doc;
        Doc.Tempo pinned = d.bpms.get(0);
        for (Doc.Tempo sg : d.bpms) if (sg.beat <= tlStart) pinned = sg;
        for (Doc.Tempo sg : d.bpms) {
            float x = bx(sg.beat);
            if (sg == pinned && sg.beat <= tlStart) x = TX + 2f;
            else if (x < TX - 70f || x > TR) continue;
            else g.dashed(x, R_AUDIO + 16f, x, R_AUDIO + AUDIO_H, 1f, 4f, Gfx.BPM, 0.8f);
            g.rrect(x, R_AUDIO + 1f, 62f, 16f, 3f, Gfx.BPM);
            g.text("♩ " + Im.fmt(sg.bpm), x + 6f, R_AUDIO + 9f, 11, Gfx.PRI_INK);
        }
    }

    /** The ruler and the 音频 strip: a press on the ruler moves the playhead; on the strip it pans, as before. */
    private void timelineInput() {
        if (im.modalOpen() || splitting) return;
        boolean in = im.hover(TX, TL_TOP, TW, TL_BOTTOM - TL_TOP);
        if (in && im.wheel != 0f && !im.shift) wheelTime();
        boolean strip = im.my >= R_AUDIO && im.my < R_ROWS;
        boolean onEdge = drag == D_NONE && in && strip && pvEdge(im.mx) != D_NONE;
        if (onEdge || drag == D_PV_START || drag == D_PV_END) im.cursor(Cursor.SystemCursor.HorizontalResize);
        if (in && im.pressed && drag == D_NONE && !im.popupOpen() && im.my >= R_RULER && im.my < R_ROWS) {
            dMarked = false;
            if (strip && im.my < R_AUDIO + 17f && hitTempo(im.mx)) {
                insp.tab = Inspector.CHART;
            } else if (onEdge) {
                drag = pvEdge(im.mx);
                dPv0 = pvBeat(false);
                dPv1 = pvBeat(true);
            } else if (strip) {
                drag = D_PAN;
                dPanBeat = beat;
                dPanStart = tlStart;
                dPanMoved = false;
                dPanPlay = pb.playing();
            } else drag = D_SEEK;
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
        }
    }

    /** @return D_PV_START / D_PV_END when x is within 6 px of that edge of the preview band, else D_NONE. */
    private int pvEdge(float x) {
        if (s.song.tutorial) return D_NONE;
        float d0 = Math.abs(x - bx(pvBeat(false))), d1 = Math.abs(x - bx(pvBeat(true)));
        return Math.min(d0, d1) > 6f * TOUCH ? D_NONE : d1 <= d0 ? D_PV_END : D_PV_START;
    }

    private boolean hitTempo(float mx) {
        for (Doc.Tempo sg : s.doc.bpms) {
            float x = sg.beat <= tlStart ? TX + 2f : bx(sg.beat);
            if (mx >= x && mx <= x + 62f) return true;
        }
        return false;
    }

    // ================================================================== bars

    private void topBar() {
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
        if (!TAB) keyButton(bx + 104f, 14f, 100f, 28f);              // on a tablet it is in the touch bar
        float bp = bx + (TAB ? 90f : 216f);
        g.text("BPM", bp, 28f, 12, Gfx.INK3);
        g.text(Im.fmt(s.doc.bpmAt(beat)), bp + (TAB ? 32f : 40f), 28f, 16, Gfx.BPM);

        float sn = TAB ? 906f : 1456f;
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
        Color scl = s.dirty ? Gfx.SYNC : Gfx.OK;
        g.disc(wx + 58f, 28f, 4f, scl);
        g.text(s.dirty ? "未保存" : "已保存", wx + 67f, 28f, 12, scl);
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
        if (insp.tab == Inspector.SCENE) {                              // 选择, 方块, 阵列, 镜像, 成组
            scene.rail();
            return;
        }
        scene.arrayOn = false;
        if (tool == T_BOX) tool = T_SELECT;
        for (int i = 0; i < TOOL_ORDER.length; i++) {
            int t = TOOL_ORDER[i];
            float y = TOOL_Y[i];
            boolean on = tool == t;
            boolean hv = im.hover(6f, y, 52f, TOOL_H);
            if (on) g.box(6f, y, 52f, TOOL_H, 8f, Gfx.SEL, Gfx.GND);
            else if (hv) g.rrect(6f, y, 52f, TOOL_H, 8f, Gfx.BTN);
            icon(t, 32f, y + 16f);
            String n = TOOL_NAMES[t];
            g.text(n, 32f, y + 35f, n.length() > 3 ? 9 : 11, on ? Gfx.INK1 : Gfx.INK2, 1);
            if (!TAB) g.text(TOOL_KEYS[t], 52f, y + 8f, 9, Gfx.INK3, 1);
            if (hv) im.cursor(Cursor.SystemCursor.Hand);
            if (im.click(new Im.Pair("tool", t), 6f, y, 52f, TOOL_H)) setTool(t);
        }
        g.line(14f, 116f, 50f, 116f, 1f, Gfx.LINE);
        g.line(14f, 561f, 50f, 561f, 1f, Gfx.LINE);
        g.line(14f, 669f, 50f, 669f, 1f, Gfx.LINE);
        boolean sky = s.placeSky;
        if (im.click("rail-face", 6f, 676f, 52f, 36f)) s.placeSky = !sky;
        g.text(sky ? "放天面" : "放底面", 32f, 686f, 11, sky ? UP_INK : Gfx.GND, 1);
        g.text(TAB ? "点这里换" : "Tab", 32f, 702f, 10, Gfx.INK3, 1);
    }

    /** The tablet's row of buttons under the preview: what the keyboard does on the desktop. */
    private void touchBar() {
        float x = PX0, y = AB_Y, h = AB_H;
        if (im.button("t-undo", x, y, 60f, h, "撤销", Im.NORMAL, 14)) undo();
        if (im.button("t-redo", x + 66f, y, 60f, h, "重做", Im.NORMAL, 14)) redo();
        x += 138f;
        if (im.button("t-copy", x, y, 56f, h, "复制", Im.NORMAL, 14)) copy(false);
        if (im.button("t-paste", x + 62f, y, 56f, h, "粘贴", Im.NORMAL, 14)) paste();
        if (im.button("t-del", x + 124f, y, 56f, h, "删除", Im.DANGER, 14)) {
            if (insp.tab == Inspector.VOLUME && s.volSel != 0) deleteVol();
            else deleteSel();
        }
        if (im.button("t-all", x + 186f, y, 56f, h, "全选", Im.NORMAL, 14)) selectAll();
        if (im.button("t-multi", x + 248f, y, 56f, h, "多选", multi ? Im.ON : Im.NORMAL, 14)) multi = !multi;
        x += 316f;
        if (im.button("t-prev", x, y, 40f, h, "◀", Im.NORMAL, 14)) stepTime(-1);
        if (im.button("t-next", x + 46f, y, 40f, h, "▶", Im.NORMAL, 14)) stepTime(1);
        keyButton(x + 92f, y, 104f, h);
        String n = (s.sel.isEmpty() ? "" : "已选 " + s.sel.size() + " · ") + "共 " + s.doc.keys.size();
        if (SB_X - 10f - g.width(n, 12) > x + 204f) g.text(n, SB_X - 10f, y + h * 0.5f, 12, Gfx.INK3, 2);
        if (im.button("stats", SB_X, SB_Y, SB_W, SB_H, "统计", statsOpen ? Im.ON : Im.NORMAL, 13)) statsOpen = !statsOpen;
    }

    /** The tablet's − / + by the timeline's ruler: zoom about the playhead, as Ctrl + wheel does. */
    private void zoomButtons() {
        float f = 0f;
        if (im.button("t-out", 152f, R_AUDIO + 2f, 34f, AUDIO_H - 4f, "−", Im.NORMAL, 16)) f = 1.25f;
        if (im.button("t-in", 190f, R_AUDIO + 2f, 34f, AUDIO_H - 4f, "+", Im.NORMAL, 16)) f = 0.8f;
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
            case T_LMINE:
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
            case T_DRAG:
                g.rect(cx - 13f, cy - 2.5f, 26f, 5f, Theme.DRAG);
                g.tri(cx - 7f, cy, cx, cy - 8f, cx + 7f, cy, Theme.DRAG_LIGHT, 1f);
                g.tri(cx - 7f, cy, cx, cy + 8f, cx + 7f, cy, Theme.DRAG_DEEP, 1f);
                break;
            case T_TIDE:
            case T_TSTREAM:
            case T_TMINE:
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
        Group7 gr = s.doc.group(s.groupSel);
        if (insp.tab == Inspector.SCENE && scene.arrayOn) {             // 阵列
            x = keycap(x, "Enter", "确定");
            x = keycap(x, "Esc", "取消");
            x = keycap(x, "Ctrl+D", "复制一个");
            x = keycap(x, "Ctrl+G", "成组");
        } else if (insp.tab == Inspector.SCENE) {                       // 组 and the scene tools
            x = keycap(x, "点", "选中整组");
            x = keycap(x, "双击", "进组");
            x = keycap(x, "拖箭头", "移动");
            x = keycap(x, "拖圈", "旋转");
            x = keycap(x, "B", "放方块");
            x = keycap(x, "Ctrl+G", "成组");
        } else {
            x = keycap(x, "点击", "放置");
            x = keycap(x, "拖", "长度/移动");
            x = keycap(x, "滚轮", "走时间");
            x = keycap(x, "Ctrl+滚轮", "缩放");
            x = keycap(x, "Ctrl+C/X/V/Z", "");
            x = keycap(x, "Del", "删除");
            x = keycap(x, "Tab", "天面/底面");
            x = keycap(x, "Ctrl+Alt+拖", "转视角");
        }
        keycap(x, "K", "关键帧");
        String right;
        Lane7 l = s.doc.lane(s.laneSel);
        if (insp.tab == Inspector.SCENE && scene.arrayOn) {
            right = "阵列 · " + (scene.circle ? "一圈 " : "一行 ") + Math.max(2, scene.count) + " 个";
        } else if (insp.tab == Inspector.SCENE && gr != null) {
            right = "组「" + Doc.groupLabel(gr) + "」 · " + Keyframes.points(Keyframes.group(gr)).length + " 个关键帧";
        } else if ((insp.tab == Inspector.LANE || insp.tab == Inspector.SCENE) && l != null) {
            right = "轨道 " + s.doc.laneLabel(l.id) + " · " + Keyframes.points(Keyframes.lane(l)).length + " 个关键帧";
        } else {
            right = "共 " + s.doc.keys.size() + " 个键" + (s.sel.isEmpty() ? "" : " · 已选 " + s.sel.size());
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
