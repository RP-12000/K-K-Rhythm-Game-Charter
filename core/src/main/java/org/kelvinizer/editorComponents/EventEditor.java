package org.kelvinizer.editorComponents;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.graphics.g2d.freetype.FreeTypeFontGenerator;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener;
import com.badlogic.gdx.scenes.scene2d.utils.TextureRegionDrawable;
import com.badlogic.gdx.utils.Align;
import com.badlogic.gdx.utils.viewport.Viewport;
import org.kelvinizer.TaggedTextBox;
import org.kelvinizer.elements.Events;
import org.kelvinizer.elements.Note;

import java.util.ArrayList;

public class EventEditor extends Stage {
    private enum EventKind {
        MOVE("Move", "Movement"),
        ROTATE("Rotate", "Rotation"),
        OPACITY("Opacity", "Opacity");

        final String label;
        final String eventType;

        EventKind(String label, String eventType) {
            this.label = label;
            this.eventType = eventType;
        }
    }

    private final TaggedTextBox start;
    private final TaggedTextBox end;
    private final TaggedTextBox initial;
    private final TaggedTextBox target;
    private final TaggedTextBox exponent;
    private final TaggedTextBox deleteIndex;

    private final BitmapFont font;
    private final Texture buttonTexture;
    private final Texture activeButtonTexture;
    private final Table eventTable;
    private final ScrollPane scrollPane;
    private final Label moveButton;
    private final Label rotateButton;
    private final Label opacityButton;

    private Note selectedNote;
    private EventKind selectedKind = EventKind.MOVE;

    // Checkbox state — mirrors selectedNote.isEventsLocked()
    private boolean lockEventsChecked = false;
    private final Texture checkboxOff;
    private final Texture checkboxOn;
    private Label checkboxLabel;

    public EventEditor(Viewport viewport) {
        super(viewport);
        font = buildFont();
        buttonTexture = makeColorTexture(new Color(0.16f, 0.18f, 0.20f, 1f));
        activeButtonTexture = makeColorTexture(new Color(0.15f, 0.65f, 0.75f, 1f));
        checkboxOff = makeColorTexture(new Color(0.16f, 0.18f, 0.20f, 1f));
        checkboxOn  = makeColorTexture(new Color(0.15f, 0.75f, 0.95f, 1f));

        moveButton = kindButton(EventKind.MOVE, 1545f);
        rotateButton = kindButton(EventKind.ROTATE, 1668f);
        opacityButton = kindButton(EventKind.OPACITY, 1791f);
        addActor(moveButton);
        addActor(rotateButton);
        addActor(opacityButton);

        // "Lock Events" checkbox — sits above the kind buttons
        checkboxLabel = buildCheckboxLabel();
        addActor(checkboxLabel);

        eventTable = new Table();
        eventTable.top().left();
        scrollPane = new ScrollPane(eventTable);
        scrollPane.setPosition(1545f, 375f);
        scrollPane.setSize(360f, 600f);
        scrollPane.setScrollingDisabled(true, false);
        addActor(scrollPane);

        start = field("Start", "0", 1545f, 365f);
        end = field("End", "1", 1545f, 310f);
        initial = field("Initial", "0", 1545f, 255f);
        target = field("Final", "0", 1545f, 200f);
        exponent = field("Exp", "1", 1545f, 145f);
        addActor(start);
        addActor(end);
        addActor(initial);
        addActor(target);
        addActor(exponent);
        addActor(actionButton("Add Event", 1545f, 90f, this::addEvent));
        deleteIndex = new TaggedTextBox("No.", "1", 1545f, 35f, 70f, 90f, 38f);
        deleteIndex.setTextBoxFilter((textField, c) -> c >= '0' && c <= '9');
        addActor(deleteIndex);
        Label deleteButton = actionButton("Delete Event", 1715f, 35f, this::deleteEvent);
        deleteButton.setSize(190f, 38f);
        addActor(deleteButton);

        refreshEventList();
        refreshKindButtons();
    }

    public void setSelectedNote(Note selectedNote) {
        if (this.selectedNote == selectedNote) return;
        this.selectedNote = selectedNote;
        // Sync checkbox to the newly selected note's lock state
        lockEventsChecked = selectedNote != null && selectedNote.isEventsLocked();
        refreshCheckbox();
        refreshEventList();
    }

    private Label buildCheckboxLabel() {
        Label.LabelStyle style = new Label.LabelStyle(font, Color.WHITE);
        style.background = new TextureRegionDrawable(new TextureRegion(checkboxOff));
        Label label = new Label("  Lock Events", style);
        label.setAlignment(Align.left);
        label.setPosition(1545f, 1030f);
        label.setSize(360f, 40f);
        label.addListener(new ClickListener() {
            @Override
            public void clicked(InputEvent event, float x, float y) {
                lockEventsChecked = !lockEventsChecked;
                if (selectedNote != null) selectedNote.setEventsLocked(lockEventsChecked);
                refreshCheckbox();
            }
        });
        return label;
    }

    private void refreshCheckbox() {
        checkboxLabel.getStyle().background = new TextureRegionDrawable(
            new TextureRegion(lockEventsChecked ? checkboxOn : checkboxOff));
        // Prefix the label text with a visual tick indicator
        checkboxLabel.setText(lockEventsChecked ? "  ✓ Lock Events" : "  Lock Events");
    }

    private Label kindButton(EventKind kind, float x) {
        Label label = actionButton(kind.label, x, 980f, () -> {
            selectedKind = kind;
            refreshKindButtons();
            refreshEventList();
        });
        label.setSize(114f, 40f);
        return label;
    }

    private Label actionButton(String text, float x, float y, Runnable action) {
        Label.LabelStyle style = new Label.LabelStyle(font, Color.WHITE);
        style.background = new TextureRegionDrawable(new TextureRegion(buttonTexture));
        Label label = new Label(text, style);
        label.setAlignment(Align.center);
        label.setPosition(x, y);
        label.setSize(360f, 45f);
        label.addListener(new ClickListener() {
            @Override
            public void clicked(InputEvent event, float x, float y) {
                action.run();
            }
        });
        return label;
    }

    private TaggedTextBox field(String tag, String value, float x, float y) {
        TaggedTextBox box = new TaggedTextBox(tag, value, x, y, 120f, 240f, 45f);
        box.setTextBoxFilter((textField, c) -> {
            String text = textField.getText();

            // 允许数字
            if (c >= '0' && c <= '9') {
                return true;
            }
            // 允许一个小数点
            if (c == '.') {
                return !text.contains(".");
            }
            // 允许负号（只能在首位，且只能一个）
            if (c == '-') {
                return text.isEmpty();
            }
            return false;
        });
        return box;
    }

    private void addEvent() {
        if (selectedNote == null) return;
        float baseBeat = selectedNote.getJudgementTime();
        Events event = new Events(
            selectedKind.eventType,
            baseBeat + parse(start),
            baseBeat + parse(end),
            parse(initial),
            parse(target),
            parse(exponent)
        );
        switch (selectedKind) {
            case MOVE:
                selectedNote.addMovementEvent(event);
                break;
            case ROTATE:
                selectedNote.addRotationEvent(event);
                break;
            case OPACITY:
                selectedNote.addOpacityEvent(event);
                break;
            default:
                break;
        }
        refreshEventList();
    }

    private void deleteEvent() {
        if (selectedNote == null) return;
        ArrayList<Events> events = currentEvents();
        int index = parseIndex(deleteIndex.getText()) - 1;
        if (index < 0 || index >= events.size()) return;
        events.remove(index);
        refreshEventList();
    }

    private void refreshKindButtons() {
        setButtonActive(moveButton, selectedKind == EventKind.MOVE);
        setButtonActive(rotateButton, selectedKind == EventKind.ROTATE);
        setButtonActive(opacityButton, selectedKind == EventKind.OPACITY);
    }

    private void setButtonActive(Label label, boolean active) {
        label.getStyle().background = new TextureRegionDrawable(
            new TextureRegion(active ? activeButtonTexture : buttonTexture));
    }

    private void refreshEventList() {
        eventTable.clear();
        addRow(selectedNote == null ? "No note selected" : selectedKind.label + " Events");
        if (selectedNote == null) return;

        ArrayList<Events> events = currentEvents();
        if (events.isEmpty()) {
            addRow("(empty)");
            return;
        }
        for (int i = 0; i < events.size(); i++) {
            Events e = events.get(i);
            addRow(String.format(
                "%02d  %+.3f-%+.3f  %.2f -> %.2f  exp %.2f",
                i + 1,
                e.getStartTime() - selectedNote.getJudgementTime(),
                e.getEndTime() - selectedNote.getJudgementTime(),
                e.getInitialVal(),
                e.getFinalVal(),
                e.getExponent()
            ));
        }
    }

    private ArrayList<Events> currentEvents() {
        switch (selectedKind) {
            case ROTATE:
                return selectedNote.getRotationEvents();
            case OPACITY:
                return selectedNote.getOpacityEvents();
            case MOVE:
            default:
                return selectedNote.getMovementEvents();
        }
    }

    private void addRow(String text) {
        Label.LabelStyle style = new Label.LabelStyle(font, Color.WHITE);
        Label label = new Label(text, style);
        label.setWrap(false);
        eventTable.add(label).left().width(340f).padBottom(8f).row();
    }

    private float parse(TaggedTextBox box) {
        try {
            return Float.parseFloat(box.getText());
        } catch (NumberFormatException e) {
            return 0f;
        }
    }

    private int parseIndex(String text) {
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    @Override
    public boolean touchDown(int screenX, int screenY, int pointer, int button) {
        Vector3 v = new Vector3(screenX, screenY, 0);
        getViewport().unproject(v);
        if (v.x < 1536f) return false;
        boolean handled = super.touchDown(screenX, screenY, pointer, button);
        if (!handled) setKeyboardFocus(null);
        return handled;
    }

    @Override
    public void dispose() {
        super.dispose();
        font.dispose();
        buttonTexture.dispose();
        activeButtonTexture.dispose();
        checkboxOff.dispose();
        checkboxOn.dispose();
    }

    private BitmapFont buildFont() {
        FreeTypeFontGenerator gen = new FreeTypeFontGenerator(Gdx.files.internal("NotoSansCJKsc-Regular.otf"));
        FreeTypeFontGenerator.FreeTypeFontParameter p = new FreeTypeFontGenerator.FreeTypeFontParameter();
        p.size = 20;
        BitmapFont result = gen.generateFont(p);
        gen.dispose();
        return result;
    }

    private Texture makeColorTexture(Color color) {
        Pixmap pm = new Pixmap(1, 1, Pixmap.Format.RGBA8888);
        pm.setColor(color);
        pm.fill();
        Texture tex = new Texture(pm);
        pm.dispose();
        return tex;
    }
}
