package org.kelvinizer.elements;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.Comparator;

public class JudgementLine {

    // ===== Initial (baseline) values =====

    private float upperJudgementLine;
    private float lowerJudgementLine;
    private float leftBound;
    private float rightBound;

    // ===== Time-based events =====

    private final ArrayList<Events> upperEvents;
    private final ArrayList<Events> lowerEvents;
    private final ArrayList<Events> leftEvents;
    private final ArrayList<Events> rightEvents;

    // ===== Runtime state (renderer only) =====

    @JsonIgnore
    private float currentUpper;

    @JsonIgnore
    private float currentLower;

    @JsonIgnore
    private float currentLeft;

    @JsonIgnore
    private float currentRight;

    // ===== Constructor =====

    @JsonCreator
    public JudgementLine(
        @JsonProperty("upperInitial") float upperJudgementLine,
        @JsonProperty("lowerInitial") float lowerJudgementLine,
        @JsonProperty("initialLeftBound") float leftBound,
        @JsonProperty("initialRightBound") float rightBound,
        @JsonProperty("upperEvents") ArrayList<Events> upperEvents,
        @JsonProperty("lowerEvents") ArrayList<Events> lowerEvents,
        @JsonProperty("leftEvents") ArrayList<Events> leftEvents,
        @JsonProperty("rightEvents") ArrayList<Events> rightEvents
    ) {
        this.upperJudgementLine = upperJudgementLine;
        this.lowerJudgementLine = lowerJudgementLine;
        this.leftBound = leftBound;
        this.rightBound = rightBound;

        this.upperEvents = upperEvents != null ? upperEvents : new ArrayList<>();
        this.lowerEvents = lowerEvents != null ? lowerEvents : new ArrayList<>();
        this.leftEvents  = leftEvents  != null ? leftEvents  : new ArrayList<>();
        this.rightEvents = rightEvents != null ? rightEvents : new ArrayList<>();

        sortEvents();
    }

    // ===== Update =====

    public void updateForTime(float time) {
        currentUpper = Events.calculateValue(upperEvents, time,  972);
        currentLower = Events.calculateValue(lowerEvents, time, 108);
        currentLeft  = Events.calculateValue(leftEvents, time, -240);
        currentRight = Events.calculateValue(rightEvents, time, 2160);
    }

    private void sortEvents() {
        Comparator<Events> cmp = Comparator.comparingDouble(Events::getStartTime);
        upperEvents.sort(cmp);
        lowerEvents.sort(cmp);
        leftEvents.sort(cmp);
        rightEvents.sort(cmp);
    }

    // ===== Initial value getters / setters =====

    public double getUpperJudgementLine() {
        return upperJudgementLine;
    }

    public void setUpperJudgementLine(float upperJudgementLine) {
        this.upperJudgementLine = upperJudgementLine;
    }

    public double getLowerJudgementLine() {
        return lowerJudgementLine;
    }

    public void setLowerJudgementLine(float lowerJudgementLine) {
        this.lowerJudgementLine = lowerJudgementLine;
    }

    public double getLeftBound() {
        return leftBound;
    }

    public void setLeftBound(float leftBound) {
        this.leftBound = leftBound;
    }

    public double getRightBound() {
        return rightBound;
    }

    public void setRightBound(float rightBound) {
        this.rightBound = rightBound;
    }

    // ===== Event getters (JSON) =====

    @JsonProperty("upperEvents")
    public ArrayList<Events> getUpperEvents() {
        return upperEvents;
    }

    @JsonProperty("lowerEvents")
    public ArrayList<Events> getLowerEvents() {
        return lowerEvents;
    }

    @JsonProperty("leftEvents")
    public ArrayList<Events> getLeftEvents() {
        return leftEvents;
    }

    @JsonProperty("rightEvents")
    public ArrayList<Events> getRightEvents() {
        return rightEvents;
    }

    public void addUpperEvent(Events event) {
        if(event.getType() == Events.TYPE.JudgementUp){
            upperEvents.add(event);
            upperEvents.sort(Comparator.comparingDouble(Events::getStartTime));
        }
    }

    public void addLowerEvent(Events event) {
        if(event.getType() == Events.TYPE.JudgementDown){
            lowerEvents.add(event);
            lowerEvents.sort(Comparator.comparingDouble(Events::getStartTime));
        }
    }

    public void addLeftEvent(Events event) {
        if(event.getType() == Events.TYPE.LeftBound){
            leftEvents.add(event);
            leftEvents.sort(Comparator.comparingDouble(Events::getStartTime));
        }
    }

    public void addRightEvent(Events event) {
        if(event.getType() == Events.TYPE.RightBound){
            rightEvents.add(event);
            rightEvents.sort(Comparator.comparingDouble(Events::getStartTime));
        }
    }

    // ===== Runtime getters (renderer only) =====

    @JsonIgnore
    public float getUpper() {
        return currentUpper;
    }

    @JsonIgnore
    public float getLower() {
        return currentLower;
    }

    @JsonIgnore
    public float getLeft() {
        return currentLeft;
    }

    @JsonIgnore
    public float getRight() {
        return currentRight;
    }
}
