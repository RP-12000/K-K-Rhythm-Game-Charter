package org.kelvinizer.chartEditor;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.graphics.g2d.freetype.FreeTypeFontGenerator;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.utils.Align;
import org.kelvinizer.audio.WavMusicPlayer;
import org.kelvinizer.elements.*;
import space.earlygrey.shapedrawer.ShapeDrawer;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;

import static org.kelvinizer.Params.*;

/**
 * Reusable chart-play subscreen (1920×1080 reference space, horizontally clipped to
 * {@code visualizerRightBound} for side panels).
 *
 * <p>Designed to be embedded in an editor or dropped into the main game with the same API:
 * construct, call {@link #update(float)} / {@link #render(Batch)} each frame,
 * forward input via {@link #touchDown(float, float)} and {@link #keyDown(int)}.
 */
public class ChartVisualizer {

    /** Transport buttons in reference pixels. */
    public static final float BTN_SIZE_REF  = 60f;
    public static final float BTN_GAP_REF   = 40f;
    public static final float BTN_EDGE_REF  = 40f;
    public static final float BTN_TOP_REF   = REF_H - BTN_EDGE_REF - BTN_SIZE_REF;

    private static final Color COL_PROGRESS = new Color(0f, 0.85f, 0.85f, 1f);
    private static final Color COL_KNOB     = Color.WHITE;
    private static final float PROGRESS_EXTRA_T = 2f;
    private static final float KNOB_R = 6f;

    private final HashMap<Integer, Integer> keyState = new HashMap<>();

    /** When true, HUD shows "AUTO" instead of "COMBO" and the top progress bar is seekable. */
    public boolean autoplay;

    private final Chart chart;
    private final PlaybackController timeline;
    private final float scalingFactor;

    private final ShapeRenderer sr;
    private final WavMusicPlayer wavPlayer;
    private final BitmapFont hudFont;
    private final BitmapFont labelFont;

    private final Texture texBack;
    private final Texture texPlay;
    private final Texture texPause;
    private final Texture texRestart;
    private final Texture texWhitePixel;
    private final TextureRegion whitePixel;

    private RenderableChart renderableChart;
    private boolean playing;
    private boolean progressDragging;
    private float audioDurationMs;

    private float btnBackX, btnRestartX, btnPlayX;
    private float btnSize;
    private float btnY;

    private float leftJudgementLineBound, judgementLineBoundSize;
    private float upperJudgement, lowerJudgement;

    /** Optional host callback (e.g. editor back navigation). */
    private Runnable onBackRequested;

    public Note selectedNote;
    private Note hoveredNote;

    public ChartVisualizer(Chart chart, float scalingFactor, boolean isAutoplay) {

        this.chart = chart;
        this.timeline = new PlaybackController();
        this.scalingFactor = scalingFactor;
        this.sr = new ShapeRenderer();
        this.wavPlayer = new WavMusicPlayer();
        this.autoplay = isAutoplay;

        btnSize = BTN_SIZE_REF * scalingFactor;
        btnY = BTN_TOP_REF * scalingFactor;
        btnBackX    = BTN_EDGE_REF * scalingFactor;
        btnRestartX = btnBackX + btnSize + BTN_GAP_REF * scalingFactor;
        btnPlayX    = btnRestartX + btnSize + BTN_GAP_REF * scalingFactor;

        texBack    = new Texture(Gdx.files.internal("Buttons/Back.jpg"));
        texPlay    = new Texture(Gdx.files.internal("Buttons/Play.jpg"));
        texPause   = new Texture(Gdx.files.internal("Buttons/Pause.jpg"));
        texRestart = new Texture(Gdx.files.internal("Buttons/Restart.jpg"));
        texWhitePixel = new Texture(Gdx.files.internal("white_pixel.png"));
        whitePixel = new TextureRegion(texWhitePixel);

        BitmapFont hud;
        BitmapFont label;
        FreeTypeFontGenerator gen = null;
        try {
            gen = new FreeTypeFontGenerator(Gdx.files.internal("times.ttf"));
            FreeTypeFontGenerator.FreeTypeFontParameter p = new FreeTypeFontGenerator.FreeTypeFontParameter();
            p.size = 32;
            label = gen.generateFont(p);
            hud = getAutoSizedFont("Title", 480f * scalingFactor, 108f * scalingFactor, gen);
        } catch (Exception e) {
            hud = new BitmapFont();
            label = new BitmapFont();
            Gdx.app.error("ChartVisualizer", "Font load failed, using default", e);
        } finally {
            if (gen != null) gen.dispose();
        }
        hudFont = hud;
        labelFont = label;

        if (chart != null && chart.hasMusic()) {
            try {
                wavPlayer.load(chart.getMusicFile());
                wavPlayer.setVolume(0.85f);
                audioDurationMs = wavPlayer.getDurationMs();
                timeline.setDurationMs(audioDurationMs);
            } catch (Exception e) {
                Gdx.app.error("ChartVisualizer", "WAV load failed: " + e.getMessage(), e);
            }
        }

        keyState.put(Input.Keys.CAPS_LOCK, 0);
        keyState.put(Input.Keys.A, 1);
        keyState.put(Input.Keys.S, 2);
        keyState.put(Input.Keys.D, 3);
        keyState.put(Input.Keys.F, 4);
        keyState.put(Input.Keys.J, 5);
        keyState.put(Input.Keys.K, 6);
        keyState.put(Input.Keys.L, 7);
        keyState.put(Input.Keys.SEMICOLON, 8);
        keyState.put(Input.Keys.APOSTROPHE, 9);

        keyState.put(Input.Keys.TAB, 10);
        keyState.put(Input.Keys.Q, 11);
        keyState.put(Input.Keys.W, 12);
        keyState.put(Input.Keys.E, 13);
        keyState.put(Input.Keys.R, 14);
        keyState.put(Input.Keys.U, 15);
        keyState.put(Input.Keys.I, 16);
        keyState.put(Input.Keys.O, 17);
        keyState.put(Input.Keys.P, 18);
        keyState.put(Input.Keys.LEFT_BRACKET, 19);

    }

    public void setOnBackRequested(Runnable onBackRequested) {
        this.onBackRequested = onBackRequested;
    }

    public PlaybackController getTimeline() { return timeline; }

    public float getScalingFactor() { return scalingFactor; }

    public Chart getChart() { return chart; }

    public boolean isPlaying() { return playing; }

    // ── Update / render ─────────────────────────────────────────────────────

    public void update(float delta) {
        if (chart == null) {
            renderableChart = null;
            return;
        }

        configureTimelineRange();

        if (playing) {
            timeline.update(delta);
        } else {
            timeline.pause();
        }
        syncAudioToTimeline();

        chart.update(timeline.getTimeMs() / 60000.0f * chart.getBPM(), autoplay);
        renderableChart = RenderableChart.from(chart);
    }

    private void configureTimelineRange() {
        if (chart.getBPM() <= 0f) {
            timeline.setStartTimeMs(0f);
            timeline.setDurationMs(audioDurationMs);
            return;
        }

        float minBeat = 0f;
        float maxBeat = audioDurationMs > 0f ? msToBeat(audioDurationMs) : 0f;
        for (Note note : chart.getNotes()) {
            minBeat = Math.min(minBeat, note.getAppearTime());
            minBeat = Math.min(minBeat, note.getJudgementTime());
            maxBeat = Math.max(maxBeat, note.getEndTime());
            for (Events event : note.getMovementEvents()) {
                minBeat = Math.min(minBeat, event.getStartTime());
                maxBeat = Math.max(maxBeat, event.getEndTime());
            }
            for (Events event : note.getRotationEvents()) {
                minBeat = Math.min(minBeat, event.getStartTime());
                maxBeat = Math.max(maxBeat,event.getEndTime());
            }
            for (Events event : note.getOpacityEvents()) {
                minBeat = Math.min(minBeat, event.getStartTime());
                maxBeat = Math.max(maxBeat, event.getEndTime());
            }
        }
        timeline.setStartTimeMs(beatToMs(minBeat));
        timeline.setDurationMs(Math.max(audioDurationMs, beatToMs(maxBeat)));
    }

    private float beatToMs(float beat) {
        return chart.getBPM() > 0f ? beat * 60000f / chart.getBPM() : 0f;
    }

    private float msToBeat(float ms) {
        return ms / 60000f * chart.getBPM();
    }

    private void syncAudioToTimeline() {
        if (!wavPlayer.isLoaded()) return;

        float t = timeline.getTimeMs();
        boolean inAudioRange = t >= 0f && (audioDurationMs <= 0f || t < audioDurationMs);
        if (!playing || !inAudioRange) {
            if (wavPlayer.isPlaying()) wavPlayer.pause();
            if (t < 0f) wavPlayer.setPositionMs(0f);
            else if (audioDurationMs > 0f && t >= audioDurationMs) wavPlayer.setPositionMs(audioDurationMs);
            else wavPlayer.setPositionMs(t);
            return;
        }

        if (Math.abs(wavPlayer.getPositionMs() - t) > 80f) {
            wavPlayer.setPositionMs(t);
        }
        if (!wavPlayer.isPlaying()) wavPlayer.play();
    }

    public void render(Batch batch) {
        if (renderableChart == null) return;

        batch.begin();
        ShapeDrawer drawer = new ShapeDrawer(batch, whitePixel);

        List<RenderableNote> visibleNotes = renderableChart.getNotes().isEmpty()
            ? Collections.emptyList()
            : renderableChart.getNotes();
        RenderableJudgementLine lines = renderableChart.getJudgementLine();
        cacheJudgementBounds(lines);

        for (int i = 1; i < visibleNotes.size(); i++) {
            if (visibleNotes.get(i - 1).judgementTime == visibleNotes.get(i).judgementTime) {
                visibleNotes.get(i - 1).sync = true;
                visibleNotes.get(i).sync = true;
            }
        }

        drawer.setColor(Color.BLACK);
        drawer.rectangle(0, 0, REF_W * scalingFactor, REF_H * scalingFactor);
        drawTransportButtons(batch);

        //Gdx.gl.glLineWidth(5f);
        //shapeRenderer.begin(ShapeRenderer.ShapeType.Line);
        //shapeRenderer.setColor(Color.WHITE);
        drawer.setPixelSize(5f);
        drawer.rectangle(0, 0, REF_W * scalingFactor, REF_H * scalingFactor);
        drawer.setPixelSize(1f);
        //Gdx.gl.glLineWidth(1f);

        drawNotes(drawer, visibleNotes, lines);
        drawLaneLines(drawer, lines);
        drawTopProgressBar(drawer, lines);

        drawHud(batch, lines);
        batch.end();
    }

    private void drawNotes(ShapeDrawer shapeDrawer, List<RenderableNote> notes,
                           RenderableJudgementLine lines) {
        for (RenderableNote note : notes) {
            positionRenderableNote(note, lines);
            float[] vertices = note.noteObject.getTransformedVertices();
            if (note.lane >= 10) {
                shapeDrawer.setColor(1, 1, 1, (float) note.currentOpacity);
            } else {
                shapeDrawer.setColor(0, 1, 1, (float) note.currentOpacity);
            }
            shapeDrawer.filledPolygon(vertices);
        }

        //Gdx.gl.glLineWidth(SYNC_BOUND_WIDTH * scalingFactor);
        shapeDrawer.setPixelSize(SYNC_BOUND_WIDTH * scalingFactor);
        for (RenderableNote note : notes) {
            if(note.sync){
                positionRenderableNote(note, lines);
                shapeDrawer.setColor(1, 1, 0, (float) note.currentOpacity);
                shapeDrawer.polygon(note.noteObject.getTransformedVertices());
            }
        }
        //Gdx.gl.glLineWidth(1f);
        shapeDrawer.setPixelSize(1f);
    }

    private void drawLaneLines(ShapeDrawer shapeRenderer, RenderableJudgementLine lines) {
        //shapeRenderer.begin(ShapeRenderer.ShapeType.Line);
        shapeRenderer.setColor(Color.GREEN);
        shapeRenderer.line(0, (float) lines.getLower() * scalingFactor, REF_W * scalingFactor, (float) lines.getLower() * scalingFactor);
        shapeRenderer.line(0, (float) lines.getUpper() * scalingFactor, REF_W * scalingFactor, (float) lines.getUpper() * scalingFactor);
        shapeRenderer.setColor(Color.LIGHT_GRAY);
        for (double v : lines.getVertical()) {
            if(v >= 0 && v <= REF_W){
                shapeRenderer.line((float) (v * scalingFactor), 0, (float) (v * scalingFactor), REF_H * scalingFactor);
            }
        }
        //shapeRenderer.end();
        cacheJudgementBounds(lines);
    }

    private void cacheJudgementBounds(RenderableJudgementLine lines) {
        leftJudgementLineBound = lines.getVertical().get(0);
        judgementLineBoundSize = lines.getHorizontalDistance();
        upperJudgement = lines.getUpper();
        lowerJudgement = lines.getLower();
    }

    /** Cyan bar along the top judgement line; draggable knob when {@code autoplay}. */
    private void drawTopProgressBar(ShapeDrawer shapeRenderer, RenderableJudgementLine lines) {
        float upperY = lines.getUpper() * scalingFactor;
        float barH = 2f + PROGRESS_EXTRA_T;
        float progress = timeline.getProgress();
        float fillW = 2 * (REF_W * scalingFactor * progress);

        //shapeRenderer.begin(ShapeRenderer.ShapeType.Filled);
        shapeRenderer.setColor(COL_PROGRESS);
        shapeRenderer.filledRectangle(0f, upperY - barH / 2f, fillW, barH);

        if (autoplay) {
            shapeRenderer.setColor(COL_KNOB);
            shapeRenderer.filledCircle(fillW, upperY, KNOB_R);
        }
        //shapeRenderer.end();
    }

    private void drawHud(Batch batch, RenderableJudgementLine lines) {
        hudFont.setColor(Color.WHITE);
        hudFont.draw(batch, renderableChart.getTitle(), 40 * scalingFactor, 72 * scalingFactor);

        labelFont.setColor(Color.WHITE);
        labelFont.draw(
            batch,
            renderableChart.getLevel() + " Lv. " + renderableChart.getChartConstant(),
            1880 * scalingFactor,
            72 * scalingFactor,
            0,
            Align.right, false);

        String comboLabel = autoplay ? " AUTO" : " COMBO";
        labelFont.draw(
            batch,
            renderableChart.getCombo() + comboLabel,
            960 * scalingFactor,
            1040 * scalingFactor,
            0,
            Align.center,false);
        labelFont.draw(
            batch,
            String.format("%08d", renderableChart.getScore()),
            1880 * scalingFactor,
            1040 * scalingFactor,
            0,
            Align.right, false);
    }

    private void drawTransportButtons(Batch batch) {
        batch.draw(texBack, btnBackX, btnY, btnSize, btnSize);
        batch.draw(texRestart, btnRestartX, btnY, btnSize, btnSize);
        batch.draw(playing ? texPause : texPlay, btnPlayX, btnY, btnSize, btnSize);
    }

    public void selectNote(Note note) {
        selectedNote = note;
    }

    public void setHoveredNote(Note note) {
        hoveredNote = note;
    }

    public void deleteSelectedNote() {
        if (selectedNote == null) return;
        chart.getNotes().remove(selectedNote);
        if (hoveredNote == selectedNote) hoveredNote = null;
        selectedNote = null;
    }

    public Note findNoteAt(float worldX, float worldY) {
        if (renderableChart == null) return null;
        RenderableJudgementLine lines = renderableChart.getJudgementLine();
        List<RenderableNote> notes = renderableChart.getNotes();
        for (int i = notes.size() - 1; i >= 0; i--) {
            RenderableNote note = notes.get(i);
            positionRenderableNote(note, lines);
            if (note.noteObject.contains(worldX, worldY)) {
                return findSourceNote(note);
            }
        }
        return null;
    }

    private Note findSourceNote(RenderableNote renderable) {
        for (Note note : chart.getNotes()) {
            if (note.getLane() == renderable.lane
                && Math.abs(note.getJudgementTime() - renderable.judgementTime) < 0.0001f) {
                return note;
            }
        }
        return null;
    }

    private void positionRenderableNote(RenderableNote note, RenderableJudgementLine lines) {
        boolean hovered = findSourceNote(note) == hoveredNote;
        // Scale: NOTE_WIDTH=200 local units maps to 6/5 of one lane width.
        // scalingFactor converts reference→screen. Hovered notes are 20% bigger.
        float laneWidthRef = lines.getHorizontalDistance() / 10f;
        float noteScale = (laneWidthRef / 200f) * (5f / 6f) * scalingFactor * (hovered ? 1.2f : 1.0f);
        note.noteObject.setScale(noteScale, noteScale);

        // Lane centre in screen space.
        // Vertical lines: vertical[i] = left + (right-left)/10 * i
        // Lane i occupies vertical[i] to vertical[i+1], centre = left + laneWidthRef*(lane+0.5)
        int localLane = note.lane % 10;
        float laneCentreRef = leftJudgementLineBound + laneWidthRef * (localLane + 0.5f);
        float cx = laneCentreRef * scalingFactor;

        // Y: currentPos==0 means the note's centre is exactly on the judgement line.
        // Ground notes (lane<10) rise upward toward the lower line; sky notes toward the upper line.
        float cy;
        if (note.lane < 10) {
            cy = (lines.getLower() + note.currentPos) * scalingFactor;
        } else {
            cy = (lines.getUpper() - note.currentPos) * scalingFactor;
        }

        // setPosition places the local (0,0) point — our vertices are centred on (0,0),
        // so this directly centres the note at (cx, cy).
        note.noteObject.setPosition(cx, cy);
    }

    // ── Input (placeholders + transport) ────────────────────────────────────

    /**
     * Placeholder for keyboard routing from the host screen.
     * @return true if the key was consumed
     */
    public boolean keyDown(int keycode) {
        // Reserved for chart-editor shortcuts (note placement, etc.)
        if (keycode == Input.Keys.SPACE) {
            togglePlayPause();
            return true;
        }
        if(!autoplay){
            if (keyState.containsKey(keycode)) {
                int lane = keyState.get(keycode);
                for(Note n: chart.getNotes()){
                    if(n.getLane() == lane && n.hitJudge(timeline.getTimeMs())){
                        return true;
                    }
                }
            }
        }
        return false;
    }

    public boolean touchDownJudgement(float worldX, float worldY) {
        boolean successHit = false;
        if(hit(
            worldX, worldY,
            leftJudgementLineBound * scalingFactor, (lowerJudgement - JUDGEMENT_BOUNDS / 2) * scalingFactor,
            judgementLineBoundSize * scalingFactor, JUDGEMENT_BOUNDS * scalingFactor)
        ){
            int lane = (int) ((worldX - leftJudgementLineBound * scalingFactor) / (judgementLineBoundSize * scalingFactor) * 10);
            for(Note n: chart.getNotes()){
                if(n.getLane() == lane && n.hitJudge(timeline.getTimeMs())){
                    successHit = true;
                }
            }
        }
        else if(hit(
            worldX, worldY,
            leftJudgementLineBound * scalingFactor, (upperJudgement - JUDGEMENT_BOUNDS / 2) * scalingFactor,
            judgementLineBoundSize * scalingFactor, JUDGEMENT_BOUNDS * scalingFactor)
        ){
            int lane = 10 + (int) ((worldX - leftJudgementLineBound * scalingFactor) / (judgementLineBoundSize * scalingFactor) * 10);
            for(Note n: chart.getNotes()){
                if(n.getLane() == lane && n.hitJudge(timeline.getTimeMs())){
                    successHit = true;
                }
            }
        }
        return successHit;
    }

    /**
     * @return true if this subscreen consumed the touch
     */
    public boolean touchDown(float worldX, float worldY) {
        if (hit(worldX, worldY, btnBackX, btnY, btnSize, btnSize)) {
            if (onBackRequested != null) onBackRequested.run();
            return true;
        }
        if (hit(worldX, worldY, btnRestartX, btnY, btnSize, btnSize)) {
            restart();
            return true;
        }
        if (hit(worldX, worldY, btnPlayX, btnY, btnSize, btnSize)) {
            togglePlayPause();
            return true;
        }

        if (autoplay && hitTopProgress(worldX, worldY)) {
            progressDragging = true;
            seekFromProgressX(worldX);
            return true;
        }
        if(!autoplay){
            return touchDownJudgement(worldX, worldY);
        }
        return false;
    }

    public boolean touchDragged(float worldX, float worldY) {
        if (progressDragging && autoplay) {
            seekFromProgressX(worldX);
            return true;
        }
        return false;
    }

    public boolean touchUp() {
        boolean was = progressDragging;
        progressDragging = false;
        return was;
    }

    // ── Playback controls ───────────────────────────────────────────────────

    public void togglePlayPause() {
        if (playing) pause();
        else play();
    }

    public void play() {
        playing = true;
        timeline.play();
        syncAudioToTimeline();
    }

    public void pause() {
        playing = false;
        timeline.pause();
        if (wavPlayer.isLoaded()) wavPlayer.pause();
    }

    public void restart() {
        playing = false;
        timeline.restart();
        if (wavPlayer.isLoaded()) {
            wavPlayer.stop();
            wavPlayer.setPositionMs(0f);
        }
    }

    private void seekFromProgressX(float worldX) {
        float frac = Math.min(1f, Math.max(worldX / 2 / REF_W / scalingFactor, 0f));
        float t = timeline.getStartTimeMs()
            + frac * (timeline.getDurationMs() - timeline.getStartTimeMs());
        timeline.seek(t);
        syncAudioToTimeline();
    }

    private boolean hitTopProgress(float wx, float wy) {
        if (renderableChart == null) return false;
        float upperY = renderableChart.getJudgementLine().getUpper();
        float barH = 2f + PROGRESS_EXTRA_T + 12f;
        return wx >= 0 && wx <= REF_W * scalingFactor
            && wy >= (upperY - barH) * scalingFactor && wy <= (upperY + barH) * scalingFactor;
    }

    private BitmapFont getAutoSizedFont(String text, float maxWidth, float maxHeight,
                                        FreeTypeFontGenerator generator) {
        FreeTypeFontGenerator.FreeTypeFontParameter p = new FreeTypeFontGenerator.FreeTypeFontParameter();
        int size = 30;
        GlyphLayout layout = new GlyphLayout();
        while (size > 5) {
            p.size = size;
            BitmapFont font = generator.generateFont(p);
            layout.setText(font, text);
            if (layout.width <= maxWidth && layout.height <= maxHeight) return font;
            font.dispose();
            size--;
        }
        p.size = 5;
        return generator.generateFont(p);
    }

    public void dispose() {
        wavPlayer.dispose();
        sr.dispose();
        texBack.dispose();
        texPlay.dispose();
        texPause.dispose();
        texRestart.dispose();
        texWhitePixel.dispose();
        if (hudFont != null) hudFont.dispose();
        if (labelFont != null) labelFont.dispose();
    }
}
