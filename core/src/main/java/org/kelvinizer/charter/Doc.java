package org.kelvinizer.charter;

import org.kelvinizer.elements.Chart;
import org.kelvinizer.elements.Events;
import org.kelvinizer.elements.JudgementLine;
import org.kelvinizer.elements.Lane;
import org.kelvinizer.elements.Line;
import org.kelvinizer.elements.Note;
import org.kelvinizer.elements.NoteDistortion;
import org.kelvinizer.elements.NoteGlitch;
import org.kelvinizer.elements.NoteOrbit;
import org.kelvinizer.elements.NoteTrail;
import org.kelvinizer.elements.NoteType;
import org.kelvinizer.elements.Veil;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 正在编辑的谱面（可以修改）：和游戏的谱面（1920 × 1200 坐标，判定线 1080 / 120）一一对应；自己的文字是完整的 v4，保存时换成 v5。
 * The chart being edited (mutable). It mirrors the game's chart (positions in the 1920 × 1200 frame, lines at
 * 1080 / 120) one to one; {@link #toJson} writes it as complete, explicit v4 text ({@link #TEXT_FORMAT}), which
 * {@link Library#writeChart} turns into the v5 file (CHART_FORMAT §8). Reading goes through the game's own
 * {@code ChartMapper} ({@link #fromChart}), so older files arrive converted.
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
    /** Lanes sorted by id (non-zero, unique). */
    public final ArrayList<DLane> lanes = new ArrayList<>();
    /**
     * Finite lanes: exactly {@link #laneSet} of this many, sharing 1920 × the global scale around the
     * axis. 0 = unlimited (fixed widths tiled out from the axis, the screen only shows some). Key
     * {@code laneCount}, read by the game as {@code Chart.laneLimit()} (CHART_FORMAT §3.1).
     */
    public int laneCount;
    public float upper = JudgementLine.DEFAULT_UPPER, lower = JudgementLine.DEFAULT_LOWER;
    public final ArrayList<Ev> upperEv = new ArrayList<>(), lowerEv = new ArrayList<>();
    /**
     * Camera frame (LineEvents {@code cameraFrame} / {@code cameraFrameEvents}): how far right of the
     * screen centre (x 960) the axis between lanes −1 and +1 sits, in reference units; every lane
     * moves with it. Replaces the old left / right bounds.
     */
    public float cameraFrame;
    public final ArrayList<Ev> cameraFrameEv = new ArrayList<>();
    /** Global lane scale (LineEvents {@code laneScale} / {@code laneScaleEvents}): every lane width × this. */
    public float laneScale = 1f;
    public final ArrayList<Ev> laneScaleEv = new ArrayList<>();
    public final ArrayList<DNote> notes = new ArrayList<>();
    /** Veils in file order (§6.10). */
    public final ArrayList<DVeil> veils = new ArrayList<>();
    /** Curtains (水帘, CHART_FORMAT §6.1) in file order: veils that only blur what is under them. */
    public final ArrayList<DVeil> curtains = new ArrayList<>();

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
            return e;
        }

        /** @return the eased value at {@code t} (same formula as the game's {@code Events}). */
        public float valueAt(float t) {
            if (end <= start) return to;
            float p = Math.max(0f, Math.min(1f, (t - start) / (end - start)));
            return from + (to - from) * (float) Math.pow(p, exp);
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

    /** A lane: id, width weight, decoration opacity and their events (§3). */
    public static final class DLane {
        public int id;
        public float width = Lane.DEFAULT_WIDTH, opacity = Lane.DEFAULT_OPACITY;
        public final ArrayList<Ev> widthEv = new ArrayList<>(), opacityEv = new ArrayList<>();
        /**
         * The insertion (§3.6, Kelvin 2026-10-15): start and end beat, initial width and exponent; its
         * final width is {@link #width}. Null for a lane that is always there.
         */
        public Ev insert;
        /**
         * The deletion (§3.7, Kelvin 2026-10-17): from {@code start} the lane shrinks from {@code from} to
         * {@code to} by {@code end}, then is 0 wide. Null for a lane that stays.
         */
        public Ev delete;

        public DLane(int id) {
            this.id = id;
        }

        public DLane copy() {
            DLane l = new DLane(id);
            l.width = width;
            l.opacity = opacity;
            copyAll(widthEv, l.widthEv);
            copyAll(opacityEv, l.opacityEv);
            l.insert = insert != null ? insert.copy() : null;
            l.delete = delete != null ? delete.copy() : null;
            return l;
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
        /** Centre (the format's defaults are the middle of the 1920 × 1200 frame), angle and opacity. */
        public float x = 960f, y = 600f, rotation, opacity = 1f;
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

    /** A note. {@code uid} stays the same across copies, so selections survive undo. */
    public static final class DNote {
        public final int uid;
        public NoteType type;
        public Line line;
        public int lane;
        public float judgement, duration, height;
        /** Appearance beat as read from the file; NaN for new notes (written as the earliest event). */
        public float appearance = Float.NaN;
        /** The tail {@link Doc#setAutoTail} last set under 统一流速; while the tail keeps it, it follows the flow speed. */
        public float autoH = Float.NaN;
        public final ArrayList<Ev> move = new ArrayList<>(), rot = new ArrayList<>();
        public final ArrayList<Ev> alpha = new ArrayList<>(), heightEv = new ArrayList<>();
        /** 横移 events (Kelvin 2026-10-28): the offset by beat; outside every event {@link #offsetX} applies. */
        public final ArrayList<Ev> offEv = new ArrayList<>();
        /**
         * 公转 (§3, Kelvin 2026-10-29): lane notes turn about the absolute point ({@link #orbitX}, {@link #orbitY}) of
         * the 1920 × 1200 reference frame (y up) instead of their pivot; its moves hold their end value.
         */
        public boolean orbit;
        public float orbitX = 960f, orbitY = 600f;
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

        /** @return true when the fake flag applies to this type (水滴, 水流, 水雷, 浪花 / 潮汐). */
        public boolean canFake() {
            return type == NoteType.TAP || type == NoteType.HOLD || type == NoteType.MINE || type == NoteType.UNIVERSAL;
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
        /** Lane-note body offset (§3, Kelvin 2026-10-28): reference units to the screen's right on both lines. */
        public float offsetX;

        public DNote(NoteType type, Line line, int lane, float judgement) {
            this(nextUid++, type, line, lane, judgement);
        }

        private DNote(int uid, NoteType type, Line line, int lane, float judgement) {
            this.uid = uid;
            this.type = type;
            this.line = line;
            this.lane = lane;
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

        /** @return the Chinese display name (水滴, 水流, 水雷, 轨道水雷, 浪花, 潮汐, 浪花水雷, 潮汐水雷). */
        public String nameZh() {
            return type.displayZh(duration > 0f);
        }

        /** @return true when no movement event is written and the note never approaches (§9 exception). */
        public boolean noApproach() {
            return move.isEmpty() && !Float.isNaN(appearance) && appearance >= judgement;
        }

        /** @param sameUid keep the uid (undo snapshots) or take a new one (paste, duplicate) */
        public DNote copy(boolean sameUid) {
            DNote n = sameUid ? new DNote(uid, type, line, lane, judgement) : new DNote(type, line, lane, judgement);
            n.duration = duration;
            n.height = height;
            n.appearance = appearance;
            n.autoH = autoH;
            copyAll(move, n.move);
            copyAll(rot, n.rot);
            copyAll(offEv, n.offEv);
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
            copyAll(alpha, n.alpha);
            copyAll(heightEv, n.heightEv);
            for (DTrail t : trails) n.trails.add(t.copy());
            for (DGlitch x : glitches) n.glitches.add(x.copy());
            for (DDist x : distortions) n.distortions.add(x.copy());
            n.pivotX = pivotX;
            n.pivotY = pivotY;
            n.waveSpeed = waveSpeed;
            n.offsetX = offsetX;
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
            shiftRel(offEv, db);
            shiftRel(orbXEv, db);
            shiftRel(orbYEv, db);
            shiftRel(orbRadEv, db);
            shiftRel(orbAngEv, db);
            shiftRel(alpha, db);
            shiftRel(heightEv, db);
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

        /** Moves the note by {@code db} beats together with all its events, absolute ones too (「整体平移」). */
        public void shift(float db) {
            judgement += db;
            if (!Float.isNaN(appearance)) appearance += db;
            shiftAll(move, db);
            shiftAll(rot, db);
            shiftAll(offEv, db);
            shiftAll(orbXEv, db);
            shiftAll(orbYEv, db);
            shiftAll(orbRadEv, db);
            shiftAll(orbAngEv, db);
            shiftAll(alpha, db);
            shiftAll(heightEv, db);
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
                && Math.abs(e.start - (judgement - Note.DEFAULT_APPROACH_BEATS)) < 1e-4f && e.from == SPAWN
                && e.to == 0f && e.exp == 1f;
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
        d.laneCount = laneCount;
        for (DLane l : lanes) d.lanes.add(l.copy());
        d.upper = upper;
        d.lower = lower;
        d.cameraFrame = cameraFrame;
        copyAll(cameraFrameEv, d.cameraFrameEv);
        d.laneScale = laneScale;
        copyAll(laneScaleEv, d.laneScaleEv);
        copyAll(upperEv, d.upperEv);
        copyAll(lowerEv, d.lowerEv);
        for (DNote n : notes) d.notes.add(n.copy(true));
        for (DVeil v : veils) d.veils.add(v.copy(true));
        for (DVeil v : curtains) d.curtains.add(v.copy(true));
        return d;
    }

    public DNote byUid(int uid) {
        for (DNote n : notes) if (n.uid == uid) return n;
        return null;
    }

    public DVeil veilByUid(int uid) {
        for (DVeil v : veils) if (v.uid == uid) return v;
        for (DVeil v : curtains) if (v.uid == uid) return v;
        return null;
    }

    public DLane lane(int id) {
        for (DLane l : lanes) if (l.id == id) return l;
        return null;
    }

    /** @return the lane ids of {@code n} finite lanes: −⌊n/2⌋..−1, 1..⌈n/2⌉. */
    public static int[] laneSet(int n) {
        int left = n / 2, right = n - left;
        int[] ids = new int[n];
        for (int i = 0; i < left; i++) ids[i] = i - left;
        for (int i = 0; i < right; i++) ids[left + i] = i + 1;
        return ids;
    }

    /** Adds lane {@code id} (default width and opacity) if it is missing. */
    public void ensureLane(int id) {
        if (id == 0 || lane(id) != null) return;
        lanes.add(new DLane(id));
        sortLanes();
    }

    /**
     * @return whether lanes {@code a} &lt; {@code b} are next to each other (unlimited lanes: every id but 0 is a
     * lane).
     */
    public boolean adjacent(int a, int b) {
        if (a >= b) return false;
        for (int k = a + 1; k < b; k++) if (k == 0 ? lane(0) != null : laneCount == 0 || lane(k) != null) return false;
        return true;
    }

    /**
     * Inserts a lane between the adjacent lanes {@code a} &lt; {@code b} (§3.6): the free id between
     * them (0 between −1 and 1), else {@code b} (or {@code a} on the left side) after moving that
     * lane and every lane further out, with their notes and events, one id outwards. It appears at
     * {@code beat}, growing over one beat from 0 to its neighbours' mean width, exponent 1.
     * @return the new lane's id
     */
    public int insertLane(int a, int b, float beat) {
        float w = 0.5f * (lane(a) != null ? lane(a).width : Lane.DEFAULT_WIDTH)
            + 0.5f * (lane(b) != null ? lane(b).width : Lane.DEFAULT_WIDTH);
        int id;
        if (a < 0 && b > 0) id = 0;
        else if (laneCount > 0 && b - a > 1) id = a + 1;
        else if (a >= 0) shiftLanes(id = b, 1);
        else shiftLanes(id = a, -1);
        DLane l = new DLane(id);
        l.width = w;
        l.insert = new Ev(beat, beat + 1f, 0f, w, 1f);
        lanes.add(l);
        sortLanes();
        if (laneCount > 0) laneCount = lanes.size();
        return id;
    }

    /**
     * Removes lane {@code id} and the notes on it, then moves the lanes further out one id back in (the reverse of
     * {@link #insertLane}).
     */
    public void removeLane(int id) {
        lanes.removeIf(l -> l.id == id);
        notes.removeIf(n -> n.inLane() && n.lane == id);
        if (id > 0) shiftLanes(id + 1, -1);
        else if (id < 0) shiftLanes(id - 1, 1);
        sortLanes();
        if (laneCount > 0) laneCount = lanes.size();
    }

    /** Moves lane {@code from} and every lane further out from the axis (with their notes) by {@code by} ids. */
    private void shiftLanes(int from, int by) {
        for (DLane l : lanes) if (from > 0 ? l.id >= from : l.id <= from) l.id += by;
        for (DNote n : notes) if (n.inLane() && (from > 0 ? n.lane >= from : n.lane <= from)) n.lane += by;
    }

    public int laneIndex(int id) {
        for (int i = 0; i < lanes.size(); i++) if (lanes.get(i).id == id) return i;
        return -1;
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

    /** @return the suggested tail: {@link #APPROACH} per beat of the duration, per flow beat under 统一流速. */
    public float autoHeight(DNote n) {
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
     * each note's distance gets the line's offset added back (sky: + (U(t) − U(a)), ground: − (L(t) − L(a))): pieces
     * follow every movement and line event boundary, linear pieces stay one piece, eased ones are cut every
     * {@link #STILL_STEP} beats (1/16 beat where only the fall eases). Events running on past {@code b} are rebuilt
     * unchanged from there. A note on the default fall gets it written out first; the rebuilt events are absolute
     * and not linked to a flow speed. Notes on a 公转 radius or angle are skipped (their place comes from the orbit).
     * @return {changed notes, skipped orbit notes}
     */
    public int[] holdStill(List<DNote> sel, float a, float b) {
        int changed = 0, skipped = 0;
        if (!(b > a)) return new int[]{0, 0};
        for (DNote n : sel) {
            if (n.polar()) {
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
            if (stillNote(n, line, base, up ? 1f : -1f, a, b)) changed++;
            else if (wrote) n.move.clear();
        }
        return new int[]{changed, skipped};
    }

    /** One note of {@link #holdStill}. @return whether its movement changed */
    private static boolean stillNote(DNote n, List<Ev> line, float base, float sign, float from, float to) {
        float ref = lineAt(line, base, from);
        // only the part where the line is away from ref is rebuilt; the note's other events stay as they are
        ArrayList<Float> lc = new ArrayList<>();
        lc.add(from);
        lc.add(to);
        for (Ev e : line) {
            lc.add(e.start);
            lc.add(e.end);
        }
        lc.removeIf(t -> t < from || t > to);
        lc.sort(null);
        float a = Float.NaN, b = Float.NaN;
        for (int i = 0; i + 1 < lc.size(); i++) {
            float x0 = lc.get(i), x1 = lc.get(i + 1);
            if (x1 - x0 < 1e-5f) continue;
            Ev le = cover(line, (x0 + x1) * 0.5f);
            boolean away = le == null ? base != ref : le.from != ref || le.to != ref;
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
        for (Ev e : line) {
            cuts.add(e.start);
            cuts.add(e.end);
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
            boolean lineFlat = !inside || le == null || le.from == le.to || le.end <= le.start;
            boolean noteFlat = e.exp == 1f || e.from == e.to || e.end <= e.start;
            if (noteFlat && (lineFlat || le.exp == 1f)) {
                out.add(still(e, le, inside, line, base, ref, sign, x0, x1, 1f));
            } else if (lineFlat && Math.abs(x0 - e.start) < 1e-5f) {
                out.add(still(e, le, inside, line, base, ref, sign, x0, x1, e.exp));
            } else {
                float step = lineFlat || le.exp == 1f ? STILL_STEP_FALL : STILL_STEP;
                int k = Math.max(1, (int) Math.ceil((x1 - x0) / step - 1e-4f));
                for (int j = 0; j < k; j++) {
                    out.add(still(e, le, inside, line, base, ref, sign, x0 + (x1 - x0) * j / k,
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
    private static Ev still(Ev e, Ev le, boolean inside, List<Ev> line, float base, float ref, float sign, float x0,
                            float x1, float exp) {
        return new Ev(x0, x1, e.valueAt(x0) + offset(le, inside, base, ref, sign, x0),
            e.valueAt(x1) + offset(le, inside, base, ref, sign, x1), exp);
    }

    /** @return what {@link #stillNote} adds at {@code t}: the line's move from {@code ref}, signed for the line. */
    private static float offset(Ev le, boolean inside, float base, float ref, float sign, float t) {
        if (!inside) return 0f;
        return sign * ((le != null ? le.valueAt(t) : base) - ref);
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

    /** @return the value of a line channel at {@code t}: its covering event's, else {@code base}. */
    static float lineAt(List<Ev> line, float base, float t) {
        Ev e = cover(line, t);
        return e != null ? e.valueAt(t) : base;
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
                if (n.move.isEmpty() && !n.noApproach()) n.approach(Note.DEFAULT_APPROACH_BEATS, SPAWN, 1f);
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

    public void sortLanes() {
        lanes.sort(Comparator.comparingInt(l -> l.id));
    }

    /** Sorts notes by judgement (stable), then every event list by start (stable). */
    public void sortAll() {
        notes.sort(Comparator.comparingDouble(n -> n.judgement));
        Comparator<Ev> byStart = Comparator.comparingDouble(e -> e.start);
        for (DNote n : notes) {
            n.move.sort(byStart);
            n.rot.sort(byStart);
            n.offEv.sort(byStart);
            n.orbXEv.sort(byStart);
            n.orbYEv.sort(byStart);
            n.orbRadEv.sort(byStart);
            n.orbAngEv.sort(byStart);
            n.alpha.sort(byStart);
            n.heightEv.sort(byStart);
        }
        for (DLane l : lanes) {
            l.widthEv.sort(byStart);
            l.opacityEv.sort(byStart);
        }
        upperEv.sort(byStart);
        lowerEv.sort(byStart);
        cameraFrameEv.sort(byStart);
        laneScaleEv.sort(byStart);
        sortBpms();
        sortLanes();
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

    /** A new chart: 8 lanes −4..−1, 1..4 of width 240, the game's default lines (120 / 1080), camera frame 0. */
    public static Doc blank(String title, String artist, String illustrator, String level, float bpm, float offsetMs) {
        Doc d = new Doc();
        d.title = nz(title);
        d.artist = nz(artist);
        d.illustrator = nz(illustrator);
        d.level = nz(level);
        d.bpms.add(new Seg(0f, bpm > 0f ? bpm : 120f));
        d.offsetMs = offsetMs;
        for (int id = -4; id <= 4; id++) if (id != 0) d.lanes.add(new DLane(id));
        return d;
    }

    /**
     * Reads the editor-only keys the game's loader skips, so {@link #fromChart} cannot see them:
     * which note events are relative to the judgement, the flow falls ({@code "flow"}) and the chart's
     * {@code flowBpmList} / {@code fallBy}. Without a {@code "rel"} key the default fall
     * ({@link DNote#defaultFall}) is relative (Kelvin 2026-10-13 06:28) and every other event absolute;
     * a key in the file ({@code true}, or {@code false} for a default fall made absolute) wins. Notes
     * are matched by type, line, lane and judgement in file order; event lists keep the order the
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
            if (!text.contains("\"rel\"") && !text.contains("\"flow") && !text.contains("\"fallBy\"")) return;
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
        java.util.HashMap<String, java.util.ArrayDeque<DNote>> byKey = new java.util.HashMap<>();
        for (DNote n : d.notes) {
            byKey.computeIfAbsent(noteKey(n.type.json, n.line.json, n.inLane() ? n.lane : 0, n.judgement),
                k -> new java.util.ArrayDeque<>()).add(n);
        }
        for (com.badlogic.gdx.utils.JsonValue v = list.child; v != null; v = v.next) {
            java.util.ArrayDeque<DNote> q = byKey.get(noteKey(v.getString("type", ""), v.getString("line", "lower"),
                v.getInt("lane", 0), v.getFloat("judgement", v.getFloat("beat", Float.NaN))));
            DNote n = q != null ? q.poll() : null;
            if (n == null) continue;
            // v4 names, else v5 (a v5 note without positionEvents falls by default, relative)
            markRel(v.has("MovementEvents") ? v.get("MovementEvents") : v.get("positionEvents"), n.move);
            markRel(v.has("RotationEvents") ? v.get("RotationEvents") : v.get("rotationEvents"), n.rot);
            markRel(v.get("offsetXEvents"), n.offEv);
            markRel(v.get("orbitXEvents"), n.orbXEv);
            markRel(v.get("orbitYEvents"), n.orbYEv);
            markRel(v.get("orbitRadiusEvents"), n.orbRadEv);
            markRel(v.get("orbitAngleEvents"), n.orbAngEv);
            markRel(v.has("DisappearEvents") ? v.get("DisappearEvents") : v.get("opacityEvents"), n.alpha);
            markRel(v.has("HeightEvents") ? v.get("HeightEvents") : v.get("heightEvents"), n.heightEv);
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

    private static String noteKey(String type, String line, int lane, float judgement) {
        return type + '|' + line + '|' + lane + '|' + Math.round(judgement * 10000.0);
    }

    private static void markRel(com.badlogic.gdx.utils.JsonValue arr, List<Ev> list) {
        if (arr == null) return;
        int i = 0;
        for (com.badlogic.gdx.utils.JsonValue e = arr.child; e != null && i < list.size(); e = e.next, i++) {
            com.badlogic.gdx.utils.JsonValue r = e.get("rel");
            if (r != null && r.isBoolean()) list.get(i).rel = r.asBoolean();
            float fb = e.getFloat("flow", Float.NaN);
            if (fb > 0f && fb < 1000f) list.get(i).flow = fb;
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
        d.laneCount = c.laneLimit();
        for (Lane l : c.lanes()) {
            if (l.implicit()) continue; // filled in by the loader, never written
            DLane x = new DLane(l.id());
            x.width = l.baseWidth();
            x.opacity = l.baseOpacity();
            read(l.widthEvents(), x.widthEv);
            read(l.opacityEvents(), x.opacityEv);
            if (l.inserted()) {
                Events e = l.insert();
                x.insert = new Ev(e.startTime(), e.endTime(), e.initialValue(), x.width, e.exponent());
            }
            if (l.deleted()) {
                Events e = l.deletion();
                x.delete = new Ev(e.startTime(), e.endTime(), e.initialValue(), e.finalValue(), e.exponent());
            }
            d.lanes.add(x);
        }
        if (d.lanes.isEmpty()) for (int id = -4; id <= 4; id++) if (id != 0) d.lanes.add(new DLane(id));
        JudgementLine j = c.lineEvents();
        d.upper = j.upperBase();
        d.lower = j.lowerBase();
        read(j.upperEvents(), d.upperEv);
        read(j.lowerEvents(), d.lowerEv);
        d.cameraFrame = j.cameraFrameBase();
        read(j.cameraFrameEvents(), d.cameraFrameEv);
        d.laneScale = j.laneScaleBase();
        read(j.laneScaleEvents(), d.laneScaleEv);
        for (Note n : c.notes()) {
            DNote x = new DNote(n.type(), n.line(), n.laneId(), n.judgement());
            x.duration = n.duration();
            x.height = n.height();
            x.appearance = n.appearance();
            x.pivotX = n.pivotX();
            x.pivotY = n.pivotY();
            x.waveSpeed = n.waveSpeed();
            x.offsetX = n.offsetX();
            read(n.movementEvents(), x.move);
            // a v5 file leaves the default fall out; the editor keeps it as an event, as a v4 file wrote it
            if (c.sourceFormat() >= 5 && x.move.isEmpty() && n.usesDefaultApproach()) {
                x.move.add(new Ev(n.judgement() - Note.DEFAULT_APPROACH_BEATS, n.judgement(), SPAWN, 0f, 1f));
            }
            read(n.rotationEvents(), x.rot);
            read(n.offsetXEvents(), x.offEv);
            if (n.orbit() != null) {
                x.orbit = true;
                x.orbitX = n.orbit().x();
                x.orbitY = n.orbit().y();
                read(n.orbit().xEvents(), x.orbXEv);
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
        d.sortAll();
        return d;
    }

    /** One veil (or curtain) of the game's chart as the editor keeps it. */
    private static DVeil readVeil(Veil v) {
        DVeil x = new DVeil();
        x.polygon = v.shape() == Veil.Shape.POLYGON;
        x.radius = v.radius();
        if (x.polygon) x.points = v.points();
        x.x = v.baseX();
        x.y = v.baseY();
        x.rotation = v.baseRotation();
        x.opacity = v.baseOpacity();
        x.start = v.hasStart() ? v.startTime() : Float.NaN;
        x.end = v.hasEnd() ? v.endTime() : Float.NaN;
        read(v.xEvents(), x.xEv);
        read(v.yEvents(), x.yEv);
        read(v.rotationEvents(), x.rotEv);
        read(v.opacityEvents(), x.opacityEv);
        readGlitches(v.glitches(), x.glitches);
        readDistortions(v.distortions(), x.distortions);
        return x;
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
        if (laneCount > 0) b.append("  \"laneCount\": ").append(laneCount).append(",\n");
        b.append("  \"lanes\": [\n");
        for (int i = 0; i < lanes.size(); i++) {
            DLane l = lanes.get(i);
            b.append("    { \"id\": ").append(l.id).append(", \"width\": ").append(num(l.width))
                .append(", \"opacity\": ").append(num(l.opacity));
            b.append(", \"widthEvents\": ");
            events(b, l.widthEv, "Width");
            b.append(", \"opacityEvents\": ");
            events(b, l.opacityEv, "Opacity");
            if (l.insert != null) {
                // all five keys, the final width being the lane's width (ChartMapper.write does the same)
                b.append(", \"insert\": { \"startTime\": ").append(num(l.insert.start)).append(", \"endTime\": ")
                    .append(num(l.insert.end)).append(", \"initial\": ").append(num(l.insert.from))
                    .append(", \"final\": ").append(num(l.width)).append(", \"exponent\": ")
                    .append(num(l.insert.exp)).append(" }");
            }
            if (l.delete != null) {
                // all five keys, like ChartMapper.write (§3.7)
                b.append(", \"delete\": { \"startTime\": ").append(num(l.delete.start)).append(", \"endTime\": ")
                    .append(num(l.delete.end)).append(", \"initial\": ").append(num(l.delete.from))
                    .append(", \"final\": ").append(num(l.delete.to)).append(", \"exponent\": ")
                    .append(num(l.delete.exp)).append(" }");
            }
            b.append(i + 1 < lanes.size() ? " },\n" : " }\n");
        }
        b.append("  ],\n");
        b.append("  \"notes\": [\n");
        boolean first = true;
        for (DNote n : notes) {
            if (!first) b.append(",\n");
            first = false;
            note(b, n);
        }
        if (Float.isFinite(sentinelBeat) && !lanes.isEmpty()) {
            if (!first) b.append(",\n");
            b.append("    { \"type\": \"mine\", \"line\": \"lower\", \"lane\": ").append(lanes.get(0).id)
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
        b.append("\n}\n");
        return b.toString();
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
        b.append(", \"x\": ").append(num(v.x)).append(", \"y\": ").append(num(v.y));
        if (v.rotation != 0f) b.append(", \"rotation\": ").append(num(v.rotation));
        if (v.opacity != 1f) b.append(", \"opacity\": ").append(num(v.opacity));
        if (Float.isFinite(v.start)) b.append(", \"startTime\": ").append(num(v.start));
        if (Float.isFinite(v.end)) b.append(", \"endTime\": ").append(num(v.end));
        // the centre's events have no type of their own (the game writes none either)
        if (!v.xEv.isEmpty()) events(b.append(", \"xEvents\": "), v.xEv, null);
        if (!v.yEv.isEmpty()) events(b.append(", \"yEvents\": "), v.yEv, null);
        if (!v.rotEv.isEmpty()) events(b.append(", \"rotationEvents\": "), v.rotEv, "Rotation");
        if (!v.opacityEv.isEmpty()) events(b.append(", \"opacityEvents\": "), v.opacityEv, "Opacity");
        glitches(b, v.glitches);
        distortions(b, v.distortions);
        b.append(" }");
    }

    private static void note(StringBuilder b, DNote n) {
        b.append("    { \"type\": \"").append(n.type.json).append("\", \"line\": \"").append(n.line.json).append('"');
        if (n.inLane()) b.append(", \"lane\": ").append(n.lane);
        b.append(", \"judgement\": ").append(num(n.judgement));
        boolean sus = n.sustained();
        if (sus) {
            float dur = n.duration > 0f ? n.duration : 0.25f;
            b.append(", \"duration\": ").append(num(dur));
            b.append(", \"height\": ").append(num(Math.max(0f, n.height)));
        }
        b.append(", \"appearance\": ").append(num(appearanceOf(n)));
        // two finite numbers, only when not the origin (ChartMapper.write does the same)
        if (n.pivotX != 0f || n.pivotY != 0f) b.append(", \"pivot\": [").append(num(n.pivotX)).append(", ")
            .append(num(n.pivotY)).append(']');
        // only on a TIDE, a TIDE STREAM or their mines and only when moving (ChartFormatV5 does the same)
        if (!n.inLane() && n.waveSpeed != 0f) b.append(", \"waveSpeed\": ").append(num(n.waveSpeed));
        if (n.inLane() && n.offsetX != 0f) b.append(", \"offsetX\": ").append(num(n.offsetX));
        b.append(", \"MovementEvents\": ");
        events(b, n.move, "Movement", n);
        b.append(", \"RotationEvents\": ");
        events(b, n.rot, "Rotation");
        if (n.inLane() && !n.offEv.isEmpty()) {
            b.append(", \"offsetXEvents\": ");
            events(b, n.offEv, "OffsetX");
        }
        // the orbit replaces the pivot; its moves only with it (ChartFormatV5 does the same)
        if (n.inLane() && n.orbit) {
            b.append(", \"orbit\": [").append(num(n.orbitX)).append(", ").append(num(n.orbitY)).append(']');
            if (!n.orbXEv.isEmpty()) events(b.append(", \"orbitXEvents\": "), n.orbXEv, "OrbitX");
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
