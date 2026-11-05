package org.kelvinizer.charter;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.graphics.g2d.freetype.FreeTypeFontGenerator;
import com.badlogic.gdx.utils.Disposable;
import com.badlogic.gdx.utils.IntMap;
import org.kelvinizer.ui.Draw;
import org.kelvinizer.ui.Theme;

import java.util.ArrayList;

/**
 * 编辑器的绘图工具：坐标从左上角往下，颜色和游戏的界面一致。
 * Drawing helpers for the editor UI. Coordinates are top-left based (y grows downwards) on the
 * 1920 × 1080 canvas (1344 × 840 on a tablet, see {@link #tablet}) and converted to libGDX's y-up world here.
 * Shapes go through the game's {@link Draw}; text uses FreeType fonts generated on demand (CJK included).
 */
public final class Gfx implements Disposable {
    /** The canvas size; set once by {@link #tablet} before anything is drawn. */
    public static float W = 1920f, H = 1080f;
    /**
     * The tablet layout (Kelvin 2026-10-25): a 16:10 canvas of 1344 × 840 with the desktop's sizes, so the side
     * panel and its text look about as big on a 10–11 inch tablet as on a desktop screen.
     */
    public static boolean TABLET;

    public static final Color BG = c(0x070C27), BAR = c(0x0A1033), BAR2 = c(0x080E2C);
    public static final Color LINE = c(0x1E2650), LINE2 = c(0x141C4A);
    public static final Color BTN = c(0x121A48), BTN_HI = c(0x1A2560), BTN_LINE = c(0x232D63);
    public static final Color FIELD = c(0x070C27), SEL = c(0x16205A), SEL_LINE = c(0x2E3A7A);
    public static final Color ITEM = c(0x0E1540), ITEM_LINE = c(0x1A2358), ITEM_ON = c(0x0F1F52);
    public static final Color INK1 = Theme.INK1, INK2 = Theme.INK2, INK3 = Theme.INK3;
    public static final Color GND = Theme.GROUND_SIG, SKY = Theme.SKY_SIG;
    public static final Color SYNC = c(0xFFD36B), MINE = c(0xFF3344), TIDE = c(0x8CFFD9), WAVE = c(0x1FBF98);
    public static final Color BAD = c(0xFF5C7A), OK = c(0x7BE0A0), BPM = c(0xFFB25C), FX = c(0x9D7BFF);
    public static final Color PRI_TOP = c(0x6FE3FF), PRI_BOT = c(0x36B9E8), PRI_INK = c(0x04142B);
    /** Difficulty colours, by ordinal (shown as Shallow, Medium, Deep, Abyss). */
    public static final Color[] DIFF = {c(0xF2F7FF), c(0x4FD8FF), c(0x3D7BFF), c(0xFF3344)};

    /**
     * Name a difficulty is shown with, as in the game (Theme.difficultyName, Kelvin 2026-10-27): Shallow, Medium,
     * Deep, Abyss. Files and the {@code level} field keep Surface / Shallow / Deep / Abyss.
     */
    public static String diffName(org.kelvinizer.library.Difficulty d) {
        String n = Theme.difficultyName(d);
        return n.charAt(0) + n.substring(1).toLowerCase(java.util.Locale.ROOT);
    }

    public final Draw d;
    public final SpriteBatch batch;
    private final FreeTypeFontGenerator gen;
    private final IntMap<BitmapFont> fonts = new IntMap<>();
    private float fontScale = 1f;
    private final GlyphLayout layout = new GlyphLayout();
    private final Color tmp = new Color();

    public Gfx(Draw draw) {
        d = draw;
        batch = draw.batch();
        gen = new FreeTypeFontGenerator(Gdx.files.internal("NotoSansSC-Medium.ttf"));
    }

    /** Switches to the tablet layout; call before the app is created. */
    public static void tablet() {
        TABLET = true;
        W = 1344f;
        H = 840f;
    }

    /**
     * Sets the screen pixels per canvas unit. On a tablet fonts are then generated at the screen's size (sharp, not
     * stretched); the desktop keeps fonts at canvas size.
     */
    public void pixelsPerUnit(float px) {
        float k = TABLET ? Math.max(1f, Math.round(px * 4f) / 4f) : 1f;
        if (k == fontScale) return;
        fontScale = k;
        for (BitmapFont f : fonts.values()) f.dispose();
        fonts.clear();
    }

    public static Color c(int rgb) {
        return new Color(((rgb >> 16) & 255) / 255f, ((rgb >> 8) & 255) / 255f, (rgb & 255) / 255f, 1f);
    }

    // ================================================================== shapes

    public void rect(float x, float y, float w, float h, Color c) {
        d.rect(x, H - y - h, w, h, c, 1f);
    }

    public void rect(float x, float y, float w, float h, Color c, float a) {
        d.rect(x, H - y - h, w, h, c, a);
    }

    public void rrect(float x, float y, float w, float h, float r, Color c) {
        d.roundRect(x, H - y - h, w, h, r, c, 1f);
    }

    public void rrect(float x, float y, float w, float h, float r, Color c, float a) {
        d.roundRect(x, H - y - h, w, h, r, c, a);
    }

    public void stroke(float x, float y, float w, float h, float r, float width, Color c) {
        d.roundRectStroke(x, H - y - h, w, h, r, width, c, 1f);
    }

    public void stroke(float x, float y, float w, float h, float r, float width, Color c, float a) {
        d.roundRectStroke(x, H - y - h, w, h, r, width, c, a);
    }

    /** Rounded box with a fill and a 1 px border. */
    public void box(float x, float y, float w, float h, float r, Color fill, Color border) {
        rrect(x, y, w, h, r, fill);
        stroke(x, y, w, h, r, 1f, border);
    }

    public void grad(float x, float y, float w, float h, float r, Color top, Color bottom) {
        d.roundRectGradient(x, H - y - h, w, h, r, bottom, top, 1f);
    }

    public void line(float x1, float y1, float x2, float y2, float w, Color c) {
        d.line(x1, H - y1, x2, H - y2, w, c, 1f);
    }

    public void line(float x1, float y1, float x2, float y2, float w, Color c, float a) {
        d.line(x1, H - y1, x2, H - y2, w, c, a);
    }

    /** Dashed horizontal or vertical line. */
    public void dashed(float x1, float y1, float x2, float y2, float w, float dash, Color c, float a) {
        float len = (float) Math.hypot(x2 - x1, y2 - y1);
        if (len <= 0f) return;
        float ux = (x2 - x1) / len, uy = (y2 - y1) / len;
        for (float s = 0f; s < len; s += dash * 2f) {
            float e = Math.min(len, s + dash);
            line(x1 + ux * s, y1 + uy * s, x1 + ux * e, y1 + uy * e, w, c, a);
        }
    }

    /** Dashed rectangle outline. */
    public void dashedRect(float x, float y, float w, float h, Color c, float a) {
        dashed(x, y, x + w, y, 1.5f, 6f, c, a);
        dashed(x, y + h, x + w, y + h, 1.5f, 6f, c, a);
        dashed(x, y, x, y + h, 1.5f, 6f, c, a);
        dashed(x + w, y, x + w, y + h, 1.5f, 6f, c, a);
    }

    public void disc(float cx, float cy, float r, Color c) {
        d.disc(cx, H - cy, r, c, 1f);
    }

    public void disc(float cx, float cy, float r, Color c, float a) {
        d.disc(cx, H - cy, r, c, a);
    }

    public void tri(float x1, float y1, float x2, float y2, float x3, float y3, Color c, float a) {
        tmp.set(c.r, c.g, c.b, c.a * a);
        d.shapes().setColor(tmp);
        d.shapes().filledTriangle(x1, H - y1, x2, H - y2, x3, H - y3);
    }

    /** A small downward triangle (drop-down caret) centred on (cx, cy). */
    public void caret(float cx, float cy, float r, Color c) {
        tri(cx - r, cy - r * 0.5f, cx + r, cy - r * 0.5f, cx, cy + r * 0.6f, c, 1f);
    }

    /** Play (triangle), pause (two bars) or skip-to-start icons centred on (cx, cy). */
    public void playIcon(float cx, float cy, float r, boolean playing, Color c) {
        if (playing) {
            rect(cx - r * 0.75f, cy - r, r * 0.5f, r * 2f, c);
            rect(cx + r * 0.25f, cy - r, r * 0.5f, r * 2f, c);
        } else {
            tri(cx - r * 0.7f, cy - r, cx - r * 0.7f, cy + r, cx + r, cy, c, 1f);
        }
    }

    public void rewindIcon(float cx, float cy, float r, Color c) {
        rect(cx - r, cy - r, r * 0.3f, r * 2f, c);
        tri(cx - r * 0.6f, cy, cx + r * 0.2f, cy - r, cx + r * 0.2f, cy + r, c, 1f);
        tri(cx + r * 0.2f, cy, cx + r, cy - r, cx + r, cy + r, c, 1f);
    }

    public void circle(float cx, float cy, float r, float w, Color c) {
        d.circle(cx, H - cy, r, w, c, 1f);
    }

    public void image(Texture t, float x, float y, float w, float h, float a) {
        batch.setColor(1f, 1f, 1f, a);
        batch.draw(t, x, H - y - h, w, h);
        batch.setColor(Color.WHITE);
    }

    public void image(TextureRegion t, float x, float y, float w, float h, float a) {
        batch.setColor(1f, 1f, 1f, a);
        batch.draw(t, x, H - y - h, w, h);
        batch.setColor(Color.WHITE);
    }

    // ================================================================== text

    public BitmapFont font(int size) {
        BitmapFont f = fonts.get(size);
        if (f == null) {
            FreeTypeFontGenerator.FreeTypeFontParameter p = new FreeTypeFontGenerator.FreeTypeFontParameter();
            p.size = Math.round(size * fontScale);
            p.incremental = true;
            p.minFilter = Texture.TextureFilter.Linear;
            p.magFilter = Texture.TextureFilter.Linear;
            p.characters = FreeTypeFontGenerator.DEFAULT_CHARS;
            f = gen.generateFont(p);
            f.getData().setScale(1f / fontScale);
            f.setUseIntegerPositions(fontScale == 1f);
            f.setFixedWidthGlyphs("0123456789");
            fonts.put(size, f);
        }
        return f;
    }

    /** Draws one line of text vertically centred on {@code cy}; {@code align} 0 left, 1 centre, 2 right. */
    public float text(String s, float x, float cy, int size, Color c, int align) {
        return text(s, x, cy, size, c, 1f, align);
    }

    public float text(String s, float x, float cy, int size, Color c) {
        return text(s, x, cy, size, c, 1f, 0);
    }

    public float text(String s, float x, float cy, int size, Color c, float a, int align) {
        if (s == null || s.isEmpty()) return 0f;
        BitmapFont f = font(size);
        layout.setText(f, s);
        float w = layout.width;
        float px = align == 1 ? x - w * 0.5f : align == 2 ? x - w : x;
        float top = H - cy + f.getCapHeight() * 0.62f;
        tmp.set(c.r, c.g, c.b, c.a * a);
        f.setColor(tmp);
        f.draw(batch, s, px, top);
        return w;
    }

    /** Characters that never start a line (they stay at the end of the line before). */
    private static final String CLOSE = "，。、；：！？）」』】》〉,.;:!?)]}%…·";
    /** Characters that never end a line. */
    private static final String OPEN = "（「『【《〈([{";
    private final ArrayList<String> lines = new ArrayList<>();

    /** Draws text wrapped to {@code w}, top at {@code y}; @return the height used. */
    public float wrap(String s, float x, float y, float w, int size, Color c) {
        BitmapFont f = font(size);
        f.setColor(c);
        ArrayList<String> ls = lines(s, size, w);
        float lh = f.getLineHeight();
        for (int k = 0; k < ls.size(); k++) f.draw(batch, ls.get(k), x, H - y - k * lh);
        return f.getCapHeight() + Math.max(0, ls.size() - 1) * lh + lh * 0.3f;
    }

    /**
     * Breaks {@code s} into lines at most {@code w} wide: Chinese may break between any two
     * characters, Latin words only at spaces, and closing punctuation never starts a line.
     */
    public ArrayList<String> lines(String s, int size, float w) {
        lines.clear();
        BitmapFont.BitmapFontData d = font(size).getData();
        for (String para : s.split("\n", -1)) {
            int n = para.length(), start = 0;
            if (n == 0) lines.add("");
            while (start < n) {
                float lw = 0f;
                int i = start, brk = -1;
                while (i < n) {
                    BitmapFont.Glyph gl = d.getGlyph(para.charAt(i));
                    float a = gl == null ? 0f : gl.xadvance * d.scaleX;
                    if (lw + a > w && i > start) break;
                    lw += a;
                    i++;
                    if (i < n && breakable(para.charAt(i - 1), para.charAt(i))) brk = i;
                }
                int cut = i;
                if (i < n) {
                    if (brk > start) cut = brk;
                    while (cut < n && CLOSE.indexOf(para.charAt(cut)) >= 0) cut++;
                }
                int end = cut;
                while (end > start && para.charAt(end - 1) == ' ') end--;
                lines.add(para.substring(start, end));
                start = cut;
                while (start < n && para.charAt(start) == ' ') start++;
            }
        }
        return lines;
    }

    private static boolean breakable(char a, char b) {
        if (CLOSE.indexOf(b) >= 0 || OPEN.indexOf(a) >= 0) return false;
        return a == ' ' || a >= 0x2E80 || b >= 0x2E80;
    }

    /** @return {@code s}, cut with "…" so that it is at most {@code w} wide. */
    public String fit(String s, int size, float w) {
        if (s == null || width(s, size) <= w) return s;
        int n = s.length();
        while (n > 1 && width(s.substring(0, n) + "…", size) > w) n--;
        return s.substring(0, n) + "…";
    }

    public float width(String s, int size) {
        if (s == null || s.isEmpty()) return 0f;
        layout.setText(font(size), s);
        return layout.width;
    }

    @Override
    public void dispose() {
        for (BitmapFont f : fonts.values()) f.dispose();
        fonts.clear();
        gen.dispose();
    }
}
