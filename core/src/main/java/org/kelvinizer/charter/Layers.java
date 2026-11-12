package org.kelvinizer.charter;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Cursor;
import org.kelvinizer.elements.Fn;
import org.kelvinizer.elements.Group7;
import org.kelvinizer.elements.KeyKind;
import org.kelvinizer.elements.Lane7;
import org.kelvinizer.elements.Seg;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;

/**
 * 分层时间轴（Kelvin 2026-11-11 16:15「我要它有点像3D动画制作的那种」，16:39「基本可以」）：总览一行，然后镜头、无轨键、每条轨道、每个漩涡和水帘各一层；
 * 展开一层看它的天面键、地面键和会动的数。
 * The layered timeline under the ruler and the 音频 strip (a dope sheet, in place of the old 天面 / 底面 / 函数 rows):
 * 总览 (every key and keyframe; a click moves the playhead), then a layer for the camera, for the keys on no lane (when
 * there are any), for each lane and for each veil or curtain. A layer row shows its life (出现 → 消失, a bar: drag an
 * end to move that end, drag the bar to move the lane with its keys, 操作 G), its keyframes and its keys. Opened
 * (▸ / ▾) a lane lists its 天面键 and 地面键 (what the old key rows did, for its keys only; a click on empty space puts
 * a key of the tool there) and a row per animated group of numbers ({@link Keyframes.Group}): ◆ = a keyframe (click:
 * the playhead goes there; drag: retime it), a purple bar = a segment (click: how it eases, in a strip above it).
 * 「＋ 加一行」 starts a row for a number that does not move yet; a selected key's own numbers show under its lane.
 * Picking a thing anywhere opens its layer and scrolls to it. 「只看选中」 and 「搜索轨道」 thin the list out.
 */
final class Layers {
    private static final boolean TAB = Gfx.TABLET;
    /** A row's height. */
    static final float RH = TAB ? 34f : 22f;
    private static final float TOUCH = TAB ? 2.5f : 1f;
    /** Half a ◆'s diagonal. */
    private static final float DIA = TAB ? 6f : 4.5f;
    private static final Color UP_INK = Gfx.c(0xE6ECFF), LIFE = Gfx.c(0x3A4478), KF = Gfx.SYNC;

    static final int LAYER = 0, KEYS = 1, NUM = 2, ADD = 3;
    static final int CAM = 0, FREE = 1, LANE = 2, VOL = 3, GROUP = 4;

    /** A row of this frame. */
    static final class Row {
        int type, obj, id;
        /** How deep in groups it is listed (组, chart 2.1): its name goes in that far. */
        int depth;
        /** Its layer: "cam", "free", "l" + lane id, "v" + volume uid, "g" + group id. */
        String layer, name, right;
        boolean sky, keyGroup;
        Keyframes.Group grp;
        Keyframes.Group[] addable;
        float y;
    }

    private final EditorScreen ed;
    private final Gfx g;
    private final Im im;
    /** Opened layers. */
    final HashSet<String> open = new HashSet<>();
    boolean onlySel;
    /** 搜索轨道: what was typed (committed), and what the field holds now (while typing). */
    String filter = "", find = "";
    private float scroll, contentH;
    final ArrayList<Row> rows = new ArrayList<>();
    private final HashMap<Integer, Lane7> lanes = new HashMap<>();
    private final HashMap<Integer, ArrayList<Doc.DKey>> byLane = new HashMap<>();
    private final ArrayList<Doc.DKey> free = new ArrayList<>();
    private final HashSet<Integer> selLanes = new HashSet<>();

    // ---- what was selected last frame: a change opens and shows its layer
    private int seenLane = Session.NO_LANE, seenKey, seenVol, seenGroup;
    private boolean seenCam;
    private String reveal;

    // ---- keyframes of every layer, kept while the doc stays the same
    private Doc cDoc;
    private int cVer = -1;
    private final HashMap<String, float[]> cPts = new HashMap<>();
    private final HashMap<Integer, Integer> cCount = new HashMap<>();
    private float[] cAll = new float[0];

    // ---- dragging
    private static final int D_NONE = 0, D_SEEK = 1, D_KEY = 2, D_KEYS = 3, D_EMPTY = 4, D_BOX = 5, D_DIA = 6,
        D_IN = 7, D_OUT = 8, D_BAR = 9, D_NAME = 10, D_SCROLL = 11;
    private int drag;
    private boolean marked, far;
    private Row dRow;
    private Doc.DKey dKey;
    private float dAt, dLo, dHi, dApplied, dScroll;
    private final ArrayList<Doc.DKey> dSel = new ArrayList<>();

    // ---- the ease strip over a picked bar
    static final float SW = 492f, SH = 108f;
    static final float[] EASES = {0.33f, 0.5f, 1f, 2f, 3f};
    private static final String[] EASE_NAMES = {"先快后慢", "先快后慢", "匀速", "先慢后快", "先慢后快"};
    private String stripLayer, stripName;
    private boolean stripKey;
    private float barA = Float.NaN, barB = Float.NaN, sx, sy;
    private boolean stripShown;

    Layers(EditorScreen ed) {
        this.ed = ed;
        this.g = ed.g;
        this.im = ed.im;
    }

    private static float top() {
        return EditorScreen.R_ROWS + RH;
    }

    // ================================================================== frame

    /** Before the timeline's widgets: the ease strip (drawn last) covers what is under it. */
    void block() {
        if (stripShown) im.block(sx, sy, SW, SH);
    }

    /** Builds this frame's rows, draws 总览 and the rows, handles their input and draws the ease strip. */
    void draw() {
        follow();
        cache();
        build();
        if (im.pressed && stripOn() && !(stripShown && im.inside(sx, sy, SW, SH))) closeStrip();
        overview();
        float t0 = top(), bottom = EditorScreen.TL_BOTTOM;
        im.pushClip(64f, t0, EditorScreen.TR - 64f, bottom - t0);
        for (Row r : rows) if (r.y + RH > t0 && r.y < bottom) name(r);
        im.pushClip(EditorScreen.TX, t0, EditorScreen.TW, bottom - t0);
        for (Row r : rows) if (r.y + RH > t0 && r.y < bottom) track(r);
        if (drag == D_BOX) {
            float x0 = Math.min(im.pressX, im.mx), y0 = Math.min(im.pressY, im.my);
            g.rect(x0, y0, Math.abs(im.mx - im.pressX), Math.abs(im.my - im.pressY), Gfx.GND, 0.12f);
            g.stroke(x0, y0, Math.abs(im.mx - im.pressX), Math.abs(im.my - im.pressY), 1f, 1f, Gfx.GND);
        }
        im.popClip();
        im.popClip();
        float viewH = bottom - t0;
        if (contentH > viewH) {                                          // where the list is scrolled to
            float bh = Math.max(16f, viewH * viewH / contentH), by = t0 + (viewH - bh) * scroll / (contentH - viewH);
            g.rrect(EditorScreen.TR - 5f, by, 3f, bh, 1.5f, Gfx.SEL_LINE);
        }
        input();
        strip();
    }

    // ================================================================== rows

    private ArrayList<Doc.DKey> keysOf(int lane) {
        ArrayList<Doc.DKey> l = byLane.get(lane);
        if (l == null) {
            l = new ArrayList<>();
            byLane.put(lane, l);
        }
        return l;
    }

    private void build() {
        Session s = ed.s;
        Doc d = s.doc;
        rows.clear();
        lanes.clear();
        byLane.clear();
        free.clear();
        for (Lane7 l : d.lanes) lanes.put(l.id, l);
        for (Doc.DKey k : d.keys) {
            if (ed.hiddenFake(k)) continue;
            if (k.k.kind.spans()) {
                boolean any = false;
                for (int id : k.k.lanes) {
                    if (lanes.containsKey(id)) {
                        keysOf(id).add(k);
                        any = true;
                    }
                }
                if (!any) free.add(k);
            } else if (lanes.containsKey(k.k.lane)) keysOf(k.k.lane).add(k);
            else free.add(k);
        }
        selLanes.clear();
        boolean freeSel = false;
        if (s.laneSel != Session.NO_LANE) selLanes.add(s.laneSel);
        for (Doc.DKey k : s.selected()) {
            if (k.k.kind.spans()) for (int id : k.k.lanes) selLanes.add(id);
            else selLanes.add(k.k.lane);
            if (free.contains(k)) freeSel = true;
        }
        float y = top() - scroll;
        Keyframes.Group[] cam = Keyframes.camera(d);
        if (passes("镜头", "", ed.insp.tab == Inspector.CAMERA)) {
            y = layer(y, CAM, 0, "cam", "镜头", Keyframes.any(cam) ? "" : "没有关键帧");
            if (open.contains("cam")) y = numRows(y, cam, false);
        }
        if (!free.isEmpty() && passes("无轨键", "", freeSel)) {
            y = layer(y, FREE, 0, "free", "无轨键", free.size() + " 个");
            if (open.contains("free")) y = keyRows(y);
        }
        Doc.DKey one = s.single();
        if (onlySel || !find.trim().isEmpty()) {                        // 只看选中 · 搜索: one flat list
            for (Group7 gr : d.groups) {
                if (passes(Doc.groupLabel(gr), "", ed.s.groupSel == gr.id)) y = groupLayer(y, gr, 0, one, false);
            }
            for (Lane7 l : d.lanes) y = laneLayer(y, l, 0, one);
        } else {                                                        // groups first, each with what is in it
            for (Group7 gr : d.groups) if (d.parentOf(gr.id) == 0) y = groupLayer(y, gr, 0, one, true);
            for (Lane7 l : d.lanes) if (d.group(l.g) == null) y = laneLayer(y, l, 0, one);
        }
        for (int i = 0; i < d.vols.size(); i++) {
            Doc.DVol v = d.vols.get(i);
            String label = (v.v.curtain ? "水帘 " : "漩涡 ") + (i + 1);
            if (!passes(label, "", ed.insp.tab == Inspector.VOLUME && s.volSel == v.uid)) continue;
            y = layer(y, VOL, v.uid, "v" + v.uid, label, "");
            if (open.contains("v" + v.uid)) y = numRows(y, Keyframes.volume(v), false);
        }
        contentH = y + scroll - top();
        float viewH = EditorScreen.TL_BOTTOM - top(), was = scroll;
        if (reveal != null) {                                           // show the layer just picked
            Row first = null, last = null;
            for (Row r : rows) {
                if (!r.layer.equals(reveal)) continue;
                if (first == null) first = r;
                last = r;
            }
            if (first != null) {
                if (first.y < top()) scroll -= top() - first.y;
                else if (last.y + RH > top() + viewH) scroll += Math.min(last.y + RH - top() - viewH, first.y - top());
            }
            reveal = null;
        }
        scroll = Math.max(0f, Math.min(scroll, contentH - viewH));
        if (scroll != was) for (Row r : rows) r.y -= scroll - was;
    }

    /** Lane l's layer at {@code depth}, and its rows when it is open. */
    private float laneLayer(float y, Lane7 l, int depth, Doc.DKey one) {
        String nm = ed.s.doc.names.get(l.id);
        String label = nm != null ? nm : "轨道 " + l.id;
        if (!passes(label, String.valueOf(l.id), selLanes.contains(l.id))) return y;
        y = layer(y, LANE, l.id, "l" + l.id, label, nm != null ? "轨道 " + l.id : "");
        rows.get(rows.size() - 1).depth = depth;
        if (!open.contains("l" + l.id)) return y;
        y = keyRows(y);
        y = numRows(y, Keyframes.lane(l), false);
        if (one != null && one.on(l.id)) y = numRows(y, Keyframes.key(one.k), true);
        return y;
    }

    /**
     * Group gr's layer at {@code depth} (组, chart 2.1: the number of its lanes on the right); open, its rows (位置, 旋转
     * …) and, {@code members}, its groups and lanes under it.
     */
    private float groupLayer(float y, Group7 gr, int depth, Doc.DKey one, boolean members) {
        Doc d = ed.s.doc;
        Integer n = cCount.get(gr.id);
        String count = (n != null ? n : 0) + (depth == 0 ? " 条轨道" : "");
        y = layer(y, GROUP, gr.id, "g" + gr.id, Doc.groupLabel(gr), count);
        rows.get(rows.size() - 1).depth = depth;
        if (!open.contains("g" + gr.id)) return y;
        y = numRows(y, Keyframes.group(gr), false);
        if (!members || depth > 16) return y;                           // a ring (the reader breaks them) ends
        for (Group7 h : d.groups) if (h != gr && h.p == gr.id) y = groupLayer(y, h, depth + 1, one, true);
        for (Lane7 l : d.lanes) if (l.g == gr.id) y = laneLayer(y, l, depth + 1, one);
        return y;
    }

    /** @return whether a layer called {@code label} (or {@code id}) is listed: 只看选中 and 搜索轨道 */
    private boolean passes(String label, String id, boolean selected) {
        if (onlySel && !selected) return false;
        String f = find.trim().toLowerCase(Locale.ROOT);
        return f.isEmpty() || label.toLowerCase(Locale.ROOT).contains(f) || id.equals(f) || id.startsWith(f);
    }

    private float layer(float y, int obj, int id, String layer, String name, String right) {
        Row r = new Row();
        r.type = LAYER;
        r.obj = obj;
        r.id = id;
        r.layer = layer;
        r.name = name;
        r.right = right;
        r.y = y;
        rows.add(r);
        return y + RH;
    }

    private Row sub(int type, float y) {
        Row up = rows.get(rows.size() - 1);
        Row r = new Row();
        r.type = type;
        r.obj = up.obj;
        r.id = up.id;
        r.depth = up.depth;
        r.layer = up.layer;
        r.y = y;
        rows.add(r);
        return r;
    }

    private float keyRows(float y) {
        for (int i = 0; i < 2; i++) {
            Row r = sub(KEYS, y);
            r.sky = i == 0;
            r.name = r.sky ? "天面键" : "地面键";
            y += RH;
        }
        return y;
    }

    /** A row per group that moves, then 「＋ 加一行」 for the others (not for a key's own). */
    private float numRows(float y, Keyframes.Group[] gs, boolean key) {
        ArrayList<Keyframes.Group> rest = new ArrayList<>();
        for (Keyframes.Group gr : gs) {
            if (Keyframes.points(gr).length == 0) {
                rest.add(gr);
                continue;
            }
            Row r = sub(NUM, y);
            r.grp = gr;
            r.keyGroup = key;
            r.name = key ? "键 · " + gr.name : gr.name;
            y += RH;
        }
        if (key || rest.isEmpty()) return y;
        Row r = sub(ADD, y);
        r.addable = rest.toArray(new Keyframes.Group[0]);
        return y + RH;
    }

    private ArrayList<Doc.DKey> keys(Row r) {
        if (r.obj == FREE) return free;
        ArrayList<Doc.DKey> l = byLane.get(r.id);
        return l != null ? l : EMPTY;
    }

    private static final ArrayList<Doc.DKey> EMPTY = new ArrayList<>();

    /** Keyframes of every layer (without life), and of all of them together for 总览. */
    private void cache() {
        Session s = ed.s;
        if (s.doc == cDoc && s.version == cVer) return;
        cDoc = s.doc;
        cVer = s.version;
        cPts.clear();
        ArrayList<float[]> all = new ArrayList<>();
        float[] p = Keyframes.points(Keyframes.camera(cDoc));
        cPts.put("cam", p);
        all.add(p);
        for (Lane7 l : cDoc.lanes) {
            p = Keyframes.points(Keyframes.lane(l));
            cPts.put("l" + l.id, p);
            all.add(p);
        }
        for (Doc.DVol v : cDoc.vols) {
            p = Keyframes.points(Keyframes.volume(v));
            cPts.put("v" + v.uid, p);
            all.add(p);
        }
        cCount.clear();
        for (Group7 gr : cDoc.groups) {
            p = Keyframes.points(Keyframes.group(gr));
            cPts.put("g" + gr.id, p);
            all.add(p);
            cCount.put(gr.id, cDoc.laneCount(gr.id));
        }
        int n = 0;
        for (float[] a : all) n += a.length;
        float[] b = new float[n];
        n = 0;
        for (float[] a : all) {
            System.arraycopy(a, 0, b, n, a.length);
            n += a.length;
        }
        Arrays.sort(b);
        int m = 0;
        for (int i = 0; i < n; i++) if (m == 0 || b[i] - b[m - 1] >= Keyframes.EPS) b[m++] = b[i];
        cAll = Arrays.copyOf(b, m);
    }

    // ================================================================== selection

    /** A thing picked anywhere (the preview, its card, the checks) opens its layer and scrolls to it. */
    private void follow() {
        Session s = ed.s;
        Doc.DKey k = s.single();
        int key = k != null ? k.uid : 0, vol = ed.insp.tab == Inspector.VOLUME ? s.volSel : 0;
        boolean cam = ed.insp.tab == Inspector.CAMERA;
        String want = null;
        if (s.laneSel != seenLane && s.laneSel != Session.NO_LANE) want = "l" + s.laneSel;
        if (key != seenKey && k != null) {
            int l = k.lane();
            want = s.doc.lane(l) != null ? "l" + l : "free";
        }
        if (vol != seenVol && vol != 0) want = "v" + vol;
        if (cam && !seenCam) want = "cam";
        if (s.groupSel != seenGroup && s.groupSel != 0) want = "g" + s.groupSel;
        quiet();
        if (want != null) show(want);
    }

    /** Opens layer {@code want} and the groups it is in, and scrolls it into view. */
    private void show(String want) {
        Doc d = ed.s.doc;
        int g = 0;
        if (want.startsWith("l") && d.lane(Integer.parseInt(want.substring(1))) != null) {
            g = d.lane(Integer.parseInt(want.substring(1))).g;
        } else if (want.startsWith("g")) g = d.parentOf(Integer.parseInt(want.substring(1)));
        for (int i = 0; i <= d.groups.size() && d.group(g) != null; i++) {
            open.add("g" + g);
            g = d.parentOf(g);
        }
        open.add(want);
        reveal = want;
    }

    /** Opens the layer of what the 关键帧 button keys and scrolls it into view (the button may have added rows to it). */
    void revealSelection() {
        Session s = ed.s;
        Doc.DKey k = s.single();
        String want = null;
        if (ed.insp.tab == Inspector.CAMERA) want = "cam";
        else if (ed.insp.tab == Inspector.VOLUME && s.volSel != 0) want = "v" + s.volSel;
        else if (ed.insp.tab == Inspector.NOTE && k != null) {
            want = s.doc.lane(k.lane()) != null ? "l" + k.lane() : "free";
        }
        else if (ed.insp.tab == Inspector.SCENE && s.groupSel != 0) want = "g" + s.groupSel;
        else if (s.laneSel != Session.NO_LANE) want = "l" + s.laneSel;
        if (want == null) return;
        show(want);
    }

    /** What is selected now counts as seen: a pick made here does not scroll the list. */
    private void quiet() {
        Session s = ed.s;
        Doc.DKey k = s.single();
        seenLane = s.laneSel;
        seenKey = k != null ? k.uid : 0;
        seenVol = ed.insp.tab == Inspector.VOLUME ? s.volSel : 0;
        seenCam = ed.insp.tab == Inspector.CAMERA;
        seenGroup = s.groupSel;
    }

    /** Picks row r's thing (its lane, veil or curtain, the camera; a key's own numbers: the 音符 page). */
    private void pick(Row r) {
        Session s = ed.s;
        if (r.keyGroup) ed.insp.tab = Inspector.NOTE;
        else if (r.obj == LANE) {
            ed.pickLane(r.id, ed.adding() && r.type == LAYER);
            if (!ed.adding()) s.sel.clear();
        } else if (r.obj == GROUP) {
            ed.pickGroup(r.id, ed.adding() && r.type == LAYER);
        } else if (r.obj == VOL) {
            s.volSel = r.id;
            ed.insp.tab = Inspector.VOLUME;
        } else if (r.obj == CAM) ed.insp.tab = Inspector.CAMERA;
        quiet();
    }

    private boolean selected(Row r) {
        Session s = ed.s;
        if (r.obj == LANE) return s.laneSel == r.id || s.pickLanes.contains(r.id);
        if (r.obj == GROUP) return s.groupSel == r.id || s.pickGroups.contains(r.id);
        if (r.obj == VOL) return ed.insp.tab == Inspector.VOLUME && s.volSel == r.id;
        return r.obj == CAM && ed.insp.tab == Inspector.CAMERA;
    }

    // ================================================================== drawing

    private void overview() {
        float y = EditorScreen.R_ROWS, x0 = EditorScreen.TX, w = EditorScreen.TW;
        g.rect(64f, y, EditorScreen.TR - 64f, RH, Gfx.ITEM, 0.6f);
        g.text("总览", 74f, y + RH * 0.5f, 13, Gfx.INK2);
        im.pushClip(x0, y, w, RH);
        for (Doc.DKey k : ed.s.doc.keys) {
            float j = k.judge();
            if (!Float.isFinite(j) || ed.hiddenFake(k)) continue;
            tick(k, j, y);
        }
        float last = -99f;
        for (float p : cAll) {
            float x = ed.bx(p);
            if (x < x0 - 8f || x > x0 + w + 8f || x - last < 2f) continue;
            last = x;
            diamond(x, y + RH * 0.5f, DIA * 0.8f, KF, 0.9f);
        }
        im.popClip();
        g.line(64f, y + RH, EditorScreen.TR, y + RH, 1f, Gfx.LINE);
    }

    /** A key's tick: a sky key high in the row, a ground key low; a long one goes on to its end. */
    private void tick(Doc.DKey k, float j, float y) {
        float x = ed.bx(j), xe = k.k.kind.hasHeight() ? ed.bx(k.end()) : x;
        if (xe < EditorScreen.TX - 4f || x > EditorScreen.TR + 4f) return;
        Color c = ed.keyColour(k.k);
        float t = RH * 0.28f;
        float y0 = k.k.sky ? y + 2f : y + RH - 2f - t;
        g.rect(x - 1f, y0, 2f, t, c, 0.85f);
        if (xe > x + 1f) g.rect(x, k.k.sky ? y0 : y0 + t - 2f, xe - x, 2f, c, 0.5f);
    }

    private void diamond(float x, float y, float r, Color c, float a) {
        g.tri(x - r, y, x, y - r, x + r, y, c, a);
        g.tri(x - r, y, x, y + r, x + r, y, c, a);
    }

    /** The name column of row r. */
    private void name(Row r) {
        float y = r.y, cy = y + RH * 0.5f, right = EditorScreen.TX - 10f, in = Math.min(r.depth, 6) * 12f;
        if (r.type == LAYER) {
            boolean on = selected(r), op = open.contains(r.layer);
            g.rect(64f, y, EditorScreen.TR - 64f, RH, on ? Gfx.SEL : Gfx.ITEM, on ? 0.7f : 0.45f);
            if (on) g.rect(64f, y, 3f, RH, Gfx.GND);
            if (op) g.tri(70f + in, cy - 3f, 80f + in, cy - 3f, 75f + in, cy + 3f, Gfx.INK2, 1f);
            else g.tri(72f + in, cy - 5f, 72f + in, cy + 5f, 78f + in, cy, Gfx.INK2, 1f);
            float rw = r.right.isEmpty() ? 0f : g.text(r.right, right, cy, 11, Gfx.INK3, 2) + 8f;
            float tag = r.obj == GROUP ? 26f : 0f;
            String nm = g.fit(r.name, 13, right - rw - 88f - in - tag);
            float nw = g.text(nm, 88f + in, cy, 13, on ? Gfx.GND : Gfx.INK1);
            if (tag > 0f) {                                             // 「组」 after a group's name
                g.box(88f + in + nw + 6f, cy - 8f, 18f, 16f, 3f, Gfx.BTN, Gfx.BTN_LINE);
                g.text("组", 88f + in + nw + 15f, cy, 10, Gfx.INK2, 1);
            }
        } else if (r.type == KEYS) {
            g.text(r.name, 100f + in, cy, 12, r.sky ? UP_INK : Gfx.GND);
        } else if (r.type == NUM) {
            g.text(g.fit(r.name, 12, right - 100f - in), 100f + in, cy, 12, r.keyGroup ? KF : Gfx.INK2);
        } else {
            float w = EditorScreen.TX - 8f - 96f - in;
            String[] items = new String[r.addable.length];
            for (int i = 0; i < items.length; i++) items[i] = r.addable[i].name;
            int k = im.dropdown(new Im.Pair("tl-add-" + r.layer, 0), 96f + in, y + 2f, w, RH - 4f,
                TAB || r.depth > 0 ? "＋ 加一行" : r.obj == GROUP ? "＋ 加一行（缩放、透明度…）" : "＋ 加一行（透明度、天面高度…）",
                items, -1);
            if (k >= 0 && k < r.addable.length) addRow(r, r.addable[k]);
        }
        g.line(64f, y + RH, EditorScreen.TR, y + RH, 1f, Gfx.LINE2, r.type == LAYER ? 1f : 0.5f);
    }

    /** 「＋ 加一行」 picked: a keyframe at the playhead on that group, so its row shows. */
    private void addRow(Row r, Keyframes.Group gr) {
        Session s = ed.s;
        float t = ed.snap(ed.beat);
        s.mark();
        boolean did = Keyframes.add(gr, t);
        s.changed(false);
        if (!did) im.toast("「" + gr.name + "」在第 " + Im.fmt(t) + " 拍加不了关键帧（它正在出现或消失）");
        pick(r);
    }

    /** The timeline part of row r. */
    private void track(Row r) {
        float y = r.y, cy = y + RH * 0.5f;
        if (r.type == LAYER) {
            lifeBar(r, cy);
            if (r.obj == LANE || r.obj == FREE) {
                for (Doc.DKey k : keys(r)) {
                    float j = k.judge();
                    if (Float.isFinite(j)) tick(k, j, y);
                }
            }
            float[] p = cPts.get(r.layer);
            if (p != null) {
                float last = -99f;
                for (float b : p) {
                    float x = ed.bx(b);
                    if (x - last < 2f || x < EditorScreen.TX - 8f || x > EditorScreen.TR + 8f) continue;
                    last = x;
                    diamond(x, cy, DIA * 0.8f, KF, 0.9f);
                }
            }
        } else if (r.type == KEYS) {
            for (Doc.DKey k : keys(r)) if (k.k.sky == r.sky) mark(k, y);
        } else if (r.type == NUM) {
            float[] p = Keyframes.points(r.grp);
            boolean lit = stripOn() && r.layer.equals(stripLayer) && r.grp.name.equals(stripName)
                && r.keyGroup == stripKey;
            for (int i = 0; i + 1 < p.length; i++) {
                if (!Keyframes.covered(r.grp, p[i], p[i + 1])) continue;
                float x0 = ed.bx(p[i]) + DIA + 1f, x1 = ed.bx(p[i + 1]) - DIA - 1f;
                if (x1 - x0 < 1f || x1 < EditorScreen.TX || x0 > EditorScreen.TR) continue;
                boolean on = lit && Math.abs(p[i] - barA) < Keyframes.EPS && Math.abs(p[i + 1] - barB) < Keyframes.EPS;
                g.rrect(x0, cy - RH * 0.22f, x1 - x0, RH * 0.44f, 3f, Gfx.FX, on ? 0.95f : 0.5f);
                if (on) g.stroke(x0 - 1f, cy - RH * 0.22f - 1f, x1 - x0 + 2f, RH * 0.44f + 2f, 3f, 1.5f, Color.WHITE);
            }
            float now = ed.snap(ed.beat), judge = Keyframes.judge(r.grp);
            for (float b : p) {
                float x = ed.bx(b);
                if (x < EditorScreen.TX - 8f || x > EditorScreen.TR + 8f) continue;
                boolean fixed = Math.abs(b - judge) < Keyframes.EPS;
                diamond(x, cy, DIA, fixed ? Gfx.INK2 : KF, 1f);
                if (Math.abs(b - now) < Keyframes.EPS) g.circle(x, cy, DIA + 3f, 1.5f, Color.WHITE);
            }
        }
    }

    /** A layer's life: the bar from its 出现 to the end of its 消失, a handle at each that is set. */
    private void lifeBar(Row r, float cy) {
        float[] lf = life(r);
        if (lf == null) return;
        boolean on = selected(r);
        float x0 = Float.isNaN(lf[0]) ? EditorScreen.TX - 10f : ed.bx(lf[0]);
        float x1 = Float.isNaN(lf[3]) ? EditorScreen.TR + 10f : ed.bx(lf[3]);
        if (x1 < EditorScreen.TX || x0 > EditorScreen.TR) return;
        g.rrect(x0, cy - 3f, Math.max(2f, x1 - x0), 6f, 3f, on ? Gfx.WAVE : LIFE, on ? 0.8f : 1f);
        if (!Float.isNaN(lf[0])) g.rect(x0 - 1.5f, cy - 7f, 3f, 14f, Gfx.INK2);
        if (!Float.isNaN(lf[2])) g.rect(ed.bx(lf[2]) - 1.5f, cy - 7f, 3f, 14f, Gfx.INK2);
    }

    /**
     * @return row r's life in beats: {出现 start, 出现 end, 消失 start, 消失 end}, NaN where it is there from the start
     *         or stays; null for a layer without one (the camera, the keys on no lane)
     */
    private float[] life(Row r) {
        Seg a, v;
        if (r.obj == LANE) {
            Lane7 l = lanes.get(r.id);
            if (l == null) return null;
            a = Doc.appearSeg(l);
            v = Doc.vanishSeg(l);
        } else if (r.obj == VOL) {
            Doc.DVol dv = ed.s.doc.volByUid(r.id);
            if (dv == null) return null;
            a = Float.isNaN(Doc.appearBeat(dv.v)) ? null : Doc.appearSeg(dv.v.V.x);
            v = Float.isNaN(Doc.vanishBeat(dv.v)) ? null : Doc.vanishSeg(dv.v.V.x);
        } else if (r.obj == GROUP) {
            a = v = null;                                               // a group is there all along
        } else return null;
        return new float[]{a != null ? a.ti : Float.NaN, a != null ? Math.max(a.ti, a.tf) : Float.NaN,
            v != null ? v.ti : Float.NaN, v != null ? Math.max(v.ti, v.tf) : Float.NaN};
    }

    /** A key in its row (as the old 天面 / 底面 rows): a wave or tide fills the row over its time. */
    private void mark(Doc.DKey k, float y) {
        float j = k.judge();
        if (!Float.isFinite(j)) return;
        float x = ed.bx(j), xe = k.k.kind.hasHeight() ? ed.bx(k.end()) : x;
        if (xe < EditorScreen.TX - 8f || x > EditorScreen.TR + 8f) return;
        boolean sel = ed.s.sel.contains(k.uid);
        Color c = ed.keyColour(k.k);
        if (k.k.kind.spans()) {
            float t = y + 3f, h = RH - 6f;
            if (xe > x) g.rect(x, t, xe - x, h, c, 0.25f);
            g.rect(x - 1f, t, 2f, h, c);
            if (sel) g.stroke(x - 3f, t - 1f, xe - x + 6f, h + 2f, 2f, 1.5f, Color.WHITE);
            return;
        }
        float cy = y + RH * 0.5f;
        if (xe > x) g.rect(x, cy - 1.5f, xe - x, 3f, c, 0.85f);
        if (k.k.kind == KeyKind.MINE || k.k.kind == KeyKind.STREAM_MINE) g.stroke(x - 3f, cy - 3f, 6f, 6f, 1f, 1.3f, c);
        else if (k.k.kind == KeyKind.DRAG) diamond(x, cy, 4f, c, 1f);
        else g.rect(x - 2.5f, cy - 2.5f, 5f, 5f, c);
        if (sel) g.stroke(x - 5f, cy - 5f, xe - x + 10f, 10f, 2f, 1.5f, Color.WHITE);
    }

    // ================================================================== input

    private Row rowAt(float my) {
        if (my < top() || my >= EditorScreen.TL_BOTTOM) return null;
        for (Row r : rows) if (my >= r.y && my < r.y + RH) return r;
        return null;
    }

    /** Wheel over the names, or Shift + wheel: the list scrolls. @return taken */
    boolean wheel() {
        if (im.wheel == 0f || im.modalOpen()) return false;
        float t0 = top();
        if (!im.hover(64f, t0, EditorScreen.TR - 64f, EditorScreen.TL_BOTTOM - t0)) return false;
        if (!im.shift && im.mx >= EditorScreen.TX) return false;
        scroll += im.wheel * RH * 2f;
        return true;
    }

    private void input() {
        if (im.modalOpen()) return;
        wheel();
        float tx = EditorScreen.TX, y0 = EditorScreen.R_ROWS;
        if (drag == D_NONE) hover();
        if (im.pressed && !im.rightPressed && drag == D_NONE && !im.popupOpen() && im.active == null) {
            if (im.hover(tx, y0, EditorScreen.TW, RH)) drag = D_SEEK;
            else {
                float h = EditorScreen.TL_BOTTOM - top();
                Row r = im.hover(64f, top(), EditorScreen.TR - 64f, h) ? rowAt(im.my) : null;
                if (r != null) press(r, im.mx < tx);
                else if (im.hover(64f, top(), tx - 64f, EditorScreen.TL_BOTTOM - top())) {
                    drag = D_NAME;                                     // under the last row: the list scrolls
                    dRow = null;
                    dScroll = scroll;
                }
            }
        }
        if (!im.down) return;
        float moved = Math.abs(im.mx - im.pressX);
        switch (drag) {
            case D_SEEK: {
                float b = (float) ed.beatAtX(Math.max(tx, Math.min(EditorScreen.TR, im.mx)));
                ed.seekBeat(im.shift || ed.pb.playing() ? b : ed.snap(b));
                break;
            }
            case D_NAME:
                if (Math.abs(im.my - im.pressY) > 6f * TOUCH) {
                    drag = D_SCROLL;
                    dScroll = scroll;
                }
                break;
            case D_SCROLL:
                scroll = dScroll - (im.my - im.pressY);
                break;
            case D_KEY:
                if (moved > 4f) {
                    drag = D_KEYS;
                    dKey = null;
                    dApplied = 0f;
                    dSel.clear();
                    dSel.addAll(ed.s.selected());
                }
                break;
            case D_KEYS: {
                float by = delta();
                if (by != dApplied) {
                    mark();
                    for (Doc.DKey k : dSel) Doc.shiftKey(k.k, by - dApplied);
                    dApplied = by;
                    ed.s.doc.sortKeys();
                    ed.s.changed(true);
                }
                break;
            }
            case D_EMPTY:
                if (moved > 4f || Math.abs(im.my - im.pressY) > 4f) drag = D_BOX;
                break;
            case D_DIA:
                if (!far && moved <= 3f) break;
                far = true;
                if (dLo > dHi) {
                    im.toast("判定拍在音符页改（或者在键那一行拖键）");
                    drag = D_NONE;
                    break;
                }
                float to = (float) ed.beatAtX(im.mx);
                to = im.alt || im.shift ? EditorScreen.round3(to) : ed.snap(to);
                to = Math.max(dLo, Math.min(dHi, to));
                if (Math.abs(to - dAt) >= Keyframes.EPS) {
                    mark();
                    Keyframes.retime(dRow.grp, dAt, to);
                    dAt = to;
                    ed.s.changed(true);
                }
                break;
            case D_IN:
            case D_OUT:
            case D_BAR:
                if (!far && moved <= 4f) break;
                far = true;
                life(Math.max(dLo, Math.min(dHi, delta())));
                break;
            default:
        }
    }

    /** @return beats moved since the press, on the snap (Alt: as it is) */
    private float delta() {
        float d = (float) (ed.beatAtX(im.mx) - ed.beatAtX(im.pressX));
        return im.alt ? EditorScreen.round3(d) : ed.snapDelta(d);
    }

    private void hover() {
        if (!im.hover(EditorScreen.TX, top(), EditorScreen.TW, EditorScreen.TL_BOTTOM - top())) return;
        Row r = rowAt(im.my);
        if (r == null) return;
        if (r.type == NUM && (diaAt(r, im.mx) != null || barAt(r, im.mx) != null)) im.cursor(Cursor.SystemCursor.Hand);
        if (r.type == LAYER) {
            float[] lf = life(r);
            if (lf != null && (near(lf[0]) || near(lf[2]))) im.cursor(Cursor.SystemCursor.HorizontalResize);
        }
    }

    private boolean near(float b) {
        return !Float.isNaN(b) && Math.abs(im.mx - ed.bx(b)) <= 6f * TOUCH;
    }

    /** @return the keyframe of number row r under x, or null */
    private Float diaAt(Row r, float x) {
        float[] p = Keyframes.points(r.grp);
        Float best = null;
        float bd = DIA + 3f * TOUCH;
        for (float b : p) {
            float d = Math.abs(ed.bx(b) - x);
            if (d <= bd) {
                bd = d;
                best = b;
            }
        }
        return best;
    }

    /** @return the bar (segment) of number row r under x: {from, to}, or null */
    private float[] barAt(Row r, float x) {
        float[] p = Keyframes.points(r.grp);
        for (int i = 0; i + 1 < p.length; i++) {
            if (x > ed.bx(p[i]) && x < ed.bx(p[i + 1]) && Keyframes.covered(r.grp, p[i], p[i + 1])) {
                return new float[]{p[i], p[i + 1]};
            }
        }
        return null;
    }

    private void press(Row r, boolean names) {
        Session s = ed.s;
        dRow = r;
        marked = false;
        far = false;
        dApplied = 0f;
        if (names) {
            if (r.type == ADD) return;
            drag = D_NAME;
            dScroll = scroll;
            return;
        }
        if (r.type == LAYER) {
            float[] lf = life(r);
            dLo = Float.NEGATIVE_INFINITY;
            dHi = Float.POSITIVE_INFINITY;
            if (lf != null && near(lf[0])) {
                drag = D_IN;
                limitIn(r, lf);
            } else if (lf != null && near(lf[2])) {
                drag = D_OUT;
                limitOut(r, lf);
            } else if (lf != null && !Float.isNaN(lf[0]) && im.mx >= ed.bx(lf[0])
                && (Float.isNaN(lf[3]) || im.mx <= ed.bx(lf[3]))) drag = D_BAR;   // 操作 G needs a 出现 to move
            else drag = D_NAME;
            pick(r);
        } else if (r.type == KEYS) {
            Doc.DKey hit = markerAt(r, im.mx);
            if (hit != null) {
                boolean was = s.sel.contains(hit.uid);
                if (ed.adding()) s.select(hit, true);
                else if (!was) s.select(hit, false);
                ed.insp.tab = Inspector.NOTE;
                quiet();
                drag = D_KEY;
                dKey = was && !ed.adding() ? hit : null;
            } else drag = D_EMPTY;
        } else if (r.type == NUM) {
            Float d = diaAt(r, im.mx);
            if (d != null) {
                drag = D_DIA;
                dAt = d;
                float[] all = Keyframes.points(true, r.grp);
                float lo = Float.NEGATIVE_INFINITY, hi = Float.POSITIVE_INFINITY, gap = 1f / 64f;
                for (float b : all) {
                    if (b < d - Keyframes.EPS) lo = b + gap;
                    else if (b > d + Keyframes.EPS && hi == Float.POSITIVE_INFINITY) hi = b - gap;
                }
                if (Math.abs(d - Keyframes.judge(r.grp)) < Keyframes.EPS) {
                    lo = 1f;
                    hi = 0f;                                            // the judge beat does not move here
                }
                dLo = lo;
                dHi = hi;
                return;
            }
            float[] bar = barAt(r, im.mx);
            if (bar != null) {
                pick(r);
                ed.insp.openFn(r.grp.ids[0]);
                quiet();
                stripLayer = r.layer;
                stripName = r.grp.name;
                stripKey = r.keyGroup;
                barA = bar[0];
                barB = bar[1];
                drag = D_NONE;
                return;
            }
            drag = D_SEEK;
        }
    }

    /** 出现 may move until it would run into the first other segment of its width, or its 消失. */
    private void limitIn(Row r, float[] lf) {
        float hi = Float.POSITIVE_INFINITY;
        for (Fn f : lifeFns(r)) {
            Seg a = Doc.appearSeg(f);
            for (Seg sg : f.segs) if (sg != a) hi = Math.min(hi, sg.ti);
        }
        dLo = Float.NEGATIVE_INFINITY;
        dHi = hi - lf[1];
    }

    /** 消失 may move back until it would run into the last other segment of its width, or its 出现. */
    private void limitOut(Row r, float[] lf) {
        float lo = Float.NEGATIVE_INFINITY;
        for (Fn f : lifeFns(r)) {
            Seg v = Doc.vanishSeg(f);
            for (Seg sg : f.segs) if (sg != v) lo = Math.max(lo, Math.max(sg.ti, sg.tf));
        }
        dLo = lo - lf[2];
        dHi = Float.POSITIVE_INFINITY;
    }

    /** @return the numbers holding row r's life: a lane's w, a volume's V x, y and z */
    private Fn[] lifeFns(Row r) {
        if (r.obj == LANE) {
            Lane7 l = lanes.get(r.id);
            return l != null ? new Fn[]{l.w} : new Fn[0];
        }
        Doc.DVol v = ed.s.doc.volByUid(r.id);
        return v != null ? new Fn[]{v.v.V.x, v.v.V.y, v.v.V.z} : new Fn[0];
    }

    /** Moves the dragged 出现, 消失 or whole life (操作 G: the lane with its keys) to {@code by} beats from the press. */
    private void life(float by) {
        if (by == dApplied) return;
        float d = by - dApplied;
        mark();
        Session s = ed.s;
        if (drag == D_BAR) {
            if (dRow.obj == LANE) {
                Lane7 l = s.doc.lane(dRow.id);
                if (l != null) s.doc.moveLane(l, d);
            } else {
                Doc.DVol v = s.doc.volByUid(dRow.id);
                if (v != null) for (Fn f : Doc.fns(v.v)) Doc.shift(f, d);
            }
        } else {
            for (Fn f : lifeFns(dRow)) {
                Seg sg = drag == D_IN ? Doc.appearSeg(f) : Doc.vanishSeg(f);
                if (sg == null) continue;
                sg.ti += d;
                sg.tf += d;
                f.sort();
            }
        }
        dApplied = by;
        s.changed(true);
    }

    private void mark() {
        if (marked) return;
        marked = true;
        ed.s.mark();
    }

    private Doc.DKey markerAt(Row r, float mx) {
        Doc.DKey best = null;
        float bd = 7f * TOUCH;
        for (Doc.DKey k : keys(r)) {
            float j = k.judge();
            if (k.k.sky != r.sky || !Float.isFinite(j)) continue;
            float x = ed.bx(j), xe = k.k.kind.hasHeight() ? ed.bx(k.end()) : x;
            float dx = mx < x ? x - mx : mx > xe ? mx - xe : 0f;
            float dd = dx + (mx >= x - 4f && mx <= x + 4f ? 0f : 1.5f);
            if (dd < bd) {
                bd = dd;
                best = k;
            }
        }
        return best;
    }

    /** At the end of a press: what a click (no drag) does, a box's selection, the undo step of a drag. */
    void endDrag() {
        if (drag == D_NONE) return;
        Session s = ed.s;
        boolean click = im.released && !far && Math.abs(im.mx - im.pressX) <= 4f && Math.abs(im.my - im.pressY) <= 4f;
        if (drag == D_BOX) commitBox();
        else if (drag == D_KEY && dKey != null && !ed.adding() && im.released) s.select(dKey, false);
        else if (drag == D_EMPTY && click) place();
        else if (drag == D_DIA && click) ed.seekBeat(dAt);
        else if (drag == D_NAME && click && dRow != null) nameClick(dRow);
        if (marked) s.changed(false);
        quiet();
        drag = D_NONE;
        marked = false;
        dKey = null;
    }

    private void nameClick(Row r) {
        if (r.type == LAYER) {
            boolean tri = im.pressX < 86f;
            if (tri && open.contains(r.layer)) open.remove(r.layer);
            else open.add(r.layer);
            if (!tri) pick(r);
        } else if (r.type == NUM) {
            pick(r);
            ed.insp.openFn(r.grp.ids[0]);
            quiet();
        }
    }

    /** A click on empty space in a key row: a key of the tool there, at the snapped beat (选择: the selection goes). */
    private void place() {
        Session s = ed.s;
        KeyKind kind = ed.tool >= 0 && ed.tool < EditorScreen.TOOL_KIND.length ? EditorScreen.TOOL_KIND[ed.tool] : null;
        if (kind == null) {
            if (!ed.adding()) s.sel.clear();
            return;
        }
        if (dRow.obj != LANE) {
            im.toast("无轨键这一行放不了键：在一条轨道的行里点");
            return;
        }
        float b = (float) ed.beatAtX(im.pressX);
        b = im.alt ? EditorScreen.round3(b) : ed.snap(b);
        s.mark();
        float len = kind.hasHeight() ? EditorScreen.LONG_DEFAULT : 0f;
        Doc.DKey k = new Doc.DKey(Doc.newKey(kind, dRow.id, dRow.sky, b, len));
        s.doc.keys.add(k);
        s.doc.sortKeys();
        s.sel.clear();
        s.sel.add(k.uid);
        ed.insp.tab = Inspector.NOTE;
        s.changed(false);
        quiet();
    }

    private void commitBox() {
        Session s = ed.s;
        float x0 = Math.min(im.pressX, im.mx), x1 = Math.max(im.pressX, im.mx);
        float y0 = Math.min(im.pressY, im.my), y1 = Math.max(im.pressY, im.my);
        if (x1 - x0 < 3f && y1 - y0 < 3f) return;
        if (!ed.adding()) s.sel.clear();
        for (Row r : rows) {
            if (r.type != KEYS) continue;
            float cy = r.y + RH * 0.5f;
            boolean mid = cy >= y0 && cy <= y1, any = y0 <= r.y + RH && y1 >= r.y;
            for (Doc.DKey k : keys(r)) {
                float j = k.judge();
                if (k.k.sky != r.sky || !Float.isFinite(j)) continue;
                float x = ed.bx(j);
                if (x < x0 || x > x1 || !(k.k.kind.spans() ? any : mid)) continue;
                if (s.sel.add(k.uid)) ed.insp.tab = Inspector.NOTE;
            }
        }
        quiet();
    }

    // ================================================================== the ease strip

    boolean stripOn() {
        return !Float.isNaN(barA);
    }

    void closeStrip() {
        barA = barB = Float.NaN;
        stripShown = false;
    }

    /**
     * 缓动条 (SPEC §2): over the picked bar, 「② → ③ 这一段怎么走」, five ways in one click and 「自定义」 for any e
     * above 0; the picked segment of each number of the row gets it.
     */
    private void strip() {
        stripShown = false;
        if (!stripOn()) return;
        Row row = null;
        for (Row r : rows) {
            if (r.type == NUM && r.layer.equals(stripLayer) && r.grp.name.equals(stripName) && r.keyGroup == stripKey) {
                row = r;
            }
        }
        if (row == null || !Keyframes.covered(row.grp, barA, barB)) {
            closeStrip();
            return;
        }
        float mid = (ed.bx(barA) + ed.bx(barB)) * 0.5f;
        sx = Math.max(EditorScreen.TX + 4f, Math.min(EditorScreen.TR - SW - 4f, mid - SW * 0.5f));
        sy = row.y - SH - 6f;
        if (sy < EditorScreen.R_RULER) sy = Math.min(row.y + RH + 6f, EditorScreen.TL_BOTTOM - SH - 2f);
        stripShown = true;
        boolean was = im.lift();
        g.box(sx, sy, SW, SH, 8f, Gfx.BAR, KF);
        float[] p = Keyframes.points(row.grp);
        int ia = 0, ib = 0;
        for (int i = 0; i < p.length; i++) {
            if (Math.abs(p[i] - barA) < Keyframes.EPS) ia = i;
            if (Math.abs(p[i] - barB) < Keyframes.EPS) ib = i;
        }
        float tw = g.text(Keyframes.circled(ia) + " → " + Keyframes.circled(ib) + " 这一段怎么走", sx + 14f, sy + 17f, 13,
            Gfx.INK1);
        String where = row.keyGroup ? "键 · " + row.grp.name : row.grp.name;
        g.text(Im.fmt(barA) + " 拍 → " + Im.fmt(barB) + " 拍 · " + where, sx + 28f + tw, sy + 17f, 11, Gfx.INK3);
        float cur = Keyframes.easeOf(row.grp, barA, barB);
        float tx = sx + 12f, ty = sy + 34f, tws = 60f, ths = 62f;
        for (int i = 0; i < EASES.length; i++) {
            float x = tx + i * (tws + 6f);
            boolean on = Math.abs(cur - EASES[i]) < 1e-3f, hv = im.hover(x, ty, tws, ths);
            g.box(x, ty, tws, ths, 6f, on ? Gfx.ITEM_ON : hv ? Gfx.BTN_HI : Gfx.BTN, on ? Gfx.GND : Gfx.BTN_LINE);
            curve(x + 14f, ty + 7f, tws - 28f, 22f, EASES[i], on ? Gfx.GND : Gfx.INK2);
            g.text(EASE_NAMES[i], x + tws * 0.5f, ty + 41f, 11, Gfx.INK1, 1);
            g.text("e " + Im.fmt(EASES[i]), x + tws * 0.5f, ty + 54f, 10, Gfx.INK3, 1);
            if (hv) im.cursor(Cursor.SystemCursor.Hand);
            if (im.click(new Im.Pair("tl-ease", i), x, ty, tws, ths)) ease(row, EASES[i]);
        }
        float cx = tx + EASES.length * (tws + 6f) + 8f;
        g.text("自定义", cx, ty + 15f, 12, Gfx.INK2);
        im.slash(Float.NaN, null, false);
        float v = im.num("tl-ease-own", cx + 46f, ty + 2f, 74f, 26f, cur, "");
        if (!Float.isNaN(v)) {
            if (v > 0f) ease(row, v);
            else im.toast("e 要大于 0（1 = 匀速）");
        }
        g.text("e：1 = 匀速", cx, ty + 46f, 11, Gfx.INK3);
        im.drop(was);
    }

    /** The curve p^e in a w × h box (its start at the bottom left). */
    private void curve(float x, float y, float w, float h, float e, Color c) {
        float px = x, py = y + h;
        for (int i = 1; i <= 12; i++) {
            float p = i / 12f, nx = x + w * p, ny = y + h - h * (float) Math.pow(p, e);
            g.line(px, py, nx, ny, 2f, c);
            px = nx;
            py = ny;
        }
    }

    private void ease(Row r, float e) {
        Session s = ed.s;
        s.mark();
        Keyframes.ease(r.grp, barA, barB, e);
        s.changed(false);
    }

    // ================================================================== for the self-test

    /**
     * @return the screen point at {@code beat} of row {@code name} (a group's, 天面键 or 地面键; null: the layer's own)
     *         of layer {@code layer}, or null when it is not on screen
     */
    float[] rowAt(String layer, String name, float beat) {
        for (Row r : rows) {
            if (!r.layer.equals(layer)) continue;
            boolean hit = name == null ? r.type == LAYER : name.equals(r.name);
            if (hit && r.y >= top() && r.y + RH <= EditorScreen.TL_BOTTOM) {
                return new float[]{ed.bx(beat), r.y + RH * 0.5f};
            }
        }
        return null;
    }

    /** @return the centre of the ease strip's tile i, or null when it is not open */
    float[] tile(int i) {
        if (!stripShown) return null;
        return new float[]{sx + 12f + i * 66f + 30f, sy + 34f + 31f};
    }

    /** @return how many layers are listed */
    int layerCount() {
        int n = 0;
        for (Row r : rows) if (r.type == LAYER) n++;
        return n;
    }
}
