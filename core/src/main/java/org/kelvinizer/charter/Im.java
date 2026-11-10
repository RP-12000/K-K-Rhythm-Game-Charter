package org.kelvinizer.charter;

import com.badlogic.gdx.Application;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.InputAdapter;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Cursor;
import com.badlogic.gdx.math.Rectangle;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.scenes.scene2d.utils.ScissorStack;
import com.badlogic.gdx.utils.IntArray;
import com.badlogic.gdx.utils.viewport.Viewport;

import java.util.ArrayList;
import java.util.ArrayDeque;

/**
 * 即时模式的界面控件：每帧直接画按钮、输入框、分段选择和下拉菜单，同时处理鼠标和键盘。
 * Immediate-mode widgets: every frame the screens draw buttons, text fields, segmented
 * controls and drop-downs and get their results back in the same call. Input events are
 * collected between frames ({@link InputAdapter}) and turned into per-frame state in
 * {@link #begin()}. Popups, modals and toasts are drawn last by {@link #overlay()}.
 */
public final class Im extends InputAdapter {
    private final Gfx g;
    private final Viewport vp;
    private final Platform platform;
    private final Vector2 v = new Vector2();

    // ---- raw input (between frames)
    private float rawX, rawY;
    private boolean rawDown;
    private int rawPresses, rawReleases, rawButton;
    private float rawScroll;
    private boolean lifted;
    /** A second finger on a touch screen and where it is (two fingers turn the 3D view, Kelvin 2026-11-08 02:16). */
    private boolean rawTwo;
    private float rawX2, rawY2;
    private final StringBuilder rawTyped = new StringBuilder();
    private final IntArray rawKeys = new IntArray();

    // ---- per-frame state
    public float mx, my, pressX, pressY;
    public boolean down, pressed, released, rightPressed;
    public float wheel;
    public boolean ctrl, shift, alt;
    /** Two fingers are down (a touch screen), and their midpoint. */
    public boolean two;
    public float twoX, twoY;
    /** Self-test: Alt, Ctrl and Shift count as held. */
    public boolean testAlt, testCtrl, testShift;
    public final IntArray keys = new IntArray();
    private String typed = "";
    /** True while a popup or modal owns the mouse (normal widgets see no hover). */
    private boolean blocked;
    private final ArrayList<Rectangle> clips = new ArrayList<>();

    // ---- widget state
    public Object active;
    private Object focus, focusLost, focusNext, lastFocusable;
    private String lostText;
    private boolean focusSeen;
    private final Rectangle focusRect = new Rectangle();
    private final StringBuilder buf = new StringBuilder();
    private int caret;
    private boolean selAll;
    private int selectionAnchor = -1, preferredColumn = -1;
    private final ArrayDeque<String> textUndo = new ArrayDeque<>(), textRedo = new ArrayDeque<>();
    private final ArrayList<Object> order = new ArrayList<>();
    private Cursor.SystemCursor cursor = Cursor.SystemCursor.Arrow, cursorNow = Cursor.SystemCursor.Arrow;

    // ---- popup / modal / toast
    private Object popupId;
    private final Rectangle popupRect = new Rectangle();
    private String[] popupItems;
    private int popupSel;
    private Object popupDoneId;
    private int popupDone = -1;
    private Modal modal;
    /** A dialog its owner draws over the whole screen (the curve editor): it alone gets the mouse and the keys. */
    public Runnable panel;
    private boolean chosenByEnter;
    private String toast;
    private float toastT;

    /** A dialog with a message, optional text input and up to three buttons. */
    public static final class Modal {
        public final String title, message;
        public final String[] buttons;
        public final Callback callback;
        public String input;
        public int danger = -1;
        public int primary = 0;

        public interface Callback {
            /** @param button index of the pressed button, −1 for Esc */
            void done(int button, String input);
        }

        public Modal(String title, String message, String[] buttons, Callback callback) {
            this.title = title;
            this.message = message;
            this.buttons = buttons;
            this.callback = callback;
        }
    }

    public Im(Gfx g, Viewport vp, Platform platform) {
        this.g = g;
        this.vp = vp;
        this.platform = platform;
    }

    // ================================================================== InputProcessor

    private void second(int sx, int sy) {
        vp.unproject(v.set(sx, sy));
        rawX2 = v.x;
        rawY2 = Gfx.H - v.y;
    }

    private void move(int sx, int sy) {
        vp.unproject(v.set(sx, sy));
        rawX = v.x;
        rawY = Gfx.H - v.y;
    }

    @Override
    public boolean touchDown(int screenX, int screenY, int pointer, int button) {
        if (pointer == 1) {
            second(screenX, screenY);
            rawTwo = true;
            return true;
        }
        if (pointer > 0) return true; // one finger at a time on a touch screen (a second one only turns the view)
        move(screenX, screenY);
        if (button == Input.Buttons.LEFT) {
            rawDown = true;
            rawPresses++;
        }
        rawButton = button;
        if (button == Input.Buttons.RIGHT) rawPresses += 1000;
        return true;
    }

    @Override
    public boolean touchUp(int screenX, int screenY, int pointer, int button) {
        if (pointer == 1) rawTwo = false;
        if (pointer > 0) return true;
        move(screenX, screenY);
        if (button == Input.Buttons.LEFT) {
            rawDown = false;
            rawReleases++;
            lifted = Gfx.TABLET;
        }
        return true;
    }

    @Override
    public boolean touchDragged(int screenX, int screenY, int pointer) {
        if (pointer == 1) second(screenX, screenY);
        if (pointer > 0) return true;
        move(screenX, screenY);
        return true;
    }

    @Override
    public boolean mouseMoved(int screenX, int screenY) {
        move(screenX, screenY);
        return true;
    }

    @Override
    public boolean scrolled(float amountX, float amountY) {
        rawScroll += amountY;
        return true;
    }

    @Override
    public boolean keyDown(int keycode) {
        rawKeys.add(keycode);
        return true;
    }

    @Override
    public boolean keyTyped(char character) {
        rawTyped.append(character);
        return true;
    }

    // ================================================================== frame

    public void begin(float delta) {
        mx = rawX;
        my = rawY;
        int presses = rawPresses % 1000;
        rightPressed = rawPresses >= 1000;
        two = rawTwo && rawDown;
        if (two) {
            twoX = (rawX + rawX2) * 0.5f;
            twoY = (rawY + rawY2) * 0.5f;
        }
        pressed = presses > 0;
        released = rawReleases > 0;
        down = rawDown || (pressed && !released);
        if (pressed) {
            pressX = mx;
            pressY = my;
        }
        rawPresses = 0;
        rawReleases = 0;
        wheel = rawScroll;
        rawScroll = 0f;
        typed = rawTyped.toString();
        rawTyped.setLength(0);
        keys.clear();
        keys.addAll(rawKeys);
        rawKeys.clear();
        stepRepeat(heldKey(), delta);
        ctrl = testCtrl || Gdx.input.isKeyPressed(Input.Keys.CONTROL_LEFT) || Gdx.input.isKeyPressed(Input.Keys.CONTROL_RIGHT)
            || Gdx.input.isKeyPressed(Input.Keys.SYM);
        shift = testShift || Gdx.input.isKeyPressed(Input.Keys.SHIFT_LEFT)
            || Gdx.input.isKeyPressed(Input.Keys.SHIFT_RIGHT);
        alt = testAlt || Gdx.input.isKeyPressed(Input.Keys.ALT_LEFT) || Gdx.input.isKeyPressed(Input.Keys.ALT_RIGHT);
        blocked = modal != null || panel != null || (popupId != null && popupRect.contains(mx, my));
        focusLost = null;
        if (pressed && focus != null && !focusRect.contains(pressX, pressY)) {
            focusLost = focus;
            lostText = buf.toString();
        }
        focusSeen = false;
        focusHint = null;
        order.clear();
        cursor = Cursor.SystemCursor.Arrow;
        if (toastT > 0f) toastT -= delta;
        clips.clear();
    }

    // ---- 长按方向键 (Kelvin 2026-11-08 06:46「对于输入框，我有时候长按左/右箭头没法连续移动光标」)

    /**
     * Keys pressed again while held: the desktop window repeats only typed characters (they come again to
     * {@link #keyTyped}), so a held arrow moved the caret one step. Arrows always; Backspace and Delete while a field
     * has the focus (held on the canvas, Delete would go on to the selection); {@link #repeatAlso} off the fields.
     */
    private static final int[] ARROWS = {Input.Keys.LEFT, Input.Keys.RIGHT, Input.Keys.UP, Input.Keys.DOWN},
        ERASE = {Input.Keys.BACKSPACE, Input.Keys.FORWARD_DEL};
    private static final float REPEAT_DELAY = 0.4f, REPEAT_EVERY = 0.04f;
    /** More keys repeated off the fields (the editor's W A S D while a node of a 轨迹 is picked), or null. */
    public int[] repeatAlso;
    /** Self-test: the key taken as held, −1 none. */
    public int testHeld = -1;
    private int repeatKey = -1;
    private float repeatT;

    /** @return the key held now that repeats, −1 none */
    private int heldKey() {
        if (testHeld >= 0) return testHeld;
        for (int k : ARROWS) if (Gdx.input.isKeyPressed(k)) return k;
        if (focus != null) {
            for (int k : ERASE) if (Gdx.input.isKeyPressed(k)) return k;
        } else if (repeatAlso != null) {
            for (int k : repeatAlso) if (Gdx.input.isKeyPressed(k)) return k;
        }
        return -1;
    }

    /** Adds the presses key {@code held} makes this frame: the first after a short wait, then one every 40 ms. */
    private void stepRepeat(int held, float delta) {
        if (held != repeatKey || held >= 0 && keys.contains(held)) {   // another key, or pressed again: wait again
            repeatKey = held;
            repeatT = REPEAT_DELAY;
            return;
        }
        if (held < 0) return;
        repeatT -= delta;
        for (int i = 0; repeatT <= 0f && i < 3; i++) {
            keys.add(held);
            repeatT += REPEAT_EVERY;
        }
        if (repeatT <= 0f) repeatT = REPEAT_EVERY;                      // a long frame: no burst after it
    }

    /** Ends the frame: clears a press nobody claimed, applies the cursor. */
    public void end() {
        if (released || !down) active = null;
        if (lifted && !rawDown) {
            // a finger that left the tablet hovers nothing (a mouse stays where it is)
            lifted = false;
            rawX = rawY = -10000f;
        }
        if (focus != null && !focusSeen && focusNext == null) focus = null; // its field is gone
        if (cursor != cursorNow) {
            cursorNow = cursor;
            Gdx.graphics.setSystemCursor(cursor);
        }
    }

    // ================================================================== queries

    public boolean inside(float x, float y, float w, float h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    public boolean hover(float x, float y, float w, float h) {
        if (blocked || !inside(x, y, w, h)) return false;
        for (Rectangle r : clips) {
            if (!(mx >= r.x && mx < r.x + r.width && my >= r.y && my < r.y + r.height)) return false;
        }
        return true;
    }

    /** @return true on a completed click (press and release inside) of widget {@code id}. */
    public boolean click(Object id, float x, float y, float w, float h) {
        boolean hv = hover(x, y, w, h);
        if (pressed && hv && !rightPressed) active = id;
        if (released && id.equals(active)) {
            active = null;
            return hv;
        }
        return false;
    }

    public boolean key(int keycode) {
        return keys.contains(keycode);
    }

    public boolean textFocus() {
        return focus != null;
    }

    public void cursor(Cursor.SystemCursor c) {
        cursor = c;
    }

    /** A panel drawn later in the frame covers this rectangle: widgets under it get no mouse this frame. */
    public void block(float x, float y, float w, float h) {
        if (inside(x, y, w, h)) blocked = true;
    }

    /**
     * A floating window drawn after the widgets it covers ({@link #block}ed for them): its own widgets get the mouse
     * again, except under a popup. @return the state to hand {@link #drop} after the window
     */
    public boolean lift() {
        boolean was = blocked;
        blocked = modal != null || panel != null || popupId != null && popupRect.contains(mx, my);
        return was;
    }

    /** Ends {@link #lift}: what is drawn after the window is blocked as before. */
    public void drop(boolean was) {
        blocked = was;
    }

    public boolean modalOpen() {
        return modal != null || panel != null;
    }

    public boolean popupOpen() {
        return popupId != null;
    }

    /** Drops keyboard focus without committing (e.g. when the edited object goes away). */
    public void unfocus() {
        focus = null;
    }

    // ================================================================== clipping

    /** Clips drawing (and hover) to a top-left based rectangle until {@link #popClip}. */
    public void pushClip(float x, float y, float w, float h) {
        g.batch.flush();
        Rectangle area = new Rectangle(x, Gfx.H - y - h, w, h);
        Rectangle sc = new Rectangle();
        vp.calculateScissors(g.batch.getTransformMatrix(), area, sc);
        if (ScissorStack.pushScissors(sc)) clips.add(new Rectangle(x, y, w, h));
        else clips.add(new Rectangle(x, y, 0, 0));
    }

    public void popClip() {
        g.batch.flush();
        if (!clips.isEmpty()) {
            Rectangle r = clips.remove(clips.size() - 1);
            if (r.width > 0) ScissorStack.popScissors();
        }
    }

    // ================================================================== widgets

    public static final int NORMAL = 0, PRIMARY = 1, GHOST = 2, DANGER = 3, ON = 4;

    public boolean button(Object id, float x, float y, float w, float h, String label, int style) {
        return button(id, x, y, w, h, label, style, 14);
    }

    public boolean button(Object id, float x, float y, float w, float h, String label, int style, int size) {
        boolean hv = hover(x, y, w, h);
        boolean hit = click(id, x, y, w, h);
        if (hv) cursor(Cursor.SystemCursor.Hand);
        boolean held = hv && id.equals(active) && down;
        Color ink = Gfx.INK1;
        switch (style) {
            case PRIMARY:
                g.grad(x, y, w, h, 6f, held ? Gfx.PRI_BOT : Gfx.PRI_TOP, Gfx.PRI_BOT);
                if (hv && !held) g.rrect(x, y, w, h, 6f, Color.WHITE, 0.12f);
                ink = Gfx.PRI_INK;
                break;
            case GHOST:
                if (hv) g.rrect(x, y, w, h, 6f, Gfx.BTN, 0.8f);
                ink = Gfx.INK2;
                break;
            case ON:
                g.box(x, y, w, h, 6f, Gfx.SEL, Gfx.GND);
                break;
            default:
                g.box(x, y, w, h, 6f, hv ? Gfx.BTN_HI : Gfx.BTN, Gfx.BTN_LINE);
                if (style == DANGER) ink = Gfx.BAD;
        }
        g.text(label, x + w * 0.5f, y + h * 0.5f, size, ink, 1);
        return hit;
    }

    /** @return the index of the chosen segment (the same {@code sel} when nothing was clicked). */
    public int segmented(Object id, float x, float y, float w, float h, String[] labels, int sel) {
        g.box(x, y, w, h, 6f, Gfx.FIELD, Gfx.BTN_LINE);
        float sw = (w - 4f) / labels.length;
        int out = sel;
        for (int i = 0; i < labels.length; i++) {
            float sx = x + 2f + i * sw;
            boolean on = i == sel;
            boolean hv = hover(sx, y + 2f, sw, h - 4f);
            if (hv) cursor(Cursor.SystemCursor.Hand);
            if (on) g.box(sx, y + 2f, sw, h - 4f, 4f, Gfx.SEL, Gfx.SEL_LINE);
            else if (hv) g.rrect(sx, y + 2f, sw, h - 4f, 4f, Gfx.BTN, 0.7f);
            g.text(labels[i], sx + sw * 0.5f, y + h * 0.5f, 12, on ? Gfx.INK1 : Gfx.INK2, 1);
            if (click(new Pair(id, i), sx, y + 2f, sw, h - 4f)) out = i;
        }
        return out;
    }

    /** A small on/off switch with a label to its left; @return the new state. */
    public boolean toggle(Object id, float rightX, float cy, boolean on) {
        String s = on ? "开" : "关";
        float w = 52f, x = rightX - w;
        boolean hv = hover(x, cy - 12f, w, 24f);
        if (hv) cursor(Cursor.SystemCursor.Hand);
        g.text(s, rightX - 20f, cy, 12, on ? Gfx.GND : Gfx.INK2, 2);
        if (on) g.disc(rightX - 7f, cy, 5f, Gfx.GND);
        else g.circle(rightX - 7f, cy, 5f, 1.4f, Gfx.INK2);
        return click(id, x, cy - 12f, w, 24f) != on;
    }

    /**
     * A text field. @return the new text on the frame it is committed (Enter, Tab, click
     * elsewhere), else null.
     *
     * @param numeric only digits, sign, point and exponent can be typed
     */
    public String field(Object id, float x, float y, float w, float h, String value, String unit, boolean numeric) {
        return field(id, x, y, w, h, value, unit, numeric, null);
    }

    public String field(Object id, float x, float y, float w, float h, String value, String unit, boolean numeric,
                        Color border) {
        if (Gfx.TABLET && textBox) return tapField(id, x, y, w, h, value, unit, numeric, border);
        return textWidget(id, x, y, w, h, value, unit, numeric, false, border);
    }

    /** Multiline text area with normal editing shortcuts. Enter inserts a line break. */
    public String area(Object id, float x, float y, float w, float h, String value) {
        if (Gfx.TABLET && textBox) return tapArea(id, x, y, w, h, value);
        return textWidget(id, x, y, w, h, value, "", false, true, null);
    }

    private String textWidget(Object id, float x, float y, float w, float h, String value, String unit,
                              boolean numeric, boolean multiline, Color border) {
        order.add(id);
        boolean focused = id.equals(focus), hv = hover(x, y, w, h);
        if (hv) cursor(Cursor.SystemCursor.Ibeam);
        String result = null;
        if (id.equals(focusNext)) {
            focusNext = null;
            focus = id;
            focused = true;
            startEdit(value);
            if (numeric) selectAll();
        }
        if (pressed && hv && !focused) {
            focus = id;
            focused = true;
            startEdit(value);
            if (numeric) selectAll();
            else setCaret(caretAt(id, x, y, w, h, value, multiline), false);
            active = id;
        }
        if (id.equals(focusLost)) {
            focusLost = null;
            if (id.equals(focus)) focus = null;
            focused = id.equals(focus);
            if (lostText != null && !lostText.equals(value)) result = lostText;
            lostText = null;
        }
        if (focused) {
            focusSeen = true;
            focusRect.set(x, y, w, h);
            result = editKeys(id, numeric, multiline);
            focused = id.equals(focus);
        }
        g.box(x, y, w, h, 5f, Gfx.FIELD, focused ? Gfx.GND : border != null ? border : Gfx.BTN_LINE);
        String shown = focused ? buf.toString() : value;
        if (multiline) {
            if (focused && hasSelection()) drawAreaSelection(x + 9f, y + 7f, w - 18f, 13, shown);
            g.wrap(shown, x + 9f, y + 7f, w - 18f, 13, Gfx.INK1);
            if (focused && ((System.currentTimeMillis() / 500L) & 1L) == 0L) {
                int line = lineNumber(shown, caret), start = lineStart(shown, caret);
                float cx = x + 9f + g.width(shown.substring(start, Math.min(caret, shown.length())), 13);
                float cy = y + 7f + line * g.font(13).getLineHeight();
                g.line(cx, cy, cx, cy + g.font(13).getLineHeight(), 1.3f, Gfx.INK1);
            }
        } else {
            float textX = x + 10f;
            float unitW = unit != null && !unit.isEmpty() ? g.width(unit, 11) + 10f : 0f;
            if (hasSelection() && focused) {
                int a = selectionStart(), b = selectionEnd();
                float sx = textX + g.width(shown.substring(0, a), 13);
                float sw = g.width(shown.substring(a, b), 13);
                g.rect(sx, y + 6f, Math.max(2f, sw), h - 12f, Gfx.GND, 0.32f);
            }
            if (unitW > 0f) g.text(unit, x + w - 10f, y + h * 0.5f, 11, Gfx.INK3, 2);
            g.text(shown, textX, y + h * 0.5f, 13, Gfx.INK1);
            if (focused && ((System.currentTimeMillis() / 500L) & 1L) == 0L) {
                float cx = textX + g.width(shown.substring(0, Math.min(caret, shown.length())), 13);
                g.line(cx, y + 7f, cx, y + h - 7f, 1.3f, Gfx.INK1);
            }
        }
        return result;
    }

    private String tapArea(Object id, float x, float y, float w, float h, String value) {
        order.add(id);
        String result = null;
        if (id.equals(typedId)) {
            typedId = null;
            if (typedText != null && !typedText.equals(value)) result = typedText;
            typedText = null;
        }
        if (click(id, x, y, w, h) || id.equals(focusNext)) {
            focusNext = null;
            askArea(id, value);
        }
        g.box(x, y, w, h, 5f, Gfx.FIELD, id.equals(askId) ? Gfx.GND : Gfx.BTN_LINE);
        g.wrap(value, x + 9f, y + 7f, w - 18f, 13, Gfx.INK1);
        return result;
    }

    private String tapField(Object id, float x, float y, float w, float h, String value, String unit, boolean numeric,
                            Color border) {
        order.add(id);
        String result = null;
        if (id.equals(typedId)) {
            typedId = null;
            if (typedText != null && !typedText.equals(value)) result = typedText;
            typedText = null;
        }
        if (click(id, x, y, w, h) || id.equals(focusNext)) {
            focusNext = null;
            askHint = numeric ? slashHint : null;
            askBeats = numeric && slashBeats;
            ask(id, value, unit, numeric);
        }
        g.box(x, y, w, h, 5f, Gfx.FIELD, id.equals(askId) ? Gfx.GND : border != null ? border : Gfx.BTN_LINE);
        if (unit != null && !unit.isEmpty()) g.text(unit, x + w - 10f, y + h * 0.5f, 11, Gfx.INK3, 2);
        g.text(value, x + 10f, y + h * 0.5f, 13, Gfx.INK1);
        return result;
    }

    private Object askId, typedId;
    private String typedText;
    /** The {@code /} hint of the number field being asked for on the tablet, and whether it is a beat field. */
    private String askHint;
    private boolean askBeats;
    boolean textBox = Gdx.app.getType() == Application.ApplicationType.Android;

    private void ask(Object id, String value, String unit, boolean numeric) {
        askId = id;
        if (numeric && askHint != null && platform != null) {
            platform.numberInput("输入数字", value, askHint, askBeats, text -> {
                if (!id.equals(askId)) return;
                askId = null;
                typedId = id;
                typedText = text == null ? null : text.trim();
            });
            return;
        }
        Gdx.input.getTextInput(new Input.TextInputListener() {
            @Override public void input(String text) {
                if (!id.equals(askId)) return;
                askId = null;
                typedId = id;
                StringBuilder sb = new StringBuilder();
                for (char ch : text.toCharArray()) {
                    if (ch == '\n' || ch == '\r') sb.append(' ');
                    else if (!numeric || NUM_CHARS.indexOf(ch) >= 0) sb.append(ch);
                }
                typedText = sb.toString().trim();
            }
            @Override public void canceled() { if (id.equals(askId)) askId = null; }
        }, numeric ? "\u8f93\u5165\u6570\u5b57" : "\u8f93\u5165\u6587\u5b57", value,
            unit != null ? unit : "", Input.OnscreenKeyboardType.Default);
    }

    private void askArea(Object id, String value) {
        askId = id;
        platform.textArea("\u7f16\u8f91\u6587\u672c", value, text -> {
            if (!id.equals(askId)) return;
            askId = null;
            typedId = id;
            typedText = text;
        });
    }

    private void startEdit(String value) {
        buf.setLength(0);
        buf.append(value == null ? "" : value);
        caret = buf.length();
        selAll = false;
        selectionAnchor = -1;
        preferredColumn = -1;
        textUndo.clear();
        textRedo.clear();
    }

    private void selectAll() {
        selectionAnchor = 0;
        caret = buf.length();
        selAll = true;
    }

    private boolean hasSelection() {
        return selAll || selectionAnchor >= 0 && selectionAnchor != caret;
    }

    private int selectionStart() {
        return selAll ? 0 : Math.min(selectionAnchor, caret);
    }

    private int selectionEnd() {
        return selAll ? buf.length() : Math.max(selectionAnchor, caret);
    }

    private void clearSelection() {
        selectionAnchor = -1;
        selAll = false;
    }

    private void setCaret(int at, boolean extend) {
        at = Math.max(0, Math.min(buf.length(), at));
        if (extend) {
            if (!hasSelection()) selectionAnchor = caret;
        } else clearSelection();
        caret = at;
    }

    private void pushTextUndo() {
        String now = buf.toString();
        if (textUndo.isEmpty() || !textUndo.peekLast().equals(now)) textUndo.addLast(now);
        while (textUndo.size() > 100) textUndo.removeFirst();
        textRedo.clear();
    }

    private void replaceSelection(String text) {
        if (hasSelection()) {
            int a = selectionStart(), b = selectionEnd();
            buf.delete(a, b);
            caret = a;
            clearSelection();
        }
        buf.insert(caret, text);
        caret += text.length();
    }

    private void deleteSelection() {
        if (!hasSelection()) return;
        int a = selectionStart(), b = selectionEnd();
        buf.delete(a, b);
        caret = a;
        clearSelection();
    }

    private int caretAt(Object id, float x, float y, float w, float h, String value, boolean multiline) {
        String text = value == null ? "" : value;
        if (!multiline) {
            float target = Math.max(0f, mx - x - 10f);
            int best = 0;
            for (int i = 1; i <= text.length(); i++) {
                if (g.width(text.substring(0, i), 13) > target) break;
                best = i;
            }
            return best;
        }
        float lineHeight = g.font(13).getLineHeight();
        int line = Math.max(0, Math.min(text.split("\\n", -1).length - 1, (int)((my - y - 7f) / lineHeight)));
        int start = 0;
        for (int i = 0; i < line; i++) start = text.indexOf('\n', start) + 1;
        int end = text.indexOf('\n', start);
        if (end < 0) end = text.length();
        float target = Math.max(0f, mx - x - 9f);
        int best = start;
        for (int i = start + 1; i <= end; i++) {
            if (g.width(text.substring(start, i), 13) > target) break;
            best = i;
        }
        return best;
    }

    private int lineStart(CharSequence text, int at) {
        int i = Math.max(0, Math.min(at, text.length())) - 1;
        while (i >= 0 && text.charAt(i) != '\n') i--;
        return i + 1;
    }

    private int lineEnd(CharSequence text, int at) {
        int i = Math.max(0, Math.min(at, text.length()));
        while (i < text.length() && text.charAt(i) != '\n') i++;
        return i;
    }

    private int lineNumber(CharSequence text, int at) {
        int count = 0;
        for (int i = 0; i < Math.min(at, text.length()); i++) if (text.charAt(i) == '\n') count++;
        return count;
    }

    private int moveVertical(int direction) {
        int start = lineStart(buf, caret), end = lineEnd(buf, caret);
        if (preferredColumn < 0) preferredColumn = caret - start;
        if (direction < 0) {
            if (start == 0) return 0;
            int prevEnd = start - 1, prevStart = lineStart(buf, prevEnd);
            return Math.min(prevStart + preferredColumn, prevEnd);
        }
        if (end == buf.length()) return buf.length();
        int nextStart = end + 1, nextEnd = lineEnd(buf, nextStart);
        return Math.min(nextStart + preferredColumn, nextEnd);
    }

    private int wordLeft(int at) {
        int i = at;
        while (i > 0 && Character.isWhitespace(buf.charAt(i - 1))) i--;
        while (i > 0 && !Character.isWhitespace(buf.charAt(i - 1))) i--;
        return i;
    }

    private int wordRight(int at) {
        int i = at;
        while (i < buf.length() && !Character.isWhitespace(buf.charAt(i))) i++;
        while (i < buf.length() && Character.isWhitespace(buf.charAt(i))) i++;
        return i;
    }

    private void drawAreaSelection(float x, float y, float w, int size, String text) {
        int a = selectionStart(), b = selectionEnd(), line = 0, start = 0;
        float lh = g.font(size).getLineHeight();
        while (start <= b) {
            int end = text.indexOf('\n', start);
            if (end < 0) end = text.length();
            int sa = Math.max(a, start), sb = Math.min(b, end);
            if (sb > sa) {
                float sx = x + g.width(text.substring(start, sa), size);
                float sw = g.width(text.substring(sa, sb), size);
                g.rect(sx, y + line * lh, Math.max(2f, sw), lh, Gfx.GND, 0.32f);
            }
            if (end >= b || end == text.length()) break;
            start = end + 1;
            line++;
        }
    }

    private String editKeys(Object id, boolean numeric, boolean multiline) {
        boolean changedByTyping = false;
        for (int i = 0; i < typed.length(); i++) {
            char ch = typed.charAt(i);
            if (ch == '\r' || ch == '\n') continue; // Enter is handled once by keyDown.
            if (ch < 32 || ch == 127 || ctrl && !alt) continue;
            if (numeric && NUM_CHARS.indexOf(ch) < 0) continue;
            if (!changedByTyping) pushTextUndo();
            changedByTyping = true;
            replaceSelection(String.valueOf(ch));
            preferredColumn = -1;
        }
        for (int i = 0; i < keys.size; i++) {
            int k = keys.get(i);
            if (ctrl && !alt) {
                switch (k) {
                    case Input.Keys.A: selectAll(); continue;
                    case Input.Keys.C:
                        if (hasSelection()) Gdx.app.getClipboard().setContents(buf.substring(selectionStart(), selectionEnd()));
                        continue;
                    case Input.Keys.X:
                        if (hasSelection()) {
                            Gdx.app.getClipboard().setContents(buf.substring(selectionStart(), selectionEnd()));
                            pushTextUndo(); deleteSelection();
                        }
                        continue;
                    case Input.Keys.V: {
                        String clip = Gdx.app.getClipboard().getContents();
                        if (clip != null) {
                            if (!multiline) clip = clip.replace('\n', ' ').replace("\r", "");
                            else clip = clip.replace("\r\n", "\n").replace('\r', '\n');
                            if (numeric) {
                                StringBuilder filtered = new StringBuilder();
                                for (int n = 0; n < clip.length(); n++)
                                    if (NUM_CHARS.indexOf(clip.charAt(n)) >= 0) filtered.append(clip.charAt(n));
                                clip = filtered.toString();
                            }
                            pushTextUndo(); replaceSelection(clip); preferredColumn = -1;
                        }
                        continue;
                    }
                    case Input.Keys.Z:
                        if (shift && !textRedo.isEmpty()) {
                            textUndo.addLast(buf.toString());
                            buf.setLength(0); buf.append(textRedo.removeLast()); caret = buf.length(); clearSelection();
                        } else if (!textUndo.isEmpty()) {
                            textRedo.addLast(buf.toString());
                            buf.setLength(0); buf.append(textUndo.removeLast()); caret = buf.length(); clearSelection();
                        }
                        continue;
                    case Input.Keys.Y:
                        if (!textRedo.isEmpty()) {
                            textUndo.addLast(buf.toString());
                            buf.setLength(0); buf.append(textRedo.removeLast()); caret = buf.length(); clearSelection();
                        }
                        continue;
                    case Input.Keys.LEFT: setCaret(wordLeft(caret), shift); preferredColumn = -1; continue;
                    case Input.Keys.RIGHT: setCaret(wordRight(caret), shift); preferredColumn = -1; continue;
                    case Input.Keys.HOME: setCaret(0, shift); preferredColumn = -1; continue;
                    case Input.Keys.END: setCaret(buf.length(), shift); preferredColumn = -1; continue;
                    case Input.Keys.BACKSPACE:
                        if (hasSelection()) { pushTextUndo(); deleteSelection(); }
                        else if (caret > 0) { pushTextUndo(); buf.delete(wordLeft(caret), caret); caret = wordLeft(caret); }
                        preferredColumn = -1; continue;
                    case Input.Keys.FORWARD_DEL:
                        if (hasSelection()) { pushTextUndo(); deleteSelection(); }
                        else if (caret < buf.length()) { pushTextUndo(); int end = wordRight(caret); buf.delete(caret, end); }
                        preferredColumn = -1; continue;
                    default:
                }
            }
            switch (k) {
                case Input.Keys.BACKSPACE:
                    if (hasSelection()) { pushTextUndo(); deleteSelection(); }
                    else if (caret > 0) { pushTextUndo(); buf.deleteCharAt(--caret); }
                    preferredColumn = -1; break;
                case Input.Keys.FORWARD_DEL:
                    if (hasSelection()) { pushTextUndo(); deleteSelection(); }
                    else if (caret < buf.length()) { pushTextUndo(); buf.deleteCharAt(caret); }
                    preferredColumn = -1; break;
                case Input.Keys.LEFT:
                    setCaret(hasSelection() && !shift ? selectionStart() : caret - 1, shift); preferredColumn = -1; break;
                case Input.Keys.RIGHT:
                    setCaret(hasSelection() && !shift ? selectionEnd() : caret + 1, shift); preferredColumn = -1; break;
                case Input.Keys.UP:
                    setCaret(multiline ? moveVertical(-1) : 0, shift); break;
                case Input.Keys.DOWN:
                    setCaret(multiline ? moveVertical(1) : buf.length(), shift); break;
                case Input.Keys.HOME:
                    setCaret(ctrl ? 0 : multiline ? lineStart(buf, caret) : 0, shift); preferredColumn = -1; break;
                case Input.Keys.END:
                    setCaret(ctrl ? buf.length() : multiline ? lineEnd(buf, caret) : buf.length(), shift); preferredColumn = -1; break;
                case Input.Keys.ENTER:
                case Input.Keys.NUMPAD_ENTER:
                    if (multiline && !ctrl) {
                        pushTextUndo(); replaceSelection("\n"); preferredColumn = -1;
                    } else { focus = null; return buf.toString(); }
                    break;
                case Input.Keys.ESCAPE:
                    focus = null; return null;
                case Input.Keys.TAB: {
                    focus = null;
                    int idx = order.indexOf(id);
                    focusNext = idx >= 0 && idx + 1 < order.size() ? order.get(idx + 1) : null;
                    if (focusNext == null) lastFocusable = id;
                    return buf.toString();
                }
                default:
            }
        }
        return null;
    }

    /** A field for one number. @return the parsed value when committed, else NaN. */
    public float num(Object id, float x, float y, float w, float h, float value, String unit) {
        return num(id, x, y, w, h, value, unit, null);
    }

    public float num(Object id, float x, float y, float w, float h, float value, String unit, Color border) {
        float sv = slashNext;
        String sh = slashNextHint;
        boolean sb = slashNextBeats, set = slashSet;
        slashSet = false;
        slashNext = Float.NaN;
        slashNextHint = null;
        if (!set && "拍".equals(unit) && !Float.isNaN(slashBeat)) {     // any 拍 field: / = the playhead
            sv = slashBeat;
            sh = beatHint(slashBeat, "");
            sb = true;
        }
        slashHint = sh;
        slashBeats = sb;
        String s = field(id, x, y, w, h, fmt(value), unit, true, border);
        slashHint = null;
        slashBeats = false;
        if (sh != null && id.equals(focus)) {
            focusHint = sh;
            focusHintRect.set(x, y, w, h);
        }
        if (s == null) return Float.NaN;
        return parse(s, sv);
    }

    // ---- 特殊编号 /: the playhead (Kelvin 2026-11-05, tester feedback)

    /** Characters a number field takes: digits, sign, point, exponent and {@code /} for the playhead. */
    static final String NUM_CHARS = "0123456789.-+eE/";
    /** The playhead's beat on the snap grid, set by the editor every frame; NaN = none. */
    public float slashBeat = Float.NaN;
    private float slashNext = Float.NaN;
    private String slashNextHint, slashHint, focusHint;
    private boolean slashNextBeats, slashBeats, slashSet;
    private final Rectangle focusHintRect = new Rectangle();

    /**
     * Sets what {@code /} means in the next {@link #num} field: {@code value}, explained by {@code hint} under the
     * field (and in the tablet's number box, which adds the playhead and ±1/4 buttons when {@code beats}).
     * A NaN value turns {@code /} off for that field, even a 拍 one.
     */
    public void slash(float value, String hint, boolean beats) {
        slashSet = true;
        slashNext = value;
        slashNextHint = Float.isNaN(value) ? null : hint;
        slashNextBeats = beats;
    }

    /** @return the hint for a beat field whose {@code /} is {@code b} ({@code what}: e.g. "距判定 "). */
    static String beatHint(float b, String what) {
        return "/ = 播放头 " + what + fmt(b) + " 拍，/+1 = 后一拍，/-0.5 = 前半拍";
    }

    /**
     * Reads a number field: a plain number, or {@code /} (= {@code slash}) optionally followed by {@code +k} or
     * {@code -k}. {@code /2} and {@code 1/4} are refused so they are not mistaken for a division.
     * @return the value, or NaN with a toast
     */
    float parse(String text, float slash) {
        String t = text.trim();
        if (t.isEmpty()) return Float.NaN;
        int at = t.indexOf('/');
        if (at < 0) {
            try {
                float f = Float.parseFloat(t);
                return Float.isFinite(f) ? f : Float.NaN;
            } catch (NumberFormatException e) {
                toast("不是有效的数字：" + text);
                return Float.NaN;
            }
        }
        if (Float.isNaN(slash)) {
            toast("这个框不认 /（/ 只用在拍数和运动的数值里）");
            return Float.NaN;
        }
        String rest = t.substring(at + 1).trim();
        if (at > 0 || rest.indexOf('/') >= 0 || !rest.isEmpty() && rest.charAt(0) != '+' && rest.charAt(0) != '-') {
            toast("/ 后面只能接 + 或 -，比如 /+1、/-0.5");
            return Float.NaN;
        }
        if (rest.isEmpty()) return slash;
        try {
            float d = Float.parseFloat(rest.substring(1).trim());
            return Float.isFinite(d) ? slash + (rest.charAt(0) == '-' ? -d : d) : Float.NaN;
        } catch (NumberFormatException e) {
            toast("不是有效的数字：" + text);
            return Float.NaN;
        }
    }

    /**
     * The tablet number box's +1/4 and −1/4: moves {@code text} by {@code by}, keeping a {@code /} form
     * ({@code "/"} → {@code "/+0.25"}) and starting from {@code /} when the text is not a number.
     */
    public static String nudge(String text, float by) {
        String t = text == null ? "" : text.trim();
        if (!t.startsWith("/")) {
            try {
                return fmt(Float.parseFloat(t) + by);
            } catch (NumberFormatException e) {
                t = "/";
            }
        }
        float off = 0f;
        String rest = t.substring(1).trim();
        try {
            float sign = rest.startsWith("-") ? -1f : 1f;
            if (rest.length() > 1) off = sign * Float.parseFloat(rest.substring(1).trim());
        } catch (NumberFormatException e) {
            off = 0f;
        }
        off += by;
        return Math.abs(off) < 1e-6f ? "/" : off > 0f ? "/+" + fmt(off) : "/-" + fmt(-off);
    }

    /** @return {@code f} for display: integers without a point, else up to 4 decimals. */
    public static String fmt(float f) {
        if (Float.isNaN(f)) return "";
        if (f == Math.rint(f) && Math.abs(f) < 1e7f) return Long.toString((long) f);
        // the float's own shortest decimal (6351.24f, not its double 6351.2402…), at most 4 places
        String s = String.format(java.util.Locale.ROOT, "%.4f", Double.parseDouble(Float.toString(f)));
        while (s.endsWith("0")) s = s.substring(0, s.length() - 1);
        if (s.endsWith(".")) s = s.substring(0, s.length() - 1);
        return s;
    }

    /** A drop-down: @return the chosen index (or {@code sel}). */
    public int dropdown(Object id, float x, float y, float w, float h, String label, String[] items, int sel) {
        boolean hv = hover(x, y, w, h);
        if (hv) cursor(Cursor.SystemCursor.Hand);
        g.box(x, y, w, h, 6f, hv ? Gfx.BTN_HI : Gfx.BTN, Gfx.BTN_LINE);
        float lw = g.width(label, 13);
        g.text(label, x + w * 0.5f - 6f, y + h * 0.5f, 13, Gfx.INK1, 1);
        g.caret(x + w * 0.5f - 6f + lw * 0.5f + 9f, y + h * 0.5f, 4f, Gfx.INK2);
        if (click(id, x, y, w, h)) {
            if (id.equals(popupId)) popupId = null;
            else {
                popupId = id;
                popupItems = items;
                popupSel = sel;
                float pw = Math.max(w, 120f);
                for (String s : items) pw = Math.max(pw, g.width(s, 13) + 30f);
                float ph = items.length * 30f + 8f;
                float py = y + h + 4f;
                if (py + ph > Gfx.H - 4f) py = y - ph - 4f;
                float px = Math.min(x, Gfx.W - pw - 4f);
                popupRect.set(px, py, pw, ph);
            }
        }
        if (id.equals(popupDoneId)) {
            popupDoneId = null;
            int r = popupDone;
            popupDone = -1;
            return r;
        }
        return sel;
    }

    // ================================================================== overlay

    public void toast(String s) {
        toast = s;
        toastT = 2.6f;
    }

    public void open(Modal m) {
        modal = m;
        focus = null;
        if (m.input != null) {
            focusNext = "modal-input";
        }
    }

    /** Draws the open popup, modal and toast, and handles their input. */
    public void overlay() {
        if (panel != null) {                                         // under the hint, toast, popups and modals
            blocked = modal != null || popupId != null && popupRect.contains(mx, my);
            panel.run();
            blocked = true;
        }
        if (focusHint != null && focus != null) {                    // the focused number field's / hint
            Rectangle r = focusHintRect;
            float hw = g.width(focusHint, 11) + 16f, hx = Math.max(4f, Math.min(r.x, Gfx.W - hw - 4f));
            g.box(hx, r.y + r.height + 2f, hw, 22f, 4f, Gfx.BAR, Gfx.SEL_LINE);
            g.text(focusHint, hx + 8f, r.y + r.height + 13f, 11, Gfx.INK2);
        }
        if (toast != null && toastT > 0f) {
            float a = Math.min(1f, toastT / 0.3f);
            float tw = g.width(toast, 14) + 40f;
            float tx = (Gfx.W - tw) * 0.5f, ty = Gfx.H - 96f;
            g.rrect(tx, ty, tw, 36f, 18f, Gfx.SEL, 0.95f * a);
            g.stroke(tx, ty, tw, 36f, 18f, 1f, Gfx.GND, 0.6f * a);
            g.text(toast, Gfx.W * 0.5f, ty + 18f, 14, Gfx.INK1, a, 1);
        }
        if (popupId != null) {
            boolean was = blocked;
            blocked = false;
            Rectangle r = popupRect;
            g.box(r.x, r.y, r.width, r.height, 6f, Gfx.BAR, Gfx.SEL_LINE);
            for (int i = 0; i < popupItems.length; i++) {
                float iy = r.y + 4f + i * 30f;
                boolean hv = hover(r.x + 4f, iy, r.width - 8f, 30f);
                if (hv) cursor(Cursor.SystemCursor.Hand);
                if (hv || i == popupSel) g.rrect(r.x + 4f, iy, r.width - 8f, 30f, 4f,
                    i == popupSel ? Gfx.SEL : Gfx.BTN);
                g.text(popupItems[i], r.x + 14f, iy + 15f, 13, i == popupSel ? Gfx.INK1 : Gfx.INK2);
                if (click(new Pair(popupId, i), r.x + 4f, iy, r.width - 8f, 30f)) {
                    popupDoneId = popupId;
                    popupDone = i;
                    popupId = null;
                }
            }
            if (popupId != null && pressed && !r.contains(mx, my)) {
                // closing click: the dropdown button itself toggles; anything else just closes
                popupId = null;
            }
            if (key(Input.Keys.ESCAPE)) popupId = null;
            blocked = was;
        }
        if (modal != null) drawModal();
    }

    private void drawModal() {
        Modal m = modal;
        blocked = false;
        g.rect(0, 0, Gfx.W, Gfx.H, Gfx.BG, 0.72f);
        float w = 560f;
        float msgH = m.message != null ? 22f * (1 + m.message.length() / 34) : 0f;
        float h = 76f + msgH + (m.input != null ? 54f : 0f) + 70f;
        float x = (Gfx.W - w) * 0.5f, y = (Gfx.H - h) * 0.5f - 40f;
        g.box(x, y, w, h, 10f, Gfx.BAR, Gfx.SEL_LINE);
        g.text(m.title, x + 28f, y + 38f, 20, Gfx.INK1);
        float cy = y + 64f;
        if (m.message != null) cy += g.wrap(m.message, x + 28f, cy, w - 56f, 14, Gfx.INK2) + 6f;
        if (m.input != null) {
            String s = field("modal-input", x + 28f, cy + 6f, w - 56f, 36f, m.input, "", false);
            if (s != null) m.input = s;
            if (s != null && key(Input.Keys.ENTER)) chosenByEnter = true;
            cy += 54f;
        }
        int n = m.buttons.length;
        float bw = 150f, gap = 12f;
        float bx = x + w - 28f - n * bw - (n - 1) * gap;
        int chosen = chosenByEnter ? m.primary : -2;
        chosenByEnter = false;
        for (int i = 0; i < n; i++) {
            int style = i == m.danger ? DANGER : i == m.primary ? PRIMARY : NORMAL;
            if (button(new Pair("modal", i), bx + i * (bw + gap), y + h - 62f, bw, 40f, m.buttons[i], style)) {
                chosen = i;
            }
        }
        if (key(Input.Keys.ESCAPE) && focus == null) chosen = -1;
        if (key(Input.Keys.ENTER) && m.input == null) chosen = m.primary;
        if (chosen != -2) {
            if (focus != null && "modal-input".equals(focus)) m.input = buf.toString();
            focus = null;
            modal = null;
            m.callback.done(chosen, m.input);
        }
        blocked = true;
    }

    /** A composite widget id. */
    public static final class Pair {
        final Object a;
        final int b;

        public Pair(Object a, int b) {
            this.a = a;
            this.b = b;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Pair && ((Pair) o).a.equals(a) && ((Pair) o).b == b;
        }

        @Override
        public int hashCode() {
            return a.hashCode() * 31 + b;
        }
    }
}
