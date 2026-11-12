package org.kelvinizer.charter;

import org.kelvinizer.elements.Fn;
import org.kelvinizer.elements.Group7;
import org.kelvinizer.elements.Key7;
import org.kelvinizer.elements.Lane7;
import org.kelvinizer.elements.Seg;
import org.kelvinizer.elements.Volume7;

import java.util.ArrayList;
import java.util.Arrays;

/**
 * 关键帧（Kelvin 2026-11-11 16:39「基本可以」）：几个一起改的数（时间轴上的一行，或者「◇+ 关键帧」管的那些），在一拍加点、去点、挪点、改一段的缓动。
 * Keyframes on the v7 segment functions (the chart format does not change): a {@link Group} is numbers edited
 * together, a row of the layered timeline (原点 v = its x, y and z) or what the 关键帧 button keys. A keyframe is where
 * a segment starts or ends. The 出现 / 消失 segments of a lane's width and of a volume's size are its life (the bar of
 * its layer), not keyframes, and a key's judge beat (where its h ends) never moves or goes here.
 */
final class Keyframes {
    /** Beats closer than this are the same keyframe. */
    static final float EPS = 1e-3f;

    /** Numbers edited together. */
    static final class Group {
        final String name;
        final Fn[] fns;
        /** Each number's id in the inspector and the curve window ("l3-4", "k-h", "cam-0", "v12-3"). */
        final String[] ids;
        /** The numbers whose 出现 / 消失 segments are a life, not keyframes (a lane's w, a volume's V). */
        final boolean[] life;
        /** A key's h: its last keyframe is the judge beat. */
        final boolean judge;

        Group(String name, Fn[] fns, String[] ids, boolean[] life, boolean judge) {
            this.name = name;
            this.fns = fns;
            this.ids = ids;
            this.life = life;
            this.judge = judge;
        }
    }

    private Keyframes() {
    }

    // ================================================================== the groups of a thing

    /** A lane's rows, in the timeline's order; the first three are its position (the 关键帧 button). */
    static final String[] LANE_ROWS = {"原点 v", "下落向量 u", "宽长 a", "天面高度 S", "天面延申 s", "底面高度 F", "底面延申 f",
        "透明度", "法向量 n", "水雷", "轨道本身"};
    /** Each row's numbers by their place in {@link Doc#fns(Lane7)}. */
    private static final int[][] LANE_FNS = {{3, 4, 5}, {0, 1, 2}, {6, 7}, {8}, {9}, {10}, {11}, {12}, {13, 14, 15},
        {16}, {17}};
    static final String[] VOL_ROWS = {"中心", "大小", "朝向", "模糊"};
    private static final int[][] VOL_FNS = {{3, 4, 5}, {0, 1, 2}, {6, 7, 8}, {9}};

    /** @return lane l's groups: 原点 v, 下落向量 u, 宽长 a (its width's 出现 / 消失 are its life), S, s, F, f, 透明度, n, 水雷, 轨道本身 */
    static Group[] lane(Lane7 l) {
        Fn[] all = Doc.fns(l);
        Group[] out = new Group[LANE_ROWS.length];
        for (int r = 0; r < out.length; r++) {
            int[] ix = LANE_FNS[r];
            Fn[] fs = new Fn[ix.length];
            String[] ids = new String[ix.length];
            boolean[] life = new boolean[ix.length];
            for (int i = 0; i < ix.length; i++) {
                fs[i] = all[ix[i]];
                ids[i] = "l" + l.id + "-" + ix[i];
                life[i] = ix[i] == 6;
            }
            out[r] = new Group(LANE_ROWS[r], fs, ids, life, false);
        }
        return out;
    }

    static final String[] GROUP_ROWS = {"位置", "旋转", "缩放", "透明度"};
    private static final int[][] GROUP_FNS = {{0, 1, 2}, {3, 4, 5}, {6}, {7}};

    /** @return a group's rows (组, chart 2.1): 位置, 旋转, 缩放 (what the 关键帧 button keys), 透明度 */
    static Group[] group(Group7 g) {
        Fn[] all = Doc.fns(g);
        Group[] out = new Group[GROUP_ROWS.length];
        for (int r = 0; r < out.length; r++) {
            int[] ix = GROUP_FNS[r];
            Fn[] fs = new Fn[ix.length];
            String[] ids = new String[ix.length];
            for (int i = 0; i < ix.length; i++) {
                fs[i] = all[ix[i]];
                ids[i] = "g" + g.id + "-" + ix[i];
            }
            out[r] = new Group(GROUP_ROWS[r], fs, ids, new boolean[ix.length], false);
        }
        return out;
    }

    /** @return the camera's groups: 镜头 P (x, y, z) and 视角 */
    static Group[] camera(Doc d) {
        return new Group[]{new Group("镜头 P", new Fn[]{d.camera.x, d.camera.y, d.camera.z},
            new String[]{"cam-0", "cam-1", "cam-2"}, new boolean[3], false),
            new Group("视角", new Fn[]{d.fov}, new String[]{"cam-3"}, new boolean[1], false)};
    }

    /** @return a veil's or curtain's groups: 中心, 大小 (its 出现 / 消失 are its life), 朝向 and a curtain's 模糊 */
    static Group[] volume(Doc.DVol v) {
        Fn[] all = Doc.fns(v.v);
        int n = v.v.curtain ? VOL_ROWS.length : VOL_ROWS.length - 1;
        Group[] out = new Group[n];
        for (int r = 0; r < n; r++) {
            int[] ix = VOL_FNS[r];
            Fn[] fs = new Fn[ix.length];
            String[] ids = new String[ix.length];
            boolean[] life = new boolean[ix.length];
            for (int i = 0; i < ix.length; i++) {
                fs[i] = all[ix[i]];
                ids[i] = "v" + v.uid + "-" + ix[i];
                life[i] = r == 1;
            }
            out[r] = new Group(VOL_ROWS[r], fs, ids, life, false);
        }
        return out;
    }

    /** @return a key's groups: 位置 h (its last keyframe is the judge beat), a long key's 物件高度 H, 透明度 */
    static Group[] key(Key7 k) {
        Group h = new Group("位置 h", new Fn[]{k.h}, new String[]{"k-h"}, new boolean[1], true);
        Group o = new Group("透明度", new Fn[]{k.o}, new String[]{"k-o"}, new boolean[1], false);
        if (!k.kind.hasHeight()) return new Group[]{h, o};
        return new Group[]{h, new Group("物件高度 H", new Fn[]{k.H}, new String[]{"k-H"}, new boolean[1], false), o};
    }

    /**
     * What the 关键帧 button keys (SPEC §1): a lane's 原点 v, 下落向量 u and 宽长 a; the camera's P and 视角; a veil's or
     * curtain's 中心 and 大小; a key's h.
     */
    static Group[] position(Group[] all, int n) {
        return Arrays.copyOf(all, Math.min(n, all.length));
    }

    // ================================================================== reading

    /** @return whether segment s of the group's number i is part of its life (its 出现 or 消失) */
    static boolean isLife(Group g, int i, Seg s) {
        return g.life[i] && (s == Doc.appearSeg(g.fns[i]) || s == Doc.vanishSeg(g.fns[i]));
    }

    /** @return whether f has a segment starting or ending at beat t (the inspector's 「◆ 播放头」) */
    static boolean at(Fn f, float t) {
        for (Seg s : f.segs) {
            if (Math.abs(s.ti - t) < EPS || s.tf > s.ti + EPS && Math.abs(s.tf - t) < EPS) return true;
        }
        return false;
    }

    /** @return whether one of the groups has a keyframe at beat t */
    static boolean on(Group[] gs, float t) {
        for (Group g : gs) if (has(g, t)) return true;
        return false;
    }

    /** @return whether the group has a keyframe at beat t */
    static boolean has(Group g, float t) {
        for (int i = 0; i < g.fns.length; i++) {
            for (Seg s : g.fns[i].segs) {
                if (isLife(g, i, s)) continue;
                if (Math.abs(s.ti - t) < EPS || s.tf > s.ti + EPS && Math.abs(s.tf - t) < EPS) return true;
            }
        }
        return false;
    }

    /** @return the groups' keyframes in order, each once */
    static float[] points(Group... gs) {
        return points(false, gs);
    }

    /** @return the groups' keyframes in order, each once; with {@code life} also the ends of their 出现 / 消失 */
    static float[] points(boolean life, Group... gs) {
        float[] b = new float[16];
        int n = 0;
        for (Group g : gs) {
            for (int i = 0; i < g.fns.length; i++) {
                for (Seg s : g.fns[i].segs) {
                    if (!life && isLife(g, i, s)) continue;
                    if (n + 2 > b.length) b = Arrays.copyOf(b, b.length * 2);
                    b[n++] = s.ti;
                    if (s.tf > s.ti + EPS) b[n++] = s.tf;
                }
            }
        }
        Arrays.sort(b, 0, n);
        int m = 0;
        for (int i = 0; i < n; i++) if (m == 0 || b[i] - b[m - 1] >= EPS) b[m++] = b[i];
        return Arrays.copyOf(b, m);
    }

    /** @return whether a segment of the group goes all the way from beat a to beat b (a bar of its row) */
    static boolean covered(Group g, float a, float b) {
        for (int i = 0; i < g.fns.length; i++) {
            for (Seg s : g.fns[i].segs) {
                if (!isLife(g, i, s) && s.tf > s.ti + EPS && s.ti <= a + EPS && s.tf >= b - EPS) return true;
            }
        }
        return false;
    }

    /** @return the ease of the group's first segment over a..b, NaN when none */
    static float easeOf(Group g, float a, float b) {
        for (int i = 0; i < g.fns.length; i++) {
            for (Seg s : g.fns[i].segs) {
                if (!isLife(g, i, s) && s.tf > s.ti + EPS && s.ti < b - EPS && s.tf > a + EPS) return s.e;
            }
        }
        return Float.NaN;
    }

    /** @return the group's judge beat (a key's h: where it ends), NaN for other groups */
    static float judge(Group g) {
        return g.judge && !g.fns[0].segs.isEmpty() ? g.fns[0].lastTf() : Float.NaN;
    }

    /** @return whether {@link #add} would change something at beat t */
    static boolean canAdd(Group g, float t) {
        for (int i = 0; i < g.fns.length; i++) if (addable(g, i, t)) return true;
        return false;
    }

    /** @return whether {@link #remove} would change something at beat t (a key's judge beat stays) */
    static boolean canRemove(Group g, float t) {
        return has(g, t) && !(g.judge && Math.abs(t - judge(g)) < EPS);
    }

    // ================================================================== changing

    /**
     * Puts a keyframe at beat t on each number of the group that can take one there ({@link Doc#keyAt}: its value
     * there stays): not over a life's 出现 or 消失, not after a key's judge beat. @return whether anything changed
     */
    static boolean add(Group g, float t) {
        boolean did = false;
        for (int i = 0; i < g.fns.length; i++) {
            if (!addable(g, i, t)) continue;
            Fn f = g.fns[i];
            if (g.life[i] && nextToLife(g, i, t)) {                     // a point, not a hold from the 出现 (no ◆ there)
                float v = f.at(t);
                f.segs.add(new Seg(t, t, v, v, 1f));
                f.sort();
            } else Doc.keyAt(f, t);
            did = true;
        }
        return did;
    }

    /** @return whether the segment in force at t (before them all: the first) is number i's 出现 or 消失 */
    private static boolean nextToLife(Group g, int i, float t) {
        Fn f = g.fns[i];
        Seg in = null;
        for (Seg s : f.segs) if (s.ti <= t) in = s;
        if (in == null && !f.segs.isEmpty()) in = f.segs.get(0);
        return in != null && isLife(g, i, in);
    }

    private static boolean addable(Group g, int i, float t) {
        Fn f = g.fns[i];
        if (g.life[i]) {
            Seg a = Doc.appearSeg(f), v = Doc.vanishSeg(f);
            if (a != null && t < a.tf - EPS || v != null && t > v.ti + EPS) return false;
        }
        if (g.judge && !f.segs.isEmpty() && t > f.lastTf() + EPS) return false;
        return !at(f, t);
    }

    /**
     * Takes the keyframe at beat t off each number of the group (SPEC §1 「◆− 关键帧」): the segments before and after
     * it become one; at the first or last one its segment goes and its other end stays a keyframe. A key's judge beat
     * stays. @return whether anything changed
     */
    static boolean remove(Group g, float t) {
        boolean did = false;
        for (int i = 0; i < g.fns.length; i++) {
            if (g.judge && Math.abs(t - judge(g)) < EPS) continue;
            did |= drop(g, i, t);
        }
        return did;
    }

    private static boolean drop(Group g, int i, float t) {
        Fn f = g.fns[i];
        Seg a = null, b = null;
        ArrayList<Seg> jumps = new ArrayList<>();
        for (Seg s : f.segs) {
            if (isLife(g, i, s)) continue;
            boolean len = s.tf > s.ti + EPS;
            if (len && Math.abs(s.tf - t) < EPS) a = s;
            if (Math.abs(s.ti - t) < EPS) {
                if (len) b = s;
                else jumps.add(s);
            }
        }
        if (a == null && b == null && jumps.isEmpty()) return false;
        float hold = jumps.isEmpty() ? f.at(t) : jumps.get(jumps.size() - 1).xf;
        f.segs.removeAll(jumps);
        if (a != null && b != null) {
            a.tf = b.tf;
            a.xf = b.xf;
            f.segs.remove(b);
        } else if (a != null) {
            f.segs.remove(a);
            lone(f, a.ti, a.xi);
            hold = a.xi;
        } else if (b != null) {
            f.segs.remove(b);
            lone(f, b.tf, b.xf);
            hold = b.xf;
        }
        if (f.segs.isEmpty()) f.constant = hold;
        f.sort();
        return true;
    }

    /** Keeps a keyframe at beat t with value v when no segment of f starts or ends there any more. */
    private static void lone(Fn f, float t, float v) {
        if (!at(f, t)) f.segs.add(new Seg(t, t, v, v, 1f));
    }

    /**
     * Moves the group's keyframe {@code from} to beat {@code to} (dragging a ◆): the end of the segment before it and
     * the start of the one after, on each of its numbers; never a key's judge beat. The caller keeps {@code to}
     * between the neighbouring keyframes.
     */
    static void retime(Group g, float from, float to) {
        for (int i = 0; i < g.fns.length; i++) {
            Fn f = g.fns[i];
            if (g.judge && Math.abs(from - judge(g)) < EPS) continue;
            boolean moved = false;
            for (Seg s : f.segs) {
                if (isLife(g, i, s)) continue;
                if (Math.abs(s.ti - from) < EPS) {
                    s.ti = to;
                    moved = true;
                }
                if (Math.abs(s.tf - from) < EPS) {
                    s.tf = to;
                    moved = true;
                }
            }
            if (moved) f.sort();
        }
    }

    /** Sets the ease of the group's segments over beats a..b (the bar picked in its row) to e. */
    static void ease(Group g, float a, float b, float e) {
        for (int i = 0; i < g.fns.length; i++) {
            for (Seg s : g.fns[i].segs) {
                if (!isLife(g, i, s) && s.tf > s.ti + EPS && s.ti < b - EPS && s.tf > a + EPS) s.e = e;
            }
        }
    }

    /** @return whether one of the groups has a keyframe */
    static boolean any(Group[] gs) {
        for (Group g : gs) if (points(g).length > 0) return true;
        return false;
    }

    /** @return ① … ⑳ for i = 0 … 19, then (21), (22) … */
    static String circled(int i) {
        return i < 20 ? String.valueOf((char) ('①' + i)) : "(" + (i + 1) + ")";
    }

    /** Lane l's 底面 centre at beat t into out (world), via its frame; lf is scratch of {@link Scene#LF}. */
    static void laneSpot(Lane7 l, float t, float[] lf, double[] out) {
        Scene.laneFrame(l, t, lf);
        Scene.at(lf, 0, lf[Scene.GND], 0, out, 0);
    }

    /** Volume v's centre at beat t into out. */
    static void volSpot(Volume7 v, float t, double[] out) {
        out[0] = v.p.x.at(t);
        out[1] = v.p.y.at(t);
        out[2] = v.p.z.at(t);
    }
}
