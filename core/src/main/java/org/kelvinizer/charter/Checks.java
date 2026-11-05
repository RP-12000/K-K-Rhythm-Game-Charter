package org.kelvinizer.charter;

import org.kelvinizer.elements.Chart;
import org.kelvinizer.elements.Events;
import org.kelvinizer.elements.Lane;
import org.kelvinizer.elements.LaneLayout;
import org.kelvinizer.elements.Note;
import org.kelvinizer.elements.NoteOrbit;
import org.kelvinizer.elements.NoteTrail;
import org.kelvinizer.elements.NoteType;
import org.kelvinizer.elements.Veil;
import org.kelvinizer.gameplay.CharterPreview;
import org.kelvinizer.gameplay.Field;
import org.kelvinizer.gameplay.GameEngine;
import org.kelvinizer.gameplay.NoteShapes;
import org.kelvinizer.gameplay.Veils;

import java.util.ArrayList;
import java.util.List;

/**
 * 谱面检查：「谱面」页和顶栏 ⚠ 里列出的问题，每一项都能跳到相关的音符。
 * Chart checks shown in the 谱面 tab and counted by the ⚠ badge: notes on the default approach,
 * taps and hold heads inside a LANE MINE block, and judged notes of its line inside a TIDE STREAM MINE's (a WARN,
 * like the game's laneMineConflicts; mines may stack anywhere since Kelvin 2026-10-22), notes the game did not build,
 * taps / holds stacked on one lane and beat (an INFO: allowed since game 7.166, one finger each),
 * overlapping events and trails over the copy limit, notes whose whole judgement zone a veil covers, polygon veils and
 * curtains the game drops, and 物件坐标冲突 on orbiting keys. Each item keeps the notes (or the veil) it is about, so
 * 跳到 can select them. 假键 (fake keys) are counted apart and left out of the veil and block checks.
 */
final class Checks {
    /** INFO lines are listed but not counted as problems. */
    static final int OK = 0, WARN = 1, ERROR = 2, INFO = 3;

    static final class Item {
        final int level;
        String text;
        final ArrayList<Integer> uids = new ArrayList<>();
        float beat = Float.NaN;
        /** The veil the item is about (0 = none): 跳到 selects it. */
        int veil;

        Item(int level, String text) {
            this.level = level;
            this.text = text;
        }

        void add(Doc.DNote n) {
            if (!uids.contains(n.uid)) uids.add(n.uid);
            if (Float.isNaN(beat) || n.judgement < beat) beat = n.judgement;
        }

        void at(float b) {
            if (Float.isNaN(beat) || b < beat) beat = b;
        }
    }

    final ArrayList<Item> items = new ArrayList<>();
    /** Number of problem items (warnings and errors). */
    int problems;
    final int[] counts = new int[NoteType.count()];
    /** 假键 in the chart (not in {@link #counts}). */
    int fakes;
    private int version = Integer.MIN_VALUE;
    private Chart chart;
    private CharterPreview lastPv;

    void update(Session s) {
        update(s, null);
    }

    /** @param pv the preview, for the drawn key sizes of the 物件坐标冲突 check (null skips that check) */
    void update(Session s, CharterPreview pv) {
        if (s.version == version && s.chart == chart && (pv == null || pv == lastPv)) return;
        lastPv = pv;
        version = s.version;
        chart = s.chart;
        items.clear();
        java.util.Arrays.fill(counts, 0);
        fakes = 0;
        Doc doc = s.doc;
        if (s.buildError != null) items.add(new Item(ERROR, "谱面有错误，预览没有更新：" + s.buildError));

        Item def = new Item(WARN, null), dup = new Item(ERROR, null), trail = new Item(WARN, null);
        Item mines = new Item(WARN, null), overlap = new Item(WARN, null), hidden = new Item(INFO, null);
        Item early = new Item(ERROR, null), gone = new Item(ERROR, null), veiled = new Item(WARN, null);
        int conflicts = 0, tideConflicts = 0, overlaps = 0;
        ArrayList<Item> orbits = new ArrayList<>();
        Veil[] veils = chart != null ? chart.veils() : new Veil[0];
        LaneLayout lay = veils.length > 0 ? new LaneLayout(chart) : null;
        for (Doc.DNote n : doc.notes) {
            if (n.fake && n.canFake()) fakes++;                    // 假键 are not notes of the chart (game 7.167)
            else counts[n.type.ordinal()]++;
            Note g = s.note(n);
            if (g == null) {
                if (s.buildError == null) dup.add(n);
                continue;
            }
            if (g.usesDefaultApproach()) def.add(n);
            // an inserted lane is not there before its insertion (§3.6), a deleted one after its deletion
            // (§3.7): narrower than 1 cannot be hit
            int li = n.inLane() && chart != null ? chart.laneIndexOf(n.lane) : -1;
            Lane ln = li >= 0 ? chart.lane(li) : null;
            if (ln != null && (ln.inserted() || ln.deleted()) && ln.widthAt(n.judgement) < 1f) {
                (ln.deleted() && n.judgement >= ln.deletion().startTime() ? gone : early).add(n);
            } else if (!n.move.isEmpty() && !n.coversJudgement()) hidden.add(n);
            for (Doc.DTrail t : n.trails) {
                if (t.length * t.density <= NoteTrail.MAX_TRAIL_COPIES + 1e-3f) continue;
                trail.add(n);
                break;
            }
            int o = overlaps(n.move) + overlaps(n.rot) + overlaps(n.offEv) + overlaps(n.orbXEv) + overlaps(n.orbYEv)
                + overlaps(n.orbRadEv) + overlaps(n.orbAngEv) + overlaps(n.alpha) + overlaps(n.heightEv);
            if (o > 0) {
                overlaps += o;
                overlap.add(n);
            }
            boolean real = !(n.fake && n.canFake());               // a 假键 is never judged: no veil or block checks
            if (real && lay != null && n.type.judged() && zoneVeiled(chart, lay, veils, n)) veiled.add(n);
            if (pv != null && n.polar() && n.orbitConflict == NoteOrbit.CONFLICT_UNSET) {
                Item it = orbitConflict(chart, pv, n, g);
                if (it != null) orbits.add(it);
            }
            if (real && n.type == NoteType.MINE && n.duration > 0f) {
                double from = s.beatToMs(n.judgement) - GameEngine.LANE_MINE_LEAD_MS, to = s.beatToMs(n.end());
                for (Doc.DNote x : doc.notes) {
                    // mines (plain or LANE MINE) may sit anywhere (Kelvin 2026-10-22: 「地雷的限制不用那么多」)
                    if (x == n || !x.inLane() || x.type == NoteType.MINE || x.line != n.line
                        || x.lane != n.lane || x.fake) continue;
                    double t = s.beatToMs(x.judgement);
                    if (t >= from && t < to) {
                        conflicts++;
                        mines.add(x);
                        mines.add(n);
                    }
                }
            }
            if (real && n.type == NoteType.TIDE_MINE && n.duration > 0f) {
                // a TIDE STREAM MINE blocks its line's whole zone from its judgement to its end (CHART_FORMAT §5.1)
                double from = s.beatToMs(n.judgement), to = s.beatToMs(n.end());
                for (Doc.DNote x : doc.notes) {
                    if (x == n || !x.type.judged() || x.line != n.line || x.fake && x.canFake()) continue;
                    double t = s.beatToMs(x.judgement);
                    if (t >= from && t < to) {
                        tideConflicts++;
                        mines.add(x);
                        mines.add(n);
                    }
                }
            }
        }
        for (Doc.DLane l : doc.lanes) {
            int o = overlaps(l.widthEv) + overlaps(l.opacityEv);
            if (o > 0) {
                overlaps += o;
                firstOverlap(l.widthEv, overlap);
                firstOverlap(l.opacityEv, overlap);
            }
        }
        for (List<Doc.Ev> list : lineLists(doc)) {
            int o = overlaps(list);
            if (o > 0) {
                overlaps += o;
                firstOverlap(list, overlap);
            }
        }
        if (!def.uids.isEmpty()) {
            def.text = def.uids.size() + " 个音符没写运动，用默认下落";
            items.add(def);
        }
        if (conflicts + tideConflicts > 0) {
            mines.text = (conflicts > 0 ? conflicts + " 处轨道水雷封锁冲突" : "") + (conflicts > 0 && tideConflicts > 0
                ? "，" : "") + (tideConflicts > 0 ? tideConflicts + " 处潮汐水雷封锁冲突" : "");
            items.add(mines);
        }
        if (!dup.uids.isEmpty()) {
            dup.text = dup.uids.size() + " 个音符游戏没有读进来";
            items.add(dup);
        }
        if (overlaps > 0) {
            overlap.text = overlaps + " 处事件重叠（后开始的那段生效）";
            items.add(overlap);
        }
        items.addAll(orbits);
        if (!early.uids.isEmpty()) {
            early.text = early.uids.size() + " 个音符在轨道插入前（打不到）";
            items.add(early);
        }
        if (!gone.uids.isEmpty()) {
            gone.text = gone.uids.size() + " 个音符在轨道删除后（轨道已经没了，手机上打不到）";
            items.add(gone);
        }
        if (!veiled.uids.isEmpty()) {
            veiled.text = veiled.uids.size() + " 个音符判定时整个判定区都被漩涡盖住（打不到）";
            items.add(veiled);
        }
        fewPoints(items, doc.veils, "漩涡");
        fewPoints(items, doc.curtains, "水帘");
        if (!trail.uids.isEmpty()) {
            trail.text = trail.uids.size() + " 个拖拽残影超过 " + NoteTrail.MAX_TRAIL_COPIES + " 个副本（只画 "
                + NoteTrail.MAX_TRAIL_COPIES + " 个）";
            items.add(trail);
        }
        problems = items.size();
        if (!hidden.uids.isEmpty()) {
            hidden.text = hidden.uids.size() + " 个音符判定时在屏幕外（运动在判定前就结束了）";
            items.add(hidden);
        }
        Item stacked = stacks(doc);
        if (stacked != null) items.add(stacked);
        if (dup.uids.isEmpty() && stacked == null && overlaps == 0 && s.buildError == null) {
            items.add(new Item(OK, "无叠在一起的音符 · 事件不重叠"));
        }
    }

    /**
     * Taps and holds on the same line, lane and beat (Kelvin 2026-11-02, game 7.166: the game keeps them all and each
     * press judges one, so they need a finger each).
     *
     * @return an INFO item with every stacked note, or null when there are none
     */
    private static Item stacks(Doc doc) {
        java.util.HashMap<String, java.util.ArrayList<Doc.DNote>> at = new java.util.HashMap<>();
        for (Doc.DNote n : doc.notes) {
            if (n.type != NoteType.TAP && n.type != NoteType.HOLD || n.fake) continue;
            String key = n.line + "/" + n.lane + "/" + Math.round(n.judgement * 1e4f);
            at.computeIfAbsent(key, k -> new java.util.ArrayList<>()).add(n);
        }
        Item it = new Item(INFO, null);
        int groups = 0;
        for (java.util.ArrayList<Doc.DNote> g : at.values()) {
            if (g.size() < 2) continue;
            groups++;
            for (Doc.DNote n : g) it.add(n);
        }
        if (groups == 0) return null;
        it.text = "同轨同拍叠着的音符 " + groups + " 组（每个要一根手指）";
        return it;
    }

    /** Beats between samples of the 物件坐标冲突 check, and at most this many per note. */
    private static final float CONFLICT_STEP = 1f / 32f;
    private static final int CONFLICT_SAMPLES = 4096;

    /**
     * 物件坐标冲突 (Kelvin 2026-10-29 20:55): where a key with a radius orbit is in its orbit's time
     * ({@link Note#inOrbitSpan}) and its movement shows it, the orbit and the movement put its head at different
     * heights (more than about 1 reference unit, {@link NoteShapes#polarAnchorY} both ways).
     *
     * @return the warning naming the beats and the events that cover them, or null
     */
    private static Item orbitConflict(Chart c, CharterPreview pv, Doc.DNote d, Note n) {
        List<Events> move = n.movementEvents();
        if (move.isEmpty()) return null;
        float from = Float.POSITIVE_INFINITY, to = Float.NEGATIVE_INFINITY;
        for (Events e : move) {
            from = Math.min(from, e.startTime());
            to = Math.max(to, e.endTime());
        }
        from = Math.max(from, n.orbitFrom());
        to = Math.min(Math.min(to, n.orbitTo()), n.isSustained() ? n.judgement() + n.duration() : n.judgement());
        if (!(to > from)) return null;
        float step = Math.max(CONFLICT_STEP, (to - from) / CONFLICT_SAMPLES);
        boolean mine = n.type() == NoteType.MINE, up = n.isUpper();
        float refH = pv.refHeight(n.type()), h = mine ? pv.mineHeight() : pv.noteHeight();
        float first = Float.NaN, last = Float.NaN;
        StringBuilder spans = new StringBuilder();
        int count = 0;
        for (float b = from; b <= to + 1e-4f; b += step) {
            boolean bad = false;
            if (covered(move, b) && n.inOrbitSpan(b)) {
                float pos = n.positionAt(b);
                if (pos < Note.HIDDEN_POSITION - 1f) {
                    float dp = Field.dist(pos - pv.hitShift()), ly = Field.y(c.lineEvents().yAt(n.line(), b));
                    float y = up ? ly - dp : ly + dp, rot = n.angleAt(b) + (up ? 180f : 0f);
                    float yo = NoteShapes.polarAnchorY(n, b, y, dp, refH, h, rot, false);
                    float ym = NoteShapes.polarAnchorY(n, b, y, dp, refH, h, rot, true);
                    bad = Math.abs(yo - ym) / Field.ky() > 1f;
                }
            }
            if (bad) {
                if (Float.isNaN(first)) first = b;
                last = b;
            } else if (!Float.isNaN(first)) {
                count += span(spans, first, last, count);
                first = Float.NaN;
            }
        }
        if (!Float.isNaN(first)) count += span(spans, first, last, count);
        if (count == 0) return null;
        StringBuilder evs = new StringBuilder();
        String[] names = {"公转中心 x", "公转中心 y", "公转半径", "公转角度", "移动"};
        List<List<Doc.Ev>> lists = java.util.Arrays.asList(d.orbXEv, d.orbYEv, d.orbRadEv, d.orbAngEv, d.move);
        for (int k = 0; k < lists.size(); k++) {
            for (Doc.Ev e : lists.get(k)) {
                if (e.end < from || e.start > to) continue;
                evs.append(evs.length() == 0 ? "" : "、").append(names[k]).append(' ').append(Im.fmt(e.start))
                    .append('–').append(Im.fmt(e.end));
            }
        }
        Item it = new Item(WARN, "物件坐标冲突：" + EditorScreen.laneName(d.lane) + " 轨第 " + Im.fmt(d.judgement) + " 拍的键在第 "
            + spans + " 拍，公转和移动算出的高度不同（" + evs + " 拍）。看预览后在这个键的「公转」里选以公转为准或以移动为准");
        it.add(d);
        return it;
    }

    /** @return true when one of {@code list} lasts over {@code beat}. */
    private static boolean covered(List<Events> list, float beat) {
        for (Events e : list) if (beat >= e.startTime() && beat <= e.endTime()) return true;
        return false;
    }

    /** Appends a beat span (at most 3 listed, then …). @return 1 */
    private static int span(StringBuilder b, float from, float to, int listed) {
        if (listed < 3) b.append(listed == 0 ? "" : "、").append(Im.fmt(from)).append('–').append(Im.fmt(to));
        else if (listed == 3) b.append(" 等");
        return 1;
    }

    /**
     * @return true when, at its judgement, every point of n's judgement zone on screen lies in a showing veil: its
     *         lane (a TIDE: the whole width) across its line's zone ({@link GameEngine#ZONE_HALF} each way), sampled
     *         on a 9 × 9 grid of reference points with the game's own test ({@link Veils#containsAny})
     */
    /** Warns about every polygon in {@code list} with fewer than 3 usable points (the game drops it). */
    private static void fewPoints(List<Item> items, List<Doc.DVeil> list, String kind) {
        for (int i = 0; i < list.size(); i++) {
            Doc.DVeil v = list.get(i);
            if (!v.polygon || v.pointCount() >= 3) continue;
            Item it = new Item(WARN, "第 " + (i + 1) + " 块" + kind + "是多边形，能用的点不到 3 个（游戏会丢掉它）");
            it.veil = v.uid;
            if (Float.isFinite(v.start)) it.beat = v.start;
            items.add(it);
        }
    }

    private static boolean zoneVeiled(Chart c, LaneLayout lay, Veil[] veils, Doc.DNote n) {
        float b = n.judgement;
        float x0 = LaneLayout.VIEW_LEFT, x1 = LaneLayout.VIEW_RIGHT;
        if (n.inLane()) {
            lay.update(b, LaneLayout.VIEW_LEFT, LaneLayout.VIEW_RIGHT, 1f);
            int i = lay.indexOf(n.lane);
            if (i < 0 || !lay.active(i)) return false;
            x0 = Math.max(x0, lay.left(i));
            x1 = Math.min(x1, lay.right(i));
        }
        float ly = c.lineEvents().yAt(n.line, b);
        float y0 = Math.max(0f, ly - GameEngine.ZONE_HALF), y1 = Math.min(Field.REF_H, ly + GameEngine.ZONE_HALF);
        if (x1 <= x0 || y1 <= y0) return false;
        for (int i = 0; i <= 8; i++) {
            for (int j = 0; j <= 8; j++) {
                float x = Field.x(x0 + (x1 - x0) * i / 8f), y = Field.y(y0 + (y1 - y0) * j / 8f);
                if (!Veils.containsAny(veils, x, y, b)) return false;
            }
        }
        return true;
    }

    /** @return the global event lists by channel: upper line, lower line, camera frame, global lane scale. */
    static ArrayList<List<Doc.Ev>> lineLists(Doc d) {
        ArrayList<List<Doc.Ev>> l = new ArrayList<>(4);
        l.add(d.upperEv);
        l.add(d.lowerEv);
        l.add(d.cameraFrameEv);
        l.add(d.laneScaleEv);
        return l;
    }

    /** @return how many events start before the previous one (by start) ends. */
    static int overlaps(List<Doc.Ev> list) {
        int n = 0;
        for (int i = 1; i < list.size(); i++) if (list.get(i).start < list.get(i - 1).end - 1e-4f) n++;
        return n;
    }

    private static void firstOverlap(List<Doc.Ev> list, Item item) {
        for (int i = 1; i < list.size(); i++) {
            if (list.get(i).start < list.get(i - 1).end - 1e-4f) {
                item.at(list.get(i).start);
                return;
            }
        }
    }

    /** @return "412 音符 · 水滴 288 / 水流 64 / 水雷 31 / 浪花 29" (plus 浪花水雷 and 假键 when there are some). */
    String summary() {
        int total = 0;
        for (int c : counts) total += c;
        int tm = counts[NoteType.TIDE_MINE.ordinal()];
        return total + " 音符 · 水滴 " + counts[NoteType.TAP.ordinal()] + " / 水流 " + counts[NoteType.HOLD.ordinal()]
            + " / 水雷 " + counts[NoteType.MINE.ordinal()] + " / 浪花 " + counts[NoteType.UNIVERSAL.ordinal()]
            + (tm > 0 ? " / 浪花水雷 " + tm : "") + (fakes > 0 ? " · 另有假键 " + fakes : "");
    }
}
