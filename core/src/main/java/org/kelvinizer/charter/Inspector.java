package org.kelvinizer.charter;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Cursor;
import org.kelvinizer.elements.Chart;
import org.kelvinizer.elements.ChartMapper;
import org.kelvinizer.elements.Lane;
import org.kelvinizer.elements.LaneLayout;
import org.kelvinizer.elements.Line;
import org.kelvinizer.elements.Note;
import org.kelvinizer.elements.NoteDistortion;
import org.kelvinizer.elements.NoteGlitch;
import org.kelvinizer.elements.NoteOrbit;
import org.kelvinizer.elements.NoteTrail;
import org.kelvinizer.elements.NoteType;
import org.kelvinizer.elements.Veil;
import org.kelvinizer.gameplay.GameEngine;
import org.kelvinizer.library.Difficulty;

import java.util.ArrayList;
import java.util.List;

/**
 * 右边的属性面板：「谱面」（歌曲信息、难度、变速、检查）、「音符」（类型、时间、运动事件、拖拽残影、故障、扭曲）、
 * 「场地」（轨道、缩放、镜头平移、判定线）、「漩涡」和「水帘」。滚轮、滚动条或按住空白处拖动都能滚动。
 * The inspector on the right with five tabs: 谱面 (song texts, difficulty, tempo
 * map, offset, checks), 音符 (type, line, lane, timing, 假键, events, 不跟判定线, trail, glitch and distortion segments),
 * 场地 (lanes, global scale, camera frame, judgement lines), 漩涡 (veils: shape, place, time, events, glitch
 * and distortion) and 水帘 (curtains: the same, plus their blur). Every edit goes through the session's undo. The
 * panel scrolls with the wheel, its scrollbar or a drag on empty space.
 */
final class Inspector {
    static final int CHART = 0, NOTE = 1, FIELD = 2, VEIL = 3, CURTAIN = 4;
    private static final String[] TABS = {"\u8c31\u9762", "\u97f3\u7b26", "\u573a\u5730", "\u6f29\u6da1", "\u6c34\u5e18"};
    private static final String[] LINES = {"上线", "下线"};
    private static final float X = EditorScreen.TR, TOP = 102f, CX = X + 19f, CW = Gfx.W - 18f - CX,
            FX = X + 101f, FW = Gfx.W - 18f - FX;
    private static final int CH_MOVE = 0, CH_ROT = 1, CH_ALPHA = 2, CH_HEIGHT = 3, CH_LANE = 4, CH_LINE = 5,
        CH_VEIL = 6, CH_OFFX = 7, CH_ORBX = 8, CH_ORBY = 9, CH_ORBR = 10, CH_ORBA = 11;
    private static final String[] LINE_IDS = {"f-up", "f-low", "f-cam", "f-scale"};
    /** Start distance of 先快后慢: 1.44 × the default fall, 1555.56 (1400 in the old 1080-high frame). */
    private static final float FAST_SLOW = Doc.SPAWN * 1400f / 972f;
    private static final String[] TEMPLATES = {"正常下落：2 拍内 " + dist(Doc.SPAWN) + " → 0",
        "快速下落：1 拍内 " + dist(Doc.SPAWN) + " → 0", "慢速下落：4 拍内 " + dist(Doc.SPAWN) + " → 0",
        "先快后慢：2 拍内 " + dist(FAST_SLOW) + " → 0", "先慢后快：2 拍内 " + dist(Doc.SPAWN) + " → 0（指数 2）",
        "淡入：接近的前一半透明 0 → 1", "无接近：判定时直接出现在线上"};
    private static final String[] LANE_MODES = {"无限轨", "有限轨"};
    private static final String[] FALL_BY = {"统一拍数", "统一流速"};

    /**
     * Note event beats are shown and typed relative to the judgement, and follow the note when its
     * judgement moves (like the old editor's default); false = absolute beats that stay put.
     */

    private final EditorScreen ed;
    private final Gfx g;
    private final Im im;
    int tab = NOTE;
    final float[] scroll = new float[TABS.length];
    private float contentH;
    /** Panel drag: the thumb's grab offset (BAR) or the last pointer y (PAN). */
    private float grab;
    private static final Object BAR = "insp-bar", PAN = "insp-pan";
    private static final float BAR_W = 14f;
    /** The expanded event list (one at a time) and its selected event. */
    private String open;
    private int evSel = -1;
    private final ArrayList<Long> taps = new ArrayList<>();
    private float tapBpm;
    /** Layout cursor: screen y of the next row. */
    private float y;

    Inspector(EditorScreen ed) {
        this.ed = ed;
        this.g = ed.g;
        this.im = ed.im;
    }

    /** Opens the event list of a field-row bar (a lane id, or {@code LINE_BAR − line channel}). */
    void openLine(int id) {
        open = id > EditorScreen.LINE_BAR ? "f-wev"
            : LINE_IDS[Math.max(0, Math.min(LINE_IDS.length - 1, EditorScreen.LINE_BAR - id))];
        evSel = 0;
    }

    void draw() {
        g.rect(X, 56f, Gfx.W - X, Gfx.H - 56f, Gfx.BAR);
        g.line(X, 56f, X, Gfx.H, 1f, Gfx.LINE);
        float tw = (Gfx.W - X) / TABS.length;
        for (int i = 0; i < TABS.length; i++) {
            float tx = X + i * tw;
            boolean hv = im.hover(tx, 56f, tw, 46f);
            if (hv) im.cursor(Cursor.SystemCursor.Hand);
            if (im.click(new Im.Pair("tab", i), tx, 56f, tw, 46f)) tab = i;
            g.text(TABS[i], tx + tw * 0.5f, 79f, 14, tab == i ? Gfx.INK1 : Gfx.INK2, 1);
            if (tab == i) g.rect(tx, 99f, tw, 2f, Gfx.GND);
        }
        g.line(X, TOP, Gfx.W, TOP, 1f, Gfx.LINE);
        float viewH = Gfx.H - TOP, maxS = Math.max(0f, contentH - viewH);
        if (im.hover(X, TOP, Gfx.W - X, viewH) && im.wheel != 0f) scroll[tab] += im.wheel * 60f;
        // drag the scrollbar (a press on its track centres the thumb there), or the content from empty space
        float bh = maxS > 0f ? viewH * viewH / contentH : viewH;
        boolean onBar = maxS > 0f && im.hover(Gfx.W - BAR_W, TOP, BAR_W, viewH);
        if (onBar) im.cursor(Cursor.SystemCursor.Hand);
        if (onBar && im.pressed && !im.rightPressed) {
            float by = TOP + (viewH - bh) * scroll[tab] / maxS;
            im.active = BAR;
            grab = im.my >= by && im.my < by + bh ? im.my - by : bh * 0.5f;
        }
        if (im.down && BAR.equals(im.active)) {
            if (maxS > 0f) scroll[tab] = (im.my - grab - TOP) / (viewH - bh) * maxS;
        } else if (im.down && PAN.equals(im.active)) {
            scroll[tab] -= im.my - grab;
            grab = im.my;
        }
        scroll[tab] = Math.max(0f, Math.min(scroll[tab], maxS));
        im.pushClip(X, TOP, Gfx.W - X, viewH);
        float y0 = TOP + 14f - scroll[tab];
        y = y0;
        if (tab == CHART) chartTab();
        else if (tab == NOTE) noteTab();
        else if (tab == FIELD) fieldTab();
        else if (tab == VEIL) veilTab(false);
        else if (tab == CURTAIN) veilTab(true);
        contentH = y - y0 + 30f;
        im.popClip();
        // a press no widget took drags the panel
        if (im.pressed && !im.rightPressed && im.active == null && im.hover(X, TOP, Gfx.W - X, viewH)) {
            im.active = PAN;
            grab = im.my;
        }
        if (contentH > viewH) {
            float nh = viewH * viewH / contentH, by = TOP + (viewH - nh) * scroll[tab] / (contentH - viewH);
            boolean hot = onBar || BAR.equals(im.active);
            g.rrect(Gfx.W - (hot ? 8f : 6f), by, hot ? 5f : 3f, nh, hot ? 2.5f : 1.5f, hot ? Gfx.INK3 : Gfx.SEL_LINE);
        }
    }

    // ================================================================== layout helpers

    private void title(String t, String right) {
        g.text(t, CX, y + 8f, 12, Gfx.INK3);
        if (right != null) g.text(right, CX + CW, y + 8f, 12, Gfx.INK3, 2);
        y += 26f;
    }

    private void divider() {
        y += 10f;
        g.line(X, y, Gfx.W, y, 1f, Gfx.LINE2);
        y += 18f;
    }

    private void label(String t) {
        g.text(t, CX, y + 15f, 13, Gfx.INK2);
    }

    private void caption(String t) {
        y += g.wrap(t, CX, y, CW, 12, Gfx.INK3) + 8f;
    }

    private String text(Object id, String label, String value) {
        label(label);
        String r = im.field(id, FX, y, FW, 30f, value, "", false);
        y += 38f;
        return r;
    }

    private float num(Object id, String label, float value, String unit) {
        label(label);
        float r = im.num(id, FX, y, FW, 30f, value, unit);
        y += 38f;
        return r;
    }

    private static float parseOrNaN(String s) {
        try {
            return s.trim().isEmpty() ? Float.NaN : Float.parseFloat(s.trim());
        } catch (NumberFormatException e) {
            return Float.NaN;
        }
    }

    // ================================================================== 谱面

    private void chartTab() {
        Session s = ed.s;
        Doc d = s.doc;
        title("歌曲（四个难度共用）", null);
        String v;
        if ((v = text("c-title", "曲名", d.title)) != null) {
            s.mark();
            d.title = v.trim();
            s.changed(false);
        }
        if ((v = text("c-artist", "曲师", d.artist)) != null) {
            s.mark();
            d.artist = v.trim();
            s.changed(false);
        }
        if ((v = text("c-ill", "画师", d.illustrator)) != null) {
            s.mark();
            d.illustrator = v.trim();
            s.changed(false);
        }
        if (!s.song.tutorial) preview(s);
        divider();
        title("本难度", null);
        String[] labels = new String[Difficulty.count()];
        for (int i = 0; i < labels.length; i++) {
            labels[i] = (s.song.diffs[i].exists() ? "" : "+ ") + Gfx.diffName(Difficulty.byOrdinal(i));
        }
        int di = im.segmented("c-diff", CX, y, CW, 32f, labels, s.diff.ordinal());
        y += 42f;
        if ((v = text("c-charter", "谱师", d.charter)) != null) {
            s.mark();
            d.charter = v.trim();
            s.changed(false);
        }
        if ((v = text("c-const", "定数", d.constant)) != null) {
            s.mark();
            d.constant = v.trim().isEmpty() ? "1" : v.trim();
            s.changed(false);
        }
        label("迭代次数");
        g.text(String.valueOf(d.iterations), FX, y + 15f, 13, Gfx.INK1);
        y += 30f;
        caption("保存前编辑器会备份旧谱面，每备份一次 +1，也就是打开编辑器后第一次保存这张谱的时候。游戏不读这个数。");
        divider();
        tempo(s, d);
        divider();
        flow(s, d);
        divider();
        checks(s);
        if (di != s.diff.ordinal()) ed.switchDiff(Difficulty.byOrdinal(di));
    }

    /** The song list's preview window by beat (written to song.json at once, not with the chart; Kelvin 2026-10-21). */
    private void preview(Session s) {
        Library.Song so = s.song;
        float a = num("c-pvs", "预览起点", ed.pvBeat(false), "拍");
        if (!Float.isNaN(a)) ed.previewTo(a, Float.NaN);
        float b = num("c-pve", "预览终点", ed.pvBeat(true), "拍");
        if (!Float.isNaN(b)) ed.previewTo(Float.NaN, b);
        float bw = (CW - 16f) / 3f, now = ed.snap((float) s.msToBeat(ed.pb.pos()));
        if (im.button("pv-in", CX, y, bw, 30f, "起点设为播放头", Im.NORMAL, 12)) ed.previewTo(now, Float.NaN);
        if (im.button("pv-out", CX + bw + 8f, y, bw, 30f, "终点设为播放头", Im.NORMAL, 12)) ed.previewTo(Float.NaN, now);
        if (im.button("pv-play", CX + 2f * (bw + 8f), y, bw, 30f, "▶ 试听预览", Im.NORMAL, 12)) ed.listenPreview();
        y += 38f;
        boolean set = !Float.isNaN(so.previewStartMs) || !Float.isNaN(so.previewEndMs);
        if (set && im.button("pv-def", CX + CW - 76f, y, 76f, 24f, "恢复默认", Im.NORMAL, 12)
            && !s.lib.setPreview(so, Float.NaN, Float.NaN, null)) im.toast("song.json 写不进去");
        String c = set ? "已按拍写进歌曲文件夹的 song.json：改了 BPM 或偏移，保存后预览也跟着拍走。游戏的歌曲列表按这段试听。"
            : "默认：从整首歌 30% 处开始，放 20 秒。改了就按拍写进 song.json。";
        y += g.wrap(c + "也可以在时间轴的音频条上拖预览框的两条边（按住 Shift 不吸附）。起点可以在音乐开始之前（先静音），终点可以超过音乐结尾。",
            CX, y, set ? CW - 88f : CW, 12, Gfx.INK3) + 8f;
    }

    private void tempo(Session s, Doc d) {
        title("节奏 · 变速", d.bpms.size() + " 段");
        int cur = segRows(s, d.bpms, "seg", "BPM", true);
        float bw = CW - 112f;
        if (im.button("seg-add", CX, y, bw, 32f, "+ 在当前拍变速", Im.NORMAL, 13)) addSeg(s, d);
        String tl = tapBpm > 0f ? "♪ " + Im.fmt(Math.round(tapBpm * 10f) / 10f) : "♪ 敲拍测速";
        if (im.button("tap", CX + bw + 8f, y, 104f, 32f, tl, Im.NORMAL, 13)) tap();
        y += 40f;
        if (tapBpm > 0f) {
            g.text("敲了 " + taps.size() + " 下 · 约 " + Im.fmt(Math.round(tapBpm * 10f) / 10f) + " BPM", CX,
                y + 14f, 12, Gfx.INK2);
            if (im.button("tap-use", CX + CW - 104f, y, 104f, 28f, "用到当前段", Im.NORMAL, 12)) {
                s.mark();
                d.bpms.get(Math.min(cur, d.bpms.size() - 1)).bpm = Math.round(tapBpm * 10f) / 10f;
                s.changed(false);
                taps.clear();
                tapBpm = 0f;
            }
            y += 36f;
        }
        float off = num("c-off", "偏移", d.offsetMs, "ms");
        if (!Float.isNaN(off)) {
            s.mark();
            d.offsetMs = off;
            s.changed(false);
        }
        caption("时间仍以拍为单位，变速只改每拍多长");
    }

    /**
     * Rows of a {@code beat → bpm} list (the tempo, or the flow BPM); the first starts at beat 0 and,
     * with {@code keepFirst}, cannot be removed. @return the row in force at the playhead
     */
    private int segRows(Session s, ArrayList<Doc.Seg> list, String id, String label, boolean keepFirst) {
        int cur = 0;
        for (int i = 0; i < list.size(); i++) if (list.get(i).beat <= ed.beat) cur = i;
        int remove = -1;
        for (int i = 0; i < list.size(); i++) {
            Doc.Seg sg = list.get(i);
            boolean on = i == cur && list.size() > 1;
            g.box(CX, y, CW, 38f, 6f, on ? Gfx.ITEM_ON : Gfx.ITEM, on ? Gfx.SEL_LINE : Gfx.ITEM_LINE);
            g.text("#" + (i + 1), CX + 12f, y + 19f, 12, Gfx.INK3);
            g.text("从拍", CX + 40f, y + 19f, 12, Gfx.INK2);
            if (i == 0) {
                g.box(CX + 74f, y + 5f, 86f, 28f, 5f, Gfx.FIELD, Gfx.BTN_LINE);
                g.text("0", CX + 84f, y + 19f, 13, Gfx.INK3);
            } else {
                float b = im.num(new Im.Pair(id + "-b", i), CX + 74f, y + 5f, 86f, 28f, sg.beat, "");
                if (!Float.isNaN(b)) setSegBeat(s, list, sg, b);
            }
            g.text(label, CX + 172f, y + 19f, 12, Gfx.INK2);
            float bpm = im.num(new Im.Pair(id + "-v", i), CX + 206f, y + 5f, 84f, 28f, sg.bpm, "");
            if (!Float.isNaN(bpm)) {
                if (bpm > 0f && bpm < 10000f) {
                    s.mark();
                    sg.bpm = bpm;
                    s.changed(false);
                } else im.toast("BPM 要大于 0");
            }
            if ((i > 0 || !keepFirst)
                && im.button(new Im.Pair(id + "-x", i), CX + CW - 32f, y + 7f, 24f, 24f, "×", Im.GHOST, 14)) remove = i;
            y += 44f;
        }
        if (remove >= 0) {
            s.mark();
            list.remove(remove);
            if (!keepFirst && !list.isEmpty()) list.get(0).beat = 0f;
            s.changed(false);
        }
        return cur;
    }

    private void setSegBeat(Session s, ArrayList<Doc.Seg> list, Doc.Seg sg, float b) {
        if (!(b > 0f)) {
            im.toast("变速的拍要大于 0");
            return;
        }
        for (Doc.Seg o : list) {
            if (o != sg && Math.abs(o.beat - b) < 1e-4f) {
                im.toast("这一拍已经有变速了");
                return;
            }
        }
        s.mark();
        sg.beat = b;
        list.sort((p, q) -> Float.compare(p.beat, q.beat));
        s.changed(false);
    }

    /** 流速 BPM and 统一拍数 / 统一流速 (Kelvin 2026-10-15): a fast song need not fall fast. */
    private void flow(Session s, Doc d) {
        title("流速", d.flows.isEmpty() ? "跟拍号 BPM 一样" : d.flows.size() + " 段");
        int by = im.segmented("c-fall", CX, y, CW, 32f, FALL_BY, d.flowFall ? 1 : 0);
        y += 40f;
        if ((by == 1) != d.flowFall) {
            s.mark();
            int k = d.setFallBy(by == 1);
            s.changed(false);
            im.toast(FALL_BY[by] + "：改了 " + k + " 个音符的下落（可以撤销）");
        }
        segRows(s, d.flows, "flw", "流速", false);
        if (im.button("flw-add", CX, y, CW, 32f, d.flows.isEmpty() ? "+ 设流速 BPM" : "+ 在当前拍改流速", Im.NORMAL, 13)) {
            addFlow(s, d);
        }
        y += 40f;
        caption("统一流速：正常下落等按流速 BPM 的拍数算时间，比如 BPM 240、流速 120 时是 4 拍内 " + dist(Doc.SPAWN) + " → 0，"
            + "看起来和 120 BPM 一样快；改 BPM、流速或挪音符后自动对齐，长条尾巴也跟着变。统一拍数 = 按拍下落，BPM 越高越快。只影响画面，不影响判定。");
    }

    private void addFlow(Session s, Doc d) {
        float b = d.flows.isEmpty() ? 0f : ed.snap(ed.beat);
        if (!d.flows.isEmpty()) {
            if (!(b > 0f)) {
                im.toast("先把播放头移到要改流速的地方（第 0 拍之后）");
                return;
            }
            for (Doc.Seg o : d.flows) {
                if (Math.abs(o.beat - b) < 1e-4f) {
                    im.toast("这一拍已经改过流速了");
                    return;
                }
            }
        }
        s.mark();
        d.flows.add(new Doc.Seg(b, Math.round(d.flowAt(b) * 10f) / 10f));
        d.sortFlows();
        s.changed(false);
        im.toast(b == 0f ? "流速 BPM 先和拍号一样，改成想要的数就行" : "在第 " + Im.fmt(b) + " 拍改流速，改它的 BPM 就行");
    }

    private void addSeg(Session s, Doc d) {
        float b = ed.snap(ed.beat);
        if (!(b > 0f)) {
            im.toast("先把播放头移到要变速的地方（第 0 拍之后）");
            return;
        }
        for (Doc.Seg o : d.bpms) {
            if (Math.abs(o.beat - b) < 1e-4f) {
                im.toast("这一拍已经有变速了");
                return;
            }
        }
        s.mark();
        d.bpms.add(new Doc.Seg(b, d.bpmAt(b)));
        d.sortBpms();
        s.changed(false);
        im.toast("在第 " + Im.fmt(b) + " 拍加了变速，改它的 BPM 就行");
    }

    /** Tap tempo: the average interval of the recent taps (a pause of 2.5 s starts over). */
    private void tap() {
        long now = System.nanoTime();
        if (!taps.isEmpty() && now - taps.get(taps.size() - 1) > 2_500_000_000L) taps.clear();
        taps.add(now);
        while (taps.size() > 12) taps.remove(0);
        tapBpm = taps.size() >= 3 ? (float) (60e9 * (taps.size() - 1) / (taps.get(taps.size() - 1) - taps.get(0))) : 0f;
    }

    private void checks(Session s) {
        title("检查", "保存前自动检查");
        Checks c = ed.checks;
        for (int i = 0; i < c.items.size(); i++) {
            Checks.Item it = c.items.get(i);
            g.box(CX, y, CW, 36f, 6f, Gfx.ITEM, Gfx.ITEM_LINE);
            Color ic = it.level == Checks.OK ? Gfx.OK : it.level == Checks.WARN ? Gfx.SYNC
                : it.level == Checks.INFO ? Gfx.INK2 : Gfx.BAD;
            String icon = it.level == Checks.OK ? "✓" : it.level == Checks.WARN ? "⚠"
                : it.level == Checks.INFO ? "○" : "×";
            g.text(icon, CX + 16f, y + 18f, 13, ic, 1);
            boolean jump = !Float.isNaN(it.beat);
            g.text(g.fit(it.text, 13, CW - (jump ? 112f : 48f)), CX + 32f, y + 18f, 13, Gfx.INK1);
            if (jump
                && im.button(new Im.Pair("chk", i), CX + CW - 66f, y + 6f, 60f, 24f, "跳到 ›", Im.GHOST, 12)) ed.jump(it);
            y += 42f;
        }
        g.text(c.summary(), CX, y + 6f, 12, Gfx.INK3);
        y += 24f;
        if (s.loadNote != null) caption(s.loadNote);
    }

    // ================================================================== 音符

    private void noteTab() {
        Session s = ed.s;
        ArrayList<Doc.DNote> sel = s.selected();
        if (sel.isEmpty()) {
            noNotes(s);
            return;
        }
        if (sel.size() > 1) {
            manyNotes(s, sel);
            return;
        }
        Doc.DNote n = sel.get(0);
        title("类型", null);
        int ti = n.type.ordinal();
        int nt = im.segmented("n-type", CX, y, CW, 32f, new String[]{"水滴", "水流", "水雷", "浪花", "浪花水雷"}, ti);
        if (nt != ti) {
            s.mark();
            setType(s, n, NoteType.of(nt));
            s.changed(false);
        }
        y += 40f;
        int li = n.line == Line.UPPER ? 0 : 1;
        int nl = im.segmented("n-line", CX, y, 148f, 32f, LINES, li);
        if (nl != li) {
            s.mark();
            n.line = nl == 0 ? Line.UPPER : Line.LOWER;
            s.changed(false);
        }
        Note gn = s.note(n);
        if (gn != null && gn.isSync()) {
            g.rect(CX + 162f, y + 11f, 10f, 10f, Gfx.SYNC);
            g.text("同拍 " + gn.syncSize() + " 键（sync）", CX + 178f, y + 16f, 12, Gfx.SYNC);
        }
        y += 40f;
        if (n.canFake()) fake(s, sel, n);
        divider();
        title("位置与时间", null);
        label("轨道");
        if (n.inLane()) {
            if (im.button("n-lane-l", FX, y, 30f, 30f, "‹", Im.GHOST, 16)) stepLane(s, n, -1);
            String v = im.field("n-lane", FX + 34f, y, FW - 68f, 30f, EditorScreen.laneName(n.lane), "id", true);
            if (im.button("n-lane-r", FX + FW - 30f, y, 30f, 30f, "›", Im.GHOST, 16)) stepLane(s, n, 1);
            if (v != null) setLane(s, sel, v);
        } else {
            g.text(n.type == NoteType.TIDE_MINE ? "整条线（浪花水雷不分轨道）" : "整条线（浪花不分轨道）", FX + 10f, y + 15f, 13, Gfx.INK3);
        }
        y += 38f;
        float j = num("n-j", "判定拍", n.judgement, "拍");
        if (!Float.isNaN(j) && j != n.judgement) {
            s.mark();
            n.retime(j - n.judgement);
            s.changed(false);
        }
        if (n.type == NoteType.TAP) {
            caption("水滴没有时长；改成水流后出现「时长 / 尾长」");
        } else {
            float du = num("n-dur", "时长", n.duration, "拍");
            if (!Float.isNaN(du) && du != n.duration) setDuration(s, n, du);
            if (n.sustained()) {
                boolean mine = n.type == NoteType.MINE || n.type == NoteType.TIDE_MINE;
                float h = num("n-h", mine ? "红区长度" : "尾长", n.height,
                    "建议 " + Im.fmt(Math.round(s.doc.autoHeight(n))));
                if (!Float.isNaN(h) && h >= 0f) {
                    s.mark();
                    n.height = h;
                    n.autoH = s.doc.flowFall && Math.abs(h - s.doc.autoHeight(n)) < 1f ? h : Float.NaN;
                    s.changed(false);
                }
            }
            if (n.type == NoteType.MINE) {
                caption(n.duration > 0f
                    ? "轨道水雷：时长 = 封锁这条轨道多久；碰到判定线后底部不再动，红区长度可以用下面的「红区长度」事件随时改（只影响画面）"
                    : "时长 0 = 水雷；大于 0 = 轨道水雷（整段封锁这条轨道）");
            }
            else if (n.type == NoteType.UNIVERSAL) caption("时长 0 = 浪花；大于 0 = 潮汐");
            else if (n.type == NoteType.TIDE_MINE) {
                caption("时长 0 = 浪花水雷（在这条线的判定区里按下就算碰到）；大于 0 = 潮汐水雷（整段封锁这条线的判定区）");
            }
        }
        if (!n.inLane()) {
            float ws = num("n-wave", "波动速度", n.waveSpeed, "波/拍");
            if (Float.isFinite(ws) && ws != n.waveSpeed) {
                s.mark();
                n.waveSpeed = ws;
                s.changed(false);
            }
            caption("每拍移动几个波：0 = 不动，0.5 = 每拍半个波，负数往左。地上是波往旁边流，天上是珍珠串往旁边移（一个波 = 两颗珍珠）。只影响画面。");
        }
        divider();
        title("运动（只影响画面）", null);
        allRelButtons(s, sel, y - 31f);
        evGroup("n-move", "移动 Movement", Gfx.GND, n.move, n, CH_MOVE, 0f);
        evGroup("n-rot", "旋转 Rotation", Gfx.INK2, n.rot, n, CH_ROT, 0f);
        if (n.inLane()) {
            offsetX(s, sel, n);
            evGroup("n-offx-ev", "横移 OffsetX", Gfx.INK2, n.offEv, n, CH_OFFX, n.offsetX);
        }
        pivot(s, sel, n);
        evGroup("n-alpha", "透明 Opacity", Gfx.INK2, n.alpha, n, CH_ALPHA, 1f);
        boolean redZone = n.type == NoteType.MINE || n.type == NoteType.TIDE_MINE;
        if (n.sustained()) evGroup("n-height", redZone ? "红区长度 Height" : "尾长 Height", Gfx.FX,
            n.heightEv, n, CH_HEIGHT, n.height);
        templates(s, sel);
        divider();
        stillLine(s, sel);
        divider();
        trail(s, n);
        divider();
        glitch(s, n.glitches, "n", n.judgement - 2f, n.judgement, "只影响画面，不影响判定。");
        divider();
        distortion(s, n.distortions, "n", n.judgement - 2f, n.judgement, NoteDistortion.DEFAULT_INTENSITY,
            "键平常不扭曲（0）；1 = 和漩涡平常一样，2 = 两倍，最多 " + Im.fmt(NoteDistortion.MAX_INTENSITY) + "。");
        divider();
        actions();
    }

    private void noNotes(Session s) {
        title("没有选中音符", null);
        caption("用左边的工具在画面上点一下就能放音符；长的几种（水流、轨道水雷、潮汐、潮汐水雷）按住往远处拖出长度。"
            + "用「选择」点音符来编辑，在时间轴上拖动可以框选。");
        divider();
        title("新音符放在", null);
        int li = s.placeLine == Line.UPPER ? 0 : 1;
        int nl = im.segmented("p-line", CX, y, 160f, 32f, LINES, li);
        if (nl != li) s.placeLine = nl == 0 ? Line.UPPER : Line.LOWER;
        g.text("Tab 切换", CX + 172f, y + 16f, 12, Gfx.INK3);
        y += 40f;
        caption("新音符自带接近运动：2 拍内从 " + dist(Doc.SPAWN) + " 落到线上（相对判定，和游戏默认一样）。");
    }

    private void manyNotes(Session s, ArrayList<Doc.DNote> sel) {
        title("已选 " + sel.size() + " 个音符", null);
        int[] c = new int[NoteType.count()];
        int up = 0;
        for (Doc.DNote n : sel) {
            c[n.type.ordinal()]++;
            if (n.line == Line.UPPER) up++;
        }
        g.text("水滴 " + c[0] + " · 水流 " + c[1] + " · 水雷 " + c[2] + " · 浪花 " + c[3], CX, y + 8f, 13, Gfx.INK2);
        y += 30f;
        int li = up == sel.size() ? 0 : up == 0 ? 1 : -1;
        int nl = im.segmented("m-line", CX, y, 160f, 32f, LINES, li);
        if (nl != li && nl >= 0) {
            s.mark();
            for (Doc.DNote n : sel) n.line = nl == 0 ? Line.UPPER : Line.LOWER;
            s.changed(false);
        }
        y += 40f;
        float sh = num("m-shift", "整体平移", 0f, "拍");
        if (!Float.isNaN(sh) && sh != 0f) {
            s.mark();
            for (Doc.DNote n : sel) n.shift(sh);
            s.changed(false);
        }
        g.text("事件", CX, y + 15f, 13, Gfx.INK2);
        allRelButtons(s, sel, y);
        y += 38f;
        divider();
        templates(s, sel);
        divider();
        stillLine(s, sel);
        divider();
        actions();
    }

    /** The 不跟判定线 range being typed, NaN = the selection's default; reset when the selection changes. */
    private float stillFrom = Float.NaN, stillTo = Float.NaN;
    private String stillFor = "";

    /**
     * 不跟判定线 (Kelvin 2026-11-03): over a range of beats the selected keys keep still while their line moves,
     * by added movement events ({@link Doc#holdStill}). The range starts as the selection's earliest fall and its
     * last judgement.
     */
    private void stillLine(Session s, List<Doc.DNote> sel) {
        String key = sel.size() + ":" + sel.get(0).uid + ":" + sel.get(sel.size() - 1).uid;
        if (!key.equals(stillFor)) {
            stillFor = key;
            stillFrom = stillTo = Float.NaN;
        }
        float a = Float.POSITIVE_INFINITY, b = Float.NEGATIVE_INFINITY;
        for (Doc.DNote n : sel) {
            float first = n.judgement - Note.DEFAULT_APPROACH_BEATS;
            if (!n.move.isEmpty()) {
                first = Float.POSITIVE_INFINITY;
                for (Doc.Ev e : n.move) first = Math.min(first, e.start);
            }
            a = Math.min(a, first);
            b = Math.max(b, n.judgement);
        }
        if (!Float.isNaN(stillFrom)) a = stillFrom;
        if (!Float.isNaN(stillTo)) b = stillTo;
        title("不跟判定线", null);
        caption("这段时间里选中的键不跟着判定线上下动，判定线按「从」那一拍的位置算（自动加抵消用的运动事件，可以撤销）。");
        float v = num("st-from", "从", a, "拍");
        if (!Float.isNaN(v)) {
            stillFrom = v;
            stillTo = b;
        }
        v = num("st-to", "到", b, "拍");
        if (!Float.isNaN(v)) {
            stillTo = v;
            stillFrom = a;
        }
        if (im.button("st-go", CX, y, CW, 34f, "抵消判定线运动（" + sel.size() + " 个键）", Im.NORMAL)) {
            if (!(b > a)) {
                im.toast("「到」要比「从」晚");
            } else {
                s.mark();
                int[] r = s.doc.holdStill(sel, a, b);
                s.changed(false);
                im.toast(r[0] == 0 ? "这段时间判定线没有动，键不用改"
                    : "已抵消 " + r[0] + " 个键" + (r[1] > 0 ? "（" + r[1] + " 个按公转半径 / 角度走的没改）" : ""));
            }
        }
        y += 46f;
    }

    private void setType(Session s, Doc.DNote n, NoteType t) {
        if (t == NoteType.TAP) {
            n.duration = 0f;
            n.heightEv.clear();
        }
        if (t == NoteType.HOLD && n.duration <= 0f) {
            n.duration = 1f;
            s.doc.setAutoTail(n);
        }
        if (t.inLane() && s.doc.lane(n.lane) == null) n.lane = s.doc.lanes.get(s.doc.lanes.size() / 2).id;
        n.type = t;
    }

    private void setLane(Session s, ArrayList<Doc.DNote> sel, String v) {
        int id;
        try {
            id = Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            im.toast("轨道要填编号，比如 -1 或 +2");
            return;
        }
        if (id == 0 && s.doc.lane(0) == null) {
            im.toast("没有 0 号轨道（可以在「场地」页插入）");
            return;
        }
        if (s.doc.lane(id) == null && s.doc.laneCount > 0) {
            im.toast("有限轨（" + s.doc.laneCount + " 条）里没有 " + EditorScreen.laneName(id));
            return;
        }
        s.mark();
        if (s.doc.lane(id) == null) {
            s.doc.ensureLane(id);
            im.toast("已加轨道 " + EditorScreen.laneName(id));
        }
        for (Doc.DNote n : sel) if (n.inLane()) n.lane = id;
        s.changed(false);
    }

    /** Moves the selected lane notes one lane left / right; past the edge adds a lane when lanes are unlimited. */
    private void stepLane(Session s, Doc.DNote n, int dir) {
        Doc d = s.doc;
        int i = d.laneIndex(n.lane) + dir;
        int id;
        if (i >= 0 && i < d.lanes.size()) id = d.lanes.get(i).id;
        else if (d.laneCount > 0) return;
        else {
            id = dir < 0 ? d.lanes.get(0).id - 1 : d.lanes.get(d.lanes.size() - 1).id + 1;
            if (id == 0) id = dir;
        }
        setLane(s, s.selected(), Integer.toString(id));
    }

    private void setDuration(Session s, Doc.DNote n, float du) {
        if (du < 0f) {
            im.toast("时长不能小于 0");
            return;
        }
        if (n.type == NoteType.HOLD && du <= 0f) {
            im.toast("水流的时长要大于 0（不要时长就改成水滴）");
            return;
        }
        s.mark();
        boolean auto = s.doc.autoTail(n);
        n.duration = du;
        if (auto) s.doc.setAutoTail(n);
        s.changed(false);
    }

    private void templates(Session s, ArrayList<Doc.DNote> sel) {
        if (im.button("n-fall", CX, y, 128f, 32f, "正常下落  D", Im.NORMAL, 13)) applyTemplate(s, sel, 0);
        int tp = im.dropdown("n-tpl", CX + 136f, y, CW - 136f, 32f, "更多快捷运动", TEMPLATES, -1);
        y += 40f;
        caption(s.doc.flowFall ? "正常下落 = 流速 BPM 的 2 拍内从 " + dist(Doc.SPAWN) + " 落到判定线（统一流速，换掉移动事件）"
            : "正常下落 = 2 拍内从 " + dist(Doc.SPAWN) + " 落到判定线（换掉移动事件）");
        if (tp >= 0) applyTemplate(s, sel, tp);
    }

    /** @return a distance for a label: at most 2 decimals, no trailing zeros (1080, 1555.56). */
    private static String dist(float v) {
        return String.format(java.util.Locale.ROOT, "%.2f", v).replaceAll("\\.?0+$", "");
    }

    /** Applies quick motion {@code tp} (an index of {@link #TEMPLATES}) to {@code sel} as one undo step. */
    void applyTemplate(Session s, List<Doc.DNote> sel, int tp) {
        if (sel.isEmpty()) return;
        s.mark();
        for (Doc.DNote n : sel) {
            switch (tp) {
                case 0: n.approach(2f, Doc.SPAWN, 1f); break;
                case 1: n.approach(1f, Doc.SPAWN, 1f); break;
                case 2: n.approach(4f, Doc.SPAWN, 1f); break;
                case 3: n.approach(2f, FAST_SLOW, 0.5f); break;
                case 4: n.approach(2f, Doc.SPAWN, 2f); break;
                case 5: {
                    float from = n.judgement - 2f;
                    for (Doc.Ev e : n.move) from = Math.min(from, e.start);
                    n.alpha.clear();
                    Doc.Ev fade = new Doc.Ev(from, from + (n.judgement - from) * 0.5f, 0f, 1f, 1f);
                    fade.rel = true;
                    n.alpha.add(fade);
                    break;
                }
                default:
                    n.move.clear();
                    n.appearance = n.judgement;
            }
            if (tp <= 4) s.doc.linkFlow(n);
        }
        s.changed(false);
        String t = TEMPLATES[tp];
        im.toast("已套用" + t.substring(0, t.indexOf('：')) + "（" + sel.size() + " 个音符）");
    }

    /** 假键 (game 7.167 / 7.169): a key that is only drawn; applies to every selected note that can be one. */
    private void fake(Session s, List<Doc.DNote> sel, Doc.DNote n) {
        g.text("假键 fake（只显示到判定时刻，不判定、不计分、不响）", CX, y + 12f, 12, Gfx.INK3);
        boolean t = im.toggle("n-fake", CX + CW, y + 12f, n.fake);
        if (t != n.fake) {
            s.mark();
            for (Doc.DNote x : sel) if (x.canFake()) x.fake = t;
            s.changed(false);
        }
        y += 30f;
    }

    /** 拖拽残影: one or more segments, each with its own window (§6.8). */
    private void trail(Session s, Doc.DNote n) {
        g.text("拖拽残影 trail", CX, y + 12f, 12, Gfx.INK3);
        boolean on = !n.trails.isEmpty();
        boolean t = im.toggle("n-trail", CX + CW, y + 12f, on);
        if (t != on) {
            s.mark();
            n.trails.clear();
            if (t) n.trails.add(new Doc.DTrail());
            s.changed(false);
        }
        y += 34f;
        if (!on) return;
        for (int k = 0; k < n.trails.size(); k++) {
            if (n.trails.size() > 1 && segHeader("t-del", k)) {
                s.mark();
                n.trails.remove(k);
                s.changed(false);
                return;
            }
            trailSeg(s, n, k);
        }
        if (im.button("t-add", CX, y, 160f, 30f, "+ 加一段残影", Im.GHOST, 12)) {
            s.mark();
            Doc.DTrail nt = n.trails.get(n.trails.size() - 1).copy();
            nt.from = nextStart(nt.to);
            nt.to = nt.from + 2f;
            n.trails.add(nt);
            s.changed(false);
        }
        y += 38f;
        caption("残影数 = 长度 × 密度（每段最多 " + NoteTrail.MAX_TRAIL_COPIES + " 个）；范围空着 = 不限。分成几段时，每段只在自己的范围里出现，段和段之间没有残影。");
    }

    private void trailSeg(Session s, Doc.DNote n, int k) {
        Doc.DTrail tr = n.trails.get(k);
        float w2 = (FW - 8f) / 2f;
        label("长度/密度");
        float a = im.num(new Im.Pair("t-len", k), FX, y, w2, 30f, tr.length, "拍");
        float b = im.num(new Im.Pair("t-den", k), FX + w2 + 8f, y, w2, 30f, tr.density, "/拍");
        y += 38f;
        label("透明/衰减");
        float c = im.num(new Im.Pair("t-a", k), FX, y, w2, 30f, tr.alpha, "");
        float f = im.num(new Im.Pair("t-f", k), FX + w2 + 8f, y, w2, 30f, tr.fade, "");
        y += 38f;
        label("模式");
        int m = im.segmented(new Im.Pair("t-mode", k), FX, y, FW, 30f, new String[]{"沿路径 path", "同高 level"},
            tr.level ? 1 : 0);
        y += 38f;
        label("范围");
        String fs = im.field(new Im.Pair("t-from", k), FX, y, w2, 30f,
            Float.isNaN(tr.from) ? "" : Im.fmt(tr.from), "从", true);
        String ts = im.field(new Im.Pair("t-to", k), FX + w2 + 8f, y, w2, 30f,
            Float.isNaN(tr.to) ? "" : Im.fmt(tr.to), "到", true);
        y += 38f;
        Doc.DTrail nt = tr.copy();
        boolean ch = false;
        if (!Float.isNaN(a) && a >= 0f) {
            nt.length = a;
            ch = true;
        }
        if (!Float.isNaN(b) && b > 0f) {
            nt.density = b;
            ch = true;
        }
        if (!Float.isNaN(c)) {
            nt.alpha = clamp(c, 0f, 1f);
            ch = true;
        }
        if (!Float.isNaN(f)) {
            nt.fade = clamp(f, NoteTrail.MIN_FADE, NoteTrail.MAX_FADE);
            ch = true;
        }
        if (m != (tr.level ? 1 : 0)) {
            nt.level = m == 1;
            ch = true;
        }
        if (fs != null) {
            nt.from = parseOrNaN(fs);
            ch = true;
        }
        if (ts != null) {
            nt.to = parseOrNaN(ts);
            ch = true;
        }
        if (!ch) return;
        if (nt.from > nt.to) { // the game swaps them too
            float x = nt.from;
            nt.from = nt.to;
            nt.to = x;
        }
        s.mark();
        n.trails.set(k, nt);
        s.changed(false);
    }

    /**
     * 故障: the glitch segments of a note or a veil (§6.9, visual only). {@code p} keeps the widget ids apart,
     * {@code from}/{@code to} is the window of the first segment and {@code lead} starts the caption.
     */
    private void glitch(Session s, List<Doc.DGlitch> list, String p, float from, float to, String lead) {
        g.text("故障 glitch", CX, y + 12f, 12, Gfx.INK3);
        boolean on = !list.isEmpty();
        boolean t = im.toggle(p + "-glitch", CX + CW, y + 12f, on);
        if (t != on) {
            s.mark();
            list.clear();
            if (t) {
                Doc.DGlitch x = new Doc.DGlitch();
                x.from = from;
                x.to = to;
                list.add(x);
            }
            s.changed(false);
        }
        y += 34f;
        if (!on) return;
        for (int k = 0; k < list.size(); k++) {
            if (list.size() > 1 && segHeader(p + "g-del", k)) {
                s.mark();
                list.remove(k);
                s.changed(false);
                return;
            }
            glitchSeg(s, list, p, k);
        }
        if (im.button(p + "g-add", CX, y, 160f, 30f, "+ 加一段故障", Im.GHOST, 12)) {
            s.mark();
            Doc.DGlitch x = list.get(list.size() - 1).copy();
            x.from = nextStart(x.to);
            x.to = x.from + 2f;
            list.add(x);
            sortGlitches(list);
            s.changed(false);
        }
        y += 38f;
        caption(lead + "强度 0–1，填了「结束」就从开始强度渐变过去（指数 = 缓动）；速度 = 每拍换几次花样（0 = 不换）；"
            + "色散 / 错位 / 闪断是比重 0–2。两段重叠时，后开始的那段起作用；开了「减少闪烁」没有闪断，花样换得慢一半。");
    }

    private void glitchSeg(Session s, List<Doc.DGlitch> list, String p, int k) {
        Doc.DGlitch x = list.get(k);
        float w2 = (FW - 8f) / 2f, w3 = (FW - 16f) / 3f;
        label("范围");
        String fs = im.field(new Im.Pair(p + "g-from", k), FX, y, w2, 30f,
            Float.isNaN(x.from) ? "" : Im.fmt(x.from), "从", true);
        String ts = im.field(new Im.Pair(p + "g-to", k), FX + w2 + 8f, y, w2, 30f,
            Float.isNaN(x.to) ? "" : Im.fmt(x.to), "到", true);
        y += 38f;
        label("强度");
        float i0 = im.num(new Im.Pair(p + "g-i0", k), FX, y, w2, 30f, x.intensity, "开始");
        String i1 = im.field(new Im.Pair(p + "g-i1", k), FX + w2 + 8f, y, w2, 30f,
            Float.isNaN(x.intensityEnd) ? "" : Im.fmt(x.intensityEnd), "结束", true);
        y += 38f;
        label("缓动/速度");
        float ex = im.num(new Im.Pair(p + "g-ex", k), FX, y, w2, 30f, x.exponent, "指数");
        float ra = im.num(new Im.Pair(p + "g-ra", k), FX + w2 + 8f, y, w2, 30f, x.rate, "次/拍");
        y += 38f;
        label("比重");
        float sp = im.num(new Im.Pair(p + "g-sp", k), FX, y, w3, 30f, x.split, "色散");
        float sl = im.num(new Im.Pair(p + "g-sl", k), FX + w3 + 8f, y, w3, 30f, x.slice, "错位");
        float fl = im.num(new Im.Pair(p + "g-fl", k), FX + 2f * (w3 + 8f), y, w3, 30f, x.flicker, "闪断");
        y += 38f;
        Doc.DGlitch nx = x.copy();
        boolean ch = false;
        if (fs != null) {
            nx.from = parseOrNaN(fs);
            ch = true;
        }
        if (ts != null) {
            nx.to = parseOrNaN(ts);
            ch = true;
        }
        if (!Float.isNaN(i0)) {
            nx.intensity = clamp(i0, 0f, 1f);
            ch = true;
        }
        if (i1 != null) {
            float v = parseOrNaN(i1);
            nx.intensityEnd = Float.isNaN(v) ? Float.NaN : clamp(v, 0f, 1f);
            ch = true;
        }
        if (!Float.isNaN(ex)) {
            nx.exponent = clamp(ex, NoteGlitch.MIN_EXPONENT, NoteGlitch.MAX_EXPONENT);
            ch = true;
        }
        if (!Float.isNaN(ra)) {
            nx.rate = clamp(ra, 0f, NoteGlitch.MAX_RATE);
            ch = true;
        }
        if (!Float.isNaN(sp)) {
            nx.split = clamp(sp, 0f, NoteGlitch.MAX_WEIGHT);
            ch = true;
        }
        if (!Float.isNaN(sl)) {
            nx.slice = clamp(sl, 0f, NoteGlitch.MAX_WEIGHT);
            ch = true;
        }
        if (!Float.isNaN(fl)) {
            nx.flicker = clamp(fl, 0f, NoteGlitch.MAX_WEIGHT);
            ch = true;
        }
        if (!ch) return;
        if (nx.from > nx.to) { // the game swaps them too
            float v = nx.from;
            nx.from = nx.to;
            nx.to = v;
        }
        s.mark();
        list.set(k, nx);
        sortGlitches(list);
        s.changed(false);
    }

    /**
     * 扭曲: the distortion segments of a note or a veil (§6.11, visual only, Kelvin 2026-10-23). {@code p} keeps the
     * widget ids apart; the first segment covers {@code from}/{@code to} at {@code strength}; {@code lead} says what
     * the strength is outside the segments.
     */
    private void distortion(Session s, List<Doc.DDist> list, String p, float from, float to, float strength,
                            String lead) {
        g.text("扭曲 distortion", CX, y + 12f, 12, Gfx.INK3);
        boolean on = !list.isEmpty();
        boolean t = im.toggle(p + "-dist", CX + CW, y + 12f, on);
        if (t != on) {
            s.mark();
            list.clear();
            if (t) {
                Doc.DDist x = new Doc.DDist();
                x.from = from;
                x.to = to;
                x.intensity = strength;
                list.add(x);
            }
            s.changed(false);
        }
        y += 34f;
        if (!on) {
            caption(lead);
            return;
        }
        for (int k = 0; k < list.size(); k++) {
            if (list.size() > 1 && segHeader(p + "d-del", k)) {
                s.mark();
                list.remove(k);
                s.changed(false);
                return;
            }
            distSeg(s, list, p, k);
        }
        if (im.button(p + "d-add", CX, y, 160f, 30f, "+ 加一段扭曲", Im.GHOST, 12)) {
            s.mark();
            Doc.DDist x = list.get(list.size() - 1).copy();
            x.from = nextStart(x.to);
            x.to = x.from + 2f;
            list.add(x);
            sortDistortions(list);
            s.changed(false);
        }
        y += 38f;
        caption(lead + "只影响画面。填了「结束」就从开始强度渐变过去（指数 = 缓动）；范围空着 = 不限，段外回到平常；"
            + "两段重叠时，后开始的那段起作用。");
    }

    private void distSeg(Session s, List<Doc.DDist> list, String p, int k) {
        Doc.DDist x = list.get(k);
        float w2 = (FW - 8f) / 2f, max = NoteDistortion.MAX_INTENSITY;
        label("范围");
        String fs = im.field(new Im.Pair(p + "d-from", k), FX, y, w2, 30f,
            Float.isNaN(x.from) ? "" : Im.fmt(x.from), "从", true);
        String ts = im.field(new Im.Pair(p + "d-to", k), FX + w2 + 8f, y, w2, 30f,
            Float.isNaN(x.to) ? "" : Im.fmt(x.to), "到", true);
        y += 38f;
        label("强度");
        float i0 = im.num(new Im.Pair(p + "d-i0", k), FX, y, w2, 30f, x.intensity, "开始");
        String i1 = im.field(new Im.Pair(p + "d-i1", k), FX + w2 + 8f, y, w2, 30f,
            Float.isNaN(x.intensityEnd) ? "" : Im.fmt(x.intensityEnd), "结束", true);
        y += 38f;
        label("缓动");
        float ex = im.num(new Im.Pair(p + "d-ex", k), FX, y, w2, 30f, x.exponent, "指数");
        y += 38f;
        Doc.DDist nx = x.copy();
        boolean ch = false;
        if (fs != null) {
            nx.from = parseOrNaN(fs);
            ch = true;
        }
        if (ts != null) {
            nx.to = parseOrNaN(ts);
            ch = true;
        }
        if (!Float.isNaN(i0)) {
            nx.intensity = clamp(i0, 0f, max);
            ch = true;
        }
        if (i1 != null) {
            float v = parseOrNaN(i1);
            nx.intensityEnd = Float.isNaN(v) ? Float.NaN : clamp(v, 0f, max);
            ch = true;
        }
        if (!Float.isNaN(ex)) {
            nx.exponent = clamp(ex, NoteGlitch.MIN_EXPONENT, NoteGlitch.MAX_EXPONENT); // the game clamps it so
            ch = true;
        }
        if (!ch) return;
        if (nx.from > nx.to) {
            float v = nx.from;
            nx.from = nx.to;
            nx.to = v;
        }
        s.mark();
        list.set(k, nx);
        sortDistortions(list);
        s.changed(false);
    }

    /** Keeps the segments in the game's order: by start, unbounded first (stable). */
    private static void sortGlitches(List<Doc.DGlitch> list) {
        list.sort((a, b) -> Float.compare(Doc.fromKey(a.from), Doc.fromKey(b.from)));
    }

    private static void sortDistortions(List<Doc.DDist> list) {
        list.sort((a, b) -> Float.compare(Doc.fromKey(a.from), Doc.fromKey(b.from)));
    }

    /** A segment's title row with its delete button; true when the button was pressed. */
    private boolean segHeader(String id, int k) {
        g.text("第 " + (k + 1) + " 段", CX, y + 12f, 12, Gfx.INK2);
        boolean del = im.button(new Im.Pair(id, k), CX + CW - 56f, y, 56f, 24f, "删除", Im.GHOST, 12);
        y += 30f;
        return del;
    }

    /** Where an added segment starts: right after the last one when it has an end, else at the playhead. */
    private float nextStart(float lastTo) {
        return Float.isFinite(lastTo) ? lastTo : ed.snap(ed.beat);
    }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /** 全部相对 / 全部绝对: sets the mode of every note event of {@code sel} (one undo step). */
    private void allRelButtons(Session s, List<Doc.DNote> sel, float by) {
        for (int k = 0; k < 2; k++) {
            boolean rel = k == 0;
            if (im.button("ev-all" + k, CX + CW - 150f + k * 76f, by, 74f, 26f, rel ? "全部相对" : "全部绝对", Im.GHOST, 12)) {
                s.mark();
                int c = 0;
                for (Doc.DNote n : sel) {
                    for (List<Doc.Ev> list : java.util.Arrays.asList(n.move, n.rot, n.offEv, n.orbXEv, n.orbYEv,
                        n.orbRadEv, n.orbAngEv, n.alpha, n.heightEv)) {
                        for (Doc.Ev e : list) {
                            e.rel = rel;
                            c++;
                        }
                    }
                }
                s.changed(false);
                im.toast(c + " 段事件改成" + (rel ? "相对判定" : "绝对拍"));
            }
        }
    }

    private void actions() {
        if (im.button("n-copy", CX, y, 70f, 34f, "复制", Im.NORMAL)) ed.copy(false);
        if (im.button("n-mirror", CX + 78f, y, 104f, 34f, "左右镜像 ⇄", Im.NORMAL)) ed.mirror();
        if (im.button("n-flip", CX + 190f, y, 104f, 34f, "上下镜像 ⇅", Im.NORMAL)) ed.mirrorUpDown();
        if (im.button("n-del", CX + CW - 70f, y, 70f, 34f, "删除", Im.DANGER)) ed.deleteSel();
        y += 46f;
    }

    // ================================================================== events

    private void evGroup(String id, String title, Color dot, List<Doc.Ev> list, Doc.DNote n, int ch, float base) {
        boolean isOpen = id.equals(open);
        boolean hv = im.hover(CX, y, CW, 36f);
        if (hv) im.cursor(Cursor.SystemCursor.Hand);
        g.box(CX, y, CW, 36f, 6f, isOpen ? Gfx.ITEM_ON : Gfx.ITEM,
            isOpen ? Gfx.GND : hv ? Gfx.SEL_LINE : Gfx.ITEM_LINE);
        g.disc(CX + 14f, y + 18f, 3.5f, list.isEmpty() ? Gfx.INK3 : dot);
        g.text(title, CX + 28f, y + 18f, 13, Gfx.INK1);
        g.text(list.isEmpty() ? "0 ›" : list.size() + (isOpen ? " 段 ▼" : " 段 ›"), CX + CW - 12f, y + 18f,
            12, Gfx.INK3, 2);
        if (im.click(id, CX, y, CW, 36f)) {
            open = isOpen ? null : id;
            evSel = list.isEmpty() ? -1 : 0;
        }
        y += 42f;
        if (id.equals(open)) evEditor(id, list, n, ch, base);
    }

    private void evEditor(String id, List<Doc.Ev> list, Doc.DNote n, int ch, float base) {
        Session s = ed.s;
        if (evSel >= list.size()) evSel = list.size() - 1;
        if (evSel < 0 && !list.isEmpty()) evSel = 0;
        if (!list.isEmpty()) graph(list);
        for (int i = 0; i < list.size(); i++) {
            Doc.Ev e = list.get(i);
            boolean on = i == evSel, rel = n != null && e.rel;
            float off = rel ? n.judgement : 0f;
            g.box(CX + 2f, y, CW - 4f, 30f, 5f, on ? Gfx.ITEM_ON : Gfx.FIELD, on ? Gfx.GND : Gfx.ITEM_LINE);
            g.text("#" + (i + 1), CX + 12f, y + 15f, 12, Gfx.INK3);
            g.text(beatText(e.start - off, rel) + " → " + beatText(e.end - off, rel) + "   " + Im.fmt(e.from) + " → "
                + Im.fmt(e.to), CX + 40f, y + 15f, 12, Gfx.INK1);
            String mode = !Float.isNaN(e.flow) ? "流速" : rel ? "相对" : "绝对";
            if (n != null
                && im.button(new Im.Pair(id + "-m", i), CX + CW - 134f, y + 4f, 44f, 22f, mode,
                rel ? Im.ON : Im.GHOST, 11)) {
                s.mark();
                e.rel = !e.rel;
                e.flow = Float.NaN;                                  // a flow fall made absolute stays put
                s.changed(false);
                evSel = i;
            }
            g.text("exp " + Im.fmt(e.exp), CX + CW - 40f, y + 15f, 12, Gfx.INK3, 2);
            if (im.button(new Im.Pair(id + "-x", i), CX + CW - 32f, y + 3f, 26f, 24f, "×", Im.GHOST, 14)) {
                s.mark();
                list.remove(i);
                s.changed(false);
                evSel = Math.min(evSel, list.size() - 1);
                break;
            }
            if (im.click(new Im.Pair(id + "-r", i), CX + 2f, y, CW - 40f, 30f)) evSel = i;
            y += 34f;
        }
        if (evSel >= 0 && evSel < list.size()) {
            Doc.Ev e = list.get(evSel);
            boolean rel = n != null && e.rel;
            float off = rel ? n.judgement : 0f;
            float w2 = (CW - 8f) / 2f, w3 = (CW - 16f) / 3f;
            String unit = rel ? "距判定" : "拍";
            float a = im.num(new Im.Pair(id + "-s", evSel), CX, y, w2, 30f, e.start - off, unit);
            float b = im.num(new Im.Pair(id + "-e", evSel), CX + w2 + 8f, y, w2, 30f, e.end - off, unit);
            y += 36f;
            float f0 = im.num(new Im.Pair(id + "-a", evSel), CX, y, w3, 30f, e.from, "");
            float f1 = im.num(new Im.Pair(id + "-b", evSel), CX + w3 + 8f, y, w3, 30f, e.to, "");
            float ex = im.num(new Im.Pair(id + "-p", evSel), CX + 2f * (w3 + 8f), y, w3, 30f, e.exp, "");
            y += 34f;
            g.text(unit.equals("拍") ? "开始拍 · 结束拍 / 起始值 · 结束值 · 指数" : "开始 · 结束（距判定几拍，负数在判定前）/ 起始值 · 结束值 · 指数",
                CX, y + 7f, 11, Gfx.INK3);
            y += 22f;
            if (!Float.isNaN(a) || !Float.isNaN(b) || !Float.isNaN(f0) || !Float.isNaN(f1) || !Float.isNaN(ex)) {
                s.mark();
                if (!Float.isNaN(a)) e.start = a + off;
                if (!Float.isNaN(b)) e.end = b + off;
                if (!Float.isNaN(a) || !Float.isNaN(b)) e.flow = Float.NaN;   // timed by hand now
                if (!Float.isNaN(f0)) e.from = f0;
                if (!Float.isNaN(f1)) e.to = f1;
                if (!Float.isNaN(ex)) {
                    if (ex > 0f) e.exp = ex;
                    else im.toast("指数要大于 0");
                }
                if (e.end < e.start) {
                    float t = e.start;
                    e.start = e.end;
                    e.end = t;
                }
                s.changed(false);
                evSel = list.indexOf(e);
            }
        }
        // note events: relative or absolute is chosen per event when it is added
        int add = -1;
        if (n == null) {
            if (im.button(id + "-add", CX, y, CW, 30f, "+ 添加一段", Im.NORMAL, 13)) add = 0;
        } else {
            float w2 = (CW - 8f) / 2f;
            if (im.button(id + "-add", CX, y, w2, 30f, "+ 相对判定", Im.NORMAL, 13)) add = 1;
            if (im.button(id + "-add-abs", CX + w2 + 8f, y, w2, 30f, "+ 绝对拍", Im.NORMAL, 13)) add = 0;
        }
        if (add >= 0) {
            s.mark();
            Doc.Ev ne = newEvent(list, n, ch, base);
            ne.rel = add == 1;
            list.add(ne);
            s.changed(false);
            evSel = list.indexOf(ne);
        }
        y += 40f;
    }

    /** 横移 (CHART_FORMAT §3, Kelvin 2026-10-28): body offset from the lane centre; set on every selected lane note. */
    private void offsetX(Session s, List<Doc.DNote> sel, Doc.DNote n) {
        float v = num("n-offx", "横移", n.offsetX, "向右");
        caption("键身离轨道中心往右挪多少（负数往左，天上地上都是屏幕的右），先挪再绕旋转中心转，可以拼成绕自己中心转的图形。"
            + "下面的「横移」事件让它随时间左右滑（有事件时，事件外回到轨道中心）。判定和打击效果还在原来的轨道。");
        if (!Float.isFinite(v) || v == n.offsetX) return;
        s.mark();
        for (Doc.DNote x : sel) if (x.inLane()) x.offsetX = v;
        s.changed(false);
    }

    /**
     * 旋转中心: 自转 is fixed at the key's centre (Kelvin 2026-10-29 20:55, a pivot an older chart wrote is kept), or,
     * for lane notes, 公转 about an absolute point of the screen (§3, 10-29 19:04); set on every selected note.
     */
    private void pivot(Session s, List<Doc.DNote> sel, Doc.DNote n) {
        float fw = (CX + CW - FX - 8f) / 2f;
        if (n.inLane()) {
            int mode = im.segmented("n-orb", CX, y, CW, 30f, new String[]{"自转", "公转"}, n.orbit ? 1 : 0);
            y += 38f;
            if ((mode == 1) != n.orbit) {
                s.mark();
                for (Doc.DNote x : sel) if (x.inLane()) x.orbit = mode == 1;
                s.changed(false);
            }
            if (n.orbit) {
                orbit(s, sel, n, fw);
                return;
            }
        }
        boolean old = n.pivotX != 0f || n.pivotY != 0f;
        caption(old ? "自转中心是旧谱写的 (" + Im.fmt(n.pivotX) + ", " + Im.fmt(n.pivotY) + ")，照旧保留；新写的自转都绕键中心。"
            : "自转固定在键中心（长条是底部那块的中心）。");
        if (old && im.button("n-pv0", CX, y, CW, 28f, "改回键中心", Im.NORMAL, 12)) {
            s.mark();
            for (Doc.DNote x : sel) {
                x.pivotX = 0f;
                x.pivotY = 0f;
            }
            s.changed(false);
        }
        if (old) y += 36f;
    }

    /**
     * 公转: the absolute centre (reference frame, y up) shared by the selected lane notes and its moves, then the
     * radius and the angle (Kelvin 2026-10-29 20:55, 21:13) and which wins a 物件坐标冲突.
     */
    private void orbit(Session s, List<Doc.DNote> sel, Doc.DNote n, float fw) {
        g.text("公转中心", CX, y + 15f, 13, Gfx.INK2);
        float ox = im.num("n-ox", FX, y, fw, 30f, n.orbitX, "x");
        float oy = im.num("n-oy", FX + fw + 8f, y, fw, 30f, n.orbitY, "y");
        y += 36f;
        boolean mid = im.button("n-om", CX, y, CW, 28f, "画面中心 (960, 600)", Im.NORMAL, 12);
        y += 36f;
        caption("画面上的绝对坐标：1920 × 1200，x 向右，y 从下往上。不写半径和角度时，键按自己的「旋转」角度绕这一点转，几个键填同一个点就整体转。"
            + "中心的事件让它移动，键跟着平移；事件结束后中心停在终点。选中后预览里的十字就是公转中心。");
        if (mid || Float.isFinite(ox) || Float.isFinite(oy)) {
            s.mark();
            for (Doc.DNote x : sel) {
                if (!x.inLane()) continue;
                x.orbit = true;
                if (mid) {
                    x.orbitX = 960f;
                    x.orbitY = 600f;
                }
                if (Float.isFinite(ox)) x.orbitX = ox;
                if (Float.isFinite(oy)) x.orbitY = oy;
            }
            s.changed(false);
        }
        evGroup("n-orbx", "公转中心 x", Gfx.INK2, n.orbXEv, n, CH_ORBX, n.orbitX);
        evGroup("n-orby", "公转中心 y", Gfx.INK2, n.orbYEv, n, CH_ORBY, n.orbitY);
        g.text("半径 / 角度", CX, y + 15f, 13, Gfx.INK2);
        float r = im.num("n-or", FX, y, fw, 30f, n.orbitRadius, "半径");
        float a = im.num("n-oa", FX + fw + 8f, y, fw, 30f, n.orbitAngle, "度");
        y += 36f;
        caption("写了半径或角度，键头中心就在离公转中心「半径」远、「角度」方向上（0° 朝右，逆时针为正；半径为负伸到另一边），键自己的「旋转」只绕键中心转。"
            + "公转只在中心、半径、角度这几组事件的时段里起作用（一个事件都没有就一直起作用），时段外键回到轨道按「移动」落下。事件结束后停在终点。");
        if (Float.isFinite(r) || Float.isFinite(a)) {
            s.mark();
            for (Doc.DNote x : sel) {
                if (!x.inLane()) continue;
                if (Float.isFinite(r)) x.orbitRadius = r;
                if (Float.isFinite(a)) x.orbitAngle = a;
            }
            s.changed(false);
        }
        evGroup("n-orbr", "公转半径", Gfx.INK2, n.orbRadEv, n, CH_ORBR, Float.isNaN(n.orbitRadius) ? 0f : n.orbitRadius);
        evGroup("n-orba", "公转角度", Gfx.INK2, n.orbAngEv, n, CH_ORBA, Float.isNaN(n.orbitAngle) ? 0f : n.orbitAngle);
        if (!n.polar()) return;
        g.text("半径等比（屏幕不是 16:10 时圆也不变形）", CX, y + 12f, 12, Gfx.INK3);
        boolean rg = im.toggle("n-orig", CX + CW, y + 12f, n.orbitRigid);
        if (rg != n.orbitRigid) {
            s.mark();
            for (Doc.DNote x : sel) if (x.polar()) x.orbitRigid = rg;
            s.changed(false);
        }
        y += 30f;
        if (im.button("n-onp", CX, y, CW, 28f, "不用半径和角度（回到绕中心整体转）", Im.NORMAL, 12)) {
            s.mark();
            for (Doc.DNote x : sel) {
                x.orbitRadius = Float.NaN;
                x.orbitAngle = Float.NaN;
                x.orbRadEv.clear();
                x.orbAngEv.clear();
                x.orbitConflict = NoteOrbit.CONFLICT_UNSET;
                x.orbitRigid = false;
            }
            s.changed(false);
            return;
        }
        y += 36f;
        label("坐标冲突时");
        int c = im.segmented("n-ocf", FX, y, FW, 30f, new String[]{"还没选", "以公转为准", "以移动为准"}, n.orbitConflict);
        y += 38f;
        caption("公转和「移动」算出的键高度不同时（检查列表里的「物件坐标冲突」），看预览后选一个；以移动为准时高度用移动，横坐标仍用公转。没选之前游戏按公转画。");
        if (c != n.orbitConflict) {
            s.mark();
            for (Doc.DNote x : sel) if (x.polar()) x.orbitConflict = c;
            s.changed(false);
        }
    }

    /** @return a beat for the event rows: signed when relative to the judgement. */
    private static String beatText(float v, boolean rel) {
        return rel && v > 0f ? "+" + Im.fmt(v) : Im.fmt(v);
    }

    private Doc.Ev newEvent(List<Doc.Ev> list, Doc.DNote n, int ch, float base) {
        if (!list.isEmpty()) {
            Doc.Ev last = list.get(list.size() - 1);
            return new Doc.Ev(last.end, last.end + 1f, last.to, last.to, 1f);
        }
        float b = ed.snap(ed.beat);
        switch (ch) {
            case CH_MOVE: return new Doc.Ev(n.judgement - 2f, n.judgement, Doc.SPAWN, 0f, 1f);
            case CH_ROT: return new Doc.Ev(n.judgement - 2f, n.judgement, 0f, 0f, 1f);
            case CH_OFFX: return new Doc.Ev(n.judgement - 2f, n.judgement, n.offsetX, n.offsetX, 1f);
            case CH_ORBX: return new Doc.Ev(n.judgement - 2f, n.judgement, n.orbitX, n.orbitX, 1f);
            case CH_ORBY: return new Doc.Ev(n.judgement - 2f, n.judgement, n.orbitY, n.orbitY, 1f);
            case CH_ORBR: {
                float r = Float.isNaN(n.orbitRadius) ? 0f : n.orbitRadius;
                return new Doc.Ev(n.judgement - 2f, n.judgement, r, r, 1f);
            }
            case CH_ORBA: {
                float a = Float.isNaN(n.orbitAngle) ? 0f : n.orbitAngle;
                return new Doc.Ev(n.judgement - 2f, n.judgement, a, a + 360f, 1f);
            }
            case CH_ALPHA: return new Doc.Ev(n.judgement - 2f, n.judgement, 1f, 1f, 1f);
            case CH_HEIGHT: return new Doc.Ev(n.judgement, n.end(), n.height, 0f, 1f);
            default: return new Doc.Ev(b, b + 4f, base, base, 1f);
        }
    }

    /** A small plot of the events' eased curves. */
    private void graph(List<Doc.Ev> list) {
        float gx = CX + 2f, gw = CW - 4f, gh = 64f;
        g.box(gx, y, gw, gh, 5f, Gfx.FIELD, Gfx.ITEM_LINE);
        for (int k = 1; k < 4; k++) g.line(gx + gw * k / 4f, y + 2f, gx + gw * k / 4f, y + gh - 2f, 1f, Gfx.LINE2);
        float t0 = Float.MAX_VALUE, t1 = -Float.MAX_VALUE, v0 = Float.MAX_VALUE, v1 = -Float.MAX_VALUE;
        for (Doc.Ev e : list) {
            t0 = Math.min(t0, e.start);
            t1 = Math.max(t1, e.end);
            v0 = Math.min(v0, Math.min(e.from, e.to));
            v1 = Math.max(v1, Math.max(e.from, e.to));
        }
        if (t1 - t0 < 1e-3f) t1 = t0 + 1f;
        if (v1 - v0 < 1e-3f) {
            v0 -= 1f;
            v1 += 1f;
        }
        for (int i = 0; i < list.size(); i++) {
            Doc.Ev e = list.get(i);
            Color c = i == evSel ? Gfx.GND : Gfx.INK2;
            float px = 0f, py = 0f;
            for (int k = 0; k <= 16; k++) {
                float t = e.start + (e.end - e.start) * k / 16f;
                float sx = gx + 6f + (t - t0) / (t1 - t0) * (gw - 12f);
                float sy = y + gh - 6f - (e.valueAt(t) - v0) / (v1 - v0) * (gh - 12f);
                if (k > 0) g.line(px, py, sx, sy, 1.6f, c);
                px = sx;
                py = sy;
            }
        }
        y += gh + 8f;
    }

    // ================================================================== 场地

    private void fieldTab() {
        Session s = ed.s;
        Doc d = s.doc;
        // unlimited: also the lanes the game lays out without a declaration (default width)
        java.util.TreeSet<Integer> idSet = new java.util.TreeSet<>();
        for (Doc.DLane x : d.lanes) idSet.add(x.id);
        if (d.laneCount == 0) {
            LaneLayout lay = ed.pv.engine().layout();
            for (int i = 0; i < lay.count(); i++) if (lay.active(i)) idSet.add(lay.idAt(i)); // on screen now
        }
        Integer[] ids = idSet.toArray(new Integer[0]);
        title("轨道", ids.length + " 条");
        int mode = d.laneCount > 0 ? 1 : 0;
        int nm = im.segmented("f-mode", CX, y, 160f, 30f, LANE_MODES, mode);
        if (nm != mode) {
            if (nm == 1) setLaneCount(s, d, d.lanes.size());
            else {
                s.mark();
                d.laneCount = 0;
                s.changed(false);
            }
        }
        if (d.laneCount > 0) {
            g.text("总数", CX + 174f, y + 15f, 13, Gfx.INK2);
            float c = im.num("f-count", CX + 210f, y, CW - 210f, 30f, d.laneCount, "条");
            if (!Float.isNaN(c)) setLaneCount(s, d, Math.round(c));
        } else if (im.button("f-tidy", CX + 174f, y, CW - 174f, 30f, "整理轨道", Im.NORMAL, 12)) tidyLanes(s, d);
        y += 38f;
        int n = ids.length, rows = Math.max(1, (n + 8) / 9), per = Math.max(1, (n + rows - 1) / rows);
        float cw = CW / per;
        for (int r = 0; r * per < n; r++) {
            g.box(CX, y, CW, 32f, 6f, Gfx.FIELD, Gfx.BTN_LINE);
            for (int i = r * per; i < Math.min(n, (r + 1) * per); i++) {
                int id = ids[i];
                float x = CX + (i - r * per) * cw;
                boolean on = id == s.laneSel;
                boolean hv = im.hover(x, y, cw, 32f);
                if (on) g.box(x + 1f, y + 1f, cw - 2f, 30f, 4f, Gfx.SEL, Gfx.GND);
                else if (hv) g.rrect(x + 1f, y + 1f, cw - 2f, 30f, 4f, Gfx.BTN);
                if (hv) im.cursor(Cursor.SystemCursor.Hand);
                g.text(EditorScreen.laneName(id), x + cw * 0.5f, y + 16f, 12,
                    on ? Gfx.INK1 : d.lane(id) != null ? Gfx.INK2 : Gfx.INK3, 1);
                if (s.lanePick.contains(id) && !on) g.stroke(x + 2f, y + 2f, cw - 4f, 28f, 4f, 1.5f, Gfx.GND, 0.8f);
                if (im.click(new Im.Pair("f-lane", id), x, y, cw, 32f)) pickLane(s, ids, id, on);
            }
            y += 36f;
        }
        y += 4f;
        boolean pair = s.lanePick.size() == 2 && d.adjacent(s.lanePick.first(), s.lanePick.last());
        float bw = (CW - 8f) / 2f;
        if (im.button("f-ins0", CX, y, bw, 30f, "插入0号轨", d.lane(0) == null ? Im.NORMAL : Im.GHOST, 12)) {
            if (d.lane(0) != null) im.toast("已经有 0 号轨了");
            else insertLane(s, d, -1, 1);
        }
        String pairText = pair
            ? "在 " + EditorScreen.laneName(s.lanePick.first()) + " 和 " + EditorScreen.laneName(s.lanePick.last())
            + " 之间插入" : "在两条之间插入";
        if (im.button("f-ins", CX + bw + 8f, y, bw, 30f, pairText, pair ? Im.NORMAL : Im.GHOST, 12)) {
            if (pair) insertLane(s, d, s.lanePick.first(), s.lanePick.last());
            else im.toast("先按住 Ctrl 点选相邻的两条轨道");
        }
        y += 38f;
        if (d.laneCount > 0) caption("有限轨：只有这 " + d.laneCount + " 条轨道，音符不能放到别的轨道上。改总数时，要删掉的轨道上有音符会先问你。");
        else caption("无限轨：轨道从中轴往两边排，每条宽度固定（默认 240），画面露出几条就画几条，外面的轨道也在。音符可以放到任何编号的轨道。「整理轨道」删掉没放音符、整首歌都不在画面上的轨道。");
        caption("点编号选一条轨道改宽度和透明度。插入的轨道从当前拍开始，1 拍内从 0 长到两边轨道的平均宽度；插在别的两条之间时，外侧轨道连同音符顺延一个编号。");
        Doc.DLane l = d.lane(s.laneSel);
        if (s.lanePick.size() > 1) partialScale(s, d);
        else if (l != null) laneCard(s, d, l);
        else if (s.laneSel != Session.NO_LANE && d.laneCount == 0 && idSet.contains(s.laneSel)) {
            caption("轨道 " + EditorScreen.laneName(s.laneSel) + " 用默认设置（宽 " + Im.fmt(Lane.DEFAULT_WIDTH)
                + "），要单独改宽度或透明度就点下面。");
            if (im.button("f-make", CX, y, 160f, 30f, "单独设置这条轨道", Im.NORMAL, 12)) {
                s.mark();
                d.ensureLane(s.laneSel);
                s.changed(false);
            }
            y += 40f;
        }
        divider();
        title("全局缩放", null);
        lineRow(s, 3, "倍数", Gfx.FX);
        caption(d.laneCount > 0 ? "有限轨的轨道分满「画面宽 × 倍数」，以中轴为准；点右边加事件让它随时间变。"
            : "所有轨道的宽度一起乘这个倍数，以中轴为准；点右边加事件让它随时间变。");
        divider();
        title("镜头平移", null);
        lineRow(s, 2, "平移", Gfx.INK2);
        caption("中轴（−1 和 +1 轨之间）离画面中心多远，正数往右，所有轨道跟着移；点右边加事件让它随时间移动。全局事件都是绝对拍。");
        divider();
        title("判定线", null);
        lineRow(s, 0, "上线 y", Gfx.INK1);
        lineRow(s, 1, "下线 y", Gfx.GND);
        caption("判定区 = 线上下各 " + Math.round(GameEngine.ZONE_HALF) + "（跟着线动）");
    }

    private void laneCard(Session s, Doc d, Doc.DLane l) {
        LaneLayout lay = ed.pv.engine().layout();
        int li = lay.indexOf(l.id);
        g.box(CX, y, CW, 34f, 6f, Gfx.ITEM_ON, Gfx.GND);
        g.text(EditorScreen.laneName(l.id), CX + 12f, y + 17f, 13, Gfx.GND);
        g.text("宽 " + Im.fmt(l.width) + " · 透明 " + Im.fmt(l.opacity), CX + 44f, y + 17f, 12, Gfx.INK1);
        if (li >= 0) g.text("此刻 " + Math.round(lay.width(li)), CX + CW - 12f, y + 17f, 12, Gfx.INK3, 2);
        y += 42f;
        if (l.insert != null) insertEditor(s, l);
        float w = l.insert != null ? Float.NaN : num("f-w", d.laneCount > 0 ? "宽度权重" : "宽度", l.width, "基础");
        if (!Float.isNaN(w)) {
            if (w >= 0f) {
                s.mark();
                l.width = w;
                s.changed(false);
            } else im.toast("宽度不能小于 0");
        }
        float o = num("f-o", "透明度", l.opacity, "只影响轨道装饰");
        if (!Float.isNaN(o)) {
            s.mark();
            l.opacity = Math.max(0f, Math.min(1f, o));
            s.changed(false);
        }
        evGroup("f-wev", "宽度事件 Width", Gfx.FX, l.widthEv, null, CH_LANE, l.width);
        evGroup("f-oev", "透明事件 Opacity", Gfx.INK2, l.opacityEv, null, CH_LANE, l.opacity);
        if (l.delete != null) deleteEditor(s, l);
        else {
            if (im.button("f-dl", CX, y, CW, 30f, "+ 删除轨道事件（从当前拍开始）", Im.NORMAL, 12)) {
                deleteLane(s, l, ed.snap(ed.beat));
            }
            y += 40f;
        }
        if (l.insert != null) {
            if (im.button("f-del", CX, y, 160f, 30f, "删掉插入的轨道", Im.GHOST, 12)) removeLane(s, d, l.id);
            y += 40f;
        } else if (d.laneCount == 0) {
            if (im.button("f-del", CX, y, 160f, 30f, "恢复默认（宽 " + Im.fmt(Lane.DEFAULT_WIDTH) + "）", Im.GHOST, 12)) {
                resetLane(s, d, l);
            }
            y += 40f;
        }
    }

    /** An inserted lane's insertion (§3.6), edited like an event; its final size is the lane's width. */
    private void insertEditor(Session s, Doc.DLane l) {
        Doc.Ev e = l.insert;
        float w2 = (CW - 8f) / 2f, w3 = (CW - 16f) / 3f;
        float a = im.num("f-is", CX, y, w2, 30f, e.start, "拍");
        float b = im.num("f-ie", CX + w2 + 8f, y, w2, 30f, e.end, "拍");
        y += 36f;
        float f0 = im.num("f-ia", CX, y, w3, 30f, e.from, "");
        float f1 = im.num("f-ib", CX + w3 + 8f, y, w3, 30f, l.width, "");
        float ex = im.num("f-ip", CX + 2f * (w3 + 8f), y, w3, 30f, e.exp, "");
        y += 34f;
        g.text("插入：开始拍 · 结束拍 / 初始大小 · 最终大小 · 增长速度", CX, y + 7f, 11, Gfx.INK3);
        y += 22f;
        caption("结束拍等于开始拍、或初始大小等于最终大小时一下子出现；插完以后就是普通轨道，下面的宽度事件从那时起生效。插入开始前别在这条轨道上放音符。");
        if (Float.isNaN(a) && Float.isNaN(b) && Float.isNaN(f0) && Float.isNaN(f1) && Float.isNaN(ex)) return;
        if (f0 < 0f || f1 < 0f || ex <= 0f) {
            im.toast(ex <= 0f ? "增长速度（指数）要大于 0" : "大小不能小于 0");
            return;
        }
        s.mark();
        if (!Float.isNaN(a)) e.start = a;
        if (!Float.isNaN(b)) e.end = b;
        if (e.end < e.start) {
            float t = e.start;
            e.start = e.end;
            e.end = t;
        }
        if (!Float.isNaN(f0)) e.from = f0;
        if (!Float.isNaN(f1)) l.width = e.to = f1;
        if (!Float.isNaN(ex)) e.exp = ex;
        s.changed(false);
    }

    /**
     * A lane's deletion (§3.7), edited like an event: from its start it overrides the width, and after its end the lane
     * is gone.
     */
    private void deleteEditor(Session s, Doc.DLane l) {
        Doc.Ev e = l.delete;
        float w2 = (CW - 8f) / 2f, w3 = (CW - 16f) / 3f;
        float a = im.num("f-ds", CX, y, w2, 30f, e.start, "拍");
        float b = im.num("f-de", CX + w2 + 8f, y, w2, 30f, e.end, "拍");
        y += 36f;
        float f0 = im.num("f-da", CX, y, w3, 30f, e.from, "");
        float f1 = im.num("f-db", CX + w3 + 8f, y, w3, 30f, e.to, "");
        float ex = im.num("f-dp", CX + 2f * (w3 + 8f), y, w3, 30f, e.exp, "");
        y += 34f;
        g.text("删除：开始拍 · 结束拍 / 开始宽度 · 最后宽度 · 速度", CX, y + 7f, 11, Gfx.INK3);
        y += 22f;
        caption("从开始拍起轨道按这里缩小，宽度事件不再起作用；结束拍以后这条轨道就没了，两边合拢，屏幕里的轨道一起变宽。删完以后别在这条轨道上放音符。");
        boolean undo = im.button("f-dx", CX, y, 160f, 30f, "取消删除", Im.GHOST, 12);
        y += 40f;
        if (undo) {
            s.mark();
            l.delete = null;
            s.changed(false);
            return;
        }
        if (Float.isNaN(a) && Float.isNaN(b) && Float.isNaN(f0) && Float.isNaN(f1) && Float.isNaN(ex)) return;
        if (f0 < 0f || f1 < 0f || ex <= 0f) {
            im.toast(ex <= 0f ? "速度（指数）要大于 0" : "宽度不能小于 0");
            return;
        }
        s.mark();
        if (!Float.isNaN(a)) e.start = a;
        if (!Float.isNaN(b)) e.end = b;
        if (e.end < e.start) {
            float t = e.start;
            e.start = e.end;
            e.end = t;
        }
        if (!Float.isNaN(f0)) e.from = f0;
        if (!Float.isNaN(f1)) e.to = f1;
        if (!Float.isNaN(ex)) e.exp = ex;
        s.changed(false);
    }

    /** Starts deleting lane {@code l} at {@code beat}: within 1 beat from its width there to 0 (§3.7). */
    void deleteLane(Session s, Doc.DLane l, float beat) {
        int li = s.chart != null ? s.chart.laneIndexOf(l.id) : -1;
        float w = li >= 0 ? s.chart.lane(li).widthAt(beat) : l.width;
        s.mark();
        l.delete = new Doc.Ev(beat, beat + 1f, w, 0f, 1f);
        s.changed(false);
        im.toast("轨道 " + EditorScreen.laneName(l.id) + " 从第 " + Im.fmt(beat) + " 拍开始删除，1 拍内收起");
    }

    /** Inserts a lane between the adjacent lanes {@code a} &lt; {@code b} at the playhead and selects it. */
    void insertLane(Session s, Doc d, int a, int b) {
        s.mark();
        int id = d.insertLane(a, b, ed.snap(ed.beat));
        s.lanePick.clear();
        s.laneSel = id;
        s.changed(false);
        im.toast("已插入 " + EditorScreen.laneName(id) + " 轨" + (id == a || id == b ? "，外侧轨道和音符顺延一个编号" : ""));
    }

    /** Removes an inserted lane (asking first when notes are on it); the lanes further out move back in. */
    void removeLane(Session s, Doc d, int id) {
        int on = 0;
        for (Doc.DNote x : d.notes) if (x.inLane() && x.lane == id) on++;
        Runnable apply = () -> {
            s.mark();
            d.removeLane(id);
            s.sel.removeIf(u -> d.byUid(u) == null);
            s.lanePick.clear();
            s.laneSel = Session.NO_LANE;
            s.changed(false);
        };
        if (on == 0) {
            apply.run();
            return;
        }
        Im.Modal m = new Im.Modal("删掉插入的轨道？",
            "轨道 " + EditorScreen.laneName(id) + " 上的 " + on + " 个音符会一起删掉，外侧轨道往回挪一个编号（可以撤销）。",
            new String[]{"删掉", "取消"}, (bt, t) -> {
                if (bt == 0) apply.run();
            });
        m.danger = 0;
        m.primary = 1;
        im.open(m);
    }

    /** Chip click: plain = select one, Ctrl = add / remove, Shift = the range from the selected one. */
    private void pickLane(Session s, Integer[] ids, int id, boolean on) {
        if (im.ctrl) {
            if (s.lanePick.isEmpty() && s.laneSel != Session.NO_LANE) s.lanePick.add(s.laneSel);
            if (!s.lanePick.remove(id)) s.lanePick.add(id);
            s.laneSel = s.lanePick.contains(id) ? id : s.lanePick.isEmpty() ? Session.NO_LANE : s.lanePick.first();
        } else if (im.shift && s.laneSel != Session.NO_LANE) {
            int a = java.util.Arrays.asList(ids).indexOf(s.laneSel), b = java.util.Arrays.asList(ids).indexOf(id);
            s.lanePick.clear();
            if (a < 0) a = b;
            for (int i = Math.min(a, b); i <= Math.max(a, b); i++) s.lanePick.add(ids[i]);
        } else {
            s.lanePick.clear();
            s.laneSel = on ? Session.NO_LANE : id;
        }
    }

    private float psStart = Float.NaN, psEnd, psFrom = 1f, psTo = 0.8f, psExp = 1f;

    /** 部分缩放: one width event on every picked lane, as a multiple of each lane's own width. */
    private void partialScale(Session s, Doc d) {
        StringBuilder names = new StringBuilder();
        for (int id : s.lanePick) names.append(names.length() > 0 ? " " : "").append(EditorScreen.laneName(id));
        g.box(CX, y, CW, 34f, 6f, Gfx.ITEM_ON, Gfx.GND);
        g.text("部分缩放", CX + 12f, y + 17f, 13, Gfx.GND);
        g.text(s.lanePick.size() + " 条：" + g.fit(names.toString(), 12, CW - 120f), CX + 84f, y + 17f, 12, Gfx.INK1);
        y += 42f;
        if (Float.isNaN(psStart)) {
            psStart = ed.snap(ed.beat);
            psEnd = psStart + 2f;
        }
        float v = num("f-ps-s", "开始拍", psStart, "拍");
        if (!Float.isNaN(v)) psStart = v;
        v = num("f-ps-e", "结束拍", psEnd, "拍");
        if (!Float.isNaN(v)) psEnd = v;
        v = num("f-ps-f0", "起始倍数", psFrom, "× 轨道宽");
        if (!Float.isNaN(v)) psFrom = v;
        v = num("f-ps-f1", "结束倍数", psTo, "× 轨道宽");
        if (!Float.isNaN(v)) psTo = v;
        v = num("f-ps-x", "指数", psExp, "1 = 匀速");
        if (!Float.isNaN(v)) psExp = v;
        if (im.button("f-ps-now", CX, y, 120f, 30f, "从播放头开始", Im.GHOST, 12)) {
            float len = psEnd - psStart;
            psStart = ed.snap(ed.beat);
            psEnd = psStart + (len > 0f ? len : 2f);
        }
        if (im.button("f-ps-go", CX + 128f, y, CW - 128f, 30f, "给这 " + s.lanePick.size() + " 条轨道加宽度事件",
            Im.PRIMARY, 12)) {
            applyPartialScale(s, d);
        }
        y += 40f;
        caption("宽度 = 轨道自己的宽 × 倍数（240 × 0.8 = 192），结束后保持；要变回去，就在后面再加一段 0.8 → 1。点编号时按住 Ctrl 多选，Shift 连选。");
    }

    void applyPartialScale(Session s, Doc d) {
        if (!(psEnd > psStart)) {
            im.toast("结束拍要晚于开始拍");
            return;
        }
        if (psFrom < 0f || psTo < 0f) {
            im.toast("倍数不能小于 0");
            return;
        }
        s.mark();
        for (int id : s.lanePick) {
            d.ensureLane(id);
            Doc.DLane l = d.lane(id);
            l.widthEv.add(new Doc.Ev(psStart, psEnd, l.width * psFrom, l.width * psTo, psExp));
        }
        s.changed(false);
        im.toast("已给 " + s.lanePick.size() + " 条轨道加宽度事件");
    }

    /**
     * Switches to {@code n} finite lanes ({@link Doc#laneSet}); lanes outside the set go, and when
     * notes sit on them the charter is asked first (those notes go with them).
     */
    void setLaneCount(Session s, Doc d, int n) {
        if (n < 1 || n > 64) {
            im.toast("轨道总数要在 1 到 64 之间");
            return;
        }
        int[] ids = Doc.laneSet(n);
        java.util.HashSet<Integer> keep = new java.util.HashSet<>();
        for (int id : ids) keep.add(id);
        java.util.TreeMap<Integer, Integer> hit = new java.util.TreeMap<>();
        for (Doc.DNote x : d.notes) if (x.inLane() && !keep.contains(x.lane)) hit.merge(x.lane, 1, Integer::sum);
        Runnable apply = () -> {
            s.mark();
            d.notes.removeIf(x -> x.inLane() && !keep.contains(x.lane));
            d.lanes.removeIf(x -> !keep.contains(x.id));
            for (int id : ids) d.ensureLane(id);
            d.laneCount = n;
            s.sel.removeIf(u -> d.byUid(u) == null);
            if (!keep.contains(s.laneSel)) s.laneSel = Session.NO_LANE;
            s.changed(false);
        };
        if (hit.isEmpty()) {
            apply.run();
            return;
        }
        int total = 0;
        StringBuilder names = new StringBuilder();
        for (java.util.Map.Entry<Integer, Integer> e : hit.entrySet()) {
            total += e.getValue();
            if (names.length() > 0) names.append("、");
            names.append(EditorScreen.laneName(e.getKey()));
        }
        Im.Modal m = new Im.Modal("删掉这些轨道上的音符？",
            "改成 " + n + " 条轨道会去掉 " + names + " 轨，上面的 " + total + " 个音符会一起删掉（可以撤销）。",
            new String[]{"删掉并改成 " + n + " 条", "取消"}, (b, t) -> {
                if (b == 0) apply.run();
            });
        m.danger = 0;
        m.primary = 1;
        im.open(m);
    }

    /** 整理轨道 (unlimited lanes): asks, then drops the declared lanes {@link #lanesToTidy} finds. */
    void tidyLanes(Session s, Doc d) {
        java.util.TreeSet<Integer> kept = new java.util.TreeSet<>(), drop = lanesToTidy(d, kept);
        if (drop.isEmpty()) {
            im.toast(kept.isEmpty() ? "没有要整理的轨道" : laneNames(kept) + " 有自己的设置，删了会改变画面，没有删");
            return;
        }
        String msg = "删掉 " + drop.size() + " 条没放音符、整首歌都不会出现在画面上的轨道：" + laneNames(drop) + "（可以撤销）。";
        if (!kept.isEmpty()) msg += laneNames(kept) + " 也没用过，但有自己的设置，删了会改变画面，留着。";
        Im.Modal m = new Im.Modal("整理轨道", msg, new String[]{"删掉 " + drop.size() + " 条", "取消"}, (b, t) -> {
            if (b != 0) return;
            s.mark();
            s.doc.lanes.removeIf(l -> drop.contains(l.id));
            s.lanePick.removeAll(drop);
            if (drop.contains(s.laneSel)) s.laneSel = Session.NO_LANE;
            s.changed(false);
            im.toast("已删掉 " + drop.size() + " 条轨道");
        });
        m.primary = 0;
        im.open(m);
    }

    /**
     * Unlimited lanes: the declared lanes no note uses that never come on screen (sampled every
     * 1/16 beat and at both ends of every layout event). A dropped lane comes back as a default
     * lane, so one with its own settings is dropped only when the game's layout without it puts
     * every lane on screen exactly where it was; otherwise it goes to {@code kept}. One declared
     * lane always stays.
     */
    static java.util.TreeSet<Integer> lanesToTidy(Doc d, java.util.TreeSet<Integer> kept) {
        java.util.TreeSet<Integer> drop = new java.util.TreeSet<>();
        if (d.laneCount > 0 || d.lanes.size() <= 1) return drop;
        float[] beats = layoutBeats(d);
        java.util.HashSet<Integer> busy = new java.util.HashSet<>();
        Chart c = build(d, drop);
        if (c == null) return drop;
        long screen = screen(c, beats, busy);
        for (Doc.DNote n : d.notes) if (n.inLane()) busy.add(n.lane);
        ArrayList<Doc.DLane> own = new ArrayList<>();
        for (Doc.DLane l : d.lanes) {
            if (busy.contains(l.id) || l.insert != null || l.delete != null) continue;
            if (l.width == Lane.DEFAULT_WIDTH && l.opacity == Lane.DEFAULT_OPACITY && l.widthEv.isEmpty()
                && l.opacityEv.isEmpty()) drop.add(l.id);
            else own.add(l);
        }
        own.sort((a, b) -> Integer.compare(Math.abs(b.id), Math.abs(a.id))); // outermost first
        for (Doc.DLane l : own) {
            drop.add(l.id);
            Chart w = build(d, drop);
            if (w != null && screen(w, beats, null) == screen) continue;
            drop.remove(l.id);
            kept.add(l.id);
        }
        if (!drop.isEmpty() && drop.size() == d.lanes.size()) {
            int inner = drop.first();
            for (int id : drop) if (Math.abs(id) < Math.abs(inner)) inner = id;
            drop.remove(inner);
        }
        return drop;
    }

    /**
     * Every 1/16 beat from 0 (or the first layout event) to a beat past the end, and both ends of every lane width,
     * global scale and camera frame event.
     */
    private static float[] layoutBeats(Doc d) {
        ArrayList<Doc.Ev> evs = new ArrayList<>(d.laneScaleEv);
        evs.addAll(d.cameraFrameEv);
        for (Doc.DLane l : d.lanes) {
            evs.addAll(l.widthEv);
            if (l.insert != null) evs.add(l.insert);
            if (l.delete != null) evs.add(l.delete);
        }
        float lo = 0f, hi = d.lastBeat() + 1f;
        for (Doc.Ev e : evs) {
            lo = Math.min(lo, e.start);
            hi = Math.max(hi, e.end);
        }
        int n = (int) Math.ceil((hi - lo) * 16f) + 1;
        float[] b = new float[n + 2 * evs.size()];
        for (int i = 0; i < n; i++) b[i] = lo + i / 16f;
        for (int i = 0; i < evs.size(); i++) {
            b[n + 2 * i] = evs.get(i).start;
            b[n + 2 * i + 1] = evs.get(i).end;
        }
        return b;
    }

    /** The game's chart of {@code d} without the declared lanes in {@code drop}; null when it does not load. */
    private static Chart build(Doc d, java.util.Set<Integer> drop) {
        ArrayList<Doc.DLane> all = new ArrayList<>(d.lanes);
        d.lanes.removeIf(l -> drop.contains(l.id));
        try {
            byte[] json = d.toJson(Session.SENTINEL).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            return ChartMapper.read(new java.io.ByteArrayInputStream(json), "tidy");
        } catch (Exception e) {
            return null;
        } finally {
            d.lanes.clear();
            d.lanes.addAll(all);
        }
    }

    /** A hash of every lane on screen (id and span) at each sample beat; adds their ids to {@code seen}. */
    private static long screen(Chart c, float[] beats, java.util.Set<Integer> seen) {
        LaneLayout lay = new LaneLayout(c);
        long h = 17L;
        for (float b : beats) {
            lay.update(b, LaneLayout.VIEW_LEFT, LaneLayout.VIEW_RIGHT, 1f);
            for (int i = 0; i < lay.count(); i++) {
                if (!lay.active(i) || lay.right(i) <= LaneLayout.VIEW_LEFT + 0.5f
                    || lay.left(i) >= LaneLayout.VIEW_RIGHT - 0.5f) continue;
                if (seen != null) seen.add(lay.idAt(i));
                h = (h * 31L + lay.idAt(i)) * 31L + Math.round(lay.left(i) * 4f);
                h = h * 31L + Math.round(lay.right(i) * 4f);
            }
            h = h * 31L + 7L;
        }
        return h;
    }

    private static String laneNames(Iterable<Integer> ids) {
        StringBuilder b = new StringBuilder();
        for (int id : ids) b.append(b.length() > 0 ? "、" : "").append(EditorScreen.laneName(id));
        return b.toString();
    }

    /** Unlimited lanes: drops the lane's own settings; the lane (and its notes) stays, at the default width. */
    private void resetLane(Session s, Doc d, Doc.DLane l) {
        if (d.lanes.size() <= 1) {
            im.toast("至少要留一条轨道");
            return;
        }
        s.mark();
        d.lanes.remove(l);
        s.changed(false);
    }

    private void lineRow(Session s, int k, String label, Color c) {
        Doc d = s.doc;
        List<Doc.Ev> list = Checks.lineLists(d).get(k);
        float base = k == 0 ? d.upper : k == 1 ? d.lower : k == 2 ? d.cameraFrame : d.laneScale;
        g.text(label, CX, y + 15f, 13, c);
        float v = im.num(LINE_IDS[k] + "-v", FX, y, FW - 66f, 30f, base, "");
        boolean isOpen = LINE_IDS[k].equals(open);
        String cnt = list.isEmpty() ? "0 ›" : list.size() + (isOpen ? " 段 ▼" : " 段 ›");
        if (im.button(LINE_IDS[k], FX + FW - 60f, y, 60f, 30f, cnt, isOpen ? Im.ON : Im.GHOST, 12)) {
            open = isOpen ? null : LINE_IDS[k];
            evSel = list.isEmpty() ? -1 : 0;
        }
        y += 38f;
        if (!Float.isNaN(v)) {
            if (k == 3 && v <= 0f) {
                im.toast("倍数要大于 0");
            } else {
                s.mark();
                if (k == 0) d.upper = v;
                else if (k == 1) d.lower = v;
                else if (k == 2) d.cameraFrame = v;
                else d.laneScale = v;
                s.changed(false);
            }
        }
        if (LINE_IDS[k].equals(open)) evEditor(LINE_IDS[k], list, null, CH_LINE, base);
    }

    // ================================================================== 漩涡

    /**
     * 漩涡 (§6.10, Kelvin 2026-10-23) or 水帘 (CHART_FORMAT §6.1, Kelvin 2026-10-31: a curtain blurs everything under
     * it, the HUD too, and is never judged): every one in a list, a circle or a polygon added at the playhead, and the
     * selected one's shape, place, time, events, glitch and distortion (a curtain's blur too).
     */
    private void veilTab(boolean curtain) {
        Session s = ed.s;
        List<Doc.DVeil> list = curtain ? s.doc.curtains : s.doc.veils;
        String name = curtain ? "\u6c34\u5e18" : "\u6f29\u6da1";
        title(name, list.isEmpty() ? null : list.size() + " \u5757");
        for (int i = 0; i < list.size(); i++) {
            Doc.DVeil v = list.get(i);
            boolean on = v.uid == s.veilSel, hv = im.hover(CX, y, CW, 32f);
            if (hv) im.cursor(Cursor.SystemCursor.Hand);
            g.box(CX, y, CW, 32f, 5f, on ? Gfx.ITEM_ON : Gfx.ITEM, on ? Gfx.GND : hv ? Gfx.SEL_LINE : Gfx.ITEM_LINE);
            g.text(name + " \u00b7 #" + (i + 1), CX + 12f, y + 16f, 12, Gfx.INK3);
            g.text(v.polygon ? "\u591a\u8fb9\u5f62 \u00b7 " + v.pointCount() + " \u4e2a\u70b9"
                    : "\u5706\u5f62 \u00b7 \u534a\u5f84 " + Im.fmt(v.radius), CX + 88f,
                y + 16f, 13, v.polygon && v.pointCount() < 3 ? Gfx.BAD : Gfx.INK1);
            g.text(span(v), CX + CW - 12f, y + 16f, 12, Gfx.INK3, 2);
            if (im.click(new Im.Pair("v-row", i), CX, y, CW, 32f)) pick(v);
            y += 38f;
        }
        float w2 = (CW - 8f) / 2f;
        int add = -1;
        if (im.button("v-add-c", CX, y, w2, 32f, "+ \u5706\u5f62" + name, Im.NORMAL, 13)) add = 0;
        if (im.button("v-add-p", CX + w2 + 8f, y, w2, 32f, "+ \u591a\u8fb9\u5f62" + name, Im.NORMAL, 13)) add = 1;
        y += 40f;
        if (add >= 0) {
            s.mark();
            Doc.DVeil v = new Doc.DVeil();
            v.polygon = add == 1;
            if (v.polygon) v.points = ring(6, Veil.DEFAULT_RADIUS);
            v.start = ed.snap(ed.beat);
            v.end = v.start + 8f;
            (curtain ? s.doc.curtains : s.doc.veils).add(v);
            s.veilSel = v.uid;
            s.changed(false);
        }
        caption(curtain ? "\u6c34\u5e18\u53ea\u6a21\u7cca\u8986\u76d6\u753b\u9762\uff0c\u4e0d\u53c2\u4e0e\u5224\u5b9a\uff1b\u53ef\u8bbe\u65f6\u95f4\u3001\u5f62\u72b6\u3001\u79fb\u52a8\u548c\u52a8\u753b\u3002"
            : "\u6f29\u6da1\u4f1a\u963b\u6321\u5224\u5b9a\uff1b\u53ef\u8bbe\u65f6\u95f4\u3001\u5f62\u72b6\u3001\u79fb\u52a8\u548c\u52a8\u753b\u3002");
        Doc.DVeil v = s.doc.veilByUid(s.veilSel);
        if (v == null || !list.contains(v)) return;
        divider();
        veilCard(s, v, list, name);
    }

    /** Selects a veil from the list (a second click clears it); seeks into its time when the playhead is outside. */
    private void pick(Doc.DVeil v) {
        Session s = ed.s;
        if (s.veilSel == v.uid) {
            s.veilSel = 0;
            return;
        }
        s.veilSel = v.uid;
        if (ed.beat < v.start || ed.beat > v.end) ed.seekBeat(Float.isNaN(v.start) ? v.end : v.start);
    }

    private void veilCard(Session s, Doc.DVeil v, List<Doc.DVeil> list, String kind) {
        boolean curtain = s.doc.curtains.contains(v);
        title("第 " + (list.indexOf(v) + 1) + " 块" + kind, null);
        int shape = v.polygon ? 1 : 0;
        int sh = im.segmented("v-shape", CX, y, CW, 32f, new String[]{"圆形 · 同心圆", "多边形 · 光网"}, shape);
        y += 40f;
        if (sh != shape) {
            s.mark();
            v.polygon = sh == 1;
            if (v.polygon && v.pointCount() < 3) v.points = ring(6, v.radius);
            s.changed(false);
        }
        if (v.polygon) {
            points(s, v);
        } else {
            float r = num("v-r", "半径", v.radius, "");
            if (!Float.isNaN(r) && r != v.radius) {
                s.mark();
                v.radius = clamp(r, Veil.MIN_SIZE, Veil.MAX_SIZE);
                s.changed(false);
            }
        }
        float w2 = (FW - 8f) / 2f;
        label("中心");
        float cx = im.num("v-x", FX, y, w2, 30f, v.x, "x");
        float cy = im.num("v-y", FX + w2 + 8f, y, w2, 30f, v.y, "y");
        y += 38f;
        float ro = num("v-rot", "角度", v.rotation, "度");
        float op = num("v-op", "透明度", v.opacity, "0–1");
        label("出现");
        String fs = im.field("v-from", FX, y, w2, 30f, Float.isNaN(v.start) ? "" : Im.fmt(v.start), "从", true);
        String ts = im.field("v-to", FX + w2 + 8f, y, w2, 30f, Float.isNaN(v.end) ? "" : Im.fmt(v.end), "到", true);
        y += 38f;
        caption("中心按 1920 × 1200 的画面量，y 向上；角度逆时针。出现空着 = 不限。");
        if (curtain) {
            float bl = num("v-blur", "模糊强度", v.blur, "0–" + Im.fmt(Veil.MAX_BLUR));
            if (Float.isFinite(bl) && bl != v.blur) {
                s.mark();
                v.blur = clamp(bl, 0f, Veil.MAX_BLUR);
                s.changed(false);
            }
            caption("按 1920 × 1200 的画面量，默认 " + Im.fmt(Veil.DEFAULT_BLUR) + "；0 = 不模糊。");
        }
        if (!Float.isNaN(cx) || !Float.isNaN(cy) || !Float.isNaN(ro) || !Float.isNaN(op) || fs != null || ts != null) {
            s.mark();
            if (Float.isFinite(cx)) v.x = cx;
            if (Float.isFinite(cy)) v.y = cy;
            if (Float.isFinite(ro)) v.rotation = ro;
            if (Float.isFinite(op)) v.opacity = clamp(op, 0f, 1f);
            if (fs != null) v.start = parseOrNaN(fs);
            if (ts != null) v.end = parseOrNaN(ts);
            if (v.start > v.end) { // the game swaps them too
                float t = v.start;
                v.start = v.end;
                v.end = t;
            }
            s.changed(false);
        }
        divider();
        title("运动", null);
        evGroup("v-xev", "横移 x", Gfx.GND, v.xEv, null, CH_VEIL, v.x);
        evGroup("v-yev", "竖移 y", Gfx.GND, v.yEv, null, CH_VEIL, v.y);
        evGroup("v-rotev", "旋转 Rotation", Gfx.INK2, v.rotEv, null, CH_VEIL, v.rotation);
        evGroup("v-opev", "透明 Opacity", Gfx.INK2, v.opacityEv, null, CH_VEIL, v.opacity);
        if (curtain) evGroup("v-blurev", "模糊 Blur", Gfx.INK2, v.blurEv, null, CH_VEIL, v.blur);
        caption(curtain ? "事件之外用上面的值；模糊的范围跟着水帘移动和旋转。" : "事件之外用上面的值；挡住的范围跟着漩涡移动和旋转。");
        divider();
        glitch(s, v.glitches, "v", v.start, v.end, curtain ? "只改变水帘的样子，模糊的范围不变。" : "只改变漩涡的样子，挡住的范围不变。");
        divider();
        distortion(s, v.distortions, "v", v.start, v.end, 2f,
            kind + "平常就有扭曲（1）；0 = 不扭曲，2 = 两倍，最多 " + Im.fmt(NoteDistortion.MAX_INTENSITY) + "。");
        divider();
        if (im.button("v-copy", CX, y, 70f, 34f, "复制", Im.NORMAL)) {
            s.mark();
            Doc.DVeil c = v.copy(false);
            c.shift(60f, -60f);
            (curtain ? s.doc.curtains : s.doc.veils).add(c);
            s.veilSel = c.uid;
            s.changed(false);
        }
        if (im.button("v-del", CX + CW - 70f, y, 70f, 34f, "删除", Im.DANGER)) ed.deleteVeil();
        y += 46f;
    }

    /** A polygon's points from its centre (x right, y up) in drawing order, 3 to {@link Veil#MAX_POINTS}. */
    private void points(Session s, Doc.DVeil v) {
        int n = v.pointCount();
        float w2 = (FW - 42f) / 2f;
        for (int i = 0; i < n; i++) {
            label("点 " + (i + 1));
            float px = im.num(new Im.Pair("v-px", i), FX, y, w2, 30f, v.points[2 * i], "x");
            float py = im.num(new Im.Pair("v-py", i), FX + w2 + 8f, y, w2, 30f, v.points[2 * i + 1], "y");
            boolean del = n > 3
                && im.button(new Im.Pair("v-pdel", i), FX + FW - 28f, y + 3f, 26f, 24f, "×", Im.GHOST, 14);
            y += 38f;
            if (Float.isNaN(px) && Float.isNaN(py) && !del) continue;
            s.mark();
            if (del) {
                float[] p = new float[v.points.length - 2];
                System.arraycopy(v.points, 0, p, 0, 2 * i);
                System.arraycopy(v.points, 2 * i + 2, p, 2 * i, p.length - 2 * i);
                v.points = p;
            } else {
                if (Float.isFinite(px)) v.points[2 * i] = clamp(px, -Veil.MAX_SIZE, Veil.MAX_SIZE);
                if (Float.isFinite(py)) v.points[2 * i + 1] = clamp(py, -Veil.MAX_SIZE, Veil.MAX_SIZE);
            }
            s.changed(false);
            return;
        }
        if (n >= 1 && n < Veil.MAX_POINTS) {
            if (im.button("v-padd", CX, y, 160f, 30f, "+ 加一个点", Im.GHOST, 12)) {
                s.mark();
                float[] p = java.util.Arrays.copyOf(v.points, v.points.length + 2);
                p[2 * n] = Math.round((p[2 * n - 2] + p[0]) * 0.5f);          // halfway along the closing edge
                p[2 * n + 1] = Math.round((p[2 * n - 1] + p[1]) * 0.5f);
                v.points = p;
                s.changed(false);
            }
            y += 38f;
        }
        caption("点从中心量，x 向右、y 向上，按顺序连成一圈；3 到 " + Veil.MAX_POINTS + " 个点，新的点加在最后一个和第一个中间。");
    }

    /** @return a veil's time for the list: 「4 → 12 拍」, 「第 4 拍起」, 「到第 12 拍」 or 「一直都在」. */
    private static String span(Doc.DVeil v) {
        boolean a = !Float.isNaN(v.start), b = !Float.isNaN(v.end);
        if (a && b) return Im.fmt(v.start) + " → " + Im.fmt(v.end) + " 拍";
        if (a) return "第 " + Im.fmt(v.start) + " 拍起";
        return b ? "到第 " + Im.fmt(v.end) + " 拍" : "一直都在";
    }

    /** @return a regular polygon of {@code n} points on radius {@code r}, the first straight up (whole units). */
    static float[] ring(int n, float r) {
        float[] p = new float[2 * n];
        for (int k = 0; k < n; k++) {
            double a = 2.0 * Math.PI * k / n;
            p[2 * k] = Math.round(r * Math.sin(a));
            p[2 * k + 1] = Math.round(r * Math.cos(a));
        }
        return p;
    }
}
