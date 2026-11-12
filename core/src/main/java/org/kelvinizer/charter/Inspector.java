package org.kelvinizer.charter;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Cursor;
import org.kelvinizer.elements.Chart7;
import org.kelvinizer.elements.Fn;
import org.kelvinizer.elements.Group7;
import org.kelvinizer.elements.Key7;
import org.kelvinizer.elements.KeyKind;
import org.kelvinizer.elements.Lane7;
import org.kelvinizer.elements.NoteDistortion;
import org.kelvinizer.elements.NoteGlitch;
import org.kelvinizer.elements.NoteTrail;
import org.kelvinizer.elements.Seg;
import org.kelvinizer.elements.Volume7;
import org.kelvinizer.library.Difficulty;

import java.util.ArrayList;
import java.util.List;

/**
 * 右边的属性面板（谱面格式 v7）：「谱面」（歌曲信息、难度、变速、检查）、「音符」（类型、天面地面、轨道、判定拍、长度、位置 h 和长度 H）、
 * 「轨道」（每条轨道一个物件：名字、出现消失、u v a S s F f）、「镜头」（位置 P）、「幕布」（漩涡和水帘：形状、大小 V、中心 p）。
 * 每个函数一行：没有分段时是一个数，有分段时显示播放头的值，点开改每一段。
 *
 * <p>The inspector on the right, for chart format v7 (Kelvin 2026-11-09 18:44, on the old layout as she asked): five
 * tabs, 谱面 (song texts, difficulty, tempo map, offset, checks), 音符 (kind, face, lane or spanned lanes, judge beat,
 * length, the functions h and H), 轨道 (every lane an object: name, 出现 / 消失, its functions u v a S s F f), 镜头
 * (the camera position P) and 幕布 (veils and curtains: kind, shape, 出现 / 消失, size V and centre p). Every function
 * is one row ({@link #fnRow}): its constant while it has no segments, else its value at the playhead (a value typed
 * there puts a point at the playhead, {@link Doc#setAt}); its segments open under it (start and end beat, start and
 * end value, ease). The open function is also the curve window's. Every edit goes through the session's undo. The
 * panel scrolls with the wheel, its scrollbar or a drag on empty space.
 */
final class Inspector {
    static final int CHART = 0, NOTE = 1, LANE = 2, CAMERA = 3, VOLUME = 4, SCENE = 5;
    private static final String[] TABS = {"谱面", "音符", "轨道", "镜头", "幕布", "场景"};
    private static final String[] FACES = {"天面", "底面"};
    private static final float TOP = 102f;
    private static final float X = EditorScreen.TR, CX = X + 19f, CW = Gfx.W - 18f - CX, FX = X + 101f,
        FW = Gfx.W - 18f - FX;
    /** A key's shapes; each but Drag also comes as a mine (the 水雷 switch). */
    private static final String[] SHAPES = {"水滴", "水流", "Drag", "浪花", "潮汐"};
    /** A lane's functions in {@link Doc#fns} order: names and the curve window's tables. */
    private static final String[] LANE_FN = {"下落 x", "下落 y", "下落 z", "原点 x", "原点 y", "原点 z", "宽", "长",
        "天面高度 S", "天面延申 s", "底面高度 F", "底面延申 f", "透明度", "法向 x", "法向 y", "法向 z", "水雷", "轨道本身"},
        LANE_TABLE = {"下落向量", "下落向量", "下落向量", "原点", "原点", "原点", "底面", "底面", "高度", "延申", "高度", "延申",
            "透明度", "法向量", "法向量", "法向量", "水雷", "轨道本身"};
    private static final String[] VOL_FN = {"宽", "高", "深", "x", "y", "z", "x", "y", "z", "模糊"};
    /**
     * 下落向量的快捷方式 (Kelvin 2026-11-09 00:08「创建一些法向量快捷方式」): the slanted four 45° off straight down; the
     * last one turns it round. Only the direction counts.
     */
    static final String[] FALL_NAMES = {"朝下", "朝你", "朝上", "朝左", "朝右", "朝里", "左下", "右下", "下偏你",
        "下偏里", "反过来"};
    static final float[][] FALLS = {{0f, -1f, 0f}, {0f, 0f, -1f}, {0f, 1f, 0f}, {-1f, 0f, 0f}, {1f, 0f, 0f},
        {0f, 0f, 1f}, {-1f, -1f, 0f}, {1f, -1f, 0f}, {0f, -1f, -1f}, {0f, -1f, 1f}};
    /** Start distance of 先快后慢: 1.44 × the default fall (1555.56). */
    private static final float FAST_SLOW = Doc.SPAWN * 1400f / 972f;
    /** 远处快落 (Kelvin 2026-11-09 00:25): 5000 away, 2500 a beat. */
    private static final float FAR_FALL = 5000f;
    private static final String[] FALLS_QUICK = {"正常下落：2 拍内 " + dist(Doc.SPAWN) + " → 0",
        "快速下落：1 拍内 " + dist(Doc.SPAWN) + " → 0", "慢速下落：4 拍内 " + dist(Doc.SPAWN) + " → 0",
        "先快后慢：2 拍内 " + dist(FAST_SLOW) + " → 0（缓动 0.5）", "先慢后快：2 拍内 " + dist(Doc.SPAWN) + " → 0（缓动 2）",
        "无接近：判定时直接出现在面上", "远处快落：2 拍内 " + dist(FAR_FALL) + " → 0"};
    /** Where 更多下落 lists the saved curves, after the built-in ones. */
    private static final String MINE = "我的曲线：";

    /**
     * How a lane comes in (Kelvin 2026-11-08 21:44「新建轨道就让轨道从现在播放头的位置开始出现」): at once or 长出来 (its width
     * from 0 over {@link #appearBeats}). A card's 出现 row shows its lane's own way; the last one picked is how 新建轨道
     * and a first 出现 make it. 消失 the same way: at once or 收起 (its width to 0); 打完它的键就消失 is always at once (Kelvin
     * 2026-11-09 20:16「打完键就消失就是直接消失，没有淡出的那种」).
     */
    static final int AT_ONCE = 0, GRADUAL = 1;
    private static final String[] APPEAR_NAMES = {"直接出现", "长出来"}, VANISH_NAMES = {"直接消失", "收起"};
    int appearHow = AT_ONCE, vanishHow = AT_ONCE;
    float appearBeats = 1f, vanishBeats = 0.25f;
    /** What {@link #wayRow} got this frame: the way picked (−1 none) and the beats typed (NaN none). */
    private int pickedWay;
    private float typedBeats;

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
    /** The function whose segments are open (one at a time), or null. */
    private String open;
    /** The beat the function rows being drawn show as 0: a key's judge beat, else 0. */
    private float zero;
    private final ArrayList<Long> taps = new ArrayList<>();
    private float tapBpm;
    /** Layout cursor: screen y of the next row. */
    private float y;

    Inspector(EditorScreen ed) {
        this.ed = ed;
        this.g = ed.g;
        this.im = ed.im;
    }

    /** @return the function whose segments are open, or null (the curve window edits it) */
    String openId() {
        return open;
    }

    /** Opens function {@code id}'s segments (a click on its bar in the timeline or the curve window). */
    void openFn(String id) {
        open = id;
        if (id == null) return;
        if (id.startsWith("l")) tab = LANE;
        else if (id.startsWith("k-")) tab = NOTE;
        else if (id.startsWith("cam")) tab = CAMERA;
        else if (id.startsWith("v")) tab = VOLUME;
        else if (id.startsWith("g")) tab = SCENE;
    }

    void draw() {
        ed.curves.clearOffers();
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
        zero = 0f;
        if (tab == CHART) chartTab();
        else if (tab == NOTE) noteTab();
        else if (tab == LANE) laneTab();
        else if (tab == CAMERA) cameraTab();
        else if (tab == VOLUME) volumeTab();
        else sceneTab();
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

    /** @return a distance for a label: at most 2 decimals, no trailing zeros (1080, 1555.56). */
    private static String dist(float v) {
        return String.format(java.util.Locale.ROOT, "%.2f", v).replaceAll("\\.?0+$", "");
    }

    // ================================================================== function rows

    /**
     * One function's row: its name; its constant while it has no segments, else its value at the playhead (a value
     * typed there puts a point at the playhead); and「+ 分段」or「N 段」to open its segments. Offered to the curve
     * window as {@code id} of {@code table}.
     */
    private void fnRow(String id, String name, Fn f, String table) {
        Session s = ed.s;
        ed.curves.offer(id, name, table, f, zero);
        boolean segs = !f.segs.isEmpty(), isOpen = segs && id.equals(open);
        float t = ed.snap(ed.beat), bw = 70f, fw = FW - bw - 6f;
        label(name);
        im.slash(Float.NaN, null, false);
        boolean kf = segs && Keyframes.at(f, t);                        // a keyframe at the playhead (SPEC §3)
        float v = im.num(id, FX, y, fw, 30f, segs ? f.at(t) : f.constant, segs ? (kf ? "◆ 播放头" : "播放头") : "",
            kf ? Gfx.SYNC : null);
        if (kf) g.text("◆ 播放头", FX + fw - 10f, y + 15f, 11, Gfx.SYNC, 2);
        if (!Float.isNaN(v)) {
            s.mark();
            Doc.setAt(f, t, v);
            s.changed(false);
        }
        String bl = segs ? f.segs.size() + " 段 " + (isOpen ? "▴" : "▾") : "+ 分段";
        if (im.button(id + "-open", FX + fw + 6f, y, bw, 30f, bl, isOpen ? Im.ON : Im.GHOST, 12)) {
            if (!segs) {
                s.mark();
                f.segs.add(new Seg(t, t + 1f, f.constant, f.constant, 1f));
                s.changed(false);
                open = id;
            } else open = isOpen ? null : id;
        }
        y += 38f;
        if (isOpen || !segs && id.equals(open) && !f.segs.isEmpty()) segList(id, f);
    }

    /** Function {@code id}'s segments, one row each, and what to do with them. */
    private void segList(String id, Fn f) {
        Session s = ed.s;
        float t = ed.snap(ed.beat);
        g.text("开始拍 → 结束拍", CX + 30f, y + 7f, 11, Gfx.INK3);
        g.text("开始值 → 结束值", CX + 184f, y + 7f, 11, Gfx.INK3);
        g.text("缓动", CX + 344f, y + 7f, 11, Gfx.INK3);
        y += 18f;
        int remove = -1;
        for (int i = 0; i < f.segs.size(); i++) {
            Seg sg = f.segs.get(i);
            boolean now = sg.ti <= t + 1e-4f && t <= Math.max(sg.ti, sg.tf) + 1e-4f;
            g.box(CX, y, CW, 34f, 6f, now ? Gfx.ITEM_ON : Gfx.ITEM, now ? Gfx.SEL_LINE : Gfx.ITEM_LINE);
            g.text("#" + (i + 1), CX + 6f, y + 17f, 11, Gfx.INK3);
            float a = segNum(new Im.Pair(id + "-ti", i), CX + 28f, 64f, sg.ti - zero, true);
            g.text("→", CX + 97f, y + 17f, 12, Gfx.INK3, 1);
            float b = segNum(new Im.Pair(id + "-tf", i), CX + 106f, 64f, sg.tf - zero, true);
            float c = segNum(new Im.Pair(id + "-xi", i), CX + 180f, 72f, sg.xi, false);
            g.text("→", CX + 259f, y + 17f, 12, Gfx.INK3, 1);
            float d = segNum(new Im.Pair(id + "-xf", i), CX + 266f, 72f, sg.xf, false);
            float e = segNum(new Im.Pair(id + "-e", i), CX + 344f, 50f, sg.e, false);
            if (im.button(new Im.Pair(id + "-x", i), CX + CW - 28f, y + 5f, 24f, 24f, "×", Im.GHOST, 14)) remove = i;
            if (!Float.isNaN(e) && !(e > 0f)) {
                im.toast("缓动要大于 0（1 = 匀速，2 = 先慢后快，0.5 = 先快后慢）");
                e = Float.NaN;
            }
            if (!Float.isNaN(a) || !Float.isNaN(b) || !Float.isNaN(c) || !Float.isNaN(d) || !Float.isNaN(e)) {
                s.mark();
                if (!Float.isNaN(a)) sg.ti = a + zero;
                if (!Float.isNaN(b)) sg.tf = b + zero;
                if (!Float.isNaN(c)) sg.xi = c;
                if (!Float.isNaN(d)) sg.xf = d;
                if (!Float.isNaN(e)) sg.e = e;
                f.sort();
                s.changed(false);
            }
            y += 38f;
        }
        if (remove >= 0) {
            s.mark();
            Seg r = f.segs.remove(remove);
            if (f.segs.isEmpty()) {
                f.constant = r.xf;
                open = null;
            }
            s.changed(false);
        }
        float bw = (CW - 16f) / 3f;
        if (im.button(id + "-add", CX, y, bw, 28f, "+ 在播放头加一段", Im.NORMAL, 12)) {
            s.mark();
            float v = f.at(t);
            f.segs.add(new Seg(t, t + 1f, v, v, 1f));
            f.sort();
            s.changed(false);
        }
        if (im.button(id + "-cv", CX + bw + 8f, y, bw, 28f, "曲线 ›", Im.NORMAL, 12)) ed.curves.show(id);
        if (im.button(id + "-clr", CX + 2f * (bw + 8f), y, bw, 28f, "去掉分段", Im.GHOST, 12)) {
            s.mark();
            f.constant = f.at(t);
            f.segs.clear();
            s.changed(false);
            open = null;
        }
        y += 36f;
        caption(zero != 0f ? "拍数从判定拍算起（判定拍 = 0，前面是负数）。段与段之间、最后一段以后保持上一段的结束值；/ = 播放头。"
            : "段与段之间、最后一段以后保持上一段的结束值；第一段以前是它的开始值。结束拍不大于开始拍 = 在开始拍直接跳过去。/ = 播放头。");
    }

    /** A number of {@link #segList}: a beat ({@code /} = the playhead) or a value. @return the typed one, else NaN */
    private float segNum(Object id, float x, float w, float v, boolean beat) {
        float now = ed.snap(ed.beat) - zero;
        if (beat) im.slash(now, Im.beatHint(now, zero != 0f ? "距判定 " : ""), true);
        else im.slash(Float.NaN, null, false);
        return im.num(id, x, y + 3f, w, 28f, v, "");
    }

    // ================================================================== 场景

    /**
     * 场景 (Kelvin 2026-11-11 16:39「有点像CAD」, 17:32「可以」): the picked group's card (组, chart 2.1): its name, 进组 and
     * 解组, its centre, turn, size and opacity (each a function, keyed like any other); without one, how to make one.
     */
    private void sceneTab() {
        Session s = ed.s;
        Doc d = s.doc;
        Group7 gr = d.group(s.groupSel);
        Group7 inside = d.group(s.groupIn);
        if (inside != null) {
            g.box(CX, y, CW, 32f, 6f, Gfx.ITEM, Gfx.SYNC);
            g.text(g.fit("已进组「" + Doc.groupLabel(inside) + "」：点一下是里面单条轨道", 12, CW - 96f), CX + 10f, y + 16f, 12,
                Gfx.SYNC);
            if (im.button("g-out", CX + CW - 80f, y + 3f, 76f, 26f, "出组 Esc", Im.GHOST, 12)) ed.leaveGroup();
            y += 42f;
        }
        if (ed.scene.arrayOn) {
            arrayCard();
            return;
        }
        int picks = s.pickLanes.size() + s.pickGroups.size();
        Lane7 lane = gr == null ? d.lane(s.laneSel) : null;
        if (lane != null) {                                             // a single box is a lane: its own card
            if (picks > 1) {
                if (im.button("g-make3", CX, y, CW, 30f, "成组（Ctrl+G）：选中的 " + picks + " 个", Im.NORMAL, 12)) {
                    ed.makeGroup();
                }
                y += 40f;
            }
            laneCard(s, d, lane);
            return;
        }
        if (gr == null) {
            title("组", d.groups.size() + " 个");
            caption("在预览里点组里的轨道就选中整个组；双击进组，再点才是单条轨道。按住 Shift 点几条轨道或几个组，再按 Ctrl+G 成组。"
                + "左边「方块」（B）在网格上点一下放一个；选中后拖箭头移动、拖黄圈旋转、拖白块缩放；「阵列」排一行或一圈，"
                + "「镜像」左右对称复制一份，Ctrl+D 复制一个。");
            if (im.button("g-make", CX, y, CW, 30f, picks > 0 ? "成组（Ctrl+G）：选中的 " + picks + " 个" : "成组（Ctrl+G）",
                picks > 0 ? Im.NORMAL : Im.GHOST, 12) && picks > 0) ed.makeGroup();
            y += 40f;
            models();
            return;
        }
        Group7 up = d.group(gr.p);
        g.box(CX, y, CW, 34f, 6f, Gfx.ITEM_ON, Gfx.GND);
        g.text(g.fit(Doc.groupLabel(gr), 14, 150f), CX + 12f, y + 17f, 14, Gfx.GND);
        g.text(d.laneCount(gr.id) + " 条轨道" + (up != null ? " · 在「" + Doc.groupLabel(up) + "」里" : ""), CX + CW - 12f,
            y + 17f, 12, Gfx.INK3, 2);
        y += 42f;
        String nv = text(new Im.Pair("g-name", gr.id), "名字", Doc.groupLabel(gr));
        if (nv != null && !nv.trim().isEmpty() && !nv.trim().equals(Doc.groupLabel(gr))) {
            s.mark();
            gr.name = nv.trim();
            s.changed(false);
        }
        float bw = CW - 94f;
        if (im.button("g-in", CX, y, bw, 30f, s.groupIn == gr.id ? "已进组（Esc 出组）" : "进组，选单条轨道（也可以双击）",
            Im.NORMAL, 12)) ed.enterGroup(gr.id);
        if (im.button("g-un", CX + bw + 8f, y, 86f, 30f, "解组", Im.GHOST, 12)) ed.ungroup(gr.id);
        y += 40f;
        if (im.button("g-save", CX, y, CW, 30f, "保存为模型（所有谱都能导入）", Im.NORMAL, 12)) ed.scene.saveModel(gr);
        y += 40f;
        if (picks > 1) {
            if (im.button("g-make2", CX, y, CW, 30f, "成组（Ctrl+G）：选中的 " + picks + " 个", Im.NORMAL, 12)) ed.makeGroup();
            y += 40f;
        }
        divider();
        String id = "g" + gr.id + "-";
        title("位置（组的中心点）", "◆ = 播放头这一拍有关键帧");
        fnRow(id + 0, "位置 x", gr.P.x, "位置");
        fnRow(id + 1, "位置 y", gr.P.y, "位置");
        fnRow(id + 2, "位置 z", gr.P.z, "位置");
        title("旋转（度，绕中心点转）", null);
        fnRow(id + 3, "绕 x", gr.R.x, "旋转");
        fnRow(id + 4, "绕 y", gr.R.y, "旋转");
        fnRow(id + 5, "绕 z", gr.R.z, "旋转");
        title("缩放和透明度", null);
        fnRow(id + 6, "缩放", gr.K, "缩放");
        fnRow(id + 7, "透明度", gr.o, "透明度");
        caption("组的位置、旋转、缩放、透明度加在里面每条轨道上；里面的轨道（和小组）自己的动作照旧，原点按组的坐标算。轨道上的键跟着组一起动。"
            + "先转 z，再转 x，最后转 y。");
        models();
    }

    /** 导入模型 (场景 page): the saved models; the one picked goes in as a new group. */
    private void models() {
        divider();
        title("模型", "在 Charter 文件夹的 models 里");
        List<String> ms = ed.s.lib.models();
        String[] items = ms.isEmpty() ? new String[]{"（还没有保存的模型）"} : ms.toArray(new String[0]);
        int r = im.dropdown("g-import", CX, y, CW, 30f, "导入模型", items, -1);
        if (r >= 0 && r < ms.size()) ed.scene.importModel(ms.get(r));
        y += 40f;
        caption("选中一个组按「保存为模型」存下来，所有谱都能导入；导入的是新的一组（编号重新排），放在现在所在的组（或谱面）的原点。");
    }

    /**
     * 阵列 (场景 page, Kelvin 2026-11-11 17:32): what is picked copied in a row or a ring; the preview shows the copies as
     * amber ghosts until 确定.
     */
    private void arrayCard() {
        SceneTools st = ed.scene;
        Doc.Items it = st.picks(false);
        g.box(CX, y, CW, 34f, 6f, Gfx.ITEM_ON, Gfx.BPM);
        g.text("阵列", CX + 12f, y + 17f, 14, Gfx.BPM);
        g.text(it == null ? "先选中轨道或组" : "选中：" + g.fit(st.pickName(it), 12, CW - 120f), CX + CW - 12f, y + 17f, 12,
            Gfx.INK3, 2);
        y += 42f;
        label("方式");
        st.circle = im.segmented("a-way", FX, y, FW, 30f, new String[]{"一行", "一圈"}, st.circle ? 1 : 0) == 1;
        y += 38f;
        float v = num("a-n", "数量", st.count, "包括原来这个");
        if (!Float.isNaN(v)) st.count = Math.max(2, Math.min(500, Math.round(v)));
        if (!st.circle) {
            label("每个隔");
            float w3 = (FW - 12f) / 3f;
            float[] gap = {st.gapX, st.gapY, st.gapZ};
            String[] ax = {"x", "y", "z"};
            for (int i = 0; i < 3; i++) {
                v = im.num("a-gap" + i, FX + i * (w3 + 6f), y, w3, 30f, gap[i], ax[i]);
                if (!Float.isNaN(v)) gap[i] = v;
            }
            st.gapX = gap[0];
            st.gapY = gap[1];
            st.gapZ = gap[2];
            y += 38f;
        } else {
            v = num("a-deg", "一共转", st.total, Math.abs(st.total) >= 360f ? "度（整圈）" : "度（两头都有）");
            if (!Float.isNaN(v)) st.total = v;
            label("绕");
            st.axis = im.dropdown("a-axis", FX, y, FW, 30f, SceneTools.AXIS_LABELS[st.axis], SceneTools.AXIS_LABELS,
                st.axis);
            y += 38f;
            String[] cs = it != null ? st.centreLabels(it) : new String[]{"原点 (0, 0, 0)"};
            st.centre = Math.max(0, Math.min(cs.length - 1, st.centre));
            label("中心");
            st.centre = im.dropdown("a-centre", FX, y, FW, 30f, g.fit(cs[st.centre], 13, FW - 30f), cs, st.centre);
            y += 38f;
        }
        String[] into = {"新组「" + st.groupName(it) + "」", "不放进组"};
        label("放进");
        st.into = im.dropdown("a-into", FX, y, FW, 30f, into[st.into], into, st.into);
        y += 38f;
        float bw = (CW - 8f) * 0.5f;
        if (im.button("a-ok", CX, y, bw, 32f, "确定（Enter）", it != null ? Im.PRIMARY : Im.GHOST, 13)) st.applyArray();
        if (im.button("a-no", CX + bw + 8f, y, bw, 32f, "取消（Esc）", Im.NORMAL, 13)) st.arrayOn = false;
        y += 44f;
        caption("「一圈」从选中的开始，围着中心排；「一行」填数量和每个隔多远（x、y、z）。预览里橙色虚线是确定后会放的位置，改数字马上跟着变。"
            + "复制出来的是一样的轨道（大小、出现消失、动作都一样），不带键。");
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
        y += g.wrap(c + "也可以在时间轴的音频条上拖预览框的两条边（按住 Shift 不吸附）。", CX, y, set ? CW - 88f : CW, 12, Gfx.INK3)
            + 8f;
    }

    private void tempo(Session s, Doc d) {
        title("节奏 · 变速", d.bpms.size() + " 段");
        int cur = 0;
        for (int i = 0; i < d.bpms.size(); i++) if (d.bpms.get(i).beat <= ed.beat) cur = i;
        int remove = -1;
        for (int i = 0; i < d.bpms.size(); i++) {
            Doc.Tempo sg = d.bpms.get(i);
            boolean on = i == cur && d.bpms.size() > 1;
            g.box(CX, y, CW, 38f, 6f, on ? Gfx.ITEM_ON : Gfx.ITEM, on ? Gfx.SEL_LINE : Gfx.ITEM_LINE);
            g.text("#" + (i + 1), CX + 12f, y + 19f, 12, Gfx.INK3);
            g.text("从拍", CX + 40f, y + 19f, 12, Gfx.INK2);
            if (i == 0) {
                g.box(CX + 74f, y + 5f, 86f, 28f, 5f, Gfx.FIELD, Gfx.BTN_LINE);
                g.text("0", CX + 84f, y + 19f, 13, Gfx.INK3);
            } else {
                im.slash(ed.snap(ed.beat), Im.beatHint(ed.snap(ed.beat), ""), true);
                float b = im.num(new Im.Pair("seg-b", i), CX + 74f, y + 5f, 86f, 28f, sg.beat, "");
                if (!Float.isNaN(b)) setTempoBeat(s, d, sg, b);
            }
            g.text("BPM", CX + 172f, y + 19f, 12, Gfx.INK2);
            float bpm = im.num(new Im.Pair("seg-v", i), CX + 206f, y + 5f, 84f, 28f, sg.bpm, "");
            if (!Float.isNaN(bpm)) {
                if (bpm > 0f && bpm < 10000f) {
                    s.mark();
                    sg.bpm = bpm;
                    s.changed(false);
                } else im.toast("BPM 要大于 0");
            }
            if (i > 0 && im.button(new Im.Pair("seg-x", i), CX + CW - 32f, y + 7f, 24f, 24f, "×", Im.GHOST, 14)) {
                remove = i;
            }
            y += 44f;
        }
        if (remove >= 0) {
            s.mark();
            d.bpms.remove(remove);
            s.changed(false);
        }
        float bw = CW - 112f;
        if (im.button("seg-add", CX, y, bw, 32f, "+ 在当前拍变速", Im.NORMAL, 13)) addTempo(s, d);
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

    private void setTempoBeat(Session s, Doc d, Doc.Tempo sg, float b) {
        if (!(b > 0f)) {
            im.toast("变速的拍要大于 0");
            return;
        }
        for (Doc.Tempo o : d.bpms) {
            if (o != sg && Math.abs(o.beat - b) < 1e-4f) {
                im.toast("这一拍已经有变速了");
                return;
            }
        }
        s.mark();
        sg.beat = b;
        d.sortBpms();
        s.changed(false);
    }

    private void addTempo(Session s, Doc d) {
        float b = ed.snap(ed.beat);
        if (!(b > 0f)) {
            im.toast("先把播放头移到要变速的地方（第 0 拍之后）");
            return;
        }
        for (Doc.Tempo o : d.bpms) {
            if (Math.abs(o.beat - b) < 1e-4f) {
                im.toast("这一拍已经有变速了");
                return;
            }
        }
        s.mark();
        d.bpms.add(new Doc.Tempo(b, d.bpmAt(b)));
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

    static int shapeOf(KeyKind k) {
        switch (k) {
            case TAP:
            case MINE: return 0;
            case STREAM:
            case STREAM_MINE: return 1;
            case DRAG: return 2;
            case WAVE:
            case WAVE_MINE: return 3;
            default: return 4;
        }
    }

    static KeyKind kindOf(int shape, boolean mine) {
        switch (shape) {
            case 0: return mine ? KeyKind.MINE : KeyKind.TAP;
            case 1: return mine ? KeyKind.STREAM_MINE : KeyKind.STREAM;
            case 2: return KeyKind.DRAG;
            case 3: return mine ? KeyKind.WAVE_MINE : KeyKind.WAVE;
            default: return mine ? KeyKind.TIDE_MINE : KeyKind.TIDE;
        }
    }

    /** @return what kind {@code k} is called (Kelvin 2026-11-09 18:44: 轨道水雷 for a stream mine) */
    static String kindName(KeyKind k) {
        switch (k) {
            case TAP: return "水滴";
            case DRAG: return "Drag";
            case MINE: return "水雷";
            case STREAM: return "水流";
            case STREAM_MINE: return "轨道水雷";
            case WAVE: return "浪花";
            case WAVE_MINE: return "浪花水雷";
            case TIDE: return "潮汐";
            default: return "潮汐水雷";
        }
    }

    private void noteTab() {
        Session s = ed.s;
        ArrayList<Doc.DKey> sel = s.selected();
        if (sel.isEmpty()) {
            noKeys(s);
            return;
        }
        if (sel.size() > 1) {
            manyKeys(s, sel);
            return;
        }
        Doc d = s.doc;
        Doc.DKey dk = sel.get(0);
        Key7 k = dk.k;
        title("类型", kindName(k.kind));
        int sh = shapeOf(k.kind);
        boolean mine = k.kind.mine(), drag = k.kind == KeyKind.DRAG;
        int ns = im.segmented("n-kind", CX, y, CW - 112f, 32f, SHAPES, sh);
        g.text("水雷", CX + CW - 58f, y + 16f, 13, drag ? Gfx.INK3 : Gfx.INK2, 2);
        boolean nm = !drag && im.toggle("n-mine", CX + CW, y + 16f, mine);
        if (ns != sh || nm != mine && !drag) {
            s.mark();
            Doc.setKind(k, kindOf(ns, ns != 2 && nm), 1f);
            d.sortKeys();
            s.changed(false);
        }
        y += 40f;
        int fi = k.sky ? 0 : 1;
        int nf = im.segmented("n-face", CX, y, 148f, 32f, FACES, fi);
        if (nf != fi) {
            s.mark();
            k.sky = nf == 0;
            s.changed(false);
        }
        // 假键 (Kelvin 2026-11-09 20:37「假键的逻辑倒是可以保留」): drawn only, as before v7; not the wave and tide mines
        boolean canFake = k.kind != KeyKind.WAVE_MINE && k.kind != KeyKind.TIDE_MINE;
        if (canFake) {
            g.text("假键", CX + CW - 58f, y + 16f, 13, Gfx.INK2, 2);
            boolean nfk = im.toggle("n-fake", CX + CW, y + 16f, k.fake);
            if (nfk != k.fake) {
                s.mark();
                for (Doc.DKey o : sel) o.k.fake = nfk && o.k.kind != KeyKind.WAVE_MINE && o.k.kind != KeyKind.TIDE_MINE;
                s.changed(false);
            }
        }
        int sync = k.fake ? 0 : syncSize(d, dk.judge());
        if (sync > 1) {
            g.rect(CX + 162f, y + 11f, 10f, 10f, Gfx.SYNC);
            g.text("同拍 " + sync + " 键（sync）", CX + 178f, y + 16f, 12, Gfx.SYNC);
        }
        y += 40f;
        if (k.fake) caption("假键：只画，不判定、不计分、不算 sync，到判定拍（长的到结束）就不画。下面的拍数是歌里的拍。");
        if (k.kind.spans()) spanRow(s, d, k);
        else laneRow(s, d, sel, k);
        divider();
        title("时间", null);
        float j = dk.judge();
        float nj = num("n-j", "判定拍", j, "拍");
        if (!Float.isNaN(nj) && nj != j) {
            s.mark();
            Doc.setJudge(k, nj);
            d.sortKeys();
            s.changed(false);
        }
        if (k.kind.hasHeight()) {
            float len = Doc.length(k), tail = ed.snap(ed.beat) - j;
            im.slash(tail, "/ = 到播放头为止，长 " + Im.fmt(tail) + " 拍", true);
            float nl = num("n-len", "长度", len, "拍");
            if (!Float.isNaN(nl) && nl != len) {
                if (nl < 0f) im.toast("长度不能是负数");
                else {
                    s.mark();
                    Doc.setLength(k, nl);
                    s.changed(false);
                }
            }
            caption(k.kind == KeyKind.STREAM_MINE || k.kind == KeyKind.TIDE_MINE
                ? "长度 = 封锁多久（物件高度 H 从 540·长度 收到 0）。H 可以在下面改成别的样子。"
                : "长度 = 按住多久（物件高度 H 从 540·长度 收到 0，判定拍时满长）。H 可以在下面改成别的样子。");
        }
        divider();
        title("位置 h（离" + (k.sky ? "天面" : "底面") + "多远）", "最后一段的结束拍 = 判定拍");
        zero = Float.isFinite(j) && !k.fake ? j : 0f;                // 假键: the song's beats, as the old 移动
        fnRow("k-h", "h", k.h, "位置");
        fallButtons(s, sel);
        if (k.kind.hasHeight()) {
            divider();
            title("物件高度 H", null);
            fnRow("k-H", "H", k.H, "长度");
        }
        divider();
        title("透明度", "1 不透明，0 看不见；再乘上轨道的透明度");
        fnRow("k-o", "透明度", k.o, "透明度");
        zero = 0f;
        divider();
        float j0 = Float.isFinite(j) ? j : ed.snap(ed.beat);
        effects(s, "k", k.trails, k.glitches, k.distortions, true, j0 - 2f, j0);
        divider();
        actions();
    }

    /** @return how many keys are judged at {@code beat} (sync) */
    private static int syncSize(Doc d, float beat) {
        if (!Float.isFinite(beat)) return 0;
        int n = 0;
        for (Doc.DKey k : d.keys) if (!k.k.fake && Math.abs(k.judge() - beat) < 1e-3f) n++; // 假键 are not sync
        return n;
    }

    /** A key's lane: ‹ its name › (a name or an id typed), stepping through the lanes in order. */
    private void laneRow(Session s, Doc d, List<Doc.DKey> sel, Key7 k) {
        label("轨道");
        if (d.lanes.isEmpty()) {
            g.text("还没有轨道（在「轨道」页新建）", FX + 10f, y + 15f, 13, Gfx.BAD);
            y += 38f;
            return;
        }
        if (im.button("n-lane-l", FX, y, 30f, 30f, "‹", Im.GHOST, 16)) stepLane(s, d, sel, -1);
        Lane7 on = d.lane(k.lane);
        String v = im.field("n-lane", FX + 34f, y, FW - 68f, 30f, on != null ? d.laneLabel(k.lane) : "?" + k.lane,
            on != null && d.names.containsKey(k.lane) ? "编号 " + k.lane : "编号", false);
        if (im.button("n-lane-r", FX + FW - 30f, y, 30f, 30f, "›", Im.GHOST, 16)) stepLane(s, d, sel, 1);
        y += 38f;
        if (v != null) {
            Lane7 l = d.laneCalled(v.trim(), null);
            if (l == null) {
                try {
                    l = d.lane(Integer.parseInt(v.trim()));
                } catch (NumberFormatException ignored) {
                    // not an id either
                }
            }
            if (l == null) im.toast("没有叫「" + v.trim() + "」的轨道");
            else {
                s.mark();
                for (Doc.DKey x : sel) if (!x.k.kind.spans()) x.k.lane = l.id;
                s.changed(false);
            }
        }
        if (on == null) caption("这个键的轨道（编号 " + k.lane + "）不在了，换一条轨道它才会出现。");
    }

    private void stepLane(Session s, Doc d, List<Doc.DKey> sel, int dir) {
        s.mark();
        for (Doc.DKey x : sel) {
            if (x.k.kind.spans()) continue;
            int i = d.laneIndex(x.k.lane);
            int to = i < 0 ? 0 : Math.max(0, Math.min(d.lanes.size() - 1, i + dir));
            x.k.lane = d.lanes.get(to).id;
        }
        s.changed(false);
    }

    /** 跨越的轨道 (浪花、潮汐 and their mines): every lane as a chip, lit when the key spans it; at least one stays. */
    private void spanRow(Session s, Doc d, Key7 k) {
        title("跨越的轨道", k.lanes.length + " 条");
        int n = d.lanes.size();
        if (n == 0) {
            caption("还没有轨道（在「轨道」页新建）。");
            return;
        }
        int rows = Math.max(1, (n + 8) / 9), per = Math.max(1, (n + rows - 1) / rows);
        float cw = CW / per;
        for (int r = 0; r * per < n; r++) {
            g.box(CX, y, CW, 32f, 6f, Gfx.FIELD, Gfx.BTN_LINE);
            for (int i = r * per; i < Math.min(n, (r + 1) * per); i++) {
                int id = d.lanes.get(i).id;
                float x = CX + (i - r * per) * cw;
                boolean on = false;
                for (int l : k.lanes) on |= l == id;
                boolean hv = im.hover(x, y, cw, 32f);
                if (on) g.box(x + 1f, y + 1f, cw - 2f, 30f, 4f, Gfx.SEL, Gfx.GND);
                else if (hv) g.rrect(x + 1f, y + 1f, cw - 2f, 30f, 4f, Gfx.BTN);
                if (hv) im.cursor(Cursor.SystemCursor.Hand);
                g.text(g.fit(ed.laneTag(id), 12, cw - 8f), x + cw * 0.5f, y + 16f, 12, on ? Gfx.INK1 : Gfx.INK2, 1);
                if (im.click(new Im.Pair("n-span", id), x, y, cw, 32f)) {
                    if (on && k.lanes.length == 1) im.toast("至少跨一条轨道");
                    else {
                        s.mark();
                        ArrayList<Integer> keep = new ArrayList<>();
                        for (Lane7 l : d.lanes) {
                            boolean in = false;
                            for (int x2 : k.lanes) in |= x2 == l.id;
                            if (l.id == id ? !on : in) keep.add(l.id);
                        }
                        k.lanes = new int[keep.size()];
                        for (int q = 0; q < keep.size(); q++) k.lanes[q] = keep.get(q);
                        k.lane = k.lanes[0];
                        s.changed(false);
                    }
                }
            }
            y += 36f;
        }
        y += 2f;
    }

    /** 正常下落 (D) and 更多下落: the fall presets and the saved curves, on every selected key; 保存这条下落. */
    private void fallButtons(Session s, List<Doc.DKey> sel) {
        if (im.button("n-fall", CX, y, 128f, 32f, "正常下落  D", Im.NORMAL, 13)) applyFall(s, sel, 0);
        String[] all = fallItems(s);
        int tp = im.dropdown("n-tpl", CX + 136f, y, CW - 136f - (sel.size() == 1 ? 96f : 0f), 32f, "更多下落",
            java.util.Arrays.copyOfRange(all, 1, all.length), -1);
        if (sel.size() == 1 && im.button("n-save", CX + CW - 88f, y, 88f, 32f, "保存曲线", Im.NORMAL, 12)) {
            saveCurve(sel.get(0));
        }
        y += 40f;
        if (tp >= 0) applyFall(s, sel, tp + 1);
    }

    /** @return 更多下落's items: the built-in ones, then the saved curves */
    static String[] fallItems(Session s) {
        List<Library.Curve> cs = s.lib.curves();
        String[] r = java.util.Arrays.copyOf(FALLS_QUICK, FALLS_QUICK.length + cs.size());
        for (int k = 0; k < cs.size(); k++) r[FALLS_QUICK.length + k] = MINE + cs.get(k).name;
        return r;
    }

    /**
     * Gives every key of {@code sel} fall {@code tp} (an index of {@link #fallItems}: a built-in one, else a saved
     * curve) as one undo step; their judge beats stay.
     */
    void applyFall(Session s, List<Doc.DKey> sel, int tp) {
        if (sel.isEmpty()) return;
        List<Library.Curve> cs = s.lib.curves();
        if (tp < 0 || tp >= FALLS_QUICK.length + cs.size()) return;
        s.mark();
        for (Doc.DKey dk : sel) {
            Key7 k = dk.k;
            switch (tp) {
                case 0: Doc.fall(k, 2f, Doc.SPAWN, 1f); break;
                case 1: Doc.fall(k, 1f, Doc.SPAWN, 1f); break;
                case 2: Doc.fall(k, 4f, Doc.SPAWN, 1f); break;
                case 3: Doc.fall(k, 2f, FAST_SLOW, 0.5f); break;
                case 4: Doc.fall(k, 2f, Doc.SPAWN, 2f); break;
                case 5: Doc.fall(k, 0f, 0f, 1f); break;
                case 6: Doc.fall(k, 2f, FAR_FALL, 1f); break;
                default: applyCurve(k, cs.get(tp - FALLS_QUICK.length));
            }
        }
        s.doc.sortKeys();
        s.changed(false);
        String t = tp < FALLS_QUICK.length ? FALLS_QUICK[tp].substring(0, FALLS_QUICK[tp].indexOf('：'))
            : "「" + cs.get(tp - FALLS_QUICK.length).name + "」";
        im.toast("已套用" + t + "（" + sel.size() + " 个键）");
    }

    /** Gives key k saved curve c as its h: beats from its judge beat. */
    private static void applyCurve(Key7 k, Library.Curve c) {
        float j = k.judgeBeat();
        if (!Float.isFinite(j) || c.events.isEmpty()) return;
        k.h.segs.clear();
        for (float[] v : c.events) k.h.segs.add(new Seg(j + v[0], j + v[1], v[2], v[3], v[4] > 0f ? v[4] : 1f));
        k.h.sort();
    }

    /** 保存曲线 (Kelvin 2026-11-09 05:27「保存按键轨迹曲线」): key k's h, from its judge beat, under a name. */
    private void saveCurve(Doc.DKey k) {
        Library lib = ed.s.lib;
        List<Seg> h = new ArrayList<>();
        for (Seg sg : k.k.h.segs) h.add(new Seg(sg.ti, sg.tf, sg.xi, sg.xf, sg.e));
        float zero = k.judge();
        if (h.isEmpty() || !Float.isFinite(zero)) {
            im.toast("这个键的 h 没有分段，没什么可存的");
            return;
        }
        Im.Modal m = new Im.Modal("保存这条曲线", "起个名字。存下后在音符页「更多下落」里套用，所有谱都能用（同名的会被换掉）。",
            new String[]{"保存", "取消"}, (b, t) -> {
                String name = t == null ? "" : t.trim();
                if (b != 0) return;
                if (name.isEmpty()) im.toast("名字不能空着");
                else if (lib.saveCurve(name, h, zero)) im.toast("已保存「" + name + "」：在「更多下落」里");
                else im.toast("保存失败：Charter 文件夹写不进去");
            });
        m.input = "曲线 " + (lib.curves().size() + 1);
        im.open(m);
    }

    private void noKeys(Session s) {
        title("没有选中键", null);
        caption("用左边的工具在画面或时间轴上点一下就能放键；长的几种（水流、轨道水雷、潮汐、潮汐水雷）按住往远处拖出长度。"
            + "用「选择」点键来编辑，在时间轴上拖动可以框选。");
        divider();
        title("新键放在", null);
        int fi = s.placeSky ? 0 : 1;
        int nf = im.segmented("p-face", CX, y, 160f, 32f, FACES, fi);
        if (nf != fi) s.placeSky = nf == 0;
        g.text("Tab 切换", CX + 172f, y + 16f, 12, Gfx.INK3);
        y += 40f;
        caption("新键的位置 h：2 拍内从 " + dist(Doc.SPAWN) + " 落到面上（判定拍 = h 最后一段结束的拍）。");
    }

    private void manyKeys(Session s, ArrayList<Doc.DKey> sel) {
        title("已选 " + sel.size() + " 个键", null);
        int[] c = new int[KeyKind.values().length];
        int sky = 0;
        for (Doc.DKey k : sel) {
            c[k.k.kind.ordinal()]++;
            if (k.k.sky) sky++;
        }
        StringBuilder b = new StringBuilder();
        for (KeyKind kk : KeyKind.values()) {
            if (c[kk.ordinal()] > 0) b.append(b.length() > 0 ? " · " : "").append(kindName(kk)).append(' ')
                .append(c[kk.ordinal()]);
        }
        y += g.wrap(b.toString(), CX, y, CW, 13, Gfx.INK2) + 10f;
        int fi = sky == sel.size() ? 0 : sky == 0 ? 1 : -1;
        int nf = im.segmented("m-face", CX, y, 160f, 32f, FACES, fi);
        if (nf != fi && nf >= 0) {
            s.mark();
            for (Doc.DKey k : sel) k.k.sky = nf == 0;
            s.changed(false);
        }
        int fakes = 0;
        for (Doc.DKey k : sel) if (k.k.fake) fakes++;
        g.text("假键", CX + CW - 58f, y + 16f, 13, Gfx.INK2, 2);
        boolean allFake = fakes == sel.size(), nfk = im.toggle("m-fake", CX + CW, y + 16f, allFake);
        if (nfk != allFake) {                                           // all 假键, or none (the wave / tide mines never)
            s.mark();
            for (Doc.DKey k : sel) k.k.fake = nfk && k.k.kind != KeyKind.WAVE_MINE && k.k.kind != KeyKind.TIDE_MINE;
            s.changed(false);
        }
        y += 40f;
        float sh = num("m-shift", "整体平移", 0f, "拍");
        if (!Float.isNaN(sh) && sh != 0f) {
            s.mark();
            for (Doc.DKey k : sel) Doc.shiftKey(k.k, sh);
            s.doc.sortKeys();
            s.changed(false);
        }
        divider();
        title("位置 h", null);
        fallButtons(s, sel);
        divider();
        actions();
    }

    private void actions() {
        if (im.button("n-copy", CX, y, 70f, 34f, "复制", Im.NORMAL)) ed.copy(false);
        if (im.button("n-mirror", CX + 78f, y, 104f, 34f, "左右镜像 ⇄", Im.NORMAL)) ed.mirror();
        if (im.button("n-flip", CX + 190f, y, 104f, 34f, "天地互换 ⇅", Im.NORMAL)) ed.flipFace();
        if (im.button("n-del", CX + CW - 70f, y, 70f, 34f, "删除", Im.DANGER)) ed.deleteSel();
        y += 46f;
    }

    // ================================================================== 轨道

    private void laneTab() {
        Session s = ed.s;
        Doc d = s.doc;
        title("轨道", d.lanes.size() + " 条");
        int n = d.lanes.size(), rows = Math.max(1, (n + 8) / 9), per = Math.max(1, (n + rows - 1) / rows);
        float cw = CW / per;
        for (int r = 0; r * per < n; r++) {
            g.box(CX, y, CW, 32f, 6f, Gfx.FIELD, Gfx.BTN_LINE);
            for (int i = r * per; i < Math.min(n, (r + 1) * per); i++) {
                int id = d.lanes.get(i).id;
                float x = CX + (i - r * per) * cw;
                boolean on = id == s.laneSel;
                boolean hv = im.hover(x, y, cw, 32f);
                if (on) g.box(x + 1f, y + 1f, cw - 2f, 30f, 4f, Gfx.SEL, Gfx.GND);
                else if (hv) g.rrect(x + 1f, y + 1f, cw - 2f, 30f, 4f, Gfx.BTN);
                if (hv) im.cursor(Cursor.SystemCursor.Hand);
                g.text(g.fit(ed.laneTag(id), 12, cw - 8f), x + cw * 0.5f, y + 16f, 12, on ? Gfx.INK1 : Gfx.INK2, 1);
                if (im.click(new Im.Pair("f-lane", id), x, y, cw, 32f)) s.laneSel = on ? Session.NO_LANE : id;
            }
            y += 36f;
        }
        y += 2f;
        float at = ed.snap(ed.beat);
        String how = "从第 " + Im.fmt(at) + " 拍" + (appearHow == AT_ONCE ? APPEAR_NAMES[AT_ONCE]
            : APPEAR_NAMES[GRADUAL] + " " + Im.fmt(appearBeats) + " 拍");
        if (im.button("f-new", CX, y, CW, 30f, "+ 新建轨道（" + how + "）", Im.NORMAL, 12)) newLane(s, d);
        y += 40f;
        Lane7 l = d.lane(s.laneSel);
        if (l != null) laneCard(s, d, l);
        else caption("点上面一条轨道（或预览、时间轴里的轨道）来改它。复制轨道连上面的键：选中轨道按 Ctrl+C，再 Ctrl+V（放到播放头）。");
    }

    /** 新建轨道: a lane at the outer end that comes in at the playhead the way last picked; it is selected. */
    void newLane(Session s, Doc d) {
        float b = ed.snap(ed.beat);
        s.mark();
        Lane7 l = d.addLane(b);
        Doc.setAppear(l, b, appearHow == GRADUAL ? appearBeats : 0f, 1f);
        s.laneSel = l.id;
        s.changed(false);
        tab = LANE;
        im.toast("新建了轨道 " + d.laneLabel(l.id) + "（第 " + Im.fmt(b) + " 拍出现）");
    }

    private void laneCard(Session s, Doc d, Lane7 l) {
        float t = ed.beat;
        g.box(CX, y, CW, 34f, 6f, Gfx.ITEM_ON, Gfx.GND);
        String name = g.fit(d.laneLabel(l.id), 13, 150f);
        g.text(name, CX + 12f, y + 17f, 13, Gfx.GND);
        if (d.names.containsKey(l.id)) g.text("编号 " + l.id, CX + 26f + g.width(name, 13), y + 17f, 12, Gfx.INK3);
        g.text("此刻 宽 " + Math.round(l.w.at(t)) + " · 长 " + Math.round(l.h.at(t)), CX + CW - 12f, y + 17f, 12,
            Gfx.INK3, 2);
        y += 42f;
        Group7 in = d.group(l.g);
        if (in != null) {                                               // 组 (chart 2.1)
            g.text(g.fit("在组「" + Doc.groupLabel(in) + "」里：原点、下落向量按组的坐标", 12, CW - 96f), CX, y + 15f, 12,
                Gfx.INK2);
            if (im.button(new Im.Pair("f-grp", l.id), CX + CW - 86f, y, 86f, 30f, "选中组", Im.GHOST, 12)) {
                ed.pickGroup(in.id, false);
            }
            y += 38f;
        }
        label("名字");
        String nv = im.field(new Im.Pair("f-name", l.id), FX, y, FW, 30f, d.laneLabel(l.id), "编号 " + l.id, false);
        y += 38f;
        if (nv != null) renameLane(s, d, l, nv);
        int on = d.keysOn(l.id).size();
        if (im.button(new Im.Pair("f-keys", l.id), CX, y, CW - 94f, 30f, on > 0
            ? "选中这条轨道上的 " + on + " 个键" : "这条轨道上还没有键", on > 0 ? Im.NORMAL : Im.GHOST, 12) && on > 0) {
            s.sel.clear();
            for (Doc.DKey x : d.keysOn(l.id)) s.sel.add(x.uid);
            tab = NOTE;
            im.toast("已选中「" + d.laneLabel(l.id) + "」上的 " + on + " 个键");
        }
        if (im.button("f-del", CX + CW - 86f, y, 86f, 30f, "删除轨道", Im.DANGER, 12)) ed.removeLane(l.id);
        y += 40f;
        lifeRows(s, d, l);
        divider();
        title("下落向量 u（键往哪个方向落）", null);
        Fn[] fs = Doc.fns(l);
        for (int k = 0; k < 3; k++) fnRow("l" + l.id + "-" + k, LANE_FN[k], fs[k], LANE_TABLE[k]);
        int fall = im.dropdown("f-fall", FX, y, FW, 30f, "快捷方向", FALL_NAMES, -1);
        y += 38f;
        if (fall >= 0) setFall(s, l, fall);
        divider();
        // Kelvin 2026-11-10 17:59「那就加一个法向量朝向」, 18:00「和下落向量一样」: written and edited like u
        title("法向量 n（轨道正面朝哪边）", null);
        for (int k = 13; k < 16; k++) fnRow("l" + l.id + "-" + k, LANE_FN[k], fs[k], LANE_TABLE[k]);
        int face = im.dropdown("f-face", FX, y, FW, 30f, "快捷方向", FALL_NAMES, -1);
        y += 38f;
        if (face >= 0) setFacing(s, l, face);
        caption(l.facing() ? "轨道绕自己的向上轴转，让正面尽量朝 n。和下落向量平行时不起作用。"
            : "没写（默认朝你）：轨道按最短的转法转到下落方向，和以前一样。");
        divider();
        title("原点 v（底面中心）", null);
        for (int k = 3; k < 6; k++) fnRow("l" + l.id + "-" + k, LANE_FN[k], fs[k], LANE_TABLE[k]);
        divider();
        title("底面大小 a（宽 × 长）", null);
        for (int k = 6; k < 8; k++) fnRow("l" + l.id + "-" + k, LANE_FN[k], fs[k], LANE_TABLE[k]);
        divider();
        title("天面和底面（沿下落向量反方向量）", null);
        for (int k = 8; k < 12; k++) fnRow("l" + l.id + "-" + k, LANE_FN[k], fs[k], LANE_TABLE[k]);
        caption("天面高度 S、底面高度 F：两个判定面离原点多远（天面键在 S，底面键在 F）。延申 s、f：柱子从面再往外伸多长（最后 700 淡出）。");
        divider();
        title("透明度", "1 不透明，0 看不见；轨道上的键也跟着乘");
        fnRow("l" + l.id + "-12", LANE_FN[12], fs[12], LANE_TABLE[12]);
        // 轨道本身 l (Kelvin 2026-11-11 02:12「这种浪花水雷理论上也不能出现轨道」): the lane's own drawing, not its keys
        fnRow("l" + l.id + "-17", LANE_FN[17], fs[17], LANE_TABLE[17]);
        boolean bare = l.l.segs.isEmpty() && l.l.constant <= 0f;
        if (im.button(new Im.Pair("f-bare", l.id), CX, y, CW, 30f, bare ? "显示轨道本身" : "只显示键（轨道本身不画）",
                Im.NORMAL, 12)) {
            s.mark();
            l.l.segs.clear();
            l.l.constant = bare ? 1f : 0f;
            s.changed(false);
        }
        y += 40f;
        caption("轨道本身只管轨道自己（柱子、面、判定线、边、光）的透明度，上面的键不跟着变；0 = 只显示键。");
        divider();
        // 水雷轨道 = 红色轨道 (Kelvin 2026-11-10 23:01「在一定时间内（一维事件）这些轨道会变红」; 2026-11-12 06:08: no
        // longer a mine,「按到了，没有键，不加分，不断连击，不报lost，但是落在这种轨道上的键只能打出resonance」)
        title("水雷", "大于 0 时整条轨道变红、强制严判");
        fnRow("l" + l.id + "-16", LANE_FN[16], fs[16], LANE_TABLE[16]);
        if (im.button(new Im.Pair("f-mine", l.id), CX, y, CW, 30f, "+ 从播放头变红 1 拍", Im.NORMAL, 12)) {
            float at = ed.snap(ed.beat);
            s.mark();
            Doc.span(l.m, at, at + 1f, 1f);
            s.changed(false);
        }
        y += 40f;
        caption("0 是普通轨道；大于 0 时整条轨道变红（1 最红），强制严判：天面、底面上的键都只能打出 RESONANCE，"
            + "按到没有键不扣分、不断连击、不报 LOST。按钮从播放头加 1 拍的红，结束拍可以在上面的段里改。");
    }

    /** 快捷方向 {@code k}: lane l's fall vector (its value at the playhead when it has segments). */
    private void setFall(Session s, Lane7 l, int k) {
        float t = ed.snap(ed.beat);
        float[] f;
        if (k < FALLS.length) f = FALLS[k];
        else f = new float[]{-l.u.x.at(t), -l.u.y.at(t), -l.u.z.at(t)};               // 反过来
        s.mark();
        Doc.setAt(l.u.x, t, f[0]);
        Doc.setAt(l.u.y, t, f[1]);
        Doc.setAt(l.u.z, t, f[2]);
        s.changed(false);
    }

    /** 快捷方向 {@code k} for lane l's normal n, as {@link #setFall} for its fall (反过来: n turned round). */
    private void setFacing(Session s, Lane7 l, int k) {
        float t = ed.snap(ed.beat);
        float[] f;
        if (k < FALLS.length) f = FALLS[k];
        else f = new float[]{-l.n.x.at(t), -l.n.y.at(t), -l.n.z.at(t)};
        s.mark();
        Doc.setAt(l.n.x, t, f[0]);
        Doc.setAt(l.n.y, t, f[1]);
        Doc.setAt(l.n.z, t, f[2]);
        s.changed(false);
    }

    void renameLane(Session s, Doc d, Lane7 l, String v) {
        String name = v.trim();
        if (name.isEmpty() || name.equals(String.valueOf(l.id))) {
            s.mark();
            d.names.remove(l.id);
            s.changed(false);
            return;
        }
        if (d.laneCalled(name, l) != null) {
            im.toast("已经有叫「" + name + "」的轨道了");
            return;
        }
        if (name.matches("-?[0-9]+") && d.lane(Integer.parseInt(name)) != null) {
            im.toast("「" + name + "」是另一条轨道的编号");
            return;
        }
        s.mark();
        d.names.put(l.id, name);
        s.changed(false);
    }

    /**
     * 出现 / 消失: the beat lane l starts to come in (empty: there from the start) and the beat it starts to go
     * (empty: it stays), each with its way under it once set. They are its width's first and last segments (from and
     * to 0), edited in 宽 like any other. A typed 出现 moves the whole lane with its keys (操作 G, Kelvin 2026-11-09
     * 17:23); a typed 消失 moves the 消失.
     */
    private void lifeRows(Session s, Doc d, Lane7 l) {
        float a = Doc.appearBeat(l);
        float na = lifeField("f-in", "出现", a, "一开始就在");
        if (na == Float.NEGATIVE_INFINITY) {
            s.mark();
            Doc.clearAppear(l);
            s.changed(false);
        } else if (!Float.isNaN(na) && na != a) {
            float v = Doc.vanishBeat(l);
            if (Float.isFinite(a)) {
                s.mark();
                d.moveLane(l, na - a);
                s.changed(false);
            } else if (Float.isFinite(v) && na >= v) im.toast("出现要在消失以前");
            else {
                s.mark();
                Doc.setAppear(l, na, appearHow == GRADUAL ? appearBeats : 0f, 1f);
                s.changed(false);
            }
        }
        Seg as = Doc.appearSeg(l);
        if (as != null) {
            int how = as.tf > as.ti ? GRADUAL : AT_ONCE;
            float len = as.tf - as.ti;
            wayRow("f-in-how", APPEAR_NAMES, how, how == AT_ONCE ? appearBeats : len);
            if (pickedWay >= 0) appearHow = pickedWay;
            if (!Float.isNaN(typedBeats)) appearBeats = typedBeats;
            if (pickedWay >= 0 || !Float.isNaN(typedBeats) && how == GRADUAL) {
                int way = pickedWay >= 0 ? pickedWay : how;
                float nl = way == AT_ONCE ? 0f : !Float.isNaN(typedBeats) ? typedBeats : len > 0f ? len : appearBeats;
                s.mark();
                Doc.setAppear(l, as.ti, nl, as.e);
                s.changed(false);
            }
        }
        float v = Doc.vanishBeat(l);
        float nv = lifeField("f-out", "消失", v, "一直在");
        if (nv == Float.NEGATIVE_INFINITY) {
            s.mark();
            Doc.clearVanish(l);
            s.changed(false);
        } else if (!Float.isNaN(nv) && nv != v) {
            float in = Doc.appearBeat(l);
            if (Float.isFinite(in) && nv <= in) im.toast("消失要在出现以后");
            else if (!Float.isFinite(v)) {
                s.mark();
                Doc.setVanish(l, nv, vanishHow == GRADUAL ? vanishBeats : 0f, 1f);
                s.changed(false);
            } else {
                s.mark();
                Seg vs = Doc.vanishSeg(l);
                vs.ti += nv - v;
                vs.tf += nv - v;
                l.w.sort();
                s.changed(false);
            }
        }
        Seg vs = Doc.vanishSeg(l);
        if (vs != null) {
            int how = vs.tf > vs.ti ? GRADUAL : AT_ONCE;
            float len = vs.tf - vs.ti;
            wayRow("f-out-how", VANISH_NAMES, how, how == AT_ONCE ? vanishBeats : len);
            if (pickedWay >= 0) vanishHow = pickedWay;
            if (!Float.isNaN(typedBeats)) vanishBeats = typedBeats;
            if (pickedWay >= 0 || !Float.isNaN(typedBeats) && how == GRADUAL) {
                int way = pickedWay >= 0 ? pickedWay : how;
                float nl = way == AT_ONCE ? 0f : !Float.isNaN(typedBeats) ? typedBeats : len > 0f ? len : vanishBeats;
                s.mark();
                vs.tf = vs.ti + nl;
                s.changed(false);
            }
        } else {                                                        // 操作 A's last step: gone at once
            if (im.button("f-out-keys", FX, y, FW, 30f, "+ 打完它的键就消失", Im.NORMAL, 12)) {
                float last = d.keysEnd(l.id);
                if (Float.isNaN(last)) im.toast("这条轨道上还没有键");
                else {
                    s.mark();
                    Doc.setVanish(l, last, 0f, 1f);
                    s.changed(false);
                }
            }
            y += 38f;
        }
    }

    /**
     * A beat field of {@link #lifeRows}: {@code value}, or empty showing {@code empty}, with × to empty it.
     *
     * @return the beat typed this frame (/ = the playhead), {@link Float#NEGATIVE_INFINITY} for ×, else NaN
     */
    private float lifeField(String id, String name, float value, String empty) {
        g.text(name, CX, y + 15f, 13, Gfx.INK2);
        boolean set = !Float.isNaN(value);
        float fw = set ? FW - 36f : FW;
        im.slash(im.slashBeat, Im.beatHint(im.slashBeat, ""), true);
        float v = im.num(id, FX, y, fw, 30f, value, set ? "拍" : empty);
        if (set && im.button(id + "-r", FX + fw + 6f, y, 30f, 30f, "×", Im.GHOST, 14)) v = Float.NEGATIVE_INFINITY;
        y += 36f;
        return v;
    }

    /** A way row of {@link #lifeRows}: {@code names} with {@code how} lit and, unless at once, its beats. */
    private void wayRow(String id, String[] names, int how, float beats) {
        boolean len = how != AT_ONCE;
        int k = im.segmented(id, FX, y, len ? FW - 96f : FW, 30f, names, how);
        pickedWay = k != how ? k : -1;
        typedBeats = Float.NaN;
        if (len) {
            im.slash(Float.NaN, null, false);                          // beats long, not the playhead
            float v = im.num(id + "-len", FX + FW - 90f, y, 90f, 30f, beats, "拍");
            if (v > 0f) typedBeats = v;
            else if (!Float.isNaN(v)) im.toast("拍数要大于 0");
        }
        y += 40f;
    }

    // ================================================================== 镜头

    private void cameraTab() {
        Session s = ed.s;
        Doc d = s.doc;
        title("镜头位置 P", "一直看着原点");
        fnRow("cam-0", "x", d.camera.x, "镜头");
        fnRow("cam-1", "y", d.camera.y, "镜头");
        fnRow("cam-2", "z", d.camera.z, "镜头");
        float bw = (CW - 8f) / 2f;
        if (im.button("cam-node", CX, y, bw, 30f, "+ 镜头节点（播放头）", Im.NORMAL, 12)) ed.cameraNode();
        if (im.button("cam-reset", CX + bw + 8f, y, bw, 30f, "回到正前方", Im.GHOST, 12)) {
            s.mark();
            for (Fn f : new Fn[]{d.camera.x, d.camera.y, d.camera.z}) f.segs.clear();
            d.camera.x.constant = 0f;
            d.camera.y.constant = 0f;
            d.camera.z.constant = -Chart7.CAMERA_DIST;
            s.changed(false);
        }
        y += 40f;
        caption("默认在 (0, 0, −" + dist(Chart7.CAMERA_DIST) + ")，正对着画面。镜头节点 = 在播放头记下现在的位置；"
            + "把播放头挪到后面再改数值，镜头就从节点移过去。预览里转视角、Ctrl+Alt+方向键挪动只是看，不改镜头。");
        divider();
        title("视角", "上下看得见多少度，默认 11，1 到 150");
        fnRow("cam-3", "视角", d.fov, "视角");
        caption("视角越大看得越广，近处的东西显得越大；人眼大约 60°。");
    }

    // ================================================================== 幕布

    private void volumeTab() {
        Session s = ed.s;
        Doc d = s.doc;
        title("漩涡和水帘", d.vols.size() + " 个");
        int veils = 0, curtains = 0, n = d.vols.size();
        if (n > 0) {
            int rows = Math.max(1, (n + 5) / 6), per = Math.max(1, (n + rows - 1) / rows);
            float cw = CW / per;
            for (int r = 0; r * per < n; r++) {
                g.box(CX, y, CW, 32f, 6f, Gfx.FIELD, Gfx.BTN_LINE);
                for (int i = r * per; i < Math.min(n, (r + 1) * per); i++) {
                    Doc.DVol v = d.vols.get(i);
                    String name = v.v.curtain ? "水帘 " + ++curtains : "漩涡 " + ++veils;
                    float x = CX + (i - r * per) * cw;
                    boolean on = v.uid == s.volSel;
                    boolean hv = im.hover(x, y, cw, 32f);
                    if (on) g.box(x + 1f, y + 1f, cw - 2f, 30f, 4f, Gfx.SEL, Gfx.GND);
                    else if (hv) g.rrect(x + 1f, y + 1f, cw - 2f, 30f, 4f, Gfx.BTN);
                    if (hv) im.cursor(Cursor.SystemCursor.Hand);
                    g.text(name, x + cw * 0.5f, y + 16f, 12, on ? Gfx.INK1 : Gfx.INK2, 1);
                    if (im.click(new Im.Pair("v-pick", v.uid), x, y, cw, 32f)) s.volSel = on ? 0 : v.uid;
                }
                y += 36f;
            }
            y += 2f;
        }
        float bw = (CW - 8f) / 2f;
        if (im.button("v-new", CX, y, bw, 30f, "+ 新建漩涡（播放头）", Im.NORMAL, 12)) ed.newVolume(false);
        if (im.button("v-newc", CX + bw + 8f, y, bw, 30f, "+ 新建水帘（播放头）", Im.NORMAL, 12)) ed.newVolume(true);
        y += 40f;
        Doc.DVol dv = d.volByUid(s.volSel);
        if (dv == null) {
            caption("漩涡挡住里面的键（点不到）；水帘只是把里面模糊，不判定。都是长方体或椭球体，有大小 V、中心 p 和朝向 o。"
                + "工具 9、0 在预览里点一下也能放。");
            return;
        }
        volumeCard(s, d, dv);
    }

    private void volumeCard(Session s, Doc d, Doc.DVol dv) {
        Volume7 v = dv.v;
        int ki = v.curtain ? 1 : 0;
        int nk = im.segmented("v-kind", CX, y, (CW - 8f) / 2f, 32f, new String[]{"漩涡", "水帘"}, ki);
        int si = v.ellipsoid ? 1 : 0;
        int ns = im.segmented("v-shape", CX + (CW + 8f) / 2f, y, (CW - 8f) / 2f, 32f, new String[]{"长方体", "椭球体"}, si);
        if (nk != ki || ns != si) {
            s.mark();
            v.curtain = nk == 1;
            v.ellipsoid = ns == 1;
            s.changed(false);
        }
        y += 42f;
        float a = Doc.appearBeat(v);
        float na = lifeField("v-in", "出现", a, "一开始就在");
        if (na == Float.NEGATIVE_INFINITY) {
            s.mark();
            Doc.clearAppear(v);
            s.changed(false);
        } else if (!Float.isNaN(na) && na != a) {
            float e = Doc.vanishBeat(v);
            if (Float.isFinite(e) && na >= e) im.toast("出现要在消失以前");
            else {
                Seg as = Doc.appearSeg(v.V.x);
                s.mark();
                Doc.setAppear(v, na, as != null ? as.tf - as.ti : 0f);
                s.changed(false);
            }
        }
        float e = Doc.vanishBeat(v);
        float ne = lifeField("v-out", "消失", e, "一直在");
        if (ne == Float.NEGATIVE_INFINITY) {
            s.mark();
            Doc.clearVanish(v);
            s.changed(false);
        } else if (!Float.isNaN(ne) && ne != e) {
            float in = Doc.appearBeat(v);
            if (Float.isFinite(in) && ne <= in) im.toast("消失要在出现以后");
            else {
                Seg vs = Doc.vanishSeg(v.V.x);
                s.mark();
                Doc.setVanish(v, ne, vs != null ? vs.tf - vs.ti : 0f);
                s.changed(false);
            }
        }
        divider();
        Fn[] fs = Doc.fns(v);
        title("大小 V", null);
        for (int k = 0; k < 3; k++) fnRow("v" + dv.uid + "-" + k, VOL_FN[k], fs[k], "大小");
        divider();
        title("中心 p", null);
        for (int k = 3; k < 6; k++) fnRow("v" + dv.uid + "-" + k, VOL_FN[k], fs[k], "中心");
        divider();
        title("朝向 o", "深 d 那条边指的方向，默认 (0, 0, 1)");
        for (int k = 6; k < 9; k++) fnRow("v" + dv.uid + "-" + k, VOL_FN[k], fs[k], "朝向");
        if (v.curtain) { // 模糊 b, as before v7 (game 7.228): the opening of My Soul blurs in and clears
            divider();
            title("模糊 b", "盖住的东西糊到什么程度：默认 16，0 到 64");
            fnRow("v" + dv.uid + "-9", VOL_FN[9], fs[9], "模糊");
        }
        divider();
        float t = ed.snap(ed.beat);
        effects(s, "v" + dv.uid, null, v.curtain ? null : v.glitches, v.distortions, false, t, t + 1f);
        divider();
        if (im.button("v-del", CX + CW - 70f, y, 70f, 34f, "删除", Im.DANGER)) ed.deleteVol();
        y += 46f;
    }

    // ================================================================== 残影 · 故障 · 扭曲

    /**
     * 残影 / 故障 / 扭曲 (Kelvin 2026-11-10 02:17「故障效果和残影要加上啊，还有那个distortion，这些还是保留」), as before v7:
     * segments over the song's beats (empty = no bound), each with its values; a null list is not offered (a veil or
     * curtain has no 残影, a curtain no 故障). The game's segments are fixed values: an edit puts a new one in its place,
     * a custom ease curve kept unless 缓动 is typed. New 故障 and 扭曲 cover {@code from}..{@code to}.
     */
    private void effects(Session s, String p, List<NoteTrail> trails, List<NoteGlitch> glitches,
                         List<NoteDistortion> dists, boolean key, float from, float to) {
        float w4 = (CW - 16f - 24f) / 4f;
        if (trails != null) {
            title("拖拽残影 trail", trails.isEmpty() ? null : trails.size() + " 段");
            int del = -1;
            for (int i = 0; i < trails.size(); i++) {
                NoteTrail x = trails.get(i);
                segHead(p + "-tr", i, x.from(), x.to(), 140f);
                if (headDel) del = i;
                float l = cell(p + "-trl", i, CX + 8f, w4, "长度", x.length(), "拍");
                float d = cell(p + "-trd", i, CX + 16f + w4, w4, "密度", x.density(), "/拍");
                float a = cell(p + "-tra", i, CX + 24f + 2f * w4, w4, "透明", x.alpha(), "");
                float f = cell(p + "-trf", i, CX + 32f + 3f * w4, w4, "衰减", x.fade(), "");
                y += 50f;
                int m = x.mode() == NoteTrail.Mode.LEVEL ? 1 : 0;
                int nm = im.segmented(new Im.Pair(p + "-trm", i), CX + 8f, y, CW - 16f, 28f,
                    new String[]{"沿路径 path", "同高 level"}, m);
                y += 46f;
                if (Float.isNaN(headFrom) && Float.isNaN(headTo) && Float.isNaN(l) && Float.isNaN(d) && Float.isNaN(a)
                    && Float.isNaN(f) && nm == m) continue;
                NoteTrail n = NoteTrail.of(or(l, x.length()), or(d, x.density()), or(a, x.alpha()), or(f, x.fade()),
                    nm == 1 ? NoteTrail.Mode.LEVEL : NoteTrail.Mode.PATH, or(headFrom, x.from()), or(headTo, x.to()));
                if (n.isNone()) {
                    im.toast("长度和密度都要大于 0");
                    continue;
                }
                s.mark();
                trails.set(i, n);
                s.changed(false);
            }
            if (del >= 0) {
                s.mark();
                trails.remove(del);
                s.changed(false);
            }
            if (im.button(p + "-tr-add", CX, y, 160f, 30f, "+ 加一段残影", Im.GHOST, 12)) {
                s.mark();
                trails.add(NoteTrail.of(NoteTrail.SUGGESTED_LENGTH, NoteTrail.SUGGESTED_DENSITY, NoteTrail.DEFAULT_ALPHA,
                    NoteTrail.DEFAULT_FADE, NoteTrail.Mode.PATH, Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY));
                s.changed(false);
            }
            y += 38f;
            caption("残影数 = 长度 × 密度（每段最多 " + NoteTrail.MAX_TRAIL_COPIES + " 个）；范围空着 = 不限。分成几段时，每段只在"
                + "自己的范围里出现，段和段之间没有残影。");
            divider();
        }
        if (glitches != null) {
            title("故障 glitch", glitches.isEmpty() ? null : glitches.size() + " 段");
            int del = -1;
            for (int i = 0; i < glitches.size(); i++) {
                NoteGlitch x = glitches.get(i);
                segHead(p + "-gl", i, x.from(), x.to(), 150f);
                if (headDel) del = i;
                boolean curve = x.curve() != null;
                float a = cell(p + "-gla", i, CX + 8f, w4, "强度 开始", x.intensity(), "");
                float b = cell(p + "-glb", i, CX + 16f + w4, w4, "结束", x.intensityEnd(), "");
                float e = cell(p + "-gle", i, CX + 24f + 2f * w4, w4, "缓动", curve ? Float.NaN : x.exponent(),
                    curve ? "曲线" : "");
                float r = cell(p + "-glr", i, CX + 32f + 3f * w4, w4, "速度", x.rate(), "次/拍");
                y += 50f;
                float sp = cell(p + "-gls", i, CX + 8f, w4, "色散", x.split(), "");
                float sl = cell(p + "-glc", i, CX + 16f + w4, w4, "错位", x.slice(), "");
                float fl = cell(p + "-glf", i, CX + 24f + 2f * w4, w4, "闪断", x.flicker(), "");
                y += 58f;
                if (Float.isNaN(headFrom) && Float.isNaN(headTo) && Float.isNaN(a) && Float.isNaN(b) && Float.isNaN(e)
                    && Float.isNaN(r) && Float.isNaN(sp) && Float.isNaN(sl) && Float.isNaN(fl)) continue;
                if (!Float.isNaN(e) && !(e > 0f)) {
                    im.toast("缓动要大于 0（1 = 匀速，2 = 先慢后快，0.5 = 先快后慢）");
                    continue;
                }
                s.mark();
                glitches.set(i, NoteGlitch.of(or(headFrom, x.from()), or(headTo, x.to()), or(a, x.intensity()),
                    or(b, x.intensityEnd()), or(e, x.exponent()), or(r, x.rate()), or(sp, x.split()), or(sl, x.slice()),
                    or(fl, x.flicker()), Float.isNaN(e) ? x.curve() : null));
                s.changed(false);
            }
            if (del >= 0) {
                s.mark();
                glitches.remove(del);
                s.changed(false);
            }
            if (im.button(p + "-gl-add", CX, y, 160f, 30f, "+ 加一段故障", Im.GHOST, 12)) {
                s.mark();
                glitches.add(NoteGlitch.of(from, to, NoteGlitch.DEFAULT_INTENSITY, NoteGlitch.DEFAULT_INTENSITY, 1f,
                    NoteGlitch.DEFAULT_RATE, NoteGlitch.DEFAULT_SPLIT, NoteGlitch.DEFAULT_SLICE, NoteGlitch.DEFAULT_FLICKER));
                s.changed(false);
            }
            y += 38f;
            caption("只影响画面。强度 0–1，「结束」不同就从开始强度渐变过去（缓动 1 = 匀速）；速度 = 每拍换几次花样（0 = 不换）；"
                + "色散 / 错位 / 闪断是比重 0–2。范围空着 = 不限；两段重叠时，后开始的那段起作用。");
            divider();
        }
        title("扭曲 distortion", dists.isEmpty() ? null : dists.size() + " 段");
        int del = -1;
        for (int i = 0; i < dists.size(); i++) {
            NoteDistortion x = dists.get(i);
            segHead(p + "-di", i, x.from(), x.to(), 100f);
            if (headDel) del = i;
            boolean curve = x.curve() != null;
            float a = cell(p + "-dia", i, CX + 8f, w4, "强度 开始", x.intensity(), "");
            float b = cell(p + "-dib", i, CX + 16f + w4, w4, "结束", x.intensityEnd(), "");
            float e = cell(p + "-die", i, CX + 24f + 2f * w4, w4, "缓动", curve ? Float.NaN : x.exponent(),
                curve ? "曲线" : "");
            y += 58f;
            if (Float.isNaN(headFrom) && Float.isNaN(headTo) && Float.isNaN(a) && Float.isNaN(b) && Float.isNaN(e)) {
                continue;
            }
            if (!Float.isNaN(e) && !(e > 0f)) {
                im.toast("缓动要大于 0（1 = 匀速，2 = 先慢后快，0.5 = 先快后慢）");
                continue;
            }
            s.mark();
            dists.set(i, NoteDistortion.of(or(headFrom, x.from()), or(headTo, x.to()), or(a, x.intensity()),
                or(b, x.intensityEnd()), or(e, x.exponent()), Float.isNaN(e) ? x.curve() : null));
            s.changed(false);
        }
        if (del >= 0) {
            s.mark();
            dists.remove(del);
            s.changed(false);
        }
        if (im.button(p + "-di-add", CX, y, 160f, 30f, "+ 加一段扭曲", Im.GHOST, 12)) {
            s.mark();
            float v = key ? NoteDistortion.DEFAULT_INTENSITY : 2f;
            dists.add(NoteDistortion.of(from, to, v, v, 1f));
            s.changed(false);
        }
        y += 38f;
        caption((key ? "键平常不扭曲（0）；1 = 和漩涡平常一样" : "平常就有扭曲（1）；0 = 不扭曲") + "，2 = 两倍，最多 "
            + Im.fmt(NoteDistortion.MAX_INTENSITY) + "。只影响画面；范围空着 = 不限，段外回到平常；两段重叠时，后开始的那段起作用。");
    }

    /** What {@link #segHead} read: the range ends typed (NaN = unchanged, ±∞ = no bound now) and whether × was hit. */
    private float headFrom, headTo;
    private boolean headDel;

    /** An effect segment's card of height {@code h} from y: #n, 从 → 到 (song beats, empty = no bound) and ×. */
    private void segHead(String key, int i, float from, float to, float h) {
        g.box(CX, y, CW, h, 6f, Gfx.ITEM, Gfx.ITEM_LINE);
        y += 6f;
        g.text("#" + (i + 1), CX + 8f, y + 28f, 11, Gfx.INK3);
        float w = (CW - 90f) / 2f;
        headFrom = bound(key + "-from", i, CX + 30f, w, "从（拍）", from, Float.NEGATIVE_INFINITY);
        g.text("→", CX + 36f + w, y + 28f, 12, Gfx.INK3, 1);
        headTo = bound(key + "-to", i, CX + 44f + w, w, "到（拍）", to, Float.POSITIVE_INFINITY);
        headDel = im.button(new Im.Pair(key + "-del", i), CX + CW - 32f, y + 14f, 26f, 28f, "×", Im.GHOST, 14);
        y += 50f;
    }

    /** A range end of {@link #segHead} (/ = the playhead). @return the typed beat, {@code none} on its ×, else NaN */
    private float bound(String key, int i, float x, float w, String label, float v, float none) {
        g.text(label, x, y + 6f, 11, Gfx.INK3);
        boolean set = Float.isFinite(v);
        float fw = set ? w - 26f : w, now = ed.snap(ed.beat);
        im.slash(now, Im.beatHint(now, ""), true);
        float r = im.num(new Im.Pair(key, i), x, y + 14f, fw, 28f, set ? v : Float.NaN, set ? "" : "不限");
        if (set && im.button(new Im.Pair(key + "x", i), x + fw + 2f, y + 14f, 24f, 28f, "×", Im.GHOST, 12)) r = none;
        return r;
    }

    /** A value of an effect segment: its name above its field. @return the typed value, else NaN */
    private float cell(String key, int i, float x, float w, String label, float v, String unit) {
        g.text(label, x, y + 6f, 11, Gfx.INK3);
        im.slash(Float.NaN, null, false);
        return im.num(new Im.Pair(key, i), x, y + 14f, w, 28f, v, unit);
    }

    /** @return {@code typed} unless it is NaN (nothing typed), else {@code was} */
    private static float or(float typed, float was) {
        return Float.isNaN(typed) ? was : typed;
    }
}
