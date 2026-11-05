package org.kelvinizer.charter.android;

import android.os.Bundle;

import com.badlogic.gdx.backends.android.AndroidApplication;
import com.badlogic.gdx.backends.android.AndroidApplicationConfiguration;
import org.kelvinizer.charter.CharterApp;
import org.kelvinizer.charter.Gfx;

import java.io.File;

/**
 * 谱面编辑器的安卓平板版入口：16:10 的平板排版，全屏、屏幕常亮；谱面在应用自己的文件夹（Android/data/…/files/Charts）。
 * Launches the chart editor on an Android tablet: the 16:10 tablet layout, immersive and kept awake. The charts live
 * in the app's own folder ({@code Android/data/org.kelvinizer.charter/files/Charts}), which a computer can reach over
 * USB; the charts bundled in the APK are copied there on first start ({@link AndroidPlatform#prepare()}).
 */
public class AndroidLauncher extends AndroidApplication {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        File home = getExternalFilesDir(null);
        if (home == null) home = getFilesDir(); // shared storage not mounted
        System.setProperty("kk.home", home.getAbsolutePath());
        System.setProperty("kk.charts", new File(home, "Charts").getAbsolutePath());
        Gfx.tablet();
        AndroidApplicationConfiguration configuration = new AndroidApplicationConfiguration();
        configuration.useImmersiveMode = true;
        configuration.useAccelerometer = false;
        configuration.useCompass = false;
        configuration.useWakelock = true; // keep the screen on while listening through a song
        initialize(new CharterApp(new AndroidPlatform(this), null), configuration);
    }
}
