package org.kelvinizer.charter.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.database.Cursor;
import android.media.MediaPlayer;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.view.Gravity;
import android.view.WindowManager;
import android.webkit.MimeTypeMap;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.audio.Music;
import com.badlogic.gdx.backends.android.AndroidApplication;
import com.badlogic.gdx.files.FileHandle;
import org.kelvinizer.charter.Im;
import org.kelvinizer.charter.Platform;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * 安卓平板版的平台功能：第一次打开时把 APK 里带的谱面复制到应用的文件夹，用系统的文件选择器选音频和曲绘，
 * 「打开文件夹」显示文件夹在哪，播放速度用 MediaPlayer 调。
 * The Android tablet's platform services: copies the charts bundled in the APK into the app's folder (each song
 * once), picks files with the system's document picker, shows where a folder is for "open folder" and sets the
 * playback speed on the MediaPlayer.
 */
final class AndroidPlatform implements Platform {
    private static final int PICK = 0x4b4b;
    /** The songs already copied out of the APK, one {@code Charts/<song>} (or {@code Introduction}) per line. */
    private static final String SEEDED = "seeded.txt";
    private final AndroidApplication app;
    /** The open picker's callback and allowed extensions (UI thread). */
    private Consumer<File> pending;
    private String[] pendingExts;
    private Field player;
    private boolean playerTried;
    /** The speed last set on a player: 1 until changed, so normal playback never touches PlaybackParams. */
    private float speedSet = 1f;

    AndroidPlatform(AndroidApplication app) {
        this.app = app;
        app.addAndroidEventListener(this::picked);
    }

    /** A number box with the {@code /} hint and, for beats, 播放头 / +1/4 / −1/4 buttons above the keyboard. */
    @Override
    public void numberInput(String title, String value, String hint, boolean beats, Consumer<String> done) {
        app.runOnUiThread(() -> {
            float dp = app.getResources().getDisplayMetrics().density;
            int pad = Math.round(20f * dp);
            LinearLayout box = new LinearLayout(app);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setPadding(pad, pad / 2, pad, 0);
            EditText input = new EditText(app);
            input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
            input.setSingleLine(true);
            input.setText(value);
            input.selectAll();
            box.addView(input);
            TextView note = new TextView(app);
            note.setText(hint);
            note.setTextSize(12f);
            box.addView(note);
            if (beats) {
                LinearLayout row = new LinearLayout(app);
                row.setOrientation(LinearLayout.HORIZONTAL);
                String[] names = {"播放头", "+1/4", "−1/4"};
                for (int i = 0; i < names.length; i++) {
                    Button b = new Button(app);
                    b.setText(names[i]);
                    b.setAllCaps(false);
                    float by = i == 1 ? 0.25f : -0.25f;
                    boolean head = i == 0;
                    b.setOnClickListener(v -> {
                        input.setText(head ? "/" : Im.nudge(input.getText().toString(), by));
                        input.setSelection(input.length());
                    });
                    row.addView(b, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
                }
                box.addView(row);
            }
            AlertDialog dialog = new AlertDialog.Builder(app)
                .setTitle(title)
                .setView(box)
                .setPositiveButton(android.R.string.ok, (d, which) -> {
                    String text = input.getText().toString();
                    Gdx.app.postRunnable(() -> done.accept(text));
                })
                .setNegativeButton(android.R.string.cancel, null)
                .create();
            dialog.setOnShowListener(d -> {
                input.requestFocus();
                if (dialog.getWindow() != null)
                    dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
            });
            dialog.show();
        });
    }

    @Override
    public void textArea(String title, String value, Consumer<String> done) {
        app.runOnUiThread(() -> {
            EditText input = new EditText(app);
            input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES | InputType.TYPE_TEXT_FLAG_AUTO_CORRECT);
            input.setSingleLine(false);
            input.setGravity(Gravity.TOP | Gravity.START);
            input.setMinLines(4);
            input.setMaxLines(10);
            input.setImeOptions(EditorInfo.IME_FLAG_NO_ENTER_ACTION);
            input.setText(value);
            input.setSelection(input.length());
            ScrollView scroll = new ScrollView(app);
            int pad = Math.round(20f * app.getResources().getDisplayMetrics().density);
            scroll.setPadding(pad, pad / 2, pad, 0);
            scroll.addView(input);
            AlertDialog dialog = new AlertDialog.Builder(app)
                .setTitle(title)
                .setView(scroll)
                .setPositiveButton(android.R.string.ok, (d, which) -> {
                    String text = input.getText().toString();
                    Gdx.app.postRunnable(() -> done.accept(text));
                })
                .setNegativeButton(android.R.string.cancel, null)
                .create();
            dialog.setOnShowListener(d -> {
                input.requestFocus();
                if (dialog.getWindow() != null)
                    dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
                        | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
            });
            dialog.show();
        });
    }

    /**
     * Copies {@code seed/Charts/*} and {@code seed/Introduction} from the APK into the app's folder: each one the
     * first time this APK brings it, and never over a folder that is already there (edits on the tablet stay; a
     * song moved to the trash does not come back).
     */
    @Override
    public void prepare() {
        FileHandle home = Gdx.files.absolute(System.getProperty("kk.home"));
        FileHandle list = home.child(SEEDED);
        ArrayList<String> done = new ArrayList<>();
        if (list.exists()) {
            for (String line : list.readString("UTF-8").split("\n")) {
                if (!line.trim().isEmpty()) done.add(line.trim());
            }
        }
        int before = done.size();
        for (FileHandle song : Gdx.files.internal("seed/Charts").list()) {
            seed(song, home.child("Charts"), "Charts/" + song.name(), done);
        }
        FileHandle intro = Gdx.files.internal("seed/Introduction");
        if (intro.exists()) seed(intro, home, "Introduction", done);
        if (done.size() == before) return;
        StringBuilder sb = new StringBuilder();
        for (String s : done) sb.append(s).append('\n');
        list.writeString(sb.toString(), false, "UTF-8");
    }

    private static void seed(FileHandle src, FileHandle parent, String key, ArrayList<String> done) {
        if (done.contains(key)) return;
        try {
            parent.mkdirs();
            if (!parent.child(src.name()).exists()) src.copyTo(parent);
            done.add(key);
        } catch (RuntimeException e) {
            Gdx.app.error("Charter", "cannot copy " + key + " out of the APK: " + e.getMessage());
        }
    }

    @Override
    public void pickFile(String title, String[] exts, Consumer<File> done) {
        app.runOnUiThread(() -> {
            // the major type of the first extension (audio/*, image/*): exact types would hide files a provider labels
            // differently (audio/wav, audio/x-wav); the extension is checked when the file comes back
            String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(exts[0]);
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType(mime != null && mime.indexOf('/') > 0 ? mime.substring(0, mime.indexOf('/')) + "/*" : "*/*");
            pending = done;
            pendingExts = exts;
            try {
                app.startActivityForResult(i, PICK);
            } catch (RuntimeException e) {
                pending = null;
                toast("这台平板没有文件选择器");
            }
        });
    }

    /** The picker's result (UI thread): the file is copied to the cache, then handed over on the GL thread. */
    private void picked(int requestCode, int resultCode, Intent data) {
        if (requestCode != PICK) return;
        Consumer<File> done = pending;
        String[] exts = pendingExts;
        pending = null;
        if (done == null || resultCode != Activity.RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        new Thread(() -> {
            File f = copyToCache(uri, exts);
            if (f != null) Gdx.app.postRunnable(() -> done.accept(f));
        }, "charter-pick").start();
    }

    /** @return a copy of the picked document under its own name in the cache, or null (with a note) */
    private File copyToCache(Uri uri, String[] exts) {
        String name = null;
        try (Cursor c = app.getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null,
            null)) {
            if (c != null && c.moveToFirst() && !c.isNull(0)) name = c.getString(0);
        } catch (RuntimeException ignored) {
        }
        if (name == null) name = uri.getLastPathSegment() != null ? uri.getLastPathSegment() : "picked";
        name = name.replaceAll("[\\\\/:*?\"<>|]", "_");
        String ext = name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        if (!Arrays.asList(exts).contains(ext)) {
            toast("请选 " + String.join(" / ", exts) + " 文件");
            return null;
        }
        File dir = new File(app.getCacheDir(), "picked");
        dir.mkdirs();
        File f = new File(dir, name);
        try (InputStream in = app.getContentResolver().openInputStream(uri);
             OutputStream out = new FileOutputStream(f)) {
            if (in == null) throw new IllegalStateException("no stream");
            byte[] buf = new byte[1 << 16];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            return f;
        } catch (Exception e) {
            Gdx.app.error("Charter", "cannot read " + uri + ": " + e);
            toast("读不了这个文件");
            return null;
        }
    }

    /** There is no file manager to open: shows the folder's path and how a computer reaches it. */
    @Override
    public void openFolder(File dir) {
        String path = dir.getAbsolutePath();
        int at = path.indexOf("/Android/data/");
        String usb = at >= 0 ? "内部存储" + path.substring(at) : path;
        app.runOnUiThread(() -> new AlertDialog.Builder(app)
            .setTitle("文件夹")
            .setMessage(path + "\n\n用数据线连电脑后，在电脑上打开：\n" + usb)
            .setPositiveButton("好", null)
            .show());
    }

    /** Speed and pitch together, like the desktop's OpenAL pitch. Called only while the music plays. */
    @Override
    public void setMusicSpeed(Music music, float speed) {
        if (speed == 1f && speedSet == 1f) return;
        try {
            if (!playerTried) {
                playerTried = true;
                player = music.getClass().getDeclaredField("player"); // libGDX's AndroidMusic
                player.setAccessible(true);
            }
            if (player == null) return;
            MediaPlayer p = (MediaPlayer) player.get(music);
            if (p == null) return;
            // a non-zero speed also starts a paused player, so this is only called while the music plays
            p.setPlaybackParams(p.getPlaybackParams().setSpeed(speed).setPitch(speed));
            speedSet = speed;
        } catch (Exception e) {
            Gdx.app.error("Charter", "playback speed: " + e);
        }
    }

    private void toast(String text) {
        app.runOnUiThread(() -> Toast.makeText(app, text, Toast.LENGTH_LONG).show());
    }
}
