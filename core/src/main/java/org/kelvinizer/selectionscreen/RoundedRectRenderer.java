package org.kelvinizer.selectionscreen;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer.ShapeType;
import com.badlogic.gdx.math.MathUtils;

/**
 * Draws a filled rounded rectangle using two half-circles + one centre rectangle.
 * Works entirely with ShapeRenderer – no texture required.
 *
 * Coordinate convention: (x, y) is the bottom-left corner of the bounding box.
 */
public class RoundedRectRenderer {

    /** Number of triangle-fan segments per half-circle. Higher = smoother. */
    private static final int SEGMENTS = 32;

    private final ShapeRenderer sr;

    public RoundedRectRenderer() {
        sr = new ShapeRenderer();
    }

    /**
     * Draw a filled rounded rectangle.
     *
     * @param x      bottom-left x
     * @param y      bottom-left y
     * @param w      total width
     * @param h      total height
     * @param radius corner radius (clamped to h/2 automatically)
     * @param color  fill colour
     */
    public void draw(float x, float y, float w, float h, float radius, Color color) {
        float r = Math.min(radius, h / 2f);

        sr.begin(ShapeType.Filled);
        sr.setColor(color);

        // Centre rectangle (between the two semicircles)
        sr.rect(x + r, y, w - 2 * r, h);

        // Left semicircle  – centre at (x+r, y+h/2)
        drawSemiCircle(x + r,       y + h / 2f, r, 90f,  180f);

        // Right semicircle – centre at (x+w-r, y+h/2)
        drawSemiCircle(x + w - r,   y + h / 2f, r, 270f, 180f);

        sr.end();
    }

    /** Draws a filled semicircle (fan) starting at startAngle, sweeping sweepDeg degrees. */
    private void drawSemiCircle(float cx, float cy, float r, float startAngle, float sweepDeg) {
        float step = sweepDeg / SEGMENTS;
        for (int i = 0; i < SEGMENTS; i++) {
            float a0 = startAngle + i * step;
            float a1 = a0 + step;
            sr.triangle(
                cx, cy,
                cx + r * MathUtils.cosDeg(a0), cy + r * MathUtils.sinDeg(a0),
                cx + r * MathUtils.cosDeg(a1), cy + r * MathUtils.sinDeg(a1)
            );
        }
    }

    /**
     * Must be called with the same projection matrix that the SpriteBatch uses,
     * so shapes land in the same virtual-coordinate space.
     */
    public void setProjectionMatrix(com.badlogic.gdx.math.Matrix4 matrix) {
        sr.setProjectionMatrix(matrix);
    }

    public void dispose() {
        sr.dispose();
    }
}
