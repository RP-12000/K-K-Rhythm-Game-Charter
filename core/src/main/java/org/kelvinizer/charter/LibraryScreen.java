package org.kelvinizer.charter;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Cursor;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import org.kelvinizer.library.Difficulty;
import org.kelvinizer.ui.Theme;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;

/**
 * 曲库界面：左边是歌曲列表，右边是选中歌曲的曲绘、信息、试听和四个难度文件。
 * The library screen: the song list on the left; on the right the selected song's
 * jacket, texts, tempo, a 20-second listen, its four difficulty files (create missing ones,
 * optionally as a copy) and the edit / open folder / delete buttons. Importing an audio file
 * creates a new song folder. 练习谱 (practice songs and the tutorial) are listed under their own heading below the
 * singles. A picked jacket opens {@link JacketCrop} (its 16:9 area and circle).
 */
final class LibraryScreen implements CharterApp.View {
    /** The song list and the details panel (narrower on a tablet's 1344 × 840, see {@link Gfx#TABLET}). */
    private static final boolean TAB = Gfx.TABLET;
    private static final float LX = TAB ? 16f : 40f, LW = TAB ? 560f : 920f, LTOP = TAB ? 72f : 92f, ROW = 86f,
        HEAD = 44f, RX = TAB ? 592f : 1000f, RW = TAB ? 736f : 880f, RB = Gfx.H - (TAB ? 16f : 40f);
    /** A finger dragging the song list (tablet): where it went down and the scroll then; NaN = none. */
    private float dragY = Float.NaN, dragScroll;
    private boolean dragging;
    private static final Object DRAG = new Object();
    /** Last difficulty opened per song id. */
    private static final HashMap<String, Difficulty> lastDiff = new HashMap<>();

    private final CharterApp app;
    private final Gfx g;
    private final Im im;
    private final Library lib;
    private Library.Song sel;
    private Difficulty diff;
    private String query = "";
    private float scroll;
    private final Playback listen;
    private Library.Song listening;
    /** The preview window being played ({@link Library.Song#previewStart} … {@link Library.Song#previewEnd}). */
    private double listenFrom, listenTo;
    /** The jacket crop dialog while open (drawn instead of this screen). */
    JacketCrop crop;

    LibraryScreen(CharterApp app, Library.Song select) {
        this.app = app;
        this.g = app.g;
        this.im = app.im;
        this.lib = app.lib;
        listen = new Playback(app.platform);
        if (select != null && lib.songs.contains(select)) select = lib.rescan(select);
        else if (select != null) {
            for (Library.Song s : lib.songs) if (s.id.equals(select.id)) select = lib.rescan(s);
        }
        choose(select != null && lib.songs.contains(select) ? select : lib.songs.isEmpty() ? null : lib.songs.get(0));
    }

    private void choose(Library.Song s) {
        if (s != sel) stopListen();
        sel = s;
        diff = null;
        if (s == null) return;
        Difficulty last = lastDiff.get(s.id);
        if (last != null && s.diffs[last.ordinal()].exists()) diff = last;
        for (int i = Difficulty.count() - 1; i >= 0 && diff == null; i--) {
            if (s.diffs[i].exists()) diff = Difficulty.byOrdinal(i);
        }
        if (diff == null) diff = Difficulty.SHALLOW;
    }

    private ArrayList<Library.Song> visible() {
        ArrayList<Library.Song> out = new ArrayList<>();
        String q = query.trim().toLowerCase(Locale.ROOT);
        for (Library.Song s : lib.songs) {
            if (q.isEmpty() || s.name().toLowerCase(Locale.ROOT).contains(q)
                || s.artist.toLowerCase(Locale.ROOT).contains(q)
                || s.id.toLowerCase(Locale.ROOT).contains(q)) out.add(s);
        }
        return out;
    }

    private static Texture jacket(Library.Song s) {
        if (!s.jacketTried) {
            s.jacketTried = true;
            if (s.jacket != null) {
                try {
                    s.jacketTex = new Texture(s.jacket, true);
                    s.jacketTex.setFilter(Texture.TextureFilter.MipMapLinearLinear, Texture.TextureFilter.Linear);
                } catch (Exception e) {
                    Gdx.app.error("Charter", "jacket " + s.jacket.name() + ": " + e.getMessage());
                }
            }
        }
        return s.jacketTex;
    }

    // ================================================================== frame

    @Override
    public void frame(float dt) {
        listen.update();
        if (listening != null && (!listen.playing() || listen.pos() >= listenTo)) stopListen();
        if (crop != null) {
            if (!crop.frame()) {
                crop.dispose();
                crop = null;
            }
            return;
        }
        ArrayList<Library.Song> list = visible();
        keys(list);
        songList(list);
        if (sel != null) details(sel);
        else {
            g.box(RX, LTOP, RW, RB - LTOP, 12f, Gfx.BAR, Gfx.ITEM_LINE);
            g.text("曲库是空的：点右上角「导入音频」新建一首歌", RX + RW * 0.5f, LTOP + 300f, 16, Gfx.INK2, 1);
        }
        topBar();
    }

    private void keys(ArrayList<Library.Song> list) {
        if (im.modalOpen() || im.textFocus() || list.isEmpty()) return;
        int i = list.indexOf(sel);
        if (im.key(Input.Keys.DOWN)) choose(list.get(Math.min(list.size() - 1, i + 1)));
        if (im.key(Input.Keys.UP)) choose(list.get(Math.max(0, i - 1)));
        if (im.key(Input.Keys.ENTER) && sel != null) edit(sel, diff);
    }

    private void topBar() {
        g.rect(0f, 0f, Gfx.W, 56f, Gfx.BAR);
        g.line(0f, 56f, Gfx.W, 56f, 1f, Gfx.LINE);
        float x = 16f;
        for (char c : "K&K CHARTER".toCharArray()) x += g.text(String.valueOf(c), x, 28f, 16, Gfx.INK1) + 4f;
        float qx = Gfx.W - 542f, ix = Gfx.W - 228f;
        g.text(g.fit("曲库 · " + lib.label(), 12, TAB ? qx - x - 40f : 1100f), x + 14f, 28f, 12, Gfx.INK3);
        String q = im.field("search", qx, TAB ? 11f : 13f, 300f, TAB ? 34f : 30f, query, "", false);
        if (q != null) query = q;
        if (query.isEmpty() && !im.textFocus()) g.text("搜索", qx + 12f, 28f, 13, Gfx.INK3);
        if (im.button("import", ix, TAB ? 9f : 11f, 212f, TAB ? 38f : 34f, "+ 导入音频（mp3 / wav）", Im.PRIMARY, 14)) {
            importAudio();
        }
    }

    private void songList(ArrayList<Library.Song> list) {
        float viewH = Gfx.H - LTOP;
        if (im.hover(LX, 60f, LW, Gfx.H - 60f) && im.wheel != 0f) scroll += im.wheel * 60f;
        int head = list.size();                             // index of the first 练习谱 row (Kelvin 2026-11-02)
        for (int i = 0; i < list.size() && head == list.size(); i++) if (list.get(i).practice) head = i;
        float extra = head < list.size() ? HEAD : 0f;
        scroll = Math.max(0f, Math.min(scroll, Math.max(0f, list.size() * ROW + extra + 20f - viewH)));
        if (TAB) dragList();
        im.pushClip(LX - 4f, 60f, LW + 8f, Gfx.H - 60f);
        if (extra > 0f) {
            float hy = LTOP + head * ROW - scroll;
            g.text("练习谱", LX + 4f, hy + 20f, 15, Gfx.INK2);
            g.rect(LX + 70f, hy + 19f, LW - 74f, 1f, Gfx.LINE);
        }
        for (int i = 0; i < list.size(); i++) {
            Library.Song s = list.get(i);
            float y = LTOP + i * ROW + (i >= head ? extra : 0f) - scroll;
            if (y > Gfx.H || y + 78f < 60f) continue;
            boolean on = s == sel;
            boolean hv = im.hover(LX, y, LW, 78f);
            g.box(LX, y, LW, 78f, 8f, on ? Gfx.ITEM_ON : hv ? Gfx.BTN : Gfx.ITEM, on ? Gfx.GND : Gfx.ITEM_LINE);
            Texture t = jacket(s);
            if (t != null) square(s, t, LX + 21f, y + 14f, 50f);
            else g.grad(LX + 21f, y + 14f, 50f, 50f, 4f, Gfx.c(0x1D5A9A), Gfx.c(0x0E2A5C));
            g.text(g.fit(shown(s), 18, LW - 220f), LX + 87f, y + 29f, 18, Gfx.INK1);
            g.text((s.artist.isEmpty() ? "—" : s.artist) + " · BPM " + (s.bpm > 0f ? Im.fmt(s.bpm) : "?"), LX + 87f,
                y + 51f, 13, Gfx.INK3);
            for (int k = 0; k < Difficulty.count(); k++) {
                float sx = LX + LW - 92f + k * 19f;
                if (s.diffs[k].exists()) g.rrect(sx, y + 33f, 12f, 12f, 2f, Gfx.DIFF[k]);
                else g.stroke(sx, y + 33f, 12f, 12f, 2f, 1f, Gfx.BTN_LINE);
            }
            if (hv) im.cursor(Cursor.SystemCursor.Hand);
            if (im.click(new Im.Pair("song", i), LX, y, LW, 78f)) {
                if (s == sel && im.ctrl) edit(s, diff);
                choose(s);
            }
        }
        if (list.isEmpty() && !lib.songs.isEmpty()) g.text("没有符合「" + query + "」的歌", LX + 20f, LTOP + 30f, 14, Gfx.INK3);
        im.popClip();
    }

    /** Tablet: a finger dragged up or down the list scrolls it (and opens no song). */
    private void dragList() {
        if (im.pressed && im.inside(LX, 60f, LW, Gfx.H - 60f)) {
            dragY = im.my;
            dragScroll = scroll;
            dragging = false;
        }
        if (!im.down) dragY = Float.NaN;
        if (Float.isNaN(dragY)) return;
        if (Math.abs(im.my - dragY) > 10f) dragging = true;
        if (dragging) {
            scroll = dragScroll - (im.my - dragY);
            im.active = DRAG;
        }
    }

    private void details(Library.Song s) {
        g.box(RX, LTOP, RW, RB - LTOP, 12f, Gfx.BAR, Gfx.ITEM_LINE);
        // jacket and the text column beside it, then the difficulties, the buttons at the bottom
        float pad = TAB ? 24f : 37f, jx = RX + pad, jy = LTOP + pad, js = TAB ? 220f : 340f;
        Texture t = jacket(s);
        boolean jh = im.hover(jx, jy, js, js);
        if (t != null) square(s, t, jx, jy, js);
        else {
            g.grad(jx, jy, js, js, 6f, Gfx.c(0x1A4C8C), Gfx.c(0x0A1E4A));
            g.text("曲绘（点击更换）", jx + js * 0.5f, jy + js * 0.5f, 14, Gfx.INK2, 1);
        }
        if (jh) {
            im.cursor(Cursor.SystemCursor.Hand);
            if (t != null) {
                g.rect(jx, jy + js - 36f, js, 36f, Gfx.BG, 0.7f);
                g.text("点击更换曲绘", jx + js * 0.5f, jy + js - 18f, 13, Gfx.INK1, 1);
            }
        }
        if (im.click("jacket", jx, jy, js, js)) {
            app.platform.pickFile("选择曲绘", new String[]{"png", "jpg", "jpeg"}, f -> jacketPicked(s, f));
        }
        float tx = jx + js + 32f, ty = jy - 129f; // the text column, placed as on the desktop (jy 129)
        g.text(g.fit(shown(s), 34, 440f), tx, ty + 148f, 34, Gfx.INK1);
        g.text(g.fit("曲师 " + nz(s.artist) + " · 画师 " + nz(s.illustrator), 16, 440f), tx, ty + 186f, 16, Gfx.INK2);
        stat(tx, ty, "BPM", s.bpm > 0f ? Im.fmt(s.bpm) : "?");
        stat(tx + 68f, ty, "偏移", Im.fmt(Math.round(s.offsetMs)) + "ms");
        long sec = Math.round(s.durationMs / 1000.0);
        stat(tx + 148f, ty, "时长", s.durationMs > 0f ? sec / 60 + ":" + String.format(Locale.ROOT, "%02d", sec % 60)
            : "?");
        g.text("试听", tx, ty + 296f, 12, Gfx.INK3);
        boolean playing = listening == s;
        if (im.button("listen", tx, ty + 313f, 34f, 34f, "", Im.NORMAL)) {
            if (playing) stopListen();
            else startListen(s);
        }
        g.playIcon(tx + 17f, ty + 330f, 7f, playing, Gfx.INK1);
        float len = (s.previewEnd() - s.previewStart()) / 1000f;
        float p = playing
            ? (float) Math.max(0.0, Math.min(1.0, (listen.pos() - listenFrom) / (listenTo - listenFrom))) : 0f;
        g.rrect(tx + 44f, ty + 329f, 314f, 3f, 1.5f, Gfx.BTN_LINE);
        g.rrect(tx + 44f, ty + 329f, 314f * p, 3f, 1.5f, Gfx.GND);
        g.text(mmss(p * len) + " / " + mmss(len), tx + 368f, ty + 330f, 12, Gfx.INK3);
        if (s.audio == null) g.text("这首歌的文件夹里没有音频", tx, ty + 372f, 12, Gfx.BAD);
        if (s.jacket != null && im.button("frame", tx, ty + 412f, 250f, 34f, "曲绘取景（16:9 区域 / 圆形）", Im.NORMAL,
            13)) frameJacket(s);

        float dy = ty + (TAB ? 470f : 507f), cw = TAB ? (RW - 2f * pad - 11f) * 0.5f : 397f;
        g.text("难度（一个难度一个文件）", jx, dy, 12, Gfx.INK3);
        for (int k = 0; k < Difficulty.count(); k++) {
            float cx = k % 2 == 0 ? jx : jx + cw + 11f, cy = dy + (k < 2 ? 20f : 106f);
            diffCard(s, Difficulty.byOrdinal(k), cx, cy, cw);
        }
        g.text("没有的难度显示「+ 新建」，可选从别的难度复制", jx, dy + 197f, 12, Gfx.INK3);

        boolean has = s.diffs[diff.ordinal()].exists();
        String el = has ? "编辑 " + Theme.difficultyName(diff) : "新建 " + Theme.difficultyName(diff);
        float by = RB - pad - 54f, trashX = RX + RW - pad - 98f, folderX = trashX - 124f;
        if (im.button("edit", jx, by, folderX - 12f - jx, 54f, el, Im.PRIMARY, 20)) {
            if (has) edit(s, diff);
            else create(s, diff);
        }
        if (im.button("folder", folderX, by, 112f, 54f, "打开文件夹", Im.NORMAL, 14)) app.platform.openFolder(s.dir.file());
        if (im.button("trash", trashX, by, 98f, 54f, "删除歌曲", Im.DANGER, 14)) {
            if (s.tutorial || Library.PRACTICE.contains(s.id) && s.practice) {
                im.toast(s.tutorial ? "新手教程不能删除（游戏要用）" : "主线的歌不能删除（游戏要用）");
                return;
            }
            Im.Modal m = new Im.Modal("删除「" + s.name() + "」？", "整个歌曲文件夹会移到 Charter 的 trash 文件夹里，需要时可以拿回来。",
                new String[]{"删除", "取消"}, (b, txt) -> {
                    if (b != 0) return;
                    stopListen();
                    lib.trash(s);
                    choose(lib.songs.isEmpty() ? null : lib.songs.get(0));
                    im.toast("已移到 trash");
                });
            m.danger = 0;
            m.primary = 1;
            im.open(m);
        }
    }

    private void stat(float x, float ty, String label, String value) {
        g.text(label, x, ty + 227f, 12, Gfx.INK3);
        g.text(value, x, ty + 248f, 22, Gfx.INK1);
    }

    private void diffCard(Library.Song s, Difficulty d, float x, float y, float w) {
        Library.Diff df = s.diffs[d.ordinal()];
        boolean on = d == diff, has = df.exists();
        boolean hv = im.hover(x, y, w, 74f);
        if (has) g.box(x, y, w, 74f, 8f, on ? Gfx.ITEM_ON : hv ? Gfx.BTN : Gfx.ITEM, on ? Gfx.GND : Gfx.ITEM_LINE);
        else {
            if (on || hv) g.rrect(x, y, w, 74f, 8f, Gfx.ITEM);
            g.dashedRect(x, y, w, 74f, on ? Gfx.GND : Gfx.BTN_LINE, 1f);
        }
        Color dc = Gfx.DIFF[d.ordinal()];
        String lbl = Theme.difficultyName(d);
        float bw = g.width(lbl, 12) + 18f;
        if (d == Difficulty.ABYSS) {
            g.rrect(x + 17f, y + 27f, bw, 20f, 3f, Gfx.BG);
            g.stroke(x + 17f, y + 27f, bw, 20f, 3f, 1.6f, dc);
            g.text(lbl, x + 17f + bw * 0.5f, y + 37f, 12, Gfx.INK1, 1);
        } else {
            g.rrect(x + 17f, y + 27f, bw, 20f, 3f, dc, has ? 1f : 0.35f);
            g.text(lbl, x + 17f + bw * 0.5f, y + 37f, 12, d == Difficulty.SURFACE ? Gfx.PRI_INK : Color.WHITE, 1);
        }
        float tx = x + 17f + bw + 14f;
        if (has) {
            g.text(df.file.name(), tx, y + 28f, 15, Gfx.INK1);
            String info = df.error != null ? "读取失败：" + df.error
                : "定数 " + (df.constant.isEmpty() ? "?" : df.constant) + " · "
                + (df.notes >= 0 ? df.notes + " 音符" : "…");
            g.text(g.fit(info, 12, w - 167f), tx, y + 47f, 12, df.error != null ? Gfx.BAD : Gfx.INK3);
            g.text("编辑 ›", x + w - 17f, y + 37f, 12, hv ? Gfx.INK1 : Gfx.INK2, 2);
        } else {
            g.text("+ 新建", tx, y + 37f, 15, Gfx.INK3);
        }
        if (hv) im.cursor(Cursor.SystemCursor.Hand);
        if (im.click(new Im.Pair("diff", d.ordinal()), x, y, w, 74f)) {
            if (!has) create(s, d);
            else if (on) edit(s, d);
            diff = d;
        }
    }

    private static String mmss(float sec) {
        int t = (int) sec;
        return t / 60 + ":" + String.format(Locale.ROOT, "%02d", t % 60);
    }

    private static String nz(String s) {
        return s == null || s.isEmpty() ? "Unknown" : s;
    }

    // ================================================================== actions

    private void startListen(Library.Song s) {
        if (s.audio == null) return;
        listen.load(s.audio);
        // the game's window (song.json, else 30% in for 20 s): silent before 0 and past the music's end
        listenFrom = s.previewStart();
        listenTo = s.previewEnd();
        listen.seek(listenFrom);
        listen.play();
        listening = s;
    }

    private void stopListen() {
        if (listening != null) listen.dispose();
        listening = null;
    }

    private void edit(Library.Song s, Difficulty d) {
        try {
            Session ses = Session.open(lib, s, d);
            stopListen();
            lastDiff.put(s.id, d);
            app.show(new EditorScreen(app, ses));
        } catch (Exception e) {
            im.open(new Im.Modal("打不开 " + d.fileName(), String.valueOf(e.getMessage()), new String[]{"好"},
                (b, t) -> { }));
        }
    }

    /** The blank charts a song without any gets at once (Kelvin 2026-10-22). */
    private static final Difficulty[] FIRST = {Difficulty.SURFACE, Difficulty.SHALLOW, Difficulty.DEEP};

    /**
     * Asks how to create a missing difficulty (blank or a copy of an existing one). A song without any chart
     * yet asks for its BPM and gets blank Surface, Shallow and Deep, then opens {@code d}.
     */
    void create(Library.Song s, Difficulty d) {
        Difficulty from = null;
        for (int i = Difficulty.count() - 1; i >= 0 && from == null; i--) {
            if (s.diffs[i].exists()) from = Difficulty.byOrdinal(i);
        }
        final Difficulty src = from;
        String[] buttons = src != null ? new String[]{"空白", "复制 " + Gfx.diffName(src), "取消"} : new String[]{"新建", "取消"};
        Im.Modal m = new Im.Modal(src != null ? "新建 " + Gfx.diffName(d) : "新建谱面", src != null ? "从空白开始，还是复制一个已有难度再改？"
            : "这首歌还没有谱面。填一下 BPM（以后也能在「谱面」页改），会建好 Shallow、Medium、Deep 三个空谱面：", buttons, (b, t) -> {
                if (b < 0 || b == buttons.length - 1) return;
                try {
                    if (src == null) {
                        float bpm = parseBpm(t);
                        if (bpm > 0f) s.bpm = bpm;
                        for (Difficulty f : FIRST) lib.createDiff(s, f, null);
                        if (!s.diffs[d.ordinal()].exists()) lib.createDiff(s, d, null);
                    } else {
                        lib.createDiff(s, d, b == 1 ? src : null);
                    }
                    diff = d;
                    edit(s, d);
                } catch (Exception e) {
                    im.toast("新建失败：" + e.getMessage());
                }
            });
        if (src == null) m.input = s.bpm > 0f ? Im.fmt(s.bpm) : "120";
        m.primary = src != null ? 1 : 0;
        im.open(m);
    }

    private static float parseBpm(String t) {
        try {
            return t == null ? 0f : Float.parseFloat(t.trim());
        } catch (NumberFormatException e) {
            return 0f;
        }
    }

    /**
     * A picked jacket image opens the framing dialog, whose OK copies it as it is (Kelvin 2026-10-21:
     * 「有些曲绘不是16：9就需要我选定区域，圆形部分也要我选定」).
     */
    void jacketPicked(Library.Song s, File f) {
        try {
            crop = new JacketCrop(g, im, lib, s, new Pixmap(Gdx.files.absolute(f.getAbsolutePath())), f);
        } catch (Exception e) {
            im.toast("读不了这张图：" + f.getName());
        }
    }

    /** Opens the framing dialog for the song's current jacket. */
    void frameJacket(Library.Song s) {
        try {
            crop = new JacketCrop(g, im, lib, s, new Pixmap(s.jacket), null);
        } catch (Exception e) {
            im.toast("读不了这张图：" + s.jacket.name());
        }
    }

    /** The jacket as a square: the square around its circle (the game's map hole), centred when none is set. */
    private void square(Library.Song s, Texture t, float x, float y, float side) {
        float[] uv = s.roundUv(t.getWidth(), t.getHeight());
        JacketCrop.round(g, t, x, y, side, uv[0], uv[1], uv[2], uv[3], 0f, 1f);
    }

    private void importAudio() {
        app.platform.pickFile("导入音频", new String[]{"mp3", "wav", "ogg"}, f -> {
            String name = f.getName().contains(".")
                ? f.getName().substring(0, f.getName().lastIndexOf('.')) : f.getName();
            Im.Modal m = new Im.Modal("新歌", "歌曲文件夹的名字（也是曲名，可以以后改曲名）：", new String[]{"导入", "取消"}, (b, t) -> {
                if (b != 0) return;
                try {
                    Library.Song s = lib.importAudio(f, t == null || t.trim().isEmpty() ? name : t.trim());
                    query = "";
                    choose(s);
                    create(s, Difficulty.SURFACE);
                } catch (Exception e) {
                    im.toast("导入失败：" + e.getMessage());
                }
            });
            m.input = name;
            im.open(m);
        });
    }

    @Override
    public void close(Runnable quit) {
        quit.run();
    }

    @Override
    public void dispose() {
        listen.dispose();
        if (crop != null) crop.dispose();
    }

    /** The song's name as listed; the tutorial is marked so it is easy to find. */
    private static String shown(Library.Song s) {
        return s.tutorial ? "新手教程 · " + s.name() : s.name();
    }
}
