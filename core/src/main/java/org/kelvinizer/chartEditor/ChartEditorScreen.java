package org.kelvinizer.chartEditor;

import com.badlogic.gdx.*;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.viewport.FitViewport;
import org.kelvinizer.editorComponents.EventEditor;
import org.kelvinizer.Main;
import org.kelvinizer.editorComponents.GeneralSettings;
import org.kelvinizer.editorComponents.NoteEditor;
import org.kelvinizer.elements.Chart;
import org.kelvinizer.elements.Events;
import org.kelvinizer.elements.Note;
import org.kelvinizer.selectionscreen.SelectionScreen;
import org.kelvinizer.transition.CurtainTransition;

import static org.kelvinizer.Params.*;

/**
 * Minimal chart editor shell: {@link ChartVisualizer} on the left, placeholder side panel
 * on the right. Full note-editing UI can be reintroduced here later.
 */
public class ChartEditorScreen extends ScreenAdapter {
    /** Lane / visualizer area ends here; right side reserved for future panels. */
    private static final float VISUALIZER_SCALING_FACTOR = 0.8f;

    private final Main main;
    private final FitViewport viewport;
    private final SpriteBatch batch;
    private final ShapeRenderer sr;
    private final ChartVisualizer visualizer;
    private final CurtainTransition curtainTransition;

    private final Texture texTransUpper;
    private final Texture texTransLower;

    private final GeneralSettings chartInfoTextbox;
    private final EditorInput editorInput = new EditorInput();
    private final NoteEditor noteEditor;
    private final EventEditor eventEditor;
    private final BitmapFont editorFont = new BitmapFont();
    private final GlyphLayout glyph = new GlyphLayout();

    private Note lastSyncedNote;
    private Note clipboardNote;
    private String selectedNoteType = "tap";

    public ChartEditorScreen(Main main, Chart chart, boolean withOpeningTransition) {
        this.main = main;
        viewport = new FitViewport(REF_W, REF_H);
        batch = new SpriteBatch();
        sr = new ShapeRenderer();

        visualizer = new ChartVisualizer(chart, VISUALIZER_SCALING_FACTOR, true);
        visualizer.setOnBackRequested(this::startBackTransition);

        texTransUpper = new Texture(Gdx.files.internal("ui/upper transition.png"));
        texTransLower = new Texture(Gdx.files.internal("ui/lower transition.png"));
        texTransUpper.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear);
        texTransLower.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear);

        curtainTransition = buildTransition();
        if (withOpeningTransition) curtainTransition.beginOpening();
        chartInfoTextbox = new GeneralSettings(chart, this.viewport);
        noteEditor = new NoteEditor(this.viewport);
        eventEditor = new EventEditor(this.viewport);
        eventEditor.setSelectedNote(null);
    }

    public ChartEditorScreen(Main main, Chart chart) {
        this(main, chart, true);
    }

    public ChartEditorScreen(Chart chart) {
        this(null, chart, false);
    }

    private CurtainTransition buildTransition() {
        CurtainTransition.Config cfg = new CurtainTransition.Config()
            .worldSize(REF_W, REF_H)
            .closeDuration(0.45f)
            .openDuration(0.45f)
            .dotOffsetFromCenter(0f)
            .dotRadius(50f)
            .lineThickness(30f);
        return new CurtainTransition(cfg, texTransUpper, texTransLower);
    }

    @Override
    public void show() {
        InputMultiplexer mux = new InputMultiplexer();
        mux.addProcessor(chartInfoTextbox);
        mux.addProcessor(noteEditor);
        mux.addProcessor(eventEditor);
        mux.addProcessor(editorInput);
        Gdx.input.setInputProcessor(mux);
    }

    @Override
    public void render(float delta) {
        curtainTransition.update(delta);
        visualizer.getChart().setTitle(chartInfoTextbox.getTitle());
        visualizer.getChart().setBPM(chartInfoTextbox.getBPM());
        visualizer.getChart().setLevel(chartInfoTextbox.getDifficulty());
        visualizer.getChart().setChartConstant(chartInfoTextbox.getConstant());
        visualizer.getChart().setArtist(chartInfoTextbox.getArtist());
        visualizer.getChart().setIllustrator(chartInfoTextbox.getIllustrator());
        visualizer.getChart().setCharter(chartInfoTextbox.getCharter());
        visualizer.update(delta);

        updateHoveredNote();
        syncSelectedNoteEditor();
        eventEditor.setSelectedNote(visualizer.selectedNote);

        Gdx.gl.glClearColor(0.05f, 0.05f, 0.06f, 1f);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);
        viewport.apply();

        batch.setProjectionMatrix(viewport.getCamera().combined);
        sr.setProjectionMatrix(viewport.getCamera().combined);

        if (!curtainTransition.isFullyClosed()) {
            drawBackground();
            visualizer.render(batch);
            drawNoteTypeButtons();
        }

        if (curtainTransition.isActive()) {
            curtainTransition.render(batch, sr);
        }
        chartInfoTextbox.act(delta);
        chartInfoTextbox.draw();
        noteEditor.act(delta);
        noteEditor.draw();
        eventEditor.act(delta);
        eventEditor.draw();
    }

    private void drawBackground() {
        sr.begin(ShapeRenderer.ShapeType.Filled);
        sr.setColor(new Color(0x212128ff));
        sr.rect(0f, 0f, REF_W, REF_H);
        sr.end();
    }

    private void syncSelectedNoteEditor() {
        Note selected = visualizer.selectedNote;
        if (selected == null) {
            lastSyncedNote = null;
            return;
        }
        if (selected != lastSyncedNote) {
            noteEditor.setText(selected);
            selectedNoteType = selected.getType();
            lastSyncedNote = selected;
            return;
        }
        if (!noteEditor.hasCompleteValues()) return;
        try {
            selected.setJudgementTime(noteEditor.getJudgementTime());
            selected.setLane(noteEditor.getLane());
            selected.setDuration(noteEditor.getDuration());
            selected.setHeight(noteEditor.getHeight());
        } catch (NumberFormatException ignored) {
        }
    }

    private void updateHoveredNote() {
        Vector3 mouse = viewport.unproject(new Vector3(Gdx.input.getX(), Gdx.input.getY(), 0));
        if (mouse.x > 1536f) {
            visualizer.setHoveredNote(null);
            return;
        }
        visualizer.setHoveredNote(visualizer.findNoteAt(mouse.x, mouse.y));
    }

    private void drawNoteTypeButtons() {
        drawNoteTypeButton("tap", 960f, 994f);
        drawNoteTypeButton("hold", 1260f, 994f);
        drawCommandButton("Delete", 1410f, 994f, 110f, 45f, new Color(0.55f, 0.12f, 0.14f, 1f));
    }

    private void drawNoteTypeButton(String type, float x, float y) {
        float w = 150f;
        float h = 45f;
        boolean active = type.equals(selectedNoteType);

        sr.begin(ShapeRenderer.ShapeType.Filled);
        sr.setColor(active ? new Color(0.15f, 0.75f, 0.95f, 1f) : new Color(0.16f, 0.18f, 0.20f, 1f));
        sr.rect(x, y, w, h);
        sr.end();

        batch.begin();
        editorFont.setColor(Color.WHITE);
        glyph.setText(editorFont, type);
        editorFont.draw(batch, type, x + (w - glyph.width) / 2f, y + h / 2f + glyph.height / 2f);
        batch.end();
    }

    private void drawCommandButton(String text, float x, float y, float w, float h, Color color) {
        sr.begin(ShapeRenderer.ShapeType.Filled);
        sr.setColor(color);
        sr.rect(x, y, w, h);
        sr.end();

        batch.begin();
        editorFont.setColor(Color.WHITE);
        glyph.setText(editorFont, text);
        editorFont.draw(batch, text, x + (w - glyph.width) / 2f, y + h / 2f + glyph.height / 2f);
        batch.end();
    }

    private void startBackTransition() {
        if (curtainTransition.isActive()) return;
        visualizer.pause();
        curtainTransition.startClose(() -> {
            if (main != null) {
                main.setScreen(new SelectionScreen(main, true));
            }
        });
    }

    @Override
    public void resize(int width, int height) {
        viewport.update(width, height, true);
    }

    @Override
    public void hide() {
        visualizer.pause();
    }

    @Override
    public void dispose() {
        visualizer.dispose();
        batch.dispose();
        sr.dispose();
        texTransUpper.dispose();
        texTransLower.dispose();
        noteEditor.dispose();
        eventEditor.dispose();
        chartInfoTextbox.dispose();
        editorFont.dispose();
    }

    private class EditorInput extends InputAdapter {
        @Override
        public boolean touchDown(int screenX, int screenY, int pointer, int button) {
            if (curtainTransition.isActive()) return false;

            Vector3 w = viewport.unproject(new Vector3(screenX, screenY, 0));

            boolean ctrlHeld = Gdx.input.isKeyPressed(Input.Keys.CONTROL_LEFT)
                || Gdx.input.isKeyPressed(Input.Keys.CONTROL_RIGHT);
            if (ctrlHeld) return createNoteAt(w.x, w.y);
            if (hit(w.x, w.y, 960f, 994f, 150f, 45f)) {
                selectedNoteType = "tap";
                Note sel = visualizer.selectedNote;
                if (sel != null && "hold".equalsIgnoreCase(sel.getType())) {
                    sel.setType("tap");
                    sel.setHeight(0f);
                    sel.setDuration(0f);
                    noteEditor.setText(sel);
                    lastSyncedNote = null; // force re-sync next frame
                }
                return true;
            }
            if (hit(w.x, w.y, 1260f, 994f, 150f, 45f)) {
                selectedNoteType = "hold";
                Note sel = visualizer.selectedNote;
                if (sel != null && "tap".equalsIgnoreCase(sel.getType())) {
                    sel.setType("hold");
                    sel.setHeight(972f);
                    sel.setDuration(2f);
                    noteEditor.setText(sel);
                    lastSyncedNote = null; // force re-sync next frame
                }
                return true;
            }
            if (hit(w.x, w.y, 1410f, 994f, 110f, 45f)) {
                deleteSelectedNote();
                return true;
            }
            if (w.x > 1536f) return false;

            Note clicked = visualizer.findNoteAt(w.x, w.y);
            if (clicked != null) {
                visualizer.selectNote(clicked);
                return true;
            }

            // 只处理 visualizer 的区域
            return visualizer.touchDown(w.x, w.y);
        }

        @Override
        public boolean touchDragged(int screenX, int screenY, int pointer) {
            Vector3 w = viewport.unproject(new Vector3(screenX, screenY, 0));
            return visualizer.touchDragged(w.x, w.y);
        }

        @Override
        public boolean touchUp(int screenX, int screenY, int pointer, int button) {
            return visualizer.touchUp();
        }

        @Override
        public boolean keyDown(int keycode) {
            if (curtainTransition.isActive()) return false;

            // 让 visualizer 处理，但不要阻止 Stage 接收 keyTyped
            visualizer.keyDown(keycode);

            boolean ctrl = Gdx.input.isKeyPressed(Input.Keys.CONTROL_LEFT)
                || Gdx.input.isKeyPressed(Input.Keys.CONTROL_RIGHT);
            if(ctrl && keycode == Input.Keys.S) {
                visualizer.getChart().saveToJson();
            }
            else if(ctrl && keycode == Input.Keys.C) {
                copySelectedNote();
                return true;
            }
            else if(ctrl && keycode == Input.Keys.X) {
                cutSelectedNote();
                return true;
            }
            else if(ctrl && keycode == Input.Keys.V) {
                pasteClipboardNote();
                return true;
            }
            else if(ctrl && keycode == Input.Keys.N) {
                visualizer.getChart().getNotes().add(
                    new Note(
                        1,
                        (float) (visualizer.getTimeline().getTimeMs() / 60000.0 * chartInfoTextbox.getBPM()) - 1,
                        (float) (visualizer.getTimeline().getTimeMs() / 60000.0 * chartInfoTextbox.getBPM())
                    )
                );
            }
            else if(keycode == Input.Keys.DEL || keycode == Input.Keys.FORWARD_DEL) {
                deleteSelectedNote();
                return true;
            }
            return false;
        }
    }

    private void copySelectedNote() {
        if (visualizer.selectedNote == null) return;
        clipboardNote = visualizer.selectedNote.copyAt(visualizer.selectedNote.getJudgementTime());
    }

    private void cutSelectedNote() {
        if (visualizer.selectedNote == null) return;
        clipboardNote = visualizer.selectedNote.copyAt(visualizer.selectedNote.getJudgementTime());
        deleteSelectedNote();
    }

    private void pasteClipboardNote() {
        if (clipboardNote == null) return;
        Note pasted = clipboardNote.copyAt(currentTimelineBeat());
        visualizer.getChart().getNotes().add(pasted);
        visualizer.selectNote(pasted);
    }

    private void deleteSelectedNote() {
        visualizer.deleteSelectedNote();
        lastSyncedNote = null;
        noteEditor.clear();
        eventEditor.setSelectedNote(null);
    }

    private float currentTimelineBeat() {
        return (float) (visualizer.getTimeline().getTimeMs() / 60000.0 * visualizer.getChart().getBPM());
    }

    private boolean createNoteAt(float worldX, float worldY) {
        float scale = visualizer.getScalingFactor();
        float left = visualizer.getChart().getLineEvents().getLeft() * scale;
        float right = visualizer.getChart().getLineEvents().getRight() * scale;
        float width = right - left;
        if (width <= 0f || worldX < left || worldX > right || worldY < 0f || worldY > REF_H * scale) {
            return false;
        }

        int lane = (int) ((worldX - left) / width * 10f);
        lane = Math.max(0, Math.min(9, lane));
        if (worldY >= REF_H * 0.5f * scale) {
            lane += 10;
        }

        float judge = currentTimelineBeat();
        float duration = "hold".equals(selectedNoteType) ? 1f : 0f;
        float height = "hold".equals(selectedNoteType) ? 240f : 0f;
        Note note = new Note(selectedNoteType, lane, judge - 2f, judge, duration, height, null, null, null);
        note.addMovementEvent(new Events(
            "Movement", note.getJudgementTime()-2f, note.getJudgementTime(), REF_H - 108, 0, 1
        ));
        visualizer.getChart().getNotes().add(note);
        visualizer.selectNote(note);
        return true;
    }
}
