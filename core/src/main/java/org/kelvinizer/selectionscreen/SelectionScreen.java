package org.kelvinizer.selectionscreen;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.InputAdapter;
import com.badlogic.gdx.ScreenAdapter;
import com.badlogic.gdx.audio.Music;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.g2d.freetype.FreeTypeFontGenerator;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.Align;
import com.badlogic.gdx.utils.viewport.FitViewport;
import org.kelvinizer.elements.Chart;
import org.kelvinizer.chartEditor.ChartEditorScreen;
import org.kelvinizer.elements.ChartMapper;
import org.kelvinizer.Main;
import org.kelvinizer.transition.CurtainTransition;
import javax.swing.JFileChooser;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/**
 * Selection screen – fixed virtual canvas 1920×1080.
 *
 * ═══════════════════════════════════════════════════════════════════════════
 *  LAYOUT PARAMETERS  (all in virtual pixels)
 * ═══════════════════════════════════════════════════════════════════════════
 *  JACKET_X, JACKET_Y      : bottom-left of the jacket image
 *  JACKET_SIZE             : width = height of the jacket square
 *  BTN_Y                   : vertical centre of Edit / Delete buttons
 *  BTN_WIDTH / BTN_HEIGHT  : size of each small button
 *  BTN_EDIT_X              : left edge of Edit button
 *  BTN_DELETE_X            : left edge of Delete button
 *  BTN_CORNER_RADIUS       : corner radius for small buttons
 *  CREATE_BTN_X/Y          : left edge / centre-Y of the "Create with .wav" button
 *  CREATE_BTN_W/H          : size of the create button
 *
 * ═══════════════════════════════════════════════════════════════════════════
 *  TRANSITION PARAMETERS
 * ═══════════════════════════════════════════════════════════════════════════
 *  TRANS_CLOSE_DURATION    : seconds for the two panels to meet in the middle
 *  TRANS_OPEN_DURATION     : seconds for the two panels to separate again
 *  TRANS_LINE_SPEED        : pixels / second the blue lines travel outward
 *  TRANS_LINE_ORIGIN_X/Y   : where the two blue lines start
 *  TRANS_LINE_THICKNESS    : stroke width of the blue lines
 *  TRANS_DOT_RADIUS        : radius of the dot at the line origin
 *  TRANS_LINE_COLOR        : colour of the lines and dot
 *
 * ═══════════════════════════════════════════════════════════════════════════
 *  FONT PARAMETERS
 * ═══════════════════════════════════════════════════════════════════════════
 *  FONT_SIZE               : point size passed to FreeTypeFontGenerator (Arial)
 */
public class SelectionScreen extends ScreenAdapter {

    // ── Virtual canvas ────────────────────────────────────────────────────────
    private static final float W = Main.WORLD_WIDTH;   // 1920
    private static final float H = Main.WORLD_HEIGHT;  // 1080

    // ── Font ──────────────────────────────────────────────────────────────────
    /** Point size for the Arial font. Increase to make text larger. */
    private static final int FONT_SIZE = 28;

    // ── Layout ────────────────────────────────────────────────────────────────
    private static final float JACKET_X    = 180f;
    private static final float JACKET_Y    = 240f;
    private static final float JACKET_SIZE = 700f;

    private static final float BTN_Y             = 150f;
    private static final float BTN_WIDTH         = 330f;
    private static final float BTN_HEIGHT        = 80f;
    private static final float BTN_EDIT_X        = 180f;
    private static final float BTN_DELETE_X      = 550f;
    private static final float BTN_CORNER_RADIUS = 40f;

    private static final float CREATE_BTN_X      = 180f;
    private static final float CREATE_BTN_Y      = 150f;
    private static final float CREATE_BTN_W      = 700f;
    private static final float CREATE_BTN_H      = 80f;
    private static final float CREATE_BTN_CORNER = 40f;

    // ── Transition ────────────────────────────────────────────────────────────
    private CurtainTransition curtainTransition;

    private Chart pendingChart = null;

    // ── Core ──────────────────────────────────────────────────────────────────
    private final Main          main;
    private final FitViewport   viewport;
    private final SpriteBatch   batch;
    private final ShapeRenderer sr;
    private final BitmapFont    font;
    private final RoundedRectRenderer rectRenderer;

    // ── Textures ──────────────────────────────────────────────────────────────
    private Texture texBackground;
    private Texture texJacket;
    private Texture texParticle;
    private Texture texTransUpper;
    private Texture texTransLower;

    // ── Scene objects ─────────────────────────────────────────────────────────
    private final ArrayList<Chart> charts = new ArrayList<>();
    private ChartListPanel     chartPanel;
    private ParticleBackground particles;

    /** Chart whose jacket is currently bound to {@link #texJacket}, or {@code null} for default.png. */
    private Chart displayedJacketChart = null;

    // ── Button colours ────────────────────────────────────────────────────────
    private static final Color BTN_COLOR    = new Color(1f, 0.78f, 0.15f, 1f);
    private static final Color CREATE_COLOR = new Color(0.55f, 0.85f, 0.35f, 1f);

    // ─────────────────────────────────────────────────────────────────────────

    public SelectionScreen(Main main) {
        this(main, false);
    }

    /**
     * @param withOpeningTransition when {@code true}, the curtain opens on entry
     *        (used when returning from {@link ChartEditorScreen}).
     */
    public SelectionScreen(Main main, boolean withOpeningTransition) {
        this.main      = main;
        this.viewport  = main.getViewport();
        this.batch     = main.getBatch();
        this.sr        = new ShapeRenderer();
        this.rectRenderer = new RoundedRectRenderer();

        this.font = buildFont();

        loadTextures();
        curtainTransition = buildTransition();
        if (withOpeningTransition) {
            curtainTransition.beginOpening();
        }
        loadAllCharts();
        preloadChartMusic();

        chartPanel = new ChartListPanel(charts, font);
        particles  = new ParticleBackground(texParticle, W, H);

        Gdx.input.setInputProcessor(new InputAdapter() {

            @Override
            public boolean keyDown(int keycode) {
                if (curtainTransition.isActive()) return false;
                if (keycode == Input.Keys.DOWN)  { chartPanel.moveSelection(1);  return true; }
                if (keycode == Input.Keys.UP)    { chartPanel.moveSelection(-1); return true; }
                if (keycode == Input.Keys.ENTER) { handleConfirm(); return true; }
                return false;
            }

            @Override
            public boolean touchDown(int screenX, int screenY, int pointer, int button) {
                if (curtainTransition.isActive()) return false;
                Vector3 w = viewport.unproject(new Vector3(screenX, screenY, 0));

                if (chartPanel.isNewChartSelected()) {
                    if (hitButton(w.x, w.y, CREATE_BTN_X, CREATE_BTN_Y, CREATE_BTN_W, CREATE_BTN_H)) {
                        handleConfirm();
                        return true;
                    }
                } else {
                    if (hitButton(w.x, w.y, BTN_EDIT_X, BTN_Y, BTN_WIDTH, BTN_HEIGHT)) {
                        handleConfirm();
                        return true;
                    }
                    if (hitButton(w.x, w.y, BTN_DELETE_X, BTN_Y, BTN_WIDTH, BTN_HEIGHT)) {
                        handleDelete();
                        return true;
                    }
                }

                chartPanel.touchDown(w.y);
                return true;
            }

            @Override
            public boolean touchDragged(int screenX, int screenY, int pointer) {
                Vector3 w = viewport.unproject(new Vector3(screenX, screenY, 0));
                chartPanel.touchDragged(w.y);
                return true;
            }

            @Override
            public boolean touchUp(int screenX, int screenY, int pointer, int button) {
                chartPanel.touchUp();
                return true;
            }

            @Override
            public boolean scrolled(float amountX, float amountY) {
                if (curtainTransition.isActive()) return false;
                // amountY > 0 = scroll down = move selection down
                chartPanel.scroll(amountY);
                return true;
            }
        });
    }

    // ── Font ──────────────────────────────────────────────────────────────────

    /**
     * Tries to load Arial via FreeType from common system font paths.
     * Falls back to the default BitmapFont (scaled) if not found.
     */
    private BitmapFont buildFont() {
        FreeTypeFontGenerator generator = new FreeTypeFontGenerator(
            Gdx.files.internal("NotoSansSC-Medium.ttf"));
        FreeTypeFontGenerator.FreeTypeFontParameter param =
            new FreeTypeFontGenerator.FreeTypeFontParameter();
        param.size      = FONT_SIZE;
        param.minFilter = Texture.TextureFilter.Linear;
        param.magFilter = Texture.TextureFilter.Linear;
        BitmapFont f = generator.generateFont(param);
        generator.dispose();
        return f;
    }

    // ── Action handlers ───────────────────────────────────────────────────────

    private void handleConfirm() {
        if (chartPanel.isNewChartSelected()) {
            openWAVFileChooser();
        } else {
            pendingChart = charts.get(chartPanel.getSelectedIndex());
            startTransition();
        }
    }

    /**
     * Confirms deletion in a Swing dialog, then on the GL thread releases music/jacket
     * handles held by this screen and deletes the chart folder from disk.
     */
    private void handleDelete() {
        int idx = chartPanel.getSelectedIndex();
        if (idx < 0 || idx >= charts.size()) return;

        Chart target = charts.get(idx);

        SwingUtilities.invokeLater(() -> {
            int choice = JOptionPane.showConfirmDialog(
                null,
                "Delete chart \"" + target.getTitle() + "\"?\n"
                    + "This will permanently remove the folder and all its contents.",
                "Confirm Delete",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE
            );

            if (choice != JOptionPane.YES_OPTION) return;

            Gdx.app.postRunnable(() -> deleteChartAtIndex(idx, target));
        });
    }

    /**
     * Releases LibGDX audio/texture locks, then removes the folder.
     * Windows will refuse to delete .wav/ jacket files while Music or Texture still holds them.
     */
    private void deleteChartAtIndex(int idx, Chart target) {
        stopPreview();
        releaseMusicForChart(target);
        resetJacketIfShowing(target);

        FileHandle dir = target.getChartFolder();
        if (dir != null && dir.exists() && !dir.deleteDirectory()) {
            Gdx.app.error("SelectionScreen", "Failed to delete folder: " + dir.path());
        }

        charts.remove(idx);
        chartPanel.afterChartRemovedAt(idx);
        lastPreviewedIndex = Integer.MIN_VALUE;

        if (charts.isEmpty()) {
            resetJacketToDefault();
        }
    }

    /** Stops playback and disposes the preloaded {@link Music} instance for one chart. */
    private void releaseMusicForChart(Chart chart) {
        Music music = preloadedMusic.remove(chart);
        if (music == null) return;
        if (previewMusic == music) previewMusic = null;
        music.stop();
        music.dispose();
    }

    /** If the large jacket preview is showing this chart's image, revert to default.png. */
    private void resetJacketIfShowing(Chart chart) {
        if (chart != null && chart == displayedJacketChart) {
            resetJacketToDefault();
        }
    }

    private void resetJacketToDefault() {
        texJacket.dispose();
        texJacket = new Texture("default.png");
        texJacket.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear);
        displayedJacketChart = null;
    }

    private void openWAVFileChooser() {
        SwingUtilities.invokeLater(() -> {
            JFileChooser chooser = new JFileChooser();
            chooser.setDialogTitle("Select an WAV file");
            chooser.setFileFilter(new FileNameExtensionFilter("WAV audio files", "wav"));
            chooser.setAcceptAllFileFilterUsed(false);

            int result = chooser.showOpenDialog(null);
            if (result != JFileChooser.APPROVE_OPTION) return;

            File selected = chooser.getSelectedFile();
            Gdx.app.postRunnable(() -> createChartFromWAV(selected));
        });
    }

    private void createChartFromWAV(File wavFile) {
        String rawName   = wavFile.getName();
        String chartName = rawName.endsWith(".wav")
            ? rawName.substring(0, rawName.length() - 4) : rawName;

        FileHandle chartsDir = Gdx.files.local("Charts");
        FileHandle chartDir  = chartsDir.child(chartName);
        chartDir.mkdirs();

        FileHandle destWAV = chartDir.child(rawName);
        try {
            Files.copy(wavFile.toPath(),
                new File(destWAV.file().getAbsolutePath()).toPath(),
                StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            Gdx.app.error("SelectionScreen", "Failed to copy wav: " + e.getMessage(), e);
            return;
        }

        FileHandle chartJson = chartDir.child("chart.json");
        chartJson.writeString(
            "{\n"
                + "  \"title\": \"" + chartName + "\",\n"
                + "  \"level\": \"Normal\",\n"
                + "  \"chartConstant\": \"0.0\",\n"
                + "  \"bpm\": 120.0,\n"
                + "  \"artist\": \"Unknown\",\n"
                + "  \"illustrator\": \"Unknown\",\n"
                + "  \"charter\": \"Unknown\",\n"
                + "  \"judgementLine\": {\n"
                + "    \"upperInitial\": 960,\n"
                + "    \"lowerInitial\": 120,\n"
                + "    \"initialLeftBound\": -240,\n"
                + "    \"initialRightBound\": 2160,\n"
                + "    \"upperEvents\": [],\n"
                + "    \"lowerEvents\": [],\n"
                + "    \"leftEvents\": [],\n"
                + "    \"rightEvents\": []\n"
                + "  },\n"
                + "  \"notes\": []\n"
                + "}\n", false);

        Chart newChart;
        try {
            newChart = ChartMapper.readFolder(chartDir);
        } catch (IOException e) {
            Gdx.app.error("SelectionScreen", "Failed to read new chart: " + e.getMessage(), e);
            return;
        }

        charts.add(newChart);
        preloadChartMusic();
        pendingChart = newChart;
        startTransition();
    }

    // ── Music preview ─────────────────────────────────────────────────────────
  /** Preloaded music instances keyed by chart (loaded in background at startup). */
    private final Map<Chart, Music> preloadedMusic = new HashMap<>();
    private Music previewMusic = null;
    private static final float PREVIEW_DURATION = 20f;

    private void preloadChartMusic() {
        for (Chart chart : charts) {
            if (!chart.hasMusic() || preloadedMusic.containsKey(chart)) continue;
            try {
                Music m = Gdx.audio.newMusic(chart.getMusicFile());
                m.setVolume(0.85f);
                preloadedMusic.put(chart, m);
            } catch (Exception e) {
                Gdx.app.error("SelectionScreen", "Preload failed: " + chart.getTitle(), e);
            }
        }
    }

    private void startPreview(Chart chart) {
        stopPreview();
        if (chart == null || !chart.hasMusic()) return;
        Music cached = preloadedMusic.get(chart);
        if (cached == null) {
            try {
                cached = Gdx.audio.newMusic(chart.getMusicFile());
                cached.setVolume(0.85f);
                preloadedMusic.put(chart, cached);
            } catch (Exception e) {
                Gdx.app.error("SelectionScreen", "Preview failed: " + e.getMessage());
                return;
            }
        }
        previewMusic = cached;
        previewMusic.setPosition(0f);
        previewMusic.play();
    }

    private void stopPreview() {
        if (previewMusic != null) {
            previewMusic.pause();
            previewMusic.setPosition(0f);
            previewMusic = null;
        }
    }

    private void disposePreloadedMusic() {
        stopPreview();
        for (Music m : preloadedMusic.values()) m.dispose();
        preloadedMusic.clear();
    }

    private float previewTimer = 0f;
    private int   lastPreviewedIndex = Integer.MIN_VALUE;

    private void updatePreview(float delta) {
        int idx = chartPanel.getSelectedIndex();
        if (idx != lastPreviewedIndex) {
            lastPreviewedIndex = idx;
            previewTimer = 0f;
            if (idx >= 0 && idx < charts.size()) {
                startPreview(charts.get(idx));
                updateJacket(charts.get(idx));
            } else {
                stopPreview();
                resetJacketToDefault();
            }
        }
        if (previewMusic != null) {
            previewTimer += delta;
            if (previewTimer >= PREVIEW_DURATION) stopPreview();
        }
    }

    private void updateJacket(Chart chart) {
        if (chart == null) return;
        if (!chart.hasJacket()) {
            resetJacketToDefault();
            return;
        }
        if (chart == displayedJacketChart) return;
        try {
            texJacket.dispose();
            texJacket = new Texture(chart.getJacketFile());
            texJacket.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear);
            displayedJacketChart = chart;
        } catch (Exception e) {
            Gdx.app.error("SelectionScreen", "Jacket load failed: " + e.getMessage());
            resetJacketToDefault();
        }
    }

    private boolean hitButton(float wx, float wy, float bx, float bCenterY, float bw, float bh) {
        return wx >= bx && wx <= bx + bw
            && wy >= bCenterY - bh / 2f && wy <= bCenterY + bh / 2f;
    }

    private void loadTextures() {
        texBackground = new Texture("ui/background.png");
        texJacket     = new Texture("default.png");
        texParticle   = new Texture("ui/blue particle.png");
        texTransUpper = new Texture("ui/upper transition.png");
        texTransLower = new Texture("ui/lower transition.png");

        texTransUpper.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear);
        texTransLower.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear);
        texBackground.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear);
        texJacket.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear);
    }

    private void loadAllCharts() {
        FileHandle chartsDir = Gdx.files.local("Charts");
        if (!chartsDir.exists()) { chartsDir.mkdirs(); return; }
        for (FileHandle folder : chartsDir.list()) {
            if (!folder.isDirectory()) continue;
            try {
                charts.add(ChartMapper.readFolder(folder));
            } catch (IOException e) {
                Gdx.app.error("ChartLoader", "Failed to load: " + folder.path(), e);
            }
        }
    }

    // ── Transition ────────────────────────────────────────────────────────────

    private CurtainTransition buildTransition() {
        CurtainTransition.Config cfg = new CurtainTransition.Config()
            .worldSize(W, H)
            .closeDuration(0.45f)
            .openDuration(0.45f)
            .dotOffsetFromCenter(0f)
            .dotRadius(50f)
            .lineThickness(30f);
        return new CurtainTransition(cfg, texTransUpper, texTransLower);
    }

    private void startTransition() {
        if (curtainTransition.isActive()) return;
        stopPreview();
        curtainTransition.startClose(() -> {
            if (pendingChart == null) return;
            final Chart toOpen = pendingChart;
            pendingChart = null;
            main.setScreen(new ChartEditorScreen(main, toOpen, true));
        });
    }

    // ── Render ────────────────────────────────────────────────────────────────

    @Override
    public void render(float delta) {
        curtainTransition.update(delta);
        particles.update(delta);
        updatePreview(delta);

        viewport.apply();
        Gdx.gl.glClearColor(0, 0, 0, 1);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);

        Matrix4 proj = viewport.getCamera().combined;
        batch.setProjectionMatrix(proj);
        sr.setProjectionMatrix(proj);

        if (!curtainTransition.isFullyClosed()) {
            // Background + particles
            batch.begin();
            batch.draw(texBackground, 0, 0, W, H);
            particles.render(batch);
            batch.end();

            // Jacket
            batch.begin();
            batch.draw(texJacket, JACKET_X, JACKET_Y, JACKET_SIZE, JACKET_SIZE);
            batch.end();

            // Bottom buttons
            rectRenderer.setProjectionMatrix(proj);

            if (chartPanel.isNewChartSelected()) {
                rectRenderer.draw(CREATE_BTN_X, CREATE_BTN_Y - CREATE_BTN_H / 2f,
                    CREATE_BTN_W, CREATE_BTN_H, CREATE_BTN_CORNER, CREATE_COLOR);
                batch.begin();
                font.setColor(Color.BLACK);
                font.draw(batch, "Create with .wav",
                    CREATE_BTN_X + CREATE_BTN_W / 2f,
                    CREATE_BTN_Y + font.getCapHeight() / 2f,
                    0, Align.center, false);
                batch.end();
            } else {
                rectRenderer.draw(BTN_EDIT_X,   BTN_Y - BTN_HEIGHT / 2f,
                    BTN_WIDTH, BTN_HEIGHT, BTN_CORNER_RADIUS, BTN_COLOR);
                rectRenderer.draw(BTN_DELETE_X, BTN_Y - BTN_HEIGHT / 2f,
                    BTN_WIDTH, BTN_HEIGHT, BTN_CORNER_RADIUS, BTN_COLOR);
                batch.begin();
                font.setColor(Color.BLACK);
                font.draw(batch, "Edit",
                    BTN_EDIT_X   + BTN_WIDTH / 2f,
                    BTN_Y + font.getCapHeight() / 2f,
                    0, Align.center, false);
                font.draw(batch, "Delete",
                    BTN_DELETE_X + BTN_WIDTH / 2f,
                    BTN_Y + font.getCapHeight() / 2f,
                    0, Align.center, false);
                batch.end();
            }

            // Chart list
            batch.begin();
            chartPanel.render(batch, delta);
            batch.end();
        }

        // Transition overlay
        if (curtainTransition.isActive()) {
            curtainTransition.render(batch, sr);
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    public void resize(int width, int height) {
        viewport.update(width, height, true);
    }

    @Override
    public void dispose() {
        texBackground.dispose();
        texJacket.dispose();
        texParticle.dispose();
        texTransUpper.dispose();
        texTransLower.dispose();
        font.dispose();
        sr.dispose();
        rectRenderer.dispose();
        chartPanel.dispose();
        particles.dispose();
        disposePreloadedMusic();
    }
}
