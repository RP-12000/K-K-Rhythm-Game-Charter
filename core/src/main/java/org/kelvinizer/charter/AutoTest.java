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
import org.kelvinizer.elements.Floating;
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
        EditorScreen e = app.view() instanceof EditorScreen ? (EditorScreen) app.view() : null;
        if (e != null && e != snapSet) {        // the steps were written for 坐标吸附 120 (the default before 11-06)
            e.xStep = 120f;
            snapSet = e;
        }
        return e;
    }

    /** The editor whose 坐标吸附 {@link #ed()} has set to 120. */
    private EditorScreen snapSet;

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
                app.im.touchDown(900, AUDIO_Y, 0, Input.Buttons.LEFT); // audio row
                break;
            case 127: app.im.touchDragged(900 - AUDIO_DRAG, AUDIO_Y, 0); break;
            case 128: app.im.touchUp(900 - AUDIO_DRAG, AUDIO_Y, 0, Input.Buttons.LEFT); break;
            case 129:
                if (e != null) log("after dragging the audio row " + AUDIO_DRAG + " px left: beat " + e.beat);
                break;
            case 133:
                if (e != null) e.insp.tab = Inspector.FIELD;
                break;
            case 134:
                if (e != null) {
                    e.s.mark();
                    e.s.doc.laneScale = 0.9f;
                    e.s.doc.laneScaleEv.add(new Doc.Ev(44f, 46f, 0.9f, 1.1f, 1f));
                    e.s.changed(false);
                }
                break;
            case 136:
                if (e != null) {
                    LaneLayout lay = e.pv.engine().layout();
                    log("scale at beat " + e.beat + ": key width " + lay.defaultWidth() + " (expect 216), x +600 at "
                        + (lay.frameAxis() + lay.frameScale() * 600f) + " (expect 1500)");
                    e.s.mark();
                    e.s.doc.cameraFrame = 120f;                      // axis 120 right of the centre
                    e.s.doc.cameraFrameEv.add(new Doc.Ev(44f, 46f, 120f, -120f, 1f));
                    e.s.changed(false);
                }
                break;
            case 138:
                if (e != null) {
                    LaneLayout lay = e.pv.engine().layout();
                    log("camera frame 120 at beat " + e.beat + ": axis " + lay.frameAxis() + " (expect 1080), x +600"
                        + " at " + (lay.frameAxis() + lay.frameScale() * 600f) + " (expect 1620)");
                }
                break;
            case 139:
                if (e != null) e.insp.tab = Inspector.CHART;
                break;
            case 132:
                app.im.keyDown(Input.Keys.NUM_1);
                app.im.mouseMoved(Math.round(EditorScreen.ux(714f)), py(737f)); // reference x 714
                break;
            case 142:
                if (e != null) notesBefore = e.s.doc.notes.size();
                click(Math.round(EditorScreen.ux(714f)), py(737f));
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
                app.im.touchDown(Math.round(EditorScreen.ux(1628f)), py(797f), 0, Input.Buttons.LEFT); // ref x 1628
                break;
            case 148: app.im.touchDragged(Math.round(EditorScreen.ux(1628f)), py(677f), 0); break;
            case 149: app.im.touchDragged(Math.round(EditorScreen.ux(1628f)), py(557f), 0); break;
            case 151: app.im.touchUp(Math.round(EditorScreen.ux(1628f)), py(557f), 0, Input.Buttons.LEFT); break;
            case 152:
                if (e != null) {
                    Doc.DNote last = e.s.doc.notes.get(e.s.doc.notes.size() - 1);
                    for (Doc.DNote n : e.s.doc.notes) if (n.uid > last.uid) last = n;
                    log("tempo 182 -> 91 at beat 48: beat 60 at " + e.s.beatToMs(60f) + " ms (expect ~23736.3)");
                    log("dragged note: " + last.type + " x " + last.x + " beat " + last.judgement + " duration "
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
            case 176:                                               // sync：高亮 → 增强
                click((int) (EditorScreen.PX + EditorScreen.PW) - 254 + 59, (int) EditorScreen.PY + 24);
                break;
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
            case 222:                                               // 旋转中心 (Kelvin 10-16)
                if (e != null) pivotTest(e);
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
                    motionUnits();
                    linkUnits();
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
                    press(EditorScreen.ux(Field.x(v.x + LaneLayout.TILE_CENTRE)), EditorScreen.uy(Field.y(v.y)));
                }
                break;
            case 247: app.im.touchDragged(at[0] + px(180f), at[1] - px(90f), 0); break;
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
                click((int) (EditorScreen.PX + EditorScreen.PW) - 254 + 183, (int) EditorScreen.PY + 24);
                break;
            case 255:                                               // a curtain over the HUD corner (Kelvin 10-31)
                if (e != null) {
                    Doc.DVeil cu = new Doc.DVeil();
                    cu.radius = 420f;
                    cu.x = 600f;                                     // 1560 in the frame
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
            case 260: if (e != null) motionSetup(e); break;               // 运动器 (Kelvin 2026-11-05)
            case 261:
                // bring the motion bar to y 300 whichever way the 滚动条 test left the page
                if (e != null) e.insp.scroll[Inspector.NOTE] = Math.max(0f,
                    e.insp.scroll[Inspector.NOTE] + e.insp.testBarY - 300f);
                break;
            case 263: if (e != null) press(Inspector.TEST_CX + 14f, e.insp.testBarY + 32f + 2f * 34f + 15f); break;
            case 264: app.im.touchDragged(at[0], at[1] - 34, 0); break;
            case 265: app.im.touchDragged(at[0], at[1] - 68, 0); break;
            case 266: app.im.touchUp(at[0], at[1] - 68, 0, Input.Buttons.LEFT); break;
            case 267:
                if (e != null) {
                    log("reorder: " + cards(motionNote.move) + " (expect 3 2 1200 900 | 2 1.5 900 600 | 1.5 0 600 0)");
                    click(Math.round(Inspector.TEST_CX + Inspector.TEST_CW - 140f), Math.round(e.insp.testKeepY + 14f));
                }
                break;
            case 269:
                if (e != null) {
                    log("keep values: " + cards(motionNote.move)
                        + " (expect 3 2 300 0 | 2 1.5 1200 900 | 1.5 0 900 300)");
                    float w3 = (Inspector.TEST_CW - 16f) / 3f;
                    click(Math.round(Inspector.TEST_CX + 2f * (w3 + 8f) + 20f), Math.round(e.insp.testBarY + 13f));
                }
                break;
            case 271: if (e != null) clickUi(Inspector.TEST_CX + 20f, e.insp.testBarY + 13f); break;
            case 273:
                if (e != null) {
                    log("copied: " + (e.insp.clip != null ? e.insp.clip.evs.size() : 0) + " (expect 3)");
                    e.s.select(others[0], false);
                    e.s.select(others[1], true);
                }
                break;
            case 275: if (e != null) clickUi(Inspector.TEST_CX + 20f, e.insp.testPasteY + 16f); break;
            case 277:
                if (e != null) {
                    log("paste default 按各自判定: " + cards(others[1].move)
                        + " (expect 3 2 300 0 | 2 1.5 1200 900 | 1.5 0 900 300)");
                    float seg = (Inspector.TEST_CW - 16f) / 3f;
                    click(Math.round(Inspector.TEST_CX + 8f + seg * 0.5f), Math.round(e.insp.testPasteBarY + 42f));
                }
                break;
            case 279:
                if (e != null) {
                    log("paste 按原拍: " + cards(others[1].move, motionNote.judgement)
                        + " (expect 3 2 300 0 | 2 1.5 1200 900 | 1.5 0 900 300)");
                    e.s.select(motionNote, false);
                }
                break;
            case 281: if (e != null) clickUi(Inspector.TEST_CX + 40f, e.insp.testStartY + 15f); break;
            case 283:
                for (char ch : "/-0.5".toCharArray()) app.im.keyTyped(ch);
                break;
            case 285: app.im.keyDown(Input.Keys.ENTER); break;
            case 287:
                if (e != null) {
                    log("/-0.5 in 开始: start " + Im.fmt(motionNote.move.get(0).start) + " (expect "
                        + Im.fmt(e.snap(e.beat) - 0.5f) + ")");
                    log("同步运动器: " + linkedCopies() + " (expect 3 linked 3 followed, 3 linked 3 followed)");
                }
                break;
            // Drag and 叠加层 (game 7.177)
            case 289: app.im.keyDown(Input.Keys.G); break;
            case 291:
                if (e != null) {
                    log("G: Drag tool " + (e.tool == EditorScreen.T_DRAG) + " (expect true)");
                    notesBefore = e.s.doc.notes.size();
                    click(Math.round(EditorScreen.ux(714f)), py(737f));
                }
                break;
            case 293:
                if (e != null) dragLayers(e);
                break;
            case 295:
                if (e != null) {
                    Note gn = e.s.note(dragNote);
                    float j = dragNote.judgement;
                    log("layers in the game: " + (gn != null && gn.hasLayers()) + ", x " + (gn == null ? "-"
                        : Im.fmt(gn.xAt(j - 1.25f) - dragNote.x)) + ", rotation layers " + (gn == null ? "-"
                        : gn.rotationLayers().size()) + ", upper line layers "
                        + e.s.chart.lineEvents().upperLayers().size() + " (expect true, 75, 1, 1)");
                    e.save(null);
                }
                break;
            case 297:
                if (e != null) layersSaved(e);
                break;
            case 299:
                if (e != null) {
                    e.setTool(EditorScreen.T_SELECT);
                    e.s.select(dragNote, false);
                    e.insp.tab = Inspector.NOTE;
                    e.insp.openEvents("n-xlay");
                }
                break;
            case 303:
                if (e != null) {
                    e.insp.tab = Inspector.FIELD;
                    e.insp.openEvents("f-up-l");
                }
                break;
            // 悬浮键 (game 7.185): B, a tap put at x −360 y 720 at beat 8, dragged, turned, flipped, keyed, saved
            case 306:
                floatUnits();
                if (e != null) {
                    e.setTool(EditorScreen.T_TAP);
                    e.floatMode = false;
                    e.autoKey = false;
                    e.s.sel.clear();
                    e.seekBeat(8f);
                    notesBefore = e.s.doc.notes.size();
                }
                app.im.keyDown(Input.Keys.B);
                break;
            case 308:
            case 310:
            case 315:
            case 317:
            case 319:
            case 321:
            case 325:
            case 330:
            case 332:
            case 336:
            case 338:
            case 340:
            case 345:
            case 348:
                if (e != null) floatStep(e, frame);
                break;
            case 311:
            case 326:
                app.im.touchDragged((floatAt[0] + floatAt[2]) / 2, (floatAt[1] + floatAt[3]) / 2, 0);
                break;
            case 312:
            case 327:
                app.im.touchDragged(floatAt[2], floatAt[3], 0);
                break;
            case 313:
            case 328:
                app.im.touchUp(floatAt[2], floatAt[3], 0, Input.Buttons.LEFT);
                break;
            case 341:                                               // 图层: the 悬浮键 row's 键 dragged onto 地线
                app.im.touchDragged((dragTo[0] + dragTo[2]) / 2, (dragTo[1] + dragTo[3]) / 2, 0);
                break;
            case 342:
                app.im.touchDragged(dragTo[2], dragTo[3], 0);
                break;
            case 343:
                app.im.touchUp(dragTo[2], dragTo[3], 0, Input.Buttons.LEFT);
                break;
            case 346:                                               // 实机 on, and off again
            case 350:
                click((int) (EditorScreen.PX + EditorScreen.PW) - 254 + 183, (int) EditorScreen.PY + 24);
                break;
            case 352:                                               // a floating long mine (game 7.186)
            case 354:
                if (e != null) floatStep(e, frame);
                break;
            // 定轨 / 无轨 (game 7.184): two sections added in 场地, a tap placed onto a lane, dragged to the next, saved
            case 406:
            case 408:
            case 410:
            case 412:
            case 414:
            case 416:
            case 417:
            case 419:
            case 421:
            case 422:
            case 423:
            case 424:
            case 425:
            case 427:
            case 430:
            case 432:
                if (e != null) lanesStep(e, frame);
                break;
            // 快捷曲线 · 快捷事件 · 判定线跟着键抖 · 原地出现 / 长条收起 / 飞出屏幕 · 故障档位 (Kelvin 2026-11-06)
            case 436:
            case 438:
            case 440:
            case 442:
            case 444:
            case 446:
            case 448:
            case 450:
            case 452:
            case 454:
            case 456:
                if (e != null) quickStep(e, frame);
                break;
            default:
        }
    }

    /** The keys of {@link #quickStep}: taps at 60 (lower) and 60.5 (upper), a hold at 61, a tap at 64 for the UI. */
    private Doc.DNote[] qk;
    private java.util.ArrayList<Doc.Ev> qkBefore;
    private Doc.DStroke qs;

    /**
     * 快捷曲线 · 快捷事件 (Kelvin 2026-11-06): 原地出现, 飞出屏幕, 长条收起, 常用指数, 故障档位 and 判定线跟着键抖
     * called directly; then 正弦 and 放上去 (抖动) clicked on a key's 移动; a custom line and a floating key with
     * glitch and distortion (game 7.187) saved and read back; a click in the preview adds a point to the line.
     */
    private void quickStep(EditorScreen e, int f) {
        Inspector in = e.insp;
        Doc d = e.s.doc;
        switch (f) {
            case 436:
                qk = new Doc.DNote[]{new Doc.DNote(NoteType.TAP, Line.LOWER, -600f, 60f),
                    new Doc.DNote(NoteType.TAP, Line.UPPER, -600f, 60.5f),
                    new Doc.DNote(NoteType.HOLD, Line.LOWER, -360f, 61f),
                    new Doc.DNote(NoteType.TAP, Line.LOWER, 600f, 64f)};
                qk[2].duration = 1f;
                e.s.mark();
                for (Doc.DNote n : qk) {
                    n.approach(Note.DEFAULT_APPROACH_BEATS, n.spawn(), 1f);
                    d.notes.add(n);
                }
                d.setAutoTail(qk[2]);
                e.s.changed(false);
                e.s.select(qk[3], false);
                in.tab = Inspector.NOTE;
                in.openEvents("n-move");
                in.qeKind = QuickEvents.SHAKE;
                in.qeFor = "n-move";
                e.seekBeat(56f);
                break;
            case 438: {
                int c = in.motion(e.s, java.util.Arrays.asList(qk[0], qk[1]), 7);
                log("原地出现 at " + Im.fmt(e.snap(e.beat)) + ": " + c + " keys, " + cards(qk[0].move, 0f) + " / "
                    + cards(qk[1].move, 0f) + " (expect 2, -56 -58 1080 1080 | -58 -60 1080 0 / -56.25 -58.5 1080 1080 "
                    + "| -58.5 -60.5 1080 0)");
                e.seekBeat(59f);
                break;
            }
            case 440: {
                in.motion(e.s, java.util.Collections.singletonList(qk[1]), 9);
                in.motion(e.s, java.util.Collections.singletonList(qk[2]), 8);
                float h = qk[2].height;
                log("飞出屏幕 / 长条收起: " + cards(qk[1].move, 0f) + " / " + cards(qk[2].move, 0f) + " · "
                    + cards(qk[2].heightEv, 0f) + " (expect -56.25 -58.5 1080 1080 | -58.5 -59 1080 810 | -59 -61 810"
                    + " -400 / -59 -61 1080 0 | -61 -62 0 " + Im.fmt(h) + " · -61 -62 " + Im.fmt(h) + " 0)");
                in.openEvents("n-height");
                in.applyCurve(e.s, qk[2].heightEv, QuickEvents.LINEAR, 0, 2.2f);
                in.openEvents("n-move");                            // back to the selected tap's 移动 for the UI
                int g = in.glitchKeys(e.s, java.util.Arrays.asList(qk[0], qk[2]), 2);
                Doc.DGlitch g0 = qk[0].glitches.get(0), g2 = qk[2].glitches.get(0);
                log("常用指数 2.2: " + Im.fmt(qk[2].heightEv.get(0).exp) + "; 故障中: " + g + " keys, " + Im.fmt(g0.from)
                    + "–" + Im.fmt(g0.to) + " " + Im.fmt(g0.intensity) + "/" + Im.fmt(g0.split) + "/" + Im.fmt(g0.slice)
                    + "/" + Im.fmt(g0.flicker) + "/" + Im.fmt(g0.rate) + ", " + Im.fmt(g2.from) + "–" + Im.fmt(g2.to)
                    + " (expect 2.2; 2 keys, 56–60 0.5/1.4/1.2/0.2/16, 59–62)");
                int lo = d.lowerLay.size(), up = d.upperLay.size();
                int k = in.lineShake(e.s, java.util.Arrays.asList(qk), 20f, 0);
                StringBuilder b = new StringBuilder();
                for (Doc.Ev x : d.lowerLay) if (x.tag != null && x.start >= 59.9f) b.append(' ').append(Im.fmt(x.to));
                for (Doc.Ev x : d.upperLay) if (x.tag != null && x.start >= 59.9f) b.append(' ').append(Im.fmt(x.to));
                int lo2 = d.lowerLay.size(), up2 = d.upperLay.size();
                e.s.undo();                                         // the doc before it comes back as e.s.doc
                log("判定线跟着键抖: " + k + " kicks, +" + (lo2 - lo) + " lower +" + (up2 - up) + " upper, kicks to" + b
                    + ", undo back " + (e.s.doc.lowerLay.size() == lo && e.s.doc.upperLay.size() == up)
                    + " (expect 4 kicks, +6 lower +2 upper, kicks to -20 0 -20 0 -20 0 20 0, undo back true)");
                e.s.redo();
                log("redo: the same doc again " + (e.s.doc == d) + " (expect true)");
                e.seekBeat(62f);
                in.scroll[Inspector.NOTE] = Math.max(0f, in.scroll[Inspector.NOTE] + in.testCurveY - 200f);
                break;
            }
            case 442: {                                             // 正弦 (缓出) on the selected key's fall
                float bw = (Inspector.TEST_CW - 30f) / 6f;
                clickUi(Inspector.TEST_CX + bw + 6f + bw / 2f, in.testCurveY + 36f + 15f);
                break;
            }
            case 444:
                log("正弦缓出: " + qk[3].move.size() + " cards, " + Im.fmt(qk[3].move.get(0).from) + " → "
                    + Im.fmt(qk[3].move.get(qk[3].move.size() - 1).to) + " (expect 3, 1080 → 0)");
                qkBefore = new java.util.ArrayList<>(qk[3].move);
                break;
            case 446:                                               // the form has moved down under the new cards
                clickUi(Inspector.TEST_CX + 20f, in.testQeGoY + 16f);
                break;
            case 448: {
                double t = 62.0 + 2.0 / 7.0;
                double dv = QuickEvents.valueAt(qk[3].move, 0f, false, t) - QuickEvents.valueAt(qkBefore, 0f, false, t);
                log("抖动 on 移动: " + qk[3].move.size() + " cards, first swing +" + Im.fmt(QuickEvents.round(dv))
                    + ", ends " + Im.fmt(qk[3].move.get(qk[3].move.size() - 1).to) + " at "
                    + Im.fmt(qk[3].move.get(qk[3].move.size() - 1).end) + " (expect +60 within 1.8, ends 0 at 64)");
                e.s.mark();
                qs = new Doc.DStroke();                             // 自定义线条 (game 7.187)
                qs.points = new float[]{-700f, 300f, -300f, 700f, 100f, 300f, 500f, 700f};
                qs.curve = true;
                qs.from = 60f;
                qs.to = 66f;
                qs.drawIn = 0.5f;
                qs.drawOut = 0.5f;
                qs.width = 8f;
                qs.color = 0xFFB0B0FF;
                qs.opacity = 0.8f;
                qs.opacityEv.add(new Doc.Ev(63f, 65f, 0.8f, 0.3f, 1f));
                Doc.DGlitch gl = new Doc.DGlitch();
                gl.from = 64f;
                gl.to = 65f;
                qs.glitches.add(gl);
                Inspector.glitchLevel(qs.glitches, 2);
                Doc.DDist ds = new Doc.DDist();
                ds.from = 65f;
                ds.to = 66f;
                ds.intensity = 1f;
                qs.distortions.add(ds);
                d.strokes.add(qs);
                Doc.DNote fl = new Doc.DNote(NoteType.TAP, Line.LOWER, 300f, 66f);   // a floating key's glitch
                fl.toFloating(500f);
                fl.glitches.add(gl.copy());
                fl.distortions.add(ds.copy());
                d.notes.add(fl);
                e.s.changed(false);
                e.save(null);
                break;
            }
            case 450:
                try {
                    Doc back = Doc.fromChart(ChartMapper.read(e.s.file, null, null));
                    Doc.readRelFlags(back, e.s.file);
                    Doc.DStroke b = back.strokes.isEmpty() ? null : back.strokes.get(back.strokes.size() - 1);
                    int fg = 0;
                    for (Doc.DNote n : back.notes) if (n.floating && n.judgement == 66f) fg += n.glitches.size() * 10
                        + n.distortions.size();
                    log("快捷 saved: identical json " + d.toJson().equals(back.toJson()) + "; line " + (b == null ? "-"
                        : b.pointCount() + " points " + Doc.colorText(b.color) + " " + b.curve + " " + Im.fmt(b.width)
                        + " " + b.opacityEv.size() + "/" + b.glitches.size() + "/" + b.distortions.size()
                        + " glitch " + Im.fmt(b.glitches.get(0).intensity)) + "; floating key glitch/distortion " + fg
                        + " (expect true; 4 points #FFB0B0 true 8 1/1/1 glitch 0.5; 11)");
                } catch (Exception x) {
                    log("快捷 saved: " + x);
                }
                in.tab = Inspector.STROKE;
                in.strokeSel = qs.uid;
                break;
            case 452: {                                             // a click right of the keys adds a fifth point
                LaneLayout lay = e.pv.engine().layout();
                click(Math.round(EditorScreen.PX + (lay.frameAxis() + lay.frameScale() * 840f) * EditorScreen.K),
                    Math.round(EditorScreen.uy(Field.y(960f))));
                break;
            }
            case 454:
                log("线条 click: " + qs.pointCount() + " points, the last at " + (qs.pointCount() < 5 ? "-"
                    : Im.fmt(qs.points[8]) + ", " + Im.fmt(qs.points[9])) + " (expect 5, about 840, 960)");
                break;
            case 456:                                               // bar lines and bold whole beats (11-06)
                e.floatMode = false;
                e.setTool(EditorScreen.T_TAP);
                e.seekBeat(63.5f);
                break;
            default:
        }
    }

    /** y of 场地's first button, the 定轨 one, in a chart without sections (Inspector top 102 + 14 + title 26 + 16). */
    private static final float LANE_ADD_Y = 158f;
    /** A key's place on screen (x, y) and where a drag takes it (x). */
    private final int[] keyAt = new int[3];
    private Doc.DNote laneKey;

    /** Puts where centre-line x {@code x} on the lower line is now on screen into {@code keyAt[0..1]}. */
    private void keyOnScreen(EditorScreen e, float x) {
        LaneLayout lay = e.pv.engine().layout();
        keyAt[0] = Math.round(EditorScreen.PX + (lay.frameAxis() + lay.frameScale() * x) * EditorScreen.K);
        keyAt[1] = Math.round(EditorScreen.uy(e.pv.engine().lineY(Line.LOWER)));
    }

    /** 定轨 / 无轨 (game 7.184, Kelvin 2026-11-06) in the editor: 场地's sections, placing and dragging onto lanes. */
    private void lanesStep(EditorScreen e, int f) {
        Doc d = e.s.doc;
        switch (f) {
            case 406:
                laneUnits();
                e.autoKey = false;
                e.setTool(EditorScreen.T_SELECT);
                e.s.sel.clear();
                e.s.mark();
                d.fixedLanes.clear();
                e.s.changed(false);
                e.seekBeat(8f);
                e.insp.tab = Inspector.FIELD;
                java.util.Arrays.fill(e.insp.scroll, 0f);
                break;
            case 408:                                               // + 从第 0 拍定 6 轨
                clickUi(Inspector.TEST_CX + 60f, LANE_ADD_Y);
                break;
            case 410:
                log("定轨 added: " + lanesOf(d) + ", the game at 8 " + e.s.chart.fixedLanesAt(8f) + " (expect 0:6, 6)");
                e.seekBeat(16f);
                break;
            case 412:                                               // + 在播放头改轨道数: 无轨 from 16 on
                clickUi(Inspector.TEST_CX + 60f, LANE_ADD_Y + 44f);
                break;
            case 414:
                log("定轨 second: " + lanesOf(d) + ", the game at 20 " + e.s.chart.fixedLanesAt(20f)
                    + " (expect 0:6 16:0, 0)");
                e.seekBeat(8f);
                e.setTool(EditorScreen.T_TAP);
                e.s.placeLine = Line.LOWER;
                break;
            case 416:                                               // a tap at x 100 lands on the lane at 160
                keyOnScreen(e, 100f);
                app.im.touchDown(keyAt[0], keyAt[1], 0, Input.Buttons.LEFT);
                break;
            case 417:
                app.im.touchUp(keyAt[0], keyAt[1], 0, Input.Buttons.LEFT);
                break;
            case 419: {
                java.util.ArrayList<Doc.DNote> sel = e.s.selected();
                laneKey = sel.size() == 1 ? sel.get(0) : null;
                log("定轨 placed: " + (laneKey == null ? "-" : Im.fmt(laneKey.x) + " at " + Im.fmt(laneKey.judgement))
                    + ", snap 100 / -1000 at 8 " + Im.fmt(e.snapX(100f, 8f)) + " " + Im.fmt(e.snapX(-1000f, 8f))
                    + ", at 20 " + Im.fmt(e.snapX(100f, 20f)) + ", ‹ › at 8 " + Im.fmt(e.nudgeX(160f, 8f, 1)) + " "
                    + Im.fmt(e.nudgeX(100f, 8f, -1)) + " " + Im.fmt(e.nudgeX(800f, 8f, 1)) + ", at 20 "
                    + Im.fmt(e.nudgeX(100f, 20f, 1)) + " (expect 160 at 8, 160 -800, 120, 320 0 800, 220)");
                e.xStep = 60f;                                      // finer 坐标吸附: 1/4 lanes, then 1/8 (11-06)
                String quarter = Im.fmt(e.snapX(100f, 8f)) + " " + Im.fmt(e.nudgeX(160f, 8f, 1));
                e.xStep = 10f;
                log("坐标吸附 60: " + quarter + "; 10: " + Im.fmt(e.snapX(100f, 8f)) + " " + Im.fmt(e.snapX(13f))
                    + " (expect 80 240; 120 10)");
                e.xStep = 120f;
                e.setTool(EditorScreen.T_SELECT);
                break;
            }
            case 421:                                               // a key at the playhead cannot be picked up
                e.seekBeat(7.5f);
                break;
            case 422: {                                             // drag it 130 to the right: onto the divider at 320
                if (laneKey == null) break;
                Note n = e.s.note(laneKey);
                if (n == null) break;
                float dp = n.positionAt(e.beat) - e.pv.hitShift(), far = dp + e.pv.bodyShift(laneKey.type, dp);
                keyOnScreen(e, laneKey.x);
                keyAt[1] = Math.round(EditorScreen.uy(e.pv.engine().lineY(Line.LOWER) + far
                    + 0.5f * Math.max(e.pv.noteHeight(), 28f)));
                keyAt[2] = keyAt[0] + Math.round(130f * e.pv.engine().layout().frameScale() * EditorScreen.K);
                app.im.touchDown(keyAt[0], keyAt[1], 0, Input.Buttons.LEFT);
                break;
            }
            case 423:
            case 424:
                app.im.touchDragged(keyAt[2], keyAt[1], 0);
                break;
            case 425:
                app.im.touchUp(keyAt[2], keyAt[1], 0, Input.Buttons.LEFT);
                break;
            case 427:
                log("定轨 dragged: " + (laneKey == null ? "-" : Im.fmt(laneKey.x) + " at " + Im.fmt(laneKey.judgement))
                    + " (expect 320 at 8)");
                e.seekBeat(8f);
                e.insp.tab = Inspector.FIELD;
                break;
            case 430:
                e.save(null);
                break;
            case 432:
                try {
                    Doc back = Doc.fromChart(ChartMapper.read(e.s.file, null, null));
                    Doc.readRelFlags(back, e.s.file);
                    log("定轨 saved: " + lanesOf(back) + ", identical json " + d.toJson().equals(back.toJson())
                        + " (expect 0:6 16:0, true)");
                } catch (Exception x) {
                    log("定轨 saved: " + x);
                }
                break;
            default:
        }
    }

    /** @return a chart's 定轨 sections as {@code beat:count}, space-separated ({@code -} for none). */
    private static String lanesOf(Doc d) {
        StringBuilder b = new StringBuilder();
        for (Doc.Lanes l : d.fixedLanes) {
            b.append(b.length() > 0 ? " " : "").append(Im.fmt(l.beat)).append(':').append(l.count);
        }
        return b.length() > 0 ? b.toString() : "-";
    }

    /**
     * 定轨 / 无轨 (game 7.184): a new chart has 6 lanes from beat 0; sections written out of order read back from v4
     * and v6 text in order; the count at a beat, the editor's and the game's.
     */
    private void laneUnits() {
        Doc d = Doc.blank("lanes", "", "", "", 120f, 0f);
        boolean blank = d.toJson().contains("\"fixedLanes\": [{ \"beat\": 0, \"count\": 6 }]");
        d.fixedLanes.clear();
        d.fixedLanes.add(new Doc.Lanes(16f, 0));
        d.fixedLanes.add(new Doc.Lanes(0f, 6));
        d.fixedLanes.add(new Doc.Lanes(32f, 4));
        Doc.DNote k = new Doc.DNote(NoteType.TAP, Line.LOWER, 160f, 4f);
        k.approach(Note.DEFAULT_APPROACH_BEATS, Doc.SPAWN, 1f);
        d.notes.add(k);
        // a whole-line key turning about its pivot keeps the pivot (as My Soul and 氷点 Deep have them)
        Doc.DNote w = new Doc.DNote(NoteType.UNIVERSAL, Line.UPPER, 0f, 6f);
        w.approach(Note.DEFAULT_APPROACH_BEATS, Doc.SPAWN, 1f);
        w.rot.add(new Doc.Ev(4f, 6f, 0f, 90f, 1f));
        w.pivotY = -300f;
        d.notes.add(w);
        try {
            // as the editor reads a file back (without its editor-only keys), so the three texts compare alike
            Chart c = ChartMapper.read(new java.io.ByteArrayInputStream(d.toJson().getBytes("UTF-8")), "lanes.json");
            String a = Doc.fromChart(c).toJson();
            Doc d4 = Doc.fromChart(ChartMapper.read(new java.io.ByteArrayInputStream(a.getBytes("UTF-8")), "l.json"));
            org.kelvinizer.elements.ChartUpgrade.Result r =
                org.kelvinizer.elements.ChartUpgrade.upgrade("lanes.json", a.getBytes("UTF-8"));
            Doc d6 = Doc.fromChart(ChartMapper.read(new java.io.ByteArrayInputStream(r.json.getBytes("UTF-8")),
                "lanes.json", org.kelvinizer.elements.SongInfo.of(c)));
            log("定轨 units: new chart " + blank + ", " + lanesOf(d) + ", the game at -1 8 20 40 " + c.fixedLanesAt(-1f)
                + " " + c.fixedLanesAt(8f) + " " + c.fixedLanesAt(20f) + " " + c.fixedLanesAt(40f) + ", the editor "
                + d.lanesAt(-1f) + " " + d.lanesAt(8f) + " " + d.lanesAt(20f) + " " + d.lanesAt(40f) + ", v4 "
                + a.equals(d4.toJson()) + ", v6 " + a.equals(d6.toJson()) + " " + lanesOf(d6) + ", v6 text "
                + r.json.contains("fixedLanes") + " (expect true, 0:6 16:0 32:4, the game 6 6 0 4, the editor 6 6 0 4,"
                + " v4 true, v6 true 0:6 16:0 32:4, v6 text true)");
            float wp = Float.NaN;
            for (Note n : c.notes()) if (n.type() == NoteType.UNIVERSAL) wp = n.pivotY();
            log("whole-line pivot: a tide's pivot y " + Im.fmt(wp) + ", in v6 "
                + r.json.contains("\"pivot\": [0, -300]")
                + " (expect -300, true)");
        } catch (Exception x) {
            log("定轨 units: " + x);
        }
    }

    /** From and to of a drag in 图层 (screen x, y). */
    private final int[] dragTo = new int[4];
    /** Where a floating key is pressed (0, 1) and where the drag takes it (2, 3), on screen. */
    private final int[] floatAt = new int[4];

    /** Puts where a floating key at centre-line x {@code x}, height {@code y} shows now into floatAt[i], [i + 1]. */
    private void floatSpot(EditorScreen e, float x, float y, int i) {
        LaneLayout lay = e.pv.engine().layout();
        floatAt[i] = Math.round(EditorScreen.ux(lay.frameAxis() + lay.frameScale() * x));
        floatAt[i + 1] = Math.round(EditorScreen.uy(Field.y(y)));
    }

    /** @return the chart's first floating key (the test puts one), or null */
    private static Doc.DNote floating(EditorScreen e) {
        for (Doc.DNote n : e.s.doc.notes) if (n.floating) return n;
        return null;
    }

    /** @return a key as "type at beat x … y …" ("-" for none) */
    private static String where(Doc.DNote n) {
        return n == null ? "-" : n.type.json + " at " + Im.fmt(n.judgement) + " x " + Doc.num(n.x) + " y "
            + Doc.num(n.y);
    }

    /** 悬浮键 (game 7.185) in the editor: placed with B on, dragged, turned, flipped, keyed, saved, moved in 图层. */
    private void floatStep(EditorScreen e, int f) {
        Doc.DNote k = floating(e);
        switch (f) {
            case 308:
                log("B: 悬浮 " + e.floatMode + ", tool tap " + (e.tool == EditorScreen.T_TAP) + " (expect true, true)");
                floatSpot(e, -360f, 720f, 0);
                click(floatAt[0], floatAt[1]);
                break;
            case 310: {
                Note gn = k != null ? e.s.note(k) : null;
                log("悬浮 placed: " + (e.s.doc.notes.size() - notesBefore) + ", " + where(k) + ", selected "
                    + (k != null && e.s.sel.contains(k.uid)) + ", in the game " + (gn != null && gn.floats()
                    ? Im.fmt(gn.floating().x()) + " " + Im.fmt(gn.floating().y()) + " size "
                    + Im.fmt(gn.floating().size()) + " appear " + Im.fmt(gn.floating().appear()) : "-")
                    + " (expect 1, tap at 8 x -360 y 720, selected true, in the game -360 720 size "
                    + Im.fmt(Floating.TAP_SIZE) + " appear 6)");
                floatSpot(e, -360f, 720f, 0);
                floatSpot(e, -120f, 960f, 2);
                app.im.touchDown(floatAt[0], floatAt[1], 0, Input.Buttons.LEFT);
                break;
            }
            case 315:
                log("悬浮 dragged: " + where(k) + " (expect tap at 8 x -120 y 960)");
                break;
            case 317: {
                // 旋转 (Ctrl+R): clockwise about the middle of the screen; a key on a line in the selection stays
                Doc.DNote line = null;
                for (Doc.DNote n : e.s.doc.notes) if (!n.floating && line == null) line = n;
                if (line != null) e.s.select(line, true);
                float lx = line != null ? line.x : 0f;
                e.rotateFloating(-45f);
                k = floating(e);
                log("Ctrl+R: " + where(k) + ", the line key moved " + (line != null && line.x != lx)
                    + " (expect tap at 8 x 170 y 939, false)");
                e.undo();
                log("Ctrl+R undone: " + where(floating(e)) + " (expect tap at 8 x -120 y 960)");
                break;
            }
            case 319:
                if (k == null) break;
                e.s.select(k, false);
                e.mirrorUpDown();
                e.mirror();
                log("Shift+M, M on a floating key: " + where(floating(e)) + " (expect tap at 8 x 120 y 240)");
                e.undo();
                e.undo();
                log("undone: " + where(floating(e)) + " (expect tap at 8 x -120 y 960)");
                break;
            case 321:
                e.autoKey = true;
                if (k != null) e.s.select(k, false);
                e.seekBeat(7f);
                break;
            case 325:
                floatSpot(e, -120f, 960f, 0);
                floatSpot(e, 120f, 960f, 2);
                app.im.touchDown(floatAt[0], floatAt[1], 0, Input.Buttons.LEFT);
                break;
            case 330: {
                Note gn = k != null ? e.s.note(k) : null;
                log("自动关键帧 on a floating key: " + where(k) + ", x events " + (k == null ? "-" : evs(k.xEv))
                    + ", in the game x at 5.5 / 7.5 " + (gn == null || !gn.floats() ? "-"
                    : Im.fmt(gn.floating().xAt(5.5f)) + " / " + Im.fmt(gn.floating().xAt(7.5f)))
                    + " (expect tap at 8 x -120 y 960, x events 6 7 -120 120, in the game -120 / 120)");
                break;
            }
            case 332:
                e.autoKey = false;
                if (k == null) break;
                e.s.mark();
                k.size = 200f;
                k.fillEv.add(new Doc.Ev(6f, 8f, 200f, 0f, 1f));
                e.s.changed(false);
                e.insp.tab = Inspector.NOTE;
                break;
            case 336:
                e.save(null);
                break;
            case 338:
                floatSaved(e);
                break;
            case 340: {
                float[] p = e.layerSpot(null, true), q = e.layerSpot(Line.LOWER, false);
                log("图层: 悬浮键 row " + (p != null) + " (expect true)");
                if (p == null || q == null) break;
                dragTo[0] = Math.round(p[0]);
                dragTo[1] = Math.round(p[1]);
                dragTo[2] = Math.round(q[0]);
                dragTo[3] = Math.round(q[1]);
                app.im.touchDown(dragTo[0], dragTo[1], 0, Input.Buttons.LEFT);
                break;
            }
            case 345: {
                Doc.DNote t = null;
                for (Doc.DNote n : e.s.doc.notes) {
                    if (n.type == NoteType.TAP && n.judgement == 8f && n.x == -120f) t = n;
                }
                log("图层: 悬浮键's 键 dragged onto 地线: floating " + (t != null && t.floating) + ", line "
                    + (t == null ? "-" : t.line) + ", y events " + (t == null ? "-" : t.yEv.size())
                    + " (expect false, LOWER, 0)");
                e.undo();
                log("图层: undone, " + where(floating(e)) + " (expect tap at 8 x -120 y 960)");
                break;
            }
            case 348:
                floatLive(e);
                break;
            case 352:
                e.setTool(EditorScreen.T_LMINE);
                e.seekBeat(12f);
                notesBefore = e.s.doc.notes.size();
                floatSpot(e, 720f, 600f, 0);
                click(floatAt[0], floatAt[1]);
                break;
            case 354: {
                Doc.DNote m = null;
                for (Doc.DNote n : e.s.doc.notes) if (n.floating && n.type == NoteType.MINE) m = n;
                Note gn = m != null ? e.s.note(m) : null;
                boolean listed = false;
                if (gn != null) for (Note x : e.s.chart.mines()) listed |= x == gn;
                log("悬浮长水雷 placed: " + (e.s.doc.notes.size() - notesBefore) + ", " + where(m) + " for "
                    + (m == null ? "-" : Im.fmt(m.duration)) + ", in the game size " + (gn == null || !gn.floats() ? "-"
                    : Im.fmt(gn.floating().size())) + ", a mine of the chart " + listed
                    + " (expect 1, mine at 12 x 720 y 600 for 1, in the game size " + Im.fmt(Floating.LONG_MINE_SIZE)
                    + ", a mine of the chart true)");
                e.floatMode = false;
                break;
            }
            default:
        }
    }

    /** The saved v6 file keeps the floating key (size, fill, x events), read back alike; one off screen warns. */
    private void floatSaved(EditorScreen e) {
        try {
            Doc back = Doc.fromChart(ChartMapper.read(e.s.file, null, null));
            Doc.readRelFlags(back, e.s.file);
            String text = e.s.file.readString("UTF-8");
            Doc.DNote k = floating(e);
            Note gn = k != null ? e.s.note(k) : null;
            log("悬浮 saved: identical json " + e.s.doc.toJson().equals(back.toJson()) + ", float "
                + text.contains("\"float\"") + ", fillEvents " + text.contains("\"fillEvents\"") + ", size "
                + (gn == null || !gn.floats() ? "-" : Im.fmt(gn.floating().size()) + ", fill at 7 "
                + Im.fmt(gn.floating().fillAt(7f, gn.judgement()))) + " (expect true, true, true, 200, fill at 7 100)");
        } catch (Exception x) {
            log("悬浮 saved: " + x);
        }
        e.s.mark();
        Doc.DNote far = new Doc.DNote(NoteType.TAP, Line.LOWER, 1100f, 9f);
        far.toFloating(600f);
        e.s.doc.notes.add(far);
        e.s.changed(false);
        e.checks.update(e.s);
        int off = 0;
        for (Checks.Item it : e.checks.items) if (it.text != null && it.text.contains("悬浮键")) off++;
        e.undo();
        int left = 0;
        for (Doc.DNote n : e.s.doc.notes) if (n.floating) left++;
        log("悬浮 checks: off the screen " + off + ", undone " + left + " floating (expect 1, 1)");
    }

    /** 实机 over the floating key's beat: autoplay hits it there. */
    private void floatLive(EditorScreen e) {
        liveRun(e, 7.5f, 8.5f);
        Note[] all = e.pv.chart().notes();
        String j = "-";
        for (int i = 0; i < all.length; i++) {
            if (all[i].floats() && Math.abs(all[i].judgement() - 8f) < 1e-3f) {
                j = String.valueOf(e.pv.engine().noteJudgement(i));
            }
        }
        log("实机 floating key: " + j + ", marks off " + !e.overlays + " (expect RESONANCE, true)");
    }

    /** @return a channel's events as "start end from to" (absolute beats) */
    private static String evs(java.util.List<Doc.Ev> list) {
        StringBuilder b = new StringBuilder();
        for (Doc.Ev x : list) {
            b.append(b.length() > 0 ? " | " : "").append(Im.fmt(x.start)).append(' ').append(Im.fmt(x.end))
                .append(' ').append(Im.fmt(x.from)).append(' ').append(Im.fmt(x.to));
        }
        return b.length() > 0 ? b.toString() : "-";
    }

    /**
     * 悬浮键 (game 7.185 / 7.186, CHART_FORMAT §11): the format's example (with a mine and a long mine) as v4 working
     * text reads into the editor and writes back the same, through the game's v6 too, and the game reads sizes, appear
     * beats, the chain and the x events.
     */
    private void floatUnits() {
        String v4 = "{ \"formatVersion\": 4, \"laneless\": true, \"bpm\": 120, \"notes\": ["
            + " { \"type\": \"tap\", \"float\": true, \"x\": -560, \"y\": 760, \"judgement\": 8 },"
            + " { \"type\": \"tap\", \"float\": true, \"x\": -260, \"y\": 760, \"judgement\": 8 },"
            + " { \"type\": \"hold\", \"float\": true, \"x\": 380, \"y\": 560, \"size\": 200, \"judgement\": 9,"
            + " \"duration\": 3 },"
            + " { \"type\": \"drag\", \"float\": true, \"x\": -500, \"y\": 360, \"judgement\": 10, \"chain\": \"c\" },"
            + " { \"type\": \"drag\", \"float\": true, \"x\": -300, \"y\": 420, \"judgement\": 10.5,"
            + " \"chain\": \"c\" },"
            + " { \"type\": \"tap\", \"float\": true, \"x\": 100, \"y\": 880, \"judgement\": 12, \"appearance\": 10.5,"
            + " \"fillEvents\": [ { \"startTime\": 10.5, \"endTime\": 12, \"initial\": 160, \"final\": 0,"
            + " \"exponent\": 2 } ], \"xEvents\": [ { \"startTime\": 11, \"endTime\": 12, \"initial\": 100,"
            + " \"final\": 300, \"exponent\": 1 } ] },"
            + " { \"type\": \"mine\", \"float\": true, \"x\": 600, \"y\": 300, \"judgement\": 13 },"
            + " { \"type\": \"mine\", \"float\": true, \"x\": -600, \"y\": 300, \"judgement\": 13, \"duration\": 2 } ],"
            + " \"LineEvents\": { \"upperJudgementLine\": 1080, \"lowerJudgementLine\": 120, \"upperEvents\": [],"
            + " \"lowerEvents\": [] } }";
        try {
            Chart c = ChartMapper.read(new java.io.ByteArrayInputStream(v4.getBytes("UTF-8")), "float.json");
            Doc d = Doc.fromChart(c);
            String a = d.toJson();
            Doc d4 = Doc.fromChart(ChartMapper.read(new java.io.ByteArrayInputStream(a.getBytes("UTF-8")), "f.json"));
            org.kelvinizer.elements.ChartUpgrade.Result r =
                org.kelvinizer.elements.ChartUpgrade.upgrade("float.json", a.getBytes("UTF-8"));
            Doc d6 = Doc.fromChart(ChartMapper.read(new java.io.ByteArrayInputStream(r.json.getBytes("UTF-8")),
                "float.json", org.kelvinizer.elements.SongInfo.of(c)));
            StringBuilder sz = new StringBuilder(), ap = new StringBuilder();
            float x115 = Float.NaN;
            for (Note n : c.notes()) {
                if (!n.floats()) continue;
                sz.append(Im.fmt(n.floating().size())).append(' ');
                ap.append(Im.fmt(n.floating().appear())).append(' ');
                if (n.judgement() == 12f) x115 = n.floating().xAt(11.5f);
            }
            int fl = 0;
            String chain = "-";
            for (Doc.DNote n : d.notes) {
                if (n.floating) fl++;
                if (n.type == NoteType.DRAG) chain = n.chain;
            }
            String t = Im.fmt(Floating.TAP_SIZE), dr = Im.fmt(Floating.DRAG_SIZE);    // the game's defaults
            log("悬浮 units: " + fl + " of " + d.notes.size() + " floating, sizes " + sz + "| appear " + ap + "| chain "
                + chain + ", x at 11.5 " + Im.fmt(x115) + ", v4 " + a.equals(d4.toJson()) + ", v6 "
                + a.equals(d6.toJson()) + ", v6 text " + r.json.contains("\"float\"") + " (expect 8 of 8 floating,"
                + " sizes " + t + " " + t + " 200 " + dr + " " + dr + " " + t + " "
                + Im.fmt(Floating.LONG_MINE_SIZE) + " " + Im.fmt(Floating.MINE_SIZE)
                + " | appear 6 6 7 8 8.5 10.5 11 11 | chain c, x at 11.5 200,"
                + " v4 true, v6 true, v6 text true)");
        } catch (Exception x) {
            log("悬浮 units: " + x);
        }
    }

    /** Places a Drag with the G tool's click, then gives it overlapping x layers and the upper line a layer. */
    private void dragLayers(EditorScreen e) {
        dragNote = null;
        for (Doc.DNote n : e.s.doc.notes) if (n.type == NoteType.DRAG) dragNote = n;
        Note gn = dragNote != null ? e.s.note(dragNote) : null;
        log("Drag placed: " + (e.s.doc.notes.size() - notesBefore) + ", type " + (dragNote != null ? dragNote.type.json
            : "-") + ", in the game " + (gn != null && gn.type() == NoteType.DRAG) + ", 假键 allowed "
            + (dragNote != null && dragNote.canFake()) + " (expect 1, drag, true, true)");
        if (dragNote == null) return;
        float j = dragNote.judgement;
        e.s.mark();
        dragNote.xLay.add(new Doc.Ev(j - 2f, j - 1f, 0f, 120f, 1f));
        dragNote.xLay.add(new Doc.Ev(j - 1.5f, j - 0.5f, 0f, -60f, 1f));
        dragNote.posLay.add(new Doc.Ev(j - 2f, j, 100f, 0f, 1f));
        dragNote.rotLay.add(new Doc.Ev(j - 2f, j, 30f, 0f, 1f));
        e.s.doc.upperLay.add(new Doc.Ev(j - 4f, j, 0f, 50f, 1f));
        e.s.changed(false);
    }

    /** The saved v6 file keeps the layers and the Drag, and reads back to the same editor text. */
    private void layersSaved(EditorScreen e) {
        try {
            Doc back = Doc.fromChart(ChartMapper.read(e.s.file, null, null));
            Doc.readRelFlags(back, e.s.file);
            String text = e.s.file.readString("UTF-8"), a = e.s.doc.toJson(), b = back.toJson();
            if (!a.equals(b)) {
                int i = 0;
                while (i < Math.min(a.length(), b.length()) && a.charAt(i) == b.charAt(i)) i++;
                log("layers saved: first difference " + a.substring(Math.max(0, i - 80), Math.min(a.length(), i + 80))
                    .replace('\n', ' ') + " ||| " + b.substring(Math.max(0, i - 80), Math.min(b.length(), i + 80))
                    .replace('\n', ' '));
            }
            log("layers saved: identical json " + a.equals(b) + ", xLayers "
                + text.contains("\"xLayers\"") + ", positionLayers " + text.contains("\"positionLayers\"")
                + ", upperLayers " + text.contains("\"upperLayers\"") + ", drag " + text.contains("\"drag\"")
                + " (expect true, true, true, true, true)");
            log("links saved: " + text.contains("\"link\"") + " (expect true)");
        } catch (Exception x) {
            log("layers saved: " + x);
        }
    }

    private void clickUi(float x, float y) {
        click(Math.round(x), Math.round(y));
    }

    private Doc.DNote motionNote, dragNote;
    private final Doc.DNote[] others = new Doc.DNote[2];

    /**
     * 运动器 (Kelvin 2026-11-05): a tap key with three 移动 cards (judgement − 3 → −2.5 → −1 → 0), the list open,
     * two other taps for the paste, and the playhead two beats before the key for {@code /}.
     */
    private void motionSetup(EditorScreen e) {
        e.s.mark();
        float lane = -840f;  // the old lane −4
        float j = 24f;
        motionNote = new Doc.DNote(NoteType.TAP, Line.UPPER, lane, j);
        others[0] = new Doc.DNote(NoteType.TAP, Line.UPPER, lane, j + 2f);
        others[1] = new Doc.DNote(NoteType.TAP, Line.LOWER, lane, j + 3.5f);
        e.s.doc.notes.add(motionNote);
        e.s.doc.notes.add(others[0]);
        e.s.doc.notes.add(others[1]);
        motionNote.move.clear();
        motionNote.move.add(new Doc.Ev(j - 3f, j - 2.5f, 1200f, 900f, 1f));
        motionNote.move.add(new Doc.Ev(j - 2.5f, j - 1f, 900f, 300f, 2f));
        motionNote.move.add(new Doc.Ev(j - 1f, j, 300f, 0f, 1f));
        for (Doc.Ev ev : motionNote.move) ev.rel = true;
        for (Doc.DNote o : others) o.move.clear();
        e.s.changed(false);
        e.s.select(motionNote, false);
        e.insp.tab = Inspector.NOTE;
        e.insp.openEvents("n-move");
        e.seekBeat(j - 2f);
    }

    /** @return the cards of {@code list} as "start end from to" before {@code j} (the first key's judgement). */
    private String cards(java.util.List<Doc.Ev> list) {
        return cards(list, list == motionNote.move ? motionNote.judgement : others[list == others[0].move ? 0 : 1]
            .judgement);
    }

    private static String cards(java.util.List<Doc.Ev> list, float j) {
        StringBuilder b = new StringBuilder();
        for (Doc.Ev ev : list) {
            if (b.length() > 0) b.append(" | ");
            b.append(Im.fmt(j - ev.start)).append(' ').append(Im.fmt(j - ev.end)).append(' ').append(Im.fmt(ev.from))
                .append(' ').append(Im.fmt(ev.to));
        }
        return b.toString();
    }

    /** @return per pasted key "k linked m followed": its 同步链接 copies, and how many sit on their source (按原拍). */
    private String linkedCopies() {
        StringBuilder b = new StringBuilder();
        for (Doc.DNote o : others) {
            int k = 0, m = 0;
            for (Doc.Ev f : o.move) {
                if (f.link == null || f.link.src) continue;
                k++;
                for (Doc.Ev s : motionNote.move) {
                    if (s.link != null && s.link.src && s.link.id == f.link.id && s.start == f.start && s.end == f.end
                        && s.from == f.from && s.to == f.to) m++;
                }
            }
            b.append(b.length() > 0 ? ", " : "").append(k).append(" linked ").append(m).append(" followed");
        }
        return b.toString();
    }

    /**
     * 同步链接 without the UI: a source edit moves its copies, a retimed 按各自判定 copy stays linked, a copy changed by
     * itself or left without its source is let go, and the editor text keeps the links.
     */
    private void linkUnits() {
        Doc d = new Doc();
        d.bpms.add(new Doc.Seg(0f, 120f));
        Doc.DNote a = new Doc.DNote(NoteType.TAP, Line.UPPER, 0f, 10f);
        Doc.DNote b = new Doc.DNote(NoteType.TAP, Line.LOWER, 240f, 12f);
        Doc.DNote c = new Doc.DNote(NoteType.TAP, Line.UPPER, -240f, 14f);
        d.notes.add(a);
        d.notes.add(b);
        d.notes.add(c);
        Doc.Ev s = new Doc.Ev(8f, 9f, 0f, 90f, 1f), fb = new Doc.Ev(10f, 11f, 0f, 90f, 1f);
        Doc.Ev fc = new Doc.Ev(8f, 9f, 30f, 120f, 1f);
        s.link = Doc.Link.source(1);
        fb.link = Doc.Link.copyOf(1, 0f, 0f, true);                 // 按各自判定: 2 beats later with b's judgement
        fb.rel = true;
        fc.link = Doc.Link.copyOf(1, 0f, 30f, false);               // 按原拍, 30 higher
        a.rot.add(s);
        b.rot.add(fb);
        c.rot.add(fc);
        StringBuilder r = new StringBuilder().append(d.relink());
        s.start = 7f;
        s.to = 180f;
        r.append(' ').append(d.relink()).append(' ').append(Im.fmt(fb.start)).append('>').append(Im.fmt(fb.to))
            .append(' ').append(Im.fmt(fc.start)).append('>').append(Im.fmt(fc.to));
        b.retime(1f);
        r.append(" | retime ").append(d.relink()).append(' ').append(Im.fmt(fb.start)).append(' ')
            .append(fb.link != null);
        fc.to = 999f;
        r.append(" | own edit ").append(d.relink()).append(' ').append(Im.fmt(fc.to)).append(' ')
            .append(fc.link != null);
        String text = d.toJson();
        r.append(" | text ").append(text.contains("\"link\": { \"id\": 1 }")).append(' ')
            .append(text.contains("\"link\": { \"of\": 1, \"dt\": 0, \"dv\": 0, \"judge\": true }"));
        a.rot.clear();
        r.append(" | source gone ").append(d.relink()).append(' ').append(fb.link != null);
        log("link units: " + r + " (expect 0 0 9>180 7>210 | retime 0 10 true | own edit 1 999 false | text true true"
            + " | source gone 1 false)");
    }

    /** 特殊编号 / and 运动器排序 without the UI: parse, the tablet's ±1/4, and the re-timing of a reorder. */
    private void motionUnits() {
        Im im = app.im;
        log("slash: " + Im.fmt(im.parse("/", 12f)) + " " + Im.fmt(im.parse("/+1", 12f)) + " "
            + Im.fmt(im.parse(" / - 0.5", 12f)) + " " + Float.isNaN(im.parse("/2", 12f)) + " " + Float.isNaN(im.parse("1/4", 12f)) + " "
            + Float.isNaN(im.parse("/", Float.NaN)) + " " + Im.fmt(im.parse("3.5", Float.NaN))
            + " (expect 12 13 11.5 true true true 3.5)");
        log("nudge: " + Im.nudge("/", 0.25f) + " " + Im.nudge("/+0.25", -0.25f) + " " + Im.nudge("/-1", -0.25f) + " "
            + Im.nudge("7", 0.25f) + " " + Im.nudge("", -0.25f) + " (expect /+0.25 / /-1.25 7.25 /-0.25)");
        java.util.ArrayList<Doc.Ev> l = new java.util.ArrayList<>();
        l.add(new Doc.Ev(0f, 1f, 0f, 10f, 1f));
        l.add(new Doc.Ev(2f, 4f, 10f, 30f, 2f));                   // a gap of 1 before it
        l.add(new Doc.Ev(4f, 4.5f, 30f, 25f, 1f));
        l.add(new Doc.Ev(6f, 7f, 50f, 60f, 1f));                   // outside the moved range: untouched
        Doc.Ev mid = l.get(1);
        Doc.reorderEvents(l, 0, 2);
        StringBuilder b = new StringBuilder();
        for (Doc.Ev ev : l) b.append(Im.fmt(ev.start)).append('-').append(Im.fmt(ev.end)).append(':')
            .append(Im.fmt(ev.from)).append('>').append(Im.fmt(ev.to)).append(' ');
        log("reorder units: " + b + "mid exp " + Im.fmt(mid.exp)
            + " (expect 0-2:0>20 3-3.5:20>15 3.5-4.5:15>25 6-7:50>60 mid exp 2)");
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
        float[] lanes = {-600f, -120f, 360f};
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
        a.x = -320f;
        a.y = 640f;
        a.start = 0f;
        a.end = 16f;
        Doc.DVeil b = new Doc.DVeil();
        b.radius = 180f;
        b.x = 360f;
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
            + (v != null && v.end - v.start == 8f) + " (expect 9, 3, +1, ~-760,1000, true)");
    }

    /** 假键 and 半径等比 (game 7.167 / 7.168, Kelvin 2026-11-02): kept through the v5 save; a fake is not counted. */
    private void fakeRigid(EditorScreen e) {
        Doc d = e.s.doc;
        float lane = -840f;  // the old lane −4
        Doc.DNote f = new Doc.DNote(NoteType.TAP, Line.LOWER, lane, 64f);
        f.fake = true;
        f.orbit = true;
        f.orbitX = 0f;
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
     * 不跟判定线 (Kelvin 2026-11-03): on a copy of the chart with both lines shaking (one eased) and 叠加层 on both
     * (overlapping, one eased, one held across the range start), a default-fall sky key and an eased ground hold keep
     * their place on screen from 99.5 to 101 and are unchanged outside it, as the game's loader reads the saved file
     * (within half a reference unit: eased parts are cut into short lines).
     */
    private void holdStill(EditorScreen e) {
        try {
            Doc d = e.s.doc.copy();
            d.upperEv.clear();
            d.lowerEv.clear();
            d.upperEv.add(new Doc.Ev(100f, 100.5f, d.upper, d.upper + 30f, 1f));
            d.upperEv.add(new Doc.Ev(100.5f, 101f, d.upper + 30f, d.upper - 20f, 2f));
            d.lowerEv.add(new Doc.Ev(99.75f, 100.75f, d.lower, d.lower + 40f, 1.5f));
            d.upperLay.clear();
            d.lowerLay.clear();
            d.upperLay.add(new Doc.Ev(99.75f, 100.25f, 0f, 25f, 1f));
            d.upperLay.add(new Doc.Ev(100f, 100.9f, -15f, 10f, 2f));
            d.lowerLay.add(new Doc.Ev(99.4f, 99.6f, 12f, 12f, 1f));
            d.lowerLay.add(new Doc.Ev(100.2f, 100.6f, 0f, -30f, 1f));
            float lane = -840f;  // the old lane −4
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
        float lane = -840f;  // the old lane −4
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
            + " (expect true, -140, 730)");
        e.undo();
        v = e.s.doc.veilByUid(polyUid);
        log("veil drag undone: centre " + (v != null ? Doc.num(v.x) + ", " + Doc.num(v.y) : "-")
            + " (expect -320, 640)");
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
                    c = ch.chartConstant() + " at " + Doc.num(ch.bpm()) + ", " + ch.fixedLanesAt(0f) + " lanes";
                }
            } catch (Exception ex) {
                c = ex.toString();
            }
            r.append(", ").append(d.d.label()).append(' ').append(c);
        }
        log(r + " (expect Surface, Surface 1 at 120, 6 lanes, Shallow 2 at 120, 6 lanes, Deep 3 at 120, 6 lanes,"
            + " Abyss -)");
    }

    /** Saving one difficulty writes its tempo into the song's other difficulty files (Kelvin 16:03). */
    private int[] at = {0, 0};
    private Library.Song cropSong;

    private JacketCrop crop() {
        return app.view() instanceof LibraryScreen ? ((LibraryScreen) app.view()).crop : null;
    }

    /** The audio row's y, and the middle of the tablet's touch bar under the preview. */
    private static final int AUDIO_Y = Math.round(EditorScreen.R_AUDIO + 12f);
    private static final float TOUCH_BAR_Y = EditorScreen.PY + EditorScreen.PH + 30f;

    /** @return screen y of reference y {@code ref} counted down from the preview's top edge */
    private static int py(float ref) {
        return Math.round(EditorScreen.PY + ref * EditorScreen.K);
    }

    /** @return {@code ref} reference units in screen pixels */
    private static int px(float ref) {
        return Math.round(ref * EditorScreen.K);
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
            log("selected " + pick.nameZh() + " x " + pick.x + " beat " + pick.judgement);
        } else log("no note near beat " + e.beat);
    }

    /** Motion over beats 24–26 but judged at 29: shown early, judged unseen (2026-10-13 rule). */
    private void motionBeforeJudgement(EditorScreen e) {
        Doc.DNote x = new Doc.DNote(NoteType.TAP, Line.LOWER, -120f, 29f);
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

    /** An old chart opens without lanes: every key keeps its place as x (lane moves as x events). */
    private void lanesAfterOpen(EditorScreen e) {
        Doc d = e.s.doc;
        int withEv = 0, rel = 0, all = 0, falls = 0;
        for (Doc.DNote n : d.notes) {
            if (!n.xEv.isEmpty()) withEv++;
            for (Doc.Ev v : n.move) if (n.defaultFall(v)) falls++;
            for (java.util.List<Doc.Ev> list : java.util.Arrays.asList(n.move, n.rot, n.alpha, n.heightEv)) {
                for (Doc.Ev v : list) {
                    all++;
                    if (v.rel) rel++;
                }
            }
        }
        log("opened: " + d.notes.size() + " notes, " + withEv + " with x events; note events " + all + ", relative "
            + rel + " (expect the " + falls + " default falls)");
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
        Doc.DNote x = new Doc.DNote(NoteType.TAP, Line.LOWER, 600f, 30f);
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
                if (o.type == NoteType.TAP && o.line == Line.LOWER && Math.abs(o.x - 360f) < 1f
                    && Math.abs(o.judgement - yb) < 1e-3f) {
                    taken = true;
                    yb += 0.125f;
                    break;
                }
            }
        }
        Doc.DNote y = new Doc.DNote(NoteType.TAP, Line.LOWER, 360f, yb);
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
        Doc.DNote m = new Doc.DNote(NoteType.MINE, Line.LOWER, 360f, 40.5f);
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
        Doc.DNote mi = new Doc.DNote(NoteType.MINE, Line.LOWER, 360f, 41.25f),
            tp = new Doc.DNote(NoteType.TAP, Line.LOWER, 360f, 41.75f);
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
        log("tap in the block: " + checkFor(tp.uid) + " (expect 长水雷封锁冲突)");
        e.s.doc.notes.remove(tp);
        e.s.doc.notes.remove(mi);
        e.s.changed(false);
        // stacked mines and a mine on a tap's spot all load (game fdc9922, Kelvin 10-22: 「地雷的限制不用那么多」)
        Doc.DNote m1 = new Doc.DNote(NoteType.MINE, Line.LOWER, 600f, 44.03125f),
            m2 = new Doc.DNote(NoteType.MINE, Line.LOWER, 600f, 44.03125f);
        Doc.DNote t3 = new Doc.DNote(NoteType.TAP, Line.LOWER, 600f, 44.03125f);
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
        Doc.DNote s1 = new Doc.DNote(NoteType.TAP, Line.LOWER, 600f, 44.03125f),
            s2 = new Doc.DNote(NoteType.TAP, Line.LOWER, 600f, 44.03125f);
        for (Doc.DNote x : new Doc.DNote[]{s1, s2}) {
            x.approach(2f, Doc.SPAWN, 1f);
            e.s.doc.notes.add(x);
        }
        e.s.changed(false);
        log("stacked taps: built " + (e.s.note(s1) != null) + ", " + (e.s.note(s2) != null) + ", "
            + (e.s.note(s1) != e.s.note(s2)) + ", check " + checkFor(s2.uid)
            + " (expect true, true, true, check 同一位置同一拍叠着的音符 1 组（每个要一根手指）)");
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
     * 旋转中心 (Kelvin 10-16): a turned note's pivot reaches the preview chart and survives save → reload; only a
     * non-origin pivot is written.
     */
    private void pivotTest(EditorScreen e) {
        Doc d = e.s.doc;
        Doc.DNote n = new Doc.DNote(NoteType.TAP, Line.LOWER, -360f, 14f);
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
     * 左右镜像 / 上下镜像 (Kelvin 2026-10-22): sky ↔ ground keeps x and beat and turns the other way about the pivot
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
        log("mirror up-down: " + n.line + " x " + n.x + ", angle " + a + ", pivot " + n.pivotX + ", " + n.pivotY
            + ", head x same " + (Math.abs(x1 - x0) < 1e-3f) + ", y mirrored " + (Math.abs(y1 + y0) < 1e-3f)
            + " (expect UPPER x -360.0, -60.0, -12.0, -20.0, true, true)");
        float x = n.x;
        e.mirror();
        log("mirror left-right: x " + x + " -> " + n.x + ", angle " + n.rot.get(0).to + ", pivot x " + n.pivotX
            + " (expect 360.0, 60.0, 12.0)");
        e.mirror();
        e.mirrorUpDown();
        n.pivotX = 0f;
        log("each twice: " + n.line + " x " + n.x + ", angle " + n.rot.get(0).to
            + " (expect LOWER x -360.0, 60.0)");
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
            Doc.DNote n = new Doc.DNote(NoteType.TAP, Line.LOWER, 120f, 4f);
            n.approach(2f, o[3], 1f);
            n.pivotY = o[4];
            Doc.DNote h = new Doc.DNote(NoteType.HOLD, Line.UPPER, -120f, 6f);
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
            if (g.judgement() != n.judgement || Math.abs(g.x() - n.x) > 0.01f || g.line() != n.line) continue;
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
                    m.append(' ').append(n.x).append('@').append(n.judgement).append(v.rel ? " rel" : " abs");
                }
            }
            log("Aleph-0 Surface in the editor, movement:" + m);
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
            tide = new Doc.DNote(NoteType.UNIVERSAL, Line.LOWER, 0f, 8f);
            tide.move.add(new Doc.Ev(6f, 8f, Doc.SPAWN, 0f, 1f));
            d.notes.add(tide);
        }
        tide.waveSpeed = 0.5f;
        Doc.DNote key = null;
        for (Doc.DNote n : d.notes) if (n.inLane() && key == null) key = n;
        float kx = 0f, kj = Float.NaN;
        if (key != null) {
            key.x -= 30f;
            kx = key.x;
            kj = key.judgement;
            Doc.Ev slide = new Doc.Ev(kj - 2f, kj, kx, kx + 60f, 1f);
            slide.rel = true;
            key.xEv.add(slide);
            key.orbit = true;
            key.orbitX = -160f;                                // 800 in the 1920-wide frame
            key.orbitY = 500f;
            Doc.Ev move = new Doc.Ev(key.judgement - 2f, key.judgement - 1f, -160f, -60f, 1f);
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
        float slidePreview = Float.NaN;
        String slideBack = "none", orbitPreview = "none", orbitBack = "none";
        for (Note n : preview.notes()) {
            inPreview = Math.max(inPreview, n.waveSpeed());
            if (!n.xEvents().isEmpty() && Math.abs(n.judgement() - kj) < 1e-4f) slidePreview = n.xAt(kj - 1f);
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
            if (Math.abs(n.judgement - kj) < 1e-4f) {
                for (Doc.Ev v : n.xEv) slideBack = Im.fmt(v.from) + " to " + Im.fmt(v.to) + (v.rel ? " rel" : " abs");
            }
            for (Doc.Ev v : n.orbXEv) {
                orbitBack = (n.orbit ? "orbit " : "no orbit ") + Im.fmt(n.orbitX) + ", " + Im.fmt(n.orbitY) + ", x "
                    + Im.fmt(v.from) + " to " + Im.fmt(v.to) + (v.rel ? " rel" : " abs") + ", radius "
                    + Im.fmt(n.orbitRadius) + ", angle " + n.orbAngEv.size() + " event"
                    + (n.orbAngEv.size() == 1 && n.orbAngEv.get(0).rel ? " rel" : "") + ", conflict " + n.orbitConflict;
            }
        }
        log("wave speed: preview " + inPreview + ", v5 " + r.json.matches("(?s).*\"waveSpeed\"\\s*:\\s*0\\.5.*")
            + ", read back " + inBack + ", problems " + r.problems + " (expect 0.5, true, 0.5, [])");
        log("x events: preview halfway " + slidePreview + ", read back " + slideBack + " (expect " + (kx + 30f) + ", "
            + Im.fmt(kx) + " to " + Im.fmt(kx + 60f) + " rel)");
        log("orbit: preview at the judgement " + orbitPreview + ", read back " + orbitBack
            + " (expect 900.0, 500.0, radius -40.0, angle 90.0, conflict 2, orbit -160, 500, x -160 to -60 rel,"
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
            // v5 (Kelvin 2026-10-27), v6 without lanes (2026-11-05): the song values went to song.json, the editor's
            // keys under "editor"
            String text = e.s.file.readString("UTF-8");
            org.kelvinizer.elements.SongInfo song = org.kelvinizer.elements.SongInfo.besides(e.s.file);
            log("v6 file: format 6 " + text.matches("(?s)\\{\\s*\"format\"\\s*:\\s*6,.*") + ", no title "
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
            case 50: tap(EditorScreen.PX + 384f, TOUCH_BAR_Y); break;  // 多选
            case 53: tap(EditorScreen.PX + 30f, TOUCH_BAR_Y); break;   // 撤销
            case 56:
                if (e != null && e.s.single() != null) log("tablet 撤销: 判定拍 " + Im.fmt(e.s.single().judgement)
                    + " (expect " + Im.fmt(noteBeat) + ")");
                if (e != null) log("tablet zoom before: " + Im.fmt((float) e.tlSpan) + " beats");
                tap(117f, EditorScreen.R_RULER + 17f);                // + (zoom in)
                break;
            case 59:
                if (e != null) log("tablet zoom after +: " + Im.fmt((float) e.tlSpan) + " beats (expect 12.8)");
                break;
            case 62: tap(EditorScreen.TR - 38f, TOUCH_BAR_Y); break;   // 统计
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
            case 259: shot("c19_curtain"); break;
            case 262: shot("c20_cards"); break;
            case 265: shot("c21_drag"); break;
            case 267: shot("c22_reorder"); break;
            case 276: shot("c23_paste"); break;
            case 284: shot("c24_slash"); break;
            case 301: shot("c25_drag"); break;
            case 305: shot("c26_layers"); break;
            case 316: shot("c27_float"); break;
            case 331: shot("c28_float_path"); break;
            case 334: shot("c29_float_card"); break;
            case 429: shot("c32_lanes"); break;
            case 441: shot("c33_quick"); break;
            case 449: shot("c33_quick_shake"); break;
            case 451: shot("c34_stroke"); break;
            case 458: shot("c35_bars"); break;
            case 460:
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
