package org.kelvinizer.selectionscreen;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.utils.Align;
import org.kelvinizer.elements.Chart;

import java.util.List;

/**
 * Scrollable chart list panel with elastic (inertia + rubber-band) scrolling.
 *
 * Index convention
 * ────────────────
 *   selectedIndex == -1  →  "New Chart" entry (always at the top of the list)
 *   selectedIndex >= 0   →  charts.get(selectedIndex)
 *
 * The list is ordered:  [New Chart]  [chart 0]  [chart 1]  …
 * so "slot" = selectedIndex + 1  maps every logical index to a visual row.
 *
 * ── Layout (all values in virtual 1920×1080 pixels) ──────────────────────────
 *   PANEL_LEFT      : left edge of every item
 *   CENTER_Y        : vertical centre of the list area
 *   ITEM_WIDTH      : width of each pill button
 *   ITEM_HEIGHT     : height of each pill button
 *   ITEM_SPACING    : centre-to-centre vertical distance between items
 *
 * ── Elastic scroll parameters ────────────────────────────────────────────────
 *   FRICTION        : exponential decay rate while coasting (higher = stops faster)
 *   SNAP_SPEED      : spring constant for snap-to-item
 *   OVERSCROLL_MAX  : max pixels the list can be dragged past the ends
 *   RUBBER_STIFFNESS: pull-back rate when over edge
 */
public class ChartListPanel {

    // ── Layout ────────────────────────────────────────────────────────────────
    public static final float PANEL_LEFT    = 1100f;
    public static final float CENTER_Y      = 540f;
    public static final float ITEM_WIDTH    = 700f;
    public static final float ITEM_HEIGHT   = 110f;
    public static final float ITEM_SPACING  = 150f;

    // ── Elastic scroll ────────────────────────────────────────────────────────
    public static final float FRICTION         = 4.5f;
    public static final float SNAP_SPEED       = 12f;
    public static final float OVERSCROLL_MAX   = 200f;
    public static final float RUBBER_STIFFNESS = 10f;

    // ── Colours ───────────────────────────────────────────────────────────────
    private static final Color COLOR_SELECTED  = new Color(0.0f, 0.48f, 0.8f,  1f);
    private static final Color COLOR_NORMAL    = new Color(1f,   0.78f, 0.15f, 1f);
    private static final Color COLOR_NEW_CHART = new Color(0.55f, 0.85f, 0.35f, 1f); // distinct green

    // ── Corner radius ─────────────────────────────────────────────────────────
    public static final float CORNER_RADIUS = 55f;

    // ── State ─────────────────────────────────────────────────────────────────
    private final List<Chart> charts;

    /**
     * -1 = New Chart selected; 0..charts.size()-1 = a real chart selected.
     * Starts at -1 so New Chart is the default.
     */
    private int selectedIndex = -1;

    private final BitmapFont font; // slightly larger / italic feel via scale
    private final RoundedRectRenderer rectRenderer;

    private float scrollOffset   = 0f;
    private float scrollVelocity = 0f;
    private boolean isDragging   = false;
    private float lastDragY      = 0f;
    private boolean isSnapping   = false;

    // ── Total item count (New Chart + real charts) ────────────────────────────
    private int totalItems() { return 1 + charts.size(); }

    /** Maps selectedIndex (-1 … n-1) to a slot index (0 … n). */
    private int toSlot(int idx) { return idx + 1; }

    /** Maps a slot index back to selectedIndex. */
    private int fromSlot(int slot) { return slot - 1; }

    public ChartListPanel(List<Chart> charts, BitmapFont font) {
        this.charts       = charts;
        this.font         = font;

        // New Chart font: same scale as the main font but 25% larger on top

        this.rectRenderer = new RoundedRectRenderer();
    }

    // ── Keyboard navigation ───────────────────────────────────────────────────

    /**
     * Move selection by delta steps.
     * Clamps to [-1, charts.size()-1].
     */
    public void moveSelection(int delta) {
        int minIdx = -1;
        int maxIdx = charts.isEmpty() ? -1 : charts.size() - 1;
        selectedIndex  = MathUtils.clamp(selectedIndex + delta, minIdx, maxIdx);
        scrollOffset   = 0f;
        scrollVelocity = 0f;
    }

    /** Returns -1 for New Chart, or 0-based chart index. */
    public int getSelectedIndex() { return selectedIndex; }

    public boolean isNewChartSelected() { return selectedIndex == -1; }

    /**
     * Call after {@code charts.remove(index)} so selection and scroll state stay valid.
     * The panel holds a live reference to the same list instance as {@link SelectionScreen}.
     */
    public void afterChartRemovedAt(int removedIndex) {
        if (selectedIndex > removedIndex) {
            selectedIndex--;
        } else if (selectedIndex == removedIndex) {
            if (charts.isEmpty()) {
                selectedIndex = -1;
            } else if (removedIndex >= charts.size()) {
                selectedIndex = charts.size() - 1;
            }
            // else: same index now points at the chart that slid up
        }
        scrollOffset = 0f;
        scrollVelocity = 0f;
        isSnapping = false;
    }

    // ── Touch / drag input ────────────────────────────────────────────────────

    public void touchDown(float worldY) {
        isDragging     = true;
        isSnapping     = false;
        lastDragY      = worldY;
        scrollVelocity = 0f;
    }

    public void touchDragged(float worldY) {
        if (!isDragging) return;
        float dy = worldY - lastDragY;
        lastDragY = worldY;

        // Rubber-band resistance at edges
        float maxOff = maxScrollOffset();
        float minOff = minScrollOffset();
        if ((scrollOffset > maxOff && dy < 0) || (scrollOffset < minOff && dy > 0)) {
            dy *= 0.3f;
        }

        // Drag direction: dragging finger UP (dy < 0 in libGDX Y-up coords)
        // should scroll the list UP (positive offset = list moves up).
        // So: scrollOffset += dy  (reversed from before)
        scrollOffset   += dy;
        scrollVelocity  = dy / Gdx.graphics.getDeltaTime();
    }

    /**
     * Mouse-wheel scroll. amountY > 0 = wheel down = list scrolls down (towards later items).
     * Each notch scrolls by ITEM_SPACING / 3 pixels.
     */
    public void scroll(float amountY) {
        scrollOffset   += amountY * ITEM_SPACING / 3f;
        scrollVelocity  = 0f;
        isSnapping      = false;
    }

    public void touchUp() {
        isDragging = false;
        isSnapping = false;
    }

    // ── Scroll bounds ─────────────────────────────────────────────────────────

    /**
     * Maximum scroll offset: how far we can scroll "down" (list moves up).
     * selectedIndex is at slot toSlot(selectedIndex).
     * The last item is at slot totalItems()-1.
     * Distance from current slot to last slot, in pixels.
     */
    private float maxScrollOffset() {
        int lastSlot    = totalItems() - 1;
        int currentSlot = toSlot(selectedIndex);
        return (lastSlot - currentSlot) * ITEM_SPACING;
    }

    /**
     * Minimum scroll offset: how far we can scroll "up" (list moves down).
     * Distance from current slot to first slot (0), negative.
     */
    private float minScrollOffset() {
        int currentSlot = toSlot(selectedIndex);
        return -currentSlot * ITEM_SPACING;
    }

    // ── Update ────────────────────────────────────────────────────────────────

    public void update(float delta) {
        if (isDragging) return;

        if (isSnapping) {
            float diff = -scrollOffset;   // snap target is always 0 after index commit
            scrollOffset += diff * Math.min(SNAP_SPEED * delta, 1f);
            if (Math.abs(diff) < 0.5f) {
                scrollOffset = 0f;
                isSnapping   = false;
            }
        } else {
            // Coast
            scrollVelocity *= (float) Math.exp(-FRICTION * delta);
            scrollOffset   += scrollVelocity * delta;

            float maxOff = maxScrollOffset();
            float minOff = minScrollOffset();

            // Hard clamp + rubber-band
            if (scrollOffset > maxOff + OVERSCROLL_MAX) {
                scrollOffset   = maxOff + OVERSCROLL_MAX;
                scrollVelocity = 0f;
            }
            if (scrollOffset < minOff - OVERSCROLL_MAX) {
                scrollOffset   = minOff - OVERSCROLL_MAX;
                scrollVelocity = 0f;
            }

            if (scrollOffset > maxOff) {
                scrollOffset -= (scrollOffset - maxOff) * Math.min(RUBBER_STIFFNESS * delta, 1f);
            } else if (scrollOffset < minOff) {
                scrollOffset -= (scrollOffset - minOff) * Math.min(RUBBER_STIFFNESS * delta, 1f);
            }

            // Snap when slow enough
            if (Math.abs(scrollVelocity) < 50f) {
                // Commit nearest item
                int slotDelta = Math.round(scrollOffset / ITEM_SPACING);
                int newSlot   = MathUtils.clamp(
                    toSlot(selectedIndex) + slotDelta, 0, totalItems() - 1);
                selectedIndex  = fromSlot(newSlot);
                scrollOffset  -= slotDelta * ITEM_SPACING;
                isSnapping     = true;
                scrollVelocity = 0f;
            }
        }
    }

    // ── Render ────────────────────────────────────────────────────────────────

    /**
     * Renders the list. The SpriteBatch must already be begun.
     * ShapeRenderer calls are sandwiched between batch.end() / batch.begin().
     */
    public void render(SpriteBatch batch, float delta) {
        update(delta);

        int halfVisible = (int) (CENTER_Y / ITEM_SPACING) + 2;
        int currentSlot = toSlot(selectedIndex);

        for (int di = -halfVisible; di <= halfVisible; di++) {
            int slot = currentSlot + di;
            if (slot < 0 || slot >= totalItems()) continue;

            float itemCenterY = CENTER_Y - di * ITEM_SPACING + scrollOffset;
            if (itemCenterY + ITEM_HEIGHT / 2f < 0 || itemCenterY - ITEM_HEIGHT / 2f > 1080f) continue;

            int idx = fromSlot(slot);   // -1 = New Chart, else chart index

            boolean isSelected = (di == 0 && Math.abs(scrollOffset) < ITEM_SPACING / 2f);

            if (idx == -1) {
                // New Chart entry
                Color color = isSelected ? COLOR_SELECTED : COLOR_NEW_CHART;
                drawItem(batch, "New Chart", PANEL_LEFT, itemCenterY, color, font);
            } else {
                Color color = isSelected ? COLOR_SELECTED : COLOR_NORMAL;
                drawItem(batch, charts.get(idx).getTitle(), PANEL_LEFT, itemCenterY, color, font);
            }
        }
    }

    private void drawItem(SpriteBatch batch, String text,
                          float leftX, float centerY,
                          Color color, BitmapFont labelFont) {
        batch.end();

        rectRenderer.setProjectionMatrix(batch.getProjectionMatrix());
        rectRenderer.draw(leftX, centerY - ITEM_HEIGHT / 2f,
            ITEM_WIDTH, ITEM_HEIGHT, CORNER_RADIUS, color);

        batch.begin();

        labelFont.setColor(Color.BLACK);
        labelFont.draw(batch, text,
            leftX + ITEM_WIDTH / 2f,
            centerY + labelFont.getCapHeight() / 2f,
            0, Align.center, false);
    }

    public void dispose() {
        rectRenderer.dispose();
    }
}
