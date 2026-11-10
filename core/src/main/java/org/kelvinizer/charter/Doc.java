package org.kelvinizer.charter;

import org.kelvinizer.elements.Chart;
import org.kelvinizer.elements.Chart7;
import org.kelvinizer.elements.ChartFormatV7;
import org.kelvinizer.elements.Fn;
import org.kelvinizer.elements.Fn3;
import org.kelvinizer.elements.JudgementLine;
import org.kelvinizer.elements.Key7;
import org.kelvinizer.elements.KeyKind;
import org.kelvinizer.elements.Lane7;
import org.kelvinizer.elements.NoteDistortion;
import org.kelvinizer.elements.NoteGlitch;
import org.kelvinizer.elements.NoteTrail;
import org.kelvinizer.elements.Seg;
import org.kelvinizer.elements.SongInfo;
import org.kelvinizer.elements.Volume7;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 正在编辑的谱面（谱面格式 v7「简单函数」）：歌曲信息（四个难度共用）加上这张谱的轨道、键、水幕水帘和镜头；撤销时整份复制。
 *
 * <p>The chart being edited, in chart format v7 (Kelvin 2026-11-09 18:44: every value a function made of simple
 * functions; lanes have only u v a S s F f, keys only h (and H, and the lanes they span), veils and curtains only V
 * and p, the camera only P). It holds the song values the four difficulties share (title, artist, illustrator, tempo
 * map, offset: {@code song.json}) and this chart's {@link Lane7 lanes}, {@link DKey keys}, {@link DVol veils and
 * curtains} and {@link #camera}, as the game's {@link Chart7} has them. Keys and volumes carry an editor uid so a
 * selection survives undo; lanes are known by their id. Undo keeps whole copies ({@link #copy}).
 */
public final class Doc {
    /** Where a new key starts its fall: the game's default approach, 1080 from its face. */
    public static final float SPAWN = 1080f;
    /** How many beats a new key falls. */
    public static final float APPROACH_BEATS = 2f;
    /** The default fall speed, units per beat (540). */
    public static final float APPROACH = SPAWN / APPROACH_BEATS;
    /** A lane's default width and the distance between neighbouring default lanes (240 wide, 40 apart). */
    public static final float LANE_W = 240f, LANE_STEP = 280f;

    public String title = "", artist = "", illustrator = "", level = "";
    public String charter = "", constant = "1";
    /** 迭代次数: one more each time the file is backed up before a save (editor block {@code "iterations"}). */
    public int iterations;
    /** Tempo segments, sorted by beat; never empty. The first one also covers earlier beats. */
    public final ArrayList<Tempo> bpms = new ArrayList<>();
    public float offsetMs;
    /** The lanes, in the file's order (left to right as the editor adds them). */
    public final ArrayList<Lane7> lanes = new ArrayList<>();
    /** The keys, sorted by judge beat ({@link #sortKeys}). */
    public final ArrayList<DKey> keys = new ArrayList<>();
    /** The veils (漩涡) and curtains (水帘), in the file's order. */
    public final ArrayList<DVol> vols = new ArrayList<>();
    /** The camera position P (it always looks at the origin). */
    public final Fn3 camera = new Fn3(0f, 0f, -Chart7.CAMERA_DIST);
    /** The camera's vertical field of view, degrees (default 11, used clamped to 1…150). */
    public final Fn fov = new Fn(11f);
    /** 轨道名字 (editor-only, Kelvin 2026-11-07): lanes not called by their id, by id. */
    public final TreeMap<Integer, String> names = new TreeMap<>();
    /** The editor block's other keys, kept as read. */
    public final LinkedHashMap<String, Object> extra = new LinkedHashMap<>();

    private static int nextUid = 1;

    // ================================================================== parts

    /** A tempo segment: from {@code beat} on, {@code bpm} beats per minute. */
    public static final class Tempo {
        public float beat, bpm;

        public Tempo(float beat, float bpm) {
            this.beat = beat;
            this.bpm = bpm;
        }
    }

    /** A key and its editor uid (kept by undo copies, so selections survive). */
    public static final class DKey {
        public final int uid;
        public final Key7 k;

        /** A new key (a new uid). */
        public DKey(Key7 k) {
            this(nextUid++, k);
        }

        private DKey(int uid, Key7 k) {
            this.uid = uid;
            this.k = k;
        }

        /** @return a copy with the same uid (undo) */
        public DKey copy() {
            return new DKey(uid, copyKey(k));
        }

        /** @return a copy that is a new key (paste) */
        public DKey dup() {
            return new DKey(copyKey(k));
        }

        /** @return the judge beat: where h's last segment ends (NaN without one) */
        public float judge() {
            return k.judgeBeat();
        }

        /** @return the beat it is over: a stream's or tide's end, else its judge beat */
        public float end() {
            return k.endBeat();
        }

        /** @return whether it sits in lane {@code id} or spans it */
        public boolean on(int id) {
            if (!k.kind.spans()) return k.lane == id;
            for (int l : k.lanes) if (l == id) return true;
            return false;
        }

        /** @return the lane it is drawn on in the timeline and picked by: its lane, a span's first */
        public int lane() {
            return k.kind.spans() && k.lanes.length > 0 ? k.lanes[0] : k.lane;
        }
    }

    /** A veil (漩涡) or curtain (水帘) and its editor uid. */
    public static final class DVol {
        public final int uid;
        public final Volume7 v;

        public DVol(Volume7 v) {
            this(nextUid++, v);
        }

        private DVol(int uid, Volume7 v) {
            this.uid = uid;
            this.v = v;
        }

        public DVol copy() {
            return new DVol(uid, copyVol(v));
        }

        public DVol dup() {
            return new DVol(copyVol(v));
        }
    }

    // ================================================================== copies

    /** @return a deep copy for undo: the same uids */
    public Doc copy() {
        Doc d = new Doc();
        d.title = title;
        d.artist = artist;
        d.illustrator = illustrator;
        d.level = level;
        d.charter = charter;
        d.constant = constant;
        d.iterations = iterations;
        for (Tempo t : bpms) d.bpms.add(new Tempo(t.beat, t.bpm));
        d.offsetMs = offsetMs;
        for (Lane7 l : lanes) d.lanes.add(copyLane(l));
        for (DKey k : keys) d.keys.add(k.copy());
        for (DVol v : vols) d.vols.add(v.copy());
        copyFn3(camera, d.camera);
        copyFn(fov, d.fov);
        d.names.putAll(names);
        d.extra.putAll(extra);
        return d;
    }

    /** Copies {@code src}'s constant and segments into {@code dst} (whose default stays). */
    public static void copyFn(Fn src, Fn dst) {
        dst.constant = src.constant;
        dst.segs.clear();
        for (Seg s : src.segs) dst.segs.add(new Seg(s.ti, s.tf, s.xi, s.xf, s.e));
    }

    public static void copyFn3(Fn3 src, Fn3 dst) {
        copyFn(src.x, dst.x);
        copyFn(src.y, dst.y);
        copyFn(src.z, dst.z);
    }

    public static Lane7 copyLane(Lane7 l) {
        Lane7 c = new Lane7(l.id, 0f);
        copyFn3(l.u, c.u);
        copyFn3(l.v, c.v);
        copyFn(l.w, c.w);
        copyFn(l.h, c.h);
        copyFn(l.S, c.S);
        copyFn(l.s, c.s);
        copyFn(l.F, c.F);
        copyFn(l.f, c.f);
        copyFn(l.o, c.o);
        return c;
    }

    public static Key7 copyKey(Key7 k) {
        Key7 c = new Key7();
        c.kind = k.kind;
        c.lane = k.lane;
        c.lanes = k.lanes.clone();
        c.sky = k.sky;
        c.fake = k.fake;
        copyFn(k.h, c.h);
        copyFn(k.H, c.H);
        copyFn(k.o, c.o);
        c.trails = listCopy(k.trails);              // 残影 · 故障 · 扭曲 (game 7.228): their segments are fixed values
        c.glitches = listCopy(k.glitches);
        c.distortions = listCopy(k.distortions);
        return c;
    }

    public static Volume7 copyVol(Volume7 v) {
        Volume7 c = new Volume7();
        c.curtain = v.curtain;
        c.ellipsoid = v.ellipsoid;
        copyFn3(v.V, c.V);
        copyFn3(v.p, c.p);
        copyFn3(v.o, c.o);
        copyFn(v.b, c.b);
        c.glitches = listCopy(v.glitches);
        c.distortions = listCopy(v.distortions);
        return c;
    }

    /** @return a list of its own holding {@code l}'s items (none for null) */
    static <T> List<T> listCopy(List<T> l) {
        return l != null ? new ArrayList<>(l) : new ArrayList<>();
    }

    /** @return every function of lane {@code l}: u x y z, v x y z, w, h, S, s, F, f, opacity o */
    public static Fn[] fns(Lane7 l) {
        return new Fn[] {l.u.x, l.u.y, l.u.z, l.v.x, l.v.y, l.v.z, l.w, l.h, l.S, l.s, l.F, l.f, l.o};
    }

    // ================================================================== functions

    /** Moves every segment of {@code f} by {@code by} beats. */
    public static void shift(Fn f, float by) {
        for (Seg s : f.segs) {
            s.ti += by;
            s.tf += by;
        }
    }

    /** Adds {@code by} to every value of {@code f}: its constant and both ends of each segment. */
    public static void offset(Fn f, float by) {
        f.constant += by;
        for (Seg s : f.segs) {
            s.xi += by;
            s.xf += by;
        }
    }

    private static final float EPS = 1e-4f;

    /**
     * Makes {@code f} reach {@code v} at beat {@code t} (a keyframe): without segments its constant; at a point (a
     * segment starting or ending at t) that point's value; inside a segment a split there; after a segment a new one
     * from where it ends (its value) to t; before every segment a new one from t to the first point.
     */
    public static void setAt(Fn f, float t, float v) {
        if (f.segs.isEmpty()) {
            f.constant = v;
            return;
        }
        boolean hit = false;
        for (Seg s : f.segs) {
            if (s.tf > s.ti) {
                if (Math.abs(s.ti - t) < EPS) {
                    s.xi = v;
                    hit = true;
                }
                if (Math.abs(s.tf - t) < EPS) {
                    s.xf = v;
                    hit = true;
                }
            } else if (Math.abs(s.ti - t) < EPS) {                    // a jump: its value from t on
                s.xf = v;
                hit = true;
            }
        }
        if (hit) return;
        int k = -1;                                                   // the segment in force at t
        for (int i = 0; i < f.segs.size(); i++) if (f.segs.get(i).ti <= t) k = i;
        if (k < 0) {
            Seg first = f.segs.get(0);
            f.segs.add(0, new Seg(t, first.ti, v, first.xi, 1f));
        } else {
            Seg s = f.segs.get(k);
            if (s.tf > t) {                                           // inside it: split there
                f.segs.add(k + 1, new Seg(t, s.tf, v, s.xf, s.e));
                s.tf = t;
                s.xf = v;
            } else {
                f.segs.add(k + 1, new Seg(Math.max(s.ti, s.tf), t, s.xf, v, 1f));
            }
        }
        f.sort();
    }

    /** Puts a point at beat {@code t} with the value {@code f} has there (nothing changes on screen). */
    public static void keyAt(Fn f, float t) {
        if (f.segs.isEmpty()) f.segs.add(new Seg(t, t, f.constant, f.constant, 1f));
        else setAt(f, t, f.at(t));
    }

    /** @return the number of segments of a group of functions */
    public static int segCount(Fn... fns) {
        int n = 0;
        for (Fn f : fns) n += f.segs.size();
        return n;
    }

    /** @return the last beat a segment of {@code f} reaches (−∞ with none) */
    public static float lastTf(Fn f) {
        float b = Float.NEGATIVE_INFINITY;
        for (Seg s : f.segs) b = Math.max(b, Math.max(s.ti, s.tf));
        return b;
    }

    // ================================================================== lookup

    public DKey byUid(int uid) {
        for (DKey k : keys) if (k.uid == uid) return k;
        return null;
    }

    public DVol volByUid(int uid) {
        for (DVol v : vols) if (v.uid == uid) return v;
        return null;
    }

    public Lane7 lane(int id) {
        for (Lane7 l : lanes) if (l.id == id) return l;
        return null;
    }

    /** @return lane {@code id}'s place in {@link #lanes}, −1 when there is none */
    public int laneIndex(int id) {
        for (int i = 0; i < lanes.size(); i++) if (lanes.get(i).id == id) return i;
        return -1;
    }

    /** @return what lane {@code id} is called: its name, else its id */
    public String laneLabel(int id) {
        String n = names.get(id);
        return n != null ? n : String.valueOf(id);
    }

    /** @return the lane other than {@code self} called {@code label} (by name or id), or null */
    public Lane7 laneCalled(String label, Lane7 self) {
        for (Lane7 l : lanes) if (l != self && laneLabel(l.id).equals(label)) return l;
        return null;
    }

    /** @return the keys in lane {@code id} or spanning it, in time order */
    public ArrayList<DKey> keysOn(int id) {
        ArrayList<DKey> out = new ArrayList<>();
        for (DKey k : keys) if (k.on(id)) out.add(k);
        return out;
    }

    /**
     * @return the keys that go with lane {@code id} when it moves or is copied: those in it and the waves and tides
     *         spanning only it
     */
    public ArrayList<DKey> boundTo(int id) {
        ArrayList<DKey> out = new ArrayList<>();
        for (DKey k : keys) {
            if (k.k.kind.spans() ? k.k.lanes.length == 1 && k.k.lanes[0] == id : k.k.lane == id) out.add(k);
        }
        return out;
    }

    // ================================================================== time

    /** @return the BPM in force at {@code beat}. */
    public float bpmAt(float beat) {
        Tempo cur = bpms.get(0);
        for (Tempo s : bpms) if (s.beat <= beat) cur = s;
        return cur.bpm;
    }

    public void sortBpms() {
        bpms.sort(Comparator.comparingDouble(s -> s.beat));
    }

    /** @return milliseconds from beat 0 to {@code beat} on {@link #bpms} (negative before it). */
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

    /** @return the last beat anything uses: a key's end, a segment's end, a tempo change (0 for an empty chart) */
    public float lastBeat() {
        float b = 0f;
        for (DKey k : keys) if (Float.isFinite(k.end())) b = Math.max(b, k.end());
        for (Lane7 l : lanes) for (Fn f : fns(l)) b = Math.max(b, lastTf(f));
        for (DVol v : vols) {
            for (Fn f : fns(v.v)) b = Math.max(b, lastTf(f));
        }
        b = Math.max(b, Math.max(lastTf(camera.x), Math.max(lastTf(camera.y), lastTf(camera.z))));
        b = Math.max(b, lastTf(fov));
        for (Tempo s : bpms) b = Math.max(b, s.beat);
        return b;
    }

    // ================================================================== keys

    /** Keeps {@link #keys} in judge-beat order (stable: keys at one beat keep their order). */
    public void sortKeys() {
        keys.sort(Comparator.comparingDouble(k -> {
            float j = k.judge();
            return Float.isNaN(j) ? Float.POSITIVE_INFINITY : j;
        }));
    }

    /**
     * @return a new key of {@code kind} in lane {@code lane} (a wave or tide spans it), judged at {@code beat}
     *         with the default fall, {@code len} beats long when it has a height
     */
    public static Key7 newKey(KeyKind kind, int lane, boolean sky, float beat, float len) {
        Key7 k = new Key7();
        k.kind = kind;
        k.lane = lane;
        k.lanes = kind.spans() ? new int[] {lane} : new int[0];
        k.sky = sky;
        defaultFall(k, beat);
        if (kind.hasHeight()) setLength(k, len);
        return k;
    }

    /** Gives {@code k} the default fall ending at {@code beat}: one segment over 2 beats, 1080 → 0. */
    public static void defaultFall(Key7 k, float beat) {
        k.h.segs.clear();
        k.h.constant = 0f;
        k.h.segs.add(new Seg(beat - APPROACH_BEATS, beat, SPAWN, 0f, 1f));
    }

    /**
     * Gives {@code k} a fall of {@code beats} beats from {@code from} to its face, eased by {@code e}, ending at its
     * judge beat (unchanged); 0 beats = no approach: it shows on its face at its judge beat.
     */
    public static void fall(Key7 k, float beats, float from, float e) {
        float j = k.judgeBeat();
        if (!Float.isFinite(j)) return;
        k.h.segs.clear();
        k.h.constant = 0f;
        if (beats > 0f) k.h.segs.add(new Seg(j - beats, j, from, 0f, e > 0f ? e : 1f));
        else k.h.segs.add(new Seg(j, j, 0f, 0f, 1f));
    }

    /** Moves key {@code k} (its h, H and opacity o, its 残影 · 故障 · 扭曲 ranges) by {@code by} beats. */
    public static void shiftKey(Key7 k, float by) {
        shift(k.h, by);
        shift(k.H, by);
        shift(k.o, by);
        if (by == 0f) return;
        for (int i = 0; i < k.trails.size(); i++) k.trails.set(i, trail(k.trails.get(i), by));
        for (int i = 0; i < k.glitches.size(); i++) k.glitches.set(i, glitch(k.glitches.get(i), by));
        for (int i = 0; i < k.distortions.size(); i++) k.distortions.set(i, distortion(k.distortions.get(i), by));
    }

    /** @return trail {@code t} over its range moved by {@code by} beats (no bound stays none) */
    static NoteTrail trail(NoteTrail t, float by) {
        return NoteTrail.of(t.length(), t.density(), t.alpha(), t.fade(), t.mode(), t.from() + by, t.to() + by);
    }

    /** @return glitch segment {@code x} moved by {@code by} beats */
    static NoteGlitch glitch(NoteGlitch x, float by) {
        return NoteGlitch.of(x.from() + by, x.to() + by, x.intensity(), x.intensityEnd(), x.exponent(), x.rate(),
            x.split(), x.slice(), x.flicker(), x.curve());
    }

    /** @return distortion segment {@code x} moved by {@code by} beats */
    static NoteDistortion distortion(NoteDistortion x, float by) {
        return NoteDistortion.of(x.from() + by, x.to() + by, x.intensity(), x.intensityEnd(), x.exponent(), x.curve());
    }

    /** Moves key {@code k} so it is judged at {@code beat} (no change without an h segment). */
    public static void setJudge(Key7 k, float beat) {
        float j = k.judgeBeat();
        if (Float.isFinite(j) && Float.isFinite(beat)) shiftKey(k, beat - j);
    }

    /** @return how many beats a stream or tide lasts after its judge beat (0 for the others or without H segments) */
    public static float length(Key7 k) {
        if (!k.kind.hasHeight() || k.H.isEmpty()) return 0f;
        return Math.max(0f, k.H.lastTf() - k.judgeBeat());
    }

    /**
     * Makes a stream or tide {@code len} beats long: H one segment from its judge beat, {@code 540·len} → 0 (held
     * before it, so it falls at full length as before); 0 = no height.
     */
    public static void setLength(Key7 k, float len) {
        k.H.segs.clear();
        k.H.constant = 0f;
        float j = k.judgeBeat();
        if (!(len > 0f) || !Float.isFinite(j)) return;
        k.H.segs.add(new Seg(j, j + len, APPROACH * len, 0f, 1f));
    }

    /** Changes a key's kind; a kind without a height loses H, one with a height gets {@code len} beats. */
    public static void setKind(Key7 k, KeyKind kind, float len) {
        boolean had = k.kind.hasHeight();
        if (kind.spans() && !k.kind.spans()) k.lanes = new int[] {k.lane};
        if (!kind.spans() && k.kind.spans() && k.lanes.length > 0) {
            k.lane = k.lanes[0];
            k.lanes = new int[0];
        }
        k.kind = kind;
        if (!kind.hasHeight()) {
            k.H.segs.clear();
            k.H.constant = 0f;
        } else if (!had) {
            setLength(k, len);
        }
    }

    // ================================================================== lanes

    /** @return an id no lane has: one more than the largest (1 for the first) */
    public int nextLaneId() {
        int id = 0;
        for (Lane7 l : lanes) id = Math.max(id, l.id);
        return id + 1;
    }

    /** @return the origin x of lane {@code l} at {@code beat} */
    public static float xAt(Lane7 l, float beat) {
        return l.v.x.at(beat);
    }

    /**
     * @return where a new lane goes at {@code beat}: one step right of the rightmost lane ({@code right}) or left of
     *         the leftmost, 140 / −140 when there is none
     */
    public float outerX(boolean right, float beat) {
        if (lanes.isEmpty()) return right ? LANE_STEP / 2f : -LANE_STEP / 2f;
        float x = right ? Float.NEGATIVE_INFINITY : Float.POSITIVE_INFINITY;
        for (Lane7 l : lanes) x = right ? Math.max(x, xAt(l, beat)) : Math.min(x, xAt(l, beat));
        return right ? x + LANE_STEP : x - LANE_STEP;
    }

    /** @return whether a new lane goes on the right: that side has no more lanes than the left */
    public boolean addsRight(float beat) {
        int left = 0, right = 0;
        for (Lane7 l : lanes) {
            if (xAt(l, beat) < 0f) left++;
            else right++;
        }
        return right <= left;
    }

    /**
     * Adds a lane at the outer end (right or left, whichever has fewer) that comes in at {@code beat} (NaN: there
     * from the start). @return it
     */
    public Lane7 addLane(float beat) {
        float at = Float.isFinite(beat) ? beat : 0f;
        boolean right = addsRight(at);
        Lane7 l = new Lane7(nextLaneId(), outerX(right, at));
        if (Float.isFinite(beat)) setAppear(l, beat, 0f, 1f);
        if (right) lanes.add(l);
        else lanes.add(0, l);
        return l;
    }

    /** Removes lane {@code id}, its name and its keys; waves and tides spanning it lose it (gone with no lane left). */
    public void removeLane(int id) {
        Lane7 l = lane(id);
        if (l == null) return;
        lanes.remove(l);
        names.remove(id);
        keys.removeIf(k -> {
            if (!k.k.kind.spans()) return k.k.lane == id;
            int n = 0;
            for (int x : k.k.lanes) if (x != id) n++;
            if (n == k.k.lanes.length) return false;
            int[] rest = new int[n];
            n = 0;
            for (int x : k.k.lanes) if (x != id) rest[n++] = x;
            k.k.lanes = rest;
            if (n > 0) k.k.lane = rest[0];
            return n == 0;
        });
    }

    /** Moves lane {@code l}'s functions and the keys that go with it ({@link #boundTo}) by {@code by} beats. */
    public void moveLane(Lane7 l, float by) {
        for (Fn f : fns(l)) shift(f, by);
        for (DKey k : boundTo(l.id)) shiftKey(k.k, by);
        sortKeys();
    }

    // ------------------------------------------------------------------ 出现 / 消失 (written as w segments)

    /** @return {@code f}'s 出现 segment: its first segment when it starts from 0, else null */
    public static Seg appearSeg(Fn f) {
        if (f.segs.isEmpty()) return null;
        Seg s = f.segs.get(0);
        return s.xi == 0f && s.xf != 0f ? s : null;
    }

    /** @return {@code f}'s 消失 segment: its last segment when it ends at 0 (not its 出现), else null */
    public static Seg vanishSeg(Fn f) {
        if (f.segs.isEmpty()) return null;
        Seg s = f.segs.get(f.segs.size() - 1);
        return s.xf == 0f && s.xi != 0f ? s : null;
    }

    /** @return the value {@code f} has when its object is fully there (between its 出现 and its 消失) */
    public static float fullValue(Fn f) {
        Seg a = appearSeg(f);
        if (a != null) return a.xf;
        Seg v = vanishSeg(f);
        if (v != null) return v.xi;
        if (f.segs.isEmpty()) return f.constant;
        return f.at(f.firstTi());
    }

    /**
     * {@code f}'s object comes in at {@code beat}, growing over {@code len} beats (0 = at once) with ease {@code e}:
     * its first segment from 0 to its full value ({@code dflt} when that is 0), replacing an earlier 出现.
     */
    public static void setAppear(Fn f, float beat, float len, float e, float dflt) {
        float w = fullValue(f);
        if (!(w != 0f)) w = dflt;
        Seg old = appearSeg(f);
        if (old != null) f.segs.remove(old);
        f.segs.add(0, new Seg(beat, beat + Math.max(0f, len), 0f, w, e > 0f ? e : 1f));
        f.sort();
    }

    /** {@code f}'s object is there from the start: its 出现 segment goes (its full value stays). */
    public static void clearAppear(Fn f) {
        Seg s = appearSeg(f);
        if (s == null) return;
        f.segs.remove(s);
        if (f.segs.isEmpty()) f.constant = s.xf;
    }

    /** {@code f}'s object goes from {@code beat} over {@code len} beats (0 = at once): its last segment to 0. */
    public static void setVanish(Fn f, float beat, float len, float e, float dflt) {
        Seg old = vanishSeg(f);
        if (old != null) f.segs.remove(old);
        float w = f.segs.isEmpty() ? f.constant : f.at(beat);
        if (!(w != 0f)) w = fullValue(f);
        if (!(w != 0f)) w = dflt;
        f.segs.add(new Seg(beat, beat + Math.max(0f, len), w, 0f, e > 0f ? e : 1f));
        f.sort();
    }

    /** {@code f}'s object stays: its 消失 segment goes. */
    public static void clearVanish(Fn f) {
        Seg s = vanishSeg(f);
        if (s == null) return;
        f.segs.remove(s);
        if (f.segs.isEmpty()) f.constant = s.xi;
    }

    /** @return lane {@code l}'s 出现 segment (its width from 0), else null */
    public static Seg appearSeg(Lane7 l) {
        return appearSeg(l.w);
    }

    /** @return lane {@code l}'s 消失 segment (its width to 0), else null */
    public static Seg vanishSeg(Lane7 l) {
        return vanishSeg(l.w);
    }

    /** @return the beat lane {@code l} comes in, NaN when it is there from the start */
    public static float appearBeat(Lane7 l) {
        Seg s = appearSeg(l);
        return s != null ? s.ti : Float.NaN;
    }

    /** @return the beat lane {@code l} starts to go, NaN when it stays */
    public static float vanishBeat(Lane7 l) {
        Seg s = vanishSeg(l);
        return s != null ? s.ti : Float.NaN;
    }

    /** Lane {@code l} comes in at {@code beat}, its width growing from 0 over {@code len} beats (0 = at once). */
    public static void setAppear(Lane7 l, float beat, float len, float e) {
        setAppear(l.w, beat, len, e, LANE_W);
    }

    /** Lane {@code l} is there from the start: its 出现 segment goes (its width stays). */
    public static void clearAppear(Lane7 l) {
        clearAppear(l.w);
    }

    /** Lane {@code l} goes from {@code beat} over {@code len} beats (0 = at once): its last w segment to 0. */
    public static void setVanish(Lane7 l, float beat, float len, float e) {
        setVanish(l.w, beat, len, e, LANE_W);
    }

    /** Lane {@code l} stays: its 消失 segment goes. */
    public static void clearVanish(Lane7 l) {
        clearVanish(l.w);
    }

    // ------------------------------------------------------------------ veils and curtains

    /** The size a new veil or curtain has, each way. */
    public static final float VOL_SIZE = 480f;

    /** @return every function of volume {@code v}: V w h d, p x y z, o x y z, a curtain's blur b */
    public static Fn[] fns(Volume7 v) {
        return new Fn[] {v.V.x, v.V.y, v.V.z, v.p.x, v.p.y, v.p.z, v.o.x, v.o.y, v.o.z, v.b};
    }

    /** @return the beat volume {@code v} comes in (its size from 0), NaN when it is there from the start */
    public static float appearBeat(Volume7 v) {
        Seg s = appearSeg(v.V.x);
        return s != null && appearSeg(v.V.y) != null && appearSeg(v.V.z) != null ? s.ti : Float.NaN;
    }

    /** @return the beat volume {@code v} starts to go (its size to 0), NaN when it stays */
    public static float vanishBeat(Volume7 v) {
        Seg s = vanishSeg(v.V.x);
        return s != null && vanishSeg(v.V.y) != null && vanishSeg(v.V.z) != null ? s.ti : Float.NaN;
    }

    /** Volume {@code v} comes in at {@code beat}, its size growing from 0 over {@code len} beats (0 = at once). */
    public static void setAppear(Volume7 v, float beat, float len) {
        for (Fn f : new Fn[] {v.V.x, v.V.y, v.V.z}) setAppear(f, beat, len, 1f, VOL_SIZE);
    }

    /** Volume {@code v} goes from {@code beat} over {@code len} beats (0 = at once). */
    public static void setVanish(Volume7 v, float beat, float len) {
        for (Fn f : new Fn[] {v.V.x, v.V.y, v.V.z}) setVanish(f, beat, len, 1f, VOL_SIZE);
    }

    /** Volume {@code v} is there from the start. */
    public static void clearAppear(Volume7 v) {
        for (Fn f : new Fn[] {v.V.x, v.V.y, v.V.z}) clearAppear(f);
    }

    /** Volume {@code v} stays. */
    public static void clearVanish(Volume7 v) {
        for (Fn f : new Fn[] {v.V.x, v.V.y, v.V.z}) clearVanish(f);
    }

    /**
     * @return a new veil ({@code curtain} false) or curtain centred at (x, y, z), there from {@code beat} for
     *         {@code len} beats
     */
    public static Volume7 newVolume(boolean curtain, float x, float y, float z, float beat, float len) {
        Volume7 v = new Volume7();
        v.curtain = curtain;
        v.p.x.constant = x;
        v.p.y.constant = y;
        v.p.z.constant = z;
        setAppear(v, beat, 0f);
        setVanish(v, beat + len, 0f);
        return v;
    }

    // ------------------------------------------------------------------ mirroring

    /** @return the lane whose origin x at {@code beat} is nearest {@code x}, or null without lanes */
    public Lane7 laneNear(float x, float beat) {
        Lane7 best = null;
        float bd = Float.POSITIVE_INFINITY;
        for (Lane7 l : lanes) {
            float d = Math.abs(xAt(l, beat) - x);
            if (d < bd) {
                bd = d;
                best = l;
            }
        }
        return best;
    }

    /** @return the lane left ↔ right of lane {@code id} at {@code beat}: the one nearest −x (itself when none) */
    public int mirrorLane(int id, float beat) {
        Lane7 l = lane(id);
        if (l == null) return id;
        Lane7 m = laneNear(-xAt(l, beat), beat);
        return m != null ? m.id : id;
    }

    /** @return where the last key that goes with lane {@code id} ends, NaN when none does */
    public float keysEnd(int id) {
        float b = Float.NaN;
        for (DKey k : keysOn(id)) if (!(b >= k.end())) b = k.end();
        return b;
    }

    // ================================================================== reading

    /** A new chart: the six default lanes, no keys, the song's tempo. */
    public static Doc blank(String title, String artist, String illustrator, String level, float bpm, float offsetMs) {
        Doc d = new Doc();
        d.title = nz(title);
        d.artist = nz(artist);
        d.illustrator = nz(illustrator);
        d.level = nz(level);
        d.bpms.add(new Tempo(0f, bpm > 0f ? bpm : 120f));
        d.offsetMs = offsetMs;
        d.lanes.addAll(Chart7.empty().lanes);
        return d;
    }

    /**
     * @param song  the song's values ({@code song.json}); null = none (120 BPM, no offset)
     * @return the document of chart {@code c} (taken over, not copied)
     */
    public static Doc of(Chart7 c, SongInfo song, String level) {
        Doc d = new Doc();
        d.level = nz(level);
        if (song != null) {
            d.title = nz(song.title);
            d.artist = nz(song.artist);
            d.illustrator = nz(song.illustrator);
            d.offsetMs = Float.isFinite(song.offsetMs) ? song.offsetMs : 0f;
            if (song.bpmList != null) {
                for (float[] e : song.bpmList) if (e[1] > 0f && Float.isFinite(e[0])) d.bpms.add(new Tempo(e[0], e[1]));
            }
            d.sortBpms();
            if (d.bpms.isEmpty() || d.bpms.get(0).beat > 0f) d.bpms.add(0, new Tempo(0f, song.bpm > 0f ? song.bpm
                : d.bpms.isEmpty() ? 120f : d.bpms.get(0).bpm));
        } else {
            d.bpms.add(new Tempo(0f, 120f));
        }
        d.charter = nz(c.charter);
        d.constant = c.constant != null && !c.constant.trim().isEmpty() ? c.constant.trim() : "1";
        d.lanes.addAll(c.lanes);
        for (Key7 k : c.keys) d.keys.add(new DKey(k));
        d.sortKeys();
        for (Volume7 v : c.volumes) d.vols.add(new DVol(v));
        copyFn3(c.camera, d.camera);
        copyFn(c.fov, d.fov);
        if (c.editor != null) {
            for (Map.Entry<String, Object> e : c.editor.entrySet()) {
                if ("iterations".equals(e.getKey())) {
                    if (e.getValue() instanceof Number) d.iterations = ((Number) e.getValue()).intValue();
                } else if ("laneNames".equals(e.getKey()) && e.getValue() instanceof Map) {
                    for (Map.Entry<?, ?> n : ((Map<?, ?>) e.getValue()).entrySet()) {
                        try {
                            int id = Integer.parseInt(String.valueOf(n.getKey()).trim());
                            String name = String.valueOf(n.getValue()).trim();
                            if (!name.isEmpty() && d.lane(id) != null) d.names.put(id, name);
                        } catch (NumberFormatException ignored) {
                            // not a lane id
                        }
                    }
                } else {
                    d.extra.put(e.getKey(), e.getValue());
                }
            }
        }
        return d;
    }

    // ================================================================== writing

    /** @return the chart as the game's model: copies of everything, the editor block with names and iterations */
    /** @return a chart with only this document's song values (title … tempo, offset), for the game's builder */
    public Chart songChart() {
        int n = bpms.size();
        float[] beats = new float[n], vals = new float[n];
        for (int i = 0; i < n; i++) {
            beats[i] = bpms.get(i).beat;
            vals[i] = bpms.get(i).bpm;
        }
        return new Chart(title, artist, illustrator, charter, level, constant, n > 0 ? vals[0] : 120f, offsetMs,
            new ArrayList<>(), JudgementLine.defaults(), new ArrayList<>(), beats, vals);
    }

    public Chart7 toChart() {
        Chart7 c = new Chart7();
        c.charter = charter.isEmpty() ? null : charter;
        c.constant = constant;
        for (Lane7 l : lanes) c.lanes.add(copyLane(l));
        for (DKey k : keys) c.keys.add(copyKey(k.k));
        for (DVol v : vols) c.volumes.add(copyVol(v.v));
        copyFn3(camera, c.camera);
        copyFn(fov, c.fov);
        LinkedHashMap<String, Object> ed = new LinkedHashMap<>();
        ed.put("iterations", (long) iterations);
        if (!names.isEmpty()) {
            LinkedHashMap<String, Object> m = new LinkedHashMap<>();
            for (Map.Entry<Integer, String> e : names.entrySet()) {
                if (lane(e.getKey()) != null) m.put(String.valueOf(e.getKey()), e.getValue());
            }
            if (!m.isEmpty()) ed.put("laneNames", m);
        }
        ed.putAll(extra);
        c.editor = ed;
        return c;
    }

    /** @return the chart file's text (format v7, {@link ChartFormatV7}) */
    public String toJson() {
        ByteArrayOutputStream b = new ByteArrayOutputStream(1 << 14);
        try {
            ChartFormatV7.write(toChart(), b);
        } catch (IOException e) {
            throw new IllegalStateException(e); // a byte stream does not throw
        }
        return new String(b.toByteArray(), StandardCharsets.UTF_8);
    }

    /** @return the song values for {@code song.json} */
    public SongInfo songInfo() {
        SongInfo s = new SongInfo();
        s.title = title;
        s.artist = artist;
        s.illustrator = illustrator;
        s.bpm = bpms.get(0).bpm;
        if (bpms.size() > 1) {
            s.bpmList = new ArrayList<>();
            for (Tempo t : bpms) s.bpmList.add(new float[] {t.beat, t.bpm});
        }
        s.hasOffset = offsetMs != 0f;
        s.offsetMs = offsetMs;
        return s;
    }

    /** @return the chart text's {@code "iterations"} (0 when it has none) */
    public static int iterationsIn(String json) {
        java.util.regex.Matcher m = ITERATIONS.matcher(json);
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    private static final java.util.regex.Pattern ITERATIONS =
        java.util.regex.Pattern.compile("\"iterations\"\\s*:\\s*(\\d{1,9})");

    /** @return the chart file's {@code "iterations"} (0 when it is missing, unreadable or has none) */
    public static int iterationsIn(com.badlogic.gdx.files.FileHandle f) {
        try {
            return f.exists() ? iterationsIn(f.readString("UTF-8")) : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    /** @return a float as short text: whole numbers without a point */
    public static String num(float f) {
        if (!Float.isFinite(f)) f = 0f;
        if (f == Math.rint(f) && Math.abs(f) < 1e9f) return Long.toString((long) f);
        return Float.toString(f);
    }

    /** @return {@code s} as a JSON string literal */
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

    private static String nz(String s) {
        return s != null ? s : "";
    }

}
