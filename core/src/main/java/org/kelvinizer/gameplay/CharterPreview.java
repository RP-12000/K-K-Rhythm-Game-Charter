package org.kelvinizer.gameplay;

import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import org.kelvinizer.elements.Chart;
import org.kelvinizer.elements.Floating;
import org.kelvinizer.elements.LaneLayout;
import org.kelvinizer.elements.Line;
import org.kelvinizer.elements.Note;
import org.kelvinizer.elements.NoteType;
import org.kelvinizer.fx.HitFx;
import org.kelvinizer.library.Difficulty;
import org.kelvinizer.play.Judgement;
import org.kelvinizer.profile.Settings;
import org.kelvinizer.ui.Draw;
import org.kelvinizer.ui.Fonts;
import org.kelvinizer.ui.GlitchText;
import org.kelvinizer.ui.Ui;

import java.util.Arrays;

/**
 * 谱面编辑器的预览：用游戏自己的引擎（自动播放）和渲染器，在任意时刻画出谱面，和游戏里看到的一样；「实机」时再加上游戏的 HUD
 * 和打击特效，和游戏里的自动游玩一样（不画暂停键，没有打击音）。
 * The chart editor's preview: draws a chart at any song time with the game's own engine (autoplay)
 * and renderers, so it looks exactly like a perfect run in the game. It lives in this package
 * because the renderers are package-private. The field is fitted to a 16 : 10 screen, the game's
 * 1920 × 1200 reference frame (Kelvin 2026-10-19), so the preview shows that frame unstretched.
 * Live ({@link #setLive}, 实机, Kelvin 2026-10-27) it also shows what the game's autoplay run shows: the HUD
 * ({@link GameplayHud}: combo, AUTOPLAY, judgement word, score, progress bar, difficulty chip, title and time, without
 * the pause control) and the hit effects ({@link HitFx}, {@link FeedbackLayer}, the field's ripples), fed as
 * {@code GameplayScreen} feeds them, with the game's default settings and no hit sounds. The curtains (水帘) are
 * drawn last, over everything, by {@link #drawCurtains}, in both modes.
 */
public final class CharterPreview {
    /** The HUD's entrance (slide-in) has always ended. */
    private static final float SETTLED = 10f;
    /** A step of the song time longer than this (ms) is a jump (a click on the timeline), not playback. */
    private static final double JUMP_MS = 250.0;
    /** After a jump the HUD shows the new state as if this long had passed (s): no punch, pop or word is left. */
    private static final float SETTLE = 1f;
    /** Room kept around the pause control when the corner HUD is clipped around it (world units). */
    private static final float PAUSE_ROOM = 4f;

    private final FieldRenderer field;
    private final NoteRenderer notes;
    private final TrailRenderer trails;
    private final TideWash wash;
    private final VeilRenderer veils;
    /** The curtains (水帘): drawn last by {@link #drawCurtains}, blurring everything under them. */
    private final VeilRenderer curtains;
    private boolean curtained;
    private final Fonts fonts = new Fonts();
    private final HitFx fx;
    private final FeedbackLayer feedback;
    private final GameplayHud hud;
    /** Records the HUD's text so each font page is one draw call (as the game screen). */
    private final DeferredBatch text = new DeferredBatch();
    /** The game's defaults: what a new player's autoplay run shows. */
    private final Settings settings = new Settings();
    private final GameEngine engine = new GameEngine();
    private final GameEvent ev = new GameEvent();
    /** Fake clock for the engine: the preview sets the song time directly. */
    private long fakeNanos = 1_000_000_000L;
    private Chart chart;
    private double engineMs = Double.NaN;
    private float runTime;
    private int syncHint = Settings.SYNC_HINT_HIGHLIGHT;
    private boolean live;
    /** The difficulty on the live HUD's chip. */
    private Difficulty diff;
    /** The hit effect of each held lane STREAM, by note index (−1 = none). */
    private int[] holdFx = new int[0];
    private final float[] basePt = new float[2];

    public CharterPreview(Draw draw) {
        fit();
        field = new FieldRenderer(draw);
        notes = new NoteRenderer(draw);
        trails = new TrailRenderer(notes);
        wash = new TideWash(draw, field);
        veils = new VeilRenderer(draw);
        curtains = new VeilRenderer(draw, true);
        fx = new HitFx(draw);
        feedback = new FeedbackLayer(draw);
        hud = new GameplayHud(fonts, draw, new GlitchText(fonts, draw), feedback);
        engine.clock().setTimeSource(() -> fakeNanos);
        engine.clock().attach(null, 0f, false);
        // the run never finishes: 实机 plays the chart without the editor's sentinel and goes on past its last note
        engine.setFinishDelayMs(1e9f);
    }

    /** @return how notes that share their beat are marked, as the game's {@link Settings#syncHint}. */
    public int syncHint() {
        return syncHint;
    }

    /** Marks notes that share their beat like the game setting {@link Settings#syncHint} with value {@code mode}. */
    public void setSyncHint(int mode) {
        syncHint = mode;
        notes.setSyncHint(mode);
    }

    /**
     * 实机 on or off: the game's HUD and hit effects, for a run of {@code diff} (the HUD's chip). The run restarts at
     * the next {@link #seek}.
     */
    public void setLive(boolean on, Difficulty diff) {
        if (on == live && (!on || diff == this.diff)) return;
        live = on;
        this.diff = diff;
        engineMs = Double.NaN;
        if (!on) clearFx();
    }

    /** Uses {@code chart} from the next {@link #seek} on (the run restarts there). */
    public void setChart(Chart chart) {
        this.chart = chart;
        engineMs = Double.NaN;
    }

    public Chart chart() {
        return chart;
    }

    /** @return the engine (frame geometry: {@link GameEngine#layout()}, {@link GameEngine#lineY}). */
    public GameEngine engine() {
        return engine;
    }

    /**
     * How many world units one chart unit of a floating note's size is in this frame (FloatSpace.scale), for the
     * editor's ghost of a key not placed yet.
     */
    public static float floatScale(LaneLayout lay) {
        return FloatSpace.scale(lay);
    }

    /**
     * Where floating note {@code n} (悬浮键, CHART_FORMAT §11, game 7.185) is at {@code beat} in this frame's space, as
     * {@code FloatSpace} places it: world x and y of its centre and its world radius in {@code out[0..2]}.
     *
     * @return false when it does not float or has no place then
     */
    public boolean floatAt(Note n, float beat, float[] out) {
        Floating f = n.floating();
        if (f == null) return false;
        LaneLayout lay = engine.layout();
        out[0] = lay.x(f.xAt(beat));
        out[1] = Field.y(f.yAt(beat));
        out[2] = 0.5f * f.sizeAt(beat) * FloatSpace.scale(lay);
        return !Float.isNaN(out[0]) && !Float.isNaN(out[1]) && out[2] > 0f;
    }

    /**
     * Moves the preview to song time {@code ms} (judgement time). Going back restarts the
     * autoplay run silently and replays it up to {@code ms} in one step.
     *
     * @param dt      seconds since the last frame (animations run only while playing)
     * @param playing true while the music plays (hit effects, waves)
     */
    public void seek(double ms, float dt, boolean playing) {
        if (chart == null) return;
        boolean jump = Double.isNaN(engineMs) || ms < engineMs - 0.01 || engine.isFinished();
        if (jump) restart(ms);
        // a click on the timeline or a move while paused: the notes passed on the way were not hit just now
        else jump = ms - engineMs > JUMP_MS || !playing && Math.abs(ms - engineMs) > 0.5;
        double now = engine.clock().nowMs();
        if (ms > now) fakeNanos += (long) ((ms - now) * 1e6);
        engine.update();
        engineMs = ms;
        while (engine.pollEvent(ev)) {
            if (!playing || jump) continue;
            if (live) feedLive();
            else feed();
        }
        float d = playing ? dt : 0f;
        runTime += d;
        if (live) {
            if (jump) clearFx();
            moveHoldFx();
            double vb = engine.visualBeat();
            fx.setBeatLength((float) engine.chart().msBetween(vb, vb + 0.25) * 0.004f); // held ripples every 1/4 beat
            fx.update(d);
        }
        wash.update(d, engine, runTime);
        field.update(d, engine);
        // after a jump the HUD takes the new score and combo as SETTLE seconds ago: no count-up or punch is left
        if (live) hud.update(jump ? SETTLE : d, engine, jump ? runTime - SETTLE : runTime);
    }

    /**
     * Fits the field to the world of a 16 : 10 screen: 1920 × 1200 centred at (960, 540), so world y is
     * reference y − 60 and the renderers' screen edges (−60 … 1140) are the frame's.
     */
    private static void fit() {
        Ui.onResizeIfChanged(1920, 1200);
        Field.fit(Ui.worldW(), Ui.worldH());
    }

    private void restart(double ms) {
        fit();
        engine.load(chart, true);
        engine.setLeadInMs((float) Math.min(engine.leadInMs(), Math.floor(ms) - 1.0));
        engine.start();
        field.begin(chart);
        notes.setStyle(1f, true);
        notes.begin(chart);
        trails.begin(chart);
        wash.clear();
        if (!live) return;
        if (holdFx.length < chart.noteCount()) holdFx = new int[chart.noteCount()];
        clearFx();
        hud.begin(chart.title(), diff, chart.chartConstant(), true, settings);
    }

    private void clearFx() {
        fx.clear();
        feedback.clear();
        Arrays.fill(holdFx, -1);
    }

    private void feed() {
        if (ev.has(GameEvent.FLAG_FLOAT)) return; // floating notes draw their own effects
        Line line = ev.line;
        boolean universal = ev.has(GameEvent.FLAG_UNIVERSAL);
        if (ev.type == GameEvent.HIT) {
            if (universal) wash.hit(line, ev.judgement == Judgement.RESONANCE, runTime);
            else if (line != null) field.bead(line, ev.x, runTime);
        } else if (ev.type == GameEvent.HOLD_END && ev.has(GameEvent.FLAG_COMPLETED)
            && ev.has(GameEvent.FLAG_TIDE_STREAM)) {
            wash.complete(line, ev.judgement == Judgement.RESONANCE, runTime);
        }
    }

    /** Live: {@code GameplayScreen.consumeEvents} for an autoplay run at High quality, without the sounds. */
    private void feedLive() {
        if (ev.has(GameEvent.FLAG_FLOAT)) {
            floatLive();
            return;
        }
        Line line = ev.line;
        float ly = line != null ? engine.lineY(line) : ev.y;
        boolean sky = line == Line.UPPER;
        boolean universal = ev.has(GameEvent.FLAG_UNIVERSAL);
        switch (ev.type) {
            case GameEvent.HIT: {
                float x = universal ? ev.touchX : ev.x;
                if (!universal) fx.tap(x, ly, ev.judgement, 1f, sky); // TIDE: no point effect
                hitFeedback(line, ly, x, universal);
                if (universal) wash.hit(line, ev.judgement == Judgement.RESONANCE, runTime);
                else if (line != null) field.pulseColumn(line, ev.x, ev.width); // autoplay's contact
                hud.judgement(ev.judgement, ev.dtMs, ev.x, line, ly, runTime);
                break;
            }
            case GameEvent.HOLD_START: {
                boolean based = ev.note >= 0 && holdBase(ev.note);
                float bx = based ? basePt[0] : ev.x, by = based ? basePt[1] : ly;
                if (ev.note >= 0) {
                    if (holdFx[ev.note] >= 0) fx.holdEnd(holdFx[ev.note], false);
                    holdFx[ev.note] = fx.holdStart(bx, by, ev.judgement, 1f, sky);
                }
                if (!ev.has(GameEvent.FLAG_REGRAB)) {
                    fx.head(bx, by, ev.judgement, 1f, sky);
                    hitFeedback(line, ly, ev.x, false);
                    hud.judgement(ev.judgement, ev.dtMs, ev.x, line, ly, runTime);
                }
                if (line != null) field.pulseColumn(line, ev.x, ev.width);
                break;
            }
            case GameEvent.HOLD_END: {
                boolean completed = ev.has(GameEvent.FLAG_COMPLETED);
                boolean tide = ev.has(GameEvent.FLAG_TIDE_STREAM);
                int h = ev.note >= 0 ? holdFx[ev.note] : -1;
                if (h >= 0) {
                    if (completed && holdEndBase(ev.note)) fx.holdMove(h, basePt[0], basePt[1]);
                    fx.holdEnd(h, completed); // a completed STREAM ends with its release splash at the base
                    holdFx[ev.note] = -1;
                } else if (completed && !tide) {
                    boolean based = ev.note >= 0 && holdEndBase(ev.note);
                    fx.release(based ? basePt[0] : ev.x, based ? basePt[1] : ly, ev.judgement, 1f, sky);
                }
                if (completed) {
                    if (tide) wash.complete(line, ev.judgement == Judgement.RESONANCE, runTime);
                } else {
                    fx.lost(ev.x, sky ? ly - 120f : ly + 120f, 1f);
                    field.flash(line, ev.x, ev.width, false, runTime);
                    hud.judgement(Judgement.LOST, 0f, ev.x, line, ly, runTime);
                    if (tide && !engine.tideRunning(line)) wash.drain(line, runTime);
                }
                break;
            }
            case GameEvent.LOST:
                fx.lost(ev.x, sky ? ly - 120f : ly + 120f, 1f);
                if (universal) field.flashWindow(line, false, runTime);
                else field.flash(line, ev.x, ev.width, false, runTime);
                hud.judgement(Judgement.LOST, 0f, ev.x, line, ly, runTime);
                break;
            case GameEvent.MINE:
                feedback.mineShock(ev.touchX, ly, runTime);
                field.flash(line, ev.x, ev.width, true, runTime);
                hud.mineBreak();
                hud.mineWord(ev.x, line, ly, runTime);
                break;
            default:
                break;
        }
    }

    /**
     * Live: {@code GameplayScreen.floatEvent} without the sounds and the finger's contact ring: a floating note draws
     * its own hit and miss effects ({@code FloatNotes}), so only the words just above it ({@code ev.x}/{@code ev.y}:
     * its centre, {@code ev.width} its diameter; a touched mine's red LOST).
     */
    private void floatLive() {
        float foot = ev.y + 0.5f * ev.width + FLOAT_WORD_GAP;
        switch (ev.type) {
            case GameEvent.HIT:
            case GameEvent.HOLD_START:
                if (!ev.has(GameEvent.FLAG_REGRAB)) hud.judgement(ev.judgement, ev.dtMs, ev.x, null, foot, runTime);
                break;
            case GameEvent.HOLD_END:
                if (!ev.has(GameEvent.FLAG_COMPLETED)) hud.judgement(Judgement.LOST, 0f, ev.x, null, foot, runTime);
                break;
            case GameEvent.LOST:
                hud.judgement(Judgement.LOST, 0f, ev.x, null, foot, runTime);
                break;
            case GameEvent.MINE:                                    // the mine bursts red where it is (FloatNotes)
                hud.mineBreak();
                hud.mineWord(ev.x, null, foot, runTime);
                break;
            default:
                break;
        }
    }

    /** Gap between a floating note and the judgement word above it, as {@code GameplayScreen} draws it. */
    private static final float FLOAT_WORD_GAP = 14f;

    /** The hit ripple and EARLY / LATE chevron of a judged lane hit (a TIDE swells the tide wash instead). */
    private void hitFeedback(Line line, float ly, float x, boolean universal) {
        if (line == null || universal) return;
        field.ripple(line, x, 4f, runTime);
        if (settings.showsEarlyLate(ev.judgement, ev.dtMs)) feedback.chevron(ev.x, line, ly, ev.dtMs > 0f, runTime);
    }

    /** Moves the effects of every held lane STREAM to its base as drawn this frame. */
    private void moveHoldFx() {
        for (int k = 0; k < engine.activeHoldCount(); k++) {
            int i = engine.activeHold(k);
            if (i < holdFx.length && holdFx[i] >= 0 && holdBase(i)) fx.holdMove(holdFx[i], basePt[0], basePt[1]);
        }
    }

    /** Puts where the effects of STREAM {@code note} start into {@link #basePt}: its key's hit point as drawn now. */
    private boolean holdBase(int note) {
        Note n = engine.chart().note(note);
        return notes.effectPoint(engine, n, Math.max(engine.visualBeat(), n.judgement()), basePt);
    }

    /** Like {@link #holdBase}, for a completed STREAM's release splash: its base when it ends, never later. */
    private boolean holdEndBase(int note) {
        Note n = engine.chart().note(note);
        float beat = Math.min(Math.max(engine.visualBeat(), n.judgement()), n.endBeat());
        return notes.effectPoint(engine, n, beat, basePt);
    }

    /**
     * Draws the field, notes and veils in world coordinates (the 1920 × 1200 frame, y from −60 to 1140) with
     * {@code batch}, which must be begun with the projection already set (DESIGN §5.1 order); live, also the HUD
     * beneath the notes and the hit effects ({@link #drawCorners} draws the rest of the HUD).
     *
     * @param zoneK judgement zone hint strength (1 = the game's default)
     */
    public void draw(SpriteBatch batch, float zoneK) {
        if (chart == null) return;
        field.clearApproach();
        notes.setStyle(1f, true);
        notes.prepare(engine, field, true);
        trails.setQuality(HitFx.QUALITY_HIGH);
        wash.setStyle(false, HitFx.QUALITY_HIGH);
        trails.prepare(engine, wash.quadsBound());
        field.drawUnder(engine, zoneK, 1f);
        wash.draw(engine);
        // the veils cover the field and notes, beneath the hit effects; live, the HUD beneath the notes stays above the
        // most prominent veil: drawn outside it here, inside it again after the veils (GameplayScreen.drawVeils)
        boolean veiled = veils.prepare(engine.veils(), engine.visualBeat(), runTime, 1f);
        curtained = curtains.prepare(engine.curtains(), engine.visualBeat(), runTime, 1f);
        boolean clipped = live && veiled && veils.clipBegin(batch, false);
        if (live) {
            hud.drawUnder(batch, SETTLED, runTime, true);
            text.start(batch);
            hud.setCentreCover(0f);
            hud.drawUnderText(text, engine, runTime, true);
            feedback.drawText(text, fonts, runTime);
            if (clipped) {
                text.replayKeep();
                veils.clipEnd(batch);
            } else {
                text.replay();
            }
        }
        field.drawLines(engine, runTime, 1f);
        notes.drawBands(engine, runTime);
        trails.draw(engine);
        notes.draw(engine, runTime);
        if (veiled) {
            LaneLayout lay = engine.layout();
            veils.draw(batch, engine.lineY(Line.LOWER), field.strokeHalf(Line.LOWER, runTime),
                engine.lineY(Line.UPPER), field.strokeHalf(Line.UPPER, runTime), lay.windowLeft(), lay.windowRight());
            if (clipped && veils.clipBegin(batch, true)) {
                hud.drawUnder(batch, SETTLED, runTime, true);
                text.replay();
                veils.clipEnd(batch);
            }
        }
        int src = batch.getBlendSrcFunc();
        int dst = batch.getBlendDstFunc();
        int srcA = batch.getBlendSrcFuncAlpha();
        int dstA = batch.getBlendDstFuncAlpha();
        batch.setBlendFunction(GL20.GL_SRC_ALPHA, GL20.GL_ONE);
        field.drawAdditive(engine, runTime, live ? engine.scores().combo() : 0, live ? beatPulse() : 0f);
        notes.drawGlows();
        if (live) {
            feedback.drawAdditive(runTime);
            fx.draw(batch);
        }
        batch.setBlendFunctionSeparate(src, dst, srcA, dstA);
    }

    /** Clips drawing to a rectangle of the preview's world until {@link #pop} (the caller maps it to the screen). */
    public interface Clip {
        void push(float x0, float y0, float x1, float y1);

        void pop();
    }

    /**
     * Live only: the game's corner HUD (progress bar, difficulty chip and title, score, time), after {@link #draw}. The
     * pause control is left out: the HUD's shapes are drawn twice, clipped to under the control and to over it.
     */
    public void drawCorners(SpriteBatch batch, Clip clip) {
        if (chart == null || !live) return;
        float under = hud.bottom(GameplayHud.EL_PAUSE) - PAUSE_ROOM, over = hud.top(GameplayHud.EL_PAUSE) + PAUSE_ROOM;
        clip.push(Ui.left(), Ui.bottom(), Ui.right(), under);
        hud.drawCorners(batch, engine, SETTLED, runTime, false, 0f);
        clip.pop();
        clip.push(Ui.left(), over, Ui.right(), Ui.top());
        hud.drawCorners(batch, engine, SETTLED, runTime, false, 0f);
        clip.pop();
        text.start(batch);
        hud.drawCornerText(text, SETTLED);
        text.replay();
    }

    /**
     * The curtains over everything {@link #draw} and {@link #drawCorners} drew (GameplayScreen draws them last too,
     * beneath only the pause menu); call after both with the same projection.
     */
    public void drawCurtains(SpriteBatch batch) {
        if (chart != null && curtained) curtains.draw(batch);
    }

    /** Line-glow beat pulse 1 → 0 over 180 ms after each beat (as the game screen's). */
    private float beatPulse() {
        Chart c = engine.chart();
        float beat = engine.visualBeat();
        if (beat < 0f || c == null) return 0f;
        float ms = (float) c.msBetween(Math.floor(beat), beat);
        return ms < 180f ? 1f - ms / 180f : 0f;
    }

    /**
     * Frees the HUD's fonts, the hit effects and the curtains' buffers (the rest uses the shared {@link Draw}).
     */
    public void dispose() {
        fx.dispose();
        curtains.dispose();
        fonts.dispose();
    }

    /** @return head width of a DROP in a lane this wide (the game's note size 1). */
    public float headWidth(float laneWidth) {
        return notes.headWidth(laneWidth);
    }

    /**
     * @return how much nearer its line than its chart distance the game draws every note: the VISUAL HIT POINT at
     *         its default ({@link NoteRenderer#hitShift}: half a size-1 key, so a key's midline is at the distance)
     */
    public float hitShift() {
        return notes.hitShift();
    }

    /** @return DROP head height (the game's note size 1). */
    public float noteHeight() {
        return notes.noteHeight();
    }

    /** @return MINE body (and LANE MINE base) height (the game's note size 1). */
    public float mineHeight() {
        return notes.mineHeight();
    }

    /** @return TIDE bar thickness (the game's note size 1). */
    public float tideHeight() {
        return notes.tideHeight();
    }

    /** @return the reference height {@link NoteShapes} measures a key against: a mine's body or a DROP's head. */
    public float refHeight(NoteType type) {
        return type == NoteType.MINE ? NoteRenderer.MINE_H : NoteRenderer.NOTE_H;
    }

    /**
     * @return how much farther from its line than {@code pos} (a drawn distance, after {@link #hitShift}) an
     *         unturned lane note's or mine's near edge is drawn
     *         ({@link NoteShapes#centreShiftY}: its body is centred on the 1080-measured reference body, so at note
     *         size 1 a head is 2.22 farther and a mine 1.78, easing to 0 at the line); 0 for a TIDE or TIDE MINE
     */
    public float bodyShift(NoteType type, float pos) {
        if (!type.inLane()) return 0f;
        boolean mine = type == NoteType.MINE;
        return NoteShapes.centreShiftY(Field.dist(pos), mine ? NoteRenderer.MINE_H : NoteRenderer.NOTE_H,
            mine ? mineHeight() : noteHeight(), 0f);
    }
}
