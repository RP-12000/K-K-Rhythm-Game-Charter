package org.kelvinizer.charter;

import com.badlogic.gdx.audio.Music;
import com.badlogic.gdx.Gdx;

import java.io.File;
import java.util.function.Consumer;

/**
 * 和平台有关的功能（选文件、打开文件夹、播放速度），电脑版和安卓平板版各自实现。
 * Services the editor needs from the platform, implemented by the desktop (LWJGL3) and Android launchers: file
 * choosers, opening a folder in the system file manager and playback speed.
 */
public interface Platform {
    /**
     * Shows a file chooser off the GL thread; {@code done} runs on the GL thread with the chosen
     * file, or is not called when the user cancels.
     *
     * @param exts allowed extensions without the dot, e.g. {@code "mp3"}
     */
    void pickFile(String title, String[] exts, Consumer<File> done);

    /** Opens {@code dir} in the system file manager. */
    void openFolder(File dir);

    /** Sets the playback speed of a playing music stream (1 = normal). */
    void setMusicSpeed(Music music, float speed);

    /** Runs once on the GL thread before the library is opened (Android copies the bundled charts there). */
    default void prepare() {
    }

    /** Shows a native multiline editor when the platform has one. */
    default void textArea(String title, String value, Consumer<String> done) {
        Gdx.input.getTextInput(new com.badlogic.gdx.Input.TextInputListener() {
            @Override public void input(String text) { done.accept(text); }
            @Override public void canceled() { }
        }, title, value, "", com.badlogic.gdx.Input.OnscreenKeyboardType.Default);
    }

    /**
     * Asks for a number on the tablet. {@code hint} says what {@code /} (the playhead) stands for; with
     * {@code beats} the box also offers 播放头 / +1/4 / −1/4 buttons. {@code done} runs on the GL thread with the
     * text, and is not called on cancel.
     */
    default void numberInput(String title, String value, String hint, boolean beats, Consumer<String> done) {
        Gdx.input.getTextInput(new com.badlogic.gdx.Input.TextInputListener() {
            @Override public void input(String text) { done.accept(text); }
            @Override public void canceled() { }
        }, title + " · " + hint, value, hint, com.badlogic.gdx.Input.OnscreenKeyboardType.Default);
    }


}
