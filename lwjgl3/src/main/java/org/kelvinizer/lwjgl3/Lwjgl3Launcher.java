package org.kelvinizer.lwjgl3;

import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3WindowAdapter;
import org.kelvinizer.charter.CharterApp;
import org.kelvinizer.charter.Gfx;

/**
 * 启动谱面编辑器（桌面版）：窗口可以改大小，默认最大化；关窗口前会问有没有没保存的修改。
 * Launches the chart editor on the desktop: a resizable window, maximized at start; closing it
 * asks about unsaved work first. {@code -Dkk.autotest=<folder>} runs the self-test instead
 * (fixed 1920 × 1080 window, screenshots into that folder, then exits). {@code -Dkk.tablet=true} shows the
 * Android tablet's layout in a 16:10 window (to try it on a computer; text fields keep the keyboard).
 */
public class Lwjgl3Launcher {
    public static void main(String[] args) {
        if (StartupHelper.startNewJvmIfRequired()) return;
        if (Boolean.getBoolean("kk.tablet")) Gfx.tablet();
        String test = System.getProperty("kk.autotest");
        CharterApp app = new CharterApp(new DesktopPlatform(), test);
        Lwjgl3ApplicationConfiguration c = new Lwjgl3ApplicationConfiguration();
        c.setTitle("K & K Charter");
        c.useVsync(true);
        c.setForegroundFPS(Lwjgl3ApplicationConfiguration.getDisplayMode().refreshRate + 1);
        if (test != null) {
            if (Gfx.TABLET) c.setWindowedMode(2560, 1600);
            else c.setWindowedMode(1920, 1080);
            c.setResizable(false);
        } else if (Gfx.TABLET) {
            c.setWindowedMode(1344, 840);
            c.setResizable(true);
        } else {
            c.setWindowedMode(1600, 900);
            c.setResizable(true);
            c.setMaximized(true);
        }
        c.setWindowSizeLimits(Gfx.TABLET ? 672 : 960, Gfx.TABLET ? 420 : 540, -1, -1);
        c.setWindowIcon("libgdx128.png", "libgdx64.png", "libgdx32.png", "libgdx16.png");
        c.setWindowListener(new Lwjgl3WindowAdapter() {
            @Override
            public boolean closeRequested() {
                return app.closeRequested();
            }
        });
        new Lwjgl3Application(app, c);
    }
}
