package org.kelvinizer.charter;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.PixmapIO;
import com.badlogic.gdx.utils.BufferUtils;
import com.badlogic.gdx.utils.ScreenUtils;
import org.kelvinizer.elements.Chart;
import org.kelvinizer.elements.ChartMapper;
import org.kelvinizer.elements.LaneLayout;
import org.kelvinizer.elements.Line;
import org.kelvinizer.elements.Note;
import org.kelvinizer.elements.NoteType;
import org.kelvinizer.gameplay.Field;
import org.kelvinizer.gameplay.NoteShapes;

/**
 * 自测脚本（-Dkk.autotest=文件夹）：按顺序点击界面、截图，并检查保存后的谱面能被游戏原样读回。
 * Self-test run with {@code -Dkk.autotest=<folder>} (window exactly 1920 × 1080): drives the
 * screens with scripted mouse and key input, writes one screenshot per step and a log, checks
 * that a saved chart reads back through the game's loader unchanged, then exits. With {@code -Dkk.tablet=true}
 * (window 2560 × 1600) it runs a shorter script through the tablet layout instead ({@link #tabletBefore}).
 */
final class AutoTest {
    /** How far the audio row is dragged: about 1.14 beats at the default 16-beat view, whatever the timeline width. */
    private static final int AUDIO_DRAG = Math.round(EditorScreen.TW / 14f);
    private final CharterApp app;
    private final String dir;
    private final StringBuilder log = new StringBuilder();
    private int frame, notesBefore, lanesBefore;
    private float barScroll;
    private int flowUid = -1;
    /** Each chart file's 迭代次数 before the run saves anything (the fixture's files may already count). */
    private final java.util.HashMap<String, Integer> startIterations = new java.util.HashMap<>();

    AutoTest(CharterApp app, String dir) {
        this.app = app;
        this.dir = dir;
    }

    private EditorScreen ed() {
        return app.view() instanceof EditorScreen ? (EditorScreen) app.view() : null;
    }

    private void click(int x, int y) {
        app.im.touchDown(x, y, 0, Input.Buttons.LEFT);
        pending = new int[]{x, y};
    }

    private int[] pending;

    /** Before the frame: injects input. */
    void before() {
        frame++;
        if (frame == 1) {
            for (Library.Song s : app.lib.songs) {
                for (Library.Diff d : s.diffs) {
                    if (d.exists()) startIterations.put(d.file.path(), Doc.iterationsIn(d.file));
                }
            }
        }
        if (pending != null) {
            app.im.touchUp(pending[0], pending[1], 0, Input.Buttons.LEFT);
            pending = null;
            return;
        }
        if (Gfx.TABLET) {
            tabletBefore();
            return;
        }
        EditorScreen e = ed();
        JacketCrop c = crop();
        switch (frame) {
            case 6: cropOpen(); break;                          // a 1600 × 1000 jacket opens the framing dialog
            // 16:9 corner
            case 9: if (c != null) press(c.toScreenX(c.wx + c.ww), c.toScreenY(c.wy + c.ww * 9f / 16f)); break;
            case 10: app.im.touchDragged(at[0] - 160, at[1] - 90, 0); break;
            case 11: app.im.touchUp(at[0] - 160, at[1] - 90, 0, Input.Buttons.LEFT); break;
            case 12:
                log("16:9 after resize: " + wide() + " (expect 0,50 w about 1396)");
                if (c != null) press(c.toScreenX(c.wx + c.ww * 0.5f), c.toScreenY(c.wy + c.ww * 9f / 32f)); // drag it
                break;
            case 13: app.im.touchDragged(at[0] + 100, at[1] + 40, 0); break;
            case 14: app.im.touchUp(at[0] + 100, at[1] + 40, 0, Input.Buttons.LEFT); break;
            case 15: log("16:9 after move: " + wide() + " (expect 128,101 w about 1396)"); break;
            case 16: app.im.keyDown(Input.Keys.TAB); break;     // the circle
            case 17: if (c != null) press(c.screenX(), c.screenY()); break; // drag the circle right
            case 18: app.im.touchDragged(at[0] + 180, at[1] - 40, 0); break;
            case 19: app.im.touchUp(at[0] + 180, at[1] - 40, 0, Input.Buttons.LEFT); break;
            case 20: log("circle after move: mode " + (c != null ? c.mode : -1) + ", " + circle()
                + " (expect 1, centre 1030,500 r 500)");
                // edge handle
                if (c != null) press(c.screenX() + c.screenR() * 0.7071f, c.screenY() + c.screenR() * 0.7071f);
                break;
            case 21: app.im.touchDragged(at[0] - 40, at[1] - 40, 0); break;
            case 22: app.im.touchUp(at[0] - 40, at[1] - 40, 0, Input.Buttons.LEFT); break;
            case 23: log("circle after resize: " + circle() + " (expect r about 428)"); break;
            case 24: app.im.keyDown(Input.Keys.ENTER); break;
            case 26: cropSaved(); break;
            case 28: cropReopen(); break;
            case 29: app.im.keyDown(Input.Keys.ESCAPE); break;
            case 30: tutorialTest(); break;                     // 新手教程 (Kelvin 10-17)
            case 41: click(500, 217); break;                    // second song row
            case 52: click(1323, 976); break;                   // 编辑 <DIFF>
            case 80:
                if (e != null) e.seekBeat(40f);
                break;
            case 82:
                if (e != null) lanesAfterOpen(e);
                break;
            case 90: click((int) EditorScreen.TR - 36, 1066); break; // 统计 at the right end of the status bar
            case 93:
                if (e != null) {
                    log("stats open " + e.statsOpen + " (expect true): " + e.stats.summary() + "; editor has "
                        + e.s.doc.notes.size() + " notes, game counts "
                        + (e.s.chart != null ? e.s.chart.totalNotes() : -1) + " scored");
                    app.im.keyDown(Input.Keys.ESCAPE);
                }
                break;
            case 95: if (e != null) log("stats after Esc: open " + e.statsOpen + " (expect false), selection kept "
                + e.s.sel.size());
                break;
            case 96:
                if (e != null) tidySetup(e);
                break;
            case 98: app.im.keyDown(Input.Keys.ENTER); break;    // 删掉 2 条
            case 99:
                if (e != null) tidyCheck(e);
                break;
            case 101:
                if (e != null) selectNear(e);
                break;
            case 102:
                if (e != null && e.s.single() != null) segments(e, e.s.single());
                break;
            case 103:
                if (e != null && e.s.single() != null) segmentsBuilt(e, e.s.single());
                if (e != null) java.util.Arrays.fill(e.insp.scroll, 1e6f); // bottom of the 音符 tab
                break;
            case 105:
                if (e != null) java.util.Arrays.fill(e.insp.scroll, 0f);
                click(1700, 445);                                    // open 移动 Movement
                break;
            case 107:
                if (e != null && e.s.single() != null) {
                    app.im.keyDown(Input.Keys.D);
                }
                break;
            case 108:
                if (e != null && e.s.single() != null) {
                    Doc.DNote n = e.s.single();
                    log("after D: " + n.move.size() + " movement event(s), first " + n.move.get(0).start + " -> "
                        + n.move.get(0).end + " (judgement " + n.judgement + "), relative " + n.move.get(0).rel);
                }
                break;
            case 112: click((int) (EditorScreen.TR + (Gfx.W - EditorScreen.TR) * 5f / 6f), 79); break; // 场地
            case 114:
                if (e != null && !e.s.doc.lanes.isEmpty()) e.s.laneSel = e.s.doc.lanes.get(2).id;
                break;
            case 116:
                if (e != null) {
                    notesBefore = e.s.doc.notes.size();
                    e.insp.setLaneCount(e.s, e.s.doc, 8);           // lanes ±5 have notes: asks first
                }
                break;
            case 119:
                app.im.keyDown(Input.Keys.ENTER);                    // default button = 取消
                break;
            case 121:
                if (e != null) {
                    log("after cancel: laneCount " + e.s.doc.laneCount + ", notes "
                        + (e.s.doc.notes.size() - notesBefore));
                    e.insp.setLaneCount(e.s, e.s.doc, e.s.doc.lanes.size());
                    log("finite: laneCount " + e.s.doc.laneCount + ", lanes " + e.s.doc.lanes.size());
                }
                break;
            case 124:
                if (e != null) {
                    e.undo();
                    log("undo: laneCount " + e.s.doc.laneCount);
                    e.redo();
                    log("redo: laneCount " + e.s.doc.laneCount);
                }
                break;
            case 122: click((int) EditorScreen.TR + 62, 79); break; // 谱面
            case 125:
                if (e != null) {
                    motionBeforeJudgement(e);
                    laneMine(e);
                    relAndAbs(e);
                }
                break;
            case 126:
                if (e != null) {
                    e.insp.tab = Inspector.CHART;
                    log("before audio drag: beat " + e.beat);
                }
                app.im.touchDown(900, 898, 0, Input.Buttons.LEFT);   // audio row
                break;
            case 127: app.im.touchDragged(900 - AUDIO_DRAG, 898, 0); break;
            case 128: app.im.touchUp(900 - AUDIO_DRAG, 898, 0, Input.Buttons.LEFT); break;
            case 129:
                if (e != null) log("after dragging the audio row " + AUDIO_DRAG + " px left: beat " + e.beat);
                break;
            case 133:
                if (e != null) {
                    e.s.mark();
                    e.s.doc.laneCount = 0;                           // back to unlimited (tiled) lanes
                    e.s.changed(false);
                    e.s.lanePick.clear();
                    for (int id : new int[]{-2, -1, 1, 2}) e.s.lanePick.add(id);
                    e.s.laneSel = 1;
                    e.insp.tab = Inspector.FIELD;
                }
                break;
            case 134:
                if (e != null) {
                    e.insp.applyPartialScale(e.s, e.s.doc);
                    e.s.mark();
                    e.s.doc.laneScale = 0.9f;
                    e.s.doc.laneScaleEv.add(new Doc.Ev(44f, 46f, 0.9f, 1.1f, 1f));
                    e.s.changed(false);
                }
                break;
            case 136:
                if (e != null) {
                    LaneLayout lay = e.pv.engine().layout();
                    Doc.DLane one = e.s.doc.lane(1);
                    Doc.Ev w = one != null && !one.widthEv.isEmpty() ? one.widthEv.get(0) : null;
                    log("scale at beat " + e.beat + ": tiled " + lay.tiled() + ", lane +1 width "
                        + lay.width(lay.indexOf(1)) + " (expect 216), lane +3 left " + lay.left(lay.indexOf(3))
                        + " (expect 1392); partial on +1: "
                        + (w == null ? "none" : w.start + "-" + w.end + " " + w.from + "->" + w.to) + ", picked "
                        + e.s.lanePick);
                    e.s.lanePick.clear();
                    e.s.mark();
                    e.s.doc.cameraFrame = 120f;                      // axis 120 right of the centre
                    e.s.doc.cameraFrameEv.add(new Doc.Ev(44f, 46f, 120f, -120f, 1f));
                    e.s.changed(false);
                }
                break;
            case 138:
                if (e != null) {
                    LaneLayout lay = e.pv.engine().layout();
                    log("camera frame 120 at beat " + e.beat + ": axis " + lay.axis() + " (expect 1080), lane +1 left "
                        + lay.left(lay.indexOf(1)) + " (expect 1080), lane -1 right " + lay.right(lay.indexOf(-1))
                        + " (expect 1080), lane +3 left " + lay.left(lay.indexOf(3)) + " (expect 1512)");
                }
                break;
            case 139:
                if (e != null) e.insp.tab = Inspector.CHART;
                break;
            case 132:
                app.im.keyDown(Input.Keys.NUM_1);
                app.im.mouseMoved(Math.round(EditorScreen.ux(714f)), 560); // reference x 714
                break;
            case 142:
                if (e != null) notesBefore = e.s.doc.notes.size();
                click(Math.round(EditorScreen.ux(714f)), 560);
                break;
            case 145:
                if (e != null) {
                    int placed = e.s.doc.notes.size() - notesBefore;
                    e.undo();
                    int undone = e.s.doc.notes.size() - notesBefore;
                    e.redo();
                    log("placed notes: " + placed + ", after undo: " + undone + ", after redo: "
                        + (e.s.doc.notes.size() - notesBefore));
                }
                break;
            case 146:
                if (e != null) {
                    e.s.mark();
                    e.s.doc.bpms.add(new Doc.Seg(48f, 91f));
                    e.s.doc.sortBpms();
                    e.s.changed(false);
                }
                break;
            case 147:
                app.im.keyDown(Input.Keys.NUM_2);
                app.im.touchDown(Math.round(EditorScreen.ux(1628f)), 600, 0, Input.Buttons.LEFT); // reference x 1628
                break;
            case 148: app.im.touchDragged(Math.round(EditorScreen.ux(1628f)), 520, 0); break;
            case 149: app.im.touchDragged(Math.round(EditorScreen.ux(1628f)), 440, 0); break;
            case 151: app.im.touchUp(Math.round(EditorScreen.ux(1628f)), 440, 0, Input.Buttons.LEFT); break;
            case 152:
                if (e != null) {
                    Doc.DNote last = e.s.doc.notes.get(e.s.doc.notes.size() - 1);
                    for (Doc.DNote n : e.s.doc.notes) if (n.uid > last.uid) last = n;
                    log("tempo 182 -> 91 at beat 48: beat 60 at " + e.s.beatToMs(60f) + " ms (expect ~23736.3)");
                    log("dragged note: " + last.type + " lane " + last.lane + " beat " + last.judgement + " duration "
                        + last.duration);
                    e.save(null);
                }
                break;
            case 153:
                if (e != null) pvDrag(e, true);                      // also presses the preview band's end edge
                break;
            case 154: app.im.touchDragged(pvAt[2], pvAt[1], 0); break;
            case 155: app.im.touchUp(pvAt[2], pvAt[1], 0, Input.Buttons.LEFT); break;
            case 156:
                if (e != null) pvDrag(e, false);
                break;
            case 160:
                if (e != null) {
                    roundTrip(e);
                    sharedTempo(e);
                    iterationsTest(e);
                }
                alephSurface();
                break;
            case 162: click(55, 28); break;                     // ← 曲库
            case 172: click(1323, 976); break;                  // modal? (none when saved) / edit again
            case 174:
                if (e != null) previewWindow(e, true);
                break;
            case 176: click((int) (EditorScreen.PX + EditorScreen.PW) - 254 + 59, 68 + 24); break; // sync：高亮 → 增强
            case 178: if (e != null) log("preview sync hint after one click: "
                + EditorScreen.SYNC_LABELS[e.pv.syncHint()] + " (expect 增强)");
                if (e != null) previewWindow(e, false);              // (177 releases the click)
                break;
            case 179:
                if (e != null) e.pv.setSyncHint(0);
                break;
            case 180:
            case 186:
                if (e != null) playPastAudio(e, frame == 180);
                break;
            case 188:
                if (e != null) outsideEdit(e);
                break;
            case 200:                                               // the editor checks the file every second
                if (e != null) {
                    log("outside edit noticed: asking " + app.im.modalOpen() + " (expect true)");
                    app.im.keyDown(Input.Keys.ENTER);                // default button = 重新载入
                }
                break;
            case 202:
                if (e != null) {
                    log("after reload: constant " + e.s.doc.constant + " (expect 15.5), unsaved " + e.s.dirty
                        + " (expect false), asking again " + app.im.modalOpen() + " (expect false)");
                }
                break;
            case 204:                                               // a long 音符 tab to drag (Kelvin 10-14)
                if (e != null && !e.s.doc.notes.isEmpty()) {
                    e.s.select(e.s.doc.notes.get(0), false);
                    segments(e, e.s.single());
                    e.insp.tab = Inspector.NOTE;
                    java.util.Arrays.fill(e.insp.scroll, 0f);
                }
                break;
            case 205: app.im.touchDown(1914, 110, 0, Input.Buttons.LEFT); break; // the scrollbar thumb
            case 206: app.im.touchDragged(1914, 310, 0); break;
            case 207:
                app.im.touchUp(1914, 310, 0, Input.Buttons.LEFT);
                if (e != null) {
                    barScroll = e.insp.scroll[Inspector.NOTE];
                    log("scrollbar drag: scroll " + barScroll + " (expect > 0)");
                }
                break;
            // empty space left of the rows
            case 208: app.im.touchDown((int) EditorScreen.TR + 8, 700, 0, Input.Buttons.LEFT); break;
            case 209: app.im.touchDragged((int) EditorScreen.TR + 8, 600, 0); break;
            case 210:
                app.im.touchUp((int) EditorScreen.TR + 8, 600, 0, Input.Buttons.LEFT);
                if (e != null) log("empty-space drag: scroll moved " + (e.insp.scroll[Inspector.NOTE] - barScroll)
                    + " (expect 100)");
                break;
            case 211:                                               // 统一流速 (Kelvin 10-15)
                if (e != null) {
                    e.insp.tab = Inspector.CHART;
                    java.util.Arrays.fill(e.insp.scroll, 0f);
                    flowFalls(e);
                }
                break;
            case 214:
                if (e != null) flowBack(e);
                break;
            case 215:                                               // 轨道插入 (Kelvin 10-15)
                if (e != null) e.seekBeat(8f);
                break;
            case 216:
                if (e != null) laneInsert(e);
                break;
            case 219:
                if (e != null) laneBetween(e);
                break;
            case 222:                                               // 旋转中心 (Kelvin 10-16)
                if (e != null) pivotTest(e);
                break;
            case 226:                                               // 删除轨道 (Kelvin 10-17)
                if (e != null) laneDelete(e);
                break;
            case 227:                                               // 1920 × 1200 (Kelvin 10-19)
                if (e != null) frame1200(e);
                break;
            case 230: app.show(new LibraryScreen(app, null)); break; // 新歌三个难度 (Kelvin 10-22)
            case 231: newSong(); break;
            case 233: app.im.keyDown(Input.Keys.ENTER); break;     // BPM 120 → 新建
            case 235: newSongOpened(); break;
            case 238: if (e != null) veilSetup(e); break;          // 漩涡 and 扭曲 (Kelvin 10-23)
            case 239:
                if (e != null) {
                    veilChecks(e);
                    orbitChecks(e);
                    curtains(e);
                    tideMines(e);
                    fakeRigid(e);
                    holdStill(e);
                }
                break;
            case 240: app.im.keyDown(Input.Keys.NUM_9); break;      // 漩涡 tool (Kelvin 2026-11-01)
            case 241:
                if (e != null) {
                    veilsBefore = e.s.doc.veils.size();
                    click(Math.round(EditorScreen.ux(Field.x(200f))), Math.round(EditorScreen.uy(Field.y(1000f))));
                }
                break;
            case 243:                                               // 242 releases the click
                if (e != null) {
                    veilTool(e);
                    e.undo();
                    e.mantleCurtain = true;
                    e.setTool(EditorScreen.T_VEIL);
                    log("curtain tool: tab " + e.insp.tab + " (expect 4)");
                    e.setTool(EditorScreen.T_SELECT);
                    e.insp.tab = Inspector.VEIL;
                    app.im.keyDown(Input.Keys.N);
                    e.s.veilSel = circleUid;
                }
                break;
            case 244:
                if (e != null) {
                    int back = e.tool;
                    app.im.keyDown(Input.Keys.N);
                    log("N: select -> " + back + " (expect 10, the curtain tool picked last)");
                }
                break;
            case 245:
                if (e != null) {
                    log("N again: " + e.tool + " (expect 0)");
                    e.insp.tab = Inspector.VEIL;
                }
                break;
            case 246:                                               // drag the hexagon by its centre
                if (e != null) {
                    Doc.DVeil v = e.s.doc.veilByUid(polyUid);
                    press(EditorScreen.ux(Field.x(v.x)), EditorScreen.uy(Field.y(v.y)));
                }
                break;
            case 247: app.im.touchDragged(at[0] + 120, at[1] - 60, 0); break;
            case 248: app.im.touchUp(at[0] + 120, at[1] - 60, 0, Input.Buttons.LEFT); break;
            case 249: if (e != null) veilDragged(e); break;
            case 250:
            case 251:                                               // the distorted note's 扭曲 at the bottom
                if (e != null) {
                    e.s.select(e.s.doc.byUid(distUid), false);
                    e.insp.tab = Inspector.NOTE;
                    e.insp.scroll[Inspector.NOTE] = 1e5f;
                }
                break;
            case 253:                                               // 实机 (Kelvin 2026-10-27)
            case 257:
                click((int) (EditorScreen.PX + EditorScreen.PW) - 254 + 183, 68 + 24);
                break;
            case 255:                                               // a curtain over the HUD corner (Kelvin 10-31)
                if (e != null) {
                    Doc.DVeil cu = new Doc.DVeil();
                    cu.radius = 420f;
                    cu.x = 1560f;
                    cu.y = 900f;
                    e.s.doc.curtains.add(cu);
                    e.s.changed(false);
                }
                break;
            case 256:
                if (e != null) {
                    liveRun(e, 4.3f, 5.62f);                         // the three notes, 60 ms after the last
                    log("实机: marks off " + !e.overlays + ", combo " + e.pv.engine().scores().combo()
                        + " (expect true, 3)");
                }
                break;
            case 259: if (e != null) log("实机 off: marks back " + e.overlays + " (expect true)"); break;
            default:
        }
    }

    /**
     * Plays the preview from {@code fromBeat} to {@code toBeat} at 60 fps with the music paused, then leaves the
     * playhead there, so the next frame draws that moment as it stands (hit effects included).
     */
    private void liveRun(EditorScreen e, float fromBeat, float toBeat) {
        e.pb.pause();
        double from = e.s.beatToMs(fromBeat), to = e.s.beatToMs(toBeat);
        e.pv.seek(from, 0f, false);
        for (double t = from; t < to; t += 1000.0 / 60.0) e.pv.seek(t, 1f / 60f, true);
        e.pv.seek(to, 1f / 60f, true);
        e.pb.seek(to);
    }

    private int polyUid, circleUid, distUid;

    /** 漩涡 and 扭曲 (Kelvin 2026-10-23): two veils over three falling notes, one note distorted; save → read back. */
    private void veilSetup(EditorScreen e) {
        Doc d = e.s.doc;
        float p = 4f;                                               // the playhead in the shots
        e.s.mark();
        int[] lanes = {-3, -1, 2};
        for (int k = 0; k < lanes.length; k++) {
            Doc.DNote n = new Doc.DNote(NoteType.TAP, Line.LOWER, lanes[k], p + 0.5f + 0.5f * k);
            n.approach(Note.DEFAULT_APPROACH_BEATS, Doc.SPAWN, 1f);
            d.notes.add(n);
            distUid = n.uid;
        }
        Doc.DNote dn = d.byUid(distUid);
        Doc.DDist x = new Doc.DDist();                               // twice the veil's wobble while it falls
        x.from = dn.judgement - 2f;
        x.to = dn.judgement;
        x.intensity = 2f;
        dn.distortions.add(x);
        Doc.DVeil a = new Doc.DVeil();
        a.polygon = true;
        a.points = Inspector.ring(6, 220f);
        a.x = 640f;
        a.y = 640f;
        a.start = 0f;
        a.end = 16f;
        Doc.DVeil b = new Doc.DVeil();
        b.radius = 180f;
        b.x = 1320f;
        b.y = 520f;
        b.rotEv.add(new Doc.Ev(0f, 16f, 0f, 90f, 1f));
        Doc.DDist w = new Doc.DDist();
        w.intensity = 2f;
        b.distortions.add(w);
        d.veils.add(a);
        d.veils.add(b);
        polyUid = a.uid;
        circleUid = b.uid;
        e.s.changed(false);
        e.seekBeat(p);
        e.s.veilSel = a.uid;
        e.insp.tab = Inspector.VEIL;
        try {
            FileHandle f = Gdx.files.absolute(dir).child("veil_roundtrip.json");
            String json = d.toJson();
            f.writeString(json, false, "UTF-8");
            Chart c = ChartMapper.read(f, null, null);
            Doc back = Doc.fromChart(c);
            Doc.readRelFlags(back, f);
            Doc.DVeil bb = back.veils.size() > 1 ? back.veils.get(1) : null;
            log("veil round trip: " + c.veils().length + " veils, " + (c.veils().length > 0
                ? c.veils()[0].pointCount() : 0) + " points, circle distortion "
                + (bb != null && !bb.distortions.isEmpty() ? Doc.num(bb.distortions.get(0).intensity) : "-")
                + ", identical json " + json.equals(back.toJson()) + " (expect 2, 6, 2, true)");
        } catch (Exception ex) {
            log("veil round trip failed: " + ex);
        }
    }

    /** The two veil checks, with a 2-point polygon and a veil over the whole field added and removed again. */
    private void veilChecks(EditorScreen e) {
        Doc d = e.s.doc;
        Doc.DVeil bad = new Doc.DVeil();
        bad.polygon = true;
        bad.points = new float[]{0f, 0f, 100f, 0f};
        Doc.DVeil big = new Doc.DVeil();
        big.radius = 1500f;
        d.veils.add(bad);
        d.veils.add(big);
        e.s.changed(false);
        e.checks.update(e.s);
        StringBuilder r = new StringBuilder("veil checks:");
        for (Checks.Item it : e.checks.items) {
            if (it.text != null && it.text.contains("漩涡")) r.append(" [").append(it.text).append(']');
        }
        log(r + " (expect 3 notes covered, the 3rd veil a polygon without 3 points)");
        d.veils.remove(bad);
        d.veils.remove(big);
        e.s.changed(false);
    }

    /**
     * 水帘 (Kelvin 2026-10-31): a curtain with a blur and a blur event survives the editor's text, the v5 save and the
     * reopen, and the preview's chart has it as a curtain, not a veil.
     */
    private void curtains(EditorScreen e) {
        Doc d = e.s.doc;
        int veilsBefore = d.veils.size();
        Doc.DVeil c = new Doc.DVeil();
        c.radius = 300f;
        c.start = 0f;
        c.end = 8f;
        c.blur = 24f;
        c.blurEv.add(new Doc.Ev(0f, 4f, 24f, 0f, 1f));
        d.curtains.add(c);
        e.s.changed(false);
        try {
            Chart pc = e.s.chart;
            String v4 = d.toJson();
            org.kelvinizer.elements.ChartUpgrade.Result r =
                org.kelvinizer.elements.ChartUpgrade.upgrade("curtain.json", v4.getBytes("UTF-8"));
            Doc back = Doc.fromChart(ChartMapper.read(new java.io.ByteArrayInputStream(r.json.getBytes("UTF-8")),
                "curtain.json", org.kelvinizer.elements.SongInfo.of(pc)));
            Doc.DVeil b = back.curtains.isEmpty() ? null : back.curtains.get(0);
            log("curtains: preview " + pc.curtains().length + " curtain, " + pc.veils().length + " veils, blur at 2 "
                + Im.fmt(pc.curtains().length > 0 ? pc.curtains()[0].blurAt(2f) : -1f) + ", read back "
                + back.curtains.size() + " curtain blur " + (b != null ? Im.fmt(b.blur) + " with " + b.blurEv.size()
                + " event" : "-") + ", veils " + back.veils.size() + ", problems " + r.problems + " (expect 1, "
                + veilsBefore + ", 12, 1, 24, 1, " + veilsBefore + ", [])");
        } catch (Exception ex) {
            log("curtains failed: " + ex);
        }
        d.curtains.remove(c);
        e.s.changed(false);
    }

    /**
     * 浪花水雷 (Kelvin 2026-10-31): a TIDE MINE and a TIDE STREAM MINE survive the v5 save and the reopen (written as
     * "tideMine" with no lane, wave speed kept), count as mines, and a DROP inside the stream mine is warned about.
     */
    private int veilsBefore;

    /** Key 9 picks the 漩涡 tool and opens its tab; a click off the veils adds a round one centred there. */
    private void veilTool(EditorScreen e) {
        Doc.DVeil v = e.s.doc.veils.isEmpty() ? null : e.s.doc.veils.get(e.s.doc.veils.size() - 1);
        log("veil tool: tool " + e.tool + ", tab " + e.insp.tab + ", veils +" + (e.s.doc.veils.size() - veilsBefore)
            + ", at " + (v == null ? "-" : Math.round(v.x) + "," + Math.round(v.y)) + ", 8 beats "
            + (v != null && v.end - v.start == 8f) + " (expect 9, 3, +1, ~200,1000, true)");
    }

    /** 假键 and 半径等比 (game 7.167 / 7.168, Kelvin 2026-11-02): kept through the v5 save; a fake is not counted. */
    private void fakeRigid(EditorScreen e) {
        Doc d = e.s.doc;
        int lane = d.lanes.get(0).id;
        Doc.DNote f = new Doc.DNote(NoteType.TAP, Line.LOWER, lane, 64f);
        f.fake = true;
        f.orbit = true;
        f.orbitX = 960f;
        f.orbitY = 600f;
        f.orbitRadius = 200f;
        f.orbitRigid = true;
        Doc.DNote ft = new Doc.DNote(NoteType.UNIVERSAL, Line.UPPER, lane, 64f);   // a fake 浪花 (game 7.169)
        ft.fake = true;
        int before = e.s.chart.totalNotes();
        d.notes.add(f);
        d.notes.add(ft);
        e.s.changed(false);
        try {
            Chart pc = e.s.chart;
            int after = pc.totalNotes();
            org.kelvinizer.elements.ChartUpgrade.Result r = org.kelvinizer.elements.ChartUpgrade.upgrade(
                "fake.json", d.toJson().getBytes("UTF-8"));
            Doc back = Doc.fromChart(ChartMapper.read(new java.io.ByteArrayInputStream(r.json.getBytes("UTF-8")),
                "fake.json", org.kelvinizer.elements.SongInfo.of(pc)));
            Doc.DNote k = null;
            int tides = 0;
            for (Doc.DNote n : back.notes) {
                if (n.fake && n.type == NoteType.TAP) k = n;
                if (n.fake && n.type == NoteType.UNIVERSAL) tides++;
            }
            log("fake + rigid: built " + (e.s.note(f) != null && e.s.note(f).fake()) + ", counted " + (after - before)
                + ", read back fake " + (k != null) + " rigid " + (k != null && k.orbitRigid) + " fake tides " + tides
                + ", problems " + r.problems + " (expect true, 0, true true 1, [])");
        } catch (Exception ex) {
            log("fake + rigid failed: " + ex);
        }
        d.notes.remove(f);
        d.notes.remove(ft);
        e.s.changed(false);
    }

    /**
     * 不跟判定线 (Kelvin 2026-11-03): on a copy of the chart with both lines shaking (one eased), a default-fall sky
     * key and an eased ground hold keep their place on screen from 99.5 to 101 and are unchanged outside it, as the
     * game's loader reads the saved file (within half a reference unit: eased parts are cut into short lines).
     */
    private void holdStill(EditorScreen e) {
        try {
            Doc d = e.s.doc.copy();
            d.upperEv.clear();
            d.lowerEv.clear();
            d.upperEv.add(new Doc.Ev(100f, 100.5f, d.upper, d.upper + 30f, 1f));
            d.upperEv.add(new Doc.Ev(100.5f, 101f, d.upper + 30f, d.upper - 20f, 2f));
            d.lowerEv.add(new Doc.Ev(99.75f, 100.75f, d.lower, d.lower + 40f, 1.5f));
            int lane = d.lanes.get(0).id;
            Doc.DNote sky = new Doc.DNote(NoteType.TAP, Line.UPPER, lane, 101.37f);
            Doc.DNote hold = new Doc.DNote(NoteType.HOLD, Line.LOWER, lane, 101.29f);
            hold.duration = 0.5f;
            hold.height = 270f;
            hold.move.add(new Doc.Ev(99f, 101.29f, 1080f, 0f, 2f));
            d.notes.add(sky);
            d.notes.add(hold);
            Chart before = stillChart(e, d);
            int[] r = d.holdStill(java.util.Arrays.asList(sky, hold), 99.5f, 101f);
            Chart after = stillChart(e, d);
            float in = 0f, out = 0f;
            for (int k = 0; k < 2; k++) {
                Line ln = k == 0 ? Line.UPPER : Line.LOWER;
                float j = k == 0 ? 101.37f : 101.29f, sgn = k == 0 ? -1f : 1f;
                Note n0 = stillNote(before, ln, j), n1 = stillNote(after, ln, j);
                float ref = before.lineEvents().yAt(ln, 99.5f);
                for (float t = 98.5f + 1f / 256f; t < j; t += 1f / 128f) {   // between frames on a beat
                    float y0 = before.lineEvents().yAt(ln, t) + sgn * n0.positionAt(t);
                    float y1 = after.lineEvents().yAt(ln, t) + sgn * n1.positionAt(t);
                    if (t >= 99.5f && t < 101f) in = Math.max(in, Math.abs(y1 - (ref + sgn * n0.positionAt(t))));
                    else out = Math.max(out, Math.abs(y1 - y0));
                }
            }
            log("hold still: keys " + r[0] + ", events " + sky.move.size() + " / " + hold.move.size()
                + ", off inside " + (in < 0.5f) + ", off outside " + (out < 0.5f) + " (expect 2, true, true)");
        } catch (Exception ex) {
            log("hold still failed: " + ex);
        }
    }

    private static Chart stillChart(EditorScreen e, Doc d) throws Exception {
        org.kelvinizer.elements.ChartUpgrade.Result r = org.kelvinizer.elements.ChartUpgrade.upgrade(
            "still.json", d.toJson().getBytes("UTF-8"));
        return ChartMapper.read(new java.io.ByteArrayInputStream(r.json.getBytes("UTF-8")), "still.json",
            org.kelvinizer.elements.SongInfo.of(e.s.chart));
    }

    private static Note stillNote(Chart c, Line line, float judgement) {
        for (int i = 0; i < c.noteCount(); i++) {
            Note n = c.note(i);
            if (n.line() == line && Math.abs(n.judgement() - judgement) < 1e-3f) return n;
        }
        throw new IllegalStateException("note at " + judgement + " not built");
    }

    private void tideMines(EditorScreen e) {
        Doc d = e.s.doc;
        int lane = d.lanes.get(0).id;
        Doc.DNote a = new Doc.DNote(NoteType.TIDE_MINE, Line.LOWER, lane, 60f);
        a.waveSpeed = 0.5f;
        Doc.DNote b = new Doc.DNote(NoteType.TIDE_MINE, Line.LOWER, lane, 62f);
        b.duration = 2f;
        d.setAutoTail(b);
        Doc.DNote x = new Doc.DNote(NoteType.TAP, Line.LOWER, lane, 63f);
        d.notes.add(a);
        d.notes.add(b);
        d.notes.add(x);
        e.s.changed(false);
        try {
            Chart pc = e.s.chart;
            org.kelvinizer.elements.ChartUpgrade.Result r = org.kelvinizer.elements.ChartUpgrade.upgrade(
                "tidemine.json", d.toJson().getBytes("UTF-8"));
            Doc back = Doc.fromChart(ChartMapper.read(new java.io.ByteArrayInputStream(r.json.getBytes("UTF-8")),
                "tidemine.json", org.kelvinizer.elements.SongInfo.of(pc)));
            String kept = "none";
            for (Doc.DNote n : back.notes) {
                if (n.type != NoteType.TIDE_MINE) continue;
                kept = (kept.equals("none") ? "" : kept + " + ") + n.nameZh() + " " + Im.fmt(n.duration) + " wave "
                    + Im.fmt(n.waveSpeed);
            }
            boolean laneless = !r.json.matches("(?s).*\"tideMine\"[^}]*\"lane\".*");
            e.checks.update(e.s);
            boolean warned = false;
            for (Checks.Item it : e.checks.items) warned |= it.text != null && it.text.contains("潮汐水雷封锁冲突");
            log("tide mines: v5 " + r.json.contains("\"tideMine\"") + " laneless " + laneless + ", read back " + kept
                + ", mines " + pc.totalMines() + ", warned " + warned + ", problems " + r.problems
                + " (expect true true, 浪花水雷 0 wave 0.5 + 潮汐水雷 2 wave 0, mines >= 2, true, [])");
        } catch (Exception ex) {
            log("tide mines failed: " + ex);
        }
        d.notes.remove(a);
        d.notes.remove(b);
        d.notes.remove(x);
        e.s.changed(false);
    }

    /**
     * 物件坐标冲突 (Kelvin 2026-10-29 20:55): a falling key given a radius orbit is warned about until a side is picked.
     */
    private void orbitChecks(EditorScreen e) {
        Doc.DNote n = null;
        for (Doc.DNote x : e.s.doc.notes) if (n == null && x.type == NoteType.TAP && !x.move.isEmpty()) n = x;
        if (n == null) {
            log("orbit check: no falling DROP");
            return;
        }
        Doc.DNote keep = n.copy(true);
        n.orbit = true;
        n.orbitRadius = 300f;
        n.orbitAngle = 0f;
        e.s.changed(false);
        e.checks.update(e.s, e.pv);
        String warned = conflictText(e.checks);
        n.orbitConflict = org.kelvinizer.elements.NoteOrbit.CONFLICT_ORBIT;
        e.s.changed(false);
        e.checks.update(e.s, e.pv);
        String picked = conflictText(e.checks);
        log("orbit check: warned " + (warned != null) + ", after picking " + (picked != null) + " (expect true, false)"
            + (warned != null ? " [" + warned + "]" : ""));
        n.orbit = keep.orbit;
        n.orbitRadius = keep.orbitRadius;
        n.orbitAngle = keep.orbitAngle;
        n.orbitConflict = keep.orbitConflict;
        e.s.changed(false);
    }

    private static String conflictText(Checks c) {
        for (Checks.Item it : c.items) if (it.text != null && it.text.startsWith("物件坐标冲突")) return it.text;
        return null;
    }

    /** The press on the hexagon selected it and the drag moved it; undo puts it back. */
    private void veilDragged(EditorScreen e) {
        Doc.DVeil v = e.s.doc.veilByUid(polyUid);
        log("veil drag: selected " + (e.s.veilSel == polyUid) + ", centre " + Doc.num(v.x) + ", " + Doc.num(v.y)
            + " (expect true, 820, 730)");
        e.undo();
        v = e.s.doc.veilByUid(polyUid);
        log("veil drag undone: centre " + (v != null ? Doc.num(v.x) + ", " + Doc.num(v.y) : "-")
            + " (expect 640, 640)");
    }

    private Library.Song newSong;

    /** A new song (no chart yet) asks for its BPM. */
    private void newSong() {
        try {
            FileHandle wav = Gdx.files.absolute(dir).child("new-song.wav");
            byte[] h = new byte[44 + 8820];                         // 0.1 s of silence, 44.1 kHz mono 16-bit
            java.nio.ByteBuffer b = java.nio.ByteBuffer.wrap(h).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            b.put("RIFF".getBytes()).putInt(h.length - 8).put("WAVEfmt ".getBytes()).putInt(16).putShort((short) 1)
                .putShort((short) 1).putInt(44100).putInt(88200).putShort((short) 2).putShort((short) 16)
                .put("data".getBytes()).putInt(8820);
            wav.writeBytes(h, false);
            newSong = app.lib.importAudio(wav.file(), "AutoTest New Song");
            ((LibraryScreen) app.view()).create(newSong, org.kelvinizer.library.Difficulty.SURFACE);
        } catch (Exception ex) {
            log("new song failed: " + ex);
        }
    }

    /** Surface, Shallow and Deep exist at 1 / 2 / 3 and the editor shows Surface. */
    private void newSongOpened() {
        EditorScreen e = ed();
        StringBuilder r = new StringBuilder("new song: editing "
            + (e != null && e.s.song == newSong ? e.s.diff.label() : "-"));
        for (Library.Diff d : newSong.diffs) {
            String c = "-";
            try {
                if (d.exists()) {
                    Chart ch = ChartMapper.read(d.file, null, null);
                    c = ch.chartConstant() + " at " + Doc.num(ch.bpm());
                }
            } catch (Exception ex) {
                c = ex.toString();
            }
            r.append(", ").append(d.d.label()).append(' ').append(c);
        }
        log(r + " (expect Surface, Surface 1 at 120, Shallow 2 at 120, Deep 3 at 120, Abyss -)");
    }

    /** Saving one difficulty writes its tempo into the song's other difficulty files (Kelvin 16:03). */
    private int[] at = {0, 0};
    private Library.Song cropSong;

    private JacketCrop crop() {
        return app.view() instanceof LibraryScreen ? ((LibraryScreen) app.view()).crop : null;
    }

    private void press(float x, float y) {
        at = new int[]{Math.round(x), Math.round(y)};
        app.im.touchDown(at[0], at[1], 0, Input.Buttons.LEFT);
    }

    private String circle() {
        JacketCrop c = crop();
        return c == null ? "-" : "centre " + Math.round(c.cx) + "," + Math.round(c.cy) + " r " + Math.round(c.r);
    }

    private String wide() {
        JacketCrop c = crop();
        return c == null ? "-" : Math.round(c.wx) + "," + Math.round(c.wy) + " w " + Math.round(c.ww);
    }

    /**
     * 新手教程 (Kelvin 10-17): the game's assets/Introduction is listed last with its music, opens, and saves with a backup
     * in backups/Introduction.
     */
    private void tutorialTest() {
        Library.Song t = null;
        for (Library.Song s : app.lib.songs) if (s.tutorial) t = s;
        if (t == null) {
            log("tutorial: not listed");
            return;
        }
        String r = "tutorial: listed last " + (app.lib.songs.get(app.lib.songs.size() - 1) == t) + ", id " + t.id
            + ", music " + (t.audio == null ? "none" : t.audio.name()) + ", Surface "
            + t.diffs[org.kelvinizer.library.Difficulty.SURFACE.ordinal()].exists();
        String want = "?";
        try {
            FileHandle f = t.diffs[org.kelvinizer.library.Difficulty.SURFACE.ordinal()].file;
            want = ChartMapper.read(f, null, null).noteCount() + " notes, 1, " + (Doc.iterationsIn(f) + 1);
            Session s = Session.open(app.lib, t, org.kelvinizer.library.Difficulty.SURFACE);
            r += ", opens with " + s.doc.notes.size() + " notes";
            s.save();
            FileHandle[] b = app.lib.home.child("backups").child("Introduction").list();
            r += ", backups/Introduction " + b.length + ", iterations " + s.doc.iterations;
        } catch (Exception ex) {
            r += ", failed: " + ex;
        }
        log(r + " (expect true, Introduction, Introduction.wav, true, " + want + ")");
    }

    /** Gives the first song with a jacket a 1600 × 1000 picture (blue top, red bottom, its jacket right of centre). */
    private void cropOpen() {
        Library.Song s = app.lib.songs.get(0);
        for (Library.Song o : app.lib.songs) if (o.jacket != null) { s = o; break; }
        cropSong = s;
        Pixmap pic = new Pixmap(1600, 1000, Pixmap.Format.RGBA8888);
        pic.setColor(0.1f, 0.3f, 0.6f, 1f);
        pic.fillRectangle(0, 0, 1600, 500);
        pic.setColor(0.6f, 0.2f, 0.3f, 1f);
        pic.fillRectangle(0, 500, 1600, 500);
        if (s.jacket != null) {
            Pixmap j = new Pixmap(s.jacket);
            pic.setFilter(Pixmap.Filter.BiLinear);
            pic.drawPixmap(j, 0, 0, j.getWidth(), j.getHeight(), 820, 180, 640, 640);
            j.dispose();
        }
        FileHandle f = Gdx.files.absolute(dir).child("crop_src.png");
        PixmapIO.writePNG(f, pic);
        pic.dispose();
        ((LibraryScreen) app.view()).jacketPicked(s, f.file());
        JacketCrop c = crop();
        log("crop dialog open " + (c != null) + ", mode " + (c != null ? c.mode : -1) + ", 16:9 " + wide() + ", "
            + circle() + " (expect true, 0, 0,50 w 1600, centre 800,500 r 500)");
    }

    /** OK copied the picture whole and wrote both boxes into song.json. */
    private void cropSaved() {
        Library.Song s = cropSong;
        Pixmap j = new Pixmap(s.jacket);
        int top = j.getPixel(2, 2), bottom = j.getPixel(2, j.getHeight() - 3);
        boolean upright = ((top >>> 8) & 255) > (top >>> 24) && (bottom >>> 24) > ((bottom >>> 8) & 255);
        log("crop saved: " + s.jacket.name() + " " + j.getWidth() + " x " + j.getHeight()
            + " (expect jacket.png 1600 x 1000), upright " + upright + " (expect true), dialog closed "
            + (crop() == null) + " (expect true)");
        j.dispose();
        String got = "-";
        try {
            com.badlogic.gdx.utils.JsonValue v = new com.badlogic.gdx.utils.JsonReader()
                .parse(s.dir.child(Library.SONG_JSON));
            got = boxText(v.get(Library.WIDE_KEY)) + " " + boxText(v.get(Library.ROUND_KEY));
        } catch (Exception ex) {
            got = "unreadable: " + ex.getMessage();
        }
        log("song.json boxes: " + got
            + " (expect about [0.0799, 0.1011, 0.8722, 0.785] [0.3764, 0.0723, 0.5346, 0.8554])");
    }

    private static String boxText(com.badlogic.gdx.utils.JsonValue b) {
        if (b == null) return "none";
        return "[" + b.getFloat("x", -1f) + ", " + b.getFloat("y", -1f) + ", " + b.getFloat("w", -1f) + ", "
            + b.getFloat("h", -1f) + "]";
    }

    /** The framing dialog for the current jacket starts from the saved boxes. */
    private void cropReopen() {
        ((LibraryScreen) app.view()).frameJacket(cropSong);
        log("reopened: 16:9 " + wide() + ", " + circle() + " (expect 128,101 w 1396, centre 1030,500 r 428)");
    }

    private void sharedTempo(EditorScreen e) {
        int n = 0, same = 0;
        for (Library.Diff d : e.s.song.diffs) {
            if (d.d == e.s.diff || !d.exists()) continue;
            n++;
            try {
                Doc o = Doc.fromChart(ChartMapper.read(d.file));
                boolean eq = o.bpms.size() == e.s.doc.bpms.size();
                for (int i = 0; eq && i < o.bpms.size(); i++) {
                    eq = Math.abs(o.bpms.get(i).beat - e.s.doc.bpms.get(i).beat) < 1e-4f
                        && Math.abs(o.bpms.get(i).bpm - e.s.doc.bpms.get(i).bpm) < 1e-4f;
                }
                if (eq) same++;
            } catch (Exception ex) {
                log("tempo share: " + d.file.name() + " unreadable: " + ex.getMessage());
            }
        }
        log("tempo shared: " + same + " of " + n + " other difficulties have the same " + e.s.doc.bpms.size()
            + " tempo segments (expect all)");
    }

    /** @return whether a chart file is v5 (its song values are in song.json) */
    private static boolean v5(FileHandle f) {
        try {
            return org.kelvinizer.library.ChartHeaderReader.read(f).format >= 5;
        } catch (Exception ex) {
            return false;
        }
    }

    /**
     * 迭代次数 (Kelvin 10-16): the save at 152 backed the chart up (+1) and the shared tempo counted the other
     * old-format files (v5 files take it from song.json and stay as they are);
     * another save in the same run does not count; undo keeps the count; an outside count is kept.
     */
    private void iterationsTest(EditorScreen e) {
        Session s = e.s;
        int first = s.doc.iterations, onDisk = Doc.iterationsIn(s.file), others = 0, counted = 0;
        int base = startIterations.getOrDefault(s.file.path(), 0);
        for (Library.Diff d : s.song.diffs) {
            if (d.d == s.diff || !d.exists()) continue;
            others++;
            // an old file took the shared tempo and counted once; a v5 file is left alone (song.json has it)
            int bump = v5(d.file) ? 0 : 1;
            if (Doc.iterationsIn(d.file) == startIterations.getOrDefault(d.file.path(), 0) + bump) counted++;
        }
        s.save();
        int again = s.doc.iterations;
        s.mark();
        s.doc.iterations = again + 5;                         // as if a save had counted after the snapshot
        s.undo();
        int undone = s.doc.iterations;
        s.doc.iterations = again;
        s.file.writeString(s.file.readString("UTF-8")
            .replaceFirst("\"iterations\": \\d+", "\"iterations\": " + (again + 10)), false, "UTF-8");
        s.save();
        log("iterations: first save " + first + " (file " + onDisk + "), another save " + again + ", undo keeps "
            + undone + ", outside count " + s.doc.iterations + " (file " + Doc.iterationsIn(s.file)
            + "), other difficulties counted once: " + counted + " of " + others + " (expect " + (base + 1) + " ("
            + (base + 1) + "), " + (base + 1) + ", " + (base + 6) + ", " + (base + 11) + " ("
            + (base + 11) + "), all)");
    }

    /** Another tool rewrites the open chart file (like 写谱辅助 does): the editor must notice and offer a reload. */
    private void outsideEdit(EditorScreen e) {
        try {
            java.util.Map<String, Object> m = new java.util.HashMap<>();
            m.put("chartConstant", "15.5");
            String text = e.s.file.readString("UTF-8");
            String out = Library.patchTopLevel(text, m);
            // already 15.5 (patchTopLevel returns null): still touch the file, never write null (it emptied the file)
            e.s.file.writeString(out != null ? out : text + "\n", false, "UTF-8");
            log("outside edit written to " + e.s.file.name() + ", changed on disk " + e.s.changedOnDisk()
                + " (expect true)");
        } catch (Exception ex) {
            log("outside edit failed: " + ex);
        }
    }

    private float savedDur;
    private double savedPos;

    /**
     * Past the audio's end (a chart longer than its audio: placeholder songs on Aleph-0.mp3) the clock runs on
     * silently, and play there goes on instead of starting over.
     */
    private void playPastAudio(EditorScreen e, boolean start) {
        if (start) {
            savedDur = e.pb.durationMs();
            savedPos = e.pb.pos();
            e.pb.setDurationMs((float) e.s.beatToMs(e.s.doc.lastBeat()) - 30000f);
            e.pb.seek(e.pb.durationMs() - 1f);
            e.pb.play();
            return;
        }
        log("audio 30 s shorter than the chart: clock " + Math.round(e.pb.pos() - e.pb.durationMs())
            + " ms past the audio end, playing " + e.pb.playing() + " (expect > 0, true)");
        e.pb.pause();
        double at = e.pb.pos();
        e.pb.play();
        log("play again past the audio end: back to the start " + (e.pb.pos() < at) + " (expect false)");
        e.pb.pause();
        e.pb.setDurationMs(savedDur);
        e.pb.seek(savedPos);
    }

    /** The song list's preview window (Kelvin 2026-10-21): song.json keeps its other keys; 试听 plays the window. */
    private void previewWindow(EditorScreen e, boolean start) {
        FileHandle f = e.s.song.dir.child(Library.SONG_JSON);
        if (start) {
            savedSongJson = f.exists() ? f.readString("UTF-8") : null;
            savedPreviewPos = e.pb.pos();
            f.writeString("{ \"keepMe\": 7 }", false, "UTF-8");
            e.insp.tab = Inspector.CHART;
            boolean ok = e.s.lib.setPreview(e.s.song, 36f, 44f, e.s::beatToMs);
            e.listenPreview();
            log("preview window set " + ok + ", listening from " + Math.round(e.pb.pos()) + " ms = beat "
                + Math.round(e.s.msToBeat(e.pb.pos())) + " (expect true, 36)");
            return;
        }
        String t = f.readString("UTF-8");
        log("song.json: keepMe kept " + t.contains("\"keepMe\"") + ", start " + t.contains("\"previewStartMs\" : "
            + Math.round(e.s.beatToMs(36f))) + ", start beat " + t.contains("\"previewStartBeat\" : 36") + ", end "
            + t.contains("\"previewEndMs\"") + ", playing or stopped at its end "
            + (e.pb.playing() || e.pb.pos() >= e.s.song.previewEnd()) + " (expect true, true, true, true, true)");
        e.pb.pause();
        e.pb.seek(savedPreviewPos);
        e.s.lib.setPreview(e.s.song, Float.NaN, Float.NaN, null);
        t = f.readString("UTF-8");
        log("preview back to default: keys gone " + !t.contains("preview") + ", keepMe kept " + t.contains("keepMe")
            + ", window " + Math.round(e.s.song.previewEnd() - e.s.song.previewStart())
            + " ms (expect true, true, 20000)");
        if (savedSongJson != null) f.writeString(savedSongJson, false, "UTF-8");
        else f.delete();
    }

    private String savedSongJson;
    private double savedPreviewPos;

    /**
     * Dragging the preview band's end edge on the audio row (Kelvin 2026-10-21): it snaps to the beat grid, beats + ms
     * are written.
     */
    private void pvDrag(EditorScreen e, boolean start) {
        FileHandle f = e.s.song.dir.child(Library.SONG_JSON);
        if (start) {
            savedSongJson = f.exists() ? f.readString("UTF-8") : null;
            savedPreviewPos = e.pb.pos();
            savedTlStart = e.tlStart;
            e.s.lib.setPreview(e.s.song, 36f, 44f, e.s::beatToMs);
            e.seekBeat(40f);
            e.tlStart = 34.0;                                       // 36 … 48 well inside the view (no edge scrolling)
            pvAt = new int[]{Math.round(e.bx(44f)), Math.round(EditorScreen.R_AUDIO + 20f), Math.round(e.bx(48f)) + 2};
            app.im.touchDown(pvAt[0], pvAt[1], 0, Input.Buttons.LEFT);
            return;
        }
        String t = f.readString("UTF-8");
        log("preview end dragged: beats " + Im.fmt(e.s.song.previewStartBeat) + " - " + Im.fmt(e.s.song.previewEndBeat)
            + ", song.json end beat " + t.contains("\"previewEndBeat\" : 48") + ", end ms "
            + t.contains("\"previewEndMs\" : " + Math.round(e.s.beatToMs(48f))) + " (expect 36 - 48, true, true)");
        e.s.lib.setPreview(e.s.song, Float.NaN, Float.NaN, null);
        if (savedSongJson != null) f.writeString(savedSongJson, false, "UTF-8");
        else f.delete();
        e.pb.seek(savedPreviewPos);
        e.tlStart = savedTlStart;
    }

    private int[] pvAt = new int[3];
    private double savedTlStart;

    private void selectNear(EditorScreen e) {
        Doc.DNote pick = null;
        for (Doc.DNote n : e.s.doc.notes) {
            if (n.judgement > e.beat + 0.2f && n.judgement < e.beat + 1.6f) {
                if (pick == null || (n.type == NoteType.HOLD && pick.type != NoteType.HOLD)) pick = n;
            }
        }
        if (pick != null) {
            e.s.select(pick, false);
            log("selected " + pick.nameZh() + " lane " + pick.lane + " beat " + pick.judgement);
        } else log("no note near beat " + e.beat);
    }

    /** Motion over beats 24–26 but judged at 29: shown early, judged unseen (2026-10-13 rule). */
    private void motionBeforeJudgement(EditorScreen e) {
        Doc.DNote x = new Doc.DNote(NoteType.TAP, Line.LOWER, -1, 29f);
        x.move.add(new Doc.Ev(24f, 26f, Doc.SPAWN, 0f, 1f));
        e.s.mark();
        e.s.doc.notes.add(x);
        e.s.changed(false);
        Note g = e.s.note(x);
        if (g == null) log("motion 24-26 / judgement 29: no game note (" + e.s.buildError + ")");
        else log("motion 24-26 / judgement 29: default approach " + g.usesDefaultApproach() + ", position at 23 "
            + g.positionAt(23f) + ", 25 " + g.positionAt(25f) + ", 27 " + g.positionAt(27f) + ", 29 "
            + g.positionAt(29f) + ", 30 " + g.positionAt(30f) + ", check: " + checkFor(x.uid));
        e.undo();
    }

    /** A v1 chart opens as unlimited lanes with ±1..±5 declared (their old window squeeze as width events). */
    private void lanesAfterOpen(EditorScreen e) {
        Doc d = e.s.doc;
        int withEv = 0, rel = 0, all = 0, falls = 0;
        for (Doc.DLane l : d.lanes) if (!l.widthEv.isEmpty()) withEv++;
        for (Doc.DNote n : d.notes) {
            for (Doc.Ev v : n.move) if (n.defaultFall(v)) falls++;
            for (java.util.List<Doc.Ev> list : java.util.Arrays.asList(n.move, n.rot, n.alpha, n.heightEv)) {
                for (Doc.Ev v : list) {
                    all++;
                    if (v.rel) rel++;
                }
            }
        }
        LaneLayout lay = e.pv.engine().layout();
        int shown = 0;
        for (int i = 0; i < lay.count(); i++) if (lay.active(i)) shown++;
        log("opened: laneCount " + d.laneCount + ", declared lanes " + d.lanes.size() + " (" + withEv
            + " with width events), layout " + lay.count() + " lanes, " + shown + " shown at beat " + e.beat
            + ", tiled " + lay.tiled() + "; note events " + all + ", relative " + rel + " (expect the " + falls
            + " default falls)");
        StringBuilder sc = new StringBuilder();
        for (Doc.Ev v : d.laneScaleEv) {
            sc.append(' ').append(v.start).append('-').append(v.end).append(' ').append(v.from)
                .append("->").append(v.to);
        }
        log("opened: global scale " + d.laneScale + " events" + sc + "; camera frame " + d.cameraFrame + ", "
            + d.cameraFrameEv.size() + " event(s)");
    }

    /** Moving a judgement: a relative event follows, an absolute one stays (kept for the save check). */
    private void relAndAbs(EditorScreen e) {
        Doc.DNote x = new Doc.DNote(NoteType.TAP, Line.LOWER, 3, 30f);
        x.move.add(new Doc.Ev(24f, 26f, Doc.SPAWN, 0f, 1f));
        Doc.Ev r = new Doc.Ev(28f, 30f, 0f, 30f, 1f);
        r.rel = true;
        x.rot.add(r);
        e.s.mark();
        e.s.doc.notes.add(x);
        x.retime(1f);
        float yb = 33f;                                          // a free beat on lane 2 (no stack)
        boolean taken = true;
        while (taken) {
            taken = false;
            for (Doc.DNote o : e.s.doc.notes) {
                if (o.type == NoteType.TAP && o.line == Line.LOWER && o.lane == 2
                    && Math.abs(o.judgement - yb) < 1e-3f) {
                    taken = true;
                    yb += 0.125f;
                    break;
                }
            }
        }
        Doc.DNote y = new Doc.DNote(NoteType.TAP, Line.LOWER, 2, yb);
        y.move.add(new Doc.Ev(yb - 2f, yb, Doc.SPAWN, 0f, 1f));   // a default fall made absolute: saved "rel": false
        e.s.doc.notes.add(y);
        e.s.changed(false);
        log("retime +1: judgement " + x.judgement + ", absolute movement " + x.move.get(0).start + "-"
            + x.move.get(0).end + " (expect 24-26), relative rotation " + x.rot.get(0).start + "-" + x.rot.get(0).end
            + " (expect 29-31)");

        // paste works like retime: absolute events keep their beats (Kelvin 15:25); undone again afterwards
        java.util.ArrayList<Integer> keep = new java.util.ArrayList<>(e.s.sel);
        float back = e.beat;
        e.s.select(x, false);
        e.copy(false);
        e.beat = 50f;
        e.paste();
        Doc.DNote p = e.s.selected().get(0);
        log("paste at 50: judgement " + p.judgement + ", absolute movement " + p.move.get(0).start + "-"
            + p.move.get(0).end + " (expect 24-26), relative rotation " + p.rot.get(0).start + "-" + p.rot.get(0).end
            + " (expect 48-50)");
        e.undo();
        e.s.sel.clear();
        e.s.sel.addAll(keep);
        e.beat = back;
    }

    /** A LANE MINE whose red column grows after it lands: the bottom stays on the line. */
    private void laneMine(EditorScreen e) {
        Doc.DNote m = new Doc.DNote(NoteType.MINE, Line.LOWER, 2, 40.5f);
        m.duration = 2f;
        m.height = 300f;
        m.approach(2f, Doc.SPAWN, 1f);
        m.heightEv.add(new Doc.Ev(40.5f, 42.5f, 300f, 600f, 1f));
        e.s.mark();
        e.s.doc.notes.add(m);
        e.s.changed(false);
        e.s.select(m, false);
        e.insp.tab = Inspector.NOTE;
        Note g = e.s.note(m);
        log(g == null ? "lane mine: no game note"
            : "lane mine: position at 41 " + g.positionAt(41f) + ", 42 " + g.positionAt(42f) + ", red length at 40.5 "
            + g.tailHeightAt(40.5f) + ", 41.5 " + g.tailHeightAt(41.5f) + ", 42.4 " + g.tailHeightAt(42.4f));
        // a normal mine in its block is fine (Kelvin 10-22); a tap there is still a conflict (a WARN, like the game)
        Checks c = new Checks();
        c.update(e.s);
        int problems = c.problems;
        Doc.DNote mi = new Doc.DNote(NoteType.MINE, Line.LOWER, 2, 41.25f),
            tp = new Doc.DNote(NoteType.TAP, Line.LOWER, 2, 41.75f);
        mi.approach(2f, Doc.SPAWN, 1f);
        tp.approach(2f, Doc.SPAWN, 1f);
        e.s.doc.notes.add(mi);
        e.s.changed(false);
        c = new Checks();
        c.update(e.s);
        log("mine in the block: " + checkFor(mi.uid) + ", problems " + problems + " -> " + c.problems
            + " (expect none, unchanged)");
        e.s.doc.notes.add(tp);
        e.s.changed(false);
        log("tap in the block: " + checkFor(tp.uid) + " (expect 轨道水雷封锁冲突)");
        e.s.doc.notes.remove(tp);
        e.s.doc.notes.remove(mi);
        e.s.changed(false);
        // stacked mines and a mine on a tap's spot all load (game fdc9922, Kelvin 10-22: 「地雷的限制不用那么多」)
        Doc.DNote m1 = new Doc.DNote(NoteType.MINE, Line.LOWER, 3, 44.03125f),
            m2 = new Doc.DNote(NoteType.MINE, Line.LOWER, 3, 44.03125f);
        Doc.DNote t3 = new Doc.DNote(NoteType.TAP, Line.LOWER, 3, 44.03125f);
        for (Doc.DNote x : new Doc.DNote[]{m1, m2, t3}) {
            x.approach(2f, Doc.SPAWN, 1f);
            e.s.doc.notes.add(x);
        }
        e.s.changed(false);
        log("stacked mines: built " + (e.s.note(m1) != null) + ", " + (e.s.note(m2) != null) + ", tap "
            + (e.s.note(t3) != null) + ", checks " + checkFor(m1.uid) + " / " + checkFor(m2.uid)
            + " (expect true, true, tap true, checks none / none)");
        e.s.doc.notes.remove(m1);
        e.s.doc.notes.remove(m2);
        e.s.doc.notes.remove(t3);
        e.s.changed(false);
        // two taps on one lane and beat both load and get an INFO (game 7.166, Kelvin 2026-11-02)
        Doc.DNote s1 = new Doc.DNote(NoteType.TAP, Line.LOWER, 3, 44.03125f),
            s2 = new Doc.DNote(NoteType.TAP, Line.LOWER, 3, 44.03125f);
        for (Doc.DNote x : new Doc.DNote[]{s1, s2}) {
            x.approach(2f, Doc.SPAWN, 1f);
            e.s.doc.notes.add(x);
        }
        e.s.changed(false);
        log("stacked taps: built " + (e.s.note(s1) != null) + ", " + (e.s.note(s2) != null) + ", "
            + (e.s.note(s1) != e.s.note(s2)) + ", check " + checkFor(s2.uid)
            + " (expect true, true, true, check 同轨同拍叠着的音符 1 组（每个要一根手指）)");
        e.s.doc.notes.remove(s1);
        e.s.doc.notes.remove(s2);
        e.s.changed(false);
    }

    private String checkFor(int uid) {
        Checks c = new Checks();
        c.update(ed().s);
        for (Checks.Item i : c.items) if (i.uids.contains(uid)) return i.text;
        return "none";
    }

    /**
     * 整理轨道 on marsh mally (all 10 lanes have notes): pan 300 puts −5 on screen, so a zero-width
     * −6 must stay (as a default lane it would show), while an unused +6 and a 100-wide −9 off
     * screen go.
     */
    private void tidySetup(EditorScreen e) {
        java.util.TreeSet<Integer> kept = new java.util.TreeSet<>();
        log("tidy before: " + Inspector.lanesToTidy(e.s.doc, kept) + " kept " + kept + " (expect [] kept [])");
        e.s.mark();
        Doc d = e.s.doc;
        d.cameraFrame = 300f;
        d.ensureLane(-6);
        d.lane(-6).width = 0f;
        d.ensureLane(6);
        d.ensureLane(-9);
        d.lane(-9).width = 100f;
        e.s.changed(false);
        kept.clear();
        log("tidy: " + Inspector.lanesToTidy(d, kept) + " kept " + kept + " (expect [-9, 6] kept [-6])");
        lanesBefore = d.lanes.size();
        e.insp.tidyLanes(e.s, d);
    }

    private void tidyCheck(EditorScreen e) {
        log("tidied: lanes " + (e.s.doc.lanes.size() - lanesBefore) + " (expect -2), -6 still declared "
            + (e.s.doc.lane(-6) != null));
        e.undo();
        log("undo tidy: lanes " + (e.s.doc.lanes.size() - lanesBefore) + " (expect 0)");
        e.undo();
        log("undo setup: lanes " + e.s.doc.lanes.size() + " (expect 10), camera " + e.s.doc.cameraFrame
            + " (expect 0.0)");
    }

    /** Two trail segments and two glitch segments on the selected note; the save at the end checks they survive. */
    private void segments(EditorScreen e, Doc.DNote n) {
        e.s.mark();
        float j = n.judgement;
        n.trails.clear();
        Doc.DTrail a = new Doc.DTrail();                             // two groups of copies above the note in the shot
        a.length = 1.5f;
        a.density = 12f;
        a.fade = 0.3f;
        a.from = j - 1.5f;
        a.to = j - 1.125f;
        Doc.DTrail b = new Doc.DTrail();
        b.length = 1f;
        b.density = 24f;
        b.from = j - 0.875f;
        b.to = j - 0.625f;
        n.trails.add(a);
        n.trails.add(b);
        n.glitches.clear();
        Doc.DGlitch x = new Doc.DGlitch();
        x.from = j - 1f;                                             // listed out of order on purpose
        x.to = j;
        x.slice = 0f;
        Doc.DGlitch y = new Doc.DGlitch();
        y.from = j - 2f;
        y.to = j - 1f;
        y.intensity = 0f;
        y.intensityEnd = 1f;
        y.exponent = 2f;
        n.glitches.add(x);
        n.glitches.add(y);
        e.s.changed(false);
        String json = e.s.doc.toJson();
        log("segments: trail array " + json.contains("\"trail\": [ {") + ", glitch array "
            + json.contains("\"glitch\": [ {") + ", glitches sorted "
            + (json.indexOf("\"from\": " + Doc.num(j - 2f) + ", \"to\": " + Doc.num(j - 1f)
            + ", \"intensity\"") < json.indexOf("\"from\": " + Doc.num(j - 1f) + ", \"to\": " + Doc.num(j)
            + ", \"intensity\"")) + " (expect true true true)");
        n.sortGlitches();                                            // shown in order, as after loading
    }

    /**
     * 统一流速 (Kelvin 2026-10-15): a flow BPM at half the tempo doubles a default fall in beats and
     * halves a suggested tail per beat; the falls follow flow BPM edits and moved notes.
     */
    private void flowFalls(EditorScreen e) {
        Doc d = e.s.doc;
        Doc.DNote n = null, h = null;
        for (Doc.DNote x : d.notes) {
            if (x.move.size() != 1 || !x.defaultFall(x.move.get(0))) continue;
            if (n == null && !x.sustained()) n = x;
            if (h == null && x.type == NoteType.HOLD && d.autoTail(x)) h = x;
        }
        if (n == null) {
            log("flow: no default fall found");
            return;
        }
        flowUid = n.uid;
        float bpm = d.bpmAt(n.judgement);
        e.s.mark();
        d.flows.clear();
        d.flows.add(new Doc.Seg(0f, bpm / 2f));
        int k = d.setFallBy(true);
        e.s.changed(false);
        log("统一流速 at half the BPM: " + k + " notes, a fall " + (n.judgement - n.move.get(0).start) + " beats (expect 4)"
            + (h == null ? ", no hold"
            : ", a hold's tail per beat " + h.height / h.duration + " (expect " + Doc.APPROACH / 2f + ")"));
        d.flows.get(0).bpm = bpm;
        e.s.changed(false);
        log("flow BPM = BPM: the fall " + (n.judgement - n.move.get(0).start) + " beats (expect 2)"
            + (h == null ? "" : ", tail per beat " + h.height / h.duration + " (expect " + Doc.APPROACH + ")"));
        d.flows.get(0).bpm = bpm / 2f;
        n.retime(1f);
        e.s.changed(false);
        log("moved a beat later: the fall " + (n.judgement - n.move.get(0).start) + " beats (expect 4)");
    }

    /** Save → reload keeps the flow keys; back to 统一拍数 restores 2-beat falls. */
    private void flowBack(EditorScreen e) {
        Doc d = e.s.doc;
        try {
            FileHandle f = Gdx.files.absolute(dir).child("flow_roundtrip.json");
            f.writeString(d.toJson(), false, "UTF-8");
            Doc back = Doc.fromChart(ChartMapper.read(f, null, null));
            Doc.readRelFlags(back, f);
            int falls = 0;
            for (Doc.DNote x : back.notes) for (Doc.Ev v : x.move) if (!Float.isNaN(v.flow)) falls++;
            log("flow round trip: identical json " + d.toJson().equals(back.toJson()) + ", 统一流速 " + back.flowFall + ", "
                + back.flows.size() + " flow BPM, " + falls + " flow falls, re-aligned on reload " + back.reflow()
                + " (expect true, true, 1, > 0, 0)");
        } catch (Exception ex) {
            log("flow round trip failed: " + ex);
        }
        Doc.DNote n = d.byUid(flowUid);
        e.s.mark();
        int k = d.setFallBy(false);
        e.s.changed(false);
        log("统一拍数 again: " + k + " notes"
            + (n == null ? "" : ", the fall " + (n.judgement - n.move.get(0).start) + " beats (expect 2)"));
    }

    /**
     * 插入0号轨 (Kelvin 10-15): lane 0 between −1 and +1 from the playhead; save → reload keeps it; a note before it is
     * flagged.
     */
    private void laneInsert(EditorScreen e) {
        Doc d = e.s.doc;
        e.insp.tab = Inspector.FIELD;
        java.util.Arrays.fill(e.insp.scroll, 0f);
        e.s.lanePick.clear();
        float b0 = e.snap(e.beat);
        e.insp.insertLane(e.s, d, -1, 1);
        Doc.DLane l = d.lane(0);
        if (l == null || l.insert == null) {
            log("插入0号轨: no lane 0");
            return;
        }
        LaneLayout lay = new LaneLayout(e.s.chart);
        lay.update(l.insert.start - 0.5f, LaneLayout.VIEW_LEFT, LaneLayout.VIEW_RIGHT);
        float w0 = lay.width(lay.indexOf(0)), z0 = lay.squeeze();
        lay.update(l.insert.end + 1f, LaneLayout.VIEW_LEFT, LaneLayout.VIEW_RIGHT);
        log("插入0号轨: insert " + l.insert.start + ".." + l.insert.end + " (expect " + b0 + ".." + (b0 + 1f) + "), width "
            + l.width + "; before it lane 0 " + w0 + ", squeeze " + z0 + "; after it lane 0 "
            + lay.width(lay.indexOf(0)) + ", lane +1 " + lay.width(lay.indexOf(1)) + ", squeeze " + lay.squeeze()
            + " (expect 0, 1; equal, < 1)");
        try {
            FileHandle f = Gdx.files.absolute(dir).child("laneins_roundtrip.json");
            f.writeString(d.toJson(), false, "UTF-8");
            Doc back = Doc.fromChart(ChartMapper.read(f, null, null));
            Doc.readRelFlags(back, f);
            log("lane insert round trip: identical json " + d.toJson().equals(back.toJson()) + ", lane 0 inserted "
                + (back.lane(0) != null && back.lane(0).insert != null) + " (expect true, true)");
        } catch (Exception ex) {
            log("lane insert round trip failed: " + ex);
        }
        Doc.DNote early = new Doc.DNote(NoteType.TAP, Line.LOWER, 0, l.insert.start - 1f);
        early.approach(2f, Doc.SPAWN, 1f);
        d.notes.add(early);
        e.s.changed(false);
        log("note before the insertion: " + checkFor(early.uid) + " (expect 轨道插入前)");
        d.notes.remove(early);
        e.s.changed(false);
        e.seekBeat(l.insert.end + 1f);
    }

    /**
     * 删除轨道 (Kelvin 10-17): lane 0, inserted earlier, deleted from beat 16 within 1 beat gives all its room back (lane
     * +1 as wide as in the same chart without lane 0); the deletion survives save → reload; a note after it is flagged.
     */
    private void laneDelete(EditorScreen e) {
        Doc d = e.s.doc;
        e.insp.tab = Inspector.FIELD;
        e.s.lanePick.clear();
        if (d.lane(0) == null) e.insp.insertLane(e.s, d, -1, 1);
        Doc.DLane l = d.lane(0);
        e.s.laneSel = 0;
        e.insp.deleteLane(e.s, l, 16f);
        float without = Float.NaN;
        Doc x = d.copy();
        x.lanes.remove(x.lane(0));
        x.notes.removeIf(n -> n.inLane() && n.lane == 0);
        try {
            LaneLayout w = new LaneLayout(ChartMapper.read(new java.io.ByteArrayInputStream(
                x.toJson().getBytes(java.nio.charset.StandardCharsets.UTF_8)), "editor"));
            w.update(18f, LaneLayout.VIEW_LEFT, LaneLayout.VIEW_RIGHT);
            without = w.width(w.indexOf(1));
        } catch (Exception ex) {
            log("chart without lane 0 failed: " + ex);
        }
        LaneLayout lay = new LaneLayout(e.s.chart);
        lay.update(16.5f, LaneLayout.VIEW_LEFT, LaneLayout.VIEW_RIGHT);
        float half = lay.width(lay.indexOf(0));
        lay.update(18f, LaneLayout.VIEW_LEFT, LaneLayout.VIEW_RIGHT);
        log("删除轨道: delete " + l.delete.start + ".." + l.delete.end + " from " + l.delete.from + " to " + l.delete.to
            + " (expect 16..17, lane 0's width, 0); halfway lane 0 " + half + "; after it lane 0 "
            + lay.width(lay.indexOf(0)) + ", lane +1 " + lay.width(lay.indexOf(1)) + " (without lane 0 at all "
            + without + ", expect 0 and equal)");
        try {
            String json = d.toJson();
            FileHandle f = Gdx.files.absolute(dir).child("lanedel_roundtrip.json");
            f.writeString(json, false, "UTF-8");
            Doc back = Doc.fromChart(ChartMapper.read(f, null, null));
            Doc.readRelFlags(back, f);
            log("lane delete round trip: identical json " + json.equals(back.toJson()) + ", written "
                + json.contains("\"delete\": { \"startTime\": 16, \"endTime\": 17, ") + " (expect true, true)");
        } catch (Exception ex) {
            log("lane delete round trip failed: " + ex);
        }
        Doc.DNote late = new Doc.DNote(NoteType.TAP, Line.LOWER, 0, 18f);
        late.approach(2f, Doc.SPAWN, 1f);
        d.notes.add(late);
        e.s.changed(false);
        log("note after the deletion: " + checkFor(late.uid) + " (expect 轨道删除后)");
        d.notes.remove(late);
        e.s.changed(false);
        e.insp.scroll[Inspector.FIELD] = 760f;
        e.seekBeat(16.5f);
    }

    /**
     * Inserting between +2 and +3 moves +3 and further out (with notes) one id out; removing it moves them back; undo /
     * redo.
     */
    private void laneBetween(EditorScreen e) {
        Doc d = e.s.doc;
        Doc.DNote n = new Doc.DNote(NoteType.TAP, Line.LOWER, 3, 12f);
        n.approach(2f, Doc.SPAWN, 1f);
        e.s.mark();
        d.notes.add(n);
        e.s.changed(false);
        int far = 0;
        for (Doc.DLane l : d.lanes) far = Math.max(far, l.id);
        e.s.lanePick.clear();
        e.s.lanePick.add(2);
        e.s.lanePick.add(3);
        log("+2 and +3 adjacent " + d.adjacent(2, 3) + ", -1 and +1 adjacent " + d.adjacent(-1, 1)
            + " (expect true, false: lane 0 is there)");
        e.insp.insertLane(e.s, d, 2, 3);
        int far2 = 0;
        for (Doc.DLane l : d.lanes) far2 = Math.max(far2, l.id);
        log("between +2 and +3: new lane " + e.s.laneSel + " inserted "
            + (d.lane(3) != null && d.lane(3).insert != null) + ", the note now on " + n.lane + ", outermost " + far
            + " -> " + far2 + " (expect 3, true, 4, +1)");
        e.insp.removeLane(e.s, d, 3);
        log("removed: the note back on " + n.lane + ", lane 3 inserted "
            + (d.lane(3) != null && d.lane(3).insert != null) + " (expect 3, false)");
        e.s.undo();
        d = e.s.doc;
        Doc.DNote u = d.byUid(n.uid);
        log("undo: lane 3 inserted " + (d.lane(3) != null && d.lane(3).insert != null) + ", the note on "
            + (u == null ? "?" : u.lane) + " (expect true, 4)");
        e.s.lanePick.clear();
        e.s.laneSel = 3;
    }

    /**
     * 旋转中心 (Kelvin 10-16): a turned note's pivot reaches the preview chart and survives save → reload; only a
     * non-origin pivot is written.
     */
    private void pivotTest(EditorScreen e) {
        Doc d = e.s.doc;
        Doc.DNote n = new Doc.DNote(NoteType.TAP, Line.LOWER, -2, 14f);
        n.approach(2f, Doc.SPAWN, 1f);
        n.rot.add(new Doc.Ev(12f, 14f, 0f, 60f, 1f));
        n.pivotY = -20f;
        e.s.mark();
        d.notes.add(n);
        e.s.changed(false);
        e.s.select(n, false);
        e.insp.tab = Inspector.NOTE;
        e.insp.scroll[Inspector.NOTE] = 420f;
        Note g = e.s.note(n);
        String json = d.toJson();
        log("pivot: preview note " + (g == null ? "missing" : g.pivotX() + ", " + g.pivotY()) + ", written "
            + (json.split("\"pivot\"", -1).length - 1) + " time(s) as [0, -20] " + json.contains("\"pivot\": [0, -20]")
            + " (expect 0.0, -20.0, 1, true)");
        try {
            FileHandle f = Gdx.files.absolute(dir).child("pivot_roundtrip.json");
            f.writeString(json, false, "UTF-8");
            Doc back = Doc.fromChart(ChartMapper.read(f, null, null));
            Doc.readRelFlags(back, f);
            log("pivot round trip: identical json " + json.equals(back.toJson()) + " (expect true)");
        } catch (Exception ex) {
            log("pivot round trip failed: " + ex);
        }
        mirrorTest(e, n);
        e.seekBeat(13.4f);
    }

    /**
     * 左右镜像 / 上下镜像 (Kelvin 2026-10-22): sky ↔ ground keeps lane and beat and turns the other way about the pivot
     * mirrored in x, so the game puts the head exactly mirrored (its own pivot shift); each mirror twice is no change.
     */
    private void mirrorTest(EditorScreen e, Doc.DNote n) {
        n.pivotX = 12f;
        float x0 = NoteShapes.pivotShiftX(0.5f, 40f, 40f, 60f, false, n.pivotX, n.pivotY);
        float y0 = NoteShapes.pivotShiftY(0.5f, 40f, 40f, 60f, false, n.pivotX, n.pivotY);
        e.mirrorUpDown();
        float a = n.rot.get(0).to;
        float x1 = NoteShapes.pivotShiftX(0.5f, 40f, 40f, a + 180f, true, n.pivotX, n.pivotY);
        float y1 = NoteShapes.pivotShiftY(0.5f, 40f, 40f, a + 180f, true, n.pivotX, n.pivotY);
        log("mirror up-down: " + n.line + " lane " + n.lane + ", angle " + a + ", pivot " + n.pivotX + ", " + n.pivotY
            + ", head x same " + (Math.abs(x1 - x0) < 1e-3f) + ", y mirrored " + (Math.abs(y1 + y0) < 1e-3f)
            + " (expect UPPER lane -2, -60.0, -12.0, -20.0, true, true)");
        int lane = n.lane;
        e.mirror();
        log("mirror left-right: lane " + lane + " -> " + n.lane + ", angle " + n.rot.get(0).to + ", pivot x " + n.pivotX
            + " (expect the mirrored lane, 60.0, 12.0)");
        e.mirror();
        e.mirrorUpDown();
        n.pivotX = 0f;
        log("each twice: " + n.line + " lane " + n.lane + ", angle " + n.rot.get(0).to
            + " (expect LOWER lane -2, 60.0)");
    }

    /**
     * 1920 × 1200 with the lines at 1080 / 120 (Kelvin 2026-10-19): a v2 file opens with its y values × 10/9 (the
     * default fall, a tail, a pivot) and a v2 or v3 file with its lines on 1080 / 120; both save in the current format,
     * which reads back unchanged; a new chart has the game's lines; the preview maps a line to its place in the 16 : 10
     * frame and back.
     */
    private void frame1200(EditorScreen e) {
        Doc a = Doc.blank("frame", "", "", "", 120f, 0f);
        log("new chart lines: upper " + a.upper + ", lower " + a.lower + " (expect 1080.0, 120.0)");
        int now = Doc.TEXT_FORMAT;
        // format, upper and lower line, default fall and pivot y as that format wrote them
        float[][] old = {{2f, 960f, 120f, 972f, -20f}, {3f, 3200f / 3f, 400f / 3f, 1080f, -200f / 9f}};
        for (float[] o : old) {
            int v = (int) o[0];
            a.upper = o[1];
            a.lower = o[2];
            a.notes.clear();
            Doc.DNote n = new Doc.DNote(NoteType.TAP, Line.LOWER, 1, 4f);
            n.approach(2f, o[3], 1f);
            n.pivotY = o[4];
            Doc.DNote h = new Doc.DNote(NoteType.HOLD, Line.UPPER, -1, 6f);
            h.approach(2f, o[3], 1f);
            h.duration = 2f;
            h.height = o[3];
            a.notes.add(n);
            a.notes.add(h);
            String json = a.toJson().replace("\"formatVersion\": " + now, "\"formatVersion\": " + v);
            try {
                FileHandle f = Gdx.files.absolute(dir).child("frame_v" + v + ".json");
                f.writeString(json, false, "UTF-8");
                Chart c = ChartMapper.read(f, null, null);
                Doc b = Doc.fromChart(c);
                Doc.readRelFlags(b, f);
                Doc.DNote bn = b.notes.get(0).type == NoteType.TAP ? b.notes.get(0) : b.notes.get(1);
                Doc.DNote bh = bn == b.notes.get(0) ? b.notes.get(1) : b.notes.get(0);
                log("v" + v + " → v" + now + ": read as v" + c.sourceFormat() + ", lines " + b.upper + " / " + b.lower
                    + ", fall from " + bn.move.get(0).from + " relative " + bn.move.get(0).rel + ", pivot y "
                    + bn.pivotY + ", tail " + bh.height + " (expect " + v
                    + ", 1080.0 / 120.0, 1080.0 true, -22.222221, 1080.0)");
                String saved = b.toJson();
                FileHandle g = Gdx.files.absolute(dir).child("frame_v" + v + "_saved.json");
                g.writeString(saved, false, "UTF-8");
                Doc back = Doc.fromChart(ChartMapper.read(g, null, null));
                Doc.readRelFlags(back, g);
                log("v" + v + " saved: writes v" + now + " " + saved.contains("\"formatVersion\": " + now + ",")
                    + ", reads back identical " + saved.equals(back.toJson()) + " (expect true, true)");
            } catch (Exception ex) {
                log("v" + v + " → v" + now + " failed: " + ex);
            }
        }
        float ly = EditorScreen.uy(Field.y(e.s.doc.lower));
        log("preview: lower line " + e.s.doc.lower + " at y " + ly + " (expect "
            + (EditorScreen.PY + (Field.REF_H - e.s.doc.lower) * EditorScreen.K) + "), y 500 back to "
            + Field.refY(EditorScreen.ryOf(EditorScreen.uy(Field.y(500f)))) + " (expect 500)");
    }

    /** The preview's game chart got both lists (the glitch then draws through NoteRenderer). */
    private void segmentsBuilt(EditorScreen e, Doc.DNote n) {
        e.s.rebuild();
        for (Note g : e.s.chart.notes()) {
            if (g.judgement() != n.judgement || g.laneId() != n.lane || g.line() != n.line) continue;
            log("preview note: " + g.trails().size() + " trail segments, " + g.glitches().size()
                + " glitch segments, first glitch from " + g.glitches().get(0).from() + " intensity at its middle "
                + g.glitches().get(0).intensityAt(n.judgement - 1.5f) + " (expect 2, 2, "
                + (n.judgement - 2f) + ", 0.25)");
            return;
        }
        log("preview note not found");
    }

    /** Kelvin's Aleph-0 Surface (when the test copy has it): where its notes are just before their judgement. */
    private void alephSurface() {
        com.badlogic.gdx.files.FileHandle f = Gdx.files.absolute(System.getProperty("kk.gameDir", ""))
            .child("assets/Charts/Aleph-0/Surface.json");
        if (!f.exists()) return;
        try {
            Chart c = ChartMapper.read(f, null, null);
            for (Note n : c.notes()) {
                float j = n.judgement();
                log("Aleph-0 Surface: lane " + n.laneId() + " beat " + j + " default " + n.usesDefaultApproach()
                    + ", position at j-4 " + n.positionAt(j - 4f) + ", j-1 " + n.positionAt(j - 1f) + ", j "
                    + n.positionAt(j));
            }
            Doc d = Doc.fromChart(c);
            Doc.readRelFlags(d, f);
            StringBuilder m = new StringBuilder();
            for (Doc.DNote n : d.notes) {
                for (Doc.Ev v : n.move) {
                    m.append(' ').append(n.lane).append('@').append(n.judgement).append(v.rel ? " rel" : " abs");
                }
            }
            log("Aleph-0 Surface in the editor, movement:" + m);
            java.util.TreeSet<Integer> kept = new java.util.TreeSet<>();
            log("Aleph-0 Surface 整理轨道: " + Inspector.lanesToTidy(d, kept) + " kept " + kept);
        } catch (Exception ex) {
            log("Aleph-0 Surface failed: " + ex);
        }
    }

    /** 波动速度 and 横移 (Kelvin 2026-10-28): reach the preview, the v5 file and the editor again. */
    private void waveSpeed(Doc doc, String name) throws java.io.IOException {
        Doc d = Doc.fromChart(ChartMapper.read(new java.io.ByteArrayInputStream(doc.toJson().getBytes("UTF-8")), name));
        Doc.DNote tide = null;
        for (Doc.DNote n : d.notes) if (n.type == NoteType.UNIVERSAL && tide == null) tide = n;
        if (tide == null) {
            tide = new Doc.DNote(NoteType.UNIVERSAL, Line.LOWER, 0, 8f);
            tide.move.add(new Doc.Ev(6f, 8f, Doc.SPAWN, 0f, 1f));
            d.notes.add(tide);
        }
        tide.waveSpeed = 0.5f;
        Doc.DNote key = null;
        for (Doc.DNote n : d.notes) if (n.inLane() && key == null) key = n;
        if (key != null) {
            key.offsetX = -30f;
            Doc.Ev slide = new Doc.Ev(key.judgement - 2f, key.judgement, -30f, 30f, 1f);
            slide.rel = true;
            key.offEv.add(slide);
            key.orbit = true;
            key.orbitX = 800f;
            key.orbitY = 500f;
            Doc.Ev move = new Doc.Ev(key.judgement - 2f, key.judgement - 1f, 800f, 900f, 1f);
            move.rel = true;
            key.orbXEv.add(move);
            key.orbitRadius = -40f;
            Doc.Ev turn = new Doc.Ev(key.judgement - 2f, key.judgement, 0f, 90f, 1f);
            turn.rel = true;
            key.orbAngEv.add(turn);
            key.orbitConflict = org.kelvinizer.elements.NoteOrbit.CONFLICT_MOVE;
        }
        byte[] v4 = d.toJson().getBytes("UTF-8");
        Chart preview = ChartMapper.read(new java.io.ByteArrayInputStream(v4), name);
        float inPreview = 0f;
        float offPreview = 0f, offBack = 0f, slidePreview = Float.NaN;
        String slideBack = "none", orbitPreview = "none", orbitBack = "none";
        for (Note n : preview.notes()) {
            inPreview = Math.max(inPreview, n.waveSpeed());
            offPreview = Math.min(offPreview, n.offsetX());
            if (!n.offsetXEvents().isEmpty()) slidePreview = n.offsetXAt(n.judgement() - 1f);
            if (n.orbit() != null) {
                orbitPreview = n.orbit().xAt(n.judgement()) + ", " + n.orbit().yAt(n.judgement()) + ", radius "
                    + n.orbit().radiusAt(n.judgement()) + ", angle " + n.orbit().angleAt(n.judgement()) + ", conflict "
                    + n.orbit().conflict();
            }
        }
        org.kelvinizer.elements.ChartUpgrade.Result r = org.kelvinizer.elements.ChartUpgrade.upgrade(name, v4);
        Doc back = Doc.fromChart(ChartMapper.read(new java.io.ByteArrayInputStream(r.json.getBytes("UTF-8")), name,
            org.kelvinizer.elements.SongInfo.of(preview)));
        // the editor's rel flags come from the file, as when Kelvin reopens it
        com.badlogic.gdx.files.FileHandle tmp = new com.badlogic.gdx.files.FileHandle(
            java.io.File.createTempFile("kk-wave", ".json"));
        tmp.writeString(r.json, false, "UTF-8");
        Doc.readRelFlags(back, tmp);
        tmp.delete();
        float inBack = 0f;
        for (Doc.DNote n : back.notes) {
            inBack = Math.max(inBack, n.waveSpeed);
            offBack = Math.min(offBack, n.offsetX);
            for (Doc.Ev v : n.offEv) slideBack = Im.fmt(v.from) + " to " + Im.fmt(v.to) + (v.rel ? " rel" : " abs");
            for (Doc.Ev v : n.orbXEv) {
                orbitBack = (n.orbit ? "orbit " : "no orbit ") + Im.fmt(n.orbitX) + ", " + Im.fmt(n.orbitY) + ", x "
                    + Im.fmt(v.from) + " to " + Im.fmt(v.to) + (v.rel ? " rel" : " abs") + ", radius "
                    + Im.fmt(n.orbitRadius) + ", angle " + n.orbAngEv.size() + " event"
                    + (n.orbAngEv.size() == 1 && n.orbAngEv.get(0).rel ? " rel" : "") + ", conflict " + n.orbitConflict;
            }
        }
        log("wave speed: preview " + inPreview + ", v5 " + r.json.matches("(?s).*\"waveSpeed\"\\s*:\\s*0\\.5.*")
            + ", read back " + inBack + ", problems " + r.problems + " (expect 0.5, true, 0.5, [])");
        log("offset x: preview " + offPreview + ", v5 " + r.json.matches("(?s).*\"offsetX\"\\s*:\\s*-30.*")
            + ", read back " + offBack + " (expect -30.0, true, -30.0)");
        log("offset x events: preview halfway " + slidePreview + ", read back " + slideBack
            + " (expect 0.0, -30 to 30 rel)");
        log("orbit: preview at the judgement " + orbitPreview + ", read back " + orbitBack
            + " (expect 900.0, 500.0, radius -40.0, angle 90.0, conflict 2, orbit 800, 500, x 800 to 900 rel,"
            + " radius -40, angle 1 event rel, conflict 2)");
    }

    private void roundTrip(EditorScreen e) {
        try {
            Chart c = ChartMapper.read(e.s.file, null, null);
            Doc back = Doc.fromChart(c);
            Doc.readRelFlags(back, e.s.file);
            String a = e.s.doc.toJson(), b = back.toJson();
            log("saved " + e.s.file.name() + ": " + e.s.doc.notes.size() + " notes, read back " + c.noteCount()
                + " (format v" + c.sourceFormat() + "), identical json: " + a.equals(b));
            // v5 (Kelvin 2026-10-27): the song values went to song.json, the editor's keys under "editor"
            String text = e.s.file.readString("UTF-8");
            org.kelvinizer.elements.SongInfo song = org.kelvinizer.elements.SongInfo.besides(e.s.file);
            log("v5 file: format 5 " + text.matches("(?s)\\{\\s*\"format\"\\s*:\\s*5,.*") + ", no title "
                + !text.contains("\"title\"") + ", editor " + text.matches("(?s).*\"editor\"\\s*:\\s*\\{.*")
                + ", song.json bpm "
                + (song != null && song.bpm == e.s.doc.bpms.get(0).bpm) + " (expect true, true, true, true)");
            waveSpeed(e.s.doc, e.s.file.name());
            if (!a.equals(b)) {
                int i = 0;
                while (i < Math.min(a.length(), b.length()) && a.charAt(i) == b.charAt(i)) i++;
                log("first difference at " + i + ": " + a.substring(Math.max(0, i - 80), Math.min(a.length(), i + 80))
                    + "\n vs " + b.substring(Math.max(0, i - 80), Math.min(b.length(), i + 80)));
            }
        } catch (Exception ex) {
            log("round trip failed: " + ex);
        }
    }

    // ================================================================== tablet layout (Kelvin 2026-10-25)

    /** Taps canvas point (x, y) of the tablet's 1344 × 840 canvas. */
    private void tap(float x, float y) {
        float k = Gdx.graphics.getWidth() / Gfx.W;
        click(Math.round(x * k), Math.round(y * k));
    }

    private float noteBeat = Float.NaN;

    /** The tablet script: library, crop dialog, editor, the system text box's answer, touch bar, zoom, stats, 实机. */
    private void tabletBefore() {
        EditorScreen e = ed();
        switch (frame) {
            case 2: app.im.textBox = true; break;           // fields as on Android (the answer comes from answer())
            case 6: cropOpen(); break;
            case 9: app.im.keyDown(Input.Keys.ESCAPE); break;
            case 12: tap(300f, 72f + 86f + 39f); break;      // second song row
            case 16: tap(843f, 773f); break;                 // 编辑 <DIFF>
            case 40:
                if (e != null) e.seekBeat(40f);
                break;
            case 42:
                if (e != null) {
                    selectNear(e);
                    e.insp.tab = Inspector.NOTE;
                }
                break;
            case 46:
                if (e != null && e.s.single() != null) {
                    noteBeat = e.s.single().judgement;
                    app.im.answer("n-j", Im.fmt(noteBeat + 0.25f));
                }
                break;
            case 48:
                if (e != null && e.s.single() != null) log("tablet text box: 判定拍 " + Im.fmt(noteBeat) + " → "
                    + Im.fmt(e.s.single().judgement) + " (expect +0.25)");
                break;
            case 50: tap(72f + 138f + 248f + 28f, 559f); break;      // 多选
            case 53: tap(102f, 559f); break;                          // 撤销
            case 56:
                if (e != null && e.s.single() != null) log("tablet 撤销: 判定拍 " + Im.fmt(e.s.single().judgement)
                    + " (expect " + Im.fmt(noteBeat) + ")");
                if (e != null) log("tablet zoom before: " + Im.fmt((float) e.tlSpan) + " beats");
                tap(117f, EditorScreen.R_RULER + 17f);                // + (zoom in)
                break;
            case 59:
                if (e != null) log("tablet zoom after +: " + Im.fmt((float) e.tlSpan) + " beats (expect 12.8)");
                break;
            case 62: tap(EditorScreen.TR - 38f, 559f); break;         // 统计
            case 65:
                if (e != null) log("tablet stats open " + e.statsOpen + " (expect true)");
                break;
            case 66:                                                  // 实机
                if (e != null) e.statsOpen = false;
                tap(EditorScreen.PX + EditorScreen.PW - 254f + 183f, EditorScreen.PY + 24f);
                break;
            case 69:
                if (e != null) {
                    liveRun(e, 38f, 44f);
                    log("tablet 实机: marks off " + !e.overlays + ", combo above 0 "
                        + (e.pv.engine().scores().combo() > 0) + " (expect true, true)");
                }
                break;
            default:
        }
    }

    private void tabletAfter() {
        switch (frame) {
            case 5: shot("t1_library"); break;
            case 8: shot("t0_crop"); break;
            case 15: shot("t2_selected"); break;
            case 45: shot("t3_editor"); break;
            case 52: shot("t4_multi"); break;
            case 64: shot("t5_stats"); break;
            case 69: shot("t6_live"); break;
            case 72:
                Gdx.files.absolute(dir).child("autotest.log").writeString(log.toString(), false, "UTF-8");
                Gdx.app.exit();
                break;
            default:
        }
    }

    /** After the frame (before the buffer swap): screenshots, exit. */
    void after() {
        if (Gfx.TABLET) {
            tabletAfter();
            return;
        }
        switch (frame) {
            case 8: shot("c0_crop_open"); break;
            case 15: shot("c0_crop"); break;
            case 23: shot("c0_crop_round"); break;
            case 27: shot("c0_crop_saved"); break;
            case 40: shot("c1_library"); break;
            case 50: shot("c1_library_selected"); break;
            case 92: shot("c2_stats"); break;
            case 97: shot("c2_tidy"); break;
            case 104: shot("c3_glitch"); break;
            case 100: shot("c2_editor"); break;
            case 110: shot("c3_note"); break;
            case 125: shot("c3_lanemine"); break;
            case 135: shot("c4_scale"); break;
            case 138: shot("c4_camera"); break;
            case 118: shot("c4_lanes_ask"); break;
            case 120: shot("c4_field"); break;
            case 121: shot("c4_finite"); break;
            case 130: shot("c5_chart"); break;
            case 140: shot("c6_ghost"); break;
            case 150: shot("c7_drag"); break;
            case 156: shot("c7_hold"); break;
            case 170: shot("c8_back"); break;
            case 178: shot("c9_sync_pearls"); break;
            case 155: shot("c15_pvdrag"); break;
            case 175: shot("c14_preview"); break;
            case 190: shot("c9_reopen"); break;
            case 199: shot("c9_ask_reload"); break;
            case 203: shot("c9_reloaded"); break;
            case 213: shot("c10_flow"); break;
            case 215: shot("c10_beats"); break;
            case 218: shot("c11_lane0"); break;
            case 221: shot("c11_between"); break;
            case 224: shot("c12_pivot"); break;
            case 229: shot("c13_lanedel"); break;
            case 236: shot("c16_newsong"); break;
            case 242: shot("c17_veil"); break;
            case 245: shot("c17_veil_circle"); break;
            case 252: shot("c17_note_dist"); break;
            case 256: shot("c18_live"); break;
            case 259:
                shot("c19_curtain");
                Gdx.files.absolute(dir).child("autotest.log").writeString(log.toString(), false, "UTF-8");
                Gdx.app.exit();
                break;
            default:
        }
    }

    private void log(String s) {
        log.append(s).append('\n');
        Gdx.app.log("AutoTest", s);
    }

    private void shot(String name) {
        int w = Gdx.graphics.getBackBufferWidth(), h = Gdx.graphics.getBackBufferHeight();
        byte[] px = ScreenUtils.getFrameBufferPixels(0, 0, w, h, true);
        for (int i = 3; i < px.length; i += 4) px[i] = (byte) 255;
        Pixmap p = new Pixmap(w, h, Pixmap.Format.RGBA8888);
        BufferUtils.copy(px, 0, p.getPixels(), px.length);
        PixmapIO.writePNG(Gdx.files.absolute(dir).child(name + ".png"), p);
        p.dispose();
        log("shot " + name);
    }
}
