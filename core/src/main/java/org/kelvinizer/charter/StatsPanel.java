package org.kelvinizer.charter;

import com.badlogic.gdx.graphics.Color;
import org.kelvinizer.elements.KeyKind;
import org.kelvinizer.elements.Lane7;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Locale;

/**
 * 谱面统计面板：点状态栏右下角的「统计」打开，列出各种键的数量、函数分段数和其他统计。
 * The chart statistics panel, opened by the 统计 button at the right end of the status bar: keys by kind and face,
 * the score count and sync keys, segments of the lanes', keys', camera's and volumes' functions, lanes, tempo
 * segments, length and density; 假键 apart (they are only drawn). Everything is recounted only when the chart changes.
 */
final class StatsPanel {
    static final float W = 440f, H = 570f;
    private static final KeyKind[] KINDS = {KeyKind.TAP, KeyKind.STREAM, KeyKind.DRAG, KeyKind.WAVE, KeyKind.TIDE,
        KeyKind.MINE, KeyKind.STREAM_MINE, KeyKind.WAVE_MINE, KeyKind.TIDE_MINE};

    private Doc doc;
    private int version = -1;
    /** Key counts [row][face], 底面 0, 天面 1. */
    private final int[][] keys = new int[KINDS.length][2];
    private int scored, syncKeys, syncBeats, veils, curtains, fakes;
    /** Segments of the lanes', keys', camera's and volumes' functions. */
    private final int[] segs = new int[4];
    /** First judgement to last end, judged keys per second on average and in the densest second. */
    private double lengthMs, perSecond;
    private int peak;

    private void count(Session s) {
        doc = s.doc;
        version = s.version;
        for (int[] r : keys) Arrays.fill(r, 0);
        Arrays.fill(segs, 0);
        scored = syncKeys = syncBeats = veils = curtains = fakes = 0;
        double first = Double.MAX_VALUE, last = -Double.MAX_VALUE;
        double[] judged = new double[doc.keys.size()];
        int nj = 0;
        HashMap<Long, Integer> at = new HashMap<>();
        for (Doc.DKey dk : doc.keys) {
            segs[1] += Doc.segCount(dk.k.h, dk.k.H, dk.k.o);
            if (dk.k.fake) {                                            // 假键: not keys of the chart, only drawn
                fakes++;
                continue;
            }
            keys[row(dk.k.kind)][dk.k.sky ? 1 : 0]++;
            float j = dk.judge();
            if (Float.isNaN(j)) continue;
            double ms = doc.msOf(j);
            first = Math.min(first, ms);
            last = Math.max(last, doc.msOf(dk.end()));
            judged[nj++] = ms;
            if (dk.k.kind.mine()) continue;
            scored++;
            at.merge(Math.round(j * 960.0), 1, Integer::sum);
        }
        for (int n : at.values()) {
            if (n < 2) continue;
            syncKeys += n;
            syncBeats++;
        }
        for (Lane7 l : doc.lanes) segs[0] += Doc.segCount(Doc.fns(l));
        segs[2] = Doc.segCount(doc.camera.x, doc.camera.y, doc.camera.z, doc.fov);
        for (Doc.DVol v : doc.vols) {
            segs[3] += Doc.segCount(Doc.fns(v.v));
            if (v.v.curtain) curtains++;
            else veils++;
        }
        lengthMs = nj > 0 ? Math.max(0, last - first) : 0;
        perSecond = lengthMs > 0 ? nj / (lengthMs / 1000.0) : 0;
        Arrays.sort(judged, 0, nj);
        peak = 0;
        for (int i = 0, k = 0; i < nj; i++) {
            while (judged[i] - judged[k] >= 1000.0) k++;
            peak = Math.max(peak, i - k + 1);
        }
    }

    private static int row(KeyKind k) {
        for (int i = 0; i < KINDS.length; i++) if (KINDS[i] == k) return i;
        return 0;
    }

    /** Draws the panel with its top-left corner at (x, y). */
    void draw(Gfx g, Session s, float x, float y) {
        if (s.doc != doc || s.version != version) count(s);
        g.box(x, y, W, H, 10f, Gfx.BAR, Gfx.SEL_LINE);
        float l = x + 22f, c1 = x + 250f, c2 = x + 330f, c3 = x + W - 22f;
        g.text("谱面统计", l, y + 28f, 16, Gfx.INK1);
        g.text(s.song.name() + " · " + Gfx.diffName(s.diff), c3, y + 28f, 12, Gfx.INK3, 2);
        float ry = y + 66f;
        g.text("键", l, ry, 12, Gfx.INK3);
        g.text("底面", c1, ry, 12, Gfx.INK3, 2);
        g.text("天面", c2, ry, 12, Gfx.INK3, 2);
        g.text("合计", c3, ry, 12, Gfx.INK3, 2);
        int lower = 0, upper = 0;
        for (int i = 0; i < KINDS.length; i++) {
            ry += 22f;
            g.text(Inspector.kindName(KINDS[i]), l, ry, 13, Gfx.INK2);
            num(g, keys[i][0], c1, ry, Gfx.INK2);
            num(g, keys[i][1], c2, ry, Gfx.INK2);
            num(g, keys[i][0] + keys[i][1], c3, ry, Gfx.INK1);
            lower += keys[i][0];
            upper += keys[i][1];
        }
        ry += 24f;
        g.line(l, ry - 12f, c3, ry - 12f, 1f, Gfx.LINE);
        g.text("合计", l, ry, 13, Gfx.INK1);
        num(g, lower, c1, ry, Gfx.INK1);
        num(g, upper, c2, ry, Gfx.INK1);
        num(g, lower + upper, c3, ry, Gfx.INK1);
        ry += 26f;
        g.text("物量（计分的键，不含水雷）", l, ry, 13, Gfx.INK2);
        g.text(String.valueOf(scored), c3, ry, 13, Gfx.INK1, 2);
        ry += 22f;
        g.text("sync（同一拍还有别的键）", l, ry, 13, Gfx.INK2);
        g.text(syncKeys + " 个 · " + syncBeats + " 拍", c3, ry, 13, Gfx.INK1, 2);

        ry += 36f;
        g.text("函数分段", l, ry, 12, Gfx.INK3);
        g.text("数量", c3, ry, 12, Gfx.INK3, 2);
        String[] names = {"轨道（u v 宽 长 S s F f）", "键（h H）", "镜头 P", "漩涡 / 水帘（V p）"};
        int total = 0;
        for (int k = 0; k < names.length; k++) {
            ry += 22f;
            g.text(names[k], l, ry, 13, Gfx.INK2);
            num(g, segs[k], c3, ry, Gfx.INK1);
            total += segs[k];
        }
        ry += 24f;
        g.line(l, ry - 12f, c3, ry - 12f, 1f, Gfx.LINE);
        g.text("分段总数", l, ry, 13, Gfx.INK1);
        num(g, total, c3, ry, Gfx.INK1);

        ry += 36f;
        g.text(g.fit("轨道 " + doc.lanes.size() + " 条 · 变速 " + doc.bpms.size() + " 段 · 漩涡 " + veils + " 个 · 水帘 "
            + curtains + " 个" + (fakes > 0 ? " · 假键 " + fakes + " 个" : ""), 12, W - 44f), l, ry, 12, Gfx.INK2);
        ry += 22f;
        long sec = Math.round(lengthMs / 1000.0);
        g.text(g.fit("时长 " + sec / 60 + ":" + String.format(Locale.ROOT, "%02d", sec % 60) + " · 平均每秒 "
            + String.format(Locale.ROOT, "%.1f", perSecond) + " 个 · 最密的 1 秒 " + peak + " 个", 12, W - 44f), l,
            ry, 12, Gfx.INK2);
    }

    /** @return a one-line summary for the self-test log */
    String summary(Session s) {
        if (s.doc != doc || s.version != version) count(s);
        int all = 0;
        for (int[] r : keys) all += r[0] + r[1];
        return "keys " + all + ", fake " + fakes + ", scored " + scored + ", sync " + syncKeys + " on " + syncBeats
            + " beats, segments "
            + (segs[0] + segs[1] + segs[2] + segs[3]) + ", densest second " + peak;
    }

    private static void num(Gfx g, int n, float right, float cy, Color c) {
        g.text(String.valueOf(n), right, cy, 13, n == 0 ? Gfx.INK3 : c, 2);
    }
}
