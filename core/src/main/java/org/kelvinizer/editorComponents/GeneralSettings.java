package org.kelvinizer.editorComponents;

import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.utils.viewport.Viewport;
import org.kelvinizer.TaggedTextBox;
import org.kelvinizer.elements.Chart;

public class GeneralSettings extends Stage {
    private final TaggedTextBox title;
    private final TaggedTextBox difficulty;
    private final TaggedTextBox constant;
    private final TaggedTextBox bpm;
    private final TaggedTextBox charter;
    private final TaggedTextBox illustrator;
    private final TaggedTextBox artist;

    public GeneralSettings(Chart chart, Viewport viewport) {
        super(viewport);

        // -----------------------------
        // Create UI elements
        // -----------------------------
        title = new TaggedTextBox(
            "Title",
            chart.getTitle(),
            20f, 884f, 100f, 360f, 45f
        );

        bpm = new TaggedTextBox(
            "BPM",
            Double.toString(chart.getBPM()),
            20f, 939f, 100f, 120f, 45f
        );
        bpm.setTextBoxFilter((textField, c) -> {
            // 允许数字
            if (c >= '0' && c <= '9') return true;
            // 允许一个小数点
            return c == '.' && !textField.getText().contains(".");
        });

        difficulty = new TaggedTextBox(
            "Level",
            chart.getLevel(),
            260f, 994f, 100f, 120f, 45f
        );

        constant = new TaggedTextBox(
            "Const",
            chart.getChartConstant(),
            260f, 939f, 100f, 120f, 45f
        );

        artist = new TaggedTextBox(
            "Artist",
            chart.getArtist(),
            500f, 994f, 160f, 280f, 45f
        );

        illustrator = new TaggedTextBox(
            "Illustrator",
            chart.getIllustrator(),
            500f, 939f, 160f, 280f, 45f
        );

        charter = new TaggedTextBox(
            "Charter",
            chart.getCharter(),
            500f, 884f, 160f, 280f, 45f
        );

        // -----------------------------
        // Add actors
        // -----------------------------
        addActor(title);
        addActor(bpm);
        addActor(difficulty);
        addActor(constant);
        addActor(illustrator);
        addActor(artist);
        addActor(charter);
    }

    // -----------------------------
    // Public getters
    // -----------------------------
    public String getTitle() {
        return title.getText();
    }

    public float getBPM() {
        return Float.parseFloat(bpm.getText());
    }

    public String getConstant() {
        return constant.getText();
    }

    public String getIllustrator() {
        return illustrator.getText();
    }

    public String getArtist() {
        return artist.getText();
    }

    public String getDifficulty() {
        return difficulty.getText();
    }

    public String getCharter() {
        return charter.getText();
    }

    // -----------------------------
    // Input handling
    // -----------------------------
    @Override
    public boolean touchDown(int screenX, int screenY, int pointer, int button) {
        Vector3 v = new Vector3(screenX, screenY, 0);
        getViewport().unproject(v);
        if(!super.touchDown(screenX, screenY, pointer, button)){
            this.setKeyboardFocus(null);
        }
        return super.touchDown(screenX, screenY, pointer, button);
    }
}
