package org.kelvinizer.charter;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.utils.viewport.FitViewport;
import org.kelvinizer.gameplay.CharterPreview;
import org.kelvinizer.ui.Draw;

/**
 * K&K 谱面编辑器程序：建好绘图、输入和曲库，在曲库和编辑器两个界面之间切换。
 * The K&K chart editor application: creates the shared drawing helpers, the input handler and
 * the library, and runs the current screen (library or editor) on a 1920 × 1080 canvas (1344 × 840 on a
 * tablet) that is fitted into the (resizable) window.
 */
public final class CharterApp extends ApplicationAdapter {
    /** One screen of the editor. */
    interface View {
        /** Draws the screen and handles its input (inside the batch's begin / end). */
        void frame(float dt);

        /** The window wants to close: run {@code quit} when that is fine (maybe after asking). */
        void close(Runnable quit);

        void dispose();
    }

    final Platform platform;
    Draw draw;
    Gfx g;
    Im im;
    OrthographicCamera cam;
    FitViewport vp;
    Library lib;
    CharterPreview preview;
    private View view, next;
    private boolean quitting;
    /** Optional self-test: screenshots of each screen, then exit (see the launcher). */
    private final AutoTest test;

    public CharterApp(Platform platform, String autotestDir) {
        this.platform = platform;
        this.test = autotestDir != null ? new AutoTest(this, autotestDir) : null;
    }

    @Override
    public void create() {
        draw = new Draw();
        g = new Gfx(draw);
        cam = new OrthographicCamera();
        vp = new FitViewport(Gfx.W, Gfx.H, cam);
        vp.update(Gdx.graphics.getWidth(), Gdx.graphics.getHeight(), true);
        g.pixelsPerUnit(vp.getScreenWidth() / Gfx.W);
        im = new Im(g, vp, platform);
        Gdx.input.setInputProcessor(im);
        Gdx.input.setCatchKey(Input.Keys.BACK, true); // Android's back key goes to the screens, not out of the app
        platform.prepare();
        preview = new CharterPreview(draw);
        lib = Library.open();
        lib.scan();
        view = new LibraryScreen(this, null);
    }

    void show(View v) {
        next = v;
    }

    View view() {
        return view;
    }

    @Override
    public void resize(int width, int height) {
        if (width > 0 && height > 0) {
            vp.update(width, height, true);
            g.pixelsPerUnit(vp.getScreenWidth() / Gfx.W);
        }
    }

    /** The app goes to the background (Android may then close it): keep a copy of unsaved work. */
    @Override
    public void pause() {
        if (view instanceof EditorScreen) ((EditorScreen) view).autosaveNow();
    }

    @Override
    public void render() {
        float dt = Math.min(Gdx.graphics.getDeltaTime(), 0.1f);
        Gdx.gl.glClearColor(0.01f, 0.02f, 0.07f, 1f);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);
        vp.apply();
        g.batch.setProjectionMatrix(cam.combined);
        g.batch.begin();
        g.rect(0f, 0f, Gfx.W, Gfx.H, Gfx.BG);
        if (test != null) test.before();
        im.begin(dt);
        view.frame(dt);
        im.overlay();
        g.batch.end();
        im.end();
        if (test != null) test.after();
        if (next != null) {
            view.dispose();
            view = next;
            next = null;
        }
    }

    /**
     * Called by the launcher when the window is asked to close.
     *
     * @return true to close now; false to keep the window (the screen may ask about unsaved work first)
     */
    public boolean closeRequested() {
        if (quitting || view == null) return true;
        view.close(() -> {
            quitting = true;
            Gdx.app.exit();
        });
        return quitting;
    }

    @Override
    public void dispose() {
        if (view != null) view.dispose();
        preview.dispose();
        g.dispose();
        draw.dispose();
    }
}
