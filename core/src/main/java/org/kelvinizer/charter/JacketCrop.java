package org.kelvinizer.charter;

import com.badlogic.gdx.Input;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Cursor;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;

import java.io.File;

/**
 * 曲绘取景：在整张图上框一个 16:9 的区域和一个圆，决定游戏里各处显示图的哪一块；图片本身不裁（Kelvin 2026-10-21）。
 * The jacket framing dialog: over the whole picture the user drags a 16:9 box (what the song card
 * and the result screen show) and a circle (the map holes and the dive transition). The picture is
 * kept whole: the two boxes go into the song folder's song.json as fractions of the picture
 * ({@link Library#setJacketBoxes}), where the game reads them. Opened when a jacket is picked (OK then
 * copies the file as it is) or for the current jacket. Drawn by the library screen in place of itself.
 */
final class JacketCrop {
    /** Image area and side panel (top-left based; the tablet's fit its 1344 × 840, see {@link Gfx#TABLET}). */
    private static final float AX = Gfx.TABLET ? 16f : 40f, AY = Gfx.TABLET ? 72f : 92f, AW = Gfx.TABLET ? 840f : 1300f,
        AH = Gfx.TABLET ? 752f : 948f, PX = Gfx.TABLET ? 872f : 1380f, PW = Gfx.TABLET ? 456f : 500f;
    private static final int NONE = 0, MOVE = 1, SIZE = 2;
    /** The box being edited: the 16:9 area or the circle. */
    static final int WIDE = 0, ROUND = 1;
    /** Height of the 16:9 box per unit of width. */
    private static final float WIDE_K = 9f / 16f;

    private final Gfx g;
    private final Im im;
    private final Library lib;
    final Library.Song song;
    /** The picked file, copied as the jacket on OK; null when framing the current jacket. */
    private final File picked;
    private final Pixmap src;
    private final Texture tex;
    final int w, h;
    /** The 16:9 box (top-left corner and width, its height is width × 9/16) and the circle, in image pixels. */
    float wx, wy, ww, cx, cy, r;
    int mode = WIDE;
    /** Image placement on screen: top-left corner and scale. */
    private final float ix, iy, k;
    private int drag;
    private float grabX, grabY;
    private boolean done;

    /** Takes ownership of {@code src}; {@code picked} is copied as the jacket on OK (null: frame the current one). */
    JacketCrop(Gfx g, Im im, Library lib, Library.Song song, Pixmap src, File picked) {
        this.g = g;
        this.im = im;
        this.lib = lib;
        this.song = song;
        this.picked = picked;
        this.src = src;
        w = src.getWidth();
        h = src.getHeight();
        tex = screenTexture(src);
        k = Math.min((AW - 48f) / w, (AH - 48f) / h);
        ix = AX + (AW - w * k) * 0.5f;
        iy = AY + (AH - h * k) * 0.5f;
        resetWide();
        resetRound();
        if (picked == null) { // the current jacket: start from its saved boxes
            float[] b = song.jacketWide;
            if (b != null) {
                ww = b[2] * w;
                wx = b[0] * w;
                wy = b[1] * h;
            }
            b = song.jacketRound;
            if (b != null) {
                r = b[2] * w * 0.5f;
                cx = b[0] * w + r;
                cy = b[1] * h + r;
            }
            clamp();
        }
        if (Math.abs(w * WIDE_K - h) < 1f) mode = ROUND; // a 16:9 picture is its own 16:9 box: the circle is the choice
    }

    /** The largest 16:9 box, centred (what the game shows without one). */
    private void resetWide() {
        ww = Math.min(w, h / WIDE_K);
        wx = (w - ww) * 0.5f;
        wy = (h - ww * WIDE_K) * 0.5f;
    }

    /** The largest circle, centred (what the game shows without one). */
    private void resetRound() {
        r = Math.min(w, h) * 0.5f;
        cx = w * 0.5f;
        cy = h * 0.5f;
    }

    /** Keeps both boxes inside the image and at least 16 px across (32 px wide for the 16:9 box). */
    private void clamp() {
        float maxW = Math.min(w, h / WIDE_K);
        ww = Math.max(Math.min(maxW, 32f), Math.min(maxW, ww));
        wx = Math.max(0f, Math.min(w - ww, wx));
        wy = Math.max(0f, Math.min(h - ww * WIDE_K, wy));
        float max = Math.min(w, h) * 0.5f;
        r = Math.max(Math.min(max, 8f), Math.min(max, r));
        cx = Math.max(r, Math.min(w - r, cx));
        cy = Math.max(r, Math.min(h - r, cy));
    }

    float toScreenX(float px) {
        return ix + px * k;
    }

    float toScreenY(float py) {
        return iy + py * k;
    }

    float screenX() {
        return toScreenX(cx);
    }

    float screenY() {
        return toScreenY(cy);
    }

    float screenR() {
        return r * k;
    }

    /** Draws the dialog and handles its input. @return false once it is closed (saved or cancelled) */
    boolean frame() {
        input();
        g.rect(0f, 0f, Gfx.W, Gfx.H, Gfx.BG);
        g.rect(0f, 0f, Gfx.W, 56f, Gfx.BAR);
        g.line(0f, 56f, Gfx.W, 56f, 1f, Gfx.LINE);
        float x = 16f;
        for (char c : "K&K CHARTER".toCharArray()) x += g.text(String.valueOf(c), x, 28f, 16, Gfx.INK1) + 4f;
        g.text(g.fit("曲绘取景 · " + song.name(), 12, Gfx.W - 820f), x + 14f, 28f, 12, Gfx.INK3);
        image();
        panel();
        if (im.key(Input.Keys.ESCAPE)) done = true;
        else if (im.key(Input.Keys.ENTER) && !done) save();
        return !done;
    }

    private void input() {
        if (im.key(Input.Keys.TAB)) {
            mode = 1 - mode;
            drag = NONE;
        }
        boolean inArea = im.hover(AX, AY, AW, AH);
        float mx = (im.mx - ix) / k, my = (im.my - iy) / k;
        boolean onHandle, inside;
        if (mode == WIDE) {
            float hx = toScreenX(wx + ww), hy = toScreenY(wy + ww * WIDE_K);
            onHandle = Math.hypot(im.mx - hx, im.my - hy) <= 14f;
            inside = mx >= wx && mx <= wx + ww && my >= wy && my <= wy + ww * WIDE_K;
        } else {
            float sx = screenX(), sy = screenY(), sr = screenR();
            float d = (float) Math.hypot(im.mx - sx, im.my - sy);
            onHandle = Math.abs(d - sr) <= 12f
                || Math.hypot(im.mx - sx - sr * 0.7071f, im.my - sy - sr * 0.7071f) <= 14f;
            inside = d < sr;
        }
        if (im.pressed && inArea) {
            if (onHandle) {
                drag = SIZE;
                grabX = mode == WIDE ? wx + ww - mx : r - (float) Math.hypot(mx - cx, my - cy);
            } else {
                if (!inside) { // a press beside the box moves it there
                    if (mode == WIDE) {
                        wx = mx - ww * 0.5f;
                        wy = my - ww * WIDE_K * 0.5f;
                    } else {
                        cx = mx;
                        cy = my;
                    }
                    clamp();
                }
                drag = MOVE;
                grabX = (mode == WIDE ? wx : cx) - mx;
                grabY = (mode == WIDE ? wy : cy) - my;
            }
        }
        if (drag != NONE && im.down) {
            if (drag == MOVE && mode == WIDE) {
                wx = mx + grabX;
                wy = my + grabY;
            } else if (drag == MOVE) {
                cx = mx + grabX;
                cy = my + grabY;
            } else if (mode == WIDE) { // the top-left corner stays
                ww = Math.min(mx + grabX - wx, Math.min(w - wx, (h - wy) / WIDE_K));
            } else {
                r = (float) Math.hypot(mx - cx, my - cy) + grabX;
            }
            clamp();
        }
        if (!im.down) drag = NONE;
        if (inArea && im.wheel != 0f) {
            float f = (float) Math.pow(0.94, im.wheel);
            if (mode == WIDE) { // about the box's centre
                float mcx = wx + ww * 0.5f, mcy = wy + ww * WIDE_K * 0.5f;
                ww *= f;
                wx = mcx - ww * 0.5f;
                wy = mcy - ww * WIDE_K * 0.5f;
            } else {
                r *= f;
            }
            clamp();
        }
        if (drag == SIZE || (drag == NONE && inArea && onHandle)) im.cursor(Cursor.SystemCursor.NWSEResize);
        else if (drag == MOVE || (inArea && inside)) im.cursor(Cursor.SystemCursor.AllResize);
    }

    private void image() {
        g.box(AX, AY, AW, AH, 12f, Gfx.BAR, Gfx.ITEM_LINE);
        g.image(tex, ix, iy, w * k, h * k, 0.25f);
        float bx = toScreenX(wx), by = toScreenY(wy), bw = ww * k, bh = ww * WIDE_K * k;
        float sx = screenX(), sy = screenY(), sr = screenR();
        if (mode == WIDE) {
            region(g, tex, bx, by, bw, bh, wideUv(), 1f);
            g.circle(sx, sy, sr, 1.5f, Gfx.INK3);                // the other box, faint
            g.stroke(bx, by, bw, bh, 2f, 2f, Gfx.SYNC);
            g.disc(bx + bw, by + bh, 9f, Gfx.SYNC);
            g.circle(bx + bw, by + bh, 9f, 2f, Gfx.INK1);
        } else {
            float[] uv = roundUv();
            round(g, tex, sx - sr, sy - sr, 2f * sr, uv[0], uv[1], uv[2], uv[3], 0f, 0.6f);
            round(g, tex, sx - sr, sy - sr, 2f * sr, uv[0], uv[1], uv[2], uv[3], sr, 1f);
            g.dashedRect(bx, by, bw, bh, Gfx.INK3, 0.8f);         // the other box, faint
            g.circle(sx, sy, sr, 2f, Gfx.PRI_TOP);
            float hx = sx + sr * 0.7071f, hy = sy + sr * 0.7071f;
            g.disc(hx, hy, 9f, Gfx.PRI_TOP);
            g.circle(hx, hy, 9f, 2f, Gfx.INK1);
        }
    }

    private void panel() {
        g.box(PX, AY, PW, AH, 12f, Gfx.BAR, Gfx.ITEM_LINE);
        float x = PX + 30f, y = AY + 44f, tw = PW - 60f;
        g.text("曲绘取景", x, y, 24, Gfx.INK1);
        y += 34f;
        int m = im.segmented("crop-mode", x, y, tw, 38f, new String[]{"16:9 区域", "圆形区域"}, mode);
        if (m != mode) {
            mode = m;
            drag = NONE;
        }
        y += 60f;
        y += g.wrap("这张图是 " + w + " × " + h + "。图本身不裁，两个框存进歌曲文件夹的 song.json，游戏按框显示。",
            x, y, tw, 14, Gfx.INK2) + 10f;
        y += g.wrap(mode == WIDE ? "16:9 区域：选歌卡片和结算显示这一块。" : "圆形区域：地图上的洞和进歌时的转场显示这个圆。",
            x, y, tw, 14, Gfx.INK1) + 10f;
        y += g.wrap(Gfx.TABLET ? "拖动框来移动，拖角上的点调大小。" : "拖动框来移动，拖角上的点或滚动滚轮来调大小，Tab 换另一个框。",
            x, y, tw, 14, Gfx.INK3) + 26f;
        float ph = tw * WIDE_K;
        region(g, tex, x, y, tw, ph, wideUv(), 1f);
        if (mode == WIDE) g.stroke(x - 4f, y - 4f, tw + 8f, ph + 8f, 4f, 2f, Gfx.SYNC);
        g.text("选歌卡片 / 结算（16:9）", x, y + ph + 22f, 13, Gfx.INK3);
        y += ph + (Gfx.TABLET ? 44f : 56f);
        float ps = Gfx.TABLET ? 130f : 170f, rx = x + ps + 26f;
        float[] uv = roundUv();
        round(g, tex, x, y, ps, uv[0], uv[1], uv[2], uv[3], ps * 0.5f, 1f);
        if (mode == ROUND) g.circle(x + ps * 0.5f, y + ps * 0.5f, ps * 0.5f + 4f, 2f, Gfx.PRI_TOP);
        g.text("地图上的洞 / 转场（圆形）", rx, y + 20f, 13, Gfx.INK3);
        g.text("16:9 区域 " + Math.round(ww) + " × " + Math.round(ww * WIDE_K) + " px", rx, y + 58f, 14, Gfx.INK2);
        g.text("圆的直径 " + Math.round(2f * r) + " px", rx, y + 84f, 14, Gfx.INK2);
        if (picked != null) {
            String ext = picked.getName().substring(picked.getName().lastIndexOf('.') + 1)
                .toLowerCase(java.util.Locale.ROOT);
            g.wrap("确定后原样复制成 jacket." + ext + "，旧曲绘移到 trash/", rx, y + 112f, tw - ps - 26f, 13, Gfx.INK3);
        }
        float by = AY + AH - 66f;
        if (im.button("crop-reset", x, by, 100f, 36f, "重置", Im.NORMAL)) {
            if (mode == WIDE) resetWide();
            else resetRound();
        }
        if (im.button("crop-cancel", x + tw - 232f, by, 110f, 36f, "取消", Im.NORMAL)) done = true;
        if (im.button("crop-ok", x + tw - 110f, by, 110f, 36f, "确定", Im.PRIMARY)) save();
    }

    /** @return the 16:9 box as texture coordinates {u0, v0, u1, v1} */
    private float[] wideUv() {
        return new float[]{wx / w, wy / h, (wx + ww) / w, (wy + ww * WIDE_K) / h};
    }

    /** @return the square around the circle as texture coordinates {u0, v0, u1, v1} */
    private float[] roundUv() {
        return new float[]{(cx - r) / w, (cy - r) / h, (cx + r) / w, (cy + r) / h};
    }

    /** @return the 16:9 box as fractions {x, y, w, h} for song.json, or null where it is the largest centred one */
    float[] wideBox() {
        float maxW = Math.min(w, h / WIDE_K);
        if (Math.abs(ww - maxW) < 0.5f && Math.abs(wx - (w - ww) * 0.5f) < 0.5f
            && Math.abs(wy - (h - ww * WIDE_K) * 0.5f) < 0.5f) {
            return null;
        }
        return fractions(wx, wy, ww, ww * WIDE_K);
    }

    /**
     * @return the square around the circle as fractions {x, y, w, h} for song.json, or null where it is the largest
     * centred one
     */
    float[] roundBox() {
        if (Math.abs(r - Math.min(w, h) * 0.5f) < 0.5f && Math.abs(cx - w * 0.5f) < 0.5f
            && Math.abs(cy - h * 0.5f) < 0.5f) return null;
        return fractions(cx - r, cy - r, 2f * r, 2f * r);
    }

    /** The pixel box (x, y, bw, bh) as fractions of the picture, 5 decimals, kept inside it. */
    private float[] fractions(float x, float y, float bw, float bh) {
        float fx = Math.round(x / w * 1e5f) / 1e5f, fy = Math.round(y / h * 1e5f) / 1e5f;
        float fw = Math.min(Math.round(bw / w * 1e5f) / 1e5f, 1f - fx),
            fh = Math.min(Math.round(bh / h * 1e5f) / 1e5f, 1f - fy);
        return new float[]{fx, fy, fw, fh};
    }

    private void save() {
        if (picked != null) {
            try {
                lib.setJacket(song, picked);
            } catch (Exception e) {
                im.toast("更换曲绘失败：" + e.getMessage());
                done = true;
                return;
            }
        }
        if (!lib.setJacketBoxes(song, wideBox(), roundBox())) im.toast("song.json 写不进去");
        else im.toast(picked != null ? "曲绘已更换" : "取景已保存");
        done = true;
    }

    /** A mipmapped texture of the picture for the screen, halved until it is at most 4096 px across. */
    private static Texture screenTexture(Pixmap src) {
        Pixmap p = src;
        while (Math.max(p.getWidth(), p.getHeight()) > 4096) {
            Pixmap half = new Pixmap(Math.max(1, p.getWidth() / 2), Math.max(1, p.getHeight() / 2),
                Pixmap.Format.RGBA8888);
            half.setBlending(Pixmap.Blending.None);
            half.setFilter(Pixmap.Filter.BiLinear);
            half.drawPixmap(p, 0, 0, p.getWidth(), p.getHeight(), 0, 0, half.getWidth(), half.getHeight());
            if (p != src) p.dispose();
            p = half;
        }
        Texture t = new Texture(p, true);
        t.setFilter(Texture.TextureFilter.MipMapLinearLinear, Texture.TextureFilter.Linear);
        if (p != src) p.dispose();
        return t;
    }

    /** Draws the texture area uv = {u0, v0, u1, v1} into the rectangle at (x, y) of size bw × bh (top-left based). */
    static void region(Gfx g, Texture t, float x, float y, float bw, float bh, float[] uv, float a) {
        g.batch.setColor(1f, 1f, 1f, a);
        g.batch.draw(t, x, Gfx.H - y - bh, bw, bh, uv[0], uv[3], uv[2], uv[1]);
        g.batch.setColor(Color.WHITE);
    }

    /**
     * Draws the texture area (u0, v0)–(u1, v1) into the square at (x, y) of side s (top-left
     * based), cut to a rounded square of corner radius rad (s / 2 gives a circle, 0 a square)
     * out of horizontal strips about 1 px high, so no mask or scissor is needed.
     */
    static void round(Gfx g, Texture t, float x, float y, float s, float u0, float v0, float u1, float v1,
            float rad, float a) {
        g.batch.setColor(1f, 1f, 1f, a);
        if (rad <= 0f) {
            g.batch.draw(t, x, Gfx.H - y - s, s, s, u0, v1, u1, v0);
        } else {
            int n = Math.max(8, Math.min(600, (int) Math.ceil(s)));
            for (int i = 0; i < n; i++) {
                float ya = s * i / n, yb = s * (i + 1) / n, ym = (ya + yb) * 0.5f;
                float dy = ym < rad ? rad - ym : ym > s - rad ? ym - (s - rad) : 0f;
                float in = dy > 0f ? rad - (float) Math.sqrt(Math.max(0f, rad * rad - dy * dy)) : 0f;
                float fa = in / s, fb = 1f - in / s;
                g.batch.draw(t, x + in, Gfx.H - y - yb, s - 2f * in, yb - ya,
                    u0 + (u1 - u0) * fa, v0 + (v1 - v0) * (yb / s), u0 + (u1 - u0) * fb, v0 + (v1 - v0) * (ya / s));
            }
        }
        g.batch.setColor(Color.WHITE);
    }

    void dispose() {
        tex.dispose();
        src.dispose();
    }
}
