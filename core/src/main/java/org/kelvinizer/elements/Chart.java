package org.kelvinizer.elements;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class Chart implements AutoCloseable {
    private String title;
    private String level;
    private String chartConstant;
    private float bpm;
    private String artist;
    private String illustrator;
    private String charter;
    private int combo;
    private double score;
    private final JudgementLine lineEvents;
    private final ArrayList<Note> notes;
    @JsonIgnore
    private FileHandle chartFile;
    @JsonIgnore
    private FileHandle musicFile;
    @JsonIgnore
    private FileHandle jacketFile;

    @JsonIgnore
    private final List<RenderableNote> renderableNotes = new ArrayList<>();

    @JsonCreator
    public Chart(
            @JsonProperty("title") String title,
            @JsonProperty("level") String level,
            @JsonProperty("chartConstant") String chartConstant,
            @JsonProperty("bpm") float bpm,
            @JsonProperty("artist") String artist,
            @JsonProperty("illustrator") String illustrator,
            @JsonProperty("charter") String charter,
            @JsonProperty("judgementLine") JudgementLine lineEvents,
            @JsonProperty("notes") List<Note> notes
    ) {
        this.title = title;
        this.level = level;
        this.chartConstant = chartConstant;
        this.bpm = bpm;
        this.artist = artist;
        this.illustrator = illustrator;
        this.charter = charter;
        this.notes = new ArrayList<>();
        this.lineEvents = lineEvents;
        if (notes != null) {
            this.notes.addAll(notes);
            notes.sort(Comparator.comparingDouble(Note::getJudgementTime));
        }
    }

    @JsonProperty("title")
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    @JsonProperty("level")
    public String getLevel() { return level; }
    public void setLevel(String level) { this.level = level; }

    @JsonProperty("chartConstant")
    public String getChartConstant() { return chartConstant; }
    public void setChartConstant(String chartConstant) { this.chartConstant = chartConstant; }

    @JsonProperty("bpm")
    public float getBPM() { return bpm; }
    public void setBPM(float bpm) { this.bpm = bpm; }

    @JsonProperty("artist")
    public String getArtist() {
        return artist;
    }
    public void setArtist(String artist) { this.artist = artist; }

    @JsonProperty("illustrator")
    public String getIllustrator() {
        return illustrator;
    }
    public void setIllustrator(String illustrator) {
        this.illustrator = illustrator;
    }

    @JsonProperty("charter")
    public String getCharter() {
        return charter;
    }
    public void setCharter(String charter) {
        this.charter = charter;
    }

    @JsonProperty("notes")
    public ArrayList<Note> getNotes() {
        return notes;
    }

    public List<RenderableNote> getRenderableNotes() {
        return renderableNotes;
    }

    @JsonProperty("LineEvents")
    public JudgementLine getLineEvents() {return lineEvents;}

    public void setAssetFiles(FileHandle chartFile, FileHandle musicFile, FileHandle jacketFile) {
        this.chartFile = chartFile;
        this.musicFile = musicFile;
        this.jacketFile = jacketFile;
    }

    public void clear(boolean isAuto){
    }

    @JsonIgnore
    public void update(float time, boolean isAuto) {
        renderableNotes.clear();
        if (isAuto) {
            combo = 0;
            score = 0;
            for (Note note : notes) {
                // Reset notes that are ahead of the current time so seeking backward works correctly
                if (note.getJudgementTime() > time) {
                    note.reset();
                }
                // Auto: perfect-judge any note whose judgement time has passed and hasn't been hit yet
                if (note.getJudgementTime() <= time && note.getHitScore() < 0) {
                    note.hitJudge(note.getJudgementTime()); // perfect
                }
                if (note.getHitScore() > 0) {
                    combo++;
                    score += 1;
                }
                if (note.hasAppeared(time, bpm)) {
                    note.updateForTime(time, bpm);
                    renderableNotes.add(RenderableNote.from(note));
                }
            }
        } else {
            combo = 0;
            score = 0;
            for (Note note : notes) {
                if (note.getHitScore() > 0) {
                    combo++;
                } else {
                    combo = 0;
                }
                score += Math.max(0, note.getFinalScore());
                if (note.hasAppeared(time, bpm)) {
                    note.updateForTime(time, bpm);
                    renderableNotes.add(RenderableNote.from(note));
                }
            }
        }

        if (lineEvents != null) {
            lineEvents.updateForTime(time);
        }
        score = notes.isEmpty() ? 0 : score / notes.size() * 10000000;
    }

    @JsonIgnore
    public FileHandle getChartFile() {
        return chartFile;
    }

    /** Parent folder containing chart.json, music, and jacket assets. */
    @JsonIgnore
    public FileHandle getChartFolder() {
        return chartFile != null ? chartFile.parent() : null;
    }

    @JsonIgnore
    public FileHandle getMusicFile() {
        return musicFile;
    }

    @JsonIgnore
    public FileHandle getJacketFile() {
        return jacketFile;
    }

    @JsonIgnore
    public boolean hasMusic() {
        return musicFile != null;
    }

    @JsonIgnore
    public boolean hasJacket() {
        return jacketFile != null;
    }

    @Override
    public void close() {
        dispose();
    }

    public void dispose() {}

    public int getCombo() {
        return combo;
    }

    public double getScore() {
        return score;
    }

    @JsonIgnore
    public void saveToJson() {
        try {
            chartFile.delete();
            // 生成路径：assets/Charts/title/level.json
            String folderPath = "Charts/" + title + "/";
            String filePath = folderPath + level + ".json";

            FileHandle folder = Gdx.files.local(folderPath);
            if (!folder.exists()) {
                folder.mkdirs();
            }

            FileHandle file = Gdx.files.internal(filePath);

            com.fasterxml.jackson.databind.ObjectMapper mapper =
                new com.fasterxml.jackson.databind.ObjectMapper();

            mapper.writerWithDefaultPrettyPrinter().writeValue(file.file(), this);

            System.out.println("Saved chart to: " + filePath);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
