package org.kelvinizer.charter;

import org.kelvinizer.elements.Chart;
import org.kelvinizer.elements.Events;
import org.kelvinizer.elements.Floating;
import org.kelvinizer.elements.JudgementLine;
import org.kelvinizer.elements.LaneLayout;
import org.kelvinizer.elements.Line;
import org.kelvinizer.elements.Note;
import org.kelvinizer.elements.NoteDistortion;
import org.kelvinizer.elements.NoteGlitch;
import org.kelvinizer.elements.NoteOrbit;
import org.kelvinizer.elements.NoteTrail;
import org.kelvinizer.elements.NoteType;
import org.kelvinizer.elements.Stroke;
import org.kelvinizer.elements.Veil;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 正在编辑的谱面（可以修改）：和游戏的谱面（1920 × 1200 坐标，判定线 1080 / 120）一一对应；没有轨道，每个键有自己的横坐标
 * x（屏幕中线为 0）和 x 事件。自己的文字是完整的 v4 加「laneless」标记，保存时换成 v6。
 * The chart being edited (mutable). It mirrors the game's chart (positions in the 1920 × 1200 frame, lines at
 * 1080 / 120) one to one, without lanes: every key has its own x (0 = the centre line) and x events (Kelvin
 * 2026-11-05). {@link #toJson} writes it as complete, explicit v4 text marked {@code "laneless": true}
 * ({@link #TEXT_FORMAT}), which {@link Library#writeChart} turns into the format 6 file (CHART_FORMAT §9). Reading
 * goes through the game's own {@code ChartMapper} ({@link #fromChart}), so older files arrive converted.
 */
public final class Doc {
    /** Default spawn distance of the approach template: the game's default approach (1080). */
    public static final float SPAWN = Note.DEFAULT_SPAWN_DISTANCE;
    /** Default approach speed (ref units per beat): {@link #SPAWN} over the game's 2 beats (540). */
    public static final float APPROACH = SPAWN / Note.DEFAULT_APPROACH_BEATS;

    public String title = "", artist = "", illustrator = "", charter = "", level = "";
    public String constant = "1";
    /**
     * 迭代次数 (editor-only {@code "iterations"}, Kelvin 2026-10-16): how many backed-up versions the chart
     * has been through. {@link Session#save} adds one whenever it backs the old file up; the game never reads it.
     */
    public int iterations;
    /** Tempo segments, sorted by beat; never empty. The first one also covers earlier beats. */
    public final ArrayList<Seg> bpms = new ArrayList<>();
    public float offsetMs;
    /**
     * 流速 BPM (editor-only {@code flowBpmList}, Kelvin 2026-10-15): from {@code beat} on, falls under
     * {@link #flowFall} count their beats at this BPM, so a fast song need not fall fast. Sorted by
     * beat; empty = the tempo itself. The falls are still written as plain beats for the game.
     */
    public final ArrayList<Seg> flows = new ArrayList<>();
    /** 统一流速 (editor-only {@code "fallBy": "flow"}): falls are timed by {@link #flows}; false = 统一拍数. */
    public boolean flowFall;
    public float upper = JudgementLine.DEFAULT_UPPER, lower = JudgementLine.DEFAULT_LOWER;
    public final ArrayList<Ev> upperEv = new ArrayList<>(), lowerEv = new ArrayList<>();
    /**
     * Camera frame (LineEvents {@code cameraFrame} / {@code cameraFrameEvents}): how far right of the
     * screen centre every key is moved, in reference units. Replaces the old left / right bounds.
     */
    public float cameraFrame;
    public final ArrayList<Ev> cameraFrameEv = new ArrayList<>();
    /**
     * Global scale (LineEvents {@code laneScale} / {@code laneScaleEvents}): every key's x (about the centre line)
     * and width × this.
     */
    public float laneScale = 1f;
    public final ArrayList<Ev> laneScaleEv = new ArrayList<>();
    /**
     * 定轨 / 无轨 (game 7.184, CHART_FORMAT §12, Kelvin 2026-11-06): from each section's beat on, the field is split
     * into that many equal lanes (0 = 无轨). Sorted by beat; empty = 无轨 throughout; a new chart starts with
     * {@link Chart#DEFAULT_FIXED_LANES} from beat 0. Keys keep their own x: the lanes guide placing them
     * ({@link EditorScreen#snapX(float, float)}) and widen the judging.
     */
    public final ArrayList<Lanes> fixedLanes = new ArrayList<>();
    /** 判定线叠加层 (game 7.177, CHART_FORMAT §10): events whose eased values add to a line's y or the camera. */
    public final ArrayList<Ev> upperLay = new ArrayList<>(), lowerLay = new ArrayList<>(),
            cameraLay = new ArrayList<>();
    public final ArrayList<DNote> notes = new ArrayList<>();
    /** Veils in file order (§6.10). */
    public final ArrayList<DVeil> veils = new ArrayList<>();
    /** Curtains (水帘, CHART_FORMAT §6.1) in file order: veils that only blur what is under them. */
    public final ArrayList<DVeil> curtains = new ArrayList<>();
    /** 自定义线条 (game 7.187, CHART_FORMAT §13) in file order: lines that are only shown. */
    public final ArrayList<DStroke> strokes = new ArrayList<>();

    private static int nextUid = 1;

    // ================================================================== parts

    /** One eased event: {@code startTime/endTime/initial/final/exponent} (CHART_FORMAT §4). */
    public static final class Ev {
        public float start, end, from, to, exp = 1f;
        /**
         * Note events only: relative to the note's judgement (editor-only key {@code "rel"}; beats
         * stay absolute in the file). It follows the judgement when that moves and is shown as
         * offsets; an absolute one stays where it is. Without the key an event is absolute, except
         * the default fall ({@link DNote#defaultFall}), as {@link #readRelFlags} reads it.
         */
        public boolean rel;
        /**
         * Movement only: a fall of this many flow beats ending at the judgement (editor-only key
         * {@code "flow"}); {@link Doc#reflow} keeps its start on the tempo and flow BPM. NaN = none.
         */
        public float flow = Float.NaN;
        /**
         * 同步链接 (editor-only key {@code "link"}, Kelvin 2026-11-05 「保持链接」): a source's own id, or on a linked
         * copy the source it follows and how ({@link Link}); {@link Doc#relink} keeps the copies on their source.
         */
        public Link link;
        /** A linked copy as {@link Doc#relink} last set it: beats (from the judgement in 按各自判定), values, exponent. */
        float[] linked;
        /**
         * 快捷曲线 / 快捷事件 (Kelvin 2026-11-06): the cards one quick curve or quick event made share a group (0 =
         * none) and its name, so a click picks them together and a new curve turns them as one. Not saved or copied.
         */
        int group;
        String tag;

        public Ev(float start, float end, float from, float to, float exp) {
            this.start = start;
            this.end = end;
            this.from = from;
            this.to = to;
            this.exp = exp;
        }

        public Ev copy() {
            Ev e = new Ev(start, end, from, to, exp);
            e.rel = rel;
            e.flow = flow;
            e.link = link;
            e.linked = linked;
            return e;
        }

        /** @return the eased value at {@code t} (same formula as the game's {@code Events}). */
        public float valueAt(float t) {
            if (end <= start) return to;
            float p = Math.max(0f, Math.min(1f, (t - start) / (end - start)));
            return from + (to - from) * (float) Math.pow(p, exp);
        }
    }

    /**
     * 同步链接 of one event (Kelvin 2026-11-05): a source has an {@code id}; a linked copy names the source it follows
     * and is {@code dt} beats later and {@code dv} higher, in 按各自判定 ({@code judge}) also shifted by its key's
     * judgement minus the source key's. Written {@code "link": {"id": 3}} or {@code {"of": 3, "dt": 0, "dv": -120}}.
     */
    public static final class Link {
        public final int id;
        public final boolean src, judge;
        public final float dt, dv;

        private Link(int id, boolean src, float dt, float dv, boolean judge) {
            this.id = id;
            this.src = src;
            this.dt = dt;
            this.dv = dv;
            this.judge = judge;
        }

        public static Link source(int id) {
            return new Link(id, true, 0f, 0f, false);
        }

        public static Link copyOf(int id, float dt, float dv, boolean judge) {
            return new Link(id, false, dt, dv, judge);
        }

        String json() {
            return src ? "{ \"id\": " + id + " }" : "{ \"of\": " + id + ", \"dt\": " + num(dt) + ", \"dv\": " + num(dv)
                + (judge ? ", \"judge\": true }" : " }");
        }

        /** @return the link a file's {@code "link"} value names, null for none or an unreadable one */
        static Link read(com.badlogic.gdx.utils.JsonValue v) {
            if (v == null || !v.isObject()) return null;
            if (v.has("id")) return source(v.getInt("id", 0));
            float dt = v.getFloat("dt", 0f), dv = v.getFloat("dv", 0f);
            return v.has("of") && Float.isFinite(dt) && Float.isFinite(dv)
                ? copyOf(v.getInt("of", 0), dt, dv, v.getBoolean("judge", false)) : null;
        }
    }

    /** A tempo segment: from {@code beat} on, {@code bpm} beats per minute. */
    public static final class Seg {
        public float beat, bpm;

        public Seg(float beat, float bpm) {
            this.beat = beat;
            this.bpm = bpm;
        }
    }

    /** A 定轨 section: from {@code beat} on, {@code count} fixed lanes (0 = 无轨). */
    public static final class Lanes {
        public float beat;
        public int count;

        public Lanes(float beat, int count) {
            this.beat = beat;
            this.count = count;
        }
    }

    /** Drag afterimages of a note (§6.8). {@code from}/{@code to} are NaN when unbounded. */
    public static final class DTrail {
        public float length = NoteTrail.SUGGESTED_LENGTH, density = NoteTrail.SUGGESTED_DENSITY;
        public float alpha = NoteTrail.DEFAULT_ALPHA, fade = NoteTrail.DEFAULT_FADE;
        public boolean level;
        public float from = Float.NaN, to = Float.NaN;

        public DTrail copy() {
            DTrail t = new DTrail();
            t.length = length;
            t.density = density;
            t.alpha = alpha;
            t.fade = fade;
            t.level = level;
            t.from = from;
            t.to = to;
            return t;
        }
    }

    /**
     * A glitch segment of a note (§6.9, visual only). {@code from}/{@code to} are NaN when unbounded;
     * {@code intensityEnd} is NaN when the intensity does not ramp.
     */
    public static final class DGlitch {
        public float from = Float.NaN, to = Float.NaN;
        public float intensity = NoteGlitch.DEFAULT_INTENSITY, intensityEnd = Float.NaN, exponent = 1f;
        public float rate = NoteGlitch.DEFAULT_RATE, split = NoteGlitch.DEFAULT_SPLIT, slice = NoteGlitch.DEFAULT_SLICE;
        public float flicker = NoteGlitch.DEFAULT_FLICKER;

        public DGlitch copy() {
            DGlitch x = new DGlitch();
            x.from = from;
            x.to = to;
            x.intensity = intensity;
            x.intensityEnd = intensityEnd;
            x.exponent = exponent;
            x.rate = rate;
            x.split = split;
            x.slice = slice;
            x.flicker = flicker;
            return x;
        }
    }

    /**
     * A distortion segment of a note or a veil (§6.11, visual only). {@code from}/{@code to} are NaN when
     * unbounded; {@code intensityEnd} is NaN when the intensity does not ramp.
     */
    public static final class DDist {
        public float from = Float.NaN, to = Float.NaN;
        public float intensity = NoteDistortion.DEFAULT_INTENSITY, intensityEnd = Float.NaN, exponent = 1f;

        public DDist copy() {
            DDist x = new DDist();
            x.from = from;
            x.to = to;
            x.intensity = intensity;
            x.intensityEnd = intensityEnd;
            x.exponent = exponent;
            return x;
        }
    }

    /**
     * A veil (§6.10, Kelvin 2026-10-23): a circle or a polygon anywhere on the field; a finger pressed inside it
     * while it shows judges nothing until it lifts. {@code start}/{@code end} are NaN when unbounded; {@code points}
     * holds x0, y0, x1, y1, … measured from the centre (y up). {@code uid} stays the same across copies.
     */
    public static final class DVeil {
        public final int uid;
        public boolean polygon;
        public float radius = Veil.DEFAULT_RADIUS;
        public float[] points = new float[0];
        /** Centre (x from the centre line, y up; the defaults are the middle of the frame), angle and opacity. */
        public float x = 0f, y = 600f, rotation, opacity = 1f;
        public float start = Float.NaN, end = Float.NaN;
        public final ArrayList<Ev> xEv = new ArrayList<>(), yEv = new ArrayList<>();
        public final ArrayList<Ev> rotEv = new ArrayList<>(), opacityEv = new ArrayList<>();
        public final ArrayList<DGlitch> glitches = new ArrayList<>();
        public final ArrayList<DDist> distortions = new ArrayList<>();
        /** A curtain's blur (reference units) and its events; unused on a veil. */
        public float blur = Veil.DEFAULT_BLUR;
        public final ArrayList<Ev> blurEv = new ArrayList<>();

        public DVeil() {
            this(nextUid++);
        }

        private DVeil(int uid) {
            this.uid = uid;
        }

        public int pointCount() {
            return points.length / 2;
        }

        /** Moves the centre by (dx, dy) together with its x / y events. */
        public void shift(float dx, float dy) {
            x += dx;
            y += dy;
            for (Ev e : xEv) {
                e.from += dx;
                e.to += dx;
            }
            for (Ev e : yEv) {
                e.from += dy;
                e.to += dy;
            }
        }

        /** @param sameUid keep the uid (undo snapshots) or take a new one (duplicate) */
        public DVeil copy(boolean sameUid) {
            DVeil v = sameUid ? new DVeil(uid) : new DVeil();
            v.polygon = polygon;
            v.radius = radius;
            v.points = points.clone();
            v.x = x;
            v.y = y;
            v.rotation = rotation;
            v.opacity = opacity;
            v.start = start;
            v.end = end;
            copyAll(xEv, v.xEv);
            copyAll(yEv, v.yEv);
            copyAll(rotEv, v.rotEv);
            copyAll(opacityEv, v.opacityEv);
            for (DGlitch g : glitches) v.glitches.add(g.copy());
            for (DDist d : distortions) v.distortions.add(d.copy());
            v.blur = blur;
            copyAll(blurEv, v.blurEv);
            return v;
        }
    }

    /**
     * 自定义线条 (game 7.187, CHART_FORMAT §13, Kelvin 2026-11-06): a straight or curved line through its points, x from
     * the centre line and y 0–1200 up like a floating key's centre; only shown, never judged. {@code from} /
     * {@code to} are NaN when unbounded; {@code color} is RGBA8888.
     */
    public static final class DStroke {
        public final int uid;
        public float[] points = new float[0];
        public boolean curve, closed, front;
        public float from = Float.NaN, to = Float.NaN, drawIn, drawOut, width = Stroke.DEFAULT_WIDTH, opacity = 1f;
        public int color = Stroke.DEFAULT_COLOR;
        public final ArrayList<Ev> opacityEv = new ArrayList<>();
        public final ArrayList<DGlitch> glitches = new ArrayList<>();
        public final ArrayList<DDist> distortions = new ArrayList<>();

        public DStroke() {
            this(nextUid++);
        }

        private DStroke(int uid) {
            this.uid = uid;
        }

        public int pointCount() {
            return points.length / 2;
        }

        /** Adds point (x, y) after point {@code at} (−1 = before the first, past the last = at the end). */
        public void addPoint(int at, float x, float y) {
            int i = Math.max(0, Math.min(at + 1, pointCount()));
            float[] p = new float[points.length + 2];
            System.arraycopy(points, 0, p, 0, 2 * i);
            p[2 * i] = x;
            p[2 * i + 1] = y;
            System.arraycopy(points, 2 * i, p, 2 * i + 2, points.length - 2 * i);
            points = p;
        }

        public void removePoint(int i) {
            float[] p = new float[points.length - 2];
            System.arraycopy(points, 0, p, 0, 2 * i);
            System.arraycopy(points, 2 * i + 2, p, 2 * i, p.length - 2 * i);
            points = p;
        }

        /** @param sameUid keep the uid (undo snapshots) or take a new one (duplicate) */
        public DStroke copy(boolean sameUid) {
            DStroke s = sameUid ? new DStroke(uid) : new DStroke();
            s.points = points.clone();
            s.curve = curve;
            s.closed = closed;
            s.front = front;
            s.from = from;
            s.to = to;
            s.drawIn = drawIn;
            s.drawOut = drawOut;
            s.width = width;
            s.opacity = opacity;
            s.color = color;
            copyAll(opacityEv, s.opacityEv);
            for (DGlitch g : glitches) s.glitches.add(g.copy());
            for (DDist d : distortions) s.distortions.add(d.copy());
            return s;
        }
    }

    /** @return RGBA8888 {@code c} as the chart writes it: "#RRGGBB" when opaque, else "#RRGGBBAA". */
    public static String colorText(int c) {
        String t = String.format(java.util.Locale.ROOT, "#%08X", c);
        return (c & 0xFF) == 0xFF ? t.substring(0, 7) : t;
    }

    /** @return "#RRGGBB" or "#RRGGBBAA" (the # may be left out) as RGBA8888, or null when it is neither. */
    public static Integer parseColor(String t) {
        String h = t.trim().startsWith("#") ? t.trim().substring(1) : t.trim();
        if (h.length() != 6 && h.length() != 8 || !h.matches("[0-9A-Fa-f]+")) return null;
        long v = Long.parseLong(h, 16);
        return (int) (h.length() == 6 ? v << 8 | 0xFF : v);
    }

    /** A note. {@code uid} stays the same across copies, so selections survive undo. */
    public static final class DNote {
        public final int uid;
        public NoteType type;
        public Line line;
        /**
         * 横坐标 (game format 6, Kelvin 2026-11-05 「以屏幕中线为y轴设置X坐标」): the key's centre in reference
         * units from the screen's centre line, negative to the left (±960 = the edges at 16:10), before the global
         * scale and camera. Only for {@link #inLane} types; outside every {@link #xEv} event the key sits here.
         */
        public float x;
        public float judgement, duration, height;
        /** Appearance beat as read from the file; NaN for new notes (written as the earliest event). */
        public float appearance = Float.NaN;
        /** The tail {@link Doc#setAutoTail} last set under 统一流速; while the tail keeps it, it follows the flow speed. */
        public float autoH = Float.NaN;
        public final ArrayList<Ev> move = new ArrayList<>(), rot = new ArrayList<>();
        public final ArrayList<Ev> alpha = new ArrayList<>(), heightEv = new ArrayList<>();
        /** 横坐标 events: the key's x by beat (values like {@link #x}); outside every event {@link #x} applies. */
        public final ArrayList<Ev> xEv = new ArrayList<>();
        /**
         * 叠加层 (game 7.177, CHART_FORMAT §10): while one of these events runs its eased value is added to the key's
         * x ({@link #xLay}, keys with a place only), fall position ({@link #posLay}) or rotation ({@link #rotLay});
         * they may overlap and add 0 outside.
         */
        public final ArrayList<Ev> xLay = new ArrayList<>(), posLay = new ArrayList<>(), rotLay = new ArrayList<>();
        /**
         * 公转 (§3, Kelvin 2026-10-29): keys turn about the point ({@link #orbitX}, {@link #orbitY}) instead of
         * their pivot: x from the centre line, y up in the 1200-high frame; its moves hold their end value.
         */
        public boolean orbit;
        public float orbitX = 0f, orbitY = 600f;
        public final ArrayList<Ev> orbXEv = new ArrayList<>(), orbYEv = new ArrayList<>();
        /**
         * 公转半径 / 公转角度 (§3, Kelvin 2026-10-29 20:55, 21:13): with either the key's head goes round the centre at the
         * radius (reference units, negative reaches the other way) in the direction of the angle (degrees, 0 right,
         * counter-clockwise) while the orbit's events last; NaN = not written. Their events hold their end value.
         */
        public float orbitRadius = Float.NaN, orbitAngle = Float.NaN;
        public final ArrayList<Ev> orbRadEv = new ArrayList<>(), orbAngEv = new ArrayList<>();
        /** 物件坐标冲突 (Kelvin 2026-10-29 20:55): which wins, {@link NoteOrbit#CONFLICT_UNSET} until Kelvin picks. */
        public int orbitConflict = NoteOrbit.CONFLICT_UNSET;
        /** 公转半径等比 (game 7.168 "orbitRigid"): the radius scales by min(kx, ky) both ways, so the circle stays round. */
        public boolean orbitRigid;
        /** 假键 (game 7.167 / 7.169 "fake", not on 浪花水雷): drawn until its beat, never judged, counted or heard. */
        public boolean fake;
        /**
         * 悬浮键 (game 7.185 / 7.186, CHART_FORMAT §11, Kelvin 2026-11-06): a tap, hold, DRAG or mine ({@link #canFloat})
         * that shows at ({@link #x}, {@link #y}) from {@link #appearance} (NaN = the game's default) and fills up by
         * its beat instead of falling to a line; it has no fall, rotation, layer, orbit, pivot, tail, trail, glitch
         * or distortion ({@link #toFloating}).
         */
        public boolean floating;
        /** A floating key's centre height, up in the 1200-high frame (600 = the middle of the screen). */
        public float y = Floating.DEFAULT_Y;
        /** A floating key's diameter; NaN = its type's ({@link Floating#defaultSize}). */
        public float size = Float.NaN;
        /** A floating key's height, diameter and fill events (fill: 0 full, the diameter empty; CHART_FORMAT §11.2). */
        public final ArrayList<Ev> yEv = new ArrayList<>(), sizeEv = new ArrayList<>(), fillEv = new ArrayList<>();
        /** A floating DRAG's route: DRAGs with the same chain are joined (null = none). */
        public String chain;

        /** @return true when the fake flag applies to this type (水滴, 水流, 水雷, 浪花 / 潮汐, Drag). */
        public boolean canFake() {
            return type == NoteType.TAP || type == NoteType.HOLD || type == NoteType.MINE || type == NoteType.UNIVERSAL
                || type == NoteType.DRAG;
        }

        /** @return true for an orbit with a radius or an angle ({@link NoteOrbit#polar()}). */
        public boolean polar() {
            return orbit && (!Float.isNaN(orbitRadius) || !orbRadEv.isEmpty() || !Float.isNaN(orbitAngle)
                || !orbAngEv.isEmpty());
        }
        /**
         * Trail segments in file order (one = the old single trail), glitch and distortion segments
         * (§6.8, §6.9, §6.11).
         */
        public final ArrayList<DTrail> trails = new ArrayList<>();
        public final ArrayList<DGlitch> glitches = new ArrayList<>();
        public final ArrayList<DDist> distortions = new ArrayList<>();
        /** Rotation pivot (§6.1.1, Kelvin 2026-10-16): from the note's origin in its own frame; (0, 0) = the origin. */
        public float pivotX, pivotY;
        /** TIDE / TIDE STREAM wave speed (§3, Kelvin 2026-10-28): waves per beat, positive to the right, 0 = still. */
        public float waveSpeed;

        /** @param x the key's {@link #x} (ignored by TIDE kinds) */
        public DNote(NoteType type, Line line, float x, float judgement) {
            this(nextUid++, type, line, x, judgement);
        }

        private DNote(int uid, NoteType type, Line line, float x, float judgement) {
            this.uid = uid;
            this.type = type;
            this.line = line;
            this.x = x;
            this.judgement = judgement;
        }

        /** @return true for STREAM, TIDE STREAM and LANE MINE (notes with a tail). */
        public boolean sustained() {
            return type == NoteType.HOLD || (type != NoteType.TAP && duration > 0f);
        }

        public float end() {
            return sustained() ? judgement + duration : judgement;
        }

        public boolean inLane() {
            return type.inLane();
        }

        /** @return the Chinese display name (水滴, 水流, 水雷, 长水雷, 浪花, 潮汐, 浪花水雷, 潮汐水雷). */
        public String nameZh() {
            return type.displayZh(duration > 0f);
        }

        /** @return true when no movement event is written and the note never approaches (§9 exception). */
        public boolean noApproach() {
            return move.isEmpty() && !Float.isNaN(appearance) && appearance >= judgement;
        }

        /** @param sameUid keep the uid (undo snapshots) or take a new one (paste, duplicate) */
        public DNote copy(boolean sameUid) {
            DNote n = sameUid ? new DNote(uid, type, line, x, judgement) : new DNote(type, line, x, judgement);
            n.duration = duration;
            n.height = height;
            n.appearance = appearance;
            n.autoH = autoH;
            copyAll(move, n.move);
            copyAll(rot, n.rot);
            copyAll(xEv, n.xEv);
            copyAll(xLay, n.xLay);
            copyAll(posLay, n.posLay);
            copyAll(rotLay, n.rotLay);
            n.orbit = orbit;
            n.orbitX = orbitX;
            n.orbitY = orbitY;
            copyAll(orbXEv, n.orbXEv);
            copyAll(orbYEv, n.orbYEv);
            n.orbitRadius = orbitRadius;
            n.orbitAngle = orbitAngle;
            copyAll(orbRadEv, n.orbRadEv);
            copyAll(orbAngEv, n.orbAngEv);
            n.orbitConflict = orbitConflict;
            n.orbitRigid = orbitRigid;
            n.fake = fake;
            n.floating = floating;
            n.y = y;
            n.size = size;
            copyAll(yEv, n.yEv);
            copyAll(sizeEv, n.sizeEv);
            copyAll(fillEv, n.fillEv);
            n.chain = chain;
            copyAll(alpha, n.alpha);
            copyAll(heightEv, n.heightEv);
            for (DTrail t : trails) n.trails.add(t.copy());
            for (DGlitch x : glitches) n.glitches.add(x.copy());
            for (DDist x : distortions) n.distortions.add(x.copy());
            n.pivotX = pivotX;
            n.pivotY = pivotY;
            n.waveSpeed = waveSpeed;
            return n;
        }

        /**
         * Moves the judgement by {@code db}: relative events, the appearance and the trail and glitch windows
         * follow, absolute events stay.
         */
        public void retime(float db) {
            judgement += db;
            if (!Float.isNaN(appearance)) appearance += db;
            shiftRel(move, db);
            shiftRel(rot, db);
            shiftRel(xEv, db);
            shiftRel(xLay, db);
            shiftRel(posLay, db);
            shiftRel(rotLay, db);
            shiftRel(orbXEv, db);
            shiftRel(orbYEv, db);
            shiftRel(orbRadEv, db);
            shiftRel(orbAngEv, db);
            shiftRel(alpha, db);
            shiftRel(heightEv, db);
            shiftRel(yEv, db);
            shiftRel(sizeEv, db);
            shiftRel(fillEv, db);
            moveWindows(db);
        }

        /** Keeps the glitch segments in the game's order (by start, unbounded first; stable). */
        public void sortGlitches() {
            glitches.sort((p, q) -> Float.compare(Float.isNaN(p.from) ? Float.NEGATIVE_INFINITY : p.from,
                Float.isNaN(q.from) ? Float.NEGATIVE_INFINITY : q.from));
        }

        /** Trail, glitch and distortion segment windows move with the note. */
        private void moveWindows(float db) {
            for (DTrail t : trails) {
                if (!Float.isNaN(t.from)) t.from += db;
                if (!Float.isNaN(t.to)) t.to += db;
            }
            for (DGlitch x : glitches) {
                if (!Float.isNaN(x.from)) x.from += db;
                if (!Float.isNaN(x.to)) x.to += db;
            }
            for (DDist x : distortions) {
                if (!Float.isNaN(x.from)) x.from += db;
                if (!Float.isNaN(x.to)) x.to += db;
            }
        }

        private static void shiftRel(List<Ev> list, float db) {
            for (Ev e : list) {
                if (!e.rel) continue;
                e.start += db;
                e.end += db;
            }
        }

        /** @return every event list of the key: movement, rotation, x, the layers, orbit, opacity and height */
        public List<List<Ev>> lists() {
            return java.util.Arrays.asList(move, rot, xEv, xLay, posLay, rotLay, orbXEv, orbYEv, orbRadEv, orbAngEv,
                alpha, heightEv);
        }

        /** Moves the key {@code dx} sideways: its x and every x event's values. */
        public void moveX(float dx) {
            x += dx;
            for (Ev e : xEv) {
                e.from += dx;
                e.to += dx;
            }
        }

        /** Moves the note by {@code db} beats together with all its events, absolute ones too (「整体平移」). */
        public void shift(float db) {
            judgement += db;
            if (!Float.isNaN(appearance)) appearance += db;
            shiftAll(move, db);
            shiftAll(rot, db);
            shiftAll(xEv, db);
            shiftAll(xLay, db);
            shiftAll(posLay, db);
            shiftAll(rotLay, db);
            shiftAll(orbXEv, db);
            shiftAll(orbYEv, db);
            shiftAll(orbRadEv, db);
            shiftAll(orbAngEv, db);
            shiftAll(alpha, db);
            shiftAll(heightEv, db);
            shiftAll(yEv, db);
            shiftAll(sizeEv, db);
            shiftAll(fillEv, db);
            moveWindows(db);
        }

        /** Replaces the movement with the approach template: {@code beats} beats from {@code dist} to the line. */
        public void approach(float beats, float dist, float exp) {
            move.clear();
            Ev e = new Ev(judgement - beats, judgement, dist, 0f, exp);
            e.rel = true;
            move.add(e);
            appearance = Float.NaN;
        }

        /**
         * @return true when {@code e} is this note's default fall: {@link #SPAWN} → 0 over the
         *         {@link Note#DEFAULT_APPROACH_BEATS} beats before the judgement, exponent 1 (what new
         *         notes, 正常下落 and the game's v1 loader fill in)
         */
        public boolean defaultFall(Ev e) {
            return Math.abs(e.end - judgement) < 1e-4f
                && Math.abs(e.start - (judgement - Note.DEFAULT_APPROACH_BEATS)) < 1e-4f && e.from == spawn()
                && e.to == 0f && e.exp == 1f;
        }

        /** @return where its default fall starts: {@link #SPAWN} from its line */
        public float spawn() {
            return SPAWN;
        }

        /** @return true when its only movement is the default fall (or none: the game's default) */
        private boolean onDefaultFall() {
            return move.isEmpty() ? !noApproach() : move.size() == 1 && defaultFall(move.get(0));
        }

        /** @return true for the types that can float: 水滴, 水流, Drag and 水雷 / 长水雷 ({@link NoteType#floats()}) */
        public boolean canFloat() {
            return type.floats();
        }

        /** @return a floating key's diameter: its own, or its type's when it writes none */
        public float sizeOr() {
            return Float.isFinite(size) && size > 0f ? size : Floating.defaultSize(type, duration);
        }

        /**
         * Makes the key float (game 7.185, CHART_FORMAT §11) at its x and height {@code y}, shown from the game's
         * default beat: it drops what only a key on a line has (fall, rotation, layers, orbit, pivot, tail, trail);
         * glitch and distortion stay (game 7.187); a chain stays on a DRAG only.
         */
        public void toFloating(float y) {
            floating = true;
            this.y = y;
            line = Line.LOWER;
            appearance = Float.NaN;
            for (List<Ev> l : java.util.Arrays.asList(move, rot, xLay, posLay, rotLay, heightEv)) l.clear();
            orbit = false;
            pivotX = 0f;
            pivotY = 0f;
            height = 0f;
            autoH = Float.NaN;
            trails.clear();
            if (type != NoteType.DRAG) chain = null;
        }

        /** Puts a floating key back on the lower line at its x, on the default fall; its height, size and fill go. */
        public void toLine() {
            floating = false;
            appearance = Float.NaN;
            y = Floating.DEFAULT_Y;
            size = Float.NaN;
            yEv.clear();
            sizeEv.clear();
            fillEv.clear();
            chain = null;
            line = Line.LOWER;
            approach(Note.DEFAULT_APPROACH_BEATS, SPAWN, 1f);
        }

        /** Moves a floating key by (dx, dy) with its x and y events. */
        public void moveXY(float dx, float dy) {
            moveX(dx);
            y += dy;
            for (Ev e : yEv) {
                e.from += dy;
                e.to += dy;
            }
        }

        /** @return true when the authored movement reaches the judgement; otherwise the note is judged off screen. */
        public boolean coversJudgement() {
            float probe = judgement - Note.APPROACH_PROBE_BEATS;
            Ev best = null;
            for (Ev e : move) if (e.start <= probe && (best == null || e.start >= best.start)) best = e;
            return best != null && probe <= best.end;
        }
    }

    // ================================================================== editing helpers

    public Doc copy() {
        Doc d = new Doc();
        d.title = title;
        d.artist = artist;
        d.illustrator = illustrator;
        d.charter = charter;
        d.level = level;
        d.constant = constant;
        d.iterations = iterations;
        for (Seg s : bpms) d.bpms.add(new Seg(s.beat, s.bpm));
        for (Seg s : flows) d.flows.add(new Seg(s.beat, s.bpm));
        d.flowFall = flowFall;
        d.offsetMs = offsetMs;
        d.upper = upper;
        d.lower = lower;
        d.cameraFrame = cameraFrame;
        copyAll(cameraFrameEv, d.cameraFrameEv);
        d.laneScale = laneScale;
        copyAll(laneScaleEv, d.laneScaleEv);
        for (Lanes l : fixedLanes) d.fixedLanes.add(new Lanes(l.beat, l.count));
        copyAll(upperEv, d.upperEv);
        copyAll(lowerEv, d.lowerEv);
        copyAll(upperLay, d.upperLay);
        copyAll(lowerLay, d.lowerLay);
        copyAll(cameraLay, d.cameraLay);
        for (DNote n : notes) d.notes.add(n.copy(true));
        for (DVeil v : veils) d.veils.add(v.copy(true));
        for (DVeil v : curtains) d.curtains.add(v.copy(true));
        for (DStroke v : strokes) d.strokes.add(v.copy(true));
        return d;
    }

    public DNote byUid(int uid) {
        for (DNote n : notes) if (n.uid == uid) return n;
        return null;
    }

    public DStroke strokeByUid(int uid) {
        for (DStroke v : strokes) if (v.uid == uid) return v;
        return null;
    }

    public DVeil veilByUid(int uid) {
        for (DVeil v : veils) if (v.uid == uid) return v;
        for (DVeil v : curtains) if (v.uid == uid) return v;
        return null;
    }

    /** @return the first route name of c, c2, c3, … that no DRAG uses (a new DRAG chain) */
    public String freeChain() {
        for (int k = 1; ; k++) {
            String id = k == 1 ? "c" : "c" + k;
            boolean used = false;
            for (DNote n : notes) used |= id.equals(n.chain);
            if (!used) return id;
        }
    }

    /** @return the BPM in force at {@code beat}. */
    public float bpmAt(float beat) {
        Seg cur = bpms.get(0);
        for (Seg s : bpms) if (s.beat <= beat) cur = s;
        return cur.bpm;
    }

    public void sortBpms() {
        bpms.sort(Comparator.comparingDouble(s -> s.beat));
    }

    // ================================================================== 统一流速 (Kelvin 2026-10-15)

    /** @return the flow BPM at {@code beat} ({@link #flows}, or the tempo when there are none). */
    public float flowAt(float beat) {
        if (flows.isEmpty()) return bpmAt(beat);
        Seg cur = flows.get(0);
        for (Seg s : flows) if (s.beat <= beat) cur = s;
        return cur.bpm;
    }

    public void sortFlows() {
        flows.sort(Comparator.comparingDouble(s -> s.beat));
    }

    /**
     * @return the fixed lane count at {@code beat} (0 = 无轨) as the game's {@link Chart#fixedLanesAt} counts it: the
     *         last section starting at or before it; before the first, that one's count when it starts at beat 0 or
     *         earlier
     */
    public int lanesAt(float beat) {
        int k = 0;
        for (int i = 0; i < fixedLanes.size(); i++) {
            Lanes l = fixedLanes.get(i);
            if (l.beat <= beat || i == 0 && l.beat <= 0f) k = l.count;
            else break;
        }
        return k;
    }

    public void sortLanes() {
        fixedLanes.sort(Comparator.comparingDouble(l -> l.beat));
    }

    /** @return milliseconds from beat 0 to {@code beat} on {@link #bpms} (negative before it; CHART_FORMAT §2.1). */
    public double msOf(double beat) {
        double lo = Math.min(0, beat), hi = Math.max(0, beat), sum = 0;
        for (int i = 0; i < bpms.size(); i++) {
            double a = i == 0 ? Double.NEGATIVE_INFINITY : bpms.get(i).beat;
            double b = i + 1 < bpms.size() ? bpms.get(i + 1).beat : Double.POSITIVE_INFINITY;
            double from = Math.max(a, lo), to = Math.min(b, hi);
            if (to > from) sum += (to - from) * 60000.0 / bpms.get(i).bpm;
        }
        return beat >= 0 ? sum : -sum;
    }

    /** @return the beat {@code ms} milliseconds after beat 0 (the inverse of {@link #msOf}). */
    public double beatOf(double ms) {
        for (int i = 0; i < bpms.size(); i++) {
            boolean last = i + 1 == bpms.size();
            double end = last ? Double.POSITIVE_INFINITY : bpms.get(i + 1).beat;
            if (last || ms < msOf(end)) {
                double anchor = i > 0 ? bpms.get(i).beat : last ? 0 : end;
                return anchor + (ms - msOf(anchor)) * bpms.get(i).bpm / 60000.0;
            }
        }
        return 0;
    }

    /** @return the fall speed in ref units per beat at {@code beat}: 540, or 540 per flow beat under 统一流速. */
    public float speed(float beat) {
        return flowFall ? APPROACH * flowAt(beat) / bpmAt(beat) : APPROACH;
    }

    /** @return the start beat of a fall of {@code flowBeats} flow beats that ends at {@code judgement}. */
    public float flowStart(float judgement, float flowBeats) {
        return (float) beatOf(msOf(judgement) - flowBeats * 60000.0 / flowAt(judgement));
    }

    /**
     * @return the suggested tail: {@link #APPROACH} per beat of the duration, per flow beat under 统一流速; none on a
     *         floating key (it has no tail)
     */
    public float autoHeight(DNote n) {
        if (n.floating) return 0f;
        if (!flowFall) return n.duration * APPROACH;
        return (float) (APPROACH * (msOf(n.judgement + n.duration) - msOf(n.judgement))
            * flowAt(n.judgement) / 60000.0);
    }

    /** @return whether {@code n}'s tail is at a suggested length (it then follows duration and flow changes). */
    public boolean autoTail(DNote n) {
        return n.height <= 0f || Math.abs(n.height - n.duration * APPROACH) < 1f
            || (!Float.isNaN(n.autoH) && Math.abs(n.height - n.autoH) < 1f) || Math.abs(n.height - autoHeight(n)) < 1f;
    }

    public void setAutoTail(DNote n) {
        n.height = autoHeight(n);
        n.autoH = flowFall ? n.height : Float.NaN;
    }

    /** Under 统一流速: turns {@code n}'s single fall (as {@link DNote#approach} sets it) into a flow fall. */
    public void linkFlow(DNote n) {
        if (!flowFall || n.move.size() != 1) return;
        boolean tail = n.sustained() && autoTail(n);
        Ev e = n.move.get(0);
        e.flow = e.end - e.start;
        e.start = flowStart(n.judgement, e.flow);
        if (tail) setAutoTail(n);
    }

    // ================================================================== 不跟判定线

    /** Sampling steps of {@link #holdStill} (beats): where the line eases, and where only the note's own fall eases. */
    static final float STILL_STEP = 1f / 64f, STILL_STEP_FALL = 1f / 16f;

    /**
     * 不跟判定线 (Kelvin 2026-11-03): from {@code a} to {@code b} the notes stay where they would be if their line
     * stayed where it is at {@code a}, by rewriting their movement events (the game is not changed). Over that time
     * each note's distance gets the line's offset added back (sky: + (U(t) − U(a)), ground: − (L(t) − L(a)), the
     * line's 叠加层 included): pieces follow every movement, line and layer event boundary, linear pieces stay one
     * piece, eased ones are cut every
     * {@link #STILL_STEP} beats (1/16 beat where only the fall eases). Events running on past {@code b} are rebuilt
     * unchanged from there. A note on the default fall gets it written out first; the rebuilt events are absolute
     * and not linked to a flow speed. Notes on a 公转 radius or angle are skipped (their place comes from the orbit),
     * and so are floating keys (they have no line).
     * @return {changed notes, skipped orbit and floating notes}
     */
    public int[] holdStill(List<DNote> sel, float a, float b) {
        int changed = 0, skipped = 0;
        if (!(b > a)) return new int[]{0, 0};
        for (DNote n : sel) {
            if (n.polar() || n.floating) {
                skipped++;
                continue;
            }
            boolean wrote = n.move.isEmpty() && !n.noApproach();
            if (wrote) {
                Ev e = new Ev(n.judgement - Note.DEFAULT_APPROACH_BEATS, n.judgement, SPAWN, 0f, 1f);
                e.rel = true;
                n.move.add(e);
            }
            boolean up = n.line == Line.UPPER;
            List<Ev> line = up ? upperEv : lowerEv;
            float base = up ? upper : lower;
            if (stillNote(n, line, up ? upperLay : lowerLay, base, up ? 1f : -1f, a, b)) changed++;
            else if (wrote) n.move.clear();
        }
        return new int[]{changed, skipped};
    }

    /** One note of {@link #holdStill}. @return whether its movement changed */
    private static boolean stillNote(DNote n, List<Ev> line, List<Ev> lay, float base, float sign, float from,
                                     float to) {
        float ref = lineAt(line, base, from) + layerAt(lay, from);
        // only the part where the line is away from ref is rebuilt; the note's other events stay as they are
        ArrayList<Float> lc = new ArrayList<>();
        lc.add(from);
        lc.add(to);
        for (List<Ev> l : java.util.Arrays.asList(line, lay)) {
            for (Ev e : l) {
                lc.add(e.start);
                lc.add(e.end);
            }
        }
        lc.removeIf(t -> t < from || t > to);
        lc.sort(null);
        float a = Float.NaN, b = Float.NaN;
        for (int i = 0; i + 1 < lc.size(); i++) {
            float x0 = lc.get(i), x1 = lc.get(i + 1);
            if (x1 - x0 < 1e-5f) continue;
            float mid = (x0 + x1) * 0.5f;
            Ev le = cover(line, mid);
            boolean away = le == null ? base != ref : le.from != ref || le.to != ref;
            for (Ev e : lay) away |= e.start <= mid && mid <= e.end && (e.from != 0f || e.to != 0f);
            if (!away) continue;
            if (Float.isNaN(a)) a = x0;
            b = x1;
        }
        if (Float.isNaN(a)) return false;
        ArrayList<Ev> old = new ArrayList<>(n.move);
        old.sort(Comparator.comparingDouble(e -> e.start));   // stable, as the game sorts its copy
        float r = b;
        for (boolean grew = true; grew; ) {
            grew = false;
            for (Ev e : old) {
                if (e.start < r && e.end > a && e.end > r) {
                    r = e.end;
                    grew = true;
                }
            }
        }
        ArrayList<Float> cuts = new ArrayList<>();
        cuts.add(a);
        cuts.add(b);
        cuts.add(r);
        for (Ev e : old) {
            cuts.add(e.start);
            cuts.add(e.end);
        }
        for (List<Ev> l : java.util.Arrays.asList(line, lay)) {
            for (Ev e : l) {
                cuts.add(e.start);
                cuts.add(e.end);
            }
        }
        final float lo = a, hi = r;
        cuts.removeIf(t -> t < lo || t > hi);
        cuts.sort(null);
        ArrayList<Ev> out = new ArrayList<>();
        boolean touched = false;
        for (int i = 0; i + 1 < cuts.size(); i++) {
            float x0 = cuts.get(i), x1 = cuts.get(i + 1);
            if (x1 - x0 < 1e-5f) continue;
            float mid = (x0 + x1) * 0.5f;
            Ev e = cover(old, mid);
            if (e == null) continue;
            touched = true;
            boolean inside = mid < b;
            Ev le = inside ? cover(line, mid) : null;
            // how the line's offset runs over this piece: 0 still, 1 straight, 2 eased (its layers included)
            int shape = !inside ? 0 : Math.max(le == null || le.from == le.to || le.end <= le.start ? 0
                : le.exp == 1f ? 1 : 2, layerShape(lay, mid));
            List<Ev> pl = inside ? lay : java.util.Collections.emptyList();
            boolean noteFlat = e.exp == 1f || e.from == e.to || e.end <= e.start;
            if (noteFlat && shape <= 1) {
                out.add(still(e, le, pl, mid, base, ref, sign, inside, x0, x1, 1f));
            } else if (shape == 0 && Math.abs(x0 - e.start) < 1e-5f) {
                out.add(still(e, le, pl, mid, base, ref, sign, inside, x0, x1, e.exp));
            } else {
                float step = shape <= 1 ? STILL_STEP_FALL : STILL_STEP;
                int k = Math.max(1, (int) Math.ceil((x1 - x0) / step - 1e-4f));
                for (int j = 0; j < k; j++) {
                    out.add(still(e, le, pl, mid, base, ref, sign, inside, x0 + (x1 - x0) * j / k,
                        j == k - 1 ? x1 : x0 + (x1 - x0) * (j + 1) / k, 1f));
                }
            }
        }
        if (!touched) return false;
        n.move.clear();
        for (Ev e : old) {
            if (!(e.start < r && e.end > a)) {
                n.move.add(e);
            } else if (e.start < a) {
                // the part before a keeps its own curve exactly
                Ev l = new Ev(e.start, a, e.from, e.valueAt(a), e.exp);
                n.move.add(l);
            }
        }
        n.move.addAll(out);
        n.move.sort(Comparator.comparingDouble(e -> e.start));
        return true;
    }

    /** A rebuilt piece of {@link #stillNote} from {@code x0} to {@code x1}: absolute, no flow. */
    private static Ev still(Ev e, Ev le, List<Ev> lay, float mid, float base, float ref, float sign, boolean inside,
                            float x0, float x1, float exp) {
        return new Ev(x0, x1, e.valueAt(x0) + offset(le, lay, mid, base, ref, sign, inside, x0),
            e.valueAt(x1) + offset(le, lay, mid, base, ref, sign, inside, x1), exp);
    }

    /**
     * @return what {@link #stillNote} adds at {@code t}: the line's move from {@code ref}, signed for the line, with
     *         the layer events running over the piece around {@code mid} (pieces are cut at every layer boundary)
     */
    private static float offset(Ev le, List<Ev> lay, float mid, float base, float ref, float sign, boolean inside,
                                float t) {
        if (!inside) return 0f;
        float y = le != null ? le.valueAt(t) : base;
        for (Ev e : lay) if (e.start <= mid && mid <= e.end) y += e.valueAt(t);
        return sign * (y - ref);
    }

    /** @return how the layer events running at {@code mid} change: 0 not at all, 1 in straight lines, 2 eased. */
    private static int layerShape(List<Ev> lay, float mid) {
        int shape = 0;
        for (Ev e : lay) {
            if (e.start > mid || mid > e.end || e.from == e.to || e.end <= e.start) continue;
            shape = Math.max(shape, e.exp == 1f ? 1 : 2);
        }
        return shape;
    }

    /**
     * @return the event the game evaluates at {@code t} (the last one by start that starts at or before it, as
     *         {@code Events.calculateValue}) when it covers {@code t}, else null (the base value applies)
     */
    private static Ev cover(List<Ev> list, float t) {
        Ev best = null;
        for (Ev e : list) if (e.start <= t && (best == null || e.start >= best.start)) best = e;
        return best != null && t <= best.end ? best : null;
    }

    /**
     * 运动器拖动排序 (Kelvin 2026-11-05): moves card {@code from} of a channel in time order to slot {@code to}.
     * Only the cards from the old to the new slot change: they keep the stretch of beats they had, each its own
     * length, with the gaps between them where they were; values chain by change (each starts where the one before
     * it ends, from the first one's start value), so the motion does not jump and the cards after are untouched.
     *
     * @param list one channel's events sorted by start
     */
    public static void reorderEvents(List<Ev> list, int from, int to) {
        int a = Math.min(from, to), m = Math.max(from, to) - a + 1;
        ArrayList<Ev> seg = new ArrayList<>(list.subList(a, a + m));
        float[] gap = new float[m];
        for (int k = 0; k + 1 < m; k++) gap[k] = seg.get(k + 1).start - seg.get(k).end;
        float t = seg.get(0).start, v = seg.get(0).from;
        seg.add(to - a, seg.remove(from - a));
        for (int k = 0; k < m; k++) {
            Ev e = seg.get(k);
            float d = e.end - e.start, dv = e.to - e.from;
            e.start = t;
            e.end = t + d;
            e.from = v;
            e.to = v + dv;
            e.flow = Float.NaN;                                     // timed by hand now
            v = e.to;
            t = e.end + gap[k];
            list.set(a + k, e);
        }
    }

    /** @return the value of a line channel at {@code t}: its covering event's, else {@code base}. */
    static float lineAt(List<Ev> line, float base, float t) {
        Ev e = cover(line, t);
        return e != null ? e.valueAt(t) : base;
    }

    /**
     * @return what a 叠加层 list adds at {@code t}, as the game's {@code Layers.at}: every event running then, summed
     *         (0 outside them); one ending on {@code t} hands over when another starts on it
     */
    static float layerAt(List<Ev> lay, float t) {
        boolean handOver = false;
        for (Ev e : lay) handOver |= e.start == t;
        float sum = 0f;
        for (Ev e : lay) {
            if (e.start > t || e.end < t || e.end == t && handOver && e.start < e.end) continue;
            sum += e.valueAt(t);
        }
        return sum;
    }

    /** @return a key's x at beat {@code t} with its 叠加层, as the game's {@code Note.xAt}. */
    static float xAt(DNote n, float t) {
        return lineAt(n.xEv, n.x, t) + layerAt(n.xLay, t);
    }

    /**
     * Run on every change: each flow fall ({@link Ev#flow}) starts that many flow beats before its
     * judgement on the current tempo and flow BPM, and a tail still at its suggested length follows.
     * @return how many falls moved
     */
    public int reflow() {
        int moved = 0;
        for (DNote n : notes) {
            boolean linked = false;
            for (Ev e : n.move) {
                if (Float.isNaN(e.flow)) continue;
                linked = true;
                float a = flowStart(n.judgement, e.flow);
                if (a != e.start || e.end != n.judgement) {
                    e.start = a;
                    e.end = n.judgement;
                    moved++;
                }
            }
            if (linked && flowFall && n.sustained() && !Float.isNaN(n.autoH)
                && Math.abs(n.height - n.autoH) < 1f) setAutoTail(n);
        }
        return moved;
    }

    /**
     * 同步链接 (Kelvin 2026-11-05 「保持链接」), run on every change: each linked copy takes its source's beats, values
     * and exponent again, shifted by its {@link Link}. A copy that changed by itself (not as its source moved it) keeps
     * its values and is let go, and so is a copy whose source is gone; a source nothing follows is a plain event again.
     * @return how many copies were let go
     */
    public int relink() {
        java.util.HashMap<Integer, Ev> src = new java.util.HashMap<>();
        java.util.HashMap<Integer, DNote> srcKey = new java.util.HashMap<>();
        boolean any = false;
        for (DNote n : notes) {
            for (List<Ev> l : n.lists()) {
                for (Ev e : l) {
                    any |= e.link != null;
                    if (e.link == null || !e.link.src) continue;
                    if (src.containsKey(e.link.id)) {
                        e.link = null;                                // a duplicated source: the first one leads
                    } else {
                        src.put(e.link.id, e);
                        srcKey.put(e.link.id, n);
                    }
                }
            }
        }
        if (!any) return 0;
        java.util.HashSet<Integer> followed = new java.util.HashSet<>();
        int cut = 0;
        for (DNote n : notes) {
            for (List<Ev> l : n.lists()) {
                boolean moved = false;
                for (Ev e : l) {
                    if (e.link == null || e.link.src) continue;
                    Ev s = src.get(e.link.id);
                    float j = e.link.judge ? n.judgement : 0f;          // 按各自判定 compares beats from the judgement
                    float[] now = {e.start - j, e.end - j, e.from, e.to, e.exp}, want = null;
                    if (s != null) {
                        float dt = e.link.dt + (e.link.judge ? n.judgement - srcKey.get(e.link.id).judgement : 0f) - j;
                        want = new float[]{s.start + dt, s.end + dt, s.from + e.link.dv, s.to + e.link.dv, s.exp};
                        if (!same(now, want) && (e.linked == null || !same(now, e.linked))) want = null;
                    }
                    if (want == null) {
                        e.link = null;
                        e.linked = null;
                        cut++;
                        continue;
                    }
                    if (!same(now, want)) {
                        e.start = want[0] + j;
                        e.end = want[1] + j;
                        e.from = want[2];
                        e.to = want[3];
                        e.exp = want[4];
                        moved = true;
                    }
                    e.linked = want;
                    followed.add(e.link.id);
                }
                if (moved) l.sort(Comparator.comparingDouble(x -> x.start));
            }
        }
        for (java.util.Map.Entry<Integer, Ev> en : src.entrySet()) {
            if (!followed.contains(en.getKey())) en.getValue().link = null;
        }
        return cut;
    }

    private static boolean same(float[] a, float[] b) {
        for (int i = 0; i < a.length; i++) {
            if (Math.abs(a[i] - b[i]) > 1e-4f * Math.max(1f, Math.abs(b[i]))) return false;
        }
        return true;
    }

    /** @return how many events follow source {@code id} (同步链接) */
    public int followers(int id) {
        int c = 0;
        for (DNote n : notes) {
            for (List<Ev> l : n.lists()) for (Ev e : l) if (e.link != null && !e.link.src && e.link.id == id) c++;
        }
        return c;
    }

    /** @return an id no 同步链接 uses yet */
    public int nextLinkId() {
        int m = 0;
        for (DNote n : notes) {
            for (List<Ev> l : n.lists()) for (Ev e : l) if (e.link != null) m = Math.max(m, e.link.id);
        }
        return m + 1;
    }

    /**
     * Switches 统一拍数 / 统一流速 for the whole chart (the caller marks undo). To flow, a note whose
     * movement is one relative fall onto the line (or none: the game's default approach) keeps its
     * number of beats as flow beats; back, each flow fall keeps its number as plain beats. Their
     * tails at a suggested length follow.
     * @return how many notes changed
     */
    public int setFallBy(boolean flow) {
        boolean[] tails = new boolean[notes.size()];
        for (int i = 0; i < tails.length; i++) tails[i] = notes.get(i).sustained() && autoTail(notes.get(i));
        flowFall = flow;
        int changed = 0;
        for (int i = 0; i < tails.length; i++) {
            DNote n = notes.get(i);
            boolean hit = false;
            if (flow) {
                // no movement = the game's default approach: written out so it can follow the flow
                if (n.move.isEmpty() && !n.noApproach()) n.approach(Note.DEFAULT_APPROACH_BEATS, n.spawn(), 1f);
                Ev e = n.move.size() == 1 ? n.move.get(0) : null;
                if (e != null && e.rel && Float.isNaN(e.flow) && Math.abs(e.end - n.judgement) < 1e-4f && e.to == 0f
                    && e.start < e.end) {
                    e.flow = n.judgement - e.start;
                    hit = true;
                }
            } else {
                for (Ev e : n.move) {
                    if (Float.isNaN(e.flow)) continue;
                    e.start = n.judgement - e.flow;
                    e.end = n.judgement;
                    e.flow = Float.NaN;
                    hit = true;
                }
            }
            if (hit) {
                changed++;
                if (tails[i]) setAutoTail(n);
            }
        }
        reflow();
        return changed;
    }

    /**
     * Sorts notes by judgement, on one beat the keys on the lines (as they were) before the floating keys left to
     * right, then bottom to top, as the game orders them (Chart.NOTE_ORDER); then every event list by start (all
     * stable).
     */
    public void sortAll() {
        notes.sort((a, b) -> {
            int c = Float.compare(a.judgement, b.judgement);
            if (c != 0) return c;
            boolean fa = a.floating && a.canFloat(), fb = b.floating && b.canFloat();
            if (fa != fb || !fa) return Boolean.compare(fa, fb);
            // + 0f: −0 and 0 are one place
            c = Float.compare(at(a.xEv, a.judgement, a.x) + 0f, at(b.xEv, b.judgement, b.x) + 0f);
            return c != 0 ? c : Float.compare(at(a.yEv, a.judgement, a.y) + 0f, at(b.yEv, b.judgement, b.y) + 0f);
        });
        Comparator<Ev> byStart = Comparator.comparingDouble(e -> e.start);
        for (DNote n : notes) {
            n.move.sort(byStart);
            n.rot.sort(byStart);
            n.xEv.sort(byStart);
            n.xLay.sort(byStart);
            n.posLay.sort(byStart);
            n.rotLay.sort(byStart);
            n.orbXEv.sort(byStart);
            n.orbYEv.sort(byStart);
            n.orbRadEv.sort(byStart);
            n.orbAngEv.sort(byStart);
            n.alpha.sort(byStart);
            n.heightEv.sort(byStart);
            n.yEv.sort(byStart);
            n.sizeEv.sort(byStart);
            n.fillEv.sort(byStart);
        }
        upperEv.sort(byStart);
        lowerEv.sort(byStart);
        cameraFrameEv.sort(byStart);
        laneScaleEv.sort(byStart);
        sortLanes();
        upperLay.sort(byStart);
        lowerLay.sort(byStart);
        cameraLay.sort(byStart);
        sortBpms();
    }

    /** @return the last beat any note, veil end or tempo change uses (0 for an empty chart). */
    public float lastBeat() {
        float b = 0f;
        for (DNote n : notes) b = Math.max(b, n.end());
        for (DVeil v : veils) if (Float.isFinite(v.end)) b = Math.max(b, v.end);
        for (DVeil v : curtains) if (Float.isFinite(v.end)) b = Math.max(b, v.end);
        for (Seg s : bpms) b = Math.max(b, s.beat);
        return b;
    }

    // ================================================================== reading

    /**
     * A new chart: the game's default lines (120 / 1080), scale 1, camera frame 0 and, from beat 0,
     * {@link Chart#DEFAULT_FIXED_LANES} fixed lanes (定轨, game 7.184).
     */
    public static Doc blank(String title, String artist, String illustrator, String level, float bpm, float offsetMs) {
        Doc d = new Doc();
        d.title = nz(title);
        d.artist = nz(artist);
        d.illustrator = nz(illustrator);
        d.level = nz(level);
        d.bpms.add(new Seg(0f, bpm > 0f ? bpm : 120f));
        d.offsetMs = offsetMs;
        d.fixedLanes.add(new Lanes(0f, Chart.DEFAULT_FIXED_LANES));
        return d;
    }

    /**
     * Reads the editor-only keys the game's loader skips, so {@link #fromChart} cannot see them:
     * which note events are relative to the judgement, the flow falls ({@code "flow"}) and the chart's
     * {@code flowBpmList} / {@code fallBy}. Without a {@code "rel"} key the default fall
     * ({@link DNote#defaultFall}) is relative (Kelvin 2026-10-13 06:28) and every other event absolute;
     * a key in the file ({@code true}, or {@code false} for a default fall made absolute) wins. Notes
     * are matched by type, line, judgement and (a laneless file) x in file order; event lists keep the order the
     * editor wrote them in (sorted by start).
     */
    public static void readRelFlags(Doc d, com.badlogic.gdx.files.FileHandle f) {
        try {
            readRelFlags(d, f.readString("UTF-8"));
        } catch (Exception ignored) {
        }
    }

    /** Reads the editor-only keys from JSON text returned by an assistant. */
    public static void readRelFlags(Doc d, String text) {
        for (DNote n : d.notes) for (Ev e : n.move) if (n.defaultFall(e)) e.rel = true;
        com.badlogic.gdx.utils.JsonValue root, list;
        try {
            d.iterations = iterationsIn(text);
            if (!text.contains("\"rel\"") && !text.contains("\"flow") && !text.contains("\"fallBy\"")
                && !text.contains("\"link\"")) return;
            root = new com.badlogic.gdx.utils.JsonReader().parse(text);
            list = root.get("notes");
        } catch (Exception e) {
            return;
        }
        // v5 keeps them under "editor", v4 at the top
        com.badlogic.gdx.utils.JsonValue ed = root.has("editor") ? root.get("editor") : root;
        d.flowFall = "flow".equals(ed.getString("fallBy", ""));
        com.badlogic.gdx.utils.JsonValue fl = ed.get("flowBpmList");
        if (fl != null && fl.isArray()) {
            for (com.badlogic.gdx.utils.JsonValue v = fl.child; v != null; v = v.next) {
                float b = v.getFloat("beat", Float.NaN), bpm = v.getFloat("bpm", Float.NaN);
                if (Float.isFinite(b) && bpm > 0f && bpm < 10000f) d.flows.add(new Seg(b, bpm));
            }
            d.sortFlows();
            if (!d.flows.isEmpty()) d.flows.get(0).beat = 0f;
        }
        if (list == null) return;
        // a laneless file (format 6, or the editor's own text) tells keys of one beat apart by x; a lane file by order
        boolean placed = root.getInt("format", 0) >= 6 || root.getBoolean("laneless", false);
        java.util.HashMap<String, java.util.ArrayDeque<DNote>> byKey = new java.util.HashMap<>();
        for (DNote n : d.notes) {
            byKey.computeIfAbsent(noteKey(n.type.json, n.line.json, n.judgement, placed && n.inLane(), n.x),
                k -> new java.util.ArrayDeque<>()).add(n);
        }
        for (com.badlogic.gdx.utils.JsonValue v = list.child; v != null; v = v.next) {
            NoteType t = NoteType.fromJson(v.getString("type", ""));
            java.util.ArrayDeque<DNote> q = byKey.get(noteKey(v.getString("type", ""), v.getString("line", "lower"),
                v.getFloat("judgement", v.getFloat("beat", Float.NaN)), placed && t != null && t.inLane(),
                v.getFloat("x", 0f)));
            DNote n = q != null ? q.poll() : null;
            if (n == null) continue;
            // v4 names, else v5 (a v5 note without positionEvents falls by default, relative)
            markRel(v.has("MovementEvents") ? v.get("MovementEvents") : v.get("positionEvents"), n.move);
            markRel(v.has("RotationEvents") ? v.get("RotationEvents") : v.get("rotationEvents"), n.rot);
            // a lane file's 横移 events became the x events one to one where its lanes stand still
            com.badlogic.gdx.utils.JsonValue xs = v.has("xEvents") ? v.get("xEvents") : v.get("offsetXEvents");
            if (xs != null && xs.size == n.xEv.size()) markRel(xs, n.xEv);
            markRel(v.get("orbitXEvents"), n.orbXEv);
            markRel(v.get("orbitYEvents"), n.orbYEv);
            markRel(v.get("orbitRadiusEvents"), n.orbRadEv);
            markRel(v.get("orbitAngleEvents"), n.orbAngEv);
            markRel(v.get("xLayers"), n.xLay);
            markRel(v.get("positionLayers"), n.posLay);
            markRel(v.get("rotationLayers"), n.rotLay);
            markRel(v.has("DisappearEvents") ? v.get("DisappearEvents") : v.get("opacityEvents"), n.alpha);
            markRel(v.has("HeightEvents") ? v.get("HeightEvents") : v.get("heightEvents"), n.heightEv);
            markRel(v.get("yEvents"), n.yEv);
            markRel(v.get("sizeEvents"), n.sizeEv);
            markRel(v.get("fillEvents"), n.fillEv);
        }
        for (DNote n : d.notes) {
            boolean linked = false;
            for (Ev e : n.move) linked |= !Float.isNaN(e.flow);
            if (linked && d.flowFall && n.sustained() && Math.abs(n.height - d.autoHeight(n)) < 1f) n.autoH = n.height;
        }
    }

    private static final java.util.regex.Pattern ITERATIONS =
        java.util.regex.Pattern.compile("\"iterations\"\\s*:\\s*(\\d+)");

    /** @return the chart text's {@code "iterations"} (0 when it has none) */
    public static int iterationsIn(String json) {
        java.util.regex.Matcher m = ITERATIONS.matcher(json);
        try {
            return m.find() ? Integer.parseInt(m.group(1)) : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** @return the chart file's {@code "iterations"} (0 when it is missing, unreadable or has none) */
    public static int iterationsIn(com.badlogic.gdx.files.FileHandle f) {
        try {
            return f.exists() ? iterationsIn(f.readString("UTF-8")) : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    private static String noteKey(String type, String line, float judgement, boolean byX, float x) {
        return type + '|' + line + '|' + Math.round(judgement * 10000.0) + (byX ? "|" + Math.round(x * 100.0) : "");
    }

    private static void markRel(com.badlogic.gdx.utils.JsonValue arr, List<Ev> list) {
        if (arr == null) return;
        int i = 0;
        for (com.badlogic.gdx.utils.JsonValue e = arr.child; e != null && i < list.size(); e = e.next, i++) {
            com.badlogic.gdx.utils.JsonValue r = e.get("rel");
            if (r != null && r.isBoolean()) list.get(i).rel = r.asBoolean();
            float fb = e.getFloat("flow", Float.NaN);
            if (fb > 0f && fb < 1000f) list.get(i).flow = fb;
            list.get(i).link = Link.read(e.get("link"));
        }
    }

    /** Copies a chart the game loaded (older files arrive converted to the current format by the game's loader). */
    public static Doc fromChart(Chart c) {
        Doc d = new Doc();
        d.title = nz(c.title());
        d.artist = nz(c.artist());
        d.illustrator = nz(c.illustrator());
        d.charter = nz(c.charter());
        d.level = nz(c.level());
        d.constant = c.chartConstant() != null && !c.chartConstant().trim().isEmpty() ? c.chartConstant().trim() : "1";
        float[] sb = c.bpmListBeats(), sv = c.bpmListValues();
        if (sb != null && sv != null && sb.length > 0) {
            for (int i = 0; i < sb.length && i < sv.length; i++) d.bpms.add(new Seg(sb[i], sv[i]));
        } else {
            d.bpms.add(new Seg(0f, c.bpm()));
        }
        if (d.bpms.size() == 1) d.bpms.get(0).beat = 0f;
        d.offsetMs = c.offsetMs();
        JudgementLine j = c.lineEvents();
        d.upper = j.upperBase();
        d.lower = j.lowerBase();
        read(j.upperEvents(), d.upperEv);
        read(j.lowerEvents(), d.lowerEv);
        d.cameraFrame = j.cameraFrameBase();
        read(j.cameraFrameEvents(), d.cameraFrameEv);
        d.laneScale = j.laneScaleBase();
        read(j.laneScaleEvents(), d.laneScaleEv);
        read(j.upperLayers(), d.upperLay);
        read(j.lowerLayers(), d.lowerLay);
        read(j.cameraLayers(), d.cameraLay);
        float[] lb = c.fixedLaneBeats();
        int[] lc = c.fixedLaneCounts();
        for (int i = 0; i < lb.length && i < lc.length; i++) d.fixedLanes.add(new Lanes(lb[i], lc[i]));
        for (Note n : c.notes()) {
            if (n.floats()) {
                d.notes.add(readFloating(n));
                continue;
            }
            DNote x = new DNote(n.type(), n.line(), n.hasX() ? n.x() : 0f, n.judgement());
            x.duration = n.duration();
            x.height = n.height();
            x.appearance = n.appearance();
            x.pivotX = n.pivotX();
            x.pivotY = n.pivotY();
            x.waveSpeed = n.waveSpeed();
            read(n.movementEvents(), x.move);
            // a v5 file leaves the default fall out; the editor keeps it as an event, as a v4 file wrote it
            if (c.sourceFormat() >= 5 && x.move.isEmpty() && n.usesDefaultApproach()) {
                x.move.add(new Ev(n.judgement() - Note.DEFAULT_APPROACH_BEATS, n.judgement(), x.spawn(), 0f, 1f));
            }
            read(n.rotationEvents(), x.rot);
            read(n.xEvents(), x.xEv);
            if (n.type().inLane()) read(n.xLayers(), x.xLay);
            read(n.positionLayers(), x.posLay);
            read(n.rotationLayers(), x.rotLay);
            if (n.orbit() != null) {
                x.orbit = true;
                x.orbitX = n.orbit().x() - LaneLayout.TILE_CENTRE;
                x.orbitY = n.orbit().y();
                read(n.orbit().xEvents(), x.orbXEv);
                fromFrame(x.orbXEv);
                read(n.orbit().yEvents(), x.orbYEv);
                x.orbitRadius = n.orbit().radius();
                x.orbitAngle = n.orbit().angle();
                read(n.orbit().radiusEvents(), x.orbRadEv);
                read(n.orbit().angleEvents(), x.orbAngEv);
                x.orbitConflict = n.orbit().conflict();
                x.orbitRigid = n.orbit().rigid();
            }
            x.fake = n.fake();
            read(n.disappearEvents(), x.alpha);
            read(n.heightEvents(), x.heightEv);
            for (NoteTrail t : n.trails()) {
                DTrail y = new DTrail();
                y.length = t.length();
                y.density = t.density();
                y.alpha = t.alpha();
                y.fade = t.fade();
                y.level = t.mode() == NoteTrail.Mode.LEVEL;
                y.from = t.hasFrom() ? t.from() : Float.NaN;
                y.to = t.hasTo() ? t.to() : Float.NaN;
                x.trails.add(y);
            }
            readGlitches(n.glitches(), x.glitches);
            readDistortions(n.distortions(), x.distortions);
            d.notes.add(x);
        }
        for (Veil v : c.veils()) d.veils.add(readVeil(v));
        for (Veil v : c.curtains()) {
            DVeil x = readVeil(v);
            x.blur = v.blur();
            read(v.blurEvents(), x.blurEv);
            d.curtains.add(x);
        }
        for (Stroke st : c.strokes()) {
            DStroke x = new DStroke();
            x.points = st.points().clone();
            x.curve = st.curve();
            x.closed = st.closed();
            x.front = st.front();
            x.from = Float.isInfinite(st.from()) ? Float.NaN : st.from();
            x.to = Float.isInfinite(st.to()) ? Float.NaN : st.to();
            x.drawIn = st.drawIn();
            x.drawOut = st.drawOut();
            x.width = st.width();
            x.color = st.color();
            x.opacity = st.baseOpacity();
            read(st.opacityEvents(), x.opacityEv);
            readGlitches(st.glitches(), x.glitches);
            readDistortions(st.distortions(), x.distortions);
            d.strokes.add(x);
        }
        d.sortAll();
        return d;
    }

    /** A floating key of the game's chart (CHART_FORMAT §11): what it writes (size and appear NaN when default). */
    private static DNote readFloating(Note n) {
        Floating f = n.floating();
        DNote x = new DNote(n.type(), Line.LOWER, f.x(), n.judgement());
        x.floating = true;
        x.y = f.y();
        x.size = f.size() != Floating.defaultSize(n.type(), n.duration()) ? f.size() : Float.NaN;
        x.duration = n.duration();
        x.appearance = f.appear() != f.defaultAppear(n.judgement()) ? f.appear() : Float.NaN;
        x.chain = f.chain();
        read(f.xEvents(), x.xEv);
        read(f.yEvents(), x.yEv);
        read(f.sizeEvents(), x.sizeEv);
        read(f.fillEvents(), x.fillEv);
        read(n.disappearEvents(), x.alpha);
        x.fake = n.fake();
        readGlitches(n.glitches(), x.glitches);
        readDistortions(n.distortions(), x.distortions);
        return x;
    }

    /** One veil (or curtain) of the game's chart as the editor keeps it. */
    private static DVeil readVeil(Veil v) {
        DVeil x = new DVeil();
        x.polygon = v.shape() == Veil.Shape.POLYGON;
        x.radius = v.radius();
        if (x.polygon) x.points = v.points();
        x.x = v.baseX() - LaneLayout.TILE_CENTRE;
        x.y = v.baseY();
        x.rotation = v.baseRotation();
        x.opacity = v.baseOpacity();
        x.start = v.hasStart() ? v.startTime() : Float.NaN;
        x.end = v.hasEnd() ? v.endTime() : Float.NaN;
        read(v.xEvents(), x.xEv);
        fromFrame(x.xEv);
        read(v.yEvents(), x.yEv);
        read(v.rotationEvents(), x.rotEv);
        read(v.opacityEvents(), x.opacityEv);
        readGlitches(v.glitches(), x.glitches);
        readDistortions(v.distortions(), x.distortions);
        return x;
    }

    /** Game x values (0–1920 frame) of orbit centres and veils become the editor's x from the centre line. */
    private static void fromFrame(List<Ev> list) {
        for (Ev e : list) {
            e.from -= LaneLayout.TILE_CENTRE;
            e.to -= LaneLayout.TILE_CENTRE;
        }
    }

    /** @return copies of x events from the centre line with their values back in the 0–1920 frame (for writing). */
    private static List<Ev> toFrame(List<Ev> list) {
        ArrayList<Ev> out = new ArrayList<>(list.size());
        copyAll(list, out);
        for (Ev e : out) {
            e.from += LaneLayout.TILE_CENTRE;
            e.to += LaneLayout.TILE_CENTRE;
        }
        return out;
    }

    private static void readGlitches(List<NoteGlitch> src, ArrayList<DGlitch> dst) {
        for (NoteGlitch q : src) {
            DGlitch y = new DGlitch();
            y.from = q.hasFrom() ? q.from() : Float.NaN;
            y.to = q.hasTo() ? q.to() : Float.NaN;
            y.intensity = q.intensity();
            y.intensityEnd = q.intensityEnd() != q.intensity() ? q.intensityEnd() : Float.NaN;
            y.exponent = q.exponent();
            y.rate = q.rate();
            y.split = q.split();
            y.slice = q.slice();
            y.flicker = q.flicker();
            dst.add(y);
        }
    }

    private static void readDistortions(List<NoteDistortion> src, ArrayList<DDist> dst) {
        for (NoteDistortion q : src) {
            DDist y = new DDist();
            y.from = q.hasFrom() ? q.from() : Float.NaN;
            y.to = q.hasTo() ? q.to() : Float.NaN;
            y.intensity = q.intensity();
            y.intensityEnd = q.intensityEnd() != q.intensity() ? q.intensityEnd() : Float.NaN;
            y.exponent = q.exponent();
            dst.add(y);
        }
    }

    private static void read(List<Events> src, ArrayList<Ev> dst) {
        if (src == null) return;
        for (Events e : src) {
            dst.add(new Ev(e.startTime(), e.endTime(), e.initialValue(), e.finalValue(), e.exponent()));
        }
    }

    // ================================================================== writing

    /**
     * The format of {@link #toJson()}: the editor's own text (undo, autosaves, the preview) stays v4, which the
     * game still reads; saving turns it into v5 ({@link Library#writeChart}, Kelvin 2026-10-27).
     */
    public static final int TEXT_FORMAT = 4;

    /** @return the chart as v4 text ({@link #TEXT_FORMAT}, CHART_FORMAT_v4 §11). */
    public String toJson() {
        return toJson(Float.NaN);
    }

    /**
     * @param sentinelBeat when finite, adds an invisible mine at that beat (preview only: it keeps
     *                     the preview engine running past the last real note)
     */
    public String toJson(float sentinelBeat) {
        sortAll();
        StringBuilder b = new StringBuilder(64 + notes.size() * 220);
        b.append("{\n");
        b.append("  \"formatVersion\": ").append(TEXT_FORMAT).append(",\n");
        b.append("  \"iterations\": ").append(iterations).append(",\n");
        kv(b, "title", title);
        kv(b, "artist", artist);
        kv(b, "illustrator", illustrator);
        kv(b, "charter", charter);
        kv(b, "level", level);
        b.append("  \"chartConstant\": ");
        try {
            float cc = Float.parseFloat(constant.trim());
            b.append(Float.isFinite(cc) ? num(cc) : str(constant));
        } catch (NumberFormatException e) {
            b.append(str(constant));
        }
        b.append(",\n");
        b.append("  \"bpm\": ").append(num(bpms.get(0).bpm)).append(",\n");
        if (bpms.size() >= 2) {
            b.append("  \"bpmList\": [");
            for (int i = 0; i < bpms.size(); i++) {
                if (i > 0) b.append(", ");
                b.append("{ \"beat\": ").append(num(bpms.get(i).beat)).append(", \"bpm\": ")
                    .append(num(bpms.get(i).bpm)).append(" }");
            }
            b.append("],\n");
        }
        if (flowFall) b.append("  \"fallBy\": \"flow\",\n");
        if (!flows.isEmpty()) {
            b.append("  \"flowBpmList\": [");
            for (int i = 0; i < flows.size(); i++) {
                if (i > 0) b.append(", ");
                b.append("{ \"beat\": ").append(num(flows.get(i).beat)).append(", \"bpm\": ")
                    .append(num(flows.get(i).bpm)).append(" }");
            }
            b.append("],\n");
        }
        if (offsetMs != 0f && Float.isFinite(offsetMs)) b.append("  \"offsetMs\": ")
            .append(num(offsetMs)).append(",\n");
        // keys stand on their own x (game format 6, CHART_FORMAT §9): no lanes
        b.append("  \"laneless\": true,\n");
        if (!fixedLanes.isEmpty()) {
            // 定轨 / 无轨 sections (game 7.184, CHART_FORMAT §12)
            b.append("  \"fixedLanes\": [");
            for (int i = 0; i < fixedLanes.size(); i++) {
                if (i > 0) b.append(", ");
                b.append("{ \"beat\": ").append(num(fixedLanes.get(i).beat)).append(", \"count\": ")
                    .append(fixedLanes.get(i).count).append(" }");
            }
            b.append("],\n");
        }
        b.append("  \"notes\": [\n");
        boolean first = true;
        for (DNote n : notes) {
            if (!first) b.append(",\n");
            first = false;
            note(b, n);
        }
        if (Float.isFinite(sentinelBeat)) {
            if (!first) b.append(",\n");
            b.append("    { \"type\": \"mine\", \"line\": \"lower\", \"x\": 0")
                .append(", \"judgement\": ").append(num(sentinelBeat)).append(", \"appearance\": ")
                .append(num(sentinelBeat)).append(" }");
        }
        b.append("\n  ],\n");
        b.append("  \"LineEvents\": {\n");
        b.append("    \"upperJudgementLine\": ").append(num(upper)).append(", \"lowerJudgementLine\": ")
            .append(num(lower)).append(",\n");
        b.append("    \"upperEvents\": ");
        events(b, upperEv, "JudgementUp");
        b.append(",\n    \"lowerEvents\": ");
        events(b, lowerEv, "JudgementDown");
        if (laneScale != 1f || !laneScaleEv.isEmpty()) {
            b.append(",\n    \"laneScale\": ").append(num(laneScale)).append(",\n    \"laneScaleEvents\": ");
            events(b, laneScaleEv, "LaneScale");
        }
        if (cameraFrame != 0f || !cameraFrameEv.isEmpty()) {
            b.append(",\n    \"cameraFrame\": ").append(num(cameraFrame)).append(",\n    \"cameraFrameEvents\": ");
            events(b, cameraFrameEv, "CameraFrame");
        }
        // 叠加层 (game 7.177), only when there are some
        if (!upperLay.isEmpty()) events(b.append(",\n    \"upperLayers\": "), upperLay, null);
        if (!lowerLay.isEmpty()) events(b.append(",\n    \"lowerLayers\": "), lowerLay, null);
        if (!cameraLay.isEmpty()) events(b.append(",\n    \"cameraLayers\": "), cameraLay, null);
        b.append("\n  }");
        // only when there are some, after LineEvents (ChartMapper.write does the same)
        if (!veils.isEmpty()) {
            b.append(",\n  \"veils\": [\n");
            for (int i = 0; i < veils.size(); i++) {
                veil(b, veils.get(i));
                b.append(i + 1 < veils.size() ? ",\n" : "\n");
            }
            b.append("  ]");
        }
        if (!curtains.isEmpty()) {
            b.append(",\n  \"curtains\": [\n");
            for (int i = 0; i < curtains.size(); i++) {
                curtain(b, curtains.get(i));
                b.append(i + 1 < curtains.size() ? ",\n" : "\n");
            }
            b.append("  ]");
        }
        if (!strokes.isEmpty()) {
            b.append(",\n  \"strokes\": [\n");
            for (int i = 0; i < strokes.size(); i++) {
                stroke(b, strokes.get(i));
                b.append(i + 1 < strokes.size() ? ",\n" : "\n");
            }
            b.append("  ]");
        }
        b.append("\n}\n");
        return b.toString();
    }

    /** §13: a custom line's points, then only what differs from the defaults (as the game writes it). */
    private static void stroke(StringBuilder b, DStroke st) {
        b.append("    { \"points\": [");
        for (int i = 0; i < st.pointCount(); i++) {
            b.append(i > 0 ? ", [" : "[").append(num(st.points[2 * i])).append(", ").append(num(st.points[2 * i + 1]))
                .append(']');
        }
        b.append(']');
        if (st.curve) b.append(", \"curve\": true");
        if (st.closed) b.append(", \"closed\": true");
        if (st.front) b.append(", \"front\": true");
        if (Float.isFinite(st.from)) b.append(", \"from\": ").append(num(st.from));
        if (Float.isFinite(st.to)) b.append(", \"to\": ").append(num(st.to));
        if (st.drawIn != 0f) b.append(", \"drawIn\": ").append(num(st.drawIn));
        if (st.drawOut != 0f) b.append(", \"drawOut\": ").append(num(st.drawOut));
        if (st.width != Stroke.DEFAULT_WIDTH) b.append(", \"width\": ").append(num(st.width));
        if (st.color != Stroke.DEFAULT_COLOR) b.append(", \"color\": ").append(str(colorText(st.color)));
        if (st.opacity != 1f) b.append(", \"opacity\": ").append(num(st.opacity));
        if (!st.opacityEv.isEmpty()) events(b.append(", \"opacityEvents\": "), st.opacityEv, "Opacity");
        glitches(b, st.glitches);
        distortions(b, st.distortions);
        b.append(" }");
    }

    /** §11.12: the shape, radius or points, the centre; angle, opacity, times and events only when used. */
    /** A curtain: a veil's keys, then its blur when not the default and its blur events when it has some. */
    private static void curtain(StringBuilder b, DVeil v) {
        veil(b, v);
        b.setLength(b.length() - 2); // reopen the object veil() closed with " }"
        if (v.blur != Veil.DEFAULT_BLUR) b.append(", \"blur\": ").append(num(v.blur));
        if (!v.blurEv.isEmpty()) events(b.append(", \"blurEvents\": "), v.blurEv, null);
        b.append(" }");
    }

    private static void veil(StringBuilder b, DVeil v) {
        b.append("    { \"shape\": \"").append((v.polygon ? Veil.Shape.POLYGON : Veil.Shape.CIRCLE).json)
            .append('"');
        if (v.polygon) {
            b.append(", \"points\": [");
            for (int i = 0; i < v.pointCount(); i++) {
                b.append(i > 0 ? ", [" : "[").append(num(v.points[2 * i])).append(", ")
                    .append(num(v.points[2 * i + 1])).append(']');
            }
            b.append(']');
        } else {
            b.append(", \"radius\": ").append(num(v.radius));
        }
        b.append(", \"x\": ").append(num(v.x + LaneLayout.TILE_CENTRE)).append(", \"y\": ").append(num(v.y));
        if (v.rotation != 0f) b.append(", \"rotation\": ").append(num(v.rotation));
        if (v.opacity != 1f) b.append(", \"opacity\": ").append(num(v.opacity));
        if (Float.isFinite(v.start)) b.append(", \"startTime\": ").append(num(v.start));
        if (Float.isFinite(v.end)) b.append(", \"endTime\": ").append(num(v.end));
        // the centre's events have no type of their own (the game writes none either)
        if (!v.xEv.isEmpty()) events(b.append(", \"xEvents\": "), toFrame(v.xEv), null);
        if (!v.yEv.isEmpty()) events(b.append(", \"yEvents\": "), v.yEv, null);
        if (!v.rotEv.isEmpty()) events(b.append(", \"rotationEvents\": "), v.rotEv, "Rotation");
        if (!v.opacityEv.isEmpty()) events(b.append(", \"opacityEvents\": "), v.opacityEv, "Opacity");
        glitches(b, v.glitches);
        distortions(b, v.distortions);
        b.append(" }");
    }

    private static void note(StringBuilder b, DNote n) {
        if (n.floating && n.canFloat()) {
            floating(b, n);
            return;
        }
        b.append("    { \"type\": \"").append(n.type.json).append("\", \"line\": \"").append(n.line.json).append('"');
        boolean placed = n.inLane();
        if (placed) b.append(", \"x\": ").append(num(n.x));
        b.append(", \"judgement\": ").append(num(n.judgement));
        boolean sus = n.sustained();
        if (sus) {
            float dur = n.duration > 0f ? n.duration : 0.25f;
            b.append(", \"duration\": ").append(num(dur));
            b.append(", \"height\": ").append(num(Math.max(0f, n.height)));
        }
        b.append(", \"appearance\": ").append(num(appearanceOf(n)));
        // two finite numbers, only when not the origin, on every kind of key (ChartFormatV5 does the same)
        if (n.pivotX != 0f || n.pivotY != 0f) b.append(", \"pivot\": [").append(num(n.pivotX)).append(", ")
            .append(num(n.pivotY)).append(']');
        // only on a TIDE, a TIDE STREAM or their mines and only when moving (ChartFormatV5 does the same)
        if (!n.inLane() && n.waveSpeed != 0f) b.append(", \"waveSpeed\": ").append(num(n.waveSpeed));
        b.append(", \"MovementEvents\": ");
        events(b, n.move, "Movement", n);
        b.append(", \"RotationEvents\": ");
        events(b, n.rot, "Rotation");
        if (placed && !n.xEv.isEmpty()) {
            b.append(", \"xEvents\": ");
            events(b, n.xEv, "X");
        }
        // 叠加层 (game 7.177), only when there are some (ChartFormatV5 does the same)
        if (placed && !n.xLay.isEmpty()) events(b.append(", \"xLayers\": "), n.xLay, null);
        if (!n.posLay.isEmpty()) events(b.append(", \"positionLayers\": "), n.posLay, null);
        if (!n.rotLay.isEmpty()) events(b.append(", \"rotationLayers\": "), n.rotLay, null);
        // the orbit replaces the pivot; its moves only with it (ChartFormatV5 does the same)
        if (placed && n.orbit) {
            // the working text keeps orbit and veil x in the 0–1920 frame (the game reads it so)
            b.append(", \"orbit\": [").append(num(n.orbitX + LaneLayout.TILE_CENTRE)).append(", ").append(num(n.orbitY))
                .append(']');
            if (!n.orbXEv.isEmpty()) events(b.append(", \"orbitXEvents\": "), toFrame(n.orbXEv), "OrbitX");
            if (!n.orbYEv.isEmpty()) events(b.append(", \"orbitYEvents\": "), n.orbYEv, "OrbitY");
            if (!Float.isNaN(n.orbitRadius)) b.append(", \"orbitRadius\": ").append(num(n.orbitRadius));
            if (!n.orbRadEv.isEmpty()) events(b.append(", \"orbitRadiusEvents\": "), n.orbRadEv, "OrbitRadius");
            if (!Float.isNaN(n.orbitAngle)) b.append(", \"orbitAngle\": ").append(num(n.orbitAngle));
            if (!n.orbAngEv.isEmpty()) events(b.append(", \"orbitAngleEvents\": "), n.orbAngEv, "OrbitAngle");
            if (n.orbitConflict != NoteOrbit.CONFLICT_UNSET) b.append(", \"orbitConflict\": \"")
                .append(n.orbitConflict == NoteOrbit.CONFLICT_ORBIT ? "orbit" : "move").append('"');
            if (n.orbitRigid) b.append(", \"orbitRigid\": true");
        }
        if (n.fake && n.canFake()) b.append(", \"fake\": true");
        b.append(", \"DisappearEvents\": ");
        events(b, n.alpha, "Opacity");
        if (sus && !n.heightEv.isEmpty()) {
            b.append(", \"HeightEvents\": ");
            events(b, n.heightEv, "Height");
        }
        // one segment is written as an object, several as an array (ChartMapper.write does the same)
        ArrayList<DTrail> tr = new ArrayList<>();
        for (DTrail t : n.trails) if (NoteTrail.copyCount(t.length, t.density) > 0) tr.add(t);
        if (!tr.isEmpty()) {
            b.append(", \"trail\": ").append(tr.size() > 1 ? "[ " : "");
            for (int i = 0; i < tr.size(); i++) {
                DTrail t = tr.get(i);
                b.append(i > 0 ? ", " : "").append("{ \"length\": ").append(num(t.length)).append(", \"density\": ")
                    .append(num(t.density)).append(", \"alpha\": ").append(num(t.alpha)).append(", \"fade\": ")
                    .append(num(t.fade)).append(", \"mode\": \"").append(t.level ? "level" : "path").append('"');
                if (Float.isFinite(t.from)) b.append(", \"from\": ").append(num(t.from));
                if (Float.isFinite(t.to)) b.append(", \"to\": ").append(num(t.to));
                b.append(" }");
            }
            b.append(tr.size() > 1 ? " ]" : "");
        }
        glitches(b, n.glitches);
        distortions(b, n.distortions);
        b.append(" }");
    }

    /**
     * A floating key in the v4 working text (CHART_FORMAT §11.6): {@code "float": true}, its centre, its size and
     * appearance only when written, its chain, then its x / y / size / fill events and opacity; nothing of a line.
     */
    private static void floating(StringBuilder b, DNote n) {
        b.append("    { \"type\": \"").append(n.type.json).append("\", \"float\": true, \"x\": ").append(num(n.x))
            .append(", \"y\": ").append(num(n.y));
        if (Float.isFinite(n.size) && n.size > 0f) b.append(", \"size\": ").append(num(n.size));
        b.append(", \"judgement\": ").append(num(n.judgement));
        if (n.type == NoteType.HOLD) b.append(", \"duration\": ").append(num(n.duration > 0f ? n.duration : 0.25f));
        else if (n.type == NoteType.MINE && n.duration > 0f) b.append(", \"duration\": ").append(num(n.duration));
        if (Float.isFinite(n.appearance)) b.append(", \"appearance\": ").append(num(n.appearance));
        if (n.chain != null && n.type == NoteType.DRAG) b.append(", \"chain\": ").append(str(n.chain));
        if (n.fake) b.append(", \"fake\": true");
        if (!n.xEv.isEmpty()) events(b.append(", \"xEvents\": "), n.xEv, "X");
        if (!n.yEv.isEmpty()) events(b.append(", \"yEvents\": "), n.yEv, null);
        if (!n.sizeEv.isEmpty()) events(b.append(", \"sizeEvents\": "), n.sizeEv, null);
        if (!n.fillEv.isEmpty()) events(b.append(", \"fillEvents\": "), n.fillEv, null);
        if (!n.alpha.isEmpty()) events(b.append(", \"DisappearEvents\": "), n.alpha, "Opacity");
        glitches(b, n.glitches);
        distortions(b, n.distortions);
        b.append(" }");
    }

    /** Glitch segments: none, one object or an array, sorted by start like the game keeps them (§6.9). */
    private static void glitches(StringBuilder b, List<DGlitch> list) {
        if (list.isEmpty()) return;
        ArrayList<DGlitch> gl = new ArrayList<>(list);
        gl.sort((p, q) -> Float.compare(fromKey(p.from), fromKey(q.from)));
        b.append(", \"glitch\": ").append(gl.size() > 1 ? "[ " : "");
        for (int i = 0; i < gl.size(); i++) {
            DGlitch x = gl.get(i);
            b.append(i > 0 ? ", { " : "{ ");
            segHead(b, x.from, x.to, x.intensity, x.intensityEnd, x.exponent);
            b.append(", \"rate\": ").append(num(x.rate)).append(", \"split\": ").append(num(x.split))
                .append(", \"slice\": ").append(num(x.slice)).append(", \"flicker\": ")
                .append(num(x.flicker)).append(" }");
        }
        b.append(gl.size() > 1 ? " ]" : "");
    }

    /** Distortion segments, written like glitches (§6.11). */
    private static void distortions(StringBuilder b, List<DDist> list) {
        if (list.isEmpty()) return;
        ArrayList<DDist> dl = new ArrayList<>(list);
        dl.sort((p, q) -> Float.compare(fromKey(p.from), fromKey(q.from)));
        b.append(", \"distortion\": ").append(dl.size() > 1 ? "[ " : "");
        for (int i = 0; i < dl.size(); i++) {
            DDist x = dl.get(i);
            b.append(i > 0 ? ", { " : "{ ");
            segHead(b, x.from, x.to, x.intensity, x.intensityEnd, x.exponent);
            b.append(" }");
        }
        b.append(dl.size() > 1 ? " ]" : "");
    }

    /** The keys glitch and distortion segments share: from / to only when bounded, the ramp only when used. */
    private static void segHead(StringBuilder b, float from, float to, float i0, float i1, float exp) {
        if (Float.isFinite(from)) b.append("\"from\": ").append(num(from)).append(", ");
        if (Float.isFinite(to)) b.append("\"to\": ").append(num(to)).append(", ");
        b.append("\"intensity\": ").append(num(i0));
        if (!Float.isNaN(i1) && i1 != i0) b.append(", \"intensityEnd\": ").append(num(i1));
        if (exp != 1f) b.append(", \"exponent\": ").append(num(exp));
    }

    /** @return the sort key of a segment start: unbounded (NaN) first. */
    static float fromKey(float from) {
        return Float.isNaN(from) ? Float.NEGATIVE_INFINITY : from;
    }

    /** §11.4: the earliest event start; a note without events keeps what it had (§9 no-approach notes). */
    private static float appearanceOf(DNote n) {
        float a = Float.POSITIVE_INFINITY;
        for (Ev e : n.move) a = Math.min(a, e.start);
        for (Ev e : n.rot) a = Math.min(a, e.start);
        for (Ev e : n.alpha) a = Math.min(a, e.start);
        if (a != Float.POSITIVE_INFINITY && !n.move.isEmpty()) return a;
        if (Float.isFinite(n.appearance)) return n.appearance;
        return a != Float.POSITIVE_INFINITY ? Math.min(a, n.judgement - Note.DEFAULT_APPROACH_BEATS)
            : n.judgement - Note.DEFAULT_APPROACH_BEATS;
    }

    private static void events(StringBuilder b, List<Ev> list, String type) {
        events(b, list, type, null);
    }

    /** @return a floating key's channel at beat {@code t}: events hold their end values; before the first, base */
    private static float at(List<Ev> list, float t, float base) {
        Ev h = null;
        for (Ev e : list) if (e.start <= t && (h == null || e.start >= h.start)) h = e;
        return h == null ? base : h.valueAt(t);
    }

    /** Writes an event list; {@code fallOf} (movement only) marks its absolute default falls {@code "rel": false}. */
    private static void events(StringBuilder b, List<Ev> list, String type, DNote fallOf) {
        if (list.isEmpty()) {
            b.append("[]");
            return;
        }
        b.append('[');
        for (int i = 0; i < list.size(); i++) {
            Ev e = list.get(i);
            if (i > 0) b.append(", ");
            b.append(type != null ? "{ \"type\": \"" + type + "\", " : "{ ").append("\"startTime\": ")
                .append(num(e.start)).append(", \"endTime\": ").append(num(e.end))
                .append(", \"initial\": ").append(num(e.from)).append(", \"final\": ").append(num(e.to))
                .append(", \"exponent\": ").append(num(e.exp));
            if (e.rel) b.append(", \"rel\": true");
            else if (fallOf != null && fallOf.defaultFall(e)) b.append(", \"rel\": false");
            if (Float.isFinite(e.flow)) b.append(", \"flow\": ").append(num(e.flow));
            if (e.link != null) b.append(", \"link\": ").append(e.link.json());
            b.append(" }");
        }
        b.append(']');
    }

    private static void kv(StringBuilder b, String key, String value) {
        b.append("  \"").append(key).append("\": ").append(str(value)).append(",\n");
    }

    /** @return a finite float as short JSON text that reads back to the same float. */
    public static String num(float f) {
        if (!Float.isFinite(f)) f = 0f;
        if (f == Math.rint(f) && Math.abs(f) < 1e9f) return Long.toString((long) f);
        return Float.toString(f);
    }

    public static String str(String s) {
        StringBuilder b = new StringBuilder(s.length() + 2).append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                default:
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
            }
        }
        return b.append('"').toString();
    }

    static void copyAll(List<Ev> src, List<Ev> dst) {
        for (Ev e : src) dst.add(e.copy());
    }

    private static void shiftAll(List<Ev> list, float db) {
        for (Ev e : list) {
            e.start += db;
            e.end += db;
        }
    }

    private static String nz(String s) {
        return s != null ? s : "";
    }
}
