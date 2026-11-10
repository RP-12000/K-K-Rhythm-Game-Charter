package org.kelvinizer.charter;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.PixmapIO;
import com.badlogic.gdx.utils.BufferUtils;
import com.badlogic.gdx.utils.ScreenUtils;
import org.kelvinizer.elements.ChartFormatV7;
import org.kelvinizer.elements.Fn;
import org.kelvinizer.elements.KeyKind;
import org.kelvinizer.elements.Key7;
import org.kelvinizer.elements.Lane7;
import org.kelvinizer.elements.NoteDistortion;
import org.kelvinizer.elements.NoteGlitch;
import org.kelvinizer.elements.NoteTrail;
import org.kelvinizer.elements.Seg;
import org.kelvinizer.elements.SongInfo;
import org.kelvinizer.library.Difficulty;

import java.util.Locale;

/**
 * 自测脚本（-Dkk.autotest=文件夹）：写一张 v7 测试谱，打开它，按顺序操作各页、截图，检查保存后原样读回。
 * Self-test run with {@code -Dkk.autotest=<folder>} (window exactly 1920 × 1080): writes a v7 test chart (every key
 * kind, a veil and a curtain) into the first song, opens it, drives the inspector pages, the curve window, placing and
 * dragging keys in the preview, undo / redo, copying a lane with its keys, the six views, the stats and the checks,
 * saves, checks the file reads back unchanged, writes one screenshot per step and a log, then exits.
 */
final class AutoTest {
    private final CharterApp app;
    private final String dir;
    private final StringBuilder log = new StringBuilder();
    private int frame, failures;
    private int[] pending, at;
    private Library.Song song;
    private Difficulty diff;
    private int placedUid, lanesBefore, keysBefore;

    AutoTest(CharterApp app, String dir) {
        this.app = app;
        this.dir = dir;
    }

    private EditorScreen ed() {
        return app.view() instanceof EditorScreen ? (EditorScreen) app.view() : null;
    }

    /** Before the frame: injects input. */
    void before() {
        frame++;
        if (pending != null) {
            app.im.touchUp(pending[0], pending[1], 0, Input.Buttons.LEFT);
            pending = null;
            return;
        }
        EditorScreen e = ed();
        if (frame >= 5 && e == null) {
            if (frame == 5) fail("the editor did not open");
            if (frame == 6) finish();
            return;
        }
        switch (frame) {
            case 2: writeAndOpen(); break;
            case 3: renamed(); break;
            case 5: opened(e); break;
            case 10: pick(e, KeyKind.TAP); e.insp.tab = Inspector.NOTE; break;
            case 14: e.insp.scroll[Inspector.NOTE] = 4000f; break;     // down to 残影 · 故障 · 扭曲
            case 15: e.insp.scroll[Inspector.NOTE] = 0f; e.s.laneSel = e.s.doc.lanes.get(0).id; e.insp.tab = Inspector.LANE; break;
            case 20: e.insp.tab = Inspector.CAMERA; break;
            case 25: e.s.volSel = e.s.doc.vols.get(0).uid; e.insp.tab = Inspector.VOLUME; break;
            case 30: e.insp.tab = Inspector.CHART; break;
            case 35: segments(); break;
            case 40: place(e); break;
            case 44: placed(e); break;
            case 48: pressKey(e, 0f); break;
            case 49: dragKey(e, 0.35f); break;
            case 50: dragKey(e, 0.7f); break;
            case 51: dragKey(e, 1f); break;
            case 52: app.im.touchUp(at[0], at[1], 0, Input.Buttons.LEFT); break;
            case 54: dragged(e); break;
            case 56: app.im.testCtrl = true; app.im.keyDown(Input.Keys.Z); break;
            case 58: app.im.keyUp(Input.Keys.Z); undone(e, 3f, "undo"); app.im.keyDown(Input.Keys.Y); break;
            case 60: app.im.keyUp(Input.Keys.Y); app.im.testCtrl = false; undone(e, 4f, "redo"); break;
            case 62: copyLane(e); break;
            case 67: pick(e, KeyKind.STREAM); e.insp.tab = Inspector.NOTE; e.curves.show("k-h"); break;
            case 72: e.curves.on = false; e.viewPreset = 4; break;
            case 75: e.viewPreset = 2; break;
            case 78: e.viewPreset = -1; e.orbitYaw = 30f; e.orbitPitch = 15f; break;
            case 80: e.orbitYaw = e.orbitPitch = 0f; break;
            case 81: cubeClick(e, 20); break;                       // 前右上, a corner (released at 82)
            case 83: cubeClicked(e, 20); break;
            case 84: e.viewPreset = -1; e.statsOpen = true; break;
            case 85: log("stats: " + e.stats.summary(e.s)); break;
            case 86: e.statsOpen = false; badLane(e); break;
            case 88: checked(e); break;
            case 90: save(e); break;
            case 92: openOld(); break;
            case 96: oldOpened(e); break;
            case 100: e.orbitYaw = 30f; e.orbitPitch = 15f; break;
            case 104: e.orbitYaw = e.orbitPitch = 0f; e.overlays = false; break;
            case 110: finish(); break;
            default:
        }
    }

    /** After the frame (before the buffer swap): screenshots. */
    void after() {
        switch (frame) {
            case 8: shot("v1_editor"); break;
            case 13: shot("v2_note"); break;
            case 14: shot("v2b_note_effects"); break;
            case 18: shot("v3_lane"); break;
            case 23: shot("v4_camera"); break;
            case 28: shot("v5_volume"); break;
            case 33: shot("v6_chart"); break;
            case 46: shot("v7_placed"); break;
            case 55: shot("v8_dragged"); break;
            case 65: shot("v9_lane_pasted"); break;
            case 70: shot("v10_curves"); break;
            case 73: shot("v11_top"); break;
            case 76: shot("v12_left"); break;
            case 79: shot("v13_orbit"); break;
            case 83: shot("v13b_corner"); break;
            case 85: shot("v14_stats"); break;
            case 89: shot("v15_checks"); break;
            case 99: shot("v16_old_chart"); break;
            case 103: shot("v17_old_turned"); break;
            case 108: shot("v18_old_live"); break;
            default:
        }
    }

    // ================================================================== steps

    /**
     * 旧文件名 (Kelvin 2026-11-10 05:50): reading the library renamed every old-named song folder; an old folder with its
     * assets.txt lines is renamed Shallow → Medium first, then Surface → Shallow.
     */
    private void renamed() {
        int old = 0;
        for (Library.Song s : app.lib.songs) if (s.dir.child("Surface.json").exists()) old++;
        FileHandle dir = app.lib.charts.child("zz-old-names"), list = app.lib.charts.parent().child("assets.txt");
        String keep = list.exists() ? list.readString("UTF-8") : null, rel = app.lib.charts.name() + "/zz-old-names/";
        dir.child("Surface.json").writeString("1", false);
        dir.child("Shallow.json").writeString("2", false);
        dir.child("Deep.json").writeString("3", false);
        list.writeString((keep != null ? keep : "") + rel + "Deep.json\n" + rel + "Shallow.json\n" + rel + "Surface.json\n",
            false, "UTF-8");
        boolean done = app.lib.renameCharts(dir);
        String text = list.readString("UTF-8");
        expect("旧文件名: songs still old-named, then an old folder renamed with its assets.txt lines", old * 100
            + (done && dir.child("Shallow.json").readString().equals("1") && dir.child("Medium.json").readString().equals("2")
                && dir.child("Deep.json").readString().equals("3") && !dir.child("Surface.json").exists() ? 1 : 0)
            + (text.endsWith(rel + "Deep.json\n" + rel + "Medium.json\n" + rel + "Shallow.json\n") ? 10 : 0), 11);
        if (keep != null) list.writeString(keep, false, "UTF-8");
        else list.delete();
        dir.deleteDirectory();
    }

    /** Writes the test chart into the first song's first difficulty and opens it. */
    private void writeAndOpen() {
        if (app.lib.songs.isEmpty()) {
            fail("no songs in the library");
            return;
        }
        song = app.lib.songs.get(0);
        diff = Difficulty.values()[0];
        Doc d = Doc.blank(song.name(), "", "", diff.label(), 120f, 0f);
        int[] ids = new int[d.lanes.size()];
        for (int i = 0; i < ids.length; i++) ids[i] = d.lanes.get(i).id;
        Doc.DKey tap = add(d, KeyKind.TAP, ids[0], false, 4f, 0f);
        tap.k.trails.add(NoteTrail.of(0.5f, 16f, 0.6f, 1f, NoteTrail.Mode.PATH, 3f, 4f));   // 残影 · 故障 · 扭曲 (7.228)
        tap.k.glitches.add(NoteGlitch.of(2f, 4f, 0.5f, 1f, 1f, 8f, 1f, 1f, 0.3f));
        tap.k.distortions.add(NoteDistortion.of(2f, 4f, 1f, 2f, 1f));
        add(d, KeyKind.STREAM, ids[1], false, 5f, 2f);
        add(d, KeyKind.DRAG, ids[2], true, 6f, 0f);
        add(d, KeyKind.MINE, ids[3], false, 6.5f, 0f);
        add(d, KeyKind.STREAM_MINE, ids[4], true, 7f, 1f);
        Doc.DKey w = add(d, KeyKind.WAVE, ids[0], false, 8f, 0f);
        w.k.lanes = new int[]{ids[0], ids[1], ids[2]};
        Doc.DKey t = add(d, KeyKind.TIDE, ids[3], true, 9f, 1f);
        t.k.lanes = new int[]{ids[3], ids[4], ids[5 % ids.length]};
        Doc.DVol veil = new Doc.DVol(Doc.newVolume(false, -600f, 200f, 0f, 2f, 6f));
        veil.v.o.x.constant = 1f;                                       // turned 45° about y by its o (1, 0, 1)
        veil.v.glitches.add(NoteGlitch.of(0f, 8f, 0.4f, 0.4f, 1f, 8f, 1f, 1f, 0.3f));
        veil.v.distortions.add(NoteDistortion.of(0f, 8f, 2f, 2f, 1f));
        d.vols.add(veil);
        Doc.DVol curtain = new Doc.DVol(Doc.newVolume(true, 600f, -100f, 0f, 3f, 6f));
        curtain.v.b.segs.add(new Seg(0f, 2f, 0f, 16f, 1f));             // its blur comes in (模糊 b)
        d.vols.add(curtain);
        d.lanes.get(0).o.constant = 0.5f;                               // opacity: a half see-through lane
        w.k.o.constant = 0.25f;                                         // and a faint 浪花
        d.fov.segs.add(new Seg(100f, 101f, 11f, 60f, 1f));              // the camera's view widens, after the shots
        d.sortKeys();
        FileHandle f = song.diffs[diff.ordinal()].file;
        app.lib.writeChart(song, f, d);
        log("test chart written: " + f.name() + ", " + d.lanes.size() + " lanes, " + d.keys.size() + " keys");
        try {
            app.show(new EditorScreen(app, Session.open(app.lib, song, diff)));
        } catch (Exception ex) {
            fail("open: " + ex);
        }
    }

    private static Doc.DKey add(Doc d, KeyKind kind, int lane, boolean sky, float beat, float len) {
        Doc.DKey k = new Doc.DKey(Doc.newKey(kind, lane, sky, beat, len));
        d.keys.add(k);
        return k;
    }

    private void opened(EditorScreen e) {
        e.seekBeat(3f);
        Doc d = e.s.doc;
        log("opened: " + d.lanes.size() + " lanes, " + d.keys.size() + " keys, " + d.vols.size() + " volumes; "
            + e.checks.summary());
        expect("keys read", d.keys.size(), 7);
        expect("volumes read", d.vols.size(), 2);
        expect("veil read: its o x", d.vols.get(0).v.o.x.constant, 1f);
        expect("lane read: its opacity", d.lanes.get(0).o.constant, 0.5f);
        int faint = 0;
        for (Doc.DKey k : d.keys) if (k.k.o.constant == 0.25f) faint++;
        expect("keys read: one at opacity 0.25", faint, 1);
        expect("camera read: its 视角 segment", d.fov.segs.size(), 1);
        double[] ax = Scene.volAxes(d.vols.get(0).v, 3f);                // its depth edge along (1, 0, 1) / √2
        expect("veil turned: its z axis x", Math.round(ax[6] * 1000) / 1000f, 0.707f);
        int judged = 0;
        for (Doc.DKey k : d.keys) if (!Float.isNaN(k.judge())) judged++;
        expect("keys with a judge beat", judged, 7);
        Doc.DKey tap = null;
        for (Doc.DKey k : d.keys) if (k.k.kind == KeyKind.TAP) tap = k;
        if (tap == null) {
            fail("no TAP key read");
            return;
        }
        expect("key read: 残影 · 故障 · 扭曲", tap.k.trails.size() * 100 + tap.k.glitches.size() * 10
            + tap.k.distortions.size(), 111);
        expect("veil read: 故障 · 扭曲", d.vols.get(0).v.glitches.size() * 10 + d.vols.get(0).v.distortions.size(), 11);
        expect("curtain read: its 模糊 b segment", d.vols.get(1).v.b.segs.size(), 1);
        Key7 c = Doc.copyKey(tap.k);
        Doc.shiftKey(c, 1f);
        expect("shifted copy: its 故障 from", c.glitches.get(0).from(), 3f);
        expect("shifted copy: its 残影 to", c.trails.get(0).to(), 5f);
        expect("the key itself kept: its 扭曲 from", tap.k.distortions.get(0).from(), 2f);
    }

    private void pick(EditorScreen e, KeyKind kind) {
        for (Doc.DKey k : e.s.doc.keys) {
            if (k.k.kind == kind) {
                e.s.select(k, false);
                return;
            }
        }
        fail("no " + kind + " key to select");
    }

    /** Doc.setAt on plain functions: a constant, inside, after and before segments. */
    private void segments() {
        Fn f = new Fn(480f);
        Doc.setAt(f, 4f, 500f);
        expect("setAt on a constant", f.constant, 500f);
        f.segs.add(new Seg(2f, 6f, 0f, 100f, 1f));
        Doc.setAt(f, 4f, 70f);
        expect("setAt inside: segments", f.segs.size(), 2);
        expect("setAt inside: value", f.at(4f), 70f);
        expect("setAt inside: end kept", f.at(6f), 100f);
        Doc.setAt(f, 8f, 10f);
        expect("setAt after: segments", f.segs.size(), 3);
        expect("setAt after: value", f.at(8f), 10f);
        expect("setAt after: ramp from the end", f.at(7f), 55f);
        Doc.setAt(f, 1f, 5f);
        expect("setAt before: segments", f.segs.size(), 4);
        expect("setAt before: value", f.at(1f), 5f);
        expect("setAt before: joins the first", f.at(2f), 0f);
        Doc.setAt(f, 6f, 90f);
        expect("setAt on a shared end: segments", f.segs.size(), 4);
        expect("setAt on a shared end: value", f.at(6f), 90f);
        Fn g = new Fn(3f);
        Doc.keyAt(g, 2f);
        expect("keyAt on a constant: one jump", g.segs.size(), 1);
        expect("keyAt keeps the value", g.at(5f), 3f);
    }

    /** Clicks the 水滴 tool on lane 5 one beat above its 底面 at beat 3: a key judged at the playhead, beat 3. */
    private void place(EditorScreen e) {
        e.setTool(EditorScreen.T_TAP);
        e.s.placeSky = false;
        keysBefore = e.s.doc.keys.size();
        float[] xy = onLane(e, 4, 1f);
        if (xy == null) {
            fail("lane 5 is not on screen");
            return;
        }
        click(Math.round(xy[0]), Math.round(xy[1]));
    }

    private void placed(EditorScreen e) {
        Doc d = e.s.doc;
        expect("placed: keys", d.keys.size(), keysBefore + 1);
        Doc.DKey k = e.s.single();
        if (k == null) {
            fail("placed: no single selection");
            return;
        }
        placedUid = k.uid;
        log(String.format(Locale.ROOT, "placed %s on lane %s, %s, judged at %.3f", k.k.kind, d.laneLabel(k.k.lane),
            k.k.sky ? "天面" : "底面", k.judge()));
        expect("placed: kind", k.k.kind.ordinal(), KeyKind.TAP.ordinal());
        expect("placed: lane", k.k.lane, d.lanes.get(4).id);
        expect("placed: judge beat", k.judge(), 3f);
        e.setTool(EditorScreen.T_SELECT);
    }

    /** Presses the placed key in the preview ({@code beats} above the 底面 at beat 3). */
    private void pressKey(EditorScreen e, float beats) {
        float[] xy = onLane(e, 4, beats);
        if (xy == null) return;
        at = new int[]{Math.round(xy[0]), Math.round(xy[1])};
        app.im.touchDown(at[0], at[1], 0, Input.Buttons.LEFT);
    }

    private void dragKey(EditorScreen e, float beats) {
        float[] xy = onLane(e, 4, beats);
        if (xy == null) return;
        at = new int[]{Math.round(xy[0]), Math.round(xy[1])};
        app.im.touchDragged(at[0], at[1], 0);
    }

    private void dragged(EditorScreen e) {
        Doc.DKey k = e.s.doc.byUid(placedUid);
        if (k == null) {
            fail("dragged: the key is gone");
            return;
        }
        log(String.format(Locale.ROOT, "dragged along its lane: judged at %.3f", k.judge()));
        expect("dragged: judge beat", k.judge(), 4f);
    }

    private void undone(EditorScreen e, float want, String what) {
        Doc.DKey k = e.s.doc.byUid(placedUid);
        expect(what + ": judge beat", k != null ? k.judge() : Float.NaN, want);
    }

    /** Clicks the view cube's side k ({@link Scene#VIEW_DIR}) where it shows now. */
    private void cubeClick(EditorScreen e, int k) {
        float[] xy = e.cubeCentre(k);
        if (xy == null) {
            fail("cube: " + Scene.VIEW_NAMES[k] + " faces away");
            return;
        }
        click(Math.round(xy[0]), Math.round(xy[1]));
    }

    private void cubeClicked(EditorScreen e, int k) {
        expect("cube click: view", e.viewPreset, k);
        log(String.format(Locale.ROOT, "cube: clicked %s, looking from yaw %.1f°, pitch %.1f°", Scene.VIEW_NAMES[k],
            Scene.VIEW_YAW[k], Scene.VIEW_PITCH[k]));
    }

    /** Copies lane 5 with its keys and pastes it (op F). */
    private void copyLane(EditorScreen e) {
        Doc d = e.s.doc;
        lanesBefore = d.lanes.size();
        keysBefore = d.keys.size();
        Lane7 l = d.lanes.get(4);
        int on = d.boundTo(l.id).size();
        e.s.sel.clear();
        e.s.laneSel = l.id;
        e.copy(false);
        e.paste();
        expect("pasted lane: lanes", d.lanes.size(), lanesBefore + 1);
        expect("pasted lane: keys", d.keys.size(), keysBefore + on);
        Lane7 n = d.lane(e.s.laneSel);
        if (n == null || n == l) {
            fail("pasted lane: not picked");
            return;
        }
        expect("pasted lane: its keys", d.keysOn(n.id).size(), on);
        log("lane " + d.laneLabel(l.id) + " copied with " + on + " keys as lane " + d.laneLabel(n.id));
        e.laneIds = true;                                               // 「显示：编号」, then back to 名称
        String byId = e.laneTag(n.id);
        e.laneIds = false;
        expect("显示：编号 / 名称: the pasted lane's 编号, then its name", (byId.equals(String.valueOf(n.id)) ? 1 : 0)
            + (e.laneTag(n.id).equals(d.laneLabel(n.id)) && !byId.equals(d.laneLabel(n.id)) ? 10 : 0), 11);
    }

    /** Adds a key on a lane that does not exist: the checks list it. */
    private void badLane(EditorScreen e) {
        e.s.mark();
        e.s.doc.keys.add(new Doc.DKey(Doc.newKey(KeyKind.TAP, 999, false, 12f, 0f)));
        e.s.doc.sortKeys();
        e.s.changed(false);
        e.insp.tab = Inspector.CHART;
    }

    private void checked(EditorScreen e) {
        e.checks.update(e.s);
        log("checks: " + e.checks.problems + " problems; " + e.checks.items.get(0).text);
        expect("checks: a key on a missing lane", e.checks.problems, 1);
        e.undo();
        e.checks.update(e.s);
        expect("checks after undo", e.checks.problems, 0);
    }

    /** Saves, then reads the file back and compares it with the editor's document. */
    private void save(EditorScreen e) {
        e.s.mark();                                                     // one 假键, which must survive the save
        e.s.doc.keys.get(0).k.fake = true;
        e.s.changed(false);
        e.checks.update(e.s);
        expect("假键: checks count it apart", e.checks.fakes, 1);
        log("stats with a 假键: " + e.stats.summary(e.s));
        e.save(null);
        try {
            FileHandle f = e.s.file;
            Doc back = Doc.of(ChartFormatV7.read(f.read()), SongInfo.besides(f), diff.label());
            String again = back.toJson();
            int fakes = 0;
            for (Doc.DKey k : back.keys) if (k.k.fake) fakes++;
            expect("read back: 假键", fakes, 1);
            boolean same = again.equals(e.s.doc.toJson());
            log("saved and read back: " + (same ? "same" : "DIFFERENT") + " (" + f.length() + " bytes)");
            if (!same) {
                failures++;
                f.sibling("readback.json").writeString(again, false, "UTF-8");
            }
        } catch (Exception ex) {
            fail("read back: " + ex);
        }
    }

    /** Opens the first older chart of another song: the game's converter makes it v7. */
    private void openOld() {
        for (int i = 1; i < app.lib.songs.size(); i++) {
            Library.Song so = app.lib.songs.get(i);
            for (Difficulty df : Difficulty.values()) {
                FileHandle f = so.diffs[df.ordinal()].file;
                if (!f.exists() || ChartFormatV7.isV7(f.readBytes())) continue;
                try {
                    Session ses = Session.open(app.lib, so, df);
                    log("old chart " + so.name() + " " + df.label() + ": " + ses.doc.lanes.size() + " lanes, "
                        + ses.doc.keys.size() + " keys; note: " + ses.loadNote);
                    app.show(new EditorScreen(app, ses));
                } catch (Exception ex) {
                    fail("open old chart " + f.path() + ": " + ex);
                }
                return;
            }
        }
        fail("no older chart to open");
    }

    private void oldOpened(EditorScreen e) {
        Doc d = e.s.doc;
        float first = Float.NaN, last = Float.NaN;
        for (Doc.DKey k : d.keys) {
            float j = k.judge();
            if (Float.isNaN(j)) continue;
            if (Float.isNaN(first)) first = j;
            last = j;
        }
        float at = Float.isNaN(first) ? 8f : first + (last - first) * 0.3f;
        e.seekBeat(at);
        log(String.format(Locale.ROOT, "old chart at beat %.2f; preview built: %s; %s", at, e.s.runtime != null,
            e.checks.summary()));
        if (e.s.runtime == null) fail("the game could not build the old chart");
    }

    private void finish() {
        log(failures == 0 ? "ALL PASSED" : failures + " FAILED");
        Gdx.files.absolute(dir).child("autotest.log").writeString(log.toString(), false, "UTF-8");
        Gdx.app.exit();
    }

    // ================================================================== helpers

    /** @return lane {@code ix}'s centre {@code beats} of default fall above its 底面 at the playhead, on screen */
    private static float[] onLane(EditorScreen e, int ix, float beats) {
        float[] lf = new float[Scene.LF];
        Scene.laneFrame(e.s.doc.lanes.get(ix), e.beat, lf);
        double[] p = new double[3];
        Scene.at(lf, 0, lf[Scene.GND] + beats * Doc.APPROACH, 0, p, 0);
        float[] out = new float[2];
        return e.sc.project(p[0], p[1], p[2], out, 0) ? out : null;
    }

    private void click(int x, int y) {
        app.im.touchDown(x, y, 0, Input.Buttons.LEFT);
        pending = new int[]{x, y};
    }

    private void expect(String what, float got, float want) {
        boolean ok = Math.abs(got - want) < 1e-3f;
        if (!ok) failures++;
        log((ok ? "ok   " : "FAIL ") + what + ": " + fmt(got) + (ok ? "" : " (expect " + fmt(want) + ")"));
    }

    private static String fmt(float v) {
        return v == Math.rint(v) ? String.valueOf((int) v) : String.format(Locale.ROOT, "%.3f", v);
    }

    private void fail(String what) {
        failures++;
        log("FAIL " + what);
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
