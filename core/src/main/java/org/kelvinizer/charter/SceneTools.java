package org.kelvinizer.charter;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Cursor;
import org.kelvinizer.elements.ChartFormatV7;
import org.kelvinizer.elements.Fn;
import org.kelvinizer.elements.Group7;
import org.kelvinizer.elements.Lane7;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

/**
 * 场景页的工具：方块、阵列、镜像、复制，选中的组或轨道上的手柄，预览里的网格，保存和导入模型。
 *
 * <p>The 场景 page's tools (Kelvin 2026-11-11 16:39「建立一个3D建模的界面…有点像CAD」, 17:32「可以，然后记得保存建模，
 * 以及后面可以导入建模之类的」; step 2 of the 组 + 场景 mockup): the rail's 选择 (N), 方块 (B: a click on the grid puts
 * a lane there as a box), 阵列, 镜像 and 成组; the preview's grid 「网格 100 ▾」 that boxes and drags snap to; the
 * handle on the picked group or lane (its arrows move it, the yellow ring turns a group, the white cube sizes it); the
 * 阵列 card's amber ghosts until 确定; Ctrl+D; and models: a group saved in the Charter folder, added to any chart.
 *
 * <p>工具条 (Kelvin 2026-11-14「A可以」, after the video she sent: 移动 / 旋转 / 缩放): at the foot of the preview while a
 * lane (轨道页) or a group or lane (场景页) is picked, 移动 W (the handle above; on the 轨道 page dragging the lane as
 * before), 旋转 E (three rings round its origin or centre, x red, y green, z blue, turning its 角度 R or its 旋转 by the
 * 吸附 step, about the axes it is in (世界) or its own turned ones (自身)), 缩放 R (a lane's 宽 / 高 / 长 along its own
 * axes, and the cube).
 */
final class SceneTools {
    /** 网格: its steps (0: off) and the button's labels. */
    static final int[] GRIDS = {0, 25, 50, 100, 200, 500};
    static final String[] GRID_LABELS = {"网格 关", "网格 25", "网格 50", "网格 100", "网格 200", "网格 500"};
    /** 阵列 绕: the axes a ring turns about. */
    static final String[] AXIS_LABELS = {"x 轴", "竖直（y 轴）", "z 轴"};
    /** A new box: its base's sides and its height. */
    static final float BOX = 240f;
    private static final String[] RAIL = {"选择", "方块", "阵列", "镜像", "成组"};
    private static final Color AMBER = Gfx.BPM, RING = Gfx.SYNC;
    private static final Color[] AXIS_INK = {EditorScreen.AX_X, EditorScreen.AX_Y, EditorScreen.AX_Z};
    /** 工具条: the handle's modes and their keys. */
    static final String[] MODES = {"移动 W", "旋转 E", "缩放 R"};
    /** 吸附 of the rings: whole degrees (关), or steps of 5°, 15°, 45° or 90°. */
    static final float[] SNAPS = {0f, 5f, 15f, 45f, 90f};
    static final String[] SNAP_LABELS = {"吸附 关", "吸附 5°", "吸附 15°", "吸附 45°", "吸附 90°"};
    private static final String[] OWN_LABELS = {"世界", "自身"}, SIZE_NAMES = {"宽", "高", "长"};
    /** 工具条: where it is (left, top from the preview's foot), how big. */
    static final float STRIP_W = 536f, STRIP_H = 42f;

    private final EditorScreen ed;
    int gridIdx = 3;
    /** 阵列: open while its card shows; 一圈 or 一行, how many with the first, about which axis, centre, where to. */
    boolean arrayOn, circle = true;
    int count = 12, axis = 1, centre, into;
    /** 阵列: 一行's step (x, y, z) and 一圈's whole turn (degrees). */
    float gapX = 300f, gapY, gapZ, total = 360f;
    /**
     * The handle being dragged: 0 none, 1–3 the x, y or z arrow, 4 a group's yellow ring, 5 the cube; 旋转 6–8 the x,
     * y or z ring; 缩放 9–11 a lane's 宽, 高 or 长.
     */
    int handle;
    /** 工具条: 0 移动, 1 旋转, 2 缩放; the rings' 吸附 ({@link #SNAPS}); 自身 (the rings about its own axes) or 世界. */
    int mode, snapIdx = 2;
    boolean own;
    /** A ring's drag: which (0 x, 1 y, 2 z), its axes at the press, a lane's axes before 角度, the angle shown. */
    private int ring;
    private final double[][] rAx = new double[3][3], oAx = new double[3][3], pressRing = new double[3][3];
    private float[] plain;
    private float shown, startAngle;
    private final float[] sDir = new float[2], lf = new float[Scene.LF];
    private boolean marked, byMouse;
    private final float[] v0 = new float[6];
    private float s0, turned, lastAngle;
    /** The handle's target this frame: 0 none, 1 a group, 2 a lane; its id; its group's size, centre and axes. */
    private int tKind, tId;
    private float tK = 1f;
    private double tLen;
    private final double[] tC = new double[3], pressC = new double[3];
    private final double[][] tAx = new double[3][3], pressAx = new double[3][3];
    private final double[] dir = new double[3], p = new double[3], q = new double[3];
    private final double[][] corners = new double[8][3];
    private final float[] pa = new float[2], pb = new float[2];

    SceneTools(EditorScreen ed) {
        this.ed = ed;
    }

    // ================================================================== grid

    /** @return the grid's step, 0 when it is off */
    float step() {
        return GRIDS[gridIdx];
    }

    /** @return v on the grid on the 场景 page, else (or with the grid off) in whole units */
    float snapLen(float v) {
        float st = step();
        if (st > 0f && ed.insp.tab == Inspector.SCENE) return Math.round(v / st) * st;
        return Math.round(v);
    }

    /** 「网格 100 ▾」 in the preview's button column (场景 page). */
    void gridButton(float x, float y) {
        gridIdx = ed.im.dropdown("pv-grid", x, y, EditorScreen.PV_BTN_W, 24f, GRID_LABELS[gridIdx], GRID_LABELS,
            gridIdx);
    }

    /** The grid on the floor (y = 0) of the group one is in, else of the chart. */
    void grid() {
        float st = step();
        if (st <= 0f) return;
        Group7.Pose pp = pose(ed.s.groupIn);
        int n = 30;
        double e = n * st;
        for (int i = -n; i <= n; i++) {
            float a = i % 5 == 0 ? 0.3f : 0.13f;
            world(pp, i * st, 0, -e, p);
            world(pp, i * st, 0, e, q);
            ed.line3(p, q, 1f, Gfx.INK3, a);
            world(pp, -e, 0, i * st, p);
            world(pp, e, 0, i * st, q);
            ed.line3(p, q, 1f, Gfx.INK3, a);
        }
    }

    /** @return group {@code id}'s pose at the playhead, null for the chart's axes */
    private Group7.Pose pose(int id) {
        Group7.Pose pp = Scene.pose(id);
        return pp != null ? pp.at(ed.beat) : null;
    }

    /** Point (x, y, z) of the group whose pose is pp (null: the chart) into out, chart axes. */
    private static void world(Group7.Pose pp, double x, double y, double z, double[] out) {
        if (pp == null) {
            out[0] = x;
            out[1] = y;
            out[2] = z;
            return;
        }
        for (int i = 0; i < 3; i++) {
            out[i] = pp.t[i] + pp.k * (pp.m[3 * i] * x + pp.m[3 * i + 1] * y + pp.m[3 * i + 2] * z);
        }
    }

    /** Chart point w into the axes of the group whose pose is pp (null: the chart), into out. */
    private static void local(Group7.Pose pp, double[] w, double[] out) {
        if (pp == null) {
            System.arraycopy(w, 0, out, 0, 3);
            return;
        }
        double x = w[0] - pp.t[0], y = w[1] - pp.t[1], z = w[2] - pp.t[2], k = Math.max(1e-6, pp.k);
        for (int i = 0; i < 3; i++) out[i] = (pp.m[i] * x + pp.m[3 + i] * y + pp.m[6 + i] * z) / k;
    }

    // ================================================================== drawing and input on the preview

    /**
     * Over the preview on the 场景 page: the 阵列 ghosts, the box 方块 would put, the handle; on the 轨道 page the
     * selected lane's rings (旋转) or sizes (缩放), its moves staying as they were.
     */
    void draw(boolean free) {
        boolean scene = ed.insp.tab == Inspector.SCENE;
        if (scene && arrayOn) ghosts();
        if (scene && ed.tool == EditorScreen.T_BOX && free) boxGhost();
        if (handleOn()) {
            drawHandle();
            if (free && handle == 0 && handleAt() != 0) ed.im.cursor(Cursor.SystemCursor.Hand);
        }
    }

    /** @return whether the handle is there: the 选择 tool; the 场景 page without 阵列, the 轨道 page in 旋转 or 缩放 */
    private boolean handleOn() {
        if (ed.tool != EditorScreen.T_SELECT) return false;
        return ed.insp.tab == Inspector.SCENE ? !arrayOn : ed.insp.tab == Inspector.LANE && mode != 0;
    }

    /** A press on the preview (场景 or 轨道 page): 方块 puts a box; a press on the handle drags it. @return taken */
    boolean press() {
        if (ed.insp.tab == Inspector.SCENE && ed.tool == EditorScreen.T_BOX) {
            placeBox();
            return true;
        }
        if (!handleOn()) return false;
        int h = handleAt();
        if (h == 0) return false;
        handle = h;
        marked = false;
        System.arraycopy(tC, 0, pressC, 0, 3);
        for (int j = 0; j < 3; j++) System.arraycopy(tAx[j], 0, pressAx[j], 0, 3);
        Fn[] fs = fns();
        if (fs == null) {
            handle = 0;
            return false;
        }
        for (int i = 0; i < fs.length; i++) v0[i] = fs[i].at(ed.beat);
        ed.sc.ray(ed.im.mx, ed.im.my, dir);
        if (h <= 3) s0 = (float) along(h - 1);
        if (h == 4) {
            turned = 0f;
            lastAngle = (float) ringAngle();
            byMouse = Float.isNaN(lastAngle);
        }
        if (h >= 6 && h <= 8) {
            ring = h - 6;
            for (int j = 0; j < 3; j++) System.arraycopy(rAx[j], 0, pressRing[j], 0, 3);
            Lane7 l = tKind == 2 ? ed.s.doc.lane(tId) : null;
            plain = l != null ? new float[9] : null;
            if (l != null) Doc.plainAxes(l, ed.beat, plain);
            turned = shown = 0f;
            lastAngle = startAngle = (float) ringTurn();
            byMouse = Float.isNaN(lastAngle);
        }
        if (h >= 9) {                                                   // which way on screen the size grows
            for (int i = 0; i < 3; i++) p[i] = tC[i] + oAx[h - 9][i] * tLen;
            sDir[0] = sDir[1] = 0f;
            if (ed.sc.project(p[0], p[1], p[2], pb, 0)) {
                float dx = pb[0] - pa[0], dy = pb[1] - pa[1], n = (float) Math.hypot(dx, dy);
                if (n > 1f) {
                    sDir[0] = dx / n;
                    sDir[1] = dy / n;
                }
            }
        }
        return true;
    }

    /** Drags the handle: along its arrow (on the grid), round the ring (whole degrees) or the cube (its size). */
    void drag() {
        Fn[] fs = fns();
        if (fs == null) {
            handle = 0;
            return;
        }
        ed.sc.ray(ed.im.mx, ed.im.my, dir);
        float[] to = new float[fs.length];
        System.arraycopy(v0, 0, to, 0, fs.length);
        if (handle <= 3) {
            double s = along(handle - 1);
            if (Double.isNaN(s) || Float.isNaN(s0)) return;
            to[handle - 1] = snapLen(v0[handle - 1] + (float) ((s - s0) / Math.max(1e-6f, tK)));
        } else if (handle == 4) {
            if (byMouse) turned = (ed.im.mx - ed.im.pressX) * 0.5f;
            else {
                double a = ringAngle();
                if (Double.isNaN(a)) return;
                double da = a - lastAngle;
                while (da > 180.0) da -= 360.0;
                while (da < -180.0) da += 360.0;
                turned += (float) da;
                lastAngle = (float) a;
            }
            to[0] = Math.round(v0[0] - turned);                         // +R.y takes +x to −z: against the angle
        } else if (handle >= 6 && handle <= 8) {                        // 旋转: whole 吸附 steps, from the press
            if (byMouse) turned = (ed.im.mx - ed.im.pressX) * 0.5f;
            else {
                double a = ringTurn();
                if (Double.isNaN(a)) return;
                double da = a - lastAngle;
                while (da > 180.0) da -= 360.0;
                while (da < -180.0) da += 360.0;
                turned += (float) da;
                lastAngle = (float) a;
            }
            float st = SNAPS[snapIdx];
            shown = st > 0f ? Math.round(turned / st) * st : Math.round(turned);
            System.arraycopy(Doc.turnBy(new float[]{v0[0], v0[1], v0[2]}, ring, shown, own, plain), 0, to, 0, 3);
        } else if (handle >= 9) {                                       // 缩放 one way: along it on screen
            float f = (float) Math.exp(((ed.im.mx - ed.im.pressX) * sDir[0] + (ed.im.my - ed.im.pressY) * sDir[1])
                / 200.0);
            for (int i = 0; i < fs.length; i++) to[i] = Math.round(v0[i] * f);
        } else {
            float f = (float) Math.exp((ed.im.mx - ed.im.pressX) / 200.0);
            for (int i = 0; i < fs.length; i++) {
                to[i] = tKind == 1 ? Math.max(0.001f, Math.round(v0[i] * f * 1000f) / 1000f) : Math.round(v0[i] * f);
            }
        }
        boolean any = false;
        for (int i = 0; i < fs.length; i++) any |= fs[i].at(ed.beat) != to[i];
        if (!any) return;
        if (!marked) {
            marked = true;
            ed.s.mark();
            if (handle >= 6) {                                          // 路点: the others keep their angle, size
                Keyframes.Group[] all = keyed();
                if (all != null) Keyframes.seed(all[handle <= 8 ? 1 : handle == 10 ? 3 : 2], all);
            }
        }
        float t = ed.snap(ed.beat);
        for (int i = 0; i < fs.length; i++) if (fs[i].at(ed.beat) != to[i]) Doc.setAt(fs[i], t, to[i]);
        ed.s.changed(true);
    }

    /** The end of a handle's drag. */
    void release() {
        if (handle == 0) return;
        if (marked) ed.s.changed(false);
        handle = 0;
        marked = false;
    }

    /** @return the functions the handle being dragged changes, null when its target is gone */
    private Fn[] fns() {
        Doc d = ed.s.doc;
        if (tKind == 1) {
            Group7 g = d.group(tId);
            if (g == null) return null;
            if (handle >= 6 && handle <= 8) return new Fn[]{g.R.x, g.R.y, g.R.z};
            return handle <= 3 ? new Fn[]{g.P.x, g.P.y, g.P.z} : handle == 4 ? new Fn[]{g.R.y} : new Fn[]{g.K};
        }
        Lane7 l = tKind == 2 ? d.lane(tId) : null;
        if (l == null) return null;
        if (handle <= 3) return new Fn[]{l.v.x, l.v.y, l.v.z};
        if (handle >= 6 && handle <= 8) return new Fn[]{l.R.x, l.R.y, l.R.z};
        if (handle >= 9) return handle == 9 ? new Fn[]{l.w} : handle == 10 ? new Fn[]{l.S} : new Fn[]{l.h};
        return handle == 5 ? new Fn[]{l.w, l.h, l.S, l.s, l.F, l.f} : null;
    }

    /** @return the target's 路点 rows (a lane's 位置 / 角度 / 大小, a group's 位置 / 旋转 / 缩放), null when it is gone */
    private Keyframes.Group[] keyed() {
        Doc d = ed.s.doc;
        if (tKind == 1) return d.group(tId) != null ? Keyframes.position(Keyframes.group(d.group(tId)), 3) : null;
        return tKind == 2 && d.lane(tId) != null ? Keyframes.laneKeyed(d.lane(tId)) : null;
    }

    /**
     * The handle's target: the picked group, else the picked lane; its centre, the axes of the group it is in, and an
     * arrow length that shows about 80 pixels long. Leaves the centre's place on screen in {@link #pa}. @return any
     */
    private boolean target() {
        Session s = ed.s;
        Doc d = s.doc;
        float t = ed.beat;
        Group7 gr = ed.insp.tab == Inspector.LANE ? null : d.group(s.groupSel);  // the 轨道 page: its lane
        Lane7 l = gr == null ? d.lane(s.laneSel) : null;
        tKind = 0;
        if (gr == null && l == null) return false;
        Group7.Pose pp;
        if (gr != null) {
            tKind = 1;
            tId = gr.id;
            pp = pose(d.parentOf(gr.id));
            world(pp, gr.P.x.at(t), gr.P.y.at(t), gr.P.z.at(t), tC);
        } else {
            tKind = 2;
            tId = l.id;
            pp = pose(d.group(l.g) != null ? l.g : 0);
            world(pp, l.v.x.at(t), l.v.y.at(t), l.v.z.at(t), tC);
        }
        tK = pp != null ? pp.k : 1f;
        for (int j = 0; j < 3; j++) {
            for (int i = 0; i < 3; i++) tAx[j][i] = pp != null ? pp.m[3 * i + j] : i == j ? 1 : 0;
        }
        Group7.Pose op = gr != null ? pose(gr.id) : null;               // its own axes: turned, as drawn
        if (l != null) Scene.laneFrame(l, t, lf);
        for (int j = 0; j < 3; j++) {
            for (int i = 0; i < 3; i++) {
                oAx[j][i] = l != null ? lf[Scene.A + 3 * j + i] : op != null ? op.m[3 * i + j] : i == j ? 1 : 0;
                rAx[j][i] = own ? oAx[j][i] : tAx[j][i];
            }
        }
        double most = 0;
        for (int j = 0; j < 3; j++) {
            for (int i = 0; i < 3; i++) p[i] = tC[i] + tAx[j][i] * 100.0;
            if (ed.sc.project(p[0], p[1], p[2], pb, 0) && ed.sc.project(tC[0], tC[1], tC[2], pa, 0)) {
                most = Math.max(most, Math.hypot(pb[0] - pa[0], pb[1] - pa[1]));
            }
        }
        if (most < 1.0 || !ed.sc.project(tC[0], tC[1], tC[2], pa, 0)) return false;
        tLen = 100.0 * 80.0 / most;
        return true;
    }

    /** Point k of 48 round the ring (radius r in the target group's x–z plane) into out. */
    private void ringPoint(int k, double r, double[] out) {
        double a = Math.PI * 2.0 * k / 48.0, c = Math.cos(a) * r, s = Math.sin(a) * r;
        for (int i = 0; i < 3; i++) out[i] = tC[i] + tAx[0][i] * c + tAx[2][i] * s;
    }

    /**
     * The handle: 移动 three arrows along the axes of the group the target is in, a group's ring, the white cube;
     * 旋转 the three rings; 缩放 a lane's 宽 / 高 / 长 and the cube.
     */
    private void drawHandle() {
        if (!target()) return;
        float cx = pa[0], cy = pa[1];
        Gfx g = ed.g;
        if (mode == 1) {
            rings(cx, cy);
            return;
        }
        if (mode == 2) {
            for (int j = 0; j < 3 && tKind == 2; j++) {
                for (int i = 0; i < 3; i++) p[i] = tC[i] + oAx[j][i] * tLen;
                ed.line3(tC, p, handle == 9 + j ? 4f : 3f, AXIS_INK[j], 0.95f);
                if (!ed.sc.project(p[0], p[1], p[2], pb, 0)) continue;
                g.rect(pb[0] - 5f, pb[1] - 5f, 10f, 10f, AXIS_INK[j]);
                g.text(SIZE_NAMES[j], pb[0] + 9f, pb[1] - 9f, 12, AXIS_INK[j]);
            }
            g.rect(cx - 7f, cy - 7f, 14f, 14f, Color.WHITE);
            return;
        }
        if (tKind == 1) {
            double r = tLen * 0.7;
            double[] a = new double[3], b = new double[3];
            for (int k = 0; k < 48; k++) {
                ringPoint(k, r, a);
                ringPoint(k + 1, r, b);
                ed.line3(a, b, handle == 4 ? 3f : 2f, RING, 0.9f);
            }
        }
        for (int j = 0; j < 3; j++) {
            for (int i = 0; i < 3; i++) p[i] = tC[i] + tAx[j][i] * tLen;
            ed.line3(tC, p, handle == j + 1 ? 4f : 3f, AXIS_INK[j], 0.95f);
            if (!ed.sc.project(p[0], p[1], p[2], pb, 0)) continue;
            float dx = pb[0] - cx, dy = pb[1] - cy, len = (float) Math.hypot(dx, dy);
            if (len < 1f) continue;
            dx /= len;
            dy /= len;
            g.tri(pb[0] + dx * 10f, pb[1] + dy * 10f, pb[0] - dy * 5f, pb[1] + dx * 5f, pb[0] + dy * 5f,
                pb[1] - dx * 5f, AXIS_INK[j], 1f);
        }
        g.rect(cx - 6f, cy - 6f, 12f, 12f, Color.WHITE);
    }

    /** @return the handle under the pointer: 5 the cube, 1–3 an arrow, 4 the ring, 6–8 a ring, 9–11 a size; 0 none */
    private int handleAt() {
        if (!target()) return 0;
        float mx = ed.im.mx, my = ed.im.my, cx = pa[0], cy = pa[1], touch = EditorScreen.TOUCH;
        int h = 0;
        float best = 9f * touch;
        if (mode == 1) {
            float[] a = new float[2];
            for (int k = 0; k < 3; k++) {
                for (int q2 = 0; q2 < 48; q2++) {
                    ringPoint(rAx, k, q2, tLen, p);
                    ringPoint(rAx, k, q2 + 1, tLen, q);
                    if (!ed.sc.project(p[0], p[1], p[2], a, 0) || !ed.sc.project(q[0], q[1], q[2], pb, 0)) continue;
                    float d = Scene.segDist(a[0], a[1], pb[0], pb[1], mx, my);
                    if (d < best) {
                        best = d;
                        h = 6 + k;
                    }
                }
            }
            return h;
        }
        if (Math.abs(mx - cx) <= 8f * touch && Math.abs(my - cy) <= 8f * touch) return 5;
        if (mode == 2) {
            for (int j = 0; j < 3 && tKind == 2; j++) {
                for (int i = 0; i < 3; i++) p[i] = tC[i] + oAx[j][i] * tLen * 1.12;
                if (!ed.sc.project(p[0], p[1], p[2], pb, 0)) continue;
                float d = Scene.segDist(cx, cy, pb[0], pb[1], mx, my);
                if (d < best) {
                    best = d;
                    h = 9 + j;
                }
            }
            return h;
        }
        for (int j = 0; j < 3; j++) {
            for (int i = 0; i < 3; i++) p[i] = tC[i] + tAx[j][i] * tLen * 1.12;
            if (!ed.sc.project(p[0], p[1], p[2], pb, 0)) continue;
            float d = Scene.segDist(cx, cy, pb[0], pb[1], mx, my);
            if (d < best) {
                best = d;
                h = j + 1;
            }
        }
        if (tKind != 1) return h;
        float[] a = new float[2];
        for (int k = 0; k < 48; k++) {
            ringPoint(k, tLen * 0.7, p);
            ringPoint(k + 1, tLen * 0.7, q);
            if (!ed.sc.project(p[0], p[1], p[2], a, 0) || !ed.sc.project(q[0], q[1], q[2], pb, 0)) continue;
            float d = Scene.segDist(a[0], a[1], pb[0], pb[1], mx, my);
            if (d < best) {
                best = d;
                h = 4;
            }
        }
        return h;
    }

    /** @return how far along arrow j (from the centre at the press) the pointer's ray passes nearest; NaN along it */
    private double along(int j) {
        double[] a = pressAx[j], e = ed.sc.eye;
        double b = a[0] * dir[0] + a[1] * dir[1] + a[2] * dir[2], den = 1.0 - b * b;
        if (den < 1e-4) return Double.NaN;
        double wx = pressC[0] - e[0], wy = pressC[1] - e[1], wz = pressC[2] - e[2];
        double da = a[0] * wx + a[1] * wy + a[2] * wz, dd = dir[0] * wx + dir[1] * wy + dir[2] * wz;
        return (b * dd - da) / den;
    }

    /** @return the pointer's angle round the ring in its plane (degrees), NaN when the view runs along that plane */
    private double ringAngle() {
        double[] n = pressAx[1], e = ed.sc.eye;
        double dn = dir[0] * n[0] + dir[1] * n[1] + dir[2] * n[2];
        if (Math.abs(dn) < 0.15) return Double.NaN;
        double t = ((pressC[0] - e[0]) * n[0] + (pressC[1] - e[1]) * n[1] + (pressC[2] - e[2]) * n[2]) / dn;
        double hx = e[0] + dir[0] * t - pressC[0], hy = e[1] + dir[1] * t - pressC[1];
        double hz = e[2] + dir[2] * t - pressC[2];
        double x = hx * pressAx[0][0] + hy * pressAx[0][1] + hz * pressAx[0][2];
        double z = hx * pressAx[2][0] + hy * pressAx[2][1] + hz * pressAx[2][2];
        return Math.toDegrees(Math.atan2(z, x));
    }

    /**
     * 旋转: the three rings round the centre (x red, y green, z blue); while one is dragged, the turn so far swept from
     * where the drag began, its 吸附 ticks and a bubble with how far and where it is written.
     */
    private void rings(float cx, float cy) {
        Gfx g = ed.g;
        double[] a = new double[3], b = new double[3];
        boolean dragging = handle >= 6 && handle <= 8;
        for (int k = 0; k < 3; k++) {
            boolean on = handle == 6 + k;
            for (int q2 = 0; q2 < 48; q2++) {
                ringPoint(rAx, k, q2, tLen, a);
                ringPoint(rAx, k, q2 + 1, tLen, b);
                ed.line3(a, b, on ? 3.5f : 2f, AXIS_INK[k], dragging && !on ? 0.3f : 0.9f);
            }
        }
        g.disc(cx, cy, 5f, Color.WHITE);
        if (!dragging) return;
        Color c = AXIS_INK[ring];
        float st = SNAPS[snapIdx], a0 = startAngle;
        if (Float.isNaN(a0)) a0 = 0f;
        int n = Math.max(2, (int) Math.ceil(Math.abs(shown) / 5f));
        float[] e0 = new float[2], e1 = new float[2];
        for (int i = 0; i < n; i++) {                                   // the sweep
            pressPoint(a0 + shown * i / n, a);
            pressPoint(a0 + shown * (i + 1) / n, b);
            if (ed.sc.project(a[0], a[1], a[2], e0, 0) && ed.sc.project(b[0], b[1], b[2], e1, 0)) {
                g.tri(cx, cy, e0[0], e0[1], e1[0], e1[1], c, 0.18f);
            }
        }
        if (st > 0f && 360f / st <= 72f) {                              // the 吸附 steps
            for (float t = 0f; t < 360f - 1e-3f; t += st) {
                pressPoint(a0 + t, a);
                if (ed.sc.project(a[0], a[1], a[2], e0, 0)) g.disc(e0[0], e0[1], 2.5f, c);
            }
        }
        pressPoint(a0, a);
        pressPoint(a0 + shown, b);
        if (!ed.sc.project(a[0], a[1], a[2], e0, 0) || !ed.sc.project(b[0], b[1], b[2], e1, 0)) return;
        g.line(cx, cy, e0[0], e0[1], 1.5f, c, 0.8f);
        g.line(cx, cy, e1[0], e1[1], 2f, c, 1f);
        boolean keyed = false;
        Fn[] fs = fns();
        for (int i = 0; fs != null && i < fs.length; i++) keyed |= !fs[i].segs.isEmpty();
        String l1 = "绕 " + "xyz".charAt(ring) + " 转 " + Im.fmt(shown) + "°";
        String l2 = (st > 0f ? "吸附 " + Im.fmt(st) + "°" : "吸附 关") + " · "
            + (keyed ? "记在第 " + Im.fmt(ed.snap(ed.beat)) + " 拍" : "没有路点：一直是这个角度");
        float w = Math.max(g.width(l1, 14), g.width(l2, 11)) + 24f, x = e1[0] + 18f, y = e1[1] + 18f;
        g.box(x, y, w, 48f, 8f, Gfx.BAR, c);
        g.text(l1, x + 12f, y + 16f, 14, c);
        g.text(l2, x + 12f, y + 35f, 11, Gfx.INK2);
    }

    /** Point k of 48 round ring {@code ax} (0 x, 1 y, 2 z: in the plane of the other two axes of ax) into out. */
    private void ringPoint(double[][] ax, int k, int q2, double r, double[] out) {
        double an = Math.PI * 2.0 * q2 / 48.0, c = Math.cos(an) * r, s = Math.sin(an) * r;
        double[] u = ax[(k + 1) % 3], w = ax[(k + 2) % 3];
        for (int i = 0; i < 3; i++) out[i] = tC[i] + u[i] * c + w[i] * s;
    }

    /** The point at {@code deg} round the dragged ring as it was at the press, into out. */
    private void pressPoint(float deg, double[] out) {
        double an = Math.toRadians(deg), c = Math.cos(an) * tLen, s = Math.sin(an) * tLen;
        double[] u = pressRing[(ring + 1) % 3], w = pressRing[(ring + 2) % 3];
        for (int i = 0; i < 3; i++) out[i] = pressC[i] + u[i] * c + w[i] * s;
    }

    /**
     * @return the pointer's angle round the dragged ring in its plane (degrees; a turn of +d about the ring's axis
     *         adds d), NaN when the view runs along that plane
     */
    private double ringTurn() {
        double[] n = pressRing[ring], u = pressRing[(ring + 1) % 3], w = pressRing[(ring + 2) % 3], e = ed.sc.eye;
        double dn = dir[0] * n[0] + dir[1] * n[1] + dir[2] * n[2];
        if (Math.abs(dn) < 0.15) return Double.NaN;
        double t = ((pressC[0] - e[0]) * n[0] + (pressC[1] - e[1]) * n[1] + (pressC[2] - e[2]) * n[2]) / dn;
        double hx = e[0] + dir[0] * t - pressC[0], hy = e[1] + dir[1] * t - pressC[1];
        double hz = e[2] + dir[2] * t - pressC[2];
        return Math.toDegrees(Math.atan2(hx * w[0] + hy * w[1] + hz * w[2], hx * u[0] + hy * u[1] + hz * u[2]));
    }

    /** @return where ring k (0 x, 1 y, 2 z) is on screen at {@code deg}; null when there is none (the self-test) */
    float[] ringOnScreen(int k, float deg) {
        if (!target()) return null;
        double an = Math.toRadians(deg), c = Math.cos(an) * tLen, s = Math.sin(an) * tLen;
        double[] u = rAx[(k + 1) % 3], w = rAx[(k + 2) % 3];
        for (int i = 0; i < 3; i++) p[i] = tC[i] + u[i] * c + w[i] * s;
        return ed.sc.project(p[0], p[1], p[2], pb, 0) ? new float[]{pb[0], pb[1]} : null;
    }

    // ================================================================== 工具条

    /** @return whether the 工具条 shows: a lane picked on the 轨道 page; a group or lane on the 场景 page (no 阵列) */
    boolean stripOn() {
        Session s = ed.s;
        if (!ed.overlays) return false;
        if (ed.insp.tab == Inspector.LANE) return s.doc.lane(s.laneSel) != null;
        return ed.insp.tab == Inspector.SCENE && !arrayOn
            && (s.doc.group(s.groupSel) != null || s.doc.lane(s.laneSel) != null);
    }

    /** @return whether the pointer is on the 工具条 */
    boolean overStrip() {
        return stripOn() && ed.im.inside(EditorScreen.PX + 12f, stripY(), STRIP_W, STRIP_H);
    }

    private static float stripY() {
        return EditorScreen.PY + EditorScreen.PH - 12f - STRIP_H;
    }

    /** The 工具条 at the foot of the preview: 移动 W / 旋转 E / 缩放 R, the rings' 吸附, 世界 / 自身. */
    void strip() {
        if (!stripOn()) return;
        float x = EditorScreen.PX + 12f, y = stripY(), bx = x + 6f, by = y + 6f;
        ed.g.box(x, y, STRIP_W, STRIP_H, 10f, Gfx.BAR, Gfx.LINE);
        for (int i = 0; i < 3; i++) {
            if (ed.im.button("pv-mode" + i, bx, by, 84f, 30f, MODES[i], i == mode ? Im.ON : Im.NORMAL, 12)) {
                setMode(i);
            }
            bx += 90f;
        }
        snapIdx = ed.im.dropdown("pv-snap", bx + 6f, by, 112f, 30f, SNAP_LABELS[snapIdx], SNAP_LABELS, snapIdx);
        own = ed.im.segmented("pv-own", bx + 124f, by, 124f, 30f, OWN_LABELS, own ? 1 : 0) == 1;
    }

    /** W / E / R: the handle's mode, with the 选择 tool (where the handle is). */
    void setMode(int m) {
        mode = m;
        if (ed.tool != EditorScreen.T_SELECT) ed.setTool(EditorScreen.T_SELECT);
    }

    /** @return where the handle's arrow h (1–3) is on screen, halfway along; null when there is none (the self-test) */
    float[] arrowOnScreen(int h) {
        if (!target()) return null;
        for (int i = 0; i < 3; i++) p[i] = tC[i] + tAx[h - 1][i] * tLen * 0.6;
        return ed.sc.project(p[0], p[1], p[2], pb, 0) ? new float[]{pb[0], pb[1]} : null;
    }

    // ================================================================== 方块

    /**
     * @return where a box goes for the pointer, in the axes of the group one is in (snapped): on its floor (y = 0)
     *         where the view looks down or up at it, else on the plane through its origin facing the eye; null nowhere
     */
    private float[] boxPoint() {
        Scene sc = ed.sc;
        sc.ray(ed.im.mx, ed.im.my, dir);
        Group7.Pose pp = pose(ed.s.groupIn);
        double[] o = new double[3], n = {0, 1, 0};
        world(pp, 0, 0, 0, o);
        if (pp != null) {
            n[0] = pp.m[1];
            n[1] = pp.m[4];
            n[2] = pp.m[7];
        }
        double dn = dir[0] * n[0] + dir[1] * n[1] + dir[2] * n[2];
        boolean floor = Math.abs(dn) > 0.2;
        if (floor) {
            double t = ((o[0] - sc.eye[0]) * n[0] + (o[1] - sc.eye[1]) * n[1] + (o[2] - sc.eye[2]) * n[2]) / dn;
            if (!(t > 1.0)) return null;
            for (int i = 0; i < 3; i++) p[i] = sc.eye[i] + dir[i] * t;
        } else if (!sc.onFacing(dir, o[0], o[1], o[2], p)) return null;
        local(pp, p, q);
        float st = step() > 0f ? step() : 1f;
        float[] r = new float[3];
        for (int i = 0; i < 3; i++) r[i] = Math.round(q[i] / st) * st;
        if (floor) r[1] = 0f;
        return r;
    }

    /** @return a new box at {@code at} (the axes of the group one is in): a lane {@link #BOX} wide, deep and high */
    private Lane7 newBox(Doc d, float[] at) {
        Lane7 l = new Lane7(d.nextLaneId(), at[0]);
        l.v.y.constant = at[1];
        l.v.z.constant = at[2];
        l.g = d.group(ed.s.groupIn) != null ? ed.s.groupIn : 0;
        l.w.constant = l.h.constant = BOX;
        l.F.constant = 0f;                                              // standing on the grid
        l.S.constant = BOX;
        l.s.constant = l.f.constant = 0f;
        return l;
    }

    /** 方块 (B): a box at the pointer, on the grid; it comes in at the playhead as 新建轨道 does, and is picked. */
    private void placeBox() {
        float[] at = boxPoint();
        if (at == null) return;
        Session s = ed.s;
        Doc d = s.doc;
        float b = ed.snap(ed.beat);
        s.mark();
        Lane7 l = newBox(d, at);
        Doc.setAppear(l, b, ed.insp.appearHow == Inspector.GRADUAL ? ed.insp.appearBeats : 0f, 1f);
        d.lanes.add(l);
        s.changed(false);
        Doc.Items it = new Doc.Items();
        it.lanes.add(l);
        pick(it);
        ed.im.toast("放了方块：轨道 " + d.laneLabel(l.id) + "（第 " + Im.fmt(b) + " 拍出现），右边是它的轨道卡");
    }

    /** The box a click would put, amber and dashed. */
    private void boxGhost() {
        float[] at = boxPoint();
        if (at == null) return;
        float[] lf = new float[Scene.LF];
        Scene.laneFrame(newBox(ed.s.doc, at), ed.beat, lf);
        box(lf, AMBER, 0.85f, true);
    }

    /** The edges of lane frame lf's column, as the preview draws lanes; dashed or plain. */
    private void box(float[] lf, Color c, float a, boolean dashed) {
        double hw = Math.abs(lf[Scene.W]) * 0.5, hd = Math.abs(lf[Scene.H]) * 0.5;
        double lo = lf[Scene.GND] - lf[Scene.GND_EXT], hi = lf[Scene.SKY] + lf[Scene.SKY_EXT];
        int[][] round = {{-1, -1}, {1, -1}, {1, 1}, {-1, 1}};
        for (int i = 0; i < 8; i++) {
            Scene.at(lf, round[i % 4][0] * hw, i < 4 ? lo : hi, round[i % 4][1] * hd, corners[i], 0);
        }
        for (int e = 0; e < 4; e++) {
            seg(corners[e], corners[(e + 1) % 4], c, a, dashed);
            seg(corners[4 + e], corners[4 + (e + 1) % 4], c, a, dashed);
            seg(corners[e], corners[4 + e], c, a, dashed);
        }
    }

    private void seg(double[] a, double[] b, Color c, float alpha, boolean dashed) {
        if (dashed && ed.sc.project(a[0], a[1], a[2], pa, 0) && ed.sc.project(b[0], b[1], b[2], pb, 0)
            && Math.hypot(pb[0] - pa[0], pb[1] - pa[1]) < 3000.0) {
            ed.g.dashed(pa[0], pa[1], pb[0], pb[1], 1.5f, 6f, c, alpha);
        } else {
            ed.line3(a, b, 1.5f, c, alpha * (dashed ? 0.6f : 1f));
        }
    }

    // ================================================================== picks, 阵列, 镜像, 复制

    /** @return the picked lanes and groups when they are all right in one group, else null (and a toast if say) */
    Doc.Items picks(boolean say) {
        Session s = ed.s;
        Doc d = s.doc;
        Doc.Items it = new Doc.Items();
        for (int id : s.pickLanes) if (d.lane(id) != null) it.lanes.add(d.lane(id));
        for (int id : s.pickGroups) if (d.group(id) != null) it.groups.add(d.group(id));
        if (it.isEmpty()) {
            if (d.lane(s.laneSel) != null) it.lanes.add(d.lane(s.laneSel));
            if (d.group(s.groupSel) != null) it.groups.add(d.group(s.groupSel));
        }
        if (it.isEmpty()) {
            if (say) ed.im.toast("先选中轨道或组（预览里点一下，Shift+点多选）");
            return null;
        }
        if (d.parentOf(it) < 0) {
            if (say) ed.im.toast("选中的轨道和组不在同一个组里（先进到同一个组里再选）");
            return null;
        }
        return it;
    }

    /** Picks what is in {@code it}; the 场景 page stays. */
    private void pick(Doc.Items it) {
        Session s = ed.s;
        s.pickLanes.clear();
        s.pickGroups.clear();
        s.laneSel = Session.NO_LANE;
        s.groupSel = 0;
        for (Lane7 l : it.lanes) {
            s.pickLanes.add(l.id);
            s.laneSel = l.id;
        }
        for (Group7 g : it.groups) {
            s.pickGroups.add(g.id);
            s.groupSel = g.id;
        }
        s.sel.clear();
        ed.insp.tab = Inspector.SCENE;
    }

    /** @return what the card calls the picks: one's name, else how many */
    String pickName(Doc.Items it) {
        if (it.size() > 1) return it.size() + " 个";
        return it.groups.isEmpty() ? ed.s.doc.laneLabel(it.lanes.get(0).id) : Doc.groupLabel(it.groups.get(0));
    }

    /** 阵列 opens on what is picked. */
    void openArray() {
        if (picks(true) == null) return;
        ed.setTool(EditorScreen.T_SELECT);
        arrayOn = true;
    }

    /** @return the 阵列's new group's name: the first one's name without its number, else 「阵列」 */
    String groupName(Doc.Items it) {
        String n = null;
        if (it != null && !it.groups.isEmpty()) n = it.groups.get(0).name;
        else if (it != null && !it.lanes.isEmpty()) n = ed.s.doc.names.get(it.lanes.get(0).id);
        if (n != null) n = n.replaceAll("[\\s0-9]+$", "").trim();
        return n == null || n.isEmpty() ? "阵列" : n;
    }

    /** @return the groups whose centres 一圈 can turn about: the others right in the same group */
    private ArrayList<Group7> centreGroups(Doc.Items it) {
        Doc d = ed.s.doc;
        int parent = d.parentOf(it);
        ArrayList<Group7> out = new ArrayList<>();
        for (Group7 g : d.groups) if (d.parentOf(g.id) == parent && !it.groups.contains(g)) out.add(g);
        return out;
    }

    /** @return 中心's choices: the origin of the group they are in, then the other groups' centres there */
    String[] centreLabels(Doc.Items it) {
        ArrayList<Group7> gs = centreGroups(it);
        String[] out = new String[gs.size() + 1];
        out[0] = "原点 (0, 0, 0)";
        float t = ed.beat;
        for (int i = 0; i < gs.size(); i++) {
            Group7 g = gs.get(i);
            out[i + 1] = "「" + Doc.groupLabel(g) + "」的中心 (" + Math.round(g.P.x.at(t)) + ", " + Math.round(g.P.y.at(t))
                + ", " + Math.round(g.P.z.at(t)) + ")";
        }
        return out;
    }

    /** @return the centre 一圈 turns about, in the axes of the group they are in */
    private float[] centrePoint(Doc.Items it) {
        ArrayList<Group7> gs = centreGroups(it);
        if (centre < 1 || centre > gs.size()) return new float[3];
        Group7 g = gs.get(centre - 1);
        float t = ed.beat;
        return new float[]{g.P.x.at(t), g.P.y.at(t), g.P.z.at(t)};
    }

    /** @return copy i's turn in a ring: 360° (or more) goes round in equal steps, less has both ends */
    private float[] turnAbout(int i) {
        int n = Math.max(2, count);
        float a = (Math.abs(total) >= 359.999f ? total / n : total / (n - 1)) * i;
        return Doc.turnOf(axis == 0 ? a : 0f, axis == 1 ? a : 0f, axis == 2 ? a : 0f);
    }

    /** 阵列: the copies 确定 would add, amber and dashed where they will be (plain lines past 240 boxes). */
    private void ghosts() {
        Doc.Items it = picks(false);
        if (it == null) return;
        Doc d = ed.s.doc;
        Group7.Pose pp = pose(d.parentOf(it));
        ArrayList<Lane7> src = new ArrayList<>(it.lanes);
        for (Lane7 l : d.lanes) {
            for (Group7 g : it.groups) {
                if (d.within(l.g, g.id)) {
                    src.add(l);
                    break;
                }
            }
        }
        int n = Math.max(2, count);
        boolean dashed = src.size() * (n - 1) <= 240;
        float[] lf = new float[Scene.LF], c = centrePoint(it);
        for (int i = 1; i < n; i++) {
            float[] turn = circle ? turnAbout(i) : null;
            for (Lane7 l : src) {
                Scene.laneFrame(l, ed.beat, lf);
                if (Math.abs(lf[Scene.W]) < 0.5f) continue;
                moveFrame(lf, pp, turn, c, i);
                box(lf, AMBER, 0.85f, dashed);
            }
        }
    }

    /** Lane frame lf (chart axes) where copy i of the 阵列 puts it: through the axes of the group (pose pp) it is in. */
    private void moveFrame(float[] lf, Group7.Pose pp, float[] turn, float[] c, int i) {
        for (int j = 0; j < 3; j++) p[j] = lf[Scene.O + j];
        local(pp, p, q);
        double x = q[0], y = q[1], z = q[2];
        if (turn != null) {
            x -= c[0];
            y -= c[1];
            z -= c[2];
            for (int j = 0; j < 3; j++) q[j] = c[j] + turn[3 * j] * x + turn[3 * j + 1] * y + turn[3 * j + 2] * z;
        } else {
            q[0] += gapX * i;
            q[1] += gapY * i;
            q[2] += gapZ * i;
        }
        world(pp, q[0], q[1], q[2], p);
        for (int j = 0; j < 3; j++) lf[Scene.O + j] = (float) p[j];
        if (turn == null) return;
        for (int o = Scene.A; o <= Scene.D; o += 3) {                   // its axes: into the group's, turned, back
            double ax = lf[o], ay = lf[o + 1], az = lf[o + 2];
            double a = ax, b = ay, e = az;
            if (pp != null) {
                a = pp.m[0] * ax + pp.m[3] * ay + pp.m[6] * az;
                b = pp.m[1] * ax + pp.m[4] * ay + pp.m[7] * az;
                e = pp.m[2] * ax + pp.m[5] * ay + pp.m[8] * az;
            }
            double ra = turn[0] * a + turn[1] * b + turn[2] * e, rb = turn[3] * a + turn[4] * b + turn[5] * e;
            double re = turn[6] * a + turn[7] * b + turn[8] * e;
            for (int k = 0; k < 3; k++) {
                lf[o + k] = (float) (pp == null ? (k == 0 ? ra : k == 1 ? rb : re)
                    : pp.m[3 * k] * ra + pp.m[3 * k + 1] * rb + pp.m[3 * k + 2] * re);
            }
        }
    }

    /** 阵列 确定: the copies, each one more step along (一行) or one more step round the centre (一圈). */
    void applyArray() {
        Doc.Items it = picks(true);
        if (it == null) return;
        Session s = ed.s;
        Doc d = s.doc;
        String why = circle ? d.cannotTurn(it) : null;
        if (why != null) {
            ed.im.toast(why);
            return;
        }
        int n = Math.max(2, count), parent = d.parentOf(it);
        float[] c = centrePoint(it);
        String name = groupName(it);
        s.mark();
        Doc.Items all = new Doc.Items();
        all.addAll(it);
        for (int i = 1; i < n; i++) {
            Doc.Items cp = d.duplicate(it);
            if (circle) d.turn(cp, turnAbout(i), c);
            else Doc.move(cp, gapX * i, gapY * i, gapZ * i);
            all.addAll(cp);
        }
        arrayOn = false;
        if (into == 0) {
            Group7 gr = d.makeGroup(all.lanes, all.groups, parent, ed.snap(ed.beat));
            gr.name = d.freeGroupName(name);
            if (circle) d.recentre(gr, c);
            s.changed(false);
            ed.pickGroup(gr.id, false);
            ed.im.toast("阵列：" + (circle ? "一圈 " : "一行 ") + n + " 个，收进新组「" + gr.name + "」");
        } else {
            s.changed(false);
            pick(all);
            ed.im.toast("阵列：" + (circle ? "一圈 " : "一行 ") + n + " 个");
        }
    }

    /** 镜像: a copy of what is picked, mirrored left ↔ right (x → −x of the group it is in); the copy is picked. */
    void mirror() {
        Doc.Items it = picks(true);
        if (it == null) return;
        Session s = ed.s;
        s.mark();
        Doc.Items cp = s.doc.duplicate(it);
        s.doc.mirror(cp);
        s.changed(false);
        pick(cp);
        ed.im.toast("镜像：左右对称复制了 " + cp.size() + " 个（x 变成 −x），不带键");
    }

    /** Ctrl+D 复制一个: a copy of what is picked, one grid step (else 100) to the right; the copy is picked. */
    void duplicate() {
        Doc.Items it = picks(true);
        if (it == null) return;
        Session s = ed.s;
        float st = step() > 0f ? step() : 100f;
        s.mark();
        Doc.Items cp = s.doc.duplicate(it);
        Doc.move(cp, st, 0f, 0f);
        s.changed(false);
        pick(cp);
        ed.im.toast("复制了 " + cp.size() + " 个，往右 " + Math.round(st) + "（不带键）");
    }

    // ================================================================== rail

    /** The rail on the 场景 page: 选择 (N), 方块 (B), 阵列, 镜像, 成组 (Ctrl+G); the key tools are on the other pages. */
    void rail() {
        Gfx g = ed.g;
        Im im = ed.im;
        for (int i = 0; i < RAIL.length; i++) {
            float y = 64f + i * 52f;
            boolean on = i == 0 ? ed.tool == EditorScreen.T_SELECT && !arrayOn
                : i == 1 ? ed.tool == EditorScreen.T_BOX : i == 2 && arrayOn;
            boolean hv = im.hover(6f, y, 52f, 46f);
            if (on) g.box(6f, y, 52f, 46f, 8f, Gfx.SEL, Gfx.GND);
            else if (hv) g.rrect(6f, y, 52f, 46f, 8f, Gfx.BTN);
            icon(i, 32f, y + 16f, on ? Gfx.INK1 : Gfx.INK2);
            g.text(RAIL[i], 32f, y + 35f, 11, on ? Gfx.INK1 : Gfx.INK2, 1);
            if (!Gfx.TABLET && i < 2) g.text(i == 0 ? "N" : "B", 52f, y + 8f, 9, Gfx.INK3, 1);
            if (hv) im.cursor(Cursor.SystemCursor.Hand);
            if (im.click(new Im.Pair("srail", i), 6f, y, 52f, 46f)) act(i);
        }
        g.text("键的工具在", 32f, 64f + RAIL.length * 52f + 10f, 9, Gfx.INK3, 1);
        g.text("其他页", 32f, 64f + RAIL.length * 52f + 24f, 9, Gfx.INK3, 1);
    }

    private void act(int i) {
        if (i == 0 || i == 1) {
            arrayOn = false;
            ed.setTool(i == 0 ? EditorScreen.T_SELECT : EditorScreen.T_BOX);
        } else if (i == 2) {
            if (arrayOn) arrayOn = false;
            else openArray();
        } else if (i == 3) mirror();
        else ed.makeGroup();
    }

    private void icon(int i, float cx, float cy, Color c) {
        Gfx g = ed.g;
        if (i == 0) {
            g.tri(cx - 5f, cy - 9f, cx - 5f, cy + 6f, cx + 6f, cy + 2f, c, 1f);
            g.line(cx - 1f, cy + 2f, cx + 2f, cy + 8f, 2f, c);
        } else if (i == 1) {                                            // a cube
            g.stroke(cx - 9f, cy - 5f, 13f, 13f, 1f, 1.5f, c);
            g.line(cx - 9f, cy - 5f, cx - 4f, cy - 10f, 1.5f, c);
            g.line(cx + 4f, cy - 5f, cx + 9f, cy - 10f, 1.5f, c);
            g.line(cx + 4f, cy + 8f, cx + 9f, cy + 3f, 1.5f, c);
            g.line(cx - 4f, cy - 10f, cx + 9f, cy - 10f, 1.5f, c);
            g.line(cx + 9f, cy - 10f, cx + 9f, cy + 3f, 1.5f, c);
        } else if (i == 2) {                                            // a ring of dots
            for (int k = 0; k < 8; k++) {
                double a = Math.PI * k / 4.0;
                g.disc(cx + (float) Math.cos(a) * 9f, cy + (float) Math.sin(a) * 9f, 2.2f, c, 1f);
            }
        } else if (i == 3) {                                            // two halves about a line
            g.tri(cx - 2f, cy - 9f, cx - 2f, cy + 8f, cx - 11f, cy + 8f, c, 1f);
            g.tri(cx + 2f, cy - 9f, cx + 2f, cy + 8f, cx + 11f, cy + 8f, c, 0.45f);
        } else {                                                        // two boxes in a dashed one
            g.dashedRect(cx - 11f, cy - 9f, 22f, 18f, c, 1f);
            g.rect(cx - 7f, cy - 5f, 6f, 6f, c);
            g.rect(cx + 1f, cy - 1f, 6f, 6f, c);
        }
    }

    // ================================================================== models

    /** 保存为模型: group g with everything in it (no keys) under a name; every chart can then 导入 it. */
    void saveModel(Group7 g) {
        Library lib = ed.s.lib;
        String json = ed.s.doc.modelJson(g.id, ed.snap(ed.beat));
        Im.Modal m = new Im.Modal("保存为模型", "起个名字。存在 Charter 文件夹的 models 里，所有谱的场景页都能「导入模型」（同名的会被换掉）。",
            new String[]{"保存", "取消"}, (b, t) -> {
                String name = t == null ? "" : Library.modelName(t);
                if (b != 0) return;
                if (name.isEmpty()) ed.im.toast("名字不能空着");
                else if (lib.saveModel(name, json)) ed.im.toast("已保存模型「" + name + "」：场景页「导入模型」里");
                else ed.im.toast("保存失败：Charter 文件夹写不进去");
            });
        m.input = Doc.groupLabel(g);
        ed.im.open(m);
    }

    /** 导入模型: model {@code name} as a new group (new ids) at the origin of the group one is in; it is picked. */
    void importModel(String name) {
        Session s = ed.s;
        String json = s.lib.model(name);
        try {
            if (json == null) throw new java.io.IOException("读不出来");
            ChartFormatV7.read(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            ed.im.toast("模型「" + name + "」用不了：" + e.getMessage());
            return;
        }
        Doc d = s.doc;
        int parent = d.group(s.groupIn) != null ? s.groupIn : 0;
        s.mark();
        Doc.Items it;
        try {
            it = d.addModel(json, parent);
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e); // read once already
        }
        s.changed(false);
        pick(it);
        int lanes = it.lanes.size();
        for (Group7 g : it.groups) lanes += d.laneCount(g.id);
        ed.im.toast("已导入模型「" + name + "」：" + lanes + " 条轨道，放在" + (parent != 0 ? "这个组" : "谱面") + "的原点");
    }
}
