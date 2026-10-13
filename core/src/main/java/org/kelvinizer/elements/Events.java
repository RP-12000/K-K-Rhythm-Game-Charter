package org.kelvinizer.elements;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;

public class Events {
    public enum TYPE {Movement, Rotation, Opacity, LeftBound, RightBound, JudgementUp, JudgementDown}
    private TYPE type;
    private float startTime;
    private float endTime;
    private float initialVal;
    private float finalVal;
    private float exponent;

    @JsonCreator
    public Events(
        @JsonProperty("type") String type,
        @JsonProperty("startTime") float startTime,
        @JsonProperty("endTime") float endTime,
        @JsonProperty("initial") float initialVal,
        @JsonProperty("final") float finalVal,
        @JsonProperty("exponent") float exponent
    ) {
        switch (type) {
            case "Movement":
                this.type = TYPE.Movement;
                break;
            case "Rotation":
                this.type = TYPE.Rotation;
                break;
            case "Opacity":
                this.type = TYPE.Opacity;
                break;
            case "LeftBound":
                this.type = TYPE.LeftBound;
                break;
            case "RightBound":
                this.type = TYPE.RightBound;
                break;
            case "JudgementUp":
                this.type = TYPE.JudgementUp;
                break;
            case "JudgementDown":
                this.type = TYPE.JudgementDown;
                break;
            default:
                break;
        }
        this.startTime = startTime;
        this.endTime = endTime;
        this.initialVal = initialVal;
        this.finalVal = finalVal;
        this.exponent = exponent;
    }

    public TYPE getType() {return type;}

    public String getTypeName() {
        return type != null ? type.name() : "";
    }

    public float getStartTime() {
        return startTime;
    }

    public float getEndTime() {
        return endTime;
    }

    public float getInitialVal() {
        return initialVal;
    }

    public float getFinalVal() {
        return finalVal;
    }

    public float getExponent() {
        return exponent;
    }

    public void setStartTime(float startTime) { this.startTime = startTime; }
    public void setEndTime(float endTime) { this.endTime = endTime; }
    public void setInitialVal(float initialVal) { this.initialVal = initialVal; }
    public void setFinalVal(float finalVal) { this.finalVal = finalVal; }
    public void setExponent(float exponent) { this.exponent = exponent; }

    public boolean contains(float time) {
        return startTime <= time && time <= endTime;
    }

    public float getCurrentVal(float time){
        if (endTime == startTime || time > endTime) {
            return finalVal;
        }
        return (float) (initialVal + (finalVal - initialVal) * Math.pow((time - startTime) / (endTime - startTime), exponent));
    }

    public static float calculateValue(ArrayList<Events> events, float time, float defaultVal) {
        int low = 0;
        int high = events.size();
        while (low < high) {
            int mid = (low + high) / 2;
            if (events.get(mid).getStartTime() <= time) {
                low = mid + 1;
            } else {
                high = mid;
            }
        }
        if (low > 0 && events.get(low - 1).contains(time)) {
            return events.get(low - 1).getCurrentVal(time);
        }
        return defaultVal;
    }
}
