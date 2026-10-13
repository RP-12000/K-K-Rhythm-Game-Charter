package org.kelvinizer.elements;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import jdk.jfr.Event;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class Note{
    private String type;
    private int lane;
    private float appearTime;
    private float judgementTime;
    private float duration;
    private float height;
    private final ArrayList<Events> opacityEvents;
    private final ArrayList<Events> rotationEvents;
    private final ArrayList<Events> movementEvents;
    @JsonIgnore
    private float currentPos;
    @JsonIgnore
    private float currentAngle;
    @JsonIgnore
    private float currentOpacity;
    @JsonIgnore
    private float currentHeight;

    @JsonIgnore
    private double hitScore = -1;
    private double finalScore = -1;
    private boolean firstHitDone = false;

    /** Editor-only flag: when true, setJudgementTime() does not shift event times. Not written to JSON. */
    @JsonIgnore
    private boolean eventsLocked = false;

    @JsonCreator
    public Note(
            @JsonProperty("type") String type,
            @JsonProperty("lane") int lane,
            @JsonProperty("appearance") float appearTime,
            @JsonProperty("judgement") float judgementTime,
            @JsonProperty("duration") float duration,
            @JsonProperty("height") @JsonAlias("length") float height,
            @JsonProperty("OpacityEvents") List<Events> opacityEvents,
            @JsonProperty("RotationEvents") List<Events> rotationEvents,
            @JsonProperty("MovementEvents") List<Events> movementEvents
    ) {
        this.type = type;
        this.lane = lane;
        this.appearTime = appearTime;
        this.judgementTime = judgementTime;
        this.duration = duration;
        this.height = height;
        this.opacityEvents = new ArrayList<>();
        this.rotationEvents = new ArrayList<>();
        this.movementEvents = new ArrayList<>();
        if (opacityEvents != null) {
            this.opacityEvents.addAll(opacityEvents);
        }
        if (rotationEvents != null) {
            this.rotationEvents.addAll(rotationEvents);
        }
        if (movementEvents != null) {
            this.movementEvents.addAll(movementEvents);
        }
        sortEvents();
    }

    public Note(int lane, float appearTime, float judgementTime) {
        this(
            "tap", lane, appearTime, judgementTime, 0, 0,
            new ArrayList<>(), new ArrayList<>(), null);
    }

    public Note(int lane, float appearTime, float judgementTime, float duration, float height) {
        this("hold", lane, appearTime, judgementTime, duration, height, null, null, null);
    }

    public Note copyAt(float newJudgementTime) {
        float offset = newJudgementTime - judgementTime;
        // When locked, events stay at their absolute times; don't shift them.
        ArrayList<Events> opacityCopies  = copyEvents(opacityEvents,  eventsLocked ? 0f : offset);
        ArrayList<Events> rotationCopies = copyEvents(rotationEvents, eventsLocked ? 0f : offset);
        ArrayList<Events> movementCopies = copyEvents(movementEvents, eventsLocked ? 0f : offset);
        Note copy = new Note(
            type,
            lane,
            appearTime + offset,
            newJudgementTime,
            duration,
            height,
            opacityCopies,
            rotationCopies,
            movementCopies
        );
        copy.eventsLocked = this.eventsLocked;
        return copy;
    }

    private ArrayList<Events> copyEvents(ArrayList<Events> source, float offset) {
        ArrayList<Events> result = new ArrayList<>();
        for (Events event : source) {
            result.add(new Events(
                event.getTypeName(),
                event.getStartTime() + offset,
                event.getEndTime() + offset,
                 event.getInitialVal(),
                event.getFinalVal(),
                event.getExponent()
            ));
        }
        return result;
    }

    private void updateAppearTime(){
        double o = opacityEvents.isEmpty() ? judgementTime: opacityEvents.get(0).getStartTime();
        double m = movementEvents.isEmpty() ? judgementTime : movementEvents.get(0).getStartTime();
        double r = rotationEvents.isEmpty() ? judgementTime : rotationEvents.get(0).getStartTime();
        appearTime = (float) Math.min(Math.min(o, m), r);
    }

    public void addRotationEvent(Events event) {
        if(event.getType() == Events.TYPE.Rotation){
            rotationEvents.add(event);
            rotationEvents.sort(Comparator.comparingDouble(Events::getStartTime));
            updateAppearTime();
        }
    }

    public void addOpacityEvent(Events event) {
        if(event.getType() == Events.TYPE.Opacity){
            opacityEvents.add(event);
            opacityEvents.sort(Comparator.comparingDouble(Events::getStartTime));
            updateAppearTime();
        }
    }

    public void addMovementEvent(Events event) {
        if(event.getType() == Events.TYPE.Movement){
            movementEvents.add(event);
            movementEvents.sort(Comparator.comparingDouble(Events::getStartTime));
            updateAppearTime();
        }
    }

    private void sortEvents() {
        opacityEvents.sort(Comparator.comparingDouble(Events::getStartTime));
        rotationEvents.sort(Comparator.comparingDouble(Events::getStartTime));
        movementEvents.sort(Comparator.comparingDouble(Events::getStartTime));
        updateAppearTime();
    }

    public void updateForTime(float time, float bpm) {
        float fadeBeats = bpm / 600f; // 0.1s in beats

        if (type.equals("tap") && firstHitDone && time > judgementTime) {
            // Post-judgement fadeout: extrapolate position at the speed of the last movement event
            float posAtJudge = Events.calculateValue(movementEvents, judgementTime, 0);
            float speed = lastMovementSpeed(judgementTime);
            float elapsed = time - judgementTime; // in beats
            currentPos = posAtJudge + speed * elapsed;
            // Opacity fades 1→0 over fadeBeats
            currentOpacity = Math.max(0f, 1f - elapsed / fadeBeats);
        } else if (type.equals("hold") && time > judgementTime) {
            currentPos = Events.calculateValue(movementEvents, time, 0);
            currentOpacity = Events.calculateValue(opacityEvents, time, 1);
        } else {
            currentPos = Events.calculateValue(movementEvents, time, 2000);
            currentOpacity = Events.calculateValue(opacityEvents, time, 1);
        }
        currentAngle = Events.calculateValue(rotationEvents, time, 0);
        currentHeight = calculateHeight(time);
    }

    /** Returns the instantaneous speed (Δval/Δbeat) at the end of the last movement event. */
    private float lastMovementSpeed(float time) {
        if (movementEvents.isEmpty()) return 0f;
        // Find the active or most recent event
        Events last = movementEvents.get(movementEvents.size() - 1);
        for (Events e : movementEvents) {
            if (e.getStartTime() <= time && time <= e.getEndTime()) {
                last = e;
                break;
            }
        }
        float span = last.getEndTime() - last.getStartTime();
        if (span <= 0f) return 0f;
        // Speed at the end of the event (derivative at t=1 for power curve: exp*(finalVal-initialVal)/span)
        return last.getExponent() * (last.getFinalVal() - last.getInitialVal()) / span;
    }

    private float calculateHeight(float time) {
        if(type.equals("tap")){
            return 0;
        }
        if (duration <= 0 || time <= judgementTime) {
            return height;
        }
        float progress = (time - judgementTime) / duration;
        return Math.max(0f, height * (1.0f - progress));
    }

    public boolean hasAppeared(double time, float bpm) {
        float fadeBeats = bpm / 600f; // 0.1s in beats
        if (type.equals("tap")) {
            // Visible from appearTime until judgementTime+fadeBeats (post-hit fadeout),
            // but only after being hit (firstHitDone) or before being judged.
            if (firstHitDone) {
                return time >= appearTime && time <= judgementTime + fadeBeats;
            }
            return hitScore == -1 && time >= appearTime && time <= judgementTime + fadeBeats;
        }
        return time <= getEndTime() && time >= appearTime;
    }

    public float getEndTime() {
        return judgementTime + duration;
    }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public int getLane() { return lane; }
    public void setLane(int lane) { this.lane = lane; }

    @JsonProperty("appearance")
    public float getAppearTime() { return appearTime; }

    @JsonProperty("judgement")
    public float getJudgementTime() { return judgementTime; }
    public void setJudgementTime(float t) {
        if (!eventsLocked) {
            for(Events e: movementEvents){
                e.setEndTime((e.getEndTime() + t - judgementTime));
                e.setStartTime((e.getStartTime() + t - judgementTime));
            }
            for(Events e: rotationEvents){
                e.setEndTime((e.getEndTime() + t - judgementTime));
                e.setStartTime((e.getStartTime() + t - judgementTime));
            }
            for(Events e: opacityEvents){
                e.setEndTime((e.getEndTime() + t - judgementTime));
                e.setStartTime((e.getStartTime() + t - judgementTime));
            }
        }
        updateAppearTime();
        this.judgementTime = t;
    }

    public float getDuration() { return duration; }
    public void setDuration(float d) { this.duration = d; }

    public float getHeight() { return height; }
    public void setHeight(float h) { this.height = h;}

    @JsonIgnore
    public float getCurrentPos() {
        return currentPos;
    }

    @JsonIgnore
    public float getCurrentAngle() {
        return currentAngle;
    }

    @JsonIgnore
    public float getCurrentHeight() {
        return currentHeight;
    }

    @JsonProperty("DisappearEvents")
    public ArrayList<Events> getOpacityEvents() {
        return opacityEvents;
    }

    @JsonProperty("RotationEvents")
    public ArrayList<Events> getRotationEvents() {
        return rotationEvents;
    }

    @JsonProperty("MovementEvents")
    public ArrayList<Events> getMovementEvents() {
        return movementEvents;
    }

    public float getCurrentOpacity() {
        return currentOpacity;
    }

    public boolean hitJudge(float hitTime){
        if(firstHitDone) return false;
        if(Math.abs(judgementTime - hitTime) < 4 / 60f){
            hitScore = 1;
            firstHitDone = true;
        }
        else if(Math.abs(judgementTime - hitTime) < 8 / 60f){
            hitScore = 0.5;
            firstHitDone = true;
        }
        else if(Math.abs(judgementTime - hitTime) < 10 / 60f){
            hitScore = 0;
            firstHitDone = true;
        }
        return true;
    }

    public void liftJudge(float liftTime){
        if(type.equals("tap")){
            finalScore = hitScore;
        }
        if(type.equals("hold")){
            finalScore = hitScore * Math.min(Math.max(1 - Math.abs(getEndTime() - liftTime) / (duration), 0), 1);
        }
    }

    public boolean isEventsLocked() { return eventsLocked; }
    public void setEventsLocked(boolean locked) { this.eventsLocked = locked; }

    public double getHitScore() {
        return hitScore;
    }

    public double getFinalScore() {
        return finalScore;
    }

    public void reset(){
        hitScore = -1;
        finalScore = -1;
        firstHitDone = false;
    }
}
