package org.kelvinizer.editorComponents;

import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.utils.viewport.Viewport;
import org.kelvinizer.TaggedTextBox;
import org.kelvinizer.elements.Note;

public class NoteEditor extends Stage {
    private final TaggedTextBox judgementTime;
    private final TaggedTextBox lane;
    private final TaggedTextBox duration;
    private final TaggedTextBox height;

    public NoteEditor(Viewport viewport) {
        super(viewport);
        judgementTime = new TaggedTextBox(
            "Judge",
            "",
            960f, 884f, 150f, 130f, 45f
        );
        judgementTime.setTextBoxFilter((textField, c) -> {
            // 允许数字
            if (c >= '0' && c <= '9') return true;
            // 允许一个小数点
            return c == '.' && !textField.getText().contains(".");
        });

        lane = new TaggedTextBox(
            "Lane",
            "",
            960f, 939f, 150f, 130f, 45f
        );

        lane.setTextBoxFilter((textField, c) -> {
            // 允许数字
            return (c >= '0' && c <= '9');

        });

        duration = new TaggedTextBox(
            "Duration",
            "",
            1260f, 884f, 150f, 130f, 45f
        );
        duration.setTextBoxFilter((textField, c) -> {
            // 允许数字
            if (c >= '0' && c <= '9') return true;
            // 允许一个小数点
            return c == '.' && !textField.getText().contains(".");
        });

        height = new TaggedTextBox(
            "Length",
            "",
            1260, 939f, 150f, 130f, 45f
        );
        height.setTextBoxFilter((textField, c) -> {
            // 允许数字
            if (c >= '0' && c <= '9') return true;
            // 允许一个小数点
            return c == '.' && !textField.getText().contains(".");
        });

        addActor(judgementTime);
        addActor(lane);
        addActor(duration);
        addActor(height);
    }

    public void clear(){
        judgementTime.setText("");
        lane.setText("");
        duration.setText("");
        height.setText("");
    }

    public int getLane(){
        return Integer.parseInt(lane.getText());
    }

    public float getDuration(){
        return Float.parseFloat(duration.getText());
    }

    public float getHeight(){
        return Float.parseFloat(height.getText());
    }

    public float getJudgementTime(){
        return Float.parseFloat(judgementTime.getText());
    }

    @Override
    public boolean touchDown(int screenX, int screenY, int pointer, int button) {
        boolean hit = super.touchDown(screenX, screenY, pointer, button);
        if (!hit) {
            this.setKeyboardFocus(null);
        }
        return hit;
    }

    public boolean isEmpty(){
        return height.getText().isEmpty() &&
            duration.getText().isEmpty() &&
            lane.getText().isEmpty() &&
            judgementTime.getText().isEmpty();
    }

    public boolean hasCompleteValues() {
        return !height.getText().isEmpty()
            && !duration.getText().isEmpty()
            && !lane.getText().isEmpty()
            && !judgementTime.getText().isEmpty();
    }

    public void setText(Note n){
        judgementTime.setText(String.valueOf(n.getJudgementTime()));
        lane.setText(String.valueOf(n.getLane()));
        duration.setText(String.valueOf(n.getDuration()));
        height.setText(String.valueOf(n.getHeight()));
    }
}
