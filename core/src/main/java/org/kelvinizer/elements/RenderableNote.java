package org.kelvinizer.elements;

import com.badlogic.gdx.math.Polygon;

public class RenderableNote{
    private static final float NOTE_WIDTH = 200;
    private static final float NOTE_HEIGHT = 40;

    public String type;
    public int lane;
    public float currentPos;
    public float currentAngle;
    public float currentOpacity;
    public float currentHeight;
    public float judgementTime;
    public boolean sync;

    public Polygon noteObject = new Polygon();

    public RenderableNote(String type, int lane, float currentPos, float currentAngle, float currentOpacity, float currentHeight, float judgementTime) {
        this.type = type;
        this.lane = lane;
        this.currentPos = currentPos;
        this.currentAngle = currentAngle;
        this.currentOpacity = currentOpacity;
        this.currentHeight = currentHeight;
        this.judgementTime = judgementTime;
        sync = false;
        if(type.equals("hold")){
            if(lane < 10){
                noteObject.setVertices(new float[]{
                    - NOTE_WIDTH / 2, - NOTE_HEIGHT / 2,
                    NOTE_WIDTH / 2, - NOTE_HEIGHT / 2,
                    NOTE_WIDTH / 2, NOTE_HEIGHT / 2,
                    NOTE_WIDTH * 1 / 6, NOTE_HEIGHT / 2,
                    NOTE_WIDTH * 1 / 6, NOTE_HEIGHT / 2 + currentHeight,
                    - NOTE_WIDTH * 1 / 6, NOTE_HEIGHT / 2 + currentHeight,
                    - NOTE_WIDTH * 1 / 6, NOTE_HEIGHT / 2,
                    - NOTE_WIDTH / 2, NOTE_HEIGHT / 2
                });
            }
            else{
                noteObject.setVertices(new float[]{
                    - NOTE_WIDTH / 2, NOTE_HEIGHT / 2,
                    NOTE_WIDTH / 2, NOTE_HEIGHT / 2,
                    NOTE_WIDTH / 2, - (NOTE_HEIGHT / 2 + currentHeight),
                    NOTE_WIDTH * 1 / 6, - (NOTE_HEIGHT / 2 + currentHeight),
                    NOTE_WIDTH * 1 / 6, - NOTE_HEIGHT / 2,
                    - NOTE_WIDTH * 1 / 6, - NOTE_HEIGHT / 2,
                    - NOTE_WIDTH * 1 /
                        6, - (NOTE_HEIGHT / 2 + currentHeight),
                    - NOTE_WIDTH / 2, - (NOTE_HEIGHT / 2 + currentHeight)
                });
            }
        } else {
            noteObject.setVertices(new float[]{
                    - NOTE_WIDTH / 2, - NOTE_HEIGHT / 2,
                    + NOTE_WIDTH / 2, - NOTE_HEIGHT / 2,
                    + NOTE_WIDTH / 2, + NOTE_HEIGHT / 2,
                    - NOTE_WIDTH / 2, + NOTE_HEIGHT / 2,
            });
        }
        noteObject.setOrigin(0, 0);
        noteObject.setRotation((float) currentAngle);
    }

    public static RenderableNote from(Note note) {
        return new RenderableNote(
            note.getType(),
            note.getLane(),
            note.getCurrentPos(),
            note.getCurrentAngle(),
            note.getCurrentOpacity(),
            note.getCurrentHeight(),
            note.getJudgementTime()
        );
    }
}
