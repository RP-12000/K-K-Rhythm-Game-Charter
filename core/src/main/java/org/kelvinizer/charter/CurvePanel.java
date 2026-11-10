package org.kelvinizer.charter;

import com.badlogic.gdx.graphics.Color;
import org.kelvinizer.elements.Fn;
import org.kelvinizer.elements.Seg;

import java.util.ArrayList;
import java.util.List;

/**
 * 曲线窗口（谱面格式 v7）：右边点开的那个函数画成曲线，拍数横着（和时间轴对齐），数值竖着；同一组的其他函数淡淡地画在一起。
 * 每一段的开始和结束是一个点，拖动改拍数和数值；点曲线选一段；双击加一个点；Delete 删点或删段。段与段之间保持上一段的结束值（虚线）。
 *
 * <p>The curve window, drawn in place of the timeline's rows while its tab is on. The inspector offers the functions
 * it shows each frame ({@link #offer}); the one edited is drawn with beats across (the timeline's view) and its
 * values up, the other functions of its table dim and scaled to its range. A v7 function (Kelvin 2026-11-09 18:44)
 * is simple segments x_i → x_f over t_i → t_f with ease e, holding the last end value between and after them
 * (dashed here) and the first start value before them. Each segment's ends are points; a segment's end and the next
 * one's start at one beat and value are one point, dragged together. A click on the curve picks that segment, a
 * double click puts a point there ({@link Doc#setAt}: inside a segment a split, elsewhere a new segment from the point
 * before), Delete merges the two segments at a point or removes the picked segment.
 */
final class CurvePanel {
    /** The readout strip over the plot and the beat numbers under it; how near a press must come. */
    private static final float HEAD = Gfx.TABLET ? 26f : 20f, FOOT = 16f, HIT = Gfx.TABLET ? 18f : 9f;
    private static final long DOUBLE_MS = 330L;
    private static final Color[] COLOURS = {Gfx.GND, Gfx.BPM, Gfx.FX, Gfx.TIDE};
    private static final int D_NONE = 0, D_POINT = 1, D_SEEK = 2;

    /** One function of a table, as the inspector offered it this frame. */
    static final class Chan {
        final String id, name, table;
        final Fn f;
        /** The beat shown as 0 (a key's judge beat). */
        final float zero;
        Color colour = Gfx.GND;
        /** This frame's value range. */
        float rLo, rHi;

        Chan(String id, String name, String table, Fn f, float zero) {
            this.id = id;
            this.name = name;
            this.table = table;
            this.f = f;
            this.zero = zero;
        }
    }

    private final EditorScreen ed;
    private final Gfx g;
    private final Im im;
    private final ArrayList<Chan> offers = new ArrayList<>();
    /** The 曲线 tab is on (the timeline's rows give way to the window). */
    boolean on;
    /** The function being edited (its id), or null: the inspector's open one. */
    String active;
    /** The window was the last thing pressed: Delete acts here, not on the selected keys. */
    boolean focus;
    /** The picked segment (a click on the curve), or the picked point's segment ends ({@link #ptSegs}). */
    private Seg pickSeg;
    private final ArrayList<Seg> ptSegs = new ArrayList<>();
    private final ArrayList<Boolean> ptEnd = new ArrayList<>();
    private String pickOf;
    private int drag = D_NONE;
    private boolean marked, moved;
    private float lockLo, lockHi;
    private long lastPress;
    private float lastPx, lastPy;
    /** This frame's plot rectangle and the edited function's range. */
    private float px, py, pw, ph, lo, hi, top, bottom;

    CurvePanel(EditorScreen ed) {
        this.ed = ed;
        this.g = ed.g;
        this.im = ed.im;
    }

    void clearOffers() {
        offers.clear();
    }

    /** Offers function {@code f} as {@code id}, called {@code name}, drawn with the others of {@code table}. */
    void offer(String id, String name, String table, Fn f, float zero) {
        Chan c = new Chan(id, name, table, f, zero);
        int k = 0;
        for (Chan o : offers) if (o.table.equals(table)) k++;
        c.colour = COLOURS[k % COLOURS.length];
        offers.add(c);
    }

    List<Chan> offers() {
        return offers;
    }

    /** Opens the window on function {@code id}. */
    void show(String id) {
        on = true;
        active = id;
        clearPick();
    }

    private Chan chan(String id) {
        if (id == null) return null;
        for (Chan c : offers) if (c.id.equals(id)) return c;
        return null;
    }

    private void clearPick() {
        pickSeg = null;
        ptSegs.clear();
        ptEnd.clear();
        pickOf = null;
    }

    private float bx(double t) {
        return ed.bx(t);
    }

    private double beatAtX(float x) {
        return ed.beatAtX(x);
    }

    private float Y(float v) {
        return py + (hi - v) / (hi - lo) * ph;
    }

    private float V(float y) {
        return hi - (y - py) / ph * (hi - lo);
    }

    // ================================================================== drawing

    /** Draws the window over the timeline's area and handles its input (after the inspector offered functions). */
    void draw() {
        if (!on) return;
        top = EditorScreen.TL_TOP;
        bottom = EditorScreen.TL_BOTTOM;
        px = EditorScreen.TX;
        pw = EditorScreen.TW;
        py = top + HEAD;
        ph = bottom - FOOT - py;
        String open = ed.insp.openId();
        if (open != null && !open.equals(pickOf) && chan(open) != null && !open.equals(active)) {
            active = open;                                              // follow the inspector's open function
            clearPick();
        }
        Chan act = chan(active);
        if (act == null && !offers.isEmpty()) act = offers.get(0);
        chips(act);
        im.pushClip(px, top, pw, bottom - top);
        grid();
        if (act == null) {
            g.text("在右边选中一条轨道、一个键、镜头或漩涡水帘，它的函数就在这里改", px + 12f, py + ph * 0.5f, 13, Gfx.INK3);
            im.popClip();
            input(null);
            return;
        }
        if (!act.id.equals(pickOf)) {
            clearPick();
            pickOf = act.id;
        }
        for (Chan c : offers) if (c.table.equals(act.table)) fit(c);
        if (drag == D_POINT) {
            act.rLo = lockLo;
            act.rHi = lockHi;
        }
        lo = act.rLo;
        hi = act.rHi;
        valueTicks();
        for (Chan c : offers) if (c.table.equals(act.table) && c != act) curve(c, false);
        curve(act, true);
        for (Seg s : act.f.segs) {
            point(s.ti, s.xi, s, false);
            point(Math.max(s.ti, s.tf), s.xf, s, true);
        }
        float hx = bx(ed.beat);
        g.line(hx, py, hx, py + ph, 1f, Gfx.INK1, 0.55f);
        im.popClip();
        readout(act);
        input(act);
    }

    private void point(float t, float v, Seg s, boolean end) {
        boolean picked = false;
        for (int i = 0; i < ptSegs.size(); i++) picked |= ptSegs.get(i) == s && ptEnd.get(i) == end;
        float x = bx(t), y = Y(v);
        g.disc(x, y, picked ? 6f : 5f, Gfx.BG);
        g.disc(x, y, picked ? 4.5f : 3.5f, picked ? Gfx.SYNC : Gfx.INK1);
    }

    /** The table's functions as chips in the left column (a click edits that one), and the way back to the timeline. */
    private void chips(Chan act) {
        float x = 68f, w = EditorScreen.TX - 74f, y = top + 4f;
        if (im.button("cv-back", x, y, w, 24f, "‹ 时间轴", Im.GHOST, 12)) {
            on = false;
            return;
        }
        y += 30f;
        if (act == null) return;
        for (Chan c : offers) {
            if (!c.table.equals(act.table)) continue;
            if (y + 22f > bottom) break;
            boolean isOn = c == act;
            if (im.button("cv-chip-" + c.id, x, y, w, 22f, "", isOn ? Im.ON : Im.GHOST, 11)) {
                active = c.id;
                ed.insp.openFn(c.id);
                clearPick();
            }
            g.disc(x + 9f, y + 11f, 3.5f, c.colour);
            g.text(g.fit(c.name, 11, w - 20f), x + 17f, y + 11f, 11, isOn ? Gfx.INK1 : Gfx.INK2);
            y += 26f;
        }
    }

    private void grid() {
        g.rect(px, top, pw, bottom - top, Gfx.BAR2);
        g.rect(px, py, pw, ph, Gfx.FIELD, 0.85f);
        double b0 = beatAtX(px), span = beatAtX(px + pw) - b0, ppb = pw / span;
        float st = ed.step();
        if (st > 0f && ppb * st >= 10.0) {
            for (long k = (long) Math.floor(b0 / st); k * st <= b0 + span; k++) {
                float x = bx(k * st);
                g.line(x, py, x, py + ph, 1f, Gfx.LINE2, 0.5f);
            }
        }
        int every = 1;
        while (ppb * every < 44.0) every *= 2;
        for (long b = (long) Math.floor(b0); b <= b0 + span + 1; b++) {
            float x = bx(b);
            g.line(x, py, x, py + ph, 1f, Gfx.LINE, Math.floorMod(b, 4) == 0 ? 0.9f : 0.45f);
            if (Math.floorMod(b, every) == 0) g.text(Long.toString(b), x + 3f, py + ph + 9f, 11, Gfx.INK3);
        }
    }

    /** Fits channel c's range to the values it shows here. */
    private void fit(Chan c) {
        float a = Float.POSITIVE_INFINITY, b = Float.NEGATIVE_INFINITY;
        for (float x = px; x <= px + pw; x += 4f) {
            float v = c.f.at((float) beatAtX(x));
            a = Math.min(a, v);
            b = Math.max(b, v);
        }
        for (Seg s : c.f.segs) {                                        // its points, also off screen
            a = Math.min(a, Math.min(s.xi, s.xf));
            b = Math.max(b, Math.max(s.xi, s.xf));
        }
        if (!Float.isFinite(a) || !Float.isFinite(b)) {
            a = -1f;
            b = 1f;
        }
        if (b - a < 1e-3f) {
            float m = Math.max(1f, Math.abs(a) * 0.1f);
            a -= m;
            b += m;
        }
        float pad = (b - a) * 0.14f;
        c.rLo = a - pad;
        c.rHi = b + pad;
    }

    /** @return {@code v} of channel c drawn on the edited function's scale (a dim curve of the same table) */
    private float onScale(Chan c, float v) {
        return lo + (v - c.rLo) / (c.rHi - c.rLo) * (hi - lo);
    }

    /** A few values up the left edge of the plot. */
    private void valueTicks() {
        float step = (float) niceStep((hi - lo) / 4f);
        for (float v = (float) Math.ceil(lo / step) * step; v <= hi; v += step) {
            float y = Y(v);
            if (y < py + 6f || y > py + ph - 4f) continue;
            g.line(px, y, px + pw, y, 1f, Gfx.LINE2, 0.6f);
            g.text(Im.fmt(Math.round(v / step) * step), px + 4f, y - 6f, 10, Gfx.INK3);
        }
    }

    private static double niceStep(double r) {
        double p = Math.pow(10, Math.floor(Math.log10(r > 0 ? r : 1))), f = r / p;
        return (f < 1.5 ? 1 : f < 3.5 ? 2 : f < 7.5 ? 5 : 10) * p;
    }

    /** @return whether beat t is inside one of f's segments (drawn solid; outside them the value holds, dashed) */
    private static boolean inSeg(Fn f, float t) {
        for (Seg s : f.segs) if (s.tf > s.ti && t >= s.ti && t <= s.tf) return true;
        return false;
    }

    /** Draws a function: solid inside its segments, dashed where it holds; jumps as vertical lines. */
    private void curve(Chan c, boolean edited) {
        Fn f = c.f;
        float a = edited ? 1f : 0.4f;
        float lx = Float.NaN, ly = 0f, lt = 0f;
        for (float x = px; x <= px + pw + 2f; x += 2f) {
            float t = (float) beatAtX(x), v = f.at(t), y = Y(edited ? v : onScale(c, v));
            if (!Float.isNaN(lx)) {
                boolean solid = inSeg(f, (t + lt) * 0.5f);
                if (solid) g.line(lx, ly, x, y, edited ? 2f : 1.4f, c.colour, a);
                else if (((int) ((x - px) / 6f) & 1) == 0) g.line(lx, ly, x, y, 1.2f, c.colour, a * 0.6f);
            }
            lx = x;
            ly = y;
            lt = t;
        }
        for (Seg s : f.segs) {                                          // jumps and the picked segment
            boolean picked = edited && s == pickSeg;
            if (s.tf <= s.ti) {
                float x = bx(s.ti);
                float y0 = Y(edited ? s.xi : onScale(c, s.xi)), y1 = Y(edited ? s.xf : onScale(c, s.xf));
                g.line(x, y0, x, y1, picked ? 3f : 1.4f, picked ? Gfx.SYNC : c.colour, a * 0.8f);
            } else if (picked) {
                float u0 = Math.max(s.ti, (float) beatAtX(px)), u1 = Math.min(s.tf, (float) beatAtX(px + pw));
                int steps = Math.max(1, Math.min(240, (int) ((bx(u1) - bx(u0)) / 3f)));
                float qx = Float.NaN, qy = 0f;
                for (int q = 0; q <= steps; q++) {
                    float t = u0 + (u1 - u0) * q / steps, x = bx(t), y = Y(s.at(t));
                    if (!Float.isNaN(qx)) g.line(qx, qy, x, y, 3f, Gfx.SYNC, 1f);
                    qx = x;
                    qy = y;
                }
            }
        }
    }

    /** What is picked, over the plot; a hint when nothing is. */
    private void readout(Chan c) {
        float y = top + HEAD * 0.5f + 1f;
        float x = px + 8f + g.text(c.name, px + 8f, y, 12, c.colour) + 10f;
        String t;
        String rel = c.zero != 0f ? "（从判定拍算）" : "";
        if (!ptSegs.isEmpty()) {
            Seg s = ptSegs.get(0);
            boolean end = ptEnd.get(0);
            t = "点：拍 " + Im.fmt((end ? Math.max(s.ti, s.tf) : s.ti) - c.zero) + rel + "，数值 " + Im.fmt(end ? s.xf : s.xi)
                + "（拖动改；Shift 不吸附；Delete 删点）";
        } else if (pickSeg != null && c.f.segs.contains(pickSeg)) {
            Seg s = pickSeg;
            t = "第 " + (c.f.segs.indexOf(s) + 1) + " 段：拍 " + Im.fmt(s.ti - c.zero) + " → " + Im.fmt(s.tf - c.zero) + rel
                + "，" + Im.fmt(s.xi) + " → " + Im.fmt(s.xf) + "，缓动 " + Im.fmt(s.e) + "（右边改数字；Delete 删这段）";
        } else {
            t = c.f.segs.isEmpty() ? "没有分段：一直是 " + Im.fmt(c.f.constant) + "。双击加一个点开始分段"
                : "拖点改拍数和数值，点曲线选一段，双击加点，Delete 删；虚线 = 保持上一段的结束值";
        }
        g.text(g.fit(t, 12, px + pw - x - 8f), x, y, 12, Gfx.INK2);
    }

    // ================================================================== input

    private void input(Chan c) {
        boolean in = im.hover(px, top, pw, bottom - top);
        if (im.pressed) focus = in && !im.popupOpen();
        if (in && im.wheel != 0f) ed.wheelTime();
        if (drag != D_NONE) {
            if (!im.down) {
                if (moved) ed.s.changed(false);
                drag = D_NONE;
            } else if (drag == D_SEEK) ed.seekBeat((float) beatAtX(im.mx));
            else if (c != null) dragTo(c);
            return;
        }
        if (!in || !im.pressed || im.popupOpen() || im.modalOpen() || im.rightPressed) return;
        long now = System.currentTimeMillis();
        boolean dbl = now - lastPress < DOUBLE_MS && Math.abs(im.mx - lastPx) < 14f && Math.abs(im.my - lastPy) < 14f;
        lastPress = dbl ? 0L : now;
        lastPx = im.mx;
        lastPy = im.my;
        float t = (float) beatAtX(im.mx);
        if (c == null || im.my < py || im.my > py + ph) {
            clearPick();
            ed.seekBeat(t);
            drag = D_SEEK;
            return;
        }
        Fn f = c.f;
        if (pointAt(f)) {
            pickSeg = null;
            drag = D_POINT;
            marked = moved = false;
            lockLo = lo;
            lockHi = hi;
            return;
        }
        if (dbl) {
            ed.s.mark();
            float v = roundValue(V(im.my));
            float tb = im.shift ? t : ed.snap(t);
            if (f.segs.isEmpty()) f.segs.add(new Seg(tb, tb, v, v, 1f));
            else Doc.setAt(f, tb, v);
            ed.s.changed(false);
            clearPick();
            pickOf = c.id;
            return;
        }
        Seg s = segAt(f, t);
        clearPick();
        pickOf = c.id;
        if (s != null) pickSeg = s;
        else {
            ed.seekBeat(t);
            drag = D_SEEK;
        }
    }

    /** Picks the point under the pointer (every segment end there, at one beat and value). @return whether one is */
    private boolean pointAt(Fn f) {
        float best = HIT, bt = 0f, bv = 0f;
        boolean found = false;
        for (Seg s : f.segs) {
            for (int e = 0; e < 2; e++) {
                float t = e == 0 ? s.ti : Math.max(s.ti, s.tf), v = e == 0 ? s.xi : s.xf;
                float d = (float) Math.hypot(bx(t) - im.mx, Y(v) - im.my);
                if (d < best) {
                    best = d;
                    bt = t;
                    bv = v;
                    found = true;
                }
            }
        }
        ptSegs.clear();
        ptEnd.clear();
        if (!found) return false;
        float eps = Math.abs(hi - lo) * 1e-4f + 1e-6f;
        for (Seg s : f.segs) {
            if (Math.abs(s.ti - bt) < 1e-4f && Math.abs(s.xi - bv) <= eps) {
                ptSegs.add(s);
                ptEnd.add(false);
            }
            if (Math.abs(Math.max(s.ti, s.tf) - bt) < 1e-4f && Math.abs(s.xf - bv) <= eps) {
                ptSegs.add(s);
                ptEnd.add(true);
            }
        }
        return !ptSegs.isEmpty();
    }

    /** @return the segment whose curve passes near the pointer at beat t, or null */
    private Seg segAt(Fn f, float t) {
        Seg best = null;
        float bd = HIT * 1.5f;
        for (Seg s : f.segs) {
            float a = s.ti, b = Math.max(s.ti, s.tf);
            if (t < a - 1e-4f || t > b + 1e-4f) continue;
            float d = s.tf > s.ti ? Math.abs(Y(s.at(t)) - im.my)
                : im.my >= Math.min(Y(s.xi), Y(s.xf)) - HIT && im.my <= Math.max(Y(s.xi), Y(s.xf)) + HIT ? 0f : bd;
            if (d < bd) {
                bd = d;
                best = s;
            }
        }
        return best;
    }

    private void dragTo(Chan c) {
        float t = (float) beatAtX(im.mx), v = roundValue(V(im.my));
        if (!im.shift) t = ed.snap(t);
        if (!marked) {
            ed.s.mark();
            marked = true;
        }
        for (int i = 0; i < ptSegs.size(); i++) {
            Seg s = ptSegs.get(i);
            if (ptEnd.get(i)) {
                if (s.tf <= s.ti) s.ti = t;                             // a jump moves as one
                s.tf = t;
                s.xf = v;
            } else {
                boolean jump = s.tf <= s.ti;
                s.ti = t;
                if (jump) s.tf = t;
                s.xi = v;
            }
        }
        c.f.sort();
        moved = true;
        ed.s.changed(true);
    }

    /** @return a dragged or typed value rounded to the window's scale (about 1/500 of its range, a nice step) */
    private float roundValue(float v) {
        float step = (float) niceStep(Math.abs(hi - lo) / 500f);
        return Math.round(v / step) * step;
    }

    /** Delete: the picked point (two segments meeting there merge) or segment. @return whether something went */
    boolean deleteKey() {
        Chan c = chan(pickOf);
        if (c == null) return false;
        Fn f = c.f;
        if (!ptSegs.isEmpty()) {
            Seg endOf = null, startOf = null;
            for (int i = 0; i < ptSegs.size(); i++) {
                if (ptEnd.get(i)) endOf = ptSegs.get(i);
                else startOf = ptSegs.get(i);
            }
            ed.s.mark();
            if (endOf != null && startOf != null && endOf != startOf) {  // merge the two at this point
                endOf.tf = Math.max(startOf.ti, startOf.tf);
                endOf.xf = startOf.xf;
                f.segs.remove(startOf);
            } else {
                Seg gone = endOf != null ? endOf : startOf;
                f.segs.remove(gone);
                if (f.segs.isEmpty()) f.constant = gone.xf;
            }
            ed.s.changed(false);
            clearPick();
            return true;
        }
        if (pickSeg != null && f.segs.contains(pickSeg)) {
            ed.s.mark();
            f.segs.remove(pickSeg);
            if (f.segs.isEmpty()) f.constant = pickSeg.xf;
            ed.s.changed(false);
            clearPick();
            return true;
        }
        return false;
    }

}
