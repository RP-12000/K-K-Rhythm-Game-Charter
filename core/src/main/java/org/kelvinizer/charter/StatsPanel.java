package org.kelvinizer.charter;

import com.badlogic.gdx.graphics.Color;
import org.kelvinizer.elements.Chart;
import org.kelvinizer.elements.Line;
import org.kelvinizer.elements.Note;
import org.kelvinizer.elements.NoteType;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;

/**
 * 谱面统计面板：点状态栏右下角的「统计」打开，列出各类音符、事件的数量和其他统计（Kelvin 2026-10-13）。
 * The chart statistics panel, opened by the 统计 button at the right end of the editor's status
 * bar: notes by type and line, the game's score note count and sync notes, events by kind, and
 * lanes, tempo segments, trails and glitches, length and density. Note and event counts come from the editor's
 * chart; the score count and the sync notes from the chart the game's loader built for the
 * preview; 假键 are left out of both. Everything is recounted only when the chart changes.
 */
final class StatsPanel {
    static final float W = 440f, H = 648f;
    /** Rows of the note table: type, and whether it is the form with a tail. */
    private static final NoteType[] TYPES = {NoteType.TAP, NoteType.HOLD, NoteType.UNIVERSAL, NoteType.UNIVERSAL,
        NoteType.MINE, NoteType.MINE, NoteType.TIDE_MINE, NoteType.TIDE_MINE};
    private static final boolean[] TAIL = {false, true, false, true, false, true, false, true};
    private static final String[] EV_NAMES = {"移动", "长度", "透明度", "旋转"};

    private Doc doc;
    private Chart chart;
    private int version = -1;
    /** Note counts [row][line], lower line 0, upper line 1. */
    private final int[][] notes = new int[TYPES.length][2];
    private int scored, syncNotes, syncBeats, trails, glitches;
    /** Note events by kind (movement, length, opacity, rotation) and the relative ones among them. */
    private final int[] noteEv = new int[4], noteRel = new int[4];
    private int laneEv, scaleEv, cameraEv, lineEv;
    /** First judgement to last end, judged notes per second on average and in the densest second. */
    private double lengthMs, perSecond;
    private int peak;

    private void count(Session s) {
        doc = s.doc;
        chart = s.chart;
        version = s.version;
        for (int[] r : notes) Arrays.fill(r, 0);
        Arrays.fill(noteEv, 0);
        Arrays.fill(noteRel, 0);
        trails = 0;
        glitches = 0;
        double first = Double.MAX_VALUE, last = -Double.MAX_VALUE;
        double[] judged = new double[doc.notes.size()];
        int nj = 0;
        for (Doc.DNote n : doc.notes) {
            boolean fake = n.fake && n.canFake();                   // a 假键 is drawn only (game 7.167)
            if (!fake) notes[row(n)][n.line == Line.UPPER ? 1 : 0]++;
            List<?>[] evs = {n.move, n.heightEv, n.alpha, n.rot};
            for (int k = 0; k < 4; k++) {
                noteEv[k] += evs[k].size();
                for (Object e : evs[k]) if (((Doc.Ev) e).rel) noteRel[k]++;
            }
            if (!n.trails.isEmpty()) trails++;
            if (!n.glitches.isEmpty()) glitches++;
            double ms = s.beatToMs(n.judgement);
            first = Math.min(first, ms);
            last = Math.max(last, s.beatToMs(n.end()));
            if (n.type.judged() && !fake) judged[nj++] = ms;
        }
        laneEv = 0;
        for (Doc.DLane l : doc.lanes) laneEv += l.widthEv.size() + l.opacityEv.size();
        scaleEv = doc.laneScaleEv.size();
        cameraEv = doc.cameraFrameEv.size();
        lineEv = doc.upperEv.size() + doc.lowerEv.size();
        lengthMs = doc.notes.isEmpty() ? 0.0 : last - first;
        perSecond = lengthMs > 0.0 ? nj / (lengthMs / 1000.0) : 0.0;
        Arrays.sort(judged, 0, nj);
        peak = 0;
        for (int i = 0, j = 0; i < nj; i++) {
            while (judged[i] - judged[j] >= 1000.0) j++;
            peak = Math.max(peak, i - j + 1);
        }
        scored = -1;
        syncNotes = 0;
        syncBeats = 0;
        if (chart != null) {
            scored = chart.totalNotes();
            HashSet<Float> beats = new HashSet<>();
            for (Note n : chart.notes()) {
                if (n.judgement() >= Session.SENTINEL || !n.sharesBeat()) continue;
                syncNotes++;
                beats.add(n.judgement());
            }
            syncBeats = beats.size();
        }
    }

    /** One line for the self-test log. */
    String summary() {
        int all = 0, ev = laneEv + scaleEv + cameraEv + lineEv;
        for (int[] r : notes) all += r[0] + r[1];
        for (int k = 0; k < 4; k++) ev += noteEv[k];
        return "notes " + all + ", scored " + scored + ", sync " + syncNotes + " on " + syncBeats
            + " beats, movement events " + noteEv[0] + " (relative " + noteRel[0] + "), events " + ev
            + ", densest second " + peak;
    }

    private static int row(Doc.DNote n) {
        switch (n.type) {
            case TAP: return 0;
            case HOLD: return 1;
            case UNIVERSAL: return n.duration > 0f ? 3 : 2;
            case TIDE_MINE: return n.duration > 0f ? 7 : 6;
            default: return n.duration > 0f ? 5 : 4;
        }
    }

    /** Draws the panel with its top-left corner at (x, y). */
    void draw(Gfx g, Session s, float x, float y) {
        if (s.doc != doc || s.chart != chart || s.version != version) count(s);
        g.box(x, y, W, H, 10f, Gfx.BAR, Gfx.SEL_LINE);
        float l = x + 22f, c1 = x + 250f, c2 = x + 330f, c3 = x + W - 22f;
        g.text("谱面统计", l, y + 28f, 16, Gfx.INK1);
        g.text(s.song.name() + " · " + Gfx.diffName(s.diff), c3, y + 28f, 12, Gfx.INK3, 2);
        float ry = y + 66f;
        g.text("音符", l, ry, 12, Gfx.INK3);
        g.text("下线", c1, ry, 12, Gfx.INK3, 2);
        g.text("上线", c2, ry, 12, Gfx.INK3, 2);
        g.text("合计", c3, ry, 12, Gfx.INK3, 2);
        int lower = 0, upper = 0;
        for (int i = 0; i < TYPES.length; i++) {
            ry += 22f;
            g.text(TYPES[i].displayZh(TAIL[i]), l, ry, 13, Gfx.INK2);
            num(g, notes[i][0], c1, ry, Gfx.INK2);
            num(g, notes[i][1], c2, ry, Gfx.INK2);
            num(g, notes[i][0] + notes[i][1], c3, ry, Gfx.INK1);
            lower += notes[i][0];
            upper += notes[i][1];
        }
        ry += 24f;
        g.line(l, ry - 12f, c3, ry - 12f, 1f, Gfx.LINE);
        g.text("合计", l, ry, 13, Gfx.INK1);
        num(g, lower, c1, ry, Gfx.INK1);
        num(g, upper, c2, ry, Gfx.INK1);
        num(g, lower + upper, c3, ry, Gfx.INK1);
        ry += 26f;
        g.text("物量（计分的音符，不含水雷）", l, ry, 13, Gfx.INK2);
        g.text(scored >= 0 ? String.valueOf(scored) : "—", c3, ry, 13, Gfx.INK1, 2);
        ry += 22f;
        g.text("sync（同一拍还有别的音符）", l, ry, 13, Gfx.INK2);
        g.text(chart != null ? syncNotes + " 个 · " + syncBeats + " 拍" : "—", c3, ry, 13, Gfx.INK1, 2);

        ry += 36f;
        g.text("事件", l, ry, 12, Gfx.INK3);
        g.text("数量", c3, ry, 12, Gfx.INK3, 2);
        int total = 0;
        for (int k = 0; k < 4; k++) {
            ry += 22f;
            g.text("音符" + EV_NAMES[k], l, ry, 13, Gfx.INK2);
            if (noteEv[k] > 0) g.text("相对 " + noteRel[k] + " · 绝对 " + (noteEv[k] - noteRel[k]), x + 140f,
                ry, 12, Gfx.INK3);
            num(g, noteEv[k], c3, ry, Gfx.INK1);
            total += noteEv[k];
        }
        String[] names = {"轨道宽度 / 透明度", "全局缩放", "镜头平移", "判定线位置"};
        int[] counts = {laneEv, scaleEv, cameraEv, lineEv};
        for (int k = 0; k < names.length; k++) {
            ry += 22f;
            g.text(names[k], l, ry, 13, Gfx.INK2);
            num(g, counts[k], c3, ry, Gfx.INK1);
            total += counts[k];
        }
        ry += 24f;
        g.line(l, ry - 12f, c3, ry - 12f, 1f, Gfx.LINE);
        g.text("事件总数", l, ry, 13, Gfx.INK1);
        num(g, total, c3, ry, Gfx.INK1);

        ry += 36f;
        String lanes = doc.laneCount > 0 ? "有限轨 " + doc.laneCount + " 条" : "无限轨（写出 " + doc.lanes.size() + " 条）";
        g.text(g.fit("轨道 " + lanes + " · 变速 " + doc.bpms.size() + " 段 · 拖影 " + trails + " 个 · 故障 " + glitches + " 个",
            12, W - 44f), l, ry, 12, Gfx.INK2);
        ry += 22f;
        long sec = Math.round(lengthMs / 1000.0);
        g.text(g.fit("时长 " + sec / 60 + ":" + String.format(Locale.ROOT, "%02d", sec % 60) + " · 平均每秒 "
            + String.format(Locale.ROOT, "%.1f", perSecond) + " 个 · 最密的 1 秒 " + peak + " 个", 12, W - 44f), l,
            ry, 12, Gfx.INK2);
    }

    private static void num(Gfx g, int n, float right, float cy, Color c) {
        g.text(String.valueOf(n), right, cy, 13, n == 0 ? Gfx.INK3 : c, 2);
    }
}
