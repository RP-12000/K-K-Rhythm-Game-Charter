package org.kelvinizer;

import com.badlogic.gdx.graphics.Color;

public class Params {
    public static final float REF_W = 1920;
    public static final float REF_H = 1080;

    public static final float JUDGEMENT_BOUNDS = 216;

    public static final float SYNC_BOUND_WIDTH = 5;

    public static final Color COLOR_TITLE_TAG = Color.CYAN;
    public static final Color COLOR_TITLE_TEXTBOX = Color.DARK_GRAY;

    public static boolean hit(float wx, float wy, float x, float y, float w, float h) {
        return wx >= x && wx <= x + w && wy >= y && wy <= y + h;
    }
}
