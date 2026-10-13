package org.kelvinizer.transition;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.math.Interpolation;
import com.badlogic.gdx.math.MathUtils;

/**
 * Reusable curtain transition: upper/lower panels close, ray burst from two dots
 * on the horizontal midline, a brief fully-covered hold, then panels open.
 *
 * <h3>Phase sequence (outgoing screen)</h3>
 * <pre>
 *   CLOSING → LINES → COVERED (hold) → Runnable callback (typically setScreen)
 * </pre>
 *
 * <h3>Incoming screen</h3>
 * Call {@link #beginOpening()} when the new screen is constructed so panels
 * start closed and then open on the first frames of the new screen.
 *
 * <p>Dot placement uses {@link Config#dotOffsetFromCenter} relative to screen center:
 * left dot at {@code centerX - offset}, right dot at {@code centerX + offset}.
 * A positive offset spreads dots apart; a negative offset moves the "left" dot
 * past center while it still shoots leftward.
 *
 * <p>The {@link #COVERED} phase exists to prevent a one-frame flash of the old
 * screen underlay: callbacks are posted with {@link com.badlogic.gdx.Gdx#app
 * Gdx.app.postRunnable} only after the hold, while panels still fully obscure
 * the framebuffer.
 */
public class CurtainTransition {

    public enum Phase { IDLE, CLOSING, LINES, COVERED, OPENING }

    public static final class Config {
        public float worldWidth  = 1920f;
        public float worldHeight = 1080f;
        public float closeDuration = 0.45f;
        public float openDuration  = 0.45f;
        public float dotOffsetFromCenter = 0f;
        public float lineThickness = 4f;
        public float dotRadius = 8f;
        public Color lineColor = new Color(0.05f, 0.15f, 0.45f, 1f);
        public float lineSpeed = 2200f;
        /** Frames to keep panels closed after rays finish, before {@code onCloseComplete}. */
        public int holdFramesAfterLines = 1;

        public Config worldSize(float w, float h) {
            worldWidth = w;
            worldHeight = h;
            return this;
        }

        public Config closeDuration(float seconds) {
            closeDuration = seconds;
            return this;
        }

        public Config openDuration(float seconds) {
            openDuration = seconds;
            return this;
        }

        public Config dotOffsetFromCenter(float offset) {
            dotOffsetFromCenter = offset;
            return this;
        }

        public Config lineThickness(float thickness) {
            lineThickness = thickness;
            return this;
        }

        public Config dotRadius(float radius) {
            dotRadius = radius;
            return this;
        }

        public Config lineColor(Color color) {
            lineColor = color;
            return this;
        }

        public Config lineSpeed(float speed) {
            lineSpeed = speed;
            return this;
        }

        public Config holdFramesAfterLines(int frames) {
            holdFramesAfterLines = frames;
            return this;
        }
    }

    private final Config config;
    private final Texture texUpper;
    private final Texture texLower;

    private Phase phase = Phase.IDLE;
    private float timer = 0f;
    private float lineProgress = 0f;
    private int holdFrames = 0;
    private Runnable pendingCallback;
    private boolean switchScheduled = false;

    public CurtainTransition(Config config, Texture texUpper, Texture texLower) {
        this.config = config;
        this.texUpper = texUpper;
        this.texLower = texLower;
    }

    public Phase getPhase() { return phase; }

    public boolean isActive() { return phase != Phase.IDLE; }

    /** True while panels fully cover the screen (LINES finished, waiting to switch). */
    public boolean isFullyClosed() {
        return phase == Phase.COVERED;
    }

    /** Close panels, burst rays, hold covered, then invoke {@code onCloseComplete}. */
    public void startClose(Runnable onCloseComplete) {
        if (phase != Phase.IDLE) return;
        pendingCallback = onCloseComplete;
        switchScheduled = false;
        phase = Phase.CLOSING;
        timer = 0f;
        lineProgress = 0f;
        holdFrames = 0;
    }

    /** Begin opening immediately (e.g. when a new screen first appears covered). */
    public void beginOpening() {
        pendingCallback = null;
        switchScheduled = false;
        phase = Phase.OPENING;
        timer = 0f;
        lineProgress = 0f;
        holdFrames = 0;
    }

    /** Open panels from the closed state, then invoke {@code onOpenComplete}. */
    public void startOpen(Runnable onOpenComplete) {
        if (phase != Phase.IDLE) return;
        pendingCallback = onOpenComplete;
        switchScheduled = false;
        phase = Phase.OPENING;
        timer = 0f;
    }

    public void update(float delta) {
        switch (phase) {
            case CLOSING:
                timer += delta;
                if (timer >= config.closeDuration) {
                    timer = 0f;
                    lineProgress = 0f;
                    phase = Phase.LINES;
                }
                break;

            case LINES:
                lineProgress += config.lineSpeed * delta;
                if (lineProgress >= maxLineDistance()) {
                    lineProgress = maxLineDistance();
                    holdFrames = config.holdFramesAfterLines;
                    switchScheduled = false;
                    phase = Phase.COVERED;
                }
                break;

            case COVERED:
                if (holdFrames > 0) {
                    holdFrames--;
                    break;
                }
                scheduleSwitchOnce();
                break;

            case OPENING:
                timer += delta;
                if (timer >= config.openDuration) {
                    timer = config.openDuration;
                    finishOpening();
                }
                break;

            default:
                break;
        }
    }

  /** Defer screen switch by one frame while keeping panels drawn (prevents underlay flash). */
    private void scheduleSwitchOnce() {
        if (switchScheduled || pendingCallback == null) return;
        switchScheduled = true;
        Runnable cb = pendingCallback;
        pendingCallback = null;
        Gdx.app.postRunnable(cb);
    }

    private void finishOpening() {
        Runnable cb = pendingCallback;
        pendingCallback = null;
        phase = Phase.IDLE;
        timer = 0f;
        if (cb != null) cb.run();
    }

    public float leftDotX() {
        return config.worldWidth / 2f - config.dotOffsetFromCenter;
    }

    public float rightDotX() {
        return config.worldWidth / 2f + config.dotOffsetFromCenter;
    }

    public float dotY() {
        return config.worldHeight / 2f;
    }

    public float maxLineDistance() {
        float leftX = leftDotX();
        float rightX = rightDotX();
        return Math.max(leftX, config.worldWidth - rightX);
    }

    public void render(SpriteBatch batch, ShapeRenderer sr) {
        if (phase == Phase.IDLE) return;

        GdxEnableBlend();

        switch (phase) {
            case CLOSING: {
                float t = MathUtils.clamp(timer / config.closeDuration, 0f, 1f);
                float ease = Interpolation.exp5Out.apply(t);
                float topY = config.worldHeight - ease * (config.worldHeight / 2f);
                float bottomY = ease * (config.worldHeight / 2f);
                drawPanels(batch, topY, bottomY);
                break;
            }
            case LINES:
            case COVERED: {
                float mid = config.worldHeight / 2f;
                drawPanels(batch, mid, mid);
                drawRays(sr);
                break;
            }
            case OPENING: {
                float t = MathUtils.clamp(timer / config.openDuration, 0f, 1f);
                float ease = Interpolation.exp5In.apply(t);
                float topY = config.worldHeight / 2f + ease * (config.worldHeight / 2f);
                float bottomY = config.worldHeight / 2f - ease * (config.worldHeight / 2f);
                drawPanels(batch, topY, bottomY);
                break;
            }
            default:
                break;
        }

        GdxDisableBlend();
    }

    private void drawPanels(SpriteBatch batch, float topY, float bottomY) {
        batch.begin();
        float upperH = config.worldHeight - topY;
        if (upperH > 0f) {
            batch.draw(texUpper, 0f, topY, config.worldWidth, upperH);
        }
        if (bottomY > 0f) {
            batch.draw(texLower, 0f, 0f, config.worldWidth, bottomY);
        }
        batch.end();
    }

    private void drawRays(ShapeRenderer sr) {
        float leftX = leftDotX();
        float rightX = rightDotX();
        float cy = dotY();

        sr.begin(ShapeRenderer.ShapeType.Filled);
        sr.setColor(config.lineColor);

        sr.circle(leftX, cy, config.dotRadius, 24);
        sr.circle(rightX, cy, config.dotRadius, 24);

        float leftEnd = Math.max(leftX - lineProgress, 0f);
        if (leftEnd < leftX) {
            sr.rectLine(leftX, cy, leftEnd, cy, config.lineThickness);
        }

        float rightEnd = Math.min(rightX + lineProgress, config.worldWidth);
        if (rightEnd > rightX) {
            sr.rectLine(rightX, cy, rightEnd, cy, config.lineThickness);
        }

        sr.end();
    }

    private static void GdxEnableBlend() {
        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
    }

    private static void GdxDisableBlend() {
        Gdx.gl.glDisable(GL20.GL_BLEND);
    }
}
