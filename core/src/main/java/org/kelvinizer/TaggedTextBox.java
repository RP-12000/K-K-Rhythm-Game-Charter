package org.kelvinizer;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.scenes.scene2d.Group;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.ui.Image;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.TextField;
import com.badlogic.gdx.scenes.scene2d.ui.Label.LabelStyle;
import com.badlogic.gdx.scenes.scene2d.ui.TextField.TextFieldStyle;
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener;
import com.badlogic.gdx.scenes.scene2d.utils.TextureRegionDrawable;
import com.badlogic.gdx.utils.Align;
import com.badlogic.gdx.graphics.g2d.freetype.FreeTypeFontGenerator;

public class TaggedTextBox extends Group {

    private Label tagLabel;
    private TextField textField;

    private Image tagBg;
    private Image textBg;

    private BitmapFont font;

    private static final float LEFT_PADDING = 12f;

    public TaggedTextBox(
        String tagText,
        String defaultText,
        float x, float y,
        float tagWidth,
        float textBoxWidth,
        float height,
        Color tagColor,
        Color textBoxColor
    ) {
        // -----------------------------
        // Load NotoSansSC-Medium.ttf font (size 30)
        // -----------------------------
        FreeTypeFontGenerator gen = new FreeTypeFontGenerator(Gdx.files.internal("NotoSansCJKsc-Regular.otf"));
        FreeTypeFontGenerator.FreeTypeFontParameter p = new FreeTypeFontGenerator.FreeTypeFontParameter();
        p.size = 30;
//        StringBuilder sb = new StringBuilder(65535);
//        for (char c = 0; c < 65535; c++) sb.append(c);
//        p.characters = sb.toString();
        font = gen.generateFont(p);
        gen.dispose();

        // -----------------------------
        // Create pure color backgrounds
        // -----------------------------
        tagBg = new Image(makeColorTexture(tagColor));
        tagBg.setSize(tagWidth, height);
        tagBg.setPosition(0, 0);

        textBg = new Image(makeColorTexture(textBoxColor));
        textBg.setSize(textBoxWidth, height);
        textBg.setPosition(tagWidth, 0);

        // -----------------------------
        // Tag label
        // -----------------------------
        LabelStyle tagStyle = new LabelStyle();
        tagStyle.font = font;
        tagStyle.fontColor = Color.WHITE;

        tagLabel = new Label(tagText, tagStyle);
        tagLabel.setAlignment(Align.center);
        tagLabel.setSize(tagWidth, height);
        tagLabel.setPosition(0, 0);

        // -----------------------------
        // TextField
        // -----------------------------
        TextFieldStyle tfStyle = new TextFieldStyle();
        tfStyle.font = font;
        tfStyle.fontColor = Color.WHITE;
        tfStyle.cursor = new TextureRegionDrawable(new TextureRegion(makeColorTexture(Color.WHITE)));
        tfStyle.background = null; // we use our own background

        textField = new TextField(defaultText, tfStyle);
        textField.setPosition(tagWidth + LEFT_PADDING, 0);
        textField.setSize(textBoxWidth - LEFT_PADDING, height);

        // Single line, no wrapping
        textField.setMaxLength(9999);
        textField.setOnlyFontChars(false);
        textField.setAlignment(Align.left);

        // Enable horizontal scrolling
        textField.setCursorPosition(defaultText.length());
        textField.setFocusTraversal(false);

        textField.addListener(new ClickListener() {
            @Override
            public void clicked(InputEvent event, float x, float y) {
                Stage stage = textField.getStage();
                stage.setKeyboardFocus(textField);
            }
        });

        // -----------------------------
        // Add actors to group
        // -----------------------------
        addActor(tagBg);
        addActor(textBg);
        addActor(tagLabel);
        addActor(textField);

        // Set group size and position
        setSize(tagWidth + textBoxWidth, height);
        setPosition(x, y);
    }

    public TaggedTextBox(
        String tagText,
        String defaultText,
        float x, float y,
        float tagWidth,
        float textBoxWidth,
        float height
    ) {
        this(tagText, defaultText, x, y, tagWidth, textBoxWidth, height, Params.COLOR_TITLE_TAG, Params.COLOR_TITLE_TEXTBOX);
    }

    // Utility: create a 1x1 colored texture
    private Texture makeColorTexture(Color color) {
        Pixmap pm = new Pixmap(1, 1, Pixmap.Format.RGBA8888);
        pm.setColor(color);
        pm.fill();
        Texture tex = new Texture(pm);
        pm.dispose();
        return tex;
    }

    // -----------------------------
    // Public API
    // -----------------------------
    public String getText() {
        return textField.getText();
    }

    public void setText(String text) {
        textField.setText(text);
    }

    public void setTagText(String text) {
        tagLabel.setText(text);
    }

    public void setTagColor(Color color) {
        tagBg.setDrawable(new TextureRegionDrawable(new TextureRegion(makeColorTexture(color))));
    }

    public void setTextBoxColor(Color color) {
        textBg.setDrawable(new TextureRegionDrawable(new TextureRegion(makeColorTexture(color))));
    }

    public void setTextBoxFilter(TextField.TextFieldFilter filter) {
        textField.setTextFieldFilter(filter);
    }
}
