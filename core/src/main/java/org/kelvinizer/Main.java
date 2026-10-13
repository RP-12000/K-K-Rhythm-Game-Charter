package org.kelvinizer;

import com.badlogic.gdx.Game;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.utils.viewport.FitViewport;
import org.kelvinizer.chartEditor.ChartEditorScreen;
import org.kelvinizer.selectionscreen.SelectionScreen;

/**
 * LibGDX application entry. Owns the shared {@link com.badlogic.gdx.graphics.g2d.SpriteBatch}
 * and {@link com.badlogic.gdx.utils.viewport.FitViewport} used by {@link org.kelvinizer.selectionscreen.SelectionScreen}.
 *
 * <p>Navigation is screen-based: {@link org.kelvinizer.selectionscreen.SelectionScreen}
 * ↔ {@link ChartEditorScreen} via {@link com.badlogic.gdx.Game#setScreen}.
 */
public class Main extends Game {
    private SpriteBatch batch;
    private FitViewport viewport;

    /** Virtual canvas size – everything is authored at 1920×1080. */
    public static final float WORLD_WIDTH  = 1920f;
    public static final float WORLD_HEIGHT = 1080f;

    public SpriteBatch getBatch() { return batch; }
    public FitViewport getViewport() { return viewport; }

    @Override
    public void create() {
        batch    = new SpriteBatch();
        viewport = new FitViewport(WORLD_WIDTH, WORLD_HEIGHT);
        setScreen(new SelectionScreen(this));
    }

    @Override
    public void resize(int width, int height) {
        viewport.update(width, height, true);
        super.resize(width, height);
    }

    @Override
    public void dispose() {
        batch.dispose();
        super.dispose();
    }
}
