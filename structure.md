# K and K Rhythm Game Charter — Project Structure

This document describes how the LibGDX desktop app is organized **excluding ChartEditorScreen internals** (that screen is being rewritten). It focuses on class relationships, runtime flow, transitions, and how other components interact with the editor.

All gameplay UI is authored on a **1920×1080 virtual canvas** (`Main.WORLD_WIDTH` / `Main.WORLD_HEIGHT`) and scaled with a shared `FitViewport`.

---

## High-level runtime flow

```
Main (Game)
  └── SelectionScreen          ← entry point, chart picker, music preview
        │
        │  Edit + CurtainTransition (close → lines → covered → switch)
        ▼
      ChartEditorScreen        ← chart authoring (details omitted here)
        │
        │  Back + CurtainTransition (close → lines → covered → switch)
        ▼
      SelectionScreen(opening=true)   ← curtain opens on return
```

**Rhythm of the app**

1. **Boot** — `Main.create()` builds shared `SpriteBatch` + `FitViewport`, opens `SelectionScreen`.
2. **Browse** — user scrolls chart list; selecting a chart preloads/plays a 20s music preview and updates the jacket image.
3. **Enter editor** — outgoing `SelectionScreen` runs a full curtain close + ray burst; incoming `ChartEditorScreen` runs curtain open.
4. **Edit** — (ChartEditorScreen — not documented here.)
5. **Return** — outgoing editor runs the same close sequence; incoming menu runs curtain **opening** so the return trip feels symmetric.

---

## Package map

| Package / class | Role |
|-----------------|------|
| `org.kelvinizer.Main` | LibGDX `Game` entry; owns shared batch + viewport |
| `org.kelvinizer.selectionscreen.*` | Menu UI, chart list, particles, transitions into editor |
| `org.kelvinizer.elements.Chart` | Chart data model (JSON + on-disk assets) |
| `org.kelvinizer.elements.ChartMapper` | Loads/saves chart folders |
| `org.kelvinizer.chartEditor.ChartEditorScreen` | Editor screen (rewrite in progress) |
| `org.kelvinizer.transition.CurtainTransition` | Reusable curtain + ray transition |
| `org.kelvinizer.audio.Mp3DurationUtil` | MP3 duration via Java Sound + MP3SPI |
| `org.kelvinizer.NoteEvents.*` | Note simulation events + render DTO |

---

## Main

`Main` is the single LibGDX application root.

- Creates one **`SpriteBatch`** and one **`FitViewport(1920, 1080)`** shared by screens that opt in (`SelectionScreen` uses `main.getBatch()` / `main.getViewport()`).
- Starts on **`new SelectionScreen(this)`** (no opening animation on cold boot).
- Disposes the batch when the app exits.

Screens call **`main.setScreen(...)`** to navigate. Screen switches during transitions are deferred with **`Gdx.app.postRunnable`** inside `CurtainTransition` so the curtain stays drawn for one more frame before the swap (prevents menu/editor flash).

---

## On-disk chart layout

Each chart lives under `local://Charts/<ChartName>/`:

```
Charts/
  MySong/
    chart.json      ← required
    something.mp3   ← optional, first .mp3 found
    cover.jpg       ← optional, first .jpg/.jpeg found
```

`ChartMapper.readFolder(folder)`:

1. Finds the first `.json` in the folder (typically `chart.json`).
2. Deserializes into **`Chart`** (Jackson).
3. Attaches **`chartFile`**, **`musicFile`**, **`jacketFile`** as `@JsonIgnore` LibGDX `FileHandle`s.

---

## Chart (data model)

`Chart` is the in-memory chart:

- Serializable fields: `name`, `difficulty`, `chart constant`, `Notes[]`.
- Runtime-only: file handles, note pointer indices for playback simulation.
- **`getChartFolder()`** — parent directory used for deletion and asset lookup.
- **`update(time)`** — advances note pointers and returns **`RenderableNote`** list (used if you add a play/test screen later).

Other classes hold **`Chart` references** but do not own file deletion except `SelectionScreen`.

---

## ChartMapper

Static JSON I/O helper:

- **`readFolder(FileHandle)`** — load chart + discover mp3/jacket siblings.
- **`read(String)` / `read(InputStream)`** — JSON only, no asset discovery.

Used by:

- `SelectionScreen.loadAllCharts()` at startup
- `SelectionScreen.createChartFromMp3()` when importing a new song
- `ChartEditorScreen.saveChart()` (writes JSON back to `chartFile`)

---

## Mp3DurationUtil

Reads MP3 length using **Java Sound API + MP3SPI** (`com.googlecode.soundlibs:mp3spi`).

- **`getDurationSecondsSafe(FileHandle)`** — used by `ChartEditorScreen` for progress bar length (LibGDX `Music` has no duration API).
- Playback still uses LibGDX **`Music`** for streaming; only **metadata/duration** goes through MP3SPI.

---

## SelectionScreen

The menu / chart picker.

### UI building blocks

| Component | Purpose |
|-----------|---------|
| `ChartListPanel` | Elastic scroll list; index `-1` = “New Chart” |
| `ParticleBackground` | Animated background particles |
| `RoundedRectRenderer` | Pill-shaped buttons |
| `texJacket` | Large cover art (default or per-chart) |
| `CurtainTransition` | Screen transitions |

### Music preview

- On startup, **`preloadChartMusic()`** creates one LibGDX **`Music`** per chart (stored in `preloadedMusic` map).
- When selection changes, **`startPreview(chart)`** plays from 0 for up to **20 seconds**.
- **`stopPreview()`** pauses and resets position.

**Important for deletion:** Windows locks `.mp3` files while `Music` is alive. `deleteChartAtIndex` calls **`releaseMusicForChart`** (stop + dispose + remove from map) **before** `deleteDirectory()`.

### Jacket preview

- **`displayedJacketChart`** tracks which chart’s image is bound to `texJacket`.
- **`updateJacket(chart)`** loads jacket from chart folder; falls back to **`default.png`**.
- **`resetJacketIfShowing(chart)`** disposes texture before folder delete so `.jpg` files are not locked.

### Delete flow

1. Swing confirm dialog (off GL thread).
2. **`Gdx.app.postRunnable`** → **`deleteChartAtIndex`** on GL thread:
   - stop preview, dispose music, reset jacket if needed
   - `chartFolder.deleteDirectory()`
   - `charts.remove`, `chartPanel.afterChartRemovedAt(idx)`

### Transition into editor

```java
curtainTransition.startClose(() -> {
    main.setScreen(new ChartEditorScreen(main, chart, true));
});
```

Outgoing screen: **CLOSING → LINES → COVERED (1 frame hold) → switch**.  
Incoming editor: **`beginOpening()`** in constructor.

Constructors:

- **`SelectionScreen(main)`** — normal boot, no opening curtain.
- **`SelectionScreen(main, true)`** — return from editor; runs opening animation.

---

## ChartListPanel

Scrollable list with “New Chart” at logical index **-1**.

- Shares the **same `List<Chart>` instance** as `SelectionScreen.charts` (live view; no copy).
- **`afterChartRemovedAt(index)`** — fixes selection + scroll after deletion.
- Keyboard: Up/Down/Enter handled by `SelectionScreen`.

Does not know about transitions or music; only displays names and reports **`getSelectedIndex()`**.

---

## CurtainTransition

Reusable full-screen transition in `org.kelvinizer.transition`.

### Phases

```
IDLE → CLOSING → LINES → COVERED → (callback / switch) → [other screen OPENING] → IDLE
```

| Phase | Visual |
|-------|--------|
| CLOSING | Upper/lower panels meet at vertical center |
| LINES | Panels stay closed; rays shoot from two dots on horizontal midline |
| COVERED | Panels + full-length rays; underlay UI skipped; hold 1 frame |
| OPENING | Panels separate from center to top/bottom |

### Config knobs

- `closeDuration`, `openDuration`
- `dotOffsetFromCenter` — left dot at `centerX - offset`, right at `centerX + offset`
- `lineThickness`, `lineColor`, `dotRadius`, `lineSpeed`
- `holdFramesAfterLines` — prevents flash before `setScreen`

### API used by screens

| Method | When |
|--------|------|
| `startClose(Runnable)` | Leaving a screen (always includes LINES) |
| `beginOpening()` | New screen created already covered |
| `update(delta)` / `render(batch, sr)` | Each frame |
| `isActive()` | Block input while transitioning |
| `isFullyClosed()` | Skip drawing underlay (COVERED phase) |

Both `SelectionScreen` and `ChartEditorScreen` own their own `CurtainTransition` instance (same textures, same config builder).

### Symmetric round trip

| Direction | Outgoing | Incoming |
|-----------|----------|----------|
| Menu → Editor | `SelectionScreen.startClose` | `ChartEditorScreen(..., true)` → `beginOpening()` |
| Editor → Menu | `ChartEditorScreen.startBackTransition` | `new SelectionScreen(main, true)` → `beginOpening()` |

---

## ChartEditorScreen (interaction surface only)

Not documenting internal editor UI. Other classes interact with it as follows:

| Caller | Interaction |
|--------|-------------|
| `SelectionScreen` | `new ChartEditorScreen(main, chart, true)` after transition |
| `ChartEditorScreen` | Reads/writes `Chart` via `ChartMapper` / Jackson |
| `ChartEditorScreen` | Uses `Mp3DurationUtil` for timeline length, LibGDX `Music` for playback |
| `ChartEditorScreen` | `main.setScreen(new SelectionScreen(main, true))` on back |
| `Note` / events | Editor mutates `chart.getNotes()`; saved to JSON on disk |

When the editor is disposed, its `Music` and textures are released in **`ChartEditorScreen.dispose()`** — separate from menu preloaded music map.

---

## NoteEvents package

Shared between editor and any future gameplay screen:

| Class | Purpose |
|-------|---------|
| `Note` | Tap/hold note with judgement time, lane, event lists |
| `MovementEvents` | Position over time (fall paths) |
| `RotationEvent` | Rotation over time |
| `DisappearEvent` | Hide note over interval |
| `RenderableNote` | Lightweight snapshot for rendering |

`Chart.update(time)` walks notes and produces `RenderableNote` instances without exposing pointer logic to renderers.

---

## Supporting selection UI

### ParticleBackground

Updates and draws floating particles behind menu content. Independent of charts/transitions.

### RoundedRectRenderer

Draws rounded rectangles for list pills and buttons using `ShapeRenderer`. Used by `ChartListPanel` and menu buttons.

---

## Threading rules

| Action | Thread |
|--------|--------|
| Rendering, input, `Music.dispose`, `Texture.dispose`, file delete | LibGDX GL thread (`render`, `postRunnable`) |
| Swing file chooser / confirm dialogs | EDT (`SwingUtilities.invokeLater`) |
| Screen switch after transition | `Gdx.app.postRunnable` inside `CurtainTransition` |

Never delete chart folders or dispose LibGDX audio/textures from the Swing EDT.

---

## Dependency note

```gradle
implementation "com.googlecode.soundlibs:mp3spi:1.9.5.4"
```

Required for `Mp3DurationUtil` to parse `.mp3` duration. LibGDX remains responsible for runtime music streaming.

---

## File index (quick reference)

```
org.kelvinizer/
  Main.java
  audio/
    Mp3DurationUtil.java
  transition/
    CurtainTransition.java
  selectionscreen/
    SelectionScreen.java
    ChartListPanel.java
    ParticleBackground.java
    RoundedRectRenderer.java
  chartEditorScreen/
    Chart.java
    ChartMapper.java
    ChartEditorScreen.java    ← rewrite target
  NoteEvents/
    Note.java
    MovementEvents.java
    RotationEvent.java
    DisappearEvent.java
    RenderableNote.java
```

---

## Mental model for future work

When rewriting **ChartEditorScreen**, keep these contracts:

1. Accept **`(Main main, Chart chart, boolean withOpeningTransition)`** from the menu.
2. On back, run **`curtainTransition.startClose(...)`** then open **`SelectionScreen(main, true)`**.
3. Save chart JSON through **`chart.getChartFile()`**; do not break `ChartMapper` folder layout.
4. Use **`Mp3DurationUtil`** for duration; dispose **`Music`** in `dispose()`.
5. Assume **`SelectionScreen`** may still hold preloaded `Music` for the same chart when returning — do not double-dispose menu-owned instances from the editor.

This keeps navigation, audio file locking, and transitions stable while the editor UI is replaced.
