package org.kelvinizer.charter;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.PixmapIO;
import com.badlogic.gdx.utils.BufferUtils;
import com.badlogic.gdx.utils.ScreenUtils;
import org.kelvinizer.elements.Chart7;
import org.kelvinizer.elements.ChartFormatV7;
import org.kelvinizer.elements.Fn;
import org.kelvinizer.elements.Group7;
import org.kelvinizer.elements.KeyKind;
import org.kelvinizer.elements.Key7;
import org.kelvinizer.elements.Lane7;
import org.kelvinizer.elements.NoteDistortion;
import org.kelvinizer.elements.NoteGlitch;
import org.kelvinizer.elements.NoteTrail;
import org.kelvinizer.elements.Seg;
import org.kelvinizer.elements.SongInfo;
import org.kelvinizer.library.Difficulty;

import java.util.ArrayList;
import java.util.Locale;

/**
 * 自测脚本（-Dkk.autotest=文件夹）：写一张 v7 测试谱，打开它，按顺序操作各页、截图，检查保存后原样读回。
 * Self-test run with {@code -Dkk.autotest=<folder>} (window exactly 1920 × 1080): writes a v7 test chart (every key
 * kind, a veil and a curtain) into the first song, opens it, drives the inspector pages, the curve window, placing and
 * dragging keys in the preview, undo / redo, copying a lane with its keys, the six views, the stats and the checks,
 * moving the view by Ctrl+Alt + the arrows, saves, checks the file reads back unchanged; then on an old chart the
 * keyframes and the layered timeline (K, a bar's ease, dragging a ◆, a lane's life bar, placing a key in its row,
 * 只看选中, 搜索轨道, the line above the timeline); writes one screenshot per step and a log, then exits.
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
    /** The keyframe steps: their lane, where it comes in, the keyframe's beat, where the key goes. */
    private int kfLane;
    private float kfIn, kfT, kfKey;

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
            case 3: renamed(); trimmed(); break;
            case 5: opened(e); break;
            case 10: pick(e, KeyKind.TAP); e.insp.tab = Inspector.NOTE; break;
            case 14: e.insp.scroll[Inspector.NOTE] = 4000f; break;     // down to 残影 · 故障 · 扭曲
            case 15: e.insp.scroll[Inspector.NOTE] = 0f; e.s.laneSel = e.s.doc.lanes.get(0).id; e.insp.tab = Inspector.LANE; break;
            case 19: e.insp.scroll[Inspector.LANE] = 4000f; break;     // down to 透明度 · 水雷
            case 20: e.insp.scroll[Inspector.LANE] = 0f; e.insp.tab = Inspector.CAMERA; break;
            case 25: e.s.volSel = e.s.doc.vols.get(0).uid; e.insp.tab = Inspector.VOLUME; break;
            case 30: e.insp.tab = Inspector.CHART; break;
            case 35: segments(); break;
            case 36: normal(); break;
            case 37: mine(); break;
            case 38: groups(); break;
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
            case 109: e.overlays = true; app.im.testCtrl = app.im.testAlt = true; app.im.keyDown(Input.Keys.RIGHT); break;
            case 110: app.im.keyUp(Input.Keys.RIGHT); app.im.testShift = true; app.im.keyDown(Input.Keys.UP); break;
            case 111: app.im.keyUp(Input.Keys.UP); app.im.testShift = false; app.im.keyDown(Input.Keys.PAGE_UP); break;
            case 112: app.im.keyUp(Input.Keys.PAGE_UP); app.im.testCtrl = app.im.testAlt = false; break;
            case 113: moved(e); break;
            case 116: app.im.testCtrl = app.im.testAlt = true; app.im.keyDown(Input.Keys.NUM_0); break;
            case 117: app.im.keyUp(Input.Keys.NUM_0); app.im.testCtrl = app.im.testAlt = false; break;
            case 118: expect("move: 归位", Math.abs(e.viewMove[0]) + Math.abs(e.viewMove[1]) + Math.abs(e.viewMove[2]), 0f);
                kfIn = Math.round(e.beat) + 2f;
                e.seekBeat(kfIn);
                e.tlStart = kfIn - 4f;
                break;
            case 120: kfLaneNew(e); break;
            case 122: app.im.keyDown(Input.Keys.K); break;
            case 123: app.im.keyUp(Input.Keys.K); kfAdded(e); break;
            case 125: kfClick(e, "原点 v", kfT + 1f); break;           // its bar: the ease strip
            case 128: kfTile(e); break;
            case 130: kfEased(e); kfPress(e, "原点 v", kfT + 2f); break;
            case 131: case 132: kfDrag(e, kfT + 3f); break;
            case 133: app.im.touchUp(at[0], at[1], 0, Input.Buttons.LEFT); break;
            case 134: kfRetimed(e); break;
            case 136: app.im.keyDown(Input.Keys.K); break;
            case 137: app.im.keyUp(Input.Keys.K); kfRemoved(e); break;
            case 138: kfPress(e, null, kfIn + 2f); break;               // the life bar
            case 139: case 140: kfDrag(e, kfIn + 3f); break;
            case 141: app.im.touchUp(at[0], at[1], 0, Input.Buttons.LEFT); break;
            case 142: kfMoved(e); break;
            case 144: kfPlaced(e); e.layers.onlySel = true; break;
            case 145: expect("只看选中: layers", e.layers.layerCount(), 1);
                e.layers.onlySel = false;
                e.layers.filter = String.valueOf(kfLane);
                break;
            case 146: expect("搜索轨道: layers", e.layers.layerCount(), 1); e.layers.filter = ""; break;
            case 147:
                at = new int[]{Math.round(EditorScreen.TX + 300f), Math.round(EditorScreen.TL_TOP)};
                app.im.touchDown(at[0], at[1], 0, Input.Buttons.LEFT);
                break;
            case 148: case 149: at[1] = 700; app.im.touchDragged(at[0], at[1], 0); break;
            case 150: app.im.touchUp(at[0], at[1], 0, Input.Buttons.LEFT); break;
            case 151: expect("分界线: the timeline's top", EditorScreen.TL_TOP, 700f);
                expect("分界线: the preview's height", EditorScreen.PH, 619f);
                break;
            case 153: kfGhosts(e); break;
            case 157: grpPick(e); break;                                // 组 (chart 2.1): Shift-pick two lanes, Ctrl+G
            case 158: grpMade(e); break;
            case 160: click(grpPt[0], grpPt[1]); break;                 // a click on one: the whole group
            case 162: grpClicked(e); break;
            case 164: e.lastPress = 0L; click(grpPt[0], grpPt[1]); break;
            case 166: e.lastPress = System.currentTimeMillis();         // a double click (the test's frames are slow)
                click(grpPt[0], grpPt[1]);
                break;
            case 168: grpEntered(e); app.im.keyDown(Input.Keys.ESCAPE); break;
            case 170: app.im.keyUp(Input.Keys.ESCAPE); grpLeft(e); break;
            case 172: e.lastPress = 0L; grpPress(e); break;
            case 173: case 174: at[0] += 60; app.im.touchDragged(at[0], at[1], 0); break;
            case 175: app.im.touchUp(at[0], at[1], 0, Input.Buttons.LEFT); break;
            case 176: grpMoved(e); break;
            case 178: grpUngroup(e); break;
            case 180: scnLogic(e); break;                               // 场景页 (step 2)
            case 181: scnBoxPress(e); break;
            case 183: scnBox(e); break;
            case 186: scnArray(e); break;
            case 187: scnPress(e); break;
            case 188: case 189: at[0] += 60; app.im.touchDragged(at[0], at[1], 0); break;
            case 190: app.im.touchUp(at[0], at[1], 0, Input.Buttons.LEFT); break;
            case 191: scnMoved(e); break;
            case 194: scnMirrorModel(e); break;
            case 196: finish(); break;
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
            case 19: shot("v3b_lane_mine"); break;
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
            case 115: shot("v19_old_moved"); break;
            case 127: shot("v20_ease_strip"); break;
            case 135: shot("v21_keyframes"); break;
            case 152: shot("v22_split"); break;
            case 155: shot("v23_ghosts"); break;
            case 163: shot("v24_group"); break;
            case 169: shot("v25_group_entered"); break;
            case 185: shot("v26_array"); break;
            case 192: shot("v27_scene"); break;
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

    /** 备份超过 100 个自动清理 (Kelvin 2026-11-12 06:52): the oldest go, each chart keeps its newest, autosave stays. */
    private void trimmed() {
        FileHandle dir = Gdx.files.absolute(System.getProperty("java.io.tmpdir")).child("kk-trim-" + System.nanoTime());
        String[] names = {"A/Deep-20260101-0100.json", "A/Deep-20260102-0100.json", "A/Deep-20260103-010000.json",
            "A/Abyss-20250101-0000-before-x.json", "B/Shallow-20260101-0200.json", "autosave/A-Deep.json"};
        for (String n : names) dir.child(n).writeString("{\"iterations\": 7}", false, "UTF-8");
        expect("备份: 5 of at most 100, none deleted", Library.trimBackups(dir, 100), 0);
        expect("备份: 5 of at most 3, the 2 oldest deleted", Library.trimBackups(dir, 3), 2);
        int left = 0;
        for (int i = 0; i < names.length; i++) if (dir.child(names[i]).exists()) left += 1 << i;
        expect("备份: the old Deeps went; the newest Deep, the only Abyss and Shallow and autosave stay", left,
            4 + 8 + 16 + 32);
        expect("备份: 迭代次数 untouched", Doc.iterationsIn(dir.child(names[2])), 7);
        dir.deleteDirectory();
    }

    /** Writes the test chart into the first song's first difficulty and opens it. */
    private void writeAndOpen() {
        // 制谱器版本号 = 谱面版本 (Kelvin 2026-11-12 06:03「制谱器版本号，format都放在2.1」「这两个要始终同步的」)
        String jar = CharterApp.class.getPackage().getImplementationVersion();
        if (jar == null) log("制谱器版本号: not run from the jar, not checked");
        else expect("制谱器版本号 " + jar + " = 谱面版本 " + ChartFormatV7.VERSION, jar.equals(ChartFormatV7.VERSION) ? 1 : 0, 1);
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

    /** 法向量 n (Kelvin 2026-11-10 17:59, 18:00): copied, written and read by the game, turning the lane as the game does. */
    private void normal() {
        Lane7 l = new Lane7(1, 0f);
        int got = l.facing() ? 1 : 0;
        Doc.setAt(l.n.x, 0f, 1f);                                       // 快捷方向「朝右」
        Doc.setAt(l.n.z, 0f, 0f);
        got += l.facing() ? 10 : 0;
        expect("法向量: not written by default, then written", got, 10);
        expect("法向量: copyLane keeps it", Doc.copyLane(l).n.x.constant, 1f);
        Chart7 c = new Chart7();
        c.lanes.add(l);
        try {
            java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
            ChartFormatV7.write(c, b);
            Lane7 r = ChartFormatV7.read(new java.io.ByteArrayInputStream(b.toByteArray())).lanes.get(0);
            expect("法向量: the game writes and reads it", r.n.x.constant + 10f * r.n.z.constant, 1f);
        } catch (java.io.IOException ex) {
            fail("法向量: write / read " + ex);
        }
        float[] lf = new float[Scene.LF];
        Scene.laneFrame(l, 0f, lf);
        expect("法向量: depth axis is −n", lf[Scene.D], -1f);
        expect("法向量: across axis is up × depth", lf[Scene.A + 2], 1f);
    }

    /** 组 (chart 2.1, Kelvin 2026-11-11 17:32): kept by reading, undo copies and saving; lanes drawn where they go. */
    private void groups() {
        String json = "{\"format\": \"" + Library.version() + "\", \"groups\": [{\"id\": 1, \"name\": \"转台\", "
            + "\"P\": {\"x\": -520}, \"R\": "
            + "{\"y\": [{\"ti\": 4, \"tf\": 10, \"xi\": 0, \"xf\": 60}]}}, {\"id\": 3, \"p\": 1, \"K\": 0.5}], "
            + "\"lanes\": [{\"id\": 1, \"g\": 1, \"v\": {\"x\": 260}}, {\"id\": 2, \"g\": 3}, {\"id\": 3}]}";
        try {
            Chart7 c = ChartFormatV7.read(new java.io.ByteArrayInputStream(json.getBytes("UTF-8")));
            Doc d = Doc.of(c, null, "Deep").copy();                     // through an undo copy too
            Chart7 r = ChartFormatV7.read(new java.io.ByteArrayInputStream(d.toJson().getBytes("UTF-8")));
            expect("组: both groups kept by a save", r.groups.size(), 2);
            expect("组: name, parent and size kept", ("转台".equals(r.groups.get(0).name) ? 1 : 0)
                + 10 * r.groups.get(1).p + 100 * r.groups.get(1).K.constant, 61f);
            expect("组: the lanes' groups kept", r.lanes.get(0).g + 10 * r.lanes.get(1).g + 100 * r.lanes.get(2).g, 31f);
            expect("组: the turn's segment kept", r.groups.get(0).R.y.segs.size(), 1f);
            Scene.groups(d.groups);
            float[] lf = new float[Scene.LF];
            Scene.laneFrame(d.lanes.get(0), 10f, lf);                   // (260, 0, 0) turned 60° about y, at −520
            expect("组: a turned group's lane, x", lf[Scene.O], -390f);
            expect("组: a turned group's lane, z", Math.round(lf[Scene.O + 2] * 10f) / 10f, -225.2f);
            Scene.laneFrame(d.lanes.get(1), 0f, lf);                    // in a half-size group in that one
            expect("组: a sub-group's lane at its centre, half as wide", lf[Scene.O] + lf[Scene.W], -400f);
            double[] q = {10, 0, 0};
            EditorScreen.toGroup(Scene.pose(3).at(0f), q);
            expect("组: a drag moves twice as far in a half-size group's axes", (float) q[0], 20f);
        } catch (java.io.IOException ex) {
            fail("组: read / write " + ex);
        }
    }

    /** 水雷 (Kelvin 2026-11-10 23:01): 「+ 从播放头变红 1 拍」 makes a lane a mine for a beat; copied with the lane. */
    private void mine() {
        Lane7 l = new Lane7(1, 0f);
        Doc.span(l.m, 4f, 5f, 1f);
        expect("水雷: off before, on inside, off after", l.m.at(3.99f) + 10f * l.m.at(4.5f) + 100f * l.m.at(5f), 10f);
        Doc.span(l.m, 4.5f, 5.5f, 1f);                                  // a second one over its end: one longer span
        expect("水雷: overlapping spans join", l.m.at(4.2f) + 10f * l.m.at(5.2f) + 100f * l.m.at(5.5f), 11f);
        expect("水雷: copyLane keeps it", Doc.copyLane(l).m.at(4.5f), 1f);
        l.l.constant = 0f;                                              // 轨道本身 (Kelvin 2026-11-11 02:12)
        expect("轨道本身: copyLane keeps it", Doc.copyLane(l).l.at(4.5f), 0f);
        Chart7 c = new Chart7();
        c.lanes.add(l);
        try {
            java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
            ChartFormatV7.write(c, b);
            Lane7 r = ChartFormatV7.read(new java.io.ByteArrayInputStream(b.toByteArray())).lanes.get(0);
            expect("水雷: the game writes and reads it", r.m.at(4.5f) + 10f * r.m.at(5.6f), 1f);
            expect("轨道本身: the game writes and reads it", r.l.at(4.5f) + 10f * r.o.at(4.5f), 10f);
        } catch (java.io.IOException ex) {
            fail("水雷: write / read " + ex);
        }
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

    /** Ctrl+Alt + →, Shift+↑ and PageUp moved the edit view right 400, forward 2000 and up 400, along the view. */
    private void moved(EditorScreen e) {
        float[] m = e.viewMove;
        expect("move: keys", Math.abs(m[0] - 400f) + Math.abs(m[1] - 400f) + Math.abs(m[2] - 2000f), 0f);
        Scene c = e.sc;
        double off = 0;
        for (int i = 0; i < 3; i++) off += Math.abs(c.moved[i] - (400 * c.r[i] + 400 * c.u[i] + 2000 * c.f[i]));
        expect("move: the eye along the view", (float) off, 0f);
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
        e.s.mark();                                                     // only the jump back to 0 left: 水雷 from the start
        Fn m = e.s.doc.lanes.get(0).m;
        Doc.span(m, 2f, 3f, 1f);
        m.segs.remove(0);
        e.s.changed(false);
        e.checks.update(e.s);
        expect("checks: a lane that is 水雷 from the start", e.checks.problems, 1);
        e.undo();
        e.checks.update(e.s);
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
                    expect("old chart: saved as the current version on first open", Session.needsRefresh(Session.header(f)) ? 1 : 0, 0);
                    expect("old chart: nothing left unsaved", ses.dirty ? 1 : 0, 0);
                    // older 2.x files too (Kelvin 2026-11-11 02:36「后续每次更新一个版本的时候，你都要确保其他人第一次打开旧谱自动存成最新版本」):
                    // a 2.0 one, a 2.1 one whose format is still a number (04:14「文件里的format也改成2.0」) and a
                    // "2.2" one (2026-11-12 06:06「2.2文件记得读取的时候全部把版本调回2.1」)
                    for (String was : new String[]{"\"2.0\"", "9", "\"2.2\""}) {
                        String now = f.readString("UTF-8");
                        String older = now.replaceFirst("\"format\"\\s*:\\s*(\"[^\"]*\"|\\d+)", "\"format\": " + was);
                        f.writeString(older, false, "UTF-8");
                        ses = Session.open(app.lib, so, df);
                        log("format " + was + ": " + ses.loadNote);
                        expect("format " + was + ": saved as " + Library.version() + " on first open, nothing left unsaved",
                            (older.equals(now) ? 100 : 0) + (ses.dirty ? 10 : 0) + (Session.needsRefresh(Session.header(f)) ? 1 : 0), 0);
                    }
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

    // ---- keyframes and the layered timeline (Kelvin 2026-11-11 16:39「基本可以」)

    private Keyframes.Group[] kfGroups(EditorScreen e) {
        Lane7 l = e.s.doc.lane(kfLane);
        return l != null ? Keyframes.position(Keyframes.lane(l), 3) : new Keyframes.Group[0];
    }

    /** @return the lane's 原点 v group */
    private Keyframes.Group kfOrigin(EditorScreen e) {
        return Keyframes.lane(e.s.doc.lane(kfLane))[0];
    }

    /** 新建轨道 at the playhead (it comes in there), then the playhead 4 beats on. */
    private void kfLaneNew(EditorScreen e) {
        e.insp.newLane(e.s, e.s.doc);
        kfLane = e.s.laneSel;
        kfT = kfIn + 4f;
        e.seekBeat(kfT);
        Lane7 l = e.s.doc.lane(kfLane);
        expect("new lane comes in at the playhead", l != null ? Doc.appearBeat(l) : Float.NaN, kfIn);
        expect("new lane: keyframes", Keyframes.points(kfGroups(e)).length, 0);
    }

    /** After K: a keyframe at the playhead on 原点 v, 下落向量 u and 宽长 a, nothing moved; its layer open. */
    private void kfAdded(EditorScreen e) {
        Keyframes.Group[] gs = kfGroups(e);
        expect("K: a keyframe at the playhead", Keyframes.on(gs, kfT) ? 1 : 0, 1);
        expect("K: one keyframe (none at the 出现)", Keyframes.points(gs).length, 1);
        for (Keyframes.Group gr : gs) expect("K: " + gr.name + " keyed", Keyframes.has(gr, kfT) ? 1 : 0, 1);
        expect("K: its layer is open", e.layers.open.contains("l" + kfLane) ? 1 : 0, 1);
        Lane7 l = e.s.doc.lane(kfLane);
        Fn x = Doc.fns(l)[3];
        float was = x.at(kfT + 2f);
        e.s.mark();
        Doc.setAt(x, kfT + 2f, was + 300f);                             // as typing in the inspector 2 beats on
        e.s.changed(false);
        expect("v.x 2 beats on", x.at(kfT + 2f), was + 300f);
    }

    private void kfClick(EditorScreen e, String row, float beat) {
        float[] xy = e.layers.rowAt("l" + kfLane, row, beat);
        if (xy == null) {
            fail("the row " + row + " is not on screen");
            return;
        }
        click(Math.round(xy[0]), Math.round(xy[1]));
    }

    /** 先慢后快 e 2 on the open ease strip. */
    private void kfTile(EditorScreen e) {
        float[] xy = e.layers.tile(3);
        if (xy == null) {
            fail("clicking a bar did not open the ease strip");
            return;
        }
        click(Math.round(xy[0]), Math.round(xy[1]));
    }

    private void kfEased(EditorScreen e) {
        expect("ease strip: 先慢后快 e 2 on the bar", Keyframes.easeOf(kfOrigin(e), kfT, kfT + 2f), 2f);
    }

    /** Presses row {@code row} (null: the layer's own row) at {@code beat}. */
    private void kfPress(EditorScreen e, String row, float beat) {
        float[] xy = e.layers.rowAt("l" + kfLane, row, beat);
        if (xy == null) {
            fail("the row " + row + " is not on screen");
            at = new int[]{0, 0};
            return;
        }
        at = new int[]{Math.round(xy[0]), Math.round(xy[1])};
        app.im.touchDown(at[0], at[1], 0, Input.Buttons.LEFT);
    }

    private void kfDrag(EditorScreen e, float beat) {
        at = new int[]{Math.round(e.bx(beat)), at[1]};
        app.im.touchDragged(at[0], at[1], 0);
    }

    /** The ◆ dragged from 2 to 3 beats on: the bar keeps its ease. */
    private void kfRetimed(EditorScreen e) {
        Keyframes.Group v = kfOrigin(e);
        expect("drag ◆: at 3 beats on", Keyframes.has(v, kfT + 3f) ? 1 : 0, 1);
        expect("drag ◆: none at 2 beats on", Keyframes.has(v, kfT + 2f) ? 1 : 0, 0);
        expect("drag ◆: the bar keeps e 2", Keyframes.easeOf(v, kfT, kfT + 3f), 2f);
        e.seekBeat(kfT + 3f);
    }

    /** ◆− 关键帧 there: that one goes, the first stays. */
    private void kfRemoved(EditorScreen e) {
        Keyframes.Group[] gs = kfGroups(e);
        expect("◆− 关键帧: gone", Keyframes.has(kfOrigin(e), kfT + 3f) ? 1 : 0, 0);
        expect("◆− 关键帧: the first stays", Keyframes.on(gs, kfT) ? 1 : 0, 1);
        expect("◆− 关键帧: keyframes", Keyframes.points(gs).length, 1);
    }

    /** The life bar dragged one beat on (操作 G): its 出现 and its keyframe move with it; then a key in its 地面键 row. */
    private void kfMoved(EditorScreen e) {
        Lane7 l = e.s.doc.lane(kfLane);
        expect("life bar: 出现 one beat on", Doc.appearBeat(l), kfIn + 1f);
        expect("life bar: the keyframe with it", Keyframes.has(kfOrigin(e), kfT + 1f) ? 1 : 0, 1);
        e.setTool(EditorScreen.T_TAP);
        keysBefore = e.s.doc.keys.size();
        kfKey = kfT + 3f;
        kfClick(e, "地面键", kfKey);
    }

    private void kfPlaced(EditorScreen e) {
        expect("a click in 地面键: keys", e.s.doc.keys.size(), keysBefore + 1);
        Doc.DKey k = e.s.single();
        if (k == null) {
            fail("a click in 地面键: nothing selected");
        } else {
            expect("a click in 地面键: its lane", k.k.lane, kfLane);
            expect("a click in 地面键: on the 底面", k.k.sky ? 1 : 0, 0);
            expect("a click in 地面键: judged there", k.judge(), kfKey);
            expect("a click in 地面键: 水滴", k.k.kind.ordinal(), KeyKind.TAP.ordinal());
        }
        e.setTool(EditorScreen.T_SELECT);
    }

    /** 关键帧的影子 (SPEC §3): three keyframes of the lane next to lane 1, the playhead between the first two. */
    private void kfGhosts(EditorScreen e) {
        Doc d = e.s.doc;
        Lane7 l = d.lane(kfLane), ref = d.lane(1) != null ? d.lane(1) : d.lanes.get(0);
        float b = kfT + 1f;
        Fn[] f = Doc.fns(l), r = Doc.fns(ref);
        float x = r[3].at(b), y = r[4].at(b), z = r[5].at(b);
        float[][] at3 = {{b, x, y}, {b + 2f, x + 400f, y}, {b + 4f, x + 400f, y + 400f}};
        e.s.mark();
        for (float[] p : at3) {
            Doc.setAt(f[3], p[0], p[1]);
            Doc.setAt(f[4], p[0], p[2]);
            Doc.setAt(f[5], p[0], z);
        }
        e.s.changed(false);
        e.s.sel.clear();
        e.s.laneSel = kfLane;
        e.insp.tab = Inspector.LANE;
        e.seekBeat(b + 1f);
        EditorScreen.layout(760f);
        expect("ghosts: keyframes", Keyframes.points(kfGroups(e)).length, 3);
        float[] lf = new float[Scene.LF], px = new float[2];
        double[] w = new double[3];
        StringBuilder sb = new StringBuilder("ghosts on screen:");
        for (float[] p : at3) {
            Keyframes.laneSpot(l, p[0], lf, w);
            boolean on = e.sc.project(w[0], w[1], w[2], px, 0);
            sb.append(String.format(Locale.ROOT, " %.0f → (%.0f, %.0f)%s", p[0], px[0], px[1], on ? "" : " behind"));
        }
        log(sb.toString());
    }

    // ---- 组 (chart 2.1, Kelvin 2026-11-11 17:32)
    private int grpId;
    private int[] grpLanes, grpPt;
    private float[][] grpWorld;
    private float grpP0, grpV0;

    /** Two lanes on screen past the last key (nothing in the way), picked: the second with Shift; then Ctrl+G. */
    private void grpPick(EditorScreen e) {
        Doc d = e.s.doc;
        e.seekBeat((float) Math.ceil(d.lastBeat()) + 2f);
        e.homeView();
        e.setTool(EditorScreen.T_SELECT);
        e.insp.tab = Inspector.LANE;
        ArrayList<Integer> on = new ArrayList<>();
        float[] lf = new float[Scene.LF];
        for (int i = 0; i < d.lanes.size() && on.size() < 2; i++) {
            Scene.laneFrame(d.lanes.get(i), e.beat, lf);
            float[] p = Math.abs(lf[Scene.W]) > 0.5f ? onLane(e, i, 0.5f) : null;
            if (p != null && p[0] > EditorScreen.PX + 300f && p[0] < EditorScreen.PX + EditorScreen.PW - 300f
                && p[1] > EditorScreen.PY + 60f && p[1] < EditorScreen.PY + EditorScreen.PH - 20f) on.add(d.lanes.get(i).id);
        }
        if (on.size() < 2) {
            fail("组: two lanes on screen to group");
            grpLanes = new int[0];
            grpPt = new int[]{0, 0};
            return;
        }
        grpLanes = new int[]{on.get(0), on.get(1)};
        e.pickLane(grpLanes[0], false);
        e.pickLane(grpLanes[1], true);
        grpWorld = worlds(e);
        app.im.testCtrl = true;
        app.im.keyDown(Input.Keys.G);
    }

    private void grpMade(EditorScreen e) {
        app.im.keyUp(Input.Keys.G);
        app.im.testCtrl = false;
        Doc d = e.s.doc;
        Group7 gr = d.group(e.s.groupSel);
        expect("成组 Ctrl+G: a new group, picked", gr != null && grpLanes.length == 2 ? 1f : 0f, 1f);
        if (gr == null || grpLanes.length < 2) {
            grpPt = new int[]{0, 0};
            return;
        }
        grpId = gr.id;
        expect("成组: both lanes in it", (d.lane(grpLanes[0]).g == grpId ? 1 : 0) + (d.lane(grpLanes[1]).g == grpId ? 1 : 0),
            2f);
        expect("成组: nothing moved", worldDiff(e, grpWorld) < 0.01f ? 0f : worldDiff(e, grpWorld), 0f);
        expect("成组: on the 场景 page", e.insp.tab, Inspector.SCENE);
        float[] p = onLane(e, d.laneIndex(grpLanes[0]), 0.5f);
        grpPt = new int[]{Math.round(p[0]), Math.round(p[1])};
        e.s.groupSel = 0;                                               // nothing picked before the click
        e.s.laneSel = Session.NO_LANE;
        e.insp.tab = Inspector.LANE;
    }

    private void grpClicked(EditorScreen e) {
        expect("点一下: the whole group picked", e.s.groupSel, grpId);
        expect("点一下: no single lane", e.s.laneSel == Session.NO_LANE ? 1f : 0f, 1f);
    }

    private void grpEntered(EditorScreen e) {
        if (grpLanes.length < 2) return;
        expect("双击: into the group", e.s.groupIn, grpId);
        expect("双击: then that lane alone", e.s.laneSel, grpLanes[0]);
    }

    private void grpLeft(EditorScreen e) {
        expect("Esc: out of the group, which stays picked", e.s.groupIn * 10 + (e.s.groupSel == grpId ? 1 : 0), 1f);
    }

    /** A press on a lane of the picked group, dragged right: the group moves, not its lanes in it. */
    private void grpPress(EditorScreen e) {
        if (grpLanes.length < 2) return;
        Doc d = e.s.doc;
        grpP0 = d.group(grpId).P.x.at(e.beat);
        grpV0 = d.lane(grpLanes[0]).v.x.at(e.beat);
        at = new int[]{grpPt[0], grpPt[1]};
        app.im.touchDown(at[0], at[1], 0, Input.Buttons.LEFT);
    }

    private void grpMoved(EditorScreen e) {
        if (grpLanes.length < 2) return;
        Doc d = e.s.doc;
        expect("拖整组: the group moved right", d.group(grpId).P.x.at(e.beat) > grpP0 + 10f ? 1f : 0f, 1f);
        expect("拖整组: its lane's own origin stays", d.lane(grpLanes[0]).v.x.at(e.beat), grpV0);
    }

    /** 解组 of the group turned 30° about y and at half size: everything stays where it is, keys too. */
    private void grpUngroup(EditorScreen e) {
        if (grpLanes.length < 2) return;
        Doc d = e.s.doc;
        Group7 gr = d.group(grpId);
        e.s.mark();
        for (int id : grpLanes) {                                       // 解组 of a turned group needs them still
            Lane7 l = d.lane(id);
            for (Fn f : new Fn[]{l.v.x, l.v.y, l.v.z, l.u.x, l.u.y, l.u.z, l.n.x, l.n.y, l.n.z}) {
                f.constant = f.at(e.beat);
                f.segs.clear();
            }
        }
        gr.R.y.constant = 30f;
        gr.K.constant = 0.5f;
        e.s.changed(false);
        Doc.DKey key = null;
        for (Doc.DKey k : d.keys) if (k.lane() == grpLanes[0] && Float.isFinite(k.judge()) && key == null) key = k;
        float[] keyAt = key != null ? keyWorld(key) : null;
        float[][] was = worlds(e);
        String why = d.copy().ungroup(grpId);
        if (why != null) log("解组 refused: " + why);
        e.ungroup(grpId);
        expect("解组: the group is gone", d.group(grpId) == null ? 1f : 0f, 1f);
        expect("解组: its lanes in no group", d.lane(grpLanes[0]).g + d.lane(grpLanes[1]).g, 0f);
        float diff = worldDiff(e, was);
        expect("解组: the lanes stay where they were (turned, half size)", diff < 0.05f ? 0f : diff, 0f);
        if (keyAt != null) {
            float[] now = keyWorld(key);
            float m = 0f;
            for (int i = 0; i < 3; i++) m = Math.max(m, Math.abs(now[i] - keyAt[i]));
            expect("解组: a key on them stays where it was", m < 0.05f ? 0f : m, 0f);
        }
    }

    // ---- 场景页 (step 2): 复制, 一圈, 镜像, 模型 on a copy; then 方块, 阵列, the handle, 镜像 and 模型 in the editor

    private int scnLane, scnGroup;
    private float scnX;

    private void scnLogic(EditorScreen e) {
        Doc d = e.s.doc.copy();
        int keys0 = d.keys.size();
        Lane7 a = new Lane7(d.nextLaneId(), 100f);
        d.lanes.add(a);
        d.names.put(a.id, "柱子 1");
        ArrayList<Lane7> one = new ArrayList<>();
        one.add(a);
        Group7 g = d.makeGroup(one, new ArrayList<>(), 0, 0f);
        g.R.y.constant = 30f;
        Doc.Items gi = new Doc.Items();
        gi.groups.add(g);
        int g0 = d.groups.size(), l0 = d.lanes.size();
        Doc.Items cp = d.duplicate(gi);
        expect("复制: one more group with one more lane", (d.groups.size() - g0) * 10 + d.lanes.size() - l0, 11f);
        expect("复制: no keys", d.keys.size(), keys0);
        Group7 c = cp.groups.get(0);
        String name = null;
        for (Lane7 l : d.lanes) if (l.g == c.id) name = d.names.get(l.id);
        expect("复制: the lane's name numbered on (柱子 2)", "柱子 2".equals(name) ? 1f : 0f, 1f);
        Lane7 b = new Lane7(d.nextLaneId(), 100f);
        d.lanes.add(b);
        Doc.Items bi = new Doc.Items();
        bi.lanes.add(b);
        d.turn(bi, Doc.turnOf(0f, 90f, 0f), new float[3]);
        expect("一圈: 90° about y takes x 100 to z −100", Math.abs(b.v.x.constant) + Math.abs(b.v.z.constant + 100f), 0f);
        float[] ax = new float[9];
        b.axes(0f, ax);
        expect("一圈: its across axis turned with it", Math.abs(ax[2]), 1f);
        Doc.Items ci = new Doc.Items();
        ci.groups.add(c);
        d.turn(ci, Doc.turnOf(0f, 90f, 0f), new float[3]);
        expect("一圈: a group's turn adds up (30 + 90)", c.R.y.constant, 120f);
        d.mirror(ci);
        expect("镜像: a group turned about y the other way", c.R.y.constant, -120f);
        String json = d.modelJson(g.id, 0f);
        int g1 = d.groups.size(), l1 = d.lanes.size();
        try {
            Doc.Items in = d.addModel(json, 0);
            Group7 m = in.groups.get(0);
            expect("模型: a group back with its lane", (d.groups.size() - g1) * 10 + d.lanes.size() - l1, 11f);
            expect("模型: new ids, centre at 0, turn kept", (m.id != g.id ? 1 : 0) + Math.abs(m.P.x.constant)
                + Math.abs(m.P.z.constant) + m.R.y.constant, 31f);
        } catch (java.io.IOException ex) {
            fail("模型: read back: " + ex.getMessage());
        }
    }

    /** 方块: B, then a click in the preview (tilted 30°) puts a box on the grid. */
    private void scnBoxPress(EditorScreen e) {
        e.homeView();
        e.orbitPitch = 30f;
        e.s.groupIn = 0;
        e.s.groupSel = 0;
        e.s.laneSel = Session.NO_LANE;
        e.insp.tab = Inspector.SCENE;
        e.setTool(EditorScreen.T_BOX);
        e.lastPress = 0L;
        scnLane = e.s.doc.nextLaneId();
        click(Math.round(EditorScreen.PX + EditorScreen.PW * 0.45f),
            Math.round(EditorScreen.PY + EditorScreen.PH * 0.6f));
    }

    private void scnBox(EditorScreen e) {
        Lane7 l = e.s.doc.lane(scnLane);
        expect("方块: a new lane, picked, on the 场景 page", (l != null ? 1 : 0) + (e.s.laneSel == scnLane ? 1 : 0)
            + (e.insp.tab == Inspector.SCENE ? 1 : 0), 3f);
        if (l == null) return;
        expect("方块: standing on the grid, 240 high, no column ends", l.F.constant + l.S.constant + l.s.constant
            + l.f.constant, 240f);
        expect("方块: on the 100 grid", Math.abs(l.v.x.constant % 100f) + Math.abs(l.v.z.constant % 100f)
            + Math.abs(l.v.y.constant), 0f);
        l.v.x.constant = 600f;                                          // then a ring of 4 about the origin
        l.v.z.constant = 0f;
        e.setTool(EditorScreen.T_SELECT);
        e.scene.circle = true;
        e.scene.count = 4;
        e.scene.total = 360f;
        e.scene.axis = 1;
        e.scene.centre = 0;
        e.scene.into = 0;
        e.scene.openArray();
        expect("阵列: its card open", e.scene.arrayOn ? 1f : 0f, 1f);
    }

    private void scnArray(EditorScreen e) {
        Doc d = e.s.doc;
        e.scene.applyArray();
        Group7 g = d.group(e.s.groupSel);
        expect("阵列 一圈 4: a new group of 4, picked", g != null ? d.laneCount(g.id) : 0, 4f);
        if (g == null) return;
        scnGroup = g.id;
        float sx = 0f, sz = 0f, r = 0f;
        for (Lane7 l : d.lanes) {
            if (l.g != g.id) continue;
            sx += l.v.x.constant;
            sz += l.v.z.constant;
            r += Math.abs(l.v.x.constant) + Math.abs(l.v.z.constant);
        }
        expect("阵列: round the origin, 600 out", Math.abs(sx) + Math.abs(sz) + r, 2400f);
        expect("阵列: the group's centre at the ring's centre", Math.abs(g.P.x.constant) + Math.abs(g.P.z.constant), 0f);
    }

    /** The handle: a press on the x arrow, dragged right. */
    private void scnPress(EditorScreen e) {
        float[] p = e.scene.arrowOnScreen(1);
        if (p == null) {
            fail("手柄: the x arrow on screen");
            at = new int[]{0, 0};
            return;
        }
        scnX = e.s.doc.group(scnGroup).P.x.constant;
        at = new int[]{Math.round(p[0]), Math.round(p[1])};
        app.im.touchDown(at[0], at[1], 0, Input.Buttons.LEFT);
    }

    private void scnMoved(EditorScreen e) {
        Group7 g = e.s.doc.group(scnGroup);
        float x = g != null ? g.P.x.at(e.beat) : scnX;
        expect("手柄: the x arrow moves the group right, on the grid", x > scnX && x % 100f == 0f ? 1f : 0f, 1f);
        expect("手柄: only x", g != null ? Math.abs(g.P.y.at(e.beat)) + Math.abs(g.P.z.at(e.beat)) : -1f, 0f);
    }

    private void scnMirrorModel(EditorScreen e) {
        Doc d = e.s.doc;
        Group7 g = d.group(scnGroup);
        if (g == null) return;
        e.scene.mirror();
        Group7 m = d.group(e.s.groupSel);
        expect("镜像: a copy on the other side", m != null && m != g ? m.P.x.constant + g.P.x.constant : -1f, 0f);
        boolean saved = e.s.lib.saveModel("自测模型", d.modelJson(g.id, e.beat));
        int n = d.groups.size();
        e.scene.importModel("自测模型");
        Group7 in = d.group(e.s.groupSel);
        expect("模型: saved and put back as a new group of 4", (saved ? 1 : 0) + (d.groups.size() - n)
            + (in != null && in != g ? d.laneCount(in.id) : 0), 6f);
        e.s.lib.home.child("models").child("自测模型.json").delete();
    }

    /** @return where key k is half a beat before its judgement (chart axes) */
    private float[] keyWorld(Doc.DKey k) {
        float t = k.judge() - 0.5f;
        Scene.groups(ed().s.doc.groups);
        float[] lf = new float[Scene.LF];
        Scene.laneFrame(ed().s.doc.lane(k.lane()), t, lf);
        double[] p = new double[3];
        Scene.at(lf, 0, Scene.keyUp(k.k, t, lf), 0, p, 0);
        return new float[]{(float) p[0], (float) p[1], (float) p[2]};
    }

    /** @return the picked lanes' frames now (chart axes) */
    private float[][] worlds(EditorScreen e) {
        Scene.groups(e.s.doc.groups);
        float[][] w = new float[grpLanes.length][Scene.LF];
        for (int i = 0; i < w.length; i++) Scene.laneFrame(e.s.doc.lane(grpLanes[i]), e.beat, w[i]);
        return w;
    }

    /** @return how far the picked lanes' frames are from {@code was}: origins, axes and sizes (not the group's size) */
    private float worldDiff(EditorScreen e, float[][] was) {
        float[][] now = worlds(e);
        float m = 0f;
        for (int i = 0; i < now.length; i++) {
            for (int k = 0; k < Scene.KS; k++) m = Math.max(m, Math.abs(now[i][k] - was[i][k]));
        }
        return m;
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
