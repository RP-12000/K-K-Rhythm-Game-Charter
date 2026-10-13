package org.kelvinizer.elements;

import java.util.ArrayList;

public class RenderableJudgementLine{
    private float upper;
    private float lower;
    private float horizontalDistance;
    private final ArrayList<Float> vertical = new ArrayList<>();

    public RenderableJudgementLine(){}

    public RenderableJudgementLine(float upper, float lower, float left, float right) {
        this.upper = upper;
        this.lower = lower;
        this.horizontalDistance = right - left;
        for(int i = 0; i <= 10; i++){
            vertical.add(left + (right - left) / 10f * i);
        }
    }

    public static RenderableJudgementLine from(JudgementLine lines) {
        return new RenderableJudgementLine(
                lines.getUpper(),
                lines.getLower(),
                lines.getLeft(),
                lines.getRight()
        );
    }

    public float getUpper() {
        return upper;
    }

    public float getLower() {
        return lower;
    }

    public float getHorizontalDistance() {
        return horizontalDistance;
    }

    public ArrayList<Float> getVertical() {
        return vertical;
    }
}
