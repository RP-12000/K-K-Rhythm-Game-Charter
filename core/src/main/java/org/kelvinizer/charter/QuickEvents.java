package org.kelvinizer.charter;

import java.util.ArrayList;
import java.util.List;

/**
 * 快捷曲线和快捷事件（Kelvin 2026-11-06「各种快捷曲线，快捷事件都写上」）：给选中的事件换一种缓动曲线，或者在一条事件
 * 列表里一下子放好抖动、摆动、弹跳、闪烁这类常用的动作。游戏的一段事件只认指数曲线，别的曲线拆成几段来近似。
 *
 * <p>Quick curves and quick events. The game eases one event as {@code from + (to − from)·p^exp}, so a curve that is
 * not a power curve is baked into several events ({@link #bake}): cut where the curve turns, then halved until each
 * piece's best exponent stays within {@link #TOL} of the curve's range. A quick event is built the same way on top of
 * what the channel already does ({@link #make}) and {@link #splice}d into the list.
 */
final class QuickEvents {
    private QuickEvents() {}

    // ================================================================== 快捷曲线

    /** 快捷曲线 families, in button order (easings.net's, plus 阶跃 = a jump). */
    static final String[] CURVES = {"线性", "正弦", "二次", "三次", "四次", "五次", "指数", "圆", "回弹", "弹性", "弹跳",
        "阶跃"};
    static final String[] SHAPES = {"缓入", "缓出", "缓入缓出"};
    static final int LINEAR = 0, SINE = 1, QUAD = 2, QUINT = 5, EXPO = 6, BACK = 8, ELASTIC = 9, BOUNCE = 10, STEP = 11;
    static final int IN = 0, OUT = 1, IN_OUT = 2;
    /** How close a baked curve stays: this fraction of the range it moves over. */
    static final double TOL = 0.02;
    /** How close a quick event stays: this fraction of its own size (抖动 30 → within 0.9). */
    static final double EFFECT_TOL = 0.03;
    /** The exponent of a jump at the end of an event (阶跃缓入): flat to the eye until the last moment. */
    static final float JUMP_EXP = 1000f;

    /** @return the eased progress of curve {@code c}, shape {@code sh}, at {@code p} in [0, 1] (回弹, 弹性 overshoot). */
    static double ease(int c, int sh, double p) {
        p = Math.max(0.0, Math.min(1.0, p));
        switch (sh) {
            case IN: return in(c, p);
            case OUT: return 1.0 - in(c, 1.0 - p);
            default: return p < 0.5 ? in(c, 2.0 * p) / 2.0 : 1.0 - in(c, 2.0 - 2.0 * p) / 2.0;
        }
    }

    /** The 缓入 form of each family. */
    private static double in(int c, double p) {
        switch (c) {
            case SINE: return 1.0 - Math.cos(p * Math.PI / 2.0);
            case 2: return p * p;
            case 3: return p * p * p;
            case 4: return p * p * p * p;
            case QUINT: return p * p * p * p * p;
            case EXPO: return p <= 0.0 ? 0.0 : Math.pow(2.0, 10.0 * p - 10.0);
            case 7: return 1.0 - Math.sqrt(Math.max(0.0, 1.0 - p * p));
            case BACK: return 2.70158 * p * p * p - 1.70158 * p * p;
            case ELASTIC:
                return p <= 0.0 ? 0.0 : p >= 1.0 ? 1.0
                    : -Math.pow(2.0, 10.0 * p - 10.0) * Math.sin((p * 10.0 - 10.75) * 2.0 * Math.PI / 3.0);
            case BOUNCE: return 1.0 - bounceOut(1.0 - p);
            case STEP: return p >= 1.0 ? 1.0 : 0.0;
            default: return p;
        }
    }

    private static double bounceOut(double p) {
        double n = 7.5625, d = 2.75;
        if (p < 1.0 / d) return n * p * p;
        if (p < 2.0 / d) return n * (p -= 1.5 / d) * p + 0.75;
        if (p < 2.5 / d) return n * (p -= 2.25 / d) * p + 0.9375;
        return n * (p -= 2.625 / d) * p + 0.984375;
    }

    /** @return the one exponent that is curve {@code c} with shape {@code sh} exactly, or NaN when it must be baked. */
    static float exactExp(int c, int sh) {
        if (c == LINEAR) return 1f;
        if (sh == IN && c >= 2 && c <= QUINT) return c;
        return Float.NaN;
    }

    /**
     * @return the events that move like {@code e} (its beats and values) along curve {@code c}, shape {@code sh}: the
     *         event itself with a new exponent when the curve is a power curve, else several baked ones; each keeps
     *         {@code e}'s 相对 flag, a baked one is no longer a flow fall or linked
     */
    static List<Doc.Ev> curve(Doc.Ev e, int c, int sh) {
        ArrayList<Doc.Ev> out = new ArrayList<>();
        float ex = exactExp(c, sh);
        if (!Float.isNaN(ex) || e.end <= e.start) {
            Doc.Ev x = e.copy();
            x.exp = Float.isNaN(ex) ? 1f : ex;
            out.add(x);
            return out;
        }
        if (c == STEP) {                       // a jump: at the end, at the start, or halfway
            float m = sh == IN ? e.end : sh == OUT ? e.start : (e.start + e.end) / 2f;
            if (m > e.start) {
                out.add(new Doc.Ev(e.start, m, e.from, sh == IN ? e.to : e.from, sh == IN ? JUMP_EXP : 1f));
            }
            if (m < e.end) out.add(new Doc.Ev(m, e.end, e.to, e.to, 1f));
        } else {
            double s = e.start, d = e.end - e.start, a = e.from, b = e.to - e.from;
            bake(t -> a + b * ease(c, sh, (t - s) / d), e.start, e.end, out);
        }
        for (Doc.Ev x : out) x.rel = e.rel;
        return out;
    }

    // ================================================================== baking

    /** A value by beat. */
    interface Fn {
        double at(double t);
    }

    /**
     * Appends events approximating {@code f} on [{@code t0}, {@code t1}] to {@code out}, in order: cut where
     * {@code f} turns back, then each piece halved until one event's best exponent stays within {@link #TOL} of
     * {@code f}'s range (pieces at least 1/64 of the span). {@code f} must be continuous on the span.
     */
    static void bake(Fn f, double t0, double t1, List<Doc.Ev> out) {
        bake(f, t0, t1, Double.NaN, out);
    }

    /** As {@link #bake(Fn, double, double, List)}, within {@code tol} (NaN = {@link #TOL} of the range). */
    static void bake(Fn f, double t0, double t1, double tol, List<Doc.Ev> out) {
        if (!(t1 > t0)) return;
        int n = 256;
        double[] ts = new double[n + 1], vs = new double[n + 1];
        double lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE;
        for (int i = 0; i <= n; i++) {
            ts[i] = i == n ? t1 : t0 + (t1 - t0) * i / n;
            vs[i] = f.at(ts[i]);
            lo = Math.min(lo, vs[i]);
            hi = Math.max(hi, vs[i]);
        }
        if (Double.isNaN(tol)) tol = Math.max(1e-4, (hi - lo) * TOL);
        double eps = (hi - lo) * 1e-7;
        double cut = t0;
        int dir = 0;
        for (int i = 1; i <= n; i++) {
            double dv = vs[i] - vs[i - 1];
            int sg = dv > eps ? 1 : dv < -eps ? -1 : 0;
            if (sg != 0 && dir != 0 && sg != dir) {    // turned back at sample i − 1
                piece(f, cut, ts[i - 1], tol, 0, out);
                cut = ts[i - 1];
            }
            if (sg != 0) dir = sg;
        }
        piece(f, cut, t1, tol, 0, out);
    }

    private static void piece(Fn f, double a, double b, double tol, int depth, List<Doc.Ev> out) {
        double va = f.at(a), vb = f.at(b);
        double ex = Math.abs(vb - va) < 1e-9 ? 1.0 : bestExp(f, a, b, va, vb);
        if (depth >= 6 || err(f, a, b, va, vb, ex) <= tol) {
            out.add(new Doc.Ev((float) a, (float) b, round(va), round(vb), roundExp(ex)));
            return;
        }
        double m = (a + b) / 2.0;
        piece(f, a, m, tol, depth + 1, out);
        piece(f, m, b, tol, depth + 1, out);
    }

    /** @return the exponent in [1/64, 64] whose curve strays least from {@code f} (golden section on its log). */
    private static double bestExp(Fn f, double a, double b, double va, double vb) {
        double lo = Math.log(1.0 / 64.0), hi = Math.log(64.0), g = (Math.sqrt(5.0) - 1.0) / 2.0;
        double x1 = hi - g * (hi - lo), x2 = lo + g * (hi - lo);
        double f1 = err(f, a, b, va, vb, Math.exp(x1)), f2 = err(f, a, b, va, vb, Math.exp(x2));
        for (int k = 0; k < 36; k++) {
            if (f1 < f2) {
                hi = x2;
                x2 = x1;
                f2 = f1;
                x1 = hi - g * (hi - lo);
                f1 = err(f, a, b, va, vb, Math.exp(x1));
            } else {
                lo = x1;
                x1 = x2;
                f1 = f2;
                x2 = lo + g * (hi - lo);
                f2 = err(f, a, b, va, vb, Math.exp(x2));
            }
        }
        return Math.exp((lo + hi) / 2.0);
    }

    private static double err(Fn f, double a, double b, double va, double vb, double ex) {
        double m = 0.0;
        for (int i = 1; i < 16; i++) {
            double p = i / 16.0;
            m = Math.max(m, Math.abs(f.at(a + (b - a) * p) - (va + (vb - va) * Math.pow(p, ex))));
        }
        return m;
    }

    /** Values to 3 decimals, so baked events read cleanly in the file. */
    static float round(double v) {
        return (float) (Math.rint(v * 1000.0) / 1000.0);
    }

    private static float roundExp(double ex) {
        double r = ex >= 1.0 ? Math.rint(ex * 100.0) / 100.0 : Math.rint(ex * 1000.0) / 1000.0;
        return (float) Math.max(r, 1.0 / 64.0);
    }

    // ================================================================== 快捷事件

    /** 快捷事件 kinds, in menu order. */
    static final String[] EVENTS = {"抖动", "摆动", "弹跳", "脉冲", "闪烁", "往返", "渐变", "阶梯", "保持"};
    static final int SHAKE = 0, SWAY = 1, HOP = 2, PULSE = 3, BLINK = 4, THERE_BACK = 5, RAMP = 6, STAIRS = 7, HOLD = 8;
    /** What each kind does, shown under its form. */
    static final String[] EVENT_HELP = {
        "来回抖：幅度是离原来的值多远，抖完回到原来的值",
        "像正弦波一样来回摆：圈数 = 摆几个来回",
        "像球落地一样弹：高度是第一下，之后每下乘「每次剩」",
        "鼓起来再落回去，一段里鼓几下（快起慢落）",
        "一段里闪几下：一下变成「闪成」的值再回来，或者每下从原来的值渐暗到它（透明 0 = 看不见）；最后可以保持在「闪成」",
        "按曲线变过去再按同样的曲线变回来",
        "按曲线变化，之后可以保持几拍（事件外会回到原来的值）",
        "一级一级跳：从拍那一刻跳第一级，最后一级保持到这段结束",
        "这一段一直是同一个值（事件外的值不对时用它顶住）"};
    static final String[] GROW = {"不变", "渐大", "渐小"};

    /** The numbers of a quick event; what {@link #count}, {@link #amount}, {@link #ratio} mean depends on the kind. */
    static final class Params {
        float beats = 2f, count = 4f, amount = 60f, ratio = 0.5f, hold;
        int grow, curve = SINE, shape = OUT;
        /** 闪烁: 0 = on and off (一下灭一下亮), 1 = each one fades to the value (每下渐暗). */
        int style;

        Params copy() {
            Params p = new Params();
            p.beats = beats;
            p.count = count;
            p.amount = amount;
            p.ratio = ratio;
            p.hold = hold;
            p.grow = grow;
            p.curve = curve;
            p.shape = shape;
            p.style = style;
            return p;
        }
    }

    /**
     * @param under what the channel does now (0 for a 叠加层): the motion goes on top of it, except 闪烁 and 保持,
     *              whose values are what the channel shows
     * @return the events of quick event {@code kind} from beat {@code t0} for {@code q.beats} beats (and its 保持 after),
     *         in order, or null with the reason in {@link #why} when its numbers make no sense
     */
    static List<Doc.Ev> make(int kind, Params q, double t0, Fn under) {
        why = null;
        double len = q.beats, t1 = t0 + len;
        if (!(len > 0.0)) return fail("拍数要大于 0");
        int n = Math.max(1, Math.round(q.count));
        ArrayList<Doc.Ev> out = new ArrayList<>();
        double a = Double.isNaN(q.amount) ? under.at(t0) : q.amount;     // 保持 left empty = the value now
        double tol = Math.max(1e-3, Math.abs(kind == BLINK ? a - under.at(t0) : a) * EFFECT_TOL);
        switch (kind) {
            case SHAKE: {                                   // a zigzag: n swings of ±amount, back to 0
                for (int k = 0; k <= n; k++) {
                    double ta = t0 + len * k / (n + 1), tb = t0 + len * (k + 1) / (n + 1);
                    double oa = shakeAt(k, n, a, q.grow), ob = shakeAt(k + 1, n, a, q.grow);
                    bake(t -> under.at(t) + oa + (ob - oa) * (t - ta) / (tb - ta), ta, tb, tol, out);
                }
                break;
            }
            case SWAY:
                if (!(q.count > 0f)) return fail("圈数要大于 0");
                bake(t -> {
                    double p = (t - t0) / len;
                    return under.at(t) + a * grow(q.grow, p) * Math.sin(2.0 * Math.PI * q.count * p);
                }, t0, t1, tol, out);
                break;
            case HOP: {                                     // parabolas, each lasting as √ its height
                if (!(q.ratio > 0f)) return fail("每次剩要大于 0");
                double[] at = new double[n + 1], h = new double[n];
                double sum = 0.0;
                for (int k = 0; k < n; k++) {
                    h[k] = a * Math.pow(q.ratio, k);
                    sum += Math.sqrt(Math.abs(h[k]));
                }
                if (!(sum > 0.0)) return fail("高度不能是 0");
                at[0] = t0;
                for (int k = 0; k < n; k++) at[k + 1] = at[k] + len * Math.sqrt(Math.abs(h[k])) / sum;
                at[n] = t1;
                bake(t -> {
                    int k = 0;
                    while (k < n - 1 && t > at[k + 1]) k++;
                    double s = (t - at[k]) / (at[k + 1] - at[k]);
                    return under.at(t) + 4.0 * h[k] * s * (1.0 - s);
                }, t0, t1, tol, out);
                break;
            }
            case PULSE:                                     // per slot: up in its first quarter, down in the rest
                bake(t -> {
                    double p = Math.min(n - 1e-9, Math.max(0.0, (t - t0) / len * n)), s = p - Math.floor(p);
                    double o = s < 0.25 ? ease(QUAD, OUT, s / 0.25) : 1.0 - ease(SINE, IN_OUT, (s - 0.25) / 0.75);
                    return under.at(t) + a * o;
                }, t0, t1, tol, out);
                break;
            case BLINK:                                     // per slot: the value then what it was, or a fade
                for (int k = 0; k < n; k++) {
                    double sa = t0 + len * k / n, sb = t0 + len * (k + 1) / n, sm = (sa + sb) / 2.0;
                    if (q.style == 1) {
                        bake(t -> under.at(t) + (a - under.at(t)) * (t - sa) / (sb - sa), sa, sb, tol, out);
                        continue;
                    }
                    out.add(new Doc.Ev((float) sa, (float) sm, round(a), round(a), 1f));
                    bake(under, sm, sb, tol, out);
                }
                break;
            case THERE_BACK: {
                double tm = t0 + len / 2.0;
                if (q.curve == STEP) {                      // jump there, jump back halfway
                    bake(t -> under.at(t) + a, t0, tm, tol, out);
                    bake(under, tm, t1, out);
                    break;
                }
                bake(t -> under.at(t) + a * ease(q.curve, q.shape, (t - t0) / (tm - t0)), t0, tm, tol, out);
                bake(t -> under.at(t) + a * ease(q.curve, q.shape, (t1 - t) / (t1 - tm)), tm, t1, tol, out);
                break;
            }
            case RAMP:
                if (isFlat(under, t0, t1)) {
                    Doc.Ev e = new Doc.Ev((float) t0, (float) t1, round(under.at(t0)), round(under.at(t0) + a), 1f);
                    out.addAll(curve(e, q.curve, q.shape));
                } else if (q.curve == STEP) {               // at the end (1/16 before it), at the start, or halfway
                    double m = q.shape == IN ? t1 - len / 16.0 : q.shape == OUT ? t0 : t0 + len / 2.0;
                    bake(under, t0, m, out);
                    bake(t -> under.at(t) + a, m, t1, tol, out);
                } else {
                    bake(t -> under.at(t) + a * ease(q.curve, q.shape, (t - t0) / len), t0, t1, tol, out);
                }
                break;
            case STAIRS:                                    // jump k + 1 at the start of slot k, hold
                for (int k = 0; k < n; k++) {
                    double o = a * (k + 1) / n;
                    bake(t -> under.at(t) + o, t0 + len * k / n, t0 + len * (k + 1) / n, tol, out);
                }
                break;
            case HOLD:
                out.add(new Doc.Ev((float) t0, (float) t1, round(a), round(a), 1f));
                break;
            default:
                return fail("没有这种快捷事件");
        }
        if ((kind == RAMP || kind == STAIRS) && q.hold > 0f) {     // keep the end where it got to
            double o = a;
            bake(t -> under.at(t) + o, t1, t1 + q.hold, tol, out);
        } else if (kind == BLINK && q.hold > 0f) {                  // stay blinked out
            out.add(new Doc.Ev((float) t1, (float) (t1 + q.hold), round(a), round(a), 1f));
        }
        return out;
    }

    /** Why the last {@link #make} gave null. */
    static String why;

    private static List<Doc.Ev> fail(String w) {
        why = w;
        return null;
    }

    /** @return knot {@code k} of a shake of {@code n} swings: 0 at both ends, then +, −, + … grown as asked. */
    private static double shakeAt(int k, int n, double a, int grow) {
        if (k <= 0 || k > n) return 0.0;
        double w = grow == 1 ? (double) k / n : grow == 2 ? (double) (n + 1 - k) / n : 1.0;
        return (k % 2 == 1 ? a : -a) * w;
    }

    private static double grow(int grow, double p) {
        return grow == 1 ? p : grow == 2 ? 1.0 - p : 1.0;
    }

    /** @return true when {@code f} does not change over [t0, t1]. */
    private static boolean isFlat(Fn f, double t0, double t1) {
        double v = f.at(t0);
        for (int i = 1; i <= 32; i++) if (Math.abs(f.at(t0 + (t1 - t0) * i / 32.0) - v) > 1e-6) return false;
        return true;
    }

    // ================================================================== putting events in

    /**
     * @return channel {@code list}'s value at {@code t}: outside every event its {@code base}; when {@code held}
     *         (a floating key's x, y and size, an orbit centre) an event keeps its end value until the next one starts
     */
    static double valueAt(List<Doc.Ev> list, float base, boolean held, double t) {
        Doc.Ev best = null;
        for (Doc.Ev e : list) if (e.start <= t && (best == null || e.start >= best.start)) best = e;
        if (best == null) return base;
        if (t <= best.end) return best.valueAt((float) t);
        return held ? best.to : base;
    }

    /** @return what a 叠加层 list adds at {@code t} (every event running then, summed). */
    static double layerAt(List<Doc.Ev> lay, double t) {
        return Doc.layerAt(lay, (float) t);
    }

    /**
     * Puts {@code made} (events on [{@code t0}, {@code t1}], in order) into channel {@code list}: the events inside the
     * span go, one running into it is cut short at {@code t0} (exactly: a power curve cut short is still one) and one
     * running on past {@code t1} keeps its rest, baked again from {@code t1}. A 叠加层 list adds up, so there the new
     * events are only added. The list stays sorted by start.
     */
    static void splice(List<Doc.Ev> list, double t0, double t1, List<Doc.Ev> made, boolean layer) {
        if (!layer) {
            ArrayList<Doc.Ev> rest = new ArrayList<>();
            for (int i = list.size() - 1; i >= 0; i--) {
                Doc.Ev e = list.get(i);
                if (e.end <= t0 || e.start >= t1) continue;
                list.remove(i);
                if (e.end > t1) {                                     // the part after the span
                    ArrayList<Doc.Ev> tail = new ArrayList<>();
                    bake(t -> e.valueAt((float) t), t1, e.end, tail);
                    for (Doc.Ev x : tail) x.rel = e.rel;
                    rest.addAll(tail);
                }
                if (e.start < t0) {                                   // the part before it, exact
                    Doc.Ev h = e.copy();
                    float p = (float) ((t0 - e.start) / (e.end - e.start));
                    h.to = e.from + (e.to - e.from) * (float) Math.pow(p, e.exp);
                    h.end = (float) t0;
                    h.flow = Float.NaN;
                    h.link = null;
                    list.add(i, h);
                }
            }
            list.addAll(rest);
        }
        list.addAll(made);
        list.sort((a, b) -> Float.compare(a.start, b.start));
    }

    /** Takes out of {@code list} everything before beat {@code t}; one running across it keeps its rest, baked. */
    static void dropBefore(List<Doc.Ev> list, double t) {
        ArrayList<Doc.Ev> rest = new ArrayList<>();
        for (int i = list.size() - 1; i >= 0; i--) {
            Doc.Ev e = list.get(i);
            if (e.start >= t) continue;
            list.remove(i);
            if (e.end > t) {
                ArrayList<Doc.Ev> tail = new ArrayList<>();
                bake(x -> e.valueAt((float) x), t, e.end, tail);
                for (Doc.Ev x : tail) x.rel = e.rel;
                rest.addAll(tail);
            }
        }
        list.addAll(rest);
        list.sort((a, b) -> Float.compare(a.start, b.start));
    }
}
