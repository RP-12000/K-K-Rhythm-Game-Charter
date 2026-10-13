package org.kelvinizer.elements;

import java.util.List;

public class RenderableChart {
    private final RenderableJudgementLine judgementLine;
    private final List<RenderableNote> notes;
    String title, level;
    String chartConstant;
    private final int combo;
    private final int score;

    public RenderableChart(
        String title, String difficulty, String chartConstant,
            RenderableJudgementLine judgementLine, List<RenderableNote> notes,
        int combo, double score
    ) {
        this.title = title;
        this.level = difficulty;
        this.chartConstant = chartConstant;
        this.judgementLine = judgementLine;
        this.notes = notes;
        this.combo = combo;
        this.score = Math.toIntExact(Math.round(score));
    }

    public static RenderableChart from(Chart chart){
        return new RenderableChart(
            chart.getTitle(),
            chart.getLevel(),
            chart.getChartConstant(),
            RenderableJudgementLine.from(chart.getLineEvents()),
            chart.getRenderableNotes(),
            chart.getCombo(),
            chart.getScore()
        );
    }

    public RenderableJudgementLine getJudgementLine() {
        return judgementLine;
    }

    public List<RenderableNote> getNotes() {
        return notes;
    }

    public String getTitle() {
        return title;
    }

    public String getLevel() {
        return level;
    }

    public String getChartConstant() {
        return chartConstant;
    }

    public int getScore() {
        return score;
    }

    public int getCombo() {
        return combo;
    }
}
