package org.kelvinizer.gameplay;

import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Matrix4;
import org.kelvinizer.elements.Channels;
import org.kelvinizer.elements.Chart;
import org.kelvinizer.elements.Lane;
import org.kelvinizer.elements.LaneLayout;
import org.kelvinizer.elements.Line;
import org.kelvinizer.elements.Note;
import org.kelvinizer.fx.HitFx;
import org.kelvinizer.library.Difficulty;
import org.kelvinizer.play.Judgement;
import org.kelvinizer.profile.Settings;
import org.kelvinizer.ui.Draw;
import org.kelvinizer.ui.Fonts;
import org.kelvinizer.ui.GlitchText;
import org.kelvinizer.ui.SpaceBatch;
import org.kelvinizer.ui.Ui;

import java.util.Arrays;

/**
 * 谱面编辑器的预览：用游戏自己的引擎（自动播放）和渲染器，在任意时刻画出谱面，和游戏里看到的一样（编辑时也是游戏的 3D 视角）；
 * 「实机」时再加上游戏的 HUD 和打击特效，和游戏里的自动游玩一样（不画暂停键，没有打击音）。
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
    /** The game's 3D view (Kelvin 2026-11-07) over {@link #frame}: 实机, and editing unless {@link #edit3D} is off. */
    private final View3D view = new View3D();
    /** A chart format v7 chart's veils and curtains, worked out through the view each frame (game 7.222). */
    private final Volumes7 volumes = new Volumes7();
    /** The preview's world as the game's flat camera sees it: 1920 × 1200 centred at (960, 540). */
    private final OrthographicCamera frame = new OrthographicCamera(1920f, 1200f);
    /** While the view is on: the preview's own (flat) projection, the frame's screen onto it, the view's own matrix. */
    private final Matrix4 shown = new Matrix4(), toShown = new Matrix4(), own = new Matrix4();
    /** The beat the last {@link #begin3D} turned the view for. */
    private float viewBeat;
    /** While the view is on: the view carried onto the preview (world → the preview's clip space). */
    private final Matrix4 mapped = new Matrix4();
    /** The editor's own zoom of the 3D view ({@link #zoom}) and its matrix. */
    private float zoom = 1f;
    private final Matrix4 zoomM = new Matrix4();
    /** True while the last {@link #draw} went through the view: the editor draws its marks through {@link #mapped}. */
    private boolean drawn3D;
    /**
     * The edit view draws the game's 3D view as 实机 does (Kelvin 2026-11-07 19:48「我一点进去就需要是这样的模式，实机只是加了
     * combo，autoplay，打击特效等元素」); off = the flat front view (the self-test's scripted clicks are laid out on it).
     */
    public static boolean edit3D = true;
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
    /** Where {@link #sinkFx} puts a hit effect. */
    private final float[] fxPt = new float[2];

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
        Line line = ev.line;
        boolean universal = ev.has(GameEvent.FLAG_UNIVERSAL);
        if (ev.type == GameEvent.HIT) {
            if (universal) washHit(line, ev.judgement == Judgement.RESONANCE);
            else if (line != null) field.bead(line, ev.x, runTime);
        } else if (ev.type == GameEvent.HOLD_END && ev.has(GameEvent.FLAG_COMPLETED)
            && ev.has(GameEvent.FLAG_TIDE_STREAM)) {
            wash.complete(engine, ev.note, line, ev.judgement == Judgement.RESONANCE, runTime);
        }
    }

    /** A TIDE's hit: along its sea when the note lands on one, else along its line (GameplayScreen.consumeEvents). */
    private void washHit(Line line, boolean resonance) {
        Note on = ev.note >= 0 ? chart.note(ev.note) : null;
        if (on != null && on.sea() != null) wash.hitSea(on.sea(), line, resonance, runTime);
        else wash.hit(engine, ev.note, line, resonance, runTime); // on the lanes it spans (game 7.240)
    }

    /** Live: {@code GameplayScreen.consumeEvents} for an autoplay run at High quality, without the sounds. */
    private void feedLive() {
        Line line = ev.line;
        // where the note lands: its line, or its sea
        float ly = line == null ? ev.y : ev.note >= 0 ? engine.landY(chart.note(ev.note)) : engine.lineY(line);
        boolean sky = line == Line.UPPER;
        boolean universal = ev.has(GameEvent.FLAG_UNIVERSAL);
        switch (ev.type) {
            case GameEvent.HIT: {
                float x = universal ? ev.touchX : ev.x;
                if (!universal) { // TIDE: none
                    // sinkFx first: it fills fxPt, which the call below reads
                    float k = sinkFx(ev.note, x, ly) * keyFx(ev.note);
                    fx.tap(fxPt[0], fxPt[1], ev.judgement, k, sky);
                }
                hitFeedback(line, ly, x, universal);
                if (universal) washHit(line, ev.judgement == Judgement.RESONANCE);
                else if (line != null) field.pulseColumn(line, ev.lane); // autoplay's contact
                hud.judgement(ev.judgement, ev.dtMs, ev.x, line, ly, runTime);
                break;
            }
            case GameEvent.HOLD_START: {
                boolean based = ev.note >= 0 && holdBase(ev.note);
                float k = sinkFx(ev.note, ev.x, ly) * keyFx(ev.note);
                float bx = based ? basePt[0] : fxPt[0], by = based ? basePt[1] : fxPt[1];
                if (ev.note >= 0) {
                    if (holdFx[ev.note] >= 0) fx.holdEnd(holdFx[ev.note], false);
                    holdFx[ev.note] = fx.holdStart(bx, by, ev.judgement, k, sky);
                }
                if (!ev.has(GameEvent.FLAG_REGRAB)) {
                    fx.head(bx, by, ev.judgement, k, sky);
                    hitFeedback(line, ly, ev.x, false);
                    hud.judgement(ev.judgement, ev.dtMs, ev.x, line, ly, runTime);
                }
                if (line != null) field.pulseColumn(line, ev.lane);
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
                    float k = sinkFx(ev.note, ev.x, ly) * keyFx(ev.note);
                    fx.release(based ? basePt[0] : fxPt[0], based ? basePt[1] : fxPt[1], ev.judgement, k, sky);
                }
                if (completed) {
                    if (tide) wash.complete(engine, ev.note, line, ev.judgement == Judgement.RESONANCE, runTime);
                } else {
                    fx.lost(ev.x, sky ? ly - 120f : ly + 120f, 1f);
                    if (!spanFlash(ev.note, line)) field.flash(line, ev.lane, false, runTime);
                    hud.judgement(Judgement.LOST, 0f, ev.x, line, ly, runTime);
                    if (tide) wash.drain(engine, ev.note, line, runTime); // unless another one keeps it up
                }
                break;
            }
            case GameEvent.LOST:
                fx.lost(ev.x, sky ? ly - 120f : ly + 120f, 1f);
                if (universal && !spanFlash(ev.note, line)) field.flashWindow(line, false, runTime);
                else if (ev.lane >= 0) field.flash(line, ev.lane, false, runTime); // a DRAG off the lanes: none
                hud.judgement(Judgement.LOST, 0f, ev.x, line, ly, runTime);
                break;
            case GameEvent.MINE:
                feedback.mineShock(ev.touchX, ly, runTime);
                field.flash(line, ev.lane, true, runTime);
                hud.mineBreak();
                hud.mineWord(ev.x, line, ly, runTime);
                break;
            default:
                break;
        }
    }

    /**
     * A missed TIDE or TIDE STREAM over separate lanes flashes each column it spans instead of the window, as
     * {@code GameplayScreen.spanFlash} (game 7.240, Kelvin 2026-11-11 05:30 「只在那些跨过的轨道」).
     *
     * @return false when it is not one (no note, one on a sea, or the lanes are not separate)
     */
    private boolean spanFlash(int note, Line line) {
        if (note < 0 || line == null || !engine.layout().separate()) return false;
        Note n = chart.note(note);
        if (n.type().inLane() || n.sea() != null) return false;
        int m = wash.columns(engine, note);
        for (int k = 0; k < m; k++) field.flash(line, wash.column(k), false, runTime);
        return true;
    }

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

    /**
     * How much bigger key {@code note}'s hit effects show than a default key's, as GameplayScreen.keyFx (game 7.231,
     * Kelvin 2026-11-10 04:35「把打击特效的大小和按键大小绑定」): its width over {@link Lane#DEFAULT_WIDTH}, 1 to 4.
     */
    private float keyFx(int note) {
        if (note < 0) return 1f;
        LaneLayout lay = engine.layout();
        return MathUtils.clamp(lay.keyWidth(chart.note(note)) / (Lane.DEFAULT_WIDTH * lay.unitX()), 1f, 4f);
    }

    /**
     * Puts into {@link #fxPt} where the hit effect of note {@code note} at (x, y) shows, as GameplayScreen.sinkFx does
     * (game 7.209 – 7.217): at its column's middle and its own depth, drawn on the effects' plane towards the eye,
     * through its turned lane or sea when it is on one.
     *
     * @return the effect's size factor: 1 at the columns' depth, without a note or while the view is off
     */
    private float sinkFx(int note, float x, float y) {
        fxPt[0] = x;
        fxPt[1] = y;
        if (note < 0 || !drawn3D || !view.active()) return 1f;
        Note n = chart.note(note);
        LaneLayout lay = engine.layout();
        // at its column's middle, its origin (game 7.217: a base shorter than 240 brings its front in)
        float own = Channels.at(n.space(), Channels.SOLID, Channels.Z, engine.visualBeat())
            - 0.5f * (LaneLayout.DEFAULT_LENGTH - lay.keyLength(n));
        int f = view.keyFrame(lay, n);
        if (f >= 0) return view.frameFx(f, x, y, own, fxPt);     // on its turned lane or sea (game 7.216)
        float z = lay.keyMoveZ(n) + own;
        if (z == 0f) return 1f;
        float t = view.sinkMiddle(z);
        fxPt[0] = view.eyeX() + (x - view.eyeX()) * t;
        fxPt[1] = view.eyeY() + (y - view.eyeY()) * t;
        return t;
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
        // the game's 3D view (finite lanes always, else off the default) the game's way, editing too: the field and the
        // notes through it, 实机's HUD flat
        boolean v3 = (live || edit3D) && begin3D(batch);
        drawn3D = v3;
        field.setSpace(v3 ? view : null); // moved columns' depth, as GameplayScreen (game 7.210)
        field.drawUnder(engine, zoneK, 1f);
        if (!v3) field.drawSeas(engine, NoteShapes.songSeconds(engine)); // in 3D drawn behind, with the columns
        wash.draw(engine);
        // the veils cover the field and notes, beneath the hit effects; live, the HUD beneath the notes stays above the
        // most prominent veil: drawn outside it here, inside it again after the veils (GameplayScreen.drawVeils)
        veils.setSpace(v3 ? view : null); // their depth through the view, as GameplayScreen (game 7.209)
        // chart format v7: boxes and ellipsoids worked out through the view's own projection (not the one carried onto
        // the preview), then drawn on the frame's screen (GameplayScreen, game 7.222)
        if (v3) view.combined().set(own);
        volumes.update(chart, engine.visualBeat(), v3 ? view : null);
        if (v3) view.combined().set(mapped);
        boolean seven = !chart.volumes7().isEmpty();
        boolean veiled = seven ? veils.prepare7(volumes, runTime, 1f)
            : veils.prepare(engine.veils(), engine.visualBeat(), runTime, 1f);
        curtained = seven ? curtains.prepare7(volumes, runTime, 1f)
            : curtains.prepare(engine.curtains(), engine.visualBeat(), runTime, 1f);
        veils.setView(v3 && !seven ? view.combined() : null); // in 3D old veils lie on the field, the HUD stays flat
        if (v3) flat(batch);
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
        if (v3) deep(batch);
        field.drawLines(engine, runTime, 1f);
        notes.drawBands(engine, runTime);
        trails.draw(engine);
        notes.draw(engine, runTime);
        if (veiled && seven) { // on the frame's screen, then their edges in 3D (GameplayScreen.drawVeils)
            if (v3) flat(batch);
            veils.draw(batch);
            if (v3) {
                batch.end();
                view.drawVolumes(volumes, 1f);
                batch.begin();
                batch.setProjectionMatrix(view.combined());
            }
        } else if (veiled) {
            if (v3) space(batch, false); // the veils' shaders as before, through the view's projection (game 7.226)
            LaneLayout lay = engine.layout();
            veils.draw(batch, engine.lineY(Line.LOWER), field.strokeHalf(Line.LOWER, runTime),
                engine.lineY(Line.UPPER), field.strokeHalf(Line.UPPER, runTime), lay.windowLeft(), lay.windowRight());
        }
        if (veiled) {
            if (clipped) {
                if (v3) batch.setProjectionMatrix(shown); // the HUD again inside, flat (GameplayScreen.drawVeils)
                if (veils.clipBegin(batch, true)) {
                    hud.drawUnder(batch, SETTLED, runTime, true);
                    text.replay();
                    veils.clipEnd(batch);
                }
                if (v3) batch.setProjectionMatrix(view.combined());
            }
        }
        int src = batch.getBlendSrcFunc();
        int dst = batch.getBlendDstFunc();
        int srcA = batch.getBlendSrcFuncAlpha();
        int dstA = batch.getBlendDstFuncAlpha();
        batch.setBlendFunction(GL20.GL_SRC_ALPHA, GL20.GL_ONE);
        if (v3) deep(batch);
        field.drawAdditive(engine, runTime, live ? engine.scores().combo() : 0, live ? beatPulse() : 0f);
        notes.drawGlows();
        if (live) {
            if (v3) flat(batch);
            feedback.drawAdditive(runTime);
            // in 3D the hit effects are spheres round the keys, placed by the view's own projection and drawn flat,
            // facing the screen (GameplayScreen): the view's matrix back to its own (begin3D carried it over)
            if (v3) view.combined().set(own);
            fx.setView(v3 ? view : null);
            fx.draw(batch);
        }
        batch.setBlendFunctionSeparate(src, dst, srcA, dstA);
        if (v3) flat(batch);
    }

    /**
     * Into the 3D field: the view's projection and the batch's space mode, where each lane's and key's own frame costs
     * no draw call (GameplayScreen.deep, game 7.226; Kelvin 2026-11-10 01:19「我的手机根本带不动这么大的3D建模」).
     */
    private void deep(SpriteBatch batch) {
        batch.setProjectionMatrix(view.combined());
        space(batch, true);
    }

    /** Out of the 3D field onto the preview: what space mode holds drawn, then the preview's flat projection. */
    private void flat(SpriteBatch batch) {
        space(batch, false);
        batch.setProjectionMatrix(shown);
    }

    /** The game's batch ({@link SpaceBatch}) into or out of its space mode; any other batch draws as before. */
    private static void space(SpriteBatch batch, boolean on) {
        if (!(batch instanceof SpaceBatch)) return;
        if (on) ((SpaceBatch) batch).beginSpace();
        else ((SpaceBatch) batch).endSpace();
    }

    /** @return whether the last {@link #draw} drew the field through the game's 3D view */
    public boolean drawn3D() {
        return drawn3D;
    }

    /**
     * @return whether key n at raw distance {@code p} from its line is parked out of sight as the game draws it now
     *         ({@link NoteRenderer#parked}): on its turned lane or sea in the 3D view only at the parked value itself,
     *         so a key far down a lane turned towards the player shows (Kelvin 2026-11-09 16:06)
     */
    public boolean parked(Note n, float p) {
        return NoteRenderer.parked(p, drawn3D && view.active() && view.keyFrame(engine.layout(), n) >= 0);
    }

    /**
     * Turns the 3D view by (yaw, pitch) degrees on top of the chart's own view while editing (Kelvin 2026-11-08 01:00
     * 「编辑的时候要能够在谱面上进行三维perspective旋转」; 0, 0 = 归位, the game's view). The game never turns it.
     */
    public void orbit(float yaw, float pitch) {
        view.orbit(yaw, pitch);
    }

    /**
     * Zooms the 3D view by {@code k} round the frame's centre while editing (Kelvin 2026-11-08 04:02「ctr+alt+滚轮缩放」;
     * 1 = the chart's own), as a lens {@code k} times narrower would.
     */
    public void zoom(float k) {
        zoom = k > 0f && k < Float.POSITIVE_INFINITY ? k : 1f;
    }

    /**
     * Moves the 3D view and the point it looks at by (x, y, z) (reference units along the chart's axes) while editing
     * (Kelvin 2026-11-10 20:26 「ctrl+alt+箭头可以前后左右移动camera位置」; 0, 0, 0 = 归位). The game never moves it.
     */
    public void pan(float x, float y, float z) {
        view.pan(x, y, z);
    }

    /**
     * Moves point {@code xy} of the flat picture (world units) to where a thing {@code z} deep shows on it while the
     * last {@link #draw} went through the 3D view (game 7.209, {@link View3D#sink}), or back from there with
     * {@code back}; unchanged while flat or for z 0.
     */
    public void sink(float z, boolean back, float[] xy) {
        float k = drawn3D ? view.sink(z) : 1f;
        if (k == 1f || !(k > 0f)) return;
        if (back) k = 1f / k;
        float ex = view.eyeX(), ey = view.eyeY();
        xy[0] = ex + (xy[0] - ex) * k;
        xy[1] = ey + (xy[1] - ey) * k;
    }

    /** @return the last {@link #draw}'s 3D view carried onto the preview: world → the preview's clip space */
    public Matrix4 mapped() {
        return mapped;
    }

    /**
     * Turns the game's 3D view ({@link View3D}) over the preview's frame. When it is on, draws what lies behind the
     * flat picture (columns, mother planes, seas, key boxes) and leaves {@code batch} drawing through the view, carried
     * onto where the preview shows the frame; {@link #shown} keeps the preview's own projection.
     *
     * @return true while the view is on
     */
    private boolean begin3D(SpriteBatch batch) {
        frame.position.set(Ui.CX, Ui.CY, 0f);
        frame.update();
        viewBeat = engine.visualBeat();
        if (!view.update(chart, viewBeat, frame)) return false;
        if (zoom != 1f) view.combined().mulLeft(zoomM.setToScaling(zoom, zoom, 1f)); // as a narrower lens
        shown.set(batch.getProjectionMatrix());
        own.set(view.combined());
        // the view projects the frame onto a whole screen (its own renderers read the same matrix): carry that onto
        // the preview's rectangle
        view.combined().mulLeft(toShown.set(frame.combined).inv().mulLeft(shown));
        mapped.set(view.combined());
        batch.end();
        view.drawBack(engine, 1f);
        view.drawSeas(engine, 1f, NoteShapes.songSeconds(engine));
        notes.collectBoxes(engine, runTime, view);
        view.drawBoxes(engine.layout(), 1f, NoteShapes.songSeconds(engine));
        batch.setProjectionMatrix(view.combined());
        batch.begin();
        space(batch, true); // block A in space mode (GameplayScreen after drawBoxes, game 7.226)
        return true;
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

    /** Frees the HUD's fonts, the hit effects and the curtains' buffers (the rest uses the shared {@link Draw}). */
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

}
