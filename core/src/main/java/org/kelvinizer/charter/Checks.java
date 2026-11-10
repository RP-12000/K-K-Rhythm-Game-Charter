package org.kelvinizer.charter;

import org.kelvinizer.elements.Key7;
import org.kelvinizer.elements.KeyKind;
import org.kelvinizer.elements.Lane7;

import java.util.ArrayList;
import java.util.HashMap;

/**
 * 谱面检查：「谱面」页和状态栏 ⚠ 里列出的问题，每一项都能跳到相关的键、轨道或幕布。
 * Chart checks for format v7, shown in the 谱面 tab and counted by the ⚠ badge: keys on a lane that does not exist,
 * 浪花 / 潮汐 spanning no lane (the game does not read them), keys without a fall (no judge beat), streams and tides of
 * length 0, keys judged while their lane is 0 wide, keys stacked on one lane, face and beat (an INFO: allowed, one
 * finger each) and keys judged away from their face (an INFO). Each item keeps the keys it is about, so 跳到 can
 * select them. 假键 are counted apart and only checked for a lane, a fall and a length (they are not judged).
 */
final class Checks {
    /** INFO lines are listed but not counted as problems. */
    static final int OK = 0, WARN = 1, ERROR = 2, INFO = 3;

    static final class Item {
        final int level;
        String text;
        final ArrayList<Integer> uids = new ArrayList<>();
        float beat = Float.NaN;
        /** The lane the item is about ({@link Session#NO_LANE} = none): 跳到 selects it. */
        int lane = Session.NO_LANE;
        /** The veil or curtain the item is about (0 = none). */
        int vol;

        Item(int level, String text) {
            this.level = level;
            this.text = text;
        }

        void add(Doc.DKey k) {
            if (!uids.contains(k.uid)) uids.add(k.uid);
            float j = k.judge();
            if (!Float.isNaN(j) && (Float.isNaN(beat) || j < beat)) beat = j;
        }
    }

    final ArrayList<Item> items = new ArrayList<>();
    /** Number of problem items (warnings and errors). */
    int problems;
    /** Keys by kind ({@link KeyKind#ordinal()}), 假键 left out, and the 假键. */
    final int[] counts = new int[KeyKind.values().length];
    int fakes;
    private int version = Integer.MIN_VALUE;
    private Doc doc;

    void update(Session s) {
        if (s.version == version && s.doc == doc) return;
        version = s.version;
        doc = s.doc;
        items.clear();
        java.util.Arrays.fill(counts, 0);
        fakes = 0;
        Doc d = s.doc;
        Item noLane = new Item(ERROR, ""), noSpan = new Item(ERROR, ""), someSpan = new Item(WARN, "");
        Item noFall = new Item(WARN, ""), zero = new Item(WARN, ""), hidden = new Item(WARN, "");
        Item stack = new Item(INFO, ""), away = new Item(INFO, "");
        HashMap<String, Integer> seen = new HashMap<>();
        for (Doc.DKey dk : d.keys) {
            Key7 k = dk.k;
            if (k.fake) fakes++;
            else counts[k.kind.ordinal()]++;
            if (k.kind.spans()) {
                int found = 0;
                for (int id : k.lanes) if (d.lane(id) != null) found++;
                if (found == 0) noSpan.add(dk);
                else if (found < k.lanes.length) someSpan.add(dk);
            } else if (d.lane(k.lane) == null) {
                noLane.add(dk);
            }
            float j = dk.judge();
            if (Float.isNaN(j)) {
                noFall.add(dk);
                continue;
            }
            if (k.kind.hasHeight() && !(Doc.length(k) > 0f)) zero.add(dk);
            if (k.fake) continue;                                       // 假键 are not judged: nothing more to check
            Lane7 l = d.lane(dk.lane());
            if (l != null && !(l.w.at(j) > 0f)) hidden.add(dk);
            if (Math.abs(k.h.at(j)) > 1f) away.add(dk);
            String at = (k.kind.spans() ? "s" : "l" + k.lane) + (k.sky ? "^" : "_") + Math.round(j * 960f);
            Integer n = seen.get(at);
            seen.put(at, n == null ? 1 : n + 1);
            if (n != null) stack.add(dk);
        }
        add(noLane, "所在的轨道不存在");
        add(noSpan, "浪花 / 潮汐没有跨任何轨道（游戏不读）");
        add(someSpan, "浪花 / 潮汐跨的轨道有的不存在");
        add(noFall, "没有下落分段（判定拍未定）");
        add(zero, "水流 / 潮汐长度为 0");
        add(hidden, "判定时轨道宽为 0（还没出现或已经消失）");
        add(stack, "同一轨道同一面同一拍叠着（每个要一根手指）");
        add(away, "判定时不在判定面上（h ≠ 0）");
        problems = 0;
        for (Item it : items) if (it.level == WARN || it.level == ERROR) problems++;
        if (items.isEmpty()) items.add(new Item(OK, d.keys.isEmpty() ? "还没有键" : "没有发现问题"));
    }

    private void add(Item it, String what) {
        if (it.uids.isEmpty()) return;
        it.text = it.uids.size() + " 个键" + what;
        items.add(it);
    }

    /** @return "412 键 · 水滴 288 / 水流 64 / … · 假键 3" (kinds there are none of left out after 水滴 and 水流). */
    String summary() {
        int total = 0;
        for (int c : counts) total += c;
        StringBuilder b = new StringBuilder().append(total).append(" 键");
        String sep = " · ";
        for (KeyKind k : KeyKind.values()) {
            int c = counts[k.ordinal()];
            if (c == 0 && k != KeyKind.TAP && k != KeyKind.STREAM) continue;
            b.append(sep).append(Inspector.kindName(k)).append(' ').append(c);
            sep = " / ";
        }
        if (fakes > 0) b.append(" · 假键 ").append(fakes);
        return b.toString();
    }
}
