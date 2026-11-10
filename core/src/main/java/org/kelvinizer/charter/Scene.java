package org.kelvinizer.charter;

import org.kelvinizer.elements.Chart7;
import org.kelvinizer.elements.Fn;
import org.kelvinizer.elements.Fn3;
import org.kelvinizer.elements.Key7;
import org.kelvinizer.elements.Lane7;
import org.kelvinizer.elements.Volume7;

/**
 * 编辑器自己的三维换算：镜头在哪（看着原点）、谱面里的一点在预览上的哪里、鼠标底下的射线，轨道在某一拍的位置和朝向。
 * The editor's own 3D arithmetic for a v7 chart (Kelvin 2026-11-09 18:44): the camera P looks at the origin with +y
 * up (+z when it is straight above or below), the game's 11° field of view over the 1200-high frame, the editor's own
 * turn and zoom on top ({@link #look}); where a point of chart space (x right of the centre line, y up from the
 * frame's centre, z inward) shows on the preview ({@link #project}); the ray under a pixel ({@link #ray}); a lane's
 * frame at a beat ({@link #laneFrame}: its origin and the axes the shortest turn from straight down gives, as the
 * game's lanes have them) and where its keys are; ray tests against lanes and veils.
 */
final class Scene {
    /**
     * The editor's plain views (Kelvin 2026-11-09 04:54; 20:35「魔方可以点击边和角」): from the view cube's six faces
     * (前 后 左 右 上 下, indices 0–5), twelve edges (6–17) and eight corners (18–25); {@link #VIEW_DIR} is the side
     * each looks from, components −1, 0 or 1.
     */
    static final int[][] VIEW_DIR = new int[26][];
    static final String[] VIEW_NAMES = new String[26];
    static final float[] VIEW_YAW = new float[26], VIEW_PITCH = new float[26];

    static {
        int[][] n = {{0, 0, -1}, {0, 0, 1}, {-1, 0, 0}, {1, 0, 0}, {0, 1, 0}, {0, -1, 0}};
        String[] side = {"前", "后", "左", "右", "上", "下"}, face = {"前视", "后视", "左视", "右视", "俯视", "仰视"};
        int i = 0;
        for (int a = 0; a < 6; a++) view(i++, face[a], n[a]);
        for (int a = 0; a < 2; a++) for (int b = 2; b < 4; b++) view(i++, side[a] + side[b], n[a], n[b]);
        for (int a = 0; a < 4; a++) for (int c = 4; c < 6; c++) view(i++, side[a] + side[c], n[a], n[c]);
        for (int a = 0; a < 2; a++) {
            for (int b = 2; b < 4; b++) {
                for (int c = 4; c < 6; c++) view(i++, side[a] + side[b] + side[c], n[a], n[b], n[c]);
            }
        }
    }

    private static void view(int i, String name, int[]... sides) {
        int[] d = new int[3];
        for (int[] n : sides) for (int k = 0; k < 3; k++) d[k] += n[k];
        double len = Math.sqrt(d[0] * d[0] + d[1] * d[1] + d[2] * d[2]);
        VIEW_DIR[i] = d;
        VIEW_NAMES[i] = name;
        VIEW_PITCH[i] = (float) Math.toDegrees(Math.asin(d[1] / len));
        VIEW_YAW[i] = d[0] == 0 && d[2] == 0 ? 0f : (float) Math.toDegrees(Math.atan2(d[0], -d[2]));
    }
    /** The game's view stops 1° short of straight above or below (View3D): so does the editor's. */
    static final double MAX_PITCH = 89.0;
    /** Nearer to the eye than this (along its view) is not drawn. */
    private static final double NEAR = 1.0;
    /** A lane frame's floats: origin, across, up, depth (three each), then w, h, S, s, F, f. */
    static final int LF = 18;
    static final int O = 0, A = 3, U = 6, D = 9, W = 12, H = 13, SKY = 14, SKY_EXT = 15, GND = 16, GND_EXT = 17;

    /** Frame units → canvas pixels, and the frame's centre on the canvas. */
    float k = EditorScreen.K, cx = EditorScreen.PX + EditorScreen.PW * 0.5f,
        cy = EditorScreen.PY + EditorScreen.PH * 0.5f;
    /** The eye, and the camera's right, up and forward axes. */
    final double[] eye = new double[3], r = new double[3], u = new double[3], f = new double[3];
    /** The game's lens: 11° over the 1200-tall frame (600 / tan 5.5°), so the columns' front is at scale 1. */
    static final double FOCAL = 600.0 / Math.tan(Math.toRadians(5.5));
    /** The focal length in frame units: the chart's field of view over the 1200-tall frame, times the editor's zoom. */
    double focal = FOCAL;

    /** @return the yaw of a camera at (px, ·, pz), degrees: 0 straight above or below (+z up), as the game's View3D */
    static double baseYaw(double px, double pz) {
        return Math.hypot(px, pz) > 1e-3 ? Math.toDegrees(Math.atan2(px, -pz)) : 0;
    }

    /**
     * Sets the eye: the chart's camera at beat {@code t}, then turned about the origin by the editor ({@code yaw}
     * degrees round +y, {@code pitch} up), or one of the plain views ({@code preset} ≥ 0) at the same distance; the
     * lens is the chart's field of view {@code fov} at t (degrees, 11 by default, as the game: 1…150); {@code zoom}
     * narrows it.
     */
    void look(Fn3 cam, Fn fov, float t, float yaw, float pitch, float zoom, int preset) {
        double px = cam.x.at(t), py = cam.y.at(t), pz = cam.z.at(t);
        double d = Math.sqrt(px * px + py * py + pz * pz);
        if (!(d > 1e-3) || !Double.isFinite(d)) {
            px = 0;
            py = 0;
            pz = -Chart7.CAMERA_DIST;
            d = Chart7.CAMERA_DIST;
        }
        if (preset >= 0 || yaw != 0f || pitch != 0f) {
            double y0 = baseYaw(px, pz);
            double p0 = Math.toDegrees(Math.asin(Math.max(-1, Math.min(1, py / d))));
            double ya = preset >= 0 ? VIEW_YAW[preset] : y0 + yaw;
            double pa = Math.max(-MAX_PITCH, Math.min(MAX_PITCH, preset >= 0 ? VIEW_PITCH[preset] : p0 + pitch));
            double cy = Math.cos(Math.toRadians(ya)), sy = Math.sin(Math.toRadians(ya));
            double cp = Math.cos(Math.toRadians(pa)), sp = Math.sin(Math.toRadians(pa));
            px = d * sy * cp;
            py = d * sp;
            pz = -d * cy * cp;
            if (Math.abs(cp) < 1e-9) px = pz = 0;                 // straight above or below
        }
        eye[0] = px;
        eye[1] = py;
        eye[2] = pz;
        f[0] = -px / d;
        f[1] = -py / d;
        f[2] = -pz / d;
        // up × forward is right; straight above or below the origin +z is up
        double ux = 0, uy = 1, uz = 0;
        if (Math.abs(f[1]) > 1 - 1e-9) {
            uy = 0;
            uz = 1;
        }
        cross(ux, uy, uz, f[0], f[1], f[2], r);
        norm(r);
        cross(f[0], f[1], f[2], r[0], r[1], r[2], u);
        norm(u);
        double deg = fov != null ? fov.at(t) : 11;
        deg = Double.isFinite(deg) ? Math.max(1, Math.min(150, deg)) : 11;
        focal = 600.0 / Math.tan(Math.toRadians(deg * 0.5)) * (zoom > 0f ? zoom : 1f);
    }

    /**
     * Where point (x, y, z) shows on the canvas, into out[o], out[o + 1]. @return false when it is behind the eye
     * (out unchanged)
     */
    boolean project(double x, double y, double z, float[] out, int o) {
        double dx = x - eye[0], dy = y - eye[1], dz = z - eye[2];
        double zc = dx * f[0] + dy * f[1] + dz * f[2];
        if (zc < NEAR) return false;
        double xc = dx * r[0] + dy * r[1] + dz * r[2], yc = dx * u[0] + dy * u[1] + dz * u[2];
        out[o] = (float) (cx + xc / zc * focal * k);
        out[o + 1] = (float) (cy - yc / zc * focal * k);
        return true;
    }

    /** @return how far point (x, y, z) is in front of the eye along its view (negative behind it) */
    double depth(double x, double y, double z) {
        return (x - eye[0]) * f[0] + (y - eye[1]) * f[1] + (z - eye[2]) * f[2];
    }

    /** The direction (unit) of the ray from the eye through canvas pixel (sx, sy), into dir. */
    void ray(float sx, float sy, double[] dir) {
        double xc = (sx - cx) / (k * focal), yc = -(sy - cy) / (k * focal);
        for (int i = 0; i < 3; i++) dir[i] = f[i] + r[i] * xc + u[i] * yc;
        norm(dir);
    }

    // ================================================================== lanes

    /** Lane {@code l} at beat {@code t} into lf ({@link #LF} floats). */
    static void laneFrame(Lane7 l, float t, float[] lf) {
        lf[O] = l.v.x.at(t);
        lf[O + 1] = l.v.y.at(t);
        lf[O + 2] = l.v.z.at(t);
        turn(l.u.x.at(t), l.u.y.at(t), l.u.z.at(t), lf, A);
        lf[W] = l.w.at(t);
        lf[H] = l.h.at(t);
        lf[SKY] = l.S.at(t);
        lf[SKY_EXT] = l.s.at(t);
        lf[GND] = l.F.at(t);
        lf[GND_EXT] = l.f.at(t);
    }

    /** The point {@code a} across, {@code up} up and {@code d} deep from frame lf's origin, into out[o..o + 2]. */
    static void at(float[] lf, double a, double up, double d, double[] out, int o) {
        for (int i = 0; i < 3; i++) out[o + i] = lf[O + i] + lf[A + i] * a + lf[U + i] * up + lf[D + i] * d;
    }

    /**
     * The turn of a lane that falls along (fx, fy, fz), as the game turns its lanes (LaneLayout.turn: chart axes; any
     * length; none or NaN: straight down): the shortest turn that takes straight down to it, straight up half a turn
     * about z. Into out[o..o + 8]: the turned across axis, up axis (against the fall) and depth axis.
     */
    static void turn(float fx, float fy, float fz, float[] out, int o) {
        double len = Math.sqrt((double) fx * fx + (double) fy * fy + (double) fz * fz);
        double bx = 0, by = -1, bz = 0;
        if (len > 1e-9 && len < Double.POSITIVE_INFINITY) {
            bx = fx / len;
            by = fy / len;
            bz = fz / len;
        }
        double s = bx * bx + bz * bz;
        if (s < 1e-12) {
            float k = by < 0 ? 1f : -1f;
            out[o] = k;
            out[o + 1] = 0f;
            out[o + 2] = 0f;
            out[o + 3] = 0f;
            out[o + 4] = k;
            out[o + 5] = 0f;
            out[o + 6] = 0f;
            out[o + 7] = 0f;
            out[o + 8] = 1f;
            return;
        }
        double q = by > 0 ? (1 + by) / s : 1 / (1 - by);
        out[o] = (float) (1 - bx * bx * q);
        out[o + 1] = (float) bx;
        out[o + 2] = (float) (-bx * bz * q);
        out[o + 3] = (float) -bx;
        out[o + 4] = (float) -by;
        out[o + 5] = (float) -bz;
        out[o + 6] = (float) (-bx * bz * q);
        out[o + 7] = (float) bz;
        out[o + 8] = (float) (1 - bz * bz * q);
    }

    /**
     * @return how far up its lane key {@code k} is at beat {@code t} in the lane frame lf: a ground key {@code h}
     *         above the base, a sky key {@code h} under the sky face
     */
    static float keyUp(Key7 k, float t, float[] lf) {
        float h = k.h.at(t);
        return k.sky ? lf[SKY] - h : lf[GND] + h;
    }

    /** @return how long key {@code k}'s body is at beat {@code t} (0 for a kind without a height) */
    static float keyLength(Key7 k, float t) {
        return k.kind.hasHeight() && !k.H.isEmpty() ? Math.max(0f, k.H.at(t)) : 0f;
    }

    /** @return whether key {@code k} shows at beat {@code t}: from its fall's start to its judgement (its end) */
    static boolean keyShows(Key7 k, float t) {
        float a = k.showBeat(), b = k.endBeat();
        return Float.isFinite(a) && t >= a - 1e-4f && t <= b + 1e-4f;
    }

    // ================================================================== ray tests

    /**
     * The ray from the eye along dir against a box centred at c with unit axes ax (3 × 3, rows) and half sizes e:
     * @return the distance along the ray where it enters (0 when it starts inside), NaN when it misses
     */
    double rayBox(double[] dir, double[] c, double[] ax, double[] e) {
        double t0 = 0, t1 = Double.POSITIVE_INFINITY;
        for (int i = 0; i < 3; i++) {
            double ox = (eye[0] - c[0]) * ax[i * 3] + (eye[1] - c[1]) * ax[i * 3 + 1] + (eye[2] - c[2]) * ax[i * 3 + 2];
            double dd = dir[0] * ax[i * 3] + dir[1] * ax[i * 3 + 1] + dir[2] * ax[i * 3 + 2];
            if (Math.abs(dd) < 1e-12) {
                if (Math.abs(ox) > e[i]) return Double.NaN;
                continue;
            }
            double a = (-e[i] - ox) / dd, b = (e[i] - ox) / dd;
            if (a > b) {
                double tmp = a;
                a = b;
                b = tmp;
            }
            t0 = Math.max(t0, a);
            t1 = Math.min(t1, b);
            if (t0 > t1) return Double.NaN;
        }
        return t0;
    }

    /** The ray from the eye along dir against an ellipsoid centred at c with unit axes ax (3 × 3, rows), semi-axes e. */
    double rayEllipsoid(double[] dir, double[] c, double[] ax, double[] e) {
        double a = 0, b = 0, cc = -1;
        for (int i = 0; i < 3; i++) {
            if (!(e[i] > 1e-6)) return Double.NaN;
            double o = ((eye[0] - c[0]) * ax[i * 3] + (eye[1] - c[1]) * ax[i * 3 + 1] + (eye[2] - c[2]) * ax[i * 3 + 2])
                / e[i];
            double d = (dir[0] * ax[i * 3] + dir[1] * ax[i * 3 + 1] + dir[2] * ax[i * 3 + 2]) / e[i];
            a += d * d;
            b += 2 * o * d;
            cc += o * o;
        }
        double disc = b * b - 4 * a * cc;
        if (disc < 0 || a < 1e-18) return Double.NaN;
        double sq = Math.sqrt(disc), t0 = (-b - sq) / (2 * a), t1 = (-b + sq) / (2 * a);
        if (t1 < 0) return Double.NaN;
        return Math.max(0, t0);
    }

    /**
     * The ray from the eye along dir against lane frame lf's column (across ±w/2, up from F − f to S + s, deep ±h/2).
     * @return where it enters, NaN when it misses
     */
    double rayLane(double[] dir, float[] lf) {
        double lo = lf[GND] - lf[GND_EXT], hi = lf[SKY] + lf[SKY_EXT];
        if (hi < lo) {
            double t = lo;
            lo = hi;
            hi = t;
        }
        double[] c = new double[3], ax = new double[9], e = new double[3];
        at(lf, 0, (lo + hi) * 0.5, 0, c, 0);
        for (int i = 0; i < 3; i++) {
            ax[i] = lf[A + i];
            ax[3 + i] = lf[U + i];
            ax[6 + i] = lf[D + i];
        }
        e[0] = Math.abs(lf[W]) * 0.5;
        e[1] = (hi - lo) * 0.5;
        e[2] = Math.max(1.0, Math.abs(lf[H]) * 0.5);
        if (!(e[0] > 0.5)) return Double.NaN;                         // a lane with no width is not there
        return rayBox(dir, c, ax, e);
    }

    /**
     * Where the ray from the eye along dir meets lane frame lf's middle plane (across × up through its origin), as
     * {across, up} into out. @return false when the ray runs along that plane or it is behind the eye
     */
    boolean onLane(double[] dir, float[] lf, float[] out) {
        double nx = lf[D], ny = lf[D + 1], nz = lf[D + 2];
        double dn = dir[0] * nx + dir[1] * ny + dir[2] * nz;
        if (Math.abs(dn) < 0.12) return false;                       // grazing: no good place along it
        double t = ((lf[O] - eye[0]) * nx + (lf[O + 1] - eye[1]) * ny + (lf[O + 2] - eye[2]) * nz) / dn;
        if (!(t > NEAR)) return false;
        double px = eye[0] + dir[0] * t - lf[O], py = eye[1] + dir[1] * t - lf[O + 1];
        double pz = eye[2] + dir[2] * t - lf[O + 2];
        out[0] = (float) (px * lf[A] + py * lf[A + 1] + pz * lf[A + 2]);
        out[1] = (float) (px * lf[U] + py * lf[U + 1] + pz * lf[U + 2]);
        return true;
    }

    /**
     * Where the ray from the eye along dir meets the plane through point p facing the eye, into out (chart space).
     * @return false when that plane is behind the eye
     */
    boolean onFacing(double[] dir, double px, double py, double pz, double[] out) {
        double dn = dir[0] * f[0] + dir[1] * f[1] + dir[2] * f[2];
        double t = ((px - eye[0]) * f[0] + (py - eye[1]) * f[1] + (pz - eye[2]) * f[2]) / dn;
        if (!(t > NEAR) || !Double.isFinite(t)) return false;
        for (int i = 0; i < 3; i++) out[i] = eye[i] + dir[i] * t;
        return true;
    }

    /** @return the volume's ray hit (box or ellipsoid at beat t), NaN when the ray misses it */
    double rayVolume(double[] dir, Volume7 v, float t) {
        double[] c = {v.p.x.at(t), v.p.y.at(t), v.p.z.at(t)};
        double[] e = {Math.abs(v.V.x.at(t)) * 0.5, Math.abs(v.V.y.at(t)) * 0.5, Math.abs(v.V.z.at(t)) * 0.5};
        if (!(e[0] > 0.5 && e[1] > 0.5 && e[2] > 0.5)) return Double.NaN;
        double[] ax = volAxes(v, t);
        return v.ellipsoid ? rayEllipsoid(dir, c, ax, e) : rayBox(dir, c, ax, e);
    }

    /**
     * @return volume v's own axes at beat t (3 × 3, rows: its x, y, z in chart axes), as the game turns it: the
     *         shortest turn taking chart +z to its o (o straight back: half a turn about y; o zero: no turn)
     */
    static double[] volAxes(Volume7 v, float t) {
        float[] a = new float[9];
        Volume7.turn(v.o.x.at(t), v.o.y.at(t), v.o.z.at(t), a);     // the game's own turn
        double[] r = new double[9];
        for (int i = 0; i < 9; i++) r[i] = a[i];
        return r;
    }

    // ================================================================== screen shapes

    /**
     * @return whether (px, py) is inside the convex hull of the n points {x, y, x, y, …} grown by {@code pad} pixels
     *         (n ≥ 1)
     */
    static boolean inHull(float[] pts, int n, float px, float py, float pad) {
        if (n <= 0) return false;
        if (n == 1) return Math.hypot(pts[0] - px, pts[1] - py) <= pad;
        // the hull by Andrew's monotone chain, then the point's distance to it
        Integer[] idx = new Integer[n];
        for (int i = 0; i < n; i++) idx[i] = i;
        java.util.Arrays.sort(idx, (a, b) -> pts[a * 2] != pts[b * 2] ? Float.compare(pts[a * 2], pts[b * 2])
            : Float.compare(pts[a * 2 + 1], pts[b * 2 + 1]));
        int[] hull = new int[2 * n + 1];
        int m = 0;
        for (int pass = 0; pass < 2; pass++) {
            int start = m;
            for (int j = 0; j < n; j++) {
                int i = idx[pass == 0 ? j : n - 1 - j];
                while (m >= start + 2 && crossZ(pts, hull[m - 2], hull[m - 1], i) <= 0) m--;
                hull[m++] = i;
            }
            m--;
        }
        if (m < 3) {                                                  // a segment or a point: near it
            float best = Float.POSITIVE_INFINITY;
            for (int i = 0; i < Math.max(1, m); i++) {
                int a = hull[i], b = hull[(i + 1) % Math.max(1, m)];
                best = Math.min(best, segDist(pts[a * 2], pts[a * 2 + 1], pts[b * 2], pts[b * 2 + 1], px, py));
            }
            return best <= Math.max(pad, 3f);
        }
        boolean inside = true;
        float best = Float.POSITIVE_INFINITY;
        for (int i = 0; i < m; i++) {
            int a = hull[i], b = hull[(i + 1) % m];
            float ax = pts[a * 2], ay = pts[a * 2 + 1], bx = pts[b * 2], by = pts[b * 2 + 1];
            if ((bx - ax) * (py - ay) - (by - ay) * (px - ax) < 0) inside = false;
            best = Math.min(best, segDist(ax, ay, bx, by, px, py));
        }
        return inside || best <= pad;
    }

    private static float crossZ(float[] p, int o, int a, int b) {
        return (p[a * 2] - p[o * 2]) * (p[b * 2 + 1] - p[o * 2 + 1])
            - (p[a * 2 + 1] - p[o * 2 + 1]) * (p[b * 2] - p[o * 2]);
    }

    static float segDist(float ax, float ay, float bx, float by, float px, float py) {
        float dx = bx - ax, dy = by - ay, l2 = dx * dx + dy * dy;
        float t = l2 > 0f ? Math.max(0f, Math.min(1f, ((px - ax) * dx + (py - ay) * dy) / l2)) : 0f;
        return (float) Math.hypot(ax + dx * t - px, ay + dy * t - py);
    }

    // ================================================================== vectors

    private static void cross(double ax, double ay, double az, double bx, double by, double bz, double[] out) {
        out[0] = ay * bz - az * by;
        out[1] = az * bx - ax * bz;
        out[2] = ax * by - ay * bx;
    }

    private static void norm(double[] v) {
        double l = Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
        if (l > 1e-12) {
            v[0] /= l;
            v[1] /= l;
            v[2] /= l;
        }
    }
}
