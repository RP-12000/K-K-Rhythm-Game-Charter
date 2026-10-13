package org.kelvinizer.selectionscreen;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.math.MathUtils;

/**
 * Renders N blue-particle sprites that random-walk across the background.
 * Each particle is cropped to a circle of PARTICLE_RADIUS virtual pixels.
 *
 * ── Tunable parameters ────────────────────────────────────────────────────────
 *   PARTICLE_COUNT      : number of particles on screen at once
 *   PARTICLE_RADIUS     : drawn radius in virtual pixels (circle crop)
 *   PARTICLE_ALPHA      : opacity of each particle (0–1)
 *   WANDER_SPEED        : max pixels/second each particle drifts
 *   WANDER_TURN_RATE    : max radians/second the heading can change (higher = jerkier)
 *   WANDER_TURN_INTERVAL: seconds between heading nudges
 */
public class ParticleBackground {

    // ── Parameters ────────────────────────────────────────────────────────────
    public static final int   PARTICLE_COUNT       = 18;
    public static final float PARTICLE_RADIUS      = 15f;    // virtual px radius
    public static final float PARTICLE_ALPHA       = 0.55f;
    public static final float WANDER_SPEED         = 60f;    // px/s
    public static final float WANDER_TURN_RATE     = 1.8f;   // rad/s max turn
    public static final float WANDER_TURN_INTERVAL = 0.8f;   // seconds between nudges

    // ── World bounds (virtual canvas) ─────────────────────────────────────────
    private final float worldW;
    private final float worldH;

    // ── Per-particle state ────────────────────────────────────────────────────
    private final float[] px;
    private final float[] py;
    private final float[] heading;
    private final float[] turnTimer;

    /**
     * Circle-masked texture: we bake the source texture into a new Pixmap
     * with pixels outside the inscribed circle set to transparent.
     */
    private final Texture circleTexture;
    private final float   drawSize;   // diameter in virtual pixels

    public ParticleBackground(Texture sourceTexture, float worldW, float worldH) {
        this.worldW = worldW;
        this.worldH = worldH;

        // Bake a circle-masked copy of the source texture
        circleTexture = buildCircleTexture(sourceTexture);
        drawSize      = PARTICLE_RADIUS * 2f;

        px        = new float[PARTICLE_COUNT];
        py        = new float[PARTICLE_COUNT];
        heading   = new float[PARTICLE_COUNT];
        turnTimer = new float[PARTICLE_COUNT];

        for (int i = 0; i < PARTICLE_COUNT; i++) {
            px[i]        = MathUtils.random(0f, worldW);
            py[i]        = MathUtils.random(0f, worldH);
            heading[i]   = MathUtils.random(0f, MathUtils.PI2);
            turnTimer[i] = MathUtils.random(0f, WANDER_TURN_INTERVAL);
        }
    }

    /**
     * Reads the source texture into a Pixmap, masks every pixel outside the
     * inscribed circle to alpha=0, and returns a new Texture from that Pixmap.
     */
    private static Texture buildCircleTexture(Texture src) {
        // Force the texture to be readable
        if (!src.getTextureData().isPrepared()) {
            src.getTextureData().prepare();
        }
        Pixmap srcPm = src.getTextureData().consumePixmap();

        int w  = srcPm.getWidth();
        int h  = srcPm.getHeight();
        float cx = w / 2f;
        float cy = h / 2f;
        float r  = Math.min(cx, cy);

        Pixmap dst = new Pixmap(w, h, Pixmap.Format.RGBA8888);
        dst.setBlending(Pixmap.Blending.None);

        for (int x = 0; x < w; x++) {
            for (int y = 0; y < h; y++) {
                float dx = x - cx;
                float dy = y - cy;
                if (dx * dx + dy * dy <= r * r) {
                    dst.drawPixel(x, y, srcPm.getPixel(x, y));
                }
                // else leave transparent (RGBA8888 default is 0x00000000)
            }
        }

        srcPm.dispose();
        Texture t = new Texture(dst);
        dst.dispose();
        return t;
    }

    public void update(float delta) {
        for (int i = 0; i < PARTICLE_COUNT; i++) {
            turnTimer[i] -= delta;
            if (turnTimer[i] <= 0f) {
                heading[i]   += MathUtils.random(-WANDER_TURN_RATE, WANDER_TURN_RATE);
                turnTimer[i]  = WANDER_TURN_INTERVAL;
            }

            px[i] += MathUtils.cos(heading[i]) * WANDER_SPEED * delta;
            py[i] += MathUtils.sin(heading[i]) * WANDER_SPEED * delta;

            // Toroidal wrap
            if (px[i] < -drawSize) px[i] += worldW + drawSize;
            if (px[i] > worldW)    px[i] -= worldW + drawSize;
            if (py[i] < -drawSize) py[i] += worldH + drawSize;
            if (py[i] > worldH)    py[i] -= worldH + drawSize;
        }
    }

    public void render(SpriteBatch batch) {
        Color saved = batch.getColor().cpy();
        batch.setColor(1f, 1f, 1f, PARTICLE_ALPHA);

        for (int i = 0; i < PARTICLE_COUNT; i++) {
            // Centre the circle on (px, py)
            batch.draw(circleTexture,
                px[i] - PARTICLE_RADIUS,
                py[i] - PARTICLE_RADIUS,
                drawSize, drawSize);
        }

        batch.setColor(saved);
    }

    public void dispose() {
        circleTexture.dispose();
    }
}
