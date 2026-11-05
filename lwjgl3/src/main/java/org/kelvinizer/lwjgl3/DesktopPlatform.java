package org.kelvinizer.lwjgl3;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.audio.Music;
import org.kelvinizer.charter.Platform;
import org.lwjgl.openal.AL10;

import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.Desktop;
import java.io.File;
import java.lang.reflect.Field;
import java.util.function.Consumer;

/**
 * 桌面版的平台功能：Swing 选文件、打开文件夹、用 OpenAL 改播放速度。
 * Desktop implementation of the editor's platform services: Swing file choosers (on the AWT
 * thread, results posted back to the GL thread), opening folders and playback speed through the
 * OpenAL source pitch.
 */
final class DesktopPlatform implements Platform {
    private static File lastDir;
    private Field sourceField;
    private boolean sourceTried;



    @Override
    public void pickFile(String title, String[] exts, Consumer<File> done) {
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception ignored) {
            }
            JFrame owner = new JFrame();
            owner.setUndecorated(true);
            owner.setAlwaysOnTop(true);
            owner.setLocationRelativeTo(null);
            owner.setVisible(true);
            JFileChooser c = new JFileChooser(lastDir);
            c.setDialogTitle(title);
            c.setFileFilter(new FileNameExtensionFilter(String.join(" / ", exts), exts));
            int r = c.showOpenDialog(owner);
            owner.dispose();
            if (r != JFileChooser.APPROVE_OPTION || c.getSelectedFile() == null) return;
            File f = c.getSelectedFile();
            lastDir = f.getParentFile();
            Gdx.app.postRunnable(() -> done.accept(f));
        });
    }

    @Override
    public void openFolder(File dir) {
        new Thread(() -> {
            try {
                Desktop.getDesktop().open(dir);
            } catch (Exception e) {
                try {
                    String os = System.getProperty("os.name").toLowerCase();
                    String cmd = os.contains("win") ? "explorer" : os.contains("mac") ? "open" : "xdg-open";
                    new ProcessBuilder(cmd, dir.getAbsolutePath()).start();
                } catch (Exception e2) {
                    Gdx.app.error("Charter", "cannot open " + dir + ": " + e2.getMessage());
                }
            }
        }, "charter-open").start();
    }

    @Override
    public void setMusicSpeed(Music music, float speed) {
        if (!sourceTried) {
            sourceTried = true;
            for (Class<?> k = music.getClass(); k != null && sourceField == null; k = k.getSuperclass()) {
                for (Field f : k.getDeclaredFields()) {
                    if (f.getType() == int.class && f.getName().equalsIgnoreCase("sourceID")) {
                        f.setAccessible(true);
                        sourceField = f;
                    }
                }
            }
        }
        if (sourceField == null) return;
        try {
            int id = sourceField.getInt(music);
            if (id >= 0) AL10.alSourcef(id, AL10.AL_PITCH, speed);
        } catch (Exception e) {
            Gdx.app.error("Charter", "playback speed: " + e.getMessage());
        }
    }
}
