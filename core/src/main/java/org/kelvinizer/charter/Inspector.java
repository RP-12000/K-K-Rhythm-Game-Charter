package org.kelvinizer.charter;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Cursor;
import org.kelvinizer.elements.Chart;
import org.kelvinizer.elements.Floating;
import org.kelvinizer.elements.Line;
import org.kelvinizer.elements.Note;
import org.kelvinizer.elements.NoteDistortion;
import org.kelvinizer.elements.NoteGlitch;
import org.kelvinizer.elements.NoteOrbit;
import org.kelvinizer.elements.NoteTrail;
import org.kelvinizer.elements.NoteType;
import org.kelvinizer.elements.Stroke;
import org.kelvinizer.elements.Veil;
import org.kelvinizer.gameplay.GameEngine;
import org.kelvinizer.library.Difficulty;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;

/**
 * 右边的属性面板：「谱面」（歌曲信息、难度、变速、检查）、「音符」（类型、时间、运动事件、拖拽残影、故障、扭曲）、
 * 「场地」（定轨、坐标吸附、缩放、镜头平移、判定线）、「漩涡」和「水帘」。滚轮、滚动条或按住空白处拖动都能滚动。
 * The inspector on the right with five tabs: 谱面 (song texts, difficulty, tempo
 * map, offset, checks), 音符 (type, line, x, timing, 假键, 悬浮键 with their place, size, appearance, fill and DRAG
 * chain, events, 不跟判定线, trail, glitch and distortion segments), 场地 (定轨, x snapping, global scale, camera
 * frame, judgement lines), 漩涡 (veils: shape, place, time, events, glitch and distortion) and 水帘 (curtains: the
 * same, plus their blur). Every edit goes through the session's undo. The panel scrolls with the wheel, its
 * scrollbar or a drag on empty space.
 */
final class Inspector {
    static final int CHART = 0, NOTE = 1, FIELD = 2, VEIL = 3, CURTAIN = 4, STROKE = 5;
    private static final String[] TABS = {"\u8c31\u9762", "\u97f3\u7b26", "\u573a\u5730", "\u6f29\u6da1",
        "\u6c34\u5e18", "线条"};
    private static final String[] LINES = {"上线", "下线"};
    private static final float X = EditorScreen.TR, TOP = 102f, CX = X + 19f, CW = Gfx.W - 18f - CX,
            FX = X + 101f, FW = Gfx.W - 18f - FX;
    private static final int CH_MOVE = 0, CH_ROT = 1, CH_ALPHA = 2, CH_HEIGHT = 3, CH_LINE = 5,
        CH_VEIL = 6, CH_OFFX = 7, CH_ORBX = 8, CH_ORBY = 9, CH_ORBR = 10, CH_ORBA = 11,
        CH_XLAY = 12, CH_POSLAY = 13, CH_ROTLAY = 14, CH_LINELAY = 15, CH_FY = 16, CH_FSIZE = 17, CH_FILL = 18;
    /** Field rows by {@link Checks#fieldLists} index: the two lines, camera, scale, then the three line layers. */
    private static final String[] LINE_IDS = {"f-up", "f-low", "f-cam", "f-scale", "f-up-l", "f-low-l", "f-cam-l"};
    /** Start distance of 先快后慢: 1.44 × the default fall, 1555.56 (1400 in the old 1080-high frame). */
    private static final float FAST_SLOW = Doc.SPAWN * 1400f / 972f;
    private static final String[] TEMPLATES = {"正常下落：2 拍内 " + dist(Doc.SPAWN) + " → 0",
        "快速下落：1 拍内 " + dist(Doc.SPAWN) + " → 0", "慢速下落：4 拍内 " + dist(Doc.SPAWN) + " → 0",
        "先快后慢：2 拍内 " + dist(FAST_SLOW) + " → 0", "先慢后快：2 拍内 " + dist(Doc.SPAWN) + " → 0（指数 2）",
        "淡入：接近的前一半透明 0 → 1", "无接近：判定时直接出现在线上",
        "原地出现：从播放头起每 1/4 拍出现一个，停在原地，最后一个出现后接着原来的运动", "长条收起：按住时顶端不动、底端往上收",
        "飞出屏幕：从播放头起 2 拍加速往上飞出画面"};
    /** 坐标吸附 choices (Kelvin 2026-11-05; 10 / 20 / 30 added 11-06): 0 = whole units. */
    private static final float[] X_STEPS = {0f, 10f, 20f, 30f, 60f, 120f, 240f};
    private static final String[] X_STEP_LABELS = {"关", "10", "20", "30", "60", "120", "240"};
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

    /** Opens the event list {@code id} of the inspector (as a click on its header), its first card selected. */
    void openEvents(String id) {
        open = id;
        evSel = 0;
        evPick.clear();
    }

    /** Opens the event list of a field-row bar ({@code LINE_BAR − line channel}). */
    void openLine(int id) {
        open = LINE_IDS[Math.max(0, Math.min(LINE_IDS.length - 1, EditorScreen.LINE_BAR - id))];
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
        else if (tab == STROKE) strokeTab();
        contentH = y - y0 + 30f;
        im.popClip();
        if (!im.down) {                                                // a lifted card whose list went away
            dragList = null;
            dragEv = null;
        }
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
                im.slash(ed.snap(ed.beat), Im.beatHint(ed.snap(ed.beat), ""), true);
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
        int nt = im.segmented("n-type", CX, y, CW, 32f, new String[]{"水滴", "水流", "水雷", "浪花", "浪花水雷", "Drag"}, ti);
        if (nt != ti) {
            s.mark();
            NoteType t = NoteType.of(nt);
            if (n.floating && !t.floats()) {
                n.toLine();
                im.toast("浪花不能悬浮，这个键回到了下线");
            }
            setType(s, n, t);
            s.changed(false);
        }
        y += 40f;
        if (!n.floating) {
            int li = n.line == Line.UPPER ? 0 : 1;
            int nl = im.segmented("n-line", CX, y, 148f, 32f, LINES, li);
            if (nl != li) {
                s.mark();
                n.line = nl == 0 ? Line.UPPER : Line.LOWER;
                s.changed(false);
            }
        } else {
            g.text("悬浮键（不在判定线上）", CX + 4f, y + 16f, 13, Gfx.GND);
        }
        Note gn = s.note(n);
        if (gn != null && gn.isSync()) {
            g.rect(CX + 162f, y + 11f, 10f, 10f, Gfx.SYNC);
            g.text("同拍 " + gn.syncSize() + " 键（sync）", CX + 178f, y + 16f, 12, Gfx.SYNC);
        }
        y += 40f;
        if (n.canFloat()) floatSwitch(s, sel, n);
        if (n.canFake()) fake(s, sel, n);
        divider();
        if (n.floating) {
            floatingNote(s, sel, n);
            return;
        }
        title("位置与时间", null);
        label("横坐标");
        if (n.inLane()) {
            if (im.button("n-x-l", FX, y, 30f, 30f, "‹", Im.GHOST, 16)) {
                moveX(s, sel, ed.nudgeX(n.x, n.judgement, -1) - n.x);
            }
            float v = im.num("n-x", FX + 34f, y, FW - 68f, 30f, n.x, "x");
            if (im.button("n-x-r", FX + FW - 30f, y, 30f, 30f, "›", Im.GHOST, 16)) {
                moveX(s, sel, ed.nudgeX(n.x, n.judgement, 1) - n.x);
            }
            if (Float.isFinite(v) && v != n.x) setX(s, sel, v);
            y += 38f;
            caption("键中心离屏幕中线多远：0 是正中，负数往左，±960 是 16:10 屏幕的两边，一个键宽 240。‹ › 按坐标吸附挪，"
                + "定轨的地方一次挪半条轨道（选中几个键时一起挪）。下面的「横坐标」事件让它随时间左右滑，事件外回到这里。");
        } else {
            g.text(n.type == NoteType.TIDE_MINE ? "整条线（浪花水雷没有横坐标）" : "整条线（浪花没有横坐标）", FX + 10f, y + 15f, 13,
                Gfx.INK3);
            y += 38f;
        }
        float j = num("n-j", "判定拍", n.judgement, "拍");
        if (!Float.isNaN(j) && j != n.judgement) {
            s.mark();
            n.retime(j - n.judgement);
            s.changed(false);
        }
        if (n.type == NoteType.TAP) {
            caption("水滴没有时长；改成水流后出现「时长 / 尾长」");
        } else {
            float tail = ed.snap(ed.beat) - n.judgement;
            im.slash(tail, "/ = 到播放头为止，时长 " + Im.fmt(tail) + " 拍", true);
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
                    ? "长水雷：时长 = 封住这个位置多久；碰到判定线后底部不再动，红区长度可以用下面的「红区长度」事件随时改（只影响画面）"
                    : "时长 0 = 水雷；大于 0 = 长水雷（整段封住这个位置）");
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
        if (n.inLane()) evGroup("n-x-ev", "横坐标 X", Gfx.INK2, n.xEv, n, CH_OFFX, n.x);
        pivot(s, sel, n);
        evGroup("n-alpha", "透明 Opacity", Gfx.INK2, n.alpha, n, CH_ALPHA, 1f);
        boolean redZone = n.type == NoteType.MINE || n.type == NoteType.TIDE_MINE;
        if (n.sustained()) evGroup("n-height", redZone ? "红区长度 Height" : "尾长 Height", Gfx.FX,
            n.heightEv, n, CH_HEIGHT, n.height);
        // 叠加层 (game 7.177): added on top of the channel while they run, overlapping allowed
        if (n.inLane()) evGroup("n-xlay", "横坐标叠加", Gfx.INK2, n.xLay, n, CH_XLAY, 0f);
        evGroup("n-poslay", "位置叠加", Gfx.GND, n.posLay, n, CH_POSLAY, 0f);
        evGroup("n-rotlay", "旋转叠加", Gfx.INK2, n.rotLay, n, CH_ROTLAY, 0f);
        caption("叠加：每段事件在它的时间里把数值加到" + (n.inLane() ? "横坐标、" : "") + "位置（离线的距离）或旋转上，"
            + "可以重叠，事件外加 0。位置叠加只在「移动」让键显示时才加。");
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
        caption("用左边的工具在画面上点一下就能放音符；长的几种（水流、长水雷、潮汐、潮汐水雷）按住往远处拖出长度。"
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
        int dr = c[NoteType.DRAG.ordinal()];
        g.text("水滴 " + c[0] + " · 水流 " + c[1] + " · 水雷 " + c[2] + " · 浪花 " + c[3] + (dr > 0 ? " · Drag " + dr : ""),
            CX, y + 8f, 13, Gfx.INK2);
        y += 30f;
        int li = up == sel.size() ? 0 : up == 0 ? 1 : -1;
        int nl = im.segmented("m-line", CX, y, 160f, 32f, LINES, li);
        if (nl != li && nl >= 0) {
            s.mark();
            for (Doc.DNote n : sel) if (!n.floating) n.line = nl == 0 ? Line.UPPER : Line.LOWER;
            s.changed(false);
        }
        y += 40f;
        float sh = num("m-shift", "整体平移", 0f, "拍");
        if (!Float.isNaN(sh) && sh != 0f) {
            s.mark();
            for (Doc.DNote n : sel) n.shift(sh);
            s.changed(false);
        }
        chainButton(s, sel);
        g.text("事件", CX, y + 15f, 13, Gfx.INK2);
        allRelButtons(s, sel, y);
        y += 38f;
        if (clip != null && clip.ch != CH_LINE && clip.ch != CH_VEIL) {
            testPasteY = y;
            if (im.button("m-paste", CX, y, CW, 32f, "同步运动器：" + chName(clip.ch) + " " + clip.evs.size() + " 段 → "
                + sel.size() + " 个键", Im.NORMAL, 13)) paste(s, sel, clip.ch, MANY);
            y += 40f;
            if (pasted != null && MANY.equals(pasteFor)) pasteBar(s);
        }
        divider();
        quickMany(s, sel);
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
                    : "已抵消 " + r[0] + " 个键" + (r[1] > 0 ? "（" + r[1] + " 个按公转半径 / 角度走的或悬浮键没改）" : ""));
            }
        }
        y += 46f;
    }

    /** 悬浮键 (game 7.185 / 7.186): the selected keys that can float do (at y 600) or go back to the lower line. */
    private void floatSwitch(Session s, List<Doc.DNote> sel, Doc.DNote n) {
        g.text(n.type == NoteType.MINE ? "悬浮水雷 float（在原地浮现，填满的时候别碰）" : "悬浮键 float（在原地浮现，填满的时候点）",
            CX, y + 12f, 12, Gfx.INK3);
        boolean t = im.toggle("n-float", CX + CW, y + 12f, n.floating);
        if (t != n.floating) {
            s.mark();
            for (Doc.DNote x : sel) {
                if (!x.canFloat() || x.floating == t) continue;
                if (t) x.toFloating(Floating.DEFAULT_Y);
                else x.toLine();
            }
            s.changed(false);
        }
        y += 30f;
    }

    /**
     * 位置与时间 and 运动 of a floating key (悬浮键, game 7.185, CHART_FORMAT §11): its centre, size, beat, length,
     * appearance and DRAG chain, then its x / y / size / fill / opacity events.
     */
    private void floatingNote(Session s, ArrayList<Doc.DNote> sel, Doc.DNote n) {
        title("位置与时间", null);
        float v = num("f-x", "横坐标", n.x, "x");
        if (Float.isFinite(v) && v != n.x) setX(s, sel, v);
        v = num("f-y", "纵坐标", n.y, "y");
        if (Float.isFinite(v) && v != n.y) {
            s.mark();
            float dy = v - n.y;
            for (Doc.DNote x : sel) if (x.floating) x.moveXY(0f, dy);
            s.changed(false);
        }
        caption("圆心：横坐标从屏幕中线量（负数往左，±960 是 16:10 屏幕的两边），纵坐标从下往上 0–1200（600 是正中）。"
            + "在预览里可以直接拖（选中几个一起动）；悬浮键不看定轨。");
        v = num("f-size", "大小", n.sizeOr(), "直径");
        if (Float.isFinite(v) && v > 0f && v != n.sizeOr()) {
            s.mark();
            for (Doc.DNote x : sel) {
                if (x.floating) x.size = v == Floating.defaultSize(x.type, x.duration) ? Float.NaN : v;
            }
            s.changed(false);
        }
        float j = num("n-j", "判定拍", n.judgement, "拍");
        if (!Float.isNaN(j) && j != n.judgement) {
            s.mark();
            n.retime(j - n.judgement);
            s.changed(false);
        }
        if (n.type == NoteType.HOLD || n.type == NoteType.MINE) {
            float du = num("n-dur", n.type == NoteType.MINE ? "长度" : "时长", n.duration, "拍");
            if (!Float.isNaN(du) && du != n.duration) setDuration(s, n, du);
        }
        if (n.type == NoteType.MINE) {
            caption("长度 0 是悬浮水雷（直径默认 " + Math.round(Floating.MINE_SIZE) + "，判定拍前后按在圈里就踩中），"
                + "大于 0 是悬浮长水雷（直径默认 " + Math.round(Floating.LONG_MINE_SIZE)
                + "，判定拍到结束有手指在圈里就踩中，每碰一次断一次连击）。");
        }
        float da = defaultAppear(n), a = Float.isFinite(n.appearance) ? n.appearance : da;
        im.slash(ed.snap(ed.beat), "/ = 播放头（第 " + Im.fmt(ed.snap(ed.beat)) + " 拍）", true);
        v = num("f-appear", "出现拍", a, "拍");
        if (Float.isFinite(v) && v != a) {
            s.mark();
            v = Math.min(v, n.judgement);
            n.appearance = Math.abs(v - da) < 1e-4f ? Float.NaN : v;
            s.changed(false);
        }
        caption("从哪一拍起浮现：默认判定前 2 拍，有填充事件时从第一个的开始。不写填充事件时从出现时的空匀速填到判定拍正好满。");
        if (n.type == NoteType.DRAG) {
            String c = text("f-chain", "Drag 链", n.chain == null ? "" : n.chain);
            if (c != null && !c.trim().equals(n.chain == null ? "" : n.chain)) {
                s.mark();
                n.chain = c.trim().isEmpty() ? null : c.trim();
                s.changed(false);
            }
            caption("写同一个链名的悬浮 Drag 用紫色的线连成一条路线；选中几个时可以一键连成新链。");
        }
        divider();
        title("运动", null);
        evGroup("n-x-ev", "横坐标 X", Gfx.INK2, n.xEv, n, CH_OFFX, n.x);
        evGroup("f-y-ev", "纵坐标 Y", Gfx.INK2, n.yEv, n, CH_FY, n.y);
        evGroup("f-size-ev", "大小 Size", Gfx.INK2, n.sizeEv, n, CH_FSIZE, n.sizeOr());
        evGroup("f-fill", "填充 Fill", Gfx.GND, n.fillEv, n, CH_FILL, n.sizeOr());
        evGroup("n-alpha", "透明 Opacity", Gfx.INK2, n.alpha, n, CH_ALPHA, 1f);
        caption("每个事件结束后停在结束值，直到下一个开始；判定拍时圈在哪、多大，就在哪里点。填充 = 颜色离圈边还有多远"
            + "（按直径量）：0 正好填满（该点的时候），等于直径是空的，负数溢出到圈外；没有填充事件时从出现拍的空圈匀速填到"
            + "判定拍；最后一个事件之后按结束时的速度接着变。");
        divider();
        float ga = Float.isNaN(n.appearance) ? n.judgement - 2f : n.appearance, gb = n.judgement + n.duration;
        glitch(s, n.glitches, "n", ga, gb, "只影响画面，不影响判定。");
        divider();
        distortion(s, n.distortions, "n", ga, gb, NoteDistortion.DEFAULT_INTENSITY,
            "悬浮键平常不扭曲（0）；1 = 和漩涡平常一样，2 = 两倍，最多 " + Im.fmt(NoteDistortion.MAX_INTENSITY) + "。");
        divider();
        actions();
    }

    /** @return the beat a floating key shows from when it writes none: its first fill event's start, else 2 before. */
    private static float defaultAppear(Doc.DNote n) {
        float a = n.judgement - Floating.APPROACH_BEATS;
        if (!n.fillEv.isEmpty()) {
            a = Float.POSITIVE_INFINITY;
            for (Doc.Ev e : n.fillEv) a = Math.min(a, e.start);
        }
        return Math.min(a, n.judgement);
    }

    /** 连成一条 Drag 链: two or more selected floating DRAGs get a new chain of their own (CHART_FORMAT §11.1). */
    private void chainButton(Session s, List<Doc.DNote> sel) {
        int k = 0;
        for (Doc.DNote n : sel) if (n.floating && n.type == NoteType.DRAG) k++;
        if (k < 2) return;
        if (im.button("m-chain", CX, y, CW, 32f, "连成一条 Drag 链（" + k + " 个悬浮 Drag）", Im.NORMAL, 13)) {
            s.mark();
            String c = s.doc.freeChain();
            for (Doc.DNote n : sel) if (n.floating && n.type == NoteType.DRAG) n.chain = c;
            s.changed(false);
            im.toast("链「" + c + "」：" + k + " 个 Drag 按拍子连成一条路线");
        }
        y += 40f;
    }

    private void setType(Session s, Doc.DNote n, NoteType t) {
        if (t == NoteType.TAP || t == NoteType.DRAG) {
            n.duration = 0f;
            n.heightEv.clear();
        }
        if (t == NoteType.HOLD && n.duration <= 0f) {
            n.duration = 1f;
            s.doc.setAutoTail(n);
        }
        n.type = t;
    }

    /** Puts every selected key at x {@code v} (their x events move with them). */
    private void setX(Session s, ArrayList<Doc.DNote> sel, float v) {
        s.mark();
        for (Doc.DNote n : sel) if (n.inLane()) n.moveX(v - n.x);
        s.changed(false);
    }

    /** Moves every selected key {@code dx} sideways (their x events too). */
    private void moveX(Session s, ArrayList<Doc.DNote> sel, float dx) {
        if (dx == 0f) return;
        s.mark();
        for (Doc.DNote n : sel) if (n.inLane()) n.moveX(dx);
        s.changed(false);
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
            : "正常下落 = 2 拍内从 " + dist(Doc.SPAWN) + " 落到判定线（换掉移动事件）；悬浮键不变");
        if (tp >= 0) applyTemplate(s, sel, tp);
        divider();
        lineShake(s, sel);
        if (sel.size() > 1) {
            divider();
            title("故障档位", null);
            int lv = im.segmented("m-glv", CX, y, CW, 30f, GLITCH_LEVELS, -1);
            y += 38f;
            caption("每个键从开始下落到判定（长条到尾）放一段故障，已经有的只改数值：强度 0.3 / 0.5 / 0.7 / 1，"
                + "色散、错位、闪断跟着加重，每拍换 16 次花样。悬浮键从出现到判定。");
            if (lv >= 0) glitchKeys(s, sel, lv);
        }
    }

    /**
     * 原地出现 · 长条收起 · 飞出屏幕 (TEMPLATES 7–9, the motions of Kelvin's charts) as one undo step.
     *
     * @return how many keys changed (0 with a toast when none could)
     */
    int motion(Session s, List<Doc.DNote> sel, int tp) {
        float head = ed.snap(ed.beat), step = 0.25f;
        ArrayList<Doc.DNote> keys = new ArrayList<>();
        for (Doc.DNote n : sel) {
            boolean ok = tp == 8 ? n.sustained() && n.duration > 0f : tp != 9 || n.judgement + n.duration > head;
            if (!n.floating && ok) keys.add(n);
        }
        keys.sort(Comparator.comparingDouble((Doc.DNote n) -> n.judgement).thenComparingDouble(n -> n.x));
        if (keys.isEmpty()) {
            im.toast(tp == 8 ? "选中的键里没有长条" : tp == 9 ? "选中的键在播放头之前就结束了" : "悬浮键不下落");
            return 0;
        }
        float last = head + step * (keys.size() - 1);
        if (tp == 7 && last >= keys.get(0).judgement) {
            im.toast("最后一个出现在第 " + Im.fmt(last) + " 拍，要早于第一个键的判定：把播放头往前挪");
            return 0;
        }
        s.mark();
        for (int i = 0; i < keys.size(); i++) {
            Doc.DNote n = keys.get(i);
            if (n.move.isEmpty() && !n.noApproach()) n.approach(Note.DEFAULT_APPROACH_BEATS, n.spawn(), 1f);
            ArrayList<Doc.Ev> was = new ArrayList<>(n.move), made = new ArrayList<>();
            float j = n.judgement, end = j + n.duration;
            if (tp == 7) {                                // appear in place one by one, then go on as before
                float at = head + step * i, first = Float.MAX_VALUE;
                for (Doc.Ev e : was) first = Math.min(first, e.start);
                double u = QuickEvents.valueAt(was, 0f, true, last);
                for (Doc.Ev e : was) if (last < first && e.start == first) u = e.from;
                QuickEvents.dropBefore(n.move, last);
                float until = n.move.isEmpty() ? last : Math.max(last, n.move.get(0).start);
                if (at < until) made.add(new Doc.Ev(at, until, QuickEvents.round(u), QuickEvents.round(u), 1f));
                n.appearance = Float.NaN;
            } else if (tp == 8) {                         // the top keeps still: the bottom rises as the tail shortens
                float u = (float) QuickEvents.valueAt(was, 0f, false, j);
                float h = (float) QuickEvents.valueAt(n.heightEv, n.height, false, j);
                made.add(new Doc.Ev(j, end, u, u + h, 1f));
                Doc.Ev tail = new Doc.Ev(j, end, h, 0f, 1f);
                tail.rel = true;
                QuickEvents.splice(n.heightEv, j, end, java.util.Collections.singletonList(tail), false);
            } else {                                      // speed up and out of the top of the screen
                float u = (float) QuickEvents.valueAt(was, 0f, false, head);
                float to = n.line == Line.UPPER ? -400f : 1500f;
                made.add(new Doc.Ev(head, head + 2f, QuickEvents.round(u), to, 2f));
                if (end > head + 2f) made.add(new Doc.Ev(head + 2f, end, to, to, 1f));
                QuickEvents.splice(n.move, head, Float.MAX_VALUE, new ArrayList<>(), false);
            }
            for (Doc.Ev e : made) e.rel = true;
            if (tp == 8) QuickEvents.splice(n.move, j, end, made, false);
            else {
                n.move.addAll(made);
                n.move.sort(Comparator.comparingDouble(e -> e.start));
            }
        }
        s.changed(false);
        String t = TEMPLATES[tp];
        im.toast("已套用" + t.substring(0, t.indexOf('：')) + "（" + keys.size() + " 个键）");
        return keys.size();
    }

    /** 判定线跟着键抖: how big, and how it changes along the keys. */
    float shakeAmp = 20f;
    int shakeMode;
    static final String[] SHAKE_MODES = {"按间隔", "不变", "渐大", "渐小"};

    private void lineShake(Session s, List<Doc.DNote> sel) {
        title("判定线跟着键抖", null);
        float a = num("ls-amp", "幅度", shakeAmp, "");
        if (!Float.isNaN(a)) shakeAmp = a;
        shakeMode = im.segmented("ls-mode", CX, y, CW, 30f, SHAKE_MODES, shakeMode);
        y += 38f;
        if (im.button("ls-go", CX, y, CW, 34f, "按选中的 " + sel.size() + " 个键抖判定线", Im.NORMAL)) {
            lineShake(s, sel, shakeAmp, shakeMode);
        }
        y += 42f;
        caption("每个键判定的那一刻，它的判定线往外弹一下再回来（上线往上、下线往下），放在判定线叠加层，可以撤销。"
            + "按间隔：离下一下 1 拍及以上用这个幅度，半拍 3/4，1/4 拍一半，更密 1/4；渐大 / 渐小：从一半到两倍。"
            + "悬浮键、假键、水雷不算，同一刻几个键只抖一下。");
    }

    /**
     * 判定线跟着键抖 (Kelvin's charts): at each judgement of the selected keys their line kicks outward over
     * min(0.08, gap / 4) beats and comes back over min(0.34, the rest of the gap), exponent 0.5 both ways, as events
     * in the line's 叠加层, one undo step.
     *
     * @param mode {@link #SHAKE_MODES}: by the gap to the next hit, the same, growing or shrinking
     * @return how many kicks it added
     */
    int lineShake(Session s, List<Doc.DNote> sel, float amp, int mode) {
        ArrayList<ArrayList<Doc.Ev>> parts = new ArrayList<>();
        int kicks = 0;
        for (int up = 0; up < 2; up++) {
            java.util.TreeSet<Float> at = new java.util.TreeSet<>();
            for (Doc.DNote n : sel) {
                boolean mine = n.type == NoteType.MINE || n.type == NoteType.TIDE_MINE;
                if (!n.floating && !n.fake && !mine && (n.line == Line.UPPER) == (up == 0)) at.add(n.judgement);
            }
            Float[] b = at.toArray(new Float[0]);
            ArrayList<Doc.Ev> part = new ArrayList<>();
            for (int k = 0; k < b.length; k++) {
                float next = k + 1 < b.length ? b[k + 1] - b[k] : 1f;
                float gap = k + 1 < b.length || k == 0 ? next : b[k] - b[k - 1];
                float p = Math.min(0.08f, next / 4f), q = Math.min(0.34f, next - p);
                float f = (float) k / Math.max(1, b.length - 1);
                float w = mode == 0 ? (gap > 0.999f ? 1f : gap > 0.499f ? 0.75f : gap > 0.249f ? 0.5f : 0.25f)
                    : mode == 2 ? 0.5f + 1.5f * f : mode == 3 ? 2f - 1.5f * f : 1f;
                float a = QuickEvents.round(amp * w * (up == 0 ? 1f : -1f));
                part.add(new Doc.Ev(b[k], b[k] + p, 0f, a, 0.5f));
                part.add(new Doc.Ev(b[k] + p, b[k] + p + q, a, 0f, 0.5f));
            }
            kicks += b.length;
            parts.add(part);
        }
        if (kicks == 0) {
            im.toast("选中的键里没有会判定的（悬浮键、假键、水雷不算）");
            return 0;
        }
        s.mark();
        for (int up = 0; up < 2; up++) {
            ArrayList<Doc.Ev> part = parts.get(up);
            tagGroup(part, "抖动");
            List<Doc.Ev> lay = up == 0 ? s.doc.upperLay : s.doc.lowerLay;
            lay.addAll(part);
            lay.sort(Comparator.comparingDouble(e -> e.start));
        }
        s.changed(false);
        im.toast("判定线抖了 " + kicks + " 下（上线 " + parts.get(0).size() / 2 + "、下线 " + parts.get(1).size() / 2 + "）");
        return kicks;
    }

    /** 故障档位 (Kelvin's charts): 关, then intensity with 色散 / 错位 / 闪断. */
    static final String[] GLITCH_LEVELS = {"关", "轻", "中", "较重", "很重"};
    private static final String[] GLITCH_ON = {"轻", "中", "较重", "很重"};
    private static final float[][] GLITCH_SET = {{}, {0.3f, 1f, 0.7f, 0f}, {0.5f, 1.4f, 1.2f, 0.2f},
        {0.7f, 1.8f, 1.6f, 0.4f}, {1f, 2f, 2f, 0.6f}};

    /** Sets every segment of {@code list} to level {@code lv} of {@link #GLITCH_LEVELS} (no ramp, 16 a beat). */
    static void glitchLevel(List<Doc.DGlitch> list, int lv) {
        if (lv <= 0) {
            list.clear();
            return;
        }
        float[] v = GLITCH_SET[lv];
        for (Doc.DGlitch x : list) {
            x.intensity = v[0];
            x.intensityEnd = Float.NaN;
            x.split = v[1];
            x.slice = v[2];
            x.flicker = v[3];
            x.rate = 16f;
        }
    }

    /** @return the level {@code x} is set to (1–4), or 0 when it is none of them. */
    private static int glitchLevelOf(Doc.DGlitch x) {
        for (int i = 1; i < GLITCH_SET.length; i++) {
            float[] v = GLITCH_SET[i];
            if (x.intensity == v[0] && x.split == v[1] && x.slice == v[2] && x.flicker == v[3]) return i;
        }
        return 0;
    }

    /** Puts every selected key at glitch level {@code lv}, as one undo step; @return how many. */
    int glitchKeys(Session s, List<Doc.DNote> sel, int lv) {
        s.mark();
        int c = 0;
        for (Doc.DNote n : sel) {
            if (lv > 0 && n.glitches.isEmpty()) {         // from its first movement (or appearing) to its end
                float a = n.judgement - Note.DEFAULT_APPROACH_BEATS;
                if (!n.floating && !n.move.isEmpty()) {
                    a = Float.MAX_VALUE;
                    for (Doc.Ev e : n.move) a = Math.min(a, e.start);
                }
                if (!Float.isNaN(n.appearance)) a = Math.max(a, n.appearance);
                Doc.DGlitch x = new Doc.DGlitch();
                x.from = a;
                x.to = n.judgement + Math.max(0f, n.duration);
                n.glitches.add(x);
            }
            glitchLevel(n.glitches, lv);
            c++;
        }
        s.changed(false);
        im.toast("故障" + GLITCH_LEVELS[lv] + "（" + c + " 个键）");
        return c;
    }

    /** @return a distance for a label: at most 2 decimals, no trailing zeros (1080, 1555.56). */
    private static String dist(float v) {
        return String.format(java.util.Locale.ROOT, "%.2f", v).replaceAll("\\.?0+$", "");
    }

    /** Applies quick motion {@code tp} (an index of {@link #TEMPLATES}) to {@code sel} as one undo step. */
    void applyTemplate(Session s, List<Doc.DNote> sel, int tp) {
        if (sel.isEmpty()) return;
        if (tp >= 7) {
            motion(s, sel, tp);
            return;
        }
        s.mark();
        for (Doc.DNote n : sel) {
            if (n.floating) continue;                           // a floating key does not fall
            float k = n.spawn() / Doc.SPAWN;
            switch (tp) {
                case 0: n.approach(2f, n.spawn(), 1f); break;
                case 1: n.approach(1f, n.spawn(), 1f); break;
                case 2: n.approach(4f, n.spawn(), 1f); break;
                case 3: n.approach(2f, FAST_SLOW * k, 0.5f); break;
                case 4: n.approach(2f, n.spawn(), 2f); break;
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
        im.slash(Float.NaN, null, false);
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
        g.text("档位", CX, y + 15f, 12, Gfx.INK2);
        int cur = glitchLevelOf(list.get(0)) - 1;
        int lv = im.segmented(p + "-glv", CX + 44f, y, CW - 44f, 30f, GLITCH_ON, cur);
        if (lv >= 0 && lv != cur) {
            s.mark();
            glitchLevel(list, lv + 1);
            s.changed(false);
        }
        y += 38f;
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
                    for (List<Doc.Ev> list : java.util.Arrays.asList(n.move, n.rot, n.xEv, n.orbXEv, n.orbYEv,
                        n.orbRadEv, n.orbAngEv, n.alpha, n.heightEv, n.xLay, n.posLay, n.rotLay)) {
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
            evPick.clear();
        }
        y += 42f;
        if (id.equals(open)) evEditor(id, list, n, ch, base);
    }

    // ================================================================== 运动器 (Kelvin 2026-11-05, tester feedback)

    private static final long HOLD_MS = 400L;
    private static final Object DRAG = "ev-drag";
    private static final String MANY = "many-paste";
    private static final String[] PASTE_TIME = {"按原拍（一起动）", "按各自判定", "从播放头开始"},
        PASTE_VALUE = {"按变化量", "按原数值"}, PASTE_LINK = {"保持链接", "不链接"};
    /** The cards picked for 复制 in the open list (empty = just the selected one). */
    private final java.util.Set<Doc.Ev> evPick = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
    private List<Doc.Ev> pickList;
    /** A lifted card: its list, the card, and where on it the pointer holds it. */
    private List<Doc.Ev> dragList;
    private Doc.Ev dragEv;
    private float dragGrab;
    private Object holdRow;
    private long holdAt;
    /** The last reorder, for 保留原数值: its list, its doc and each moved card's values before. */
    private List<Doc.Ev> keepFor;
    private Doc keepDoc;
    private final IdentityHashMap<Doc.Ev, float[]> keepVals = new IdentityHashMap<>();
    /** 复制的运动器: copies with absolute beats, their channel and the source key's judgement (NaN = no key). */
    Clip clip;
    /** Where the motion bar, start field, keep bar, many-key paste button and paste bar were drawn (AutoTest). */
    float testBarY = Float.NaN, testStartY = Float.NaN, testKeepY = Float.NaN, testPasteY = Float.NaN,
        testPasteBarY = Float.NaN;
    static final float TEST_CX = CX, TEST_CW = CW;
    /** The last paste, re-timed by the bar under it: where it went, its doc, its group and the bar's choices. */
    private ArrayList<Target> pasted;
    private Doc pasteDoc;
    private String pasteFor;
    private int pasteTime, pasteValue;
    /** 保持链接 of the last paste (on for 同步运动器 onto several keys, off for 粘贴) and how many cards could not link. */
    private boolean pasteLink;
    private int noSource;

    static final class Clip {
        final int ch;
        final ArrayList<Doc.Ev> evs = new ArrayList<>();
        final float judge;
        /** The source key's uid (−1 = none) and the copied events themselves, for 保持链接. */
        final int uid;
        final ArrayList<Doc.Ev> src = new ArrayList<>();

        Clip(int ch, float judge, int uid) {
            this.ch = ch;
            this.judge = judge;
            this.uid = uid;
        }

        Doc.Ev first() {
            Doc.Ev f = evs.get(0);
            for (Doc.Ev e : evs) if (e.start < f.start) f = e;
            return f;
        }
    }

    /** One list a paste went into: its key (null for an x, line or veil list), the list, its base value. */
    private static final class Target {
        final Doc.DNote n;
        final List<Doc.Ev> list;
        final float base;
        final ArrayList<Doc.Ev> evs = new ArrayList<>();

        Target(Doc.DNote n, List<Doc.Ev> list, float base) {
            this.n = n;
            this.list = list;
            this.base = base;
        }
    }

    /** 复制 · 粘贴 · 全选 above the cards of an open list. */
    private void motionBar(String id, List<Doc.Ev> list, Doc.DNote n, int ch, float base) {
        testBarY = y;
        boolean can = clip != null && clip.ch == ch;
        if (list.isEmpty() && !can) return;
        float w3 = (CW - 16f) / 3f;
        List<Doc.Ev> picked = picked(list);
        if (im.button(id + "-cp", CX, y, w3, 26f, "复制 " + picked.size() + " 段", picked.isEmpty() ? Im.GHOST : Im.NORMAL,
            12) && !picked.isEmpty()) {
            clip = new Clip(ch, n != null ? n.judgement : Float.NaN, n != null ? n.uid : -1);
            for (Doc.Ev e : picked) {
                clip.evs.add(e.copy());
                clip.src.add(e);
            }
            im.toast("复制了 " + picked.size() + " 段「" + chName(ch) + "」运动器");
        }
        if (im.button(id + "-ps", CX + w3 + 8f, y, w3, 26f, can ? "粘贴 " + clip.evs.size() + " 段" : "粘贴",
            can ? Im.NORMAL : Im.GHOST, 12)) {
            if (can) {
                ArrayList<Target> t = new ArrayList<>();
                t.add(new Target(n, list, base));
                paste(ed.s, t, id);
            } else {
                im.toast(clip == null ? "先选几段点「复制」" : "复制的是「" + chName(clip.ch) + "」，只能粘到同一种运动里");
            }
        }
        boolean all = !list.isEmpty() && evPick.size() == list.size();
        if (im.button(id + "-all", CX + 2f * (w3 + 8f), y, w3, 26f, all ? "全不选" : "全选", Im.GHOST, 12)) {
            evPick.clear();
            if (!all) evPick.addAll(list);
        }
        y += 32f;
    }

    /** @return the cards 复制 takes, in list order. */
    private List<Doc.Ev> picked(List<Doc.Ev> list) {
        ArrayList<Doc.Ev> r = new ArrayList<>();
        for (Doc.Ev e : list) if (evPick.contains(e)) r.add(e);
        if (r.isEmpty() && evSel >= 0 && evSel < list.size()) r.add(list.get(evSel));
        return r;
    }

    /** A click on card {@code i}: Ctrl adds / removes it, Shift adds a range, on the tablet a tap toggles in a pick. */
    private void pickCard(List<Doc.Ev> list, int i) {
        Doc.Ev e = list.get(i);
        boolean multi = im.ctrl || im.shift || Gfx.TABLET && evPick.size() > 1;
        if (multi && evPick.isEmpty() && evSel >= 0 && evSel < list.size()) evPick.add(list.get(evSel));
        if (im.shift && evSel >= 0 && evSel < list.size()) {
            for (int k = Math.min(evSel, i); k <= Math.max(evSel, i); k++) evPick.add(list.get(k));
        } else if (multi) {
            if (!evPick.remove(e)) evPick.add(e);
        } else {
            evPick.clear();
            if (e.group != 0) for (Doc.Ev x : list) if (x.group == e.group) evPick.add(x);   // its quick group
        }
        evSel = i;
    }

    /** Lifts card {@code e}: its list is put in time order first, so the cards show as they will be laid out. */
    private void liftCard(List<Doc.Ev> list, Doc.Ev e) {
        Doc.Ev keep = evSel >= 0 && evSel < list.size() ? list.get(evSel) : null;
        list.sort(Comparator.comparingDouble(x -> x.start));
        evSel = keep != null ? list.indexOf(keep) : -1;
        dragList = list;
        dragEv = e;
        holdRow = null;
        im.active = DRAG;
    }

    /** @return the slot the lifted card is over (cards are 34 apart from {@code top}). */
    private int dragTarget(float top, int size) {
        return Math.max(0, Math.min(size - 1, Math.round((im.my - dragGrab - top) / 34f)));
    }

    /** Drops the lifted card: only the cards between its old and new place are re-timed (Doc#reorderEvents). */
    private void dropCard(Session s, List<Doc.Ev> list, float top) {
        if (dragList == list) {
            int from = list.indexOf(dragEv), to = dragTarget(top, list.size());
            if (from >= 0 && to != from) {
                s.mark();
                keepVals.clear();
                for (int k = Math.min(from, to); k <= Math.max(from, to); k++) {
                    Doc.Ev e = list.get(k);
                    keepVals.put(e, new float[]{e.from, e.to});
                }
                Doc.reorderEvents(list, from, to);
                keepFor = list;
                keepDoc = s.doc;
                s.changed(false);
                evSel = to;
                evPick.clear();
                im.toast("第 " + (Math.min(from, to) + 1) + "–" + (Math.max(from, to) + 1) + " 段按新顺序排了时间");
            }
        }
        dragList = null;
        dragEv = null;
    }

    /** After a reorder: the values were chained by change; 保留原数值 puts each card's own values back. */
    private void keepBar(Session s) {
        if (s.doc != keepDoc) {
            keepFor = null;
            return;
        }
        testKeepY = y;
        g.text("数值按变化量接上了", CX + 4f, y + 14f, 12, Gfx.INK3);
        if (im.button("kp-keep", CX + CW - 196f, y, 110f, 28f, "保留原数值", Im.NORMAL, 12)) {
            s.mark();
            for (java.util.Map.Entry<Doc.Ev, float[]> en : keepVals.entrySet()) {
                en.getKey().from = en.getValue()[0];
                en.getKey().to = en.getValue()[1];
            }
            s.changed(false);
            keepFor = null;
        }
        if (im.button("kp-ok", CX + CW - 78f, y, 78f, 28f, "好", Im.GHOST, 12)) keepFor = null;
        y += 36f;
    }

    /** 粘贴运动器 onto every key of {@code sel} that has the copied channel. */
    private void paste(Session s, List<Doc.DNote> sel, int ch, String forId) {
        ArrayList<Target> t = new ArrayList<>();
        for (Doc.DNote n : sel) {
            List<Doc.Ev> l = listOf(n, ch);
            if (l != null && n.uid != clip.uid) t.add(new Target(n, l, baseOf(n, ch)));   // the source has them
        }
        if (t.isEmpty()) {
            im.toast("选中的键都没有「" + chName(ch) + "」");
            return;
        }
        paste(s, t, forId);
    }

    private void paste(Session s, ArrayList<Target> targets, String forId) {
        s.mark();
        pasted = targets;
        pasteDoc = s.doc;
        pasteFor = forId;
        boolean rel = false;
        for (Doc.Ev e : clip.evs) rel |= e.rel;
        pasteTime = rel && !Float.isNaN(clip.judge) ? 1 : 0;
        pasteValue = deltaChannel(clip.ch) ? 0 : 1;
        pasteLink = MANY.equals(forId) && canLink();
        applyPaste();
        s.changed(false);
        String what = clip.evs.size() + " 段「" + chName(clip.ch) + "」到 " + targets.size() + " 处";
        im.toast(!pasteLink ? "粘贴了 " + what : noSource == 0 ? "同步了 " + what + "，改来源这些跟着变"
            : "同步了 " + what + "；有 " + noSource + " 段本身是链接出来的（或来源删了），按普通复制");
    }

    /** @return true when the last paste can keep a link: copied from a key, pasted onto another key */
    private boolean canLink() {
        if (clip.uid < 0) return false;
        for (Target t : pasted) if (t.n != null && t.n.uid != clip.uid) return true;
        return false;
    }

    /**
     * @return each copied card's event in the source key now (the same one, else one with its beats and values),
     *         made a 同步链接 source; null where it is gone or is itself a linked copy (counted in {@link #noSource})
     */
    private Doc.Ev[] sources() {
        Doc.DNote k = ed.s.doc.byUid(clip.uid);
        List<Doc.Ev> l = k == null ? null : listOf(k, clip.ch);
        Doc.Ev[] r = new Doc.Ev[clip.evs.size()];
        noSource = 0;
        for (int i = 0; i < r.length; i++) {
            Doc.Ev c = clip.evs.get(i);
            if (l != null) {
                for (Doc.Ev e : l) if (e == clip.src.get(i)) r[i] = e;
                for (Doc.Ev e : l) {
                    boolean same = e.start == c.start && e.end == c.end && e.from == c.from && e.to == c.to;
                    if (r[i] == null && same) r[i] = e;
                }
            }
            if (r[i] == null || r[i].link != null && !r[i].link.src) {
                r[i] = null;
                noSource++;
            } else if (r[i].link == null) {
                r[i].link = Doc.Link.source(ed.s.doc.nextLinkId());
            }
        }
        return r;
    }

    /** (Re)builds the pasted cards from the clipboard with the bar's time and value choices. */
    private void applyPaste() {
        Doc.Ev first = clip.first();
        Doc.Ev[] src = pasteLink ? sources() : null;
        for (Target t : pasted) {
            t.list.removeAll(t.evs);
            t.evs.clear();
            boolean judge = pasteTime == 1 && t.n != null && !Float.isNaN(clip.judge);
            float dt = pasteTime == 2 ? ed.snap(ed.beat) - first.start : judge ? t.n.judgement - clip.judge : 0f;
            float dv = pasteValue == 0 ? Doc.lineAt(t.list, t.base, first.start + dt) - first.from : 0f;
            for (int k = 0; k < clip.evs.size(); k++) {
                Doc.Ev e = clip.evs.get(k).copy();
                e.start += dt;
                e.end += dt;
                e.from += dv;
                e.to += dv;
                e.flow = Float.NaN;
                e.link = null;
                e.linked = null;
                if (t.n == null) e.rel = false;
                if (src != null && src[k] != null && t.n != null && t.n.uid != clip.uid) {
                    // 保持链接: Doc#relink puts it on its source from now on (beats from the judgement in 按各自判定)
                    e.link = Doc.Link.copyOf(src[k].link.id, judge ? 0f : dt, dv, judge);
                    e.rel = judge;
                    float j = judge ? t.n.judgement : 0f;
                    e.linked = new float[]{e.start - j, e.end - j, e.from, e.to, e.exp};
                }
                t.list.add(e);
                t.evs.add(e);
            }
            t.list.sort(Comparator.comparingDouble(x -> x.start));
        }
        if (pasted.size() == 1) {
            evPick.clear();
            evPick.addAll(pasted.get(0).evs);
        }
    }

    /** Under a paste: when the cards run (同拍 / 各自判定 / 播放头) and whether values follow the target. */
    private void pasteBar(Session s) {
        if (s.doc != pasteDoc) {
            pasted = null;
            return;
        }
        testPasteBarY = y;
        boolean link = canLink();
        g.box(CX, y, CW, link ? 136f : 100f, 6f, Gfx.BAR2, Gfx.SEL_LINE);
        g.text((pasteLink ? "刚同步的 " : "刚粘贴的 ") + clip.evs.size() + " 段：时间 · 数值" + (link ? " · 链接" : ""),
            CX + 10f, y + 15f, 12, Gfx.INK2);
        int t = im.segmented("pz-t", CX + 8f, y + 28f, CW - 16f, 28f, PASTE_TIME, pasteTime);
        int v = im.segmented("pz-v", CX + 8f, y + 64f, CW - 100f, 28f, PASTE_VALUE, pasteValue);
        int l = link ? im.segmented("pz-l", CX + 8f, y + 100f, CW - 100f, 28f, PASTE_LINK, pasteLink ? 0 : 1)
            : pasteLink ? 0 : 1;
        if (im.button("pz-ok", CX + CW - 84f, y + 64f, 76f, 28f, "完成", Im.GHOST, 12)) pasted = null;
        y += link ? 144f : 108f;
        if (pasted != null && (t != pasteTime || v != pasteValue || (l == 0) != pasteLink)) {
            s.mark();
            pasteTime = t;
            pasteValue = v;
            pasteLink = l == 0;
            applyPaste();
            s.changed(false);
        }
    }

    /** A linked card (同步链接): a copy jumps to its source or 断开; a source tells how many follow and lets all go. */
    private void linkBar(Session s, Doc.Ev e, int ch) {
        int id = e.link.id, c = s.doc.followers(id);
        boolean src = e.link.src;
        g.text(src ? "链接 × " + c + "：改这一段，" + c + " 段跟着变" : "链接：跟着来源变，单独改就断开", CX + 4f, y + 14f, 12,
            Gfx.INK2);
        if (!src && im.button("lk-go", CX + CW - 196f, y, 110f, 28f, "跳到来源", Im.NORMAL, 12)) jumpTo(s, id, ch);
        if (im.button("lk-cut", CX + CW - 78f, y, 78f, 28f, src ? "全部断开" : "断开", Im.GHOST, 12)) {
            s.mark();
            for (Doc.DNote k : s.doc.notes) {
                for (List<Doc.Ev> l : k.lists()) {
                    for (Doc.Ev x : l) if (x.link != null && x.link.id == id && (src || x == e)) x.link = null;
                }
            }
            s.changed(false);
        }
        y += 36f;
    }

    /** Selects the key holding source {@code id} in channel {@code ch} (the list stays open) and seeks to it. */
    private void jumpTo(Session s, int id, int ch) {
        for (Doc.DNote k : s.doc.notes) {
            List<Doc.Ev> l = listOf(k, ch);
            for (int i = 0; l != null && i < l.size(); i++) {
                Doc.Link x = l.get(i).link;
                if (x == null || !x.src || x.id != id) continue;
                s.select(k, false);
                evSel = i;
                evPick.clear();
                ed.seekBeat(k.judgement);
                return;
            }
        }
    }

    /** @return true for the 叠加层 channels (game 7.177), whose events may overlap and add 0 outside. */
    private static boolean layer(int ch) {
        return ch == CH_XLAY || ch == CH_POSLAY || ch == CH_ROTLAY || ch == CH_LINELAY;
    }

    /** Position-like channels paste by change by default (from the target's own value); the rest as they are. */
    private static boolean deltaChannel(int ch) {
        return ch == CH_ROT || ch == CH_OFFX || ch == CH_ORBX || ch == CH_ORBY || ch == CH_ORBA || ch == CH_FY;
    }

    /** @return key {@code n}'s list for channel {@code ch}, or null when it has none (a tap's tail, ...). */
    private static List<Doc.Ev> listOf(Doc.DNote n, int ch) {
        switch (ch) {
            case CH_MOVE: return n.move;
            case CH_ROT: return n.rot;
            case CH_ALPHA: return n.alpha;
            case CH_HEIGHT: return n.sustained() ? n.heightEv : null;
            case CH_OFFX: return n.inLane() ? n.xEv : null;
            case CH_ORBX: return n.orbXEv;
            case CH_ORBY: return n.orbYEv;
            case CH_ORBR: return n.orbRadEv;
            case CH_ORBA: return n.orbAngEv;
            case CH_XLAY: return n.inLane() ? n.xLay : null;
            case CH_POSLAY: return n.posLay;
            case CH_ROTLAY: return n.rotLay;
            case CH_FY: return n.floating ? n.yEv : null;
            case CH_FSIZE: return n.floating ? n.sizeEv : null;
            case CH_FILL: return n.floating ? n.fillEv : null;
            default: return null;
        }
    }

    /** @return the value channel {@code ch} of key {@code n} has outside its events (as {@code evGroup} is given). */
    private static float baseOf(Doc.DNote n, int ch) {
        switch (ch) {
            case CH_ALPHA: return 1f;
            case CH_HEIGHT: return n.height;
            case CH_OFFX: return n.x;
            case CH_ORBX: return n.orbitX;
            case CH_ORBY: return n.orbitY;
            case CH_ORBR: return Float.isNaN(n.orbitRadius) ? 0f : n.orbitRadius;
            case CH_ORBA: return Float.isNaN(n.orbitAngle) ? 0f : n.orbitAngle;
            case CH_FY: return n.y;
            case CH_FSIZE:
            case CH_FILL: return n.sizeOr();
            default: return 0f;
        }
    }

    private static String chName(int ch) {
        switch (ch) {
            case CH_MOVE: return "移动";
            case CH_ROT: return "旋转";
            case CH_ALPHA: return "透明";
            case CH_HEIGHT: return "尾长";
            case CH_OFFX: return "横坐标";
            case CH_ORBX: return "公转中心 x";
            case CH_ORBY: return "公转中心 y";
            case CH_ORBR: return "公转半径";
            case CH_ORBA: return "公转角度";
            case CH_LINE: return "判定线";
            case CH_XLAY: return "横坐标叠加";
            case CH_POSLAY: return "位置叠加";
            case CH_ROTLAY: return "旋转叠加";
            case CH_LINELAY: return "判定线叠加";
            case CH_FY: return "纵坐标";
            case CH_FSIZE: return "大小";
            case CH_FILL: return "填充";
            default: return "漩涡";
        }
    }

    private void evEditor(String id, List<Doc.Ev> list, Doc.DNote n, int ch, float base) {
        Session s = ed.s;
        if (evSel >= list.size()) evSel = list.size() - 1;
        if (evSel < 0 && !list.isEmpty()) evSel = 0;
        if (!list.isEmpty()) graph(list);
        if (pickList != list) {
            pickList = list;
            evPick.clear();
        }
        motionBar(id, list, n, ch, base);
        if (dragList != null && !im.down) dropCard(s, list, y);
        float top = y;
        int to = dragList == list ? dragTarget(top, list.size()) : -1, from = to >= 0 ? list.indexOf(dragEv) : -1;
        Doc.Ev lift = null;
        for (int i = 0; i < list.size(); i++) {
            Doc.Ev e = list.get(i);
            boolean on = i == evSel || evPick.contains(e), rel = n != null && e.rel, held = e == dragEv && to >= 0;
            float off = rel ? n.judgement : 0f, rowY = y;
            Im.Pair row = new Im.Pair(id + "-r", i);
            if (im.click(row, CX + 28f, y, CW - 66f, 30f)) pickCard(list, i);
            if (row.equals(im.active) && im.down && holdRow == null) {
                holdRow = row;                                        // a press on a card: lift it after a hold
                holdAt = System.currentTimeMillis();
            }
            if (row.equals(holdRow) && row.equals(im.active) && System.currentTimeMillis() - holdAt > HOLD_MS
                && Math.abs(im.my - im.pressY) < 10f && Math.abs(im.mx - im.pressX) < 10f) lift = e;
            boolean onHandle = im.hover(CX + 2f, y, 26f, 30f);
            if (onHandle) im.cursor(Cursor.SystemCursor.Hand);
            if (onHandle && im.pressed && !im.rightPressed && dragList == null) lift = e;
            if (lift == e) dragGrab = im.my - rowY;
            g.box(CX + 2f, y, CW - 4f, 30f, 5f, held ? Gfx.BG : on ? Gfx.ITEM_ON : Gfx.FIELD,
                held ? Gfx.SEL_LINE : on ? Gfx.GND : Gfx.ITEM_LINE);
            for (int k = 0; k < 3; k++) g.line(CX + 10f, y + 11f + 4f * k, CX + 20f, y + 11f + 4f * k, 1.2f, Gfx.INK3);
            g.text("#" + (i + 1), CX + 28f, y + 15f, 12, Gfx.INK3);
            g.text(beatText(e.start - off, rel) + " → " + beatText(e.end - off, rel) + "   " + Im.fmt(e.from) + " → "
                + Im.fmt(e.to), CX + 54f, y + 15f, 12, held ? Gfx.INK3 : Gfx.INK1);
            if (e.link != null) {                                      // 同步链接: a source counts its copies
                g.box(CX + CW - 184f, y + 5f, 46f, 20f, 4f, Gfx.ITEM_ON, Gfx.GND);
                g.text(e.link.src ? "链接×" + s.doc.followers(e.link.id) : "链接", CX + CW - 161f, y + 15f, 11, Gfx.GND, 1);
            }
            if (e.link == null && e.tag != null) {                     // 快捷曲线 / 快捷事件: which group it is in
                g.box(CX + CW - 202f, y + 5f, 64f, 20f, 4f, Gfx.FIELD, Gfx.ITEM_LINE);
                g.text(e.tag, CX + CW - 170f, y + 15f, 11, Gfx.INK2, 1);
            }
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
            g.text("exp " + Im.fmt(Math.round(e.exp * 100f) / 100f), CX + CW - 40f, y + 15f, 12, Gfx.INK3, 2);
            if (im.button(new Im.Pair(id + "-x", i), CX + CW - 32f, y + 3f, 26f, 24f, "×", Im.GHOST, 14)) {
                s.mark();
                evPick.remove(list.remove(i));
                s.changed(false);
                evSel = Math.min(evSel, list.size() - 1);
                break;
            }
            y += 34f;
        }
        if (to >= 0) {                                                 // where the lifted card will land
            float ly = top + to * 34f + (to > from ? 32f : -2f);
            g.rect(CX + 2f, ly - 1f, CW - 4f, 2f, Gfx.GND);
            float fy = Math.max(top - 17f, Math.min(im.my - dragGrab, top + (list.size() - 1) * 34f + 17f));
            g.box(CX + 8f, fy, CW - 16f, 30f, 5f, Gfx.ITEM_ON, Gfx.GND);
            g.text("#" + (from + 1) + "  " + Im.fmt(dragEv.from) + " → " + Im.fmt(dragEv.to), CX + 28f, fy + 15f, 12,
                Gfx.INK1);
        }
        if (lift != null) liftCard(list, lift);
        if (!im.down) holdRow = null;
        if (keepFor == list) keepBar(s);
        if (pasted != null && id.equals(pasteFor)) pasteBar(s);
        if (evSel >= 0 && evSel < list.size()) {
            Doc.Ev e = list.get(evSel);
            if (e.link != null) linkBar(s, e, ch);
            boolean rel = n != null && e.rel;
            float off = rel ? n.judgement : 0f;
            float w2 = (CW - 8f) / 2f, w3 = (CW - 16f) / 3f;
            String unit = rel ? "距判定" : "拍";
            float head = ed.snap(ed.beat);
            testStartY = y;
            if (rel) im.slash(head - off, Im.beatHint(head - off, "距判定 "), true);
            float a = im.num(new Im.Pair(id + "-s", evSel), CX, y, w2, 30f, e.start - off, unit);
            if (rel) im.slash(head - off, Im.beatHint(head - off, "距判定 "), true);
            float b = im.num(new Im.Pair(id + "-e", evSel), CX + w2 + 8f, y, w2, 30f, e.end - off, unit);
            y += 36f;
            float now = Doc.lineAt(list, base, head);
            String vh = "/ = 播放头那一刻的值 " + Im.fmt(now) + "，/+10 = 再加 10";
            im.slash(now, vh, false);
            float f0 = im.num(new Im.Pair(id + "-a", evSel), CX, y, w3, 30f, e.from, "");
            im.slash(now, vh, false);
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
        if (!list.isEmpty()) curves(s, id, list);
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
        ArrayList<Target> one = new ArrayList<>();
        one.add(new Target(n, list, base));
        quickForm(s, id, ch, one, n);
    }

    /**
     * 旋转中心: 自转 is fixed at the key's centre (Kelvin 2026-10-29 20:55, a pivot an older chart wrote is kept), or,
     * for keys (not tides), 公转 about a point of the screen (§3, 10-29 19:04); set on every selected note.
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
     * 公转: the centre (x from the centre line, y up) shared by the selected keys and its moves, then the
     * radius and the angle (Kelvin 2026-10-29 20:55, 21:13) and which wins a 物件坐标冲突.
     */
    private void orbit(Session s, List<Doc.DNote> sel, Doc.DNote n, float fw) {
        g.text("公转中心", CX, y + 15f, 13, Gfx.INK2);
        float ox = im.num("n-ox", FX, y, fw, 30f, n.orbitX, "x");
        float oy = im.num("n-oy", FX + fw + 8f, y, fw, 30f, n.orbitY, "y");
        y += 36f;
        boolean mid = im.button("n-om", CX, y, CW, 28f, "画面中心 (0, 600)", Im.NORMAL, 12);
        y += 36f;
        caption("x 从屏幕中线量（左负右正，边缘 ±960），y 按 1200 高的画面从下往上量。不写半径和角度时，键按自己的「旋转」角度绕这一点转，几个键填同一个点就整体转。"
            + "中心的事件让它移动，键跟着平移；事件结束后中心停在终点。选中后预览里的十字就是公转中心。");
        if (mid || Float.isFinite(ox) || Float.isFinite(oy)) {
            s.mark();
            for (Doc.DNote x : sel) {
                if (!x.inLane()) continue;
                x.orbit = true;
                if (mid) {
                    x.orbitX = 0f;
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
            + "公转只在中心、半径、角度这几组事件的时段里起作用（一个事件都没有就一直起作用），时段外键回到自己的横坐标按「移动」落下。事件结束后停在终点。");
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
        if (layer(ch)) {
            // a layer adds on top, so a new one starts at the playhead from nothing
            float b = ed.snap(ed.beat);
            return new Doc.Ev(b, b + 1f, 0f, 0f, 1f);
        }
        if (!list.isEmpty()) {
            Doc.Ev last = list.get(list.size() - 1);
            return new Doc.Ev(last.end, last.end + 1f, last.to, last.to, 1f);
        }
        float b = ed.snap(ed.beat);
        switch (ch) {
            case CH_MOVE: return new Doc.Ev(n.judgement - 2f, n.judgement, Doc.SPAWN, 0f, 1f);
            case CH_ROT: return new Doc.Ev(n.judgement - 2f, n.judgement, 0f, 0f, 1f);
            case CH_OFFX: return new Doc.Ev(n.judgement - 2f, n.judgement, n.x, n.x, 1f);
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
            case CH_FY: return new Doc.Ev(n.judgement - 2f, n.judgement, n.y, n.y, 1f);
            case CH_FSIZE: return new Doc.Ev(n.judgement - 2f, n.judgement, n.sizeOr(), n.sizeOr(), 1f);
            // from empty when it shows to full at its beat: the game's own fill without events
            case CH_FILL: return new Doc.Ev(defaultAppear(n), n.judgement, n.sizeOr(), 0f, 1f);
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

    // ================================================================== 快捷曲线 · 快捷事件 (Kelvin 2026-11-06)

    /** The quick curve buttons' shape (缓入 · 缓出 · 缓入缓出). */
    private int curveShape = QuickEvents.OUT;
    /** The open quick event: its kind (−1 = none) and the list id (or {@link #MANY}) it is for. */
    int qeKind = -1;
    String qeFor;
    /** Each kind's numbers by list, kept between uses. */
    private final java.util.HashMap<String, QuickEvents.Params> qeParams = new java.util.HashMap<>();
    /**
     * Where it starts: NaN = the playhead, else the typed beat (from the first key's judgement when
     * {@link #qeRel}).
     */
    float qeFrom = Float.NaN;
    boolean qeRel = true;
    /** The channel the many-key quick event goes into. */
    int qeCh = CH_POSLAY;
    private static int nextGroup = 1;
    /** For the AutoTest: where the curve buttons and the quick event menu were drawn. */
    float testCurveY = Float.NaN, testQeY = Float.NaN, testQeGoY = Float.NaN;
    private static final int[] QE_CHANNELS = {CH_POSLAY, CH_MOVE, CH_ROT, CH_ROTLAY, CH_OFFX, CH_XLAY, CH_ALPHA,
        CH_HEIGHT, CH_ORBA, CH_FY, CH_FSIZE, CH_FILL};
    /** 常用指数: the exponents most used in Kelvin's charts. */
    static final float[] EXPS = {0.3f, 0.4f, 0.5f, 0.7f, 1.5f, 2f, 2.2f, 3f};

    /** 快捷曲线: the picked cards (or the selected one) take a curve; one the game has no exponent for is baked. */
    private void curves(Session s, String id, List<Doc.Ev> list) {
        testCurveY = y;
        g.text("快捷曲线", CX, y + 15f, 13, Gfx.INK2);
        curveShape = im.segmented(id + "-csh", CX + 76f, y, CW - 76f, 30f, QuickEvents.SHAPES, curveShape);
        y += 36f;
        float bw = (CW - 30f) / 6f;
        for (int c = 0; c < QuickEvents.CURVES.length; c++) {
            float bx = CX + (c % 6) * (bw + 6f), by = y + (c / 6) * 34f;
            if (im.button(new Im.Pair(id + "-cv", c), bx, by, bw, 30f, "", Im.NORMAL)) {
                applyCurve(s, list, c, curveShape, Float.NaN);
            }
            glyph(c, curveShape, bx + 6f, by + 6f, 20f, 18f);
            g.text(QuickEvents.CURVES[c], bx + 30f, by + 15f, 12, Gfx.INK1);
        }
        y += 70f;
        g.text("常用指数", CX, y + 14f, 13, Gfx.INK2);
        float ew = (CW - 76f - 4f * (EXPS.length - 1)) / EXPS.length;
        for (int i = 0; i < EXPS.length; i++) {
            if (im.button(new Im.Pair(id + "-ex", i), CX + 76f + i * (ew + 4f), y, ew, 28f, Im.fmt(EXPS[i]), Im.GHOST,
                12)) applyCurve(s, list, QuickEvents.LINEAR, 0, EXPS[i]);
        }
        y += 36f;
        caption("点一下，选中的卡片就换成这种曲线（Ctrl / Shift 点卡片可以多选）。游戏一段事件只认指数，正弦、回弹、弹性、"
            + "弹跳这些拆成几段近似；拆出来的几段点一下会一起选中，再点别的曲线就整段换。");
    }

    /** Draws curve {@code c} with shape {@code sh} in the {@code w} × {@code h} box at ({@code x}, {@code top}). */
    private void glyph(int c, int sh, float x, float top, float w, float h) {
        float px = 0f, py = 0f;
        for (int k = 0; k <= 24; k++) {
            float p = k / 24f, v = (float) QuickEvents.ease(c, sh, p);
            float sx = x + p * w, sy = top + h - (0.15f + 0.7f * v) * h;
            if (k > 0) g.line(px, py, sx, sy, 1.4f, Gfx.GND);
            px = sx;
            py = sy;
        }
    }

    /**
     * Gives the picked cards of {@code list} curve {@code c}, shape {@code sh}, as one undo step: a whole quick group
     * turns as one stretch (from its first card's start value to its last card's end value), any other card alone.
     * A number {@code ex} (常用指数) makes each stretch one event with that exponent instead.
     */
    void applyCurve(Session s, List<Doc.Ev> list, int c, int sh, float ex) {
        List<Doc.Ev> picked = picked(list);
        if (picked.isEmpty()) return;
        s.mark();
        ArrayList<Doc.Ev> made = new ArrayList<>();
        java.util.Set<Doc.Ev> gone = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        for (Doc.Ev e : picked) {
            if (gone.contains(e)) continue;
            ArrayList<Doc.Ev> run = new ArrayList<>();
            if (e.group != 0) for (Doc.Ev x : list) if (x.group == e.group) run.add(x);
            if (run.size() < 2 || !picked.containsAll(run)) {
                run.clear();
                run.add(e);
            }
            gone.addAll(run);
            Doc.Ev a = run.get(0), b = run.get(0);
            for (Doc.Ev x : run) {
                if (x.start < a.start) a = x;
                if (x.end > b.end) b = x;
            }
            Doc.Ev span = e;
            if (run.size() > 1) {
                span = new Doc.Ev(a.start, b.end, a.from, b.to, 1f);
                span.rel = a.rel;
            }
            List<Doc.Ev> part = QuickEvents.curve(span, c, sh);
            if (!Float.isNaN(ex)) part.get(0).exp = ex;
            tagGroup(part, QuickEvents.CURVES[c]);
            made.addAll(part);
        }
        list.removeIf(gone::contains);
        list.addAll(made);
        list.sort(Comparator.comparingDouble(x -> x.start));
        s.changed(false);
        evPick.clear();
        evPick.addAll(made);
        evSel = list.indexOf(made.get(0));
        String name = !Float.isNaN(ex) ? "指数 " + Im.fmt(ex)
            : QuickEvents.CURVES[c] + (c == QuickEvents.LINEAR ? "" : QuickEvents.SHAPES[sh]);
        im.toast(made.size() == gone.size() ? "换成了" + name : "换成了" + name + "（拆成 " + made.size() + " 段）");
    }

    /** Puts the cards one quick curve or quick event made into a new group, named {@code name} k/N. */
    private static void tagGroup(List<Doc.Ev> part, String name) {
        if (part.size() < 2) return;
        int g = nextGroup++;
        for (int i = 0; i < part.size(); i++) {
            part.get(i).group = g;
            part.get(i).tag = name + " " + (i + 1) + "/" + part.size();
        }
    }

    /** The many-key quick event: into the same channel of every selected key that has it. */
    private void quickMany(Session s, ArrayList<Doc.DNote> sel) {
        title("快捷事件", null);
        String[] names = new String[QE_CHANNELS.length];
        int at = 0;
        for (int i = 0; i < names.length; i++) {
            names[i] = chName(QE_CHANNELS[i]);
            if (QE_CHANNELS[i] == qeCh) at = i;
        }
        g.text("放进", CX, y + 16f, 13, Gfx.INK2);
        int c = im.dropdown("m-qe-ch", CX + 44f, y, 200f, 32f, names[at], names, at);
        if (c != at) qeCh = QE_CHANNELS[c];
        y += 40f;
        quickForm(s, MANY, qeCh, manyTargets(sel, qeCh), sel.get(0));
        caption("选中的键里有这种运动的每个都放一份；「相对」时从拍按各自的判定算（距第一个键的判定），一起抖、一个接一个闪都行。"
            + "叠加层加在原来的运动上，别的换掉这一段里原来的事件。");
    }

    /** @return the lists of channel {@code ch} of the keys in {@code sel} that have it. */
    private static ArrayList<Target> manyTargets(List<Doc.DNote> sel, int ch) {
        ArrayList<Target> t = new ArrayList<>();
        for (Doc.DNote k : sel) {
            List<Doc.Ev> l = listOf(k, ch);
            if (l != null) t.add(new Target(k, l, baseOf(k, ch)));
        }
        return t;
    }

    /**
     * 快捷事件 (Kelvin 2026-11-06): a menu of ready-made motions and the numbers of the chosen one; 放上去 builds it on
     * top of what each target list does there and puts it in ({@link QuickEvents#make}, {@link QuickEvents#splice}).
     *
     * @param first the key the start beat is shown from (null for a line or veil list)
     */
    private void quickForm(Session s, String id, int ch, List<Target> targets, Doc.DNote first) {
        testQeY = y;
        boolean mine = id.equals(qeFor) && qeKind >= 0;
        int k = im.dropdown(id + "-qe", CX, y, CW, 30f,
            mine ? "快捷事件：" + QuickEvents.EVENTS[qeKind] : "快捷事件：抖动、摆动、弹跳、闪烁……", QuickEvents.EVENTS,
            mine ? qeKind : -1);
        if (k >= 0 && (!mine || k != qeKind)) {
            qeKind = k;
            qeFor = id;
            qeFrom = Float.NaN;
        }
        y += 38f;
        if (!id.equals(qeFor) || qeKind < 0) return;
        QuickEvents.Params q = params(qeKind, id, ch);
        boolean rel = first != null && qeRel;
        float head = ed.snap(ed.beat), off = rel ? first.judgement : 0f, w3 = (CW - 16f) / 3f;
        im.slash(head - off, Im.beatHint(head - off, rel ? "距判定 " : ""), true);
        float f = qField(id + "-qe-from", rel ? "从拍（距判定）" : "从拍", 0, Float.isNaN(qeFrom) ? head - off : qeFrom);
        if (!Float.isNaN(f)) qeFrom = f;
        float b = qField(id + "-qe-len", "拍数", 1, q.beats);
        if (!Float.isNaN(b)) {
            if (b > 0f) q.beats = b;
            else im.toast("拍数要大于 0");
        }
        if (first != null) {
            int r = im.segmented(id + "-qe-rel", CX + 2f * (w3 + 8f), y + 14f, w3, 30f, new String[]{"相对", "绝对"},
                qeRel ? 0 : 1);
            if (r == 0 != qeRel) {
                qeRel = r == 0;
                qeFrom = Float.NaN;
            }
        }
        y += 52f;
        kindFields(id, q);
        caption(QuickEvents.EVENT_HELP[qeKind]);
        float w2 = (CW - 8f) / 2f;
        testQeGoY = y;
        if (im.button(id + "-qe-go", CX, y, w2, 32f, "放上去", Im.PRIMARY, 13)) {
            float from = Float.isNaN(qeFrom) ? head - off : qeFrom;
            applyQuick(s, ch, targets, qeKind, q, from, rel);
        }
        if (im.button(id + "-qe-x", CX + w2 + 8f, y, w2, 32f, "收起", Im.GHOST, 13)) qeKind = -1;
        y += 42f;
    }

    /** A small label over a number field in column {@code col} of three; @return the typed value or NaN. */
    private float qField(String id, String label, int col, float value) {
        float w3 = (CW - 16f) / 3f, x = CX + col * (w3 + 8f);
        g.text(label, x + 2f, y + 6f, 11, Gfx.INK3);
        return im.num(id, x, y + 14f, w3, 30f, value, "");
    }

    /** The numbers of the chosen kind, in one or two rows. */
    private void kindFields(String id, QuickEvents.Params q) {
        String[] labels;
        switch (qeKind) {
            case QuickEvents.SHAKE: labels = new String[]{"次数", "幅度"}; break;
            case QuickEvents.SWAY: labels = new String[]{"圈数（来回几次）", "幅度"}; break;
            case QuickEvents.HOP: labels = new String[]{"次数", "高度", "每次剩"}; break;
            case QuickEvents.PULSE: labels = new String[]{"次数", "幅度"}; break;
            case QuickEvents.BLINK: labels = new String[]{"次数", "闪成", "之后保持（拍）"}; break;
            case QuickEvents.THERE_BACK: labels = new String[]{null, "变化量"}; break;
            case QuickEvents.RAMP: labels = new String[]{null, "变化量", "之后保持（拍）"}; break;
            case QuickEvents.STAIRS: labels = new String[]{"级数", "总变化", "之后保持（拍）"}; break;
            default: labels = new String[]{null, "值（空着 = 现在的值）"};
        }
        for (int i = 0; i < labels.length; i++) {
            if (labels[i] == null) continue;
            float cur = i == 0 ? q.count : i == 1 ? q.amount : labels[i].startsWith("之后") ? q.hold : q.ratio;
            float v = qField(new Im.Pair(id + "-qe-f", i).toString(), labels[i], i, cur);
            if (Float.isNaN(v)) continue;
            if (i == 0) {
                if (v > 0f) q.count = v;
                else im.toast("次数要大于 0");
            } else if (i == 1) q.amount = v;
            else if (labels[i].startsWith("之后")) q.hold = Math.max(0f, v);
            else q.ratio = v;
        }
        boolean curve = qeKind == QuickEvents.THERE_BACK || qeKind == QuickEvents.RAMP;
        if (curve) {                                     // the curve takes the first column
            float w3 = (CW - 16f) / 3f;
            g.text("曲线", CX + 2f, y + 6f, 11, Gfx.INK3);
            int c = im.dropdown(id + "-qe-cv", CX, y + 14f, w3, 30f, QuickEvents.CURVES[q.curve], QuickEvents.CURVES,
                q.curve);
            if (c >= 0) q.curve = c;
        }
        y += 52f;
        if (curve) {
            q.shape = im.segmented(id + "-qe-sh", CX, y, CW, 30f, QuickEvents.SHAPES, q.shape);
            y += 38f;
        } else if (qeKind == QuickEvents.BLINK) {
            q.style = im.segmented(id + "-qe-st", CX, y, CW, 30f, new String[]{"一下灭一下亮", "每下渐暗"}, q.style);
            y += 38f;
        } else if (qeKind == QuickEvents.SHAKE || qeKind == QuickEvents.SWAY) {
            g.text("变化", CX, y + 15f, 12, Gfx.INK2);
            q.grow = im.segmented(id + "-qe-gr", CX + 44f, y, CW - 44f, 30f, QuickEvents.GROW, q.grow);
            y += 38f;
        }
    }

    /** @return the numbers kind {@code kind} last had in list {@code id} (its channel's defaults the first time). */
    private QuickEvents.Params params(int kind, String id, int ch) {
        return qeParams.computeIfAbsent(kind + "/" + id + "/" + ch, x -> defaults(kind, ch, id));
    }

    /** First numbers of a quick event: sizes that suit the channel (a quarter turn is not 60). */
    static QuickEvents.Params defaults(int kind, int ch, String id) {
        QuickEvents.Params p = new QuickEvents.Params();
        boolean turn = ch == CH_ROT || ch == CH_ROTLAY || ch == CH_ORBA || id.endsWith("rotev"),
            alpha = ch == CH_ALPHA || id.endsWith("opev"), scale = id.equals("f-scale");
        float u = turn ? 15f : alpha ? 0.3f : scale ? 0.1f : id.endsWith("blurev") ? 8f
            : ch == CH_LINE || ch == CH_LINELAY ? 30f : ch == CH_FSIZE || ch == CH_FILL ? 40f : 60f;
        p.amount = u;
        switch (kind) {
            case QuickEvents.SHAKE: p.count = 6f; break;
            case QuickEvents.SWAY: p.count = 2f; break;
            case QuickEvents.HOP:
                p.count = 3f;
                p.amount = 2f * u;
                break;
            case QuickEvents.BLINK:
                p.count = 2f;
                p.beats = 1f;
                p.amount = 0f;
                break;
            case QuickEvents.THERE_BACK: p.amount = 2f * u; break;
            case QuickEvents.RAMP: p.amount = turn ? 360f : alpha ? -1f : 4f * u; break;
            case QuickEvents.STAIRS: p.amount = alpha ? 1f : 4f * u; break;
            case QuickEvents.HOLD: p.amount = Float.NaN; break;
            default: break;
        }
        return p;
    }

    /** @return true for a channel whose events keep their end value until the next one (as the game reads it). */
    private static boolean held(Doc.DNote n, int ch) {
        return ch == CH_FY || ch == CH_FSIZE || ch == CH_FILL || ch == CH_ORBX || ch == CH_ORBY
            || ch == CH_OFFX && n != null && n.floating;
    }

    /**
     * Puts quick event {@code kind} into every target as one undo step, each built on top of what its list does now.
     *
     * @param from the start beat, from each key's judgement when {@code rel}
     * @return how many cards it made, or −1 when nothing changed (with a toast)
     */
    int applyQuick(Session s, int ch, List<Target> targets, int kind, QuickEvents.Params q, float from, boolean rel) {
        if (targets.isEmpty()) {
            im.toast("选中的键没有「" + chName(ch) + "」这种运动");
            return -1;
        }
        boolean lay = layer(ch);
        ArrayList<List<Doc.Ev>> built = new ArrayList<>();
        for (Target t : targets) {                       // build them all first: a refusal changes nothing
            double t0 = rel && t.n != null ? t.n.judgement + from : from;
            ArrayList<Doc.Ev> was = new ArrayList<>(t.list);
            boolean hold = held(t.n, ch);
            float base = t.base;
            QuickEvents.Fn under = lay ? x -> 0.0 : x -> QuickEvents.valueAt(was, base, hold, x);
            List<Doc.Ev> made = QuickEvents.make(kind, q, t0, under);
            if (made == null || made.isEmpty()) {
                im.toast(QuickEvents.why != null ? QuickEvents.why : "这一段放不下");
                return -1;
            }
            built.add(made);
        }
        s.mark();
        int count = 0;
        for (int i = 0; i < targets.size(); i++) {
            Target t = targets.get(i);
            List<Doc.Ev> made = built.get(i);
            for (Doc.Ev e : made) e.rel = rel && t.n != null;
            tagGroup(made, QuickEvents.EVENTS[kind]);
            QuickEvents.splice(t.list, made.get(0).start, made.get(made.size() - 1).end, made, lay);
            count += made.size();
        }
        s.changed(false);
        if (targets.size() == 1) {
            List<Doc.Ev> made = built.get(0);
            evPick.clear();
            evPick.addAll(made);
            evSel = targets.get(0).list.indexOf(made.get(0));
        }
        im.toast("放好了「" + QuickEvents.EVENTS[kind] + "」" + (targets.size() > 1 ? targets.size() + " 个键，" : "，")
            + "共 " + count + " 段");
        return count;
    }

    // ================================================================== 场地

    private void fieldTab() {
        Session s = ed.s;
        Doc d = s.doc;
        fixedLanes(s, d);
        divider();
        title("坐标吸附", null);
        int xi = java.util.Arrays.binarySearch(X_STEPS, ed.xStep);
        int nx = im.segmented("f-xstep", CX, y, CW, 30f, X_STEP_LABELS, xi >= 0 ? xi : 4);
        if (nx >= 0 && nx < X_STEPS.length) ed.xStep = X_STEPS[nx];
        y += 38f;
        caption("在预览里放键、拖键，和音符页的 ‹ › 都按这个间距落在横坐标上（以屏幕中线为 0），一个键宽 240；"
            + "悬浮键的纵坐标和线条的点也一样。定轨的地方改成吸到轨道上：120、240 是轨道中心和两条轨道中间（半轨），"
            + "60 是 1/4 轨，30 及以下 1/8 轨。选「关」时放键、拖键不吸附。");
        divider();
        title("全局缩放", null);
        lineRow(s, 3, "倍数", Gfx.FX);
        caption("所有键的横坐标和宽度一起乘这个倍数，以屏幕中线为准；点右边加事件让它随时间变。");
        divider();
        title("镜头平移", null);
        lineRow(s, 2, "平移", Gfx.INK2);
        caption("所有键一起往右挪多少（负数往左）；点右边加事件让它随时间移动。全局事件都是绝对拍。");
        divider();
        title("判定线", null);
        lineRow(s, 0, "上线 y", Gfx.INK1);
        lineRow(s, 1, "下线 y", Gfx.GND);
        caption("判定区 = 线上下各 " + Math.round(GameEngine.ZONE_HALF) + "（跟着线动）");
        divider();
        title("叠加层", null);
        layerRow(s, 4, "上线 y", Gfx.INK1);
        layerRow(s, 5, "下线 y", Gfx.GND);
        layerRow(s, 6, "镜头平移", Gfx.INK2);
        caption("每段事件在它的时间里把数值加到上线 y、下线 y 或镜头平移上，可以重叠，事件外加 0；判定区跟着线走。"
            + "音符页的「不跟判定线」也会抵消上线、下线的叠加。");
    }

    /**
     * 定轨 / 无轨 (game 7.184, CHART_FORMAT §12, Kelvin 2026-11-06): the sections of fixed lanes, each from a beat on
     * (0 lanes = 无轨); the one in force at the playhead is lit.
     */
    private void fixedLanes(Session s, Doc d) {
        int now = d.lanesAt(ed.beat);
        title("定轨 / 无轨", "播放头 " + (now > 0 ? now + " 轨" : "无轨"));
        int cur = -1, remove = -1;
        for (int i = 0; i < d.fixedLanes.size(); i++) {
            float b = d.fixedLanes.get(i).beat;
            if (b <= ed.beat || i == 0 && b <= 0f) cur = i;
        }
        for (int i = 0; i < d.fixedLanes.size(); i++) {
            Doc.Lanes l = d.fixedLanes.get(i);
            boolean on = i == cur;
            g.box(CX, y, CW, 38f, 6f, on ? Gfx.ITEM_ON : Gfx.ITEM, on ? Gfx.SEL_LINE : Gfx.ITEM_LINE);
            g.text("#" + (i + 1), CX + 12f, y + 19f, 12, Gfx.INK3);
            g.text("从拍", CX + 40f, y + 19f, 12, Gfx.INK2);
            im.slash(ed.snap(ed.beat), Im.beatHint(ed.snap(ed.beat), ""), true);
            float b = im.num(new Im.Pair("fl-b", i), CX + 74f, y + 5f, 86f, 28f, l.beat, "");
            if (!Float.isNaN(b) && b != l.beat) setLanesBeat(s, d, l, b);
            g.text("轨道", CX + 172f, y + 19f, 12, Gfx.INK2);
            float k = im.num(new Im.Pair("fl-k", i), CX + 206f, y + 5f, 84f, 28f, l.count, "");
            if (!Float.isNaN(k) && k != l.count) {
                if (k == Math.rint(k) && k >= 0f && k <= Chart.MAX_FIXED_LANES) {
                    s.mark();
                    l.count = (int) k;
                    s.changed(false);
                } else im.toast("轨道数是 0 到 " + Chart.MAX_FIXED_LANES + " 的整数（0 = 无轨）");
            }
            if (im.button(new Im.Pair("fl-x", i), CX + CW - 32f, y + 7f, 24f, 24f, "×", Im.GHOST, 14)) remove = i;
            y += 44f;
        }
        if (remove >= 0) {
            s.mark();
            d.fixedLanes.remove(remove);
            s.changed(false);
        }
        String add = d.fixedLanes.isEmpty() ? "+ 从第 0 拍定 " + Chart.DEFAULT_FIXED_LANES + " 轨" : "+ 在播放头改轨道数";
        if (im.button("fl-add", CX, y, CW, 32f, add, Im.NORMAL, 13)) addLanes(s, d);
        y += 40f;
        caption("从这一拍起把两条线之间分成几条一样宽的轨道（0 = 无轨）并画出分隔线。键还是用自己的横坐标，放键、拖键和音符页的 ‹ › "
            + "会吸到轨道上（场地页「坐标吸附」120 以上是半轨，60 是 1/4 轨，30 及以下 1/8 轨）；游戏里线上的键"
            + "至少按一条轨道宽判定。新建的谱面默认从第 0 拍 "
            + Chart.DEFAULT_FIXED_LANES + " 轨。");
    }

    /** Adds a section at the playhead (the first one at beat 0) that switches 定轨 ↔ 无轨 there. */
    private void addLanes(Session s, Doc d) {
        float b = d.fixedLanes.isEmpty() ? 0f : ed.snap(ed.beat);
        if (b < 0f) {
            im.toast("先把播放头移到第 0 拍之后");
            return;
        }
        for (Doc.Lanes o : d.fixedLanes) {
            if (Math.abs(o.beat - b) < 1e-4f) {
                im.toast("这一拍已经设过轨道数了，改那一行就行");
                return;
            }
        }
        int k = d.lanesAt(b) > 0 ? 0 : Chart.DEFAULT_FIXED_LANES;
        s.mark();
        d.fixedLanes.add(new Doc.Lanes(b, k));
        d.sortLanes();
        s.changed(false);
        im.toast("从第 " + Im.fmt(b) + " 拍起" + (k > 0 ? " " + k + " 轨" : "无轨") + "，要别的轨道数就改那一行");
    }

    private void setLanesBeat(Session s, Doc d, Doc.Lanes l, float b) {
        if (b < 0f) {
            im.toast("拍不能是负数");
            return;
        }
        for (Doc.Lanes o : d.fixedLanes) {
            if (o != l && Math.abs(o.beat - b) < 1e-4f) {
                im.toast("这一拍已经设过轨道数了");
                return;
            }
        }
        s.mark();
        l.beat = b;
        d.sortLanes();
        s.changed(false);
    }

    /** A row of the line layers (game 7.177; {@link Checks#fieldLists} 4–6): no value of its own, just the events. */
    private void layerRow(Session s, int k, String label, Color c) {
        List<Doc.Ev> list = Checks.fieldLists(s.doc).get(k);
        g.text(label, CX, y + 15f, 13, c);
        boolean isOpen = LINE_IDS[k].equals(open);
        String cnt = list.isEmpty() ? "0 ›" : list.size() + (isOpen ? " 段 ▼" : " 段 ›");
        if (im.button(LINE_IDS[k], FX + FW - 60f, y, 60f, 30f, cnt, isOpen ? Im.ON : Im.GHOST, 12)) {
            open = isOpen ? null : LINE_IDS[k];
            evSel = list.isEmpty() ? -1 : 0;
        }
        y += 38f;
        if (LINE_IDS[k].equals(open)) evEditor(LINE_IDS[k], list, null, CH_LINELAY, 0f);
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
        caption("中心 x 从屏幕中线量（左负右正），y 按 1200 高的画面从下往上量；角度逆时针。出现空着 = 不限。");
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
        return span(v.start, v.end);
    }

    private static String span(float from, float to) {
        boolean a = !Float.isNaN(from), b = !Float.isNaN(to);
        if (a && b) return Im.fmt(from) + " → " + Im.fmt(to) + " 拍";
        if (a) return "第 " + Im.fmt(from) + " 拍起";
        return b ? "到第 " + Im.fmt(to) + " 拍" : "一直都在";
    }

    // ================================================================== 线条 (game 7.187)

    /** The selected custom line's uid (0 = none). */
    int strokeSel;

    /** 自定义线条 (game 7.187, CHART_FORMAT §13): the list, a new line at the playhead and the selected one's card. */
    private void strokeTab() {
        Session s = ed.s;
        List<Doc.DStroke> list = s.doc.strokes;
        title("自定义线条", list.isEmpty() ? null : list.size() + " 条");
        for (int i = 0; i < list.size(); i++) {
            Doc.DStroke st = list.get(i);
            boolean on = st.uid == strokeSel, hv = im.hover(CX, y, CW, 32f);
            if (hv) im.cursor(Cursor.SystemCursor.Hand);
            g.box(CX, y, CW, 32f, 5f, on ? Gfx.ITEM_ON : Gfx.ITEM, on ? Gfx.GND : hv ? Gfx.SEL_LINE : Gfx.ITEM_LINE);
            g.text("线条 · #" + (i + 1), CX + 12f, y + 16f, 12, Gfx.INK3);
            g.text((st.curve ? "曲线" : "直线") + " · " + st.pointCount() + " 个点", CX + 88f, y + 16f, 13,
                st.pointCount() < 2 ? Gfx.BAD : Gfx.INK1);
            g.text(span(st.from, st.to), CX + CW - 12f, y + 16f, 12, Gfx.INK3, 2);
            if (im.click(new Im.Pair("s-row", i), CX, y, CW, 32f)) {
                strokeSel = on ? 0 : st.uid;
                if (!on && (ed.beat < st.from || ed.beat > st.to)) ed.seekBeat(Float.isNaN(st.from) ? st.to : st.from);
            }
            y += 38f;
        }
        if (im.button("s-add", CX, y, CW, 32f, "+ 新线条（从播放头起 4 拍）", Im.NORMAL, 13)) {
            s.mark();
            Doc.DStroke st = new Doc.DStroke();
            st.points = new float[]{-300f, 600f, 300f, 600f};
            st.from = ed.snap(ed.beat);
            st.to = st.from + 4f;
            st.drawIn = 0.5f;
            st.drawOut = 0.5f;
            s.doc.strokes.add(st);
            strokeSel = st.uid;
            s.changed(false);
        }
        y += 40f;
        caption("表演用的线，不判定。选中一条后，在预览里点空白处在最后加一个点，拖小方块挪点（按坐标吸附对齐），"
            + "Alt 点小方块删掉那个点。默认画在键下面。");
        Doc.DStroke st = s.doc.strokeByUid(strokeSel);
        if (st == null) return;
        divider();
        strokeCard(s, st);
    }

    private void strokeCard(Session s, Doc.DStroke st) {
        title("第 " + (s.doc.strokes.indexOf(st) + 1) + " 条线条", null);
        int k = im.segmented("s-curve", CX, y, CW, 32f, new String[]{"直线（折线）", "曲线（穿过每个点）"}, st.curve ? 1 : 0);
        y += 40f;
        g.text("闭合（首尾连起来，至少 3 个点）", CX, y + 12f, 12, Gfx.INK3);
        boolean cl = im.toggle("s-closed", CX + CW, y + 12f, st.closed);
        y += 30f;
        g.text("画在键上面（默认在键下面，不挡键）", CX, y + 12f, 12, Gfx.INK3);
        boolean fr = im.toggle("s-front", CX + CW, y + 12f, st.front);
        y += 34f;
        float w2 = (FW - 8f) / 2f;
        label("出现");
        String fs = im.field("s-from", FX, y, w2, 30f, Float.isNaN(st.from) ? "" : Im.fmt(st.from), "从", true);
        String ts = im.field("s-to", FX + w2 + 8f, y, w2, 30f, Float.isNaN(st.to) ? "" : Im.fmt(st.to), "到", true);
        y += 38f;
        label("画出/收回");
        float di = im.num("s-in", FX, y, w2, 30f, st.drawIn, "拍");
        float dout = im.num("s-out", FX + w2 + 8f, y, w2, 30f, st.drawOut, "拍");
        y += 38f;
        label("粗细/透明");
        float wd = im.num("s-w", FX, y, w2, 30f, st.width, "");
        float op = im.num("s-op", FX + w2 + 8f, y, w2, 30f, st.opacity, "0–1");
        y += 38f;
        label("颜色");
        String co = im.field("s-color", FX, y, FW, 30f, Doc.colorText(st.color), "#RRGGBB 或 #RRGGBBAA", true);
        y += 38f;
        caption("出现空着 = 一直显示；画出 = 用几拍从第一个点画出来，收回 = 消失前几拍从第一个点收回去（有「到」才收回）。"
            + "粗细默认 4、最多 " + Im.fmt(Stroke.MAX_WIDTH) + "；颜色默认 #EAF2FF。");
        Integer col = co != null ? Doc.parseColor(co) : null;
        if (co != null && col == null) im.toast("颜色写成 #RRGGBB 或 #RRGGBBAA");
        if (k != (st.curve ? 1 : 0) || cl != st.closed || fr != st.front || fs != null || ts != null || !Float.isNaN(di)
            || !Float.isNaN(dout) || !Float.isNaN(wd) || !Float.isNaN(op) || col != null) {
            s.mark();
            st.curve = k == 1;
            st.closed = cl;
            st.front = fr;
            if (fs != null) st.from = parseOrNaN(fs);
            if (ts != null) st.to = parseOrNaN(ts);
            if (st.from > st.to) { // the game swaps them too
                float t = st.from;
                st.from = st.to;
                st.to = t;
            }
            if (Float.isFinite(di)) st.drawIn = Math.max(0f, di);
            if (Float.isFinite(dout)) st.drawOut = Math.max(0f, dout);
            if (Float.isFinite(wd) && wd > 0f) st.width = Math.min(wd, Stroke.MAX_WIDTH);
            if (Float.isFinite(op)) st.opacity = clamp(op, 0f, 1f);
            if (col != null) st.color = col;
            s.changed(false);
        }
        strokePoints(s, st);
        divider();
        title("运动", null);
        evGroup("s-opev", "透明 Opacity", Gfx.INK2, st.opacityEv, null, CH_VEIL, st.opacity);
        caption("事件之外用上面的透明度。");
        divider();
        glitch(s, st.glitches, "sk", st.from, st.to, "只影响画面。");
        divider();
        distortion(s, st.distortions, "sk", st.from, st.to, 1f,
            "线条平常不扭曲；1 = 像漩涡一样晃，2 = 两倍，最多 " + Im.fmt(NoteDistortion.MAX_INTENSITY) + "。");
        divider();
        if (im.button("s-copy", CX, y, 70f, 34f, "复制", Im.NORMAL)) {
            s.mark();
            Doc.DStroke c = st.copy(false);
            for (int i = 0; i < c.pointCount(); i++) {
                c.points[2 * i] += 60f;
                c.points[2 * i + 1] -= 60f;
            }
            s.doc.strokes.add(c);
            strokeSel = c.uid;
            s.changed(false);
        }
        if (im.button("s-del", CX + CW - 70f, y, 70f, 34f, "删除", Im.DANGER)) {
            s.mark();
            s.doc.strokes.remove(st);
            strokeSel = 0;
            s.changed(false);
        }
        y += 46f;
    }

    /** A line's points in drawing order, 2 to {@link Stroke#MAX_POINTS}; a new one goes on past the last. */
    private void strokePoints(Session s, Doc.DStroke st) {
        int n = st.pointCount();
        float w2 = (FW - 42f) / 2f;
        for (int i = 0; i < n; i++) {
            label("点 " + (i + 1));
            float px = im.num(new Im.Pair("s-px", i), FX, y, w2, 30f, st.points[2 * i], "x");
            float py = im.num(new Im.Pair("s-py", i), FX + w2 + 8f, y, w2, 30f, st.points[2 * i + 1], "y");
            boolean del = n > 2
                && im.button(new Im.Pair("s-pdel", i), FX + FW - 28f, y + 3f, 26f, 24f, "×", Im.GHOST, 14);
            y += 38f;
            if (Float.isNaN(px) && Float.isNaN(py) && !del) continue;
            s.mark();
            if (del) st.removePoint(i);
            else {
                if (Float.isFinite(px)) st.points[2 * i] = clamp(px, -Stroke.MAX_REACH, Stroke.MAX_REACH);
                if (Float.isFinite(py)) st.points[2 * i + 1] = clamp(py, -Stroke.MAX_REACH, Stroke.MAX_REACH);
            }
            s.changed(false);
            return;
        }
        if (n < Stroke.MAX_POINTS && im.button("s-padd", CX, y, 160f, 30f, "+ 加一个点", Im.GHOST, 12)) {
            s.mark();
            st.addPoint(n, n > 0 ? st.points[2 * n - 2] + 120f : 0f, n > 0 ? st.points[2 * n - 1] : 600f);
            s.changed(false);
        }
        y += 38f;
        caption("x 从屏幕中线量（左负右正），y 按 1200 高的画面从下往上量（和悬浮键一样）；按顺序连起来，2 到 "
            + Stroke.MAX_POINTS + " 个点。");
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
