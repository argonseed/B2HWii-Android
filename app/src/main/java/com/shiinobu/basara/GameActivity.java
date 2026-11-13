package com.shiinobu.basara;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.os.Process;
import android.system.Os;
import android.util.Log;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.Toast;

import org.libsdl.app.SDLActivity;

/**
 * The game: SDL's activity running libmain.so (the translated game, its runtime, SDL and Dawn,
 * linked into one library), with the on-screen remote and the phone's motion on top.
 */
public final class GameActivity extends SDLActivity
        implements TouchControlsView.Listener, MotionInput.Listener {
    private static final String TAG = "Basara";

    /** Player 1's on-screen remote and motion (runtime/src/android/android_bridge.cpp). */
    static native void nativeSetTouchState(int buttons, boolean shake, float tilt);
    static native void nativeSetTouchSticks(float leftX, float leftY, float rightX, float rightY);

    /** The Display menu: aspect, render scale (480 lines per 1x), FPS counter (DisplaySettings). */
    static native void nativeApplyDisplaySettings(int aspect, float renderScale, boolean showFps,
            boolean dynamicResolution, int overlayItems, boolean frameGraph, boolean thermalControl,
            int textureFilter);

    /** Settings > Cheats (runtime/src/cheats.cpp); a new onceSeq sets the money once at the next frame. */
    static native void nativeApplyCheats(int money, int amount, int onceSeq, boolean infiniteHp,
            boolean infiniteBasara, boolean unlockAll);

    /** Settings > Display > Camera Distance (1 = the game's camera). */
    static native void nativeSetCameraDistance(float distance);
    static native void nativeSetDisableFog(boolean disabled);
    static native void nativeSetReduceEffects(boolean on);

    /** Settings > Controls > Gamepad Buttons (GamepadButtons): "a=b,b=a,...", "" = the default layout. */
    static native void nativeSetGamepadMap(String map);

    /** The in-game settings: upscaler (0 off, 1 FSR Light, 2 FSR Full, 3 SGSR), frameskip (0 off, 1 auto, 2 30 FPS lock). */
    static native void nativeApplyLiveSettings(int upscaler, int frameskip);
    /** Sustained Performance while playing: the CPU boost lets go (true) or comes back (cpu_boost.cpp). */
    static native void nativeSetSustained(boolean on);
    /** VSync while playing, from the next present. */
    static native void nativeSetVSync(boolean on);

    /** "GPU name\n1|0":the GPU the game draws with and whether its compatibility fixes are on. */
    static native String nativeGraphicsInfo();

    /** What this phone is called in LAN room lists (netplay). */
    static native void nativeSetDeviceName(String name);

    /** Whether this run is a LAN session, and leaving one (the host keeps the session's save). */
    static native boolean nativeSessionActive();
    static native void nativePrepareToLeave();
    /** A tap on the game's menus, as fractions of the screen. */
    static native void nativeTap(float x, float y);
    static native void nativePointer(float x, float y, boolean held);
    /** Whether a menu that taps drive is up (the remote is hidden then). */
    static native boolean nativeTapScreen();
    /** {render scale, show FPS, aspect} after the game's own settings window changed them, else null. */
    static native float[] nativeTakeSettings();
    /**
     * The app left the screen (true) or came back (false). Leaving, the game writes a backup Quick Save
     * of the progress so far (deleted again when the app comes back) and pauses; nativeGamePaused says
     * when it has (runtime/src/host_suspend.h).
     */
    static native void nativeSetBackground(boolean background);
    static native boolean nativeGamePaused();
    /** The overlay's battery: many phones deny the power_supply files to apps, so it comes from here. */
    static native void nativeSetBattery(int percent, float tempC);
    /** The Back menu: pause the game while it is open (true), carry on (false). */
    static native void nativeSetMenuPause(boolean paused);

    /** How long leaving the app waits for the backup save and the pause, before SDL's own pause. */
    private static final long SUSPEND_WAIT_MS = 1500;

    private WifiManager.MulticastLock multicastLock;

    private AppSettings settings;
    private TouchControlsView touch;
    private MotionInput motion;
    private boolean nativeReady;
    private int touchButtons;
    private boolean motionShake;
    private float motionTilt;

    /**
     * Display Resolution: the game draws into a surface of that many lines and the compositor stretches
     * it to the screen for free (ARMSX2's hardware scaler), so the swapchain and every full-screen pass
     * (presentation, snapshot, overlay) cost less GPU time. Nothing when it is at or above the screen.
     */
    /** The Display Resolution in use (0: the screen's own), so a change while playing resizes once. */
    private int appliedLines = -1;

    /**
     * Display Resolution: the surface's own size, below the screen's. Changed while playing, the
     * surface is resized and the renderer makes its swapchain again, as after a rotation.
     */
    private void applyDisplayResolution() {
        if (mSurface == null) return;
        int lines = settings.displayLines();
        final android.util.DisplayMetrics m = new android.util.DisplayMetrics();
        getWindowManager().getDefaultDisplay().getRealMetrics(m);
        final int longSide = Math.max(m.widthPixels, m.heightPixels), shortSide = Math.min(m.widthPixels, m.heightPixels);
        if (shortSide <= 0 || lines >= shortSide * 0.95f) lines = 0;
        if (lines == appliedLines) return;
        final boolean first = appliedLines < 0;
        appliedLines = lines;
        if (lines <= 0) {
            if (!first) mSurface.getHolder().setSizeFromLayout();  // the screen's own size again
            if (!first) Log.i(TAG, "display resolution: the screen's own");
            return;
        }
        final int width = Math.round(longSide * (float) lines / shortSide) & ~1;
        mSurface.getHolder().setFixedSize(width, lines);  // the game is landscape (sensorLandscape)
        Log.i(TAG, "display resolution: " + width + "x" + lines + " on a " + longSide + "x" + shortSide + " screen");
    }

    /**
     * Always landscape, either way up by the sensor, whether or not auto-rotate is on. SDL would ask
     * for USER_LANDSCAPE, which stays on one side while the player has rotation locked.
     */
    @Override
    public void setOrientationBis(int w, int h, boolean resizable, String hint) {
        setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
    }

    private static void notePrepareError(StringBuilder errors, String step, Exception e) {
        Log.e(TAG, "could not prepare the game's files (" + step + ")", e);
        errors.append(step).append(": ").append(e).append('\n')
                .append(Log.getStackTraceString(e)).append('\n');
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        CrashReport.installJavaHandler(this);
        CrashReport.testCrashIfAsked();
        settings = new AppSettings(this);
        Tr.init(this);  // the in-game menu speaks the launcher's language
        // Each step on its own: one failing (a full disk while the runtime files are copied after an
        // update) used to skip Config.toml and the env below, and the game then stopped at once with
        // "No DVD root is configured". The failures go to the run's log folder for tester reports.
        final StringBuilder prepareErrors = new StringBuilder();
        java.io.File runLogs = null;
        try {
            runLogs = AppFiles.newRunLogDir();
        } catch (Exception e) {
            notePrepareError(prepareErrors, "newRunLogDir", e);
        }
        final java.io.File logs = runLogs;
        try {
            AppFiles.prepareRuntime(this);
        } catch (Exception e) {
            notePrepareError(prepareErrors, "prepareRuntime", e);
        }
        try {
            final String driverOff = GpuDrivers.checkLastStart(this, settings);
            if (driverOff != null) Toast.makeText(this, driverOff, Toast.LENGTH_LONG).show();
            GpuDrivers.applyToGame(this, settings);
        } catch (Exception e) {
            notePrepareError(prepareErrors, "gpu drivers", e);
        }
        try {
            AppFiles.writeConfig(this, settings);
        } catch (Exception e) {
            notePrepareError(prepareErrors, "writeConfig", e);
        }
        // Saves: back after a new install or Clear data (from Basara2Heroes/Saves/Auto), and the last
        // session's save copied out, before the game opens its NAND.
        if (SaveData.autoRestore(this)) {
            Toast.makeText(this, Tr.t("Your save was restored from Basara2Heroes/Saves/Auto."), Toast.LENGTH_LONG).show();
        }
        SaveData.autoBackup(this);
        try {
            DevTools.onGameStart(this);  // before the runtime reads env.txt
        } catch (Exception e) {
            notePrepareError(prepareErrors, "DevTools", e);
        }
        if (prepareErrors.length() > 0) {
            Toast.makeText(this, "Could not prepare the game's files: " + prepareErrors.toString().split("\n", 2)[0],
                    Toast.LENGTH_LONG).show();
            if (logs != null) {
                try (java.io.FileOutputStream out = new java.io.FileOutputStream(new java.io.File(logs, "prepare_errors.txt"))) {
                    out.write(prepareErrors.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                } catch (Exception ignored) {
                }
            }
        }
        try {
            // Read by runtime/src/platform/host_platform.cpp before anything else starts.
            Os.setenv("WIICOMPILED_RUNTIME_DIR", AppFiles.runtimeDir(this).getAbsolutePath(), true);
            Os.setenv("WIICOMPILED_DATA_DIR", AppFiles.dataDir(this).getAbsolutePath(), true);
            // Read mode: the game file, opened here (the system picker's grant is the app's, not a path
            // the game could open) and kept open while the game runs; the runtime reads it in place.
            final android.net.Uri source = AppFiles.gameSource(this);
            if (source != null) {
                try {
                    gameFile = getContentResolver().openFileDescriptor(source, "r");
                } catch (Exception e) {
                    Log.e(TAG, "could not open the game file " + source, e);
                }
                // Not opened: fd -1, so the game says the file can't be opened rather than that no folder is set.
                Os.setenv("BASARA_DVD_IMAGE", "/proc/self/fd/" + (gameFile != null ? gameFile.getFd() : -1), true);
            }
            // Written by the Vulkan renderer when the GPU stops responding (GpuDrivers.checkGpuLost).
            Os.setenv("AURORA_GPU_LOST_FILE", GpuDrivers.gpuLostFile(this).getAbsolutePath(), true);
            if (logs != null) Os.setenv("BASARA_LOG_DIR", logs.getAbsolutePath(), true);
            // HD Textures keeps its own cache folder; without Custom Textures it is the only one indexed.
            final java.io.File hdCache = settings.hdTextures() != 0 ? AppFiles.hdCacheDir() : null;
            final java.io.File textures = settings.customTextures() ? AppFiles.textureDir() : hdCache;
            if (textures != null) Os.setenv("BASARA_TEXTURE_DIR", textures.getAbsolutePath(), true);
            // Only the mods ticked in Settings, in their order (TexturePacks).
            if (settings.customTextures() && textures != null) {
                Os.setenv("BASARA_TEXTURE_PACKS",
                        TexturePacks.envValue(TexturePacks.load(settings, textures), textures), true);
            }
            if (hdCache != null) Os.setenv("BASARA_HD_CACHE_DIR", hdCache.getAbsolutePath(), true);
            // Sustained Performance: the system holds steady clocks, so the game thread is not pinned to
            // the fastest cores and no ADPF boost is asked for (runtime/src/cpu_boost.cpp). env.txt wins.
            appliedSustained = sustainedMode();
            if (appliedSustained) Os.setenv("BASARA_CPU_BOOST", "0", false);
                Os.setenv("BASARA_CPU_HELPERS", "fast", false);
            if (settings.recordingAudio()) Os.setenv("BASARA_AUDIO_RECORDABLE", "1", false);
            // Low RAM (ARMSX2 caps its texture pools on weak phones): a smaller cache for replaced textures.
            final long ram = DeviceProfile.ramMb(this);
            if (ram > 0 && ram < 4608) Os.setenv("AURORA_REPLACEMENT_CACHE_MB", settings.hdTextures() != 0 ? "192" : "512", false);
            // Upload Memory: Auto leaves the choice to the start-up measurement (vk_webgpu.cpp).
            if (settings.uploadMemory() != 0) {
                Os.setenv("AURORA_VK_CACHED_STAGING", settings.uploadMemory() == 2 ? "1" : "0", false);
            }
            // WebGPU 1.0 (aurora10/) was never made safe for the 1.2 GX overlap (frame sync 0: the game's
            // next frame runs while the GX thread still draws the last), so it keeps the 1.1 sync: the
            // frame waits for the GX thread at its top. env.txt wins.
            if (GpuDrivers.useRenderer10(this, settings)) Os.setenv("BASARA_GX_FRAME_SYNC", "2", false);
            try {
                // For the log's device line: the screen's refresh rate.
                Os.setenv("BASARA_DISPLAY_HZ", String.valueOf(Math.round((android.os.Build.VERSION.SDK_INT >= 30 ? getDisplay()
                        : getWindowManager().getDefaultDisplay()).getRefreshRate())), true);
            } catch (Exception ignored) {
            }
        } catch (Exception e) {
            Log.e(TAG, "could not prepare the game's files", e);
            Toast.makeText(this, "Could not prepare the game's files: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
        super.onCreate(savedInstanceState);
        applyDisplayResolution();
        nativeReady = !SDLActivity.mBrokenLibraries;
        if (nativeReady) {
            nativeSetDeviceName(deviceName());
        }
        // LAN rooms are found with broadcasts, which many Wi-Fi drivers drop unless an app holds this.
        final WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wifi != null) {
            multicastLock = wifi.createMulticastLock("basara-lan");
            multicastLock.setReferenceCounted(false);
            multicastLock.acquire();
        }

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        touch = new TouchControlsView(this);
        touch.setListener(this);
        applyControlSettings();
        addContentView(touch, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        motion = new MotionInput(this, this);
        layoutEditor = new LayoutEditor(this, touch, new LayoutEditor.Host() {
            @Override public AppSettings settings() { return settings; }
            @Override public void onEditorClosed() { resumeFromMenu(); }
        });
    }

    /** The touch layout editor, over the paused game (it carries on when the editor closes). */
    private void startLayoutEdit() {
        touch.release();
        menuOpen = true;
        setMenuPause(true);
        layoutEditor.open();
    }

    /**
     * SDL is linked into libmain.so, so that one library is all there is to load. Renderer WebGPU 1.0
     * loads libmain10.so instead: the same runtime built with the 1.0 renderer (aurora10/).
     */
    @Override
    protected String[] getLibraries() {
        final AppSettings s = settings != null ? settings : new AppSettings(this);
        final String lib = GpuDrivers.useRenderer10(this, s) ? "main10" : "main";
        Log.i(TAG, "loading lib" + lib + ".so");
        return new String[] {lib};
    }

    // The game's menus take taps and the remote hides while they are up; it comes back a moment
    // after they go, so it doesn't blink while one menu screen hands over to the next.
    private final android.os.Handler menuPoll = new android.os.Handler(android.os.Looper.getMainLooper());
    private long lastTapScreen;
    private long lastBattery = -100000;
    private boolean menuOpen;
    private LayoutEditor layoutEditor;

    private void setMenuPause(boolean paused) {
        if (!nativeReady) return;
        try {
            nativeSetMenuPause(paused);
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "menu pause bridge missing from libmain.so", e);
        }
    }

    private void pushBattery() {
        final Intent battery = registerReceiver(null,
                new android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (battery == null) return;
        final int level = battery.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1);
        final int scale = battery.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100);
        final int temp = battery.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, -10);
        try {
            nativeSetBattery(level >= 0 && scale > 0 ? level * 100 / scale : -1, temp / 10f);
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "battery bridge missing from libmain.so", e);
        }
    }

    private final Runnable pollMenu = new Runnable() {
        @Override
        public void run() {
            final long now = android.os.SystemClock.uptimeMillis();
            if (nativeReady) {
                // The game's settings window: keep its choices, the config is rewritten at each start.
                final float[] changed = nativeTakeSettings();
                if (changed != null) {
                    settings.setResolutionScale(changed[0]);
                    settings.setShowFps(changed[1] > 0.5f);
                    if (changed.length > 2) settings.setAspectMode(Math.round(changed[2]));
                    if (changed.length > 3) settings.setSpinningCoins(changed[3] > 0.5f);
                    if (changed.length > 4) settings.setFrameGraph(changed[4] > 0.5f);
                    if (changed.length > 5) settings.setVSync(changed[5] > 0.5f);
                }
            }
            if (nativeReady && now - lastBattery > 10000) {
                lastBattery = now;
                pushBattery();
            }
            if (nativeReady && nativeTapScreen()) {
                lastTapScreen = now;
                touch.setMenuMode(true);
            } else if (now - lastTapScreen > 600) {
                touch.setMenuMode(false);
            }
            menuPoll.postDelayed(this, 100);
        }
    };

    @Override
    public void onTap(float x, float y) {
        if (nativeReady) nativeTap(x, y);
    }

    @Override
    public void onPointer(float x, float y, boolean held) {
        if (nativeReady) nativePointer(x, y, held);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (nativeReady) {
            nativeSetBackground(false);  // the game carries on where it stopped
            if (menuOpen) setMenuPause(true);  // ...unless its menu is still open
            // Back from the start screen (an update check): read the settings file again, it may
            // have been written from the other process.
            settings = new AppSettings(this);
            applyNativeSettings();
        }
        applyControlSettings();
        getWindow().setSustainedPerformanceMode(sustainedMode());
        menuPoll.removeCallbacks(pollMenu);
        menuPoll.post(pollMenu);
        hideSystemBars();
        if (motion != null) {
            motion.start(settings.motionTilt(), settings.motionShake());
        }
    }

    @Override
    protected void onPause() {
        if (nativeReady) {
            // Before SDL's pause stops the game thread: let the game write its backup Quick Save (in
            // case Android ends the app while it is away) and pause itself. Bounded: a save that takes
            // longer is simply not published (the NAND only commits closed files).
            nativeSetBackground(true);
            final long until = android.os.SystemClock.uptimeMillis() + SUSPEND_WAIT_MS;
            while (!nativeGamePaused() && android.os.SystemClock.uptimeMillis() < until) {
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    break;
                }
            }
        }
        // The save the game has written so far, copied out of app storage (an uninstall deletes it).
        final android.content.Context app = getApplicationContext();
        new Thread(() -> SaveData.autoBackup(app), "save-copy").start();
        menuPoll.removeCallbacks(pollMenu);
        if (motion != null) motion.stop();
        if (touch != null) touch.release();
        super.onPause();
    }

    /** Sustained Performance as the CPU boost has it now (set at start by BASARA_CPU_BOOST). */
    private boolean appliedSustained;
    /** Read mode's game file, open for as long as this process (the game) lives. */
    private static android.os.ParcelFileDescriptor gameFile;

    private boolean sustainedMode() {
        return settings.sustainedPerformance() && AppSettings.sustainedSupported(this);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            hideSystemBars();
        } else if (touch != null) {
            touch.release();  // a dialog or the shade took the screen: let go of every button
        }
    }

    @Override
    public void onBackPressed() {
        onMenu();
    }

    /** SDL takes the Back key as keyboard input before onBackPressed: the Back menu gets it first. */
    @Override
    public boolean dispatchKeyEvent(android.view.KeyEvent event) {
        if (event.getKeyCode() == android.view.KeyEvent.KEYCODE_BACK) {
            if (event.getAction() == android.view.KeyEvent.ACTION_UP && !event.isCanceled()) {
                onMenu();
            }
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    /**
     * What this process cannot do itself (LauncherActivity.ACTION_*): a new game file, an update check,
     * a save import. The start screen does it and opens the game again; `file` goes with an import.
     */
    void toLauncher(String action, String file) {
        final Intent intent = new Intent(this, LauncherActivity.class).putExtra(LauncherActivity.EXTRA_ACTION, action);
        if (file != null) intent.putExtra(LauncherActivity.EXTRA_FILE, file);
        startActivity(intent);
    }

    /** "Restart": the game starts again in a fresh process, so settings read at start apply. */
    private void confirmRestart(Runnable cancelled) {
        // What Restart will change: the restart-only settings set since the game started.
        final StringBuilder text = new StringBuilder(Tr.t("Restart the game? Progress since your last save is lost."));
        final java.util.Map<String, String> pending = SettingsMenu.pendingRestarts();
        if (!pending.isEmpty()) {
            text.append("\n\n").append(Tr.t("Will change:"));
            for (java.util.Map.Entry<String, String> e : pending.entrySet()) {
                text.append("\n• ").append(e.getKey()).append(": ").append(e.getValue());
            }
        }
        new Sheet(this, Tr.t("Restart"), text.toString())
                .action(Tr.t("Restart"), null, true, false, this::restartGame)
                .action(Tr.t("Cancel"), null, false, false, cancelled)
                .onCancel(cancelled)
                .show();
    }

    /** "Quit": asked first, like Restart, since progress since the last save goes. */
    private void confirmQuit() {
        new Sheet(this, Tr.t("Quit"), Tr.t("Quit the game? Progress since your last save is lost."))
                .action(Tr.t("Quit"), null, true, false, this::quitApp)
                .action(Tr.t("Cancel"), null, false, false, null)
                .show();
    }

    void restartGame() {
        if (nativeReady && nativeSessionActive()) nativePrepareToLeave();  // the host keeps the session's progress
        restartForSession();
    }

    /** Settings > System in the game: the game file, updates and the report tools. */
    private void systemRows(SettingsMenu rows) {
        final boolean changed = AppFiles.discDifferences(this) != null;
        rows.valueRow(Tr.t("Game File"), changed ? Tr.t("RBSJ08, changed") : "RBSJ08", Tr.t("Change"), () ->
                new Sheet(this, Tr.t("Choose another game file?"), Tr.t("The game closes without saving; it "
                        + "starts again from the new file."))
                        .action(Tr.t("Choose"), null, true, false, () -> toLauncher(LauncherActivity.ACTION_DISC, null))
                        .action(Tr.t("Cancel"), null, false, false, null)
                        .show());
        rows.valueRow(Tr.t("Check for Updates"), Updater.displayVersion(this), Tr.t("Check"),
                () -> toLauncher(LauncherActivity.ACTION_UPDATE, null));
        rows.toggleRow(Tr.t("Auto Update"), BuildConfig.ONLINE_UPDATES ? Tr.t("Update on launch")
                        : Tr.t("Updates are sent by hand for now."),
                settings.autoUpdate(), BuildConfig.ONLINE_UPDATES, on -> settings.setAutoUpdate(on));
        rows.actionRow(Tr.t("What's New"), Tr.t("What each version brought."), Tr.t("Open"),
                () -> ReleaseNotes.showAll(this));
        rows.reportRows(true);
        rows.valueRow(Tr.t("Credits"), "xu@shiinobu · hikki@datfooldive", Tr.t("Open"), () ->
                new Sheet(this, Tr.t("Credits"), null)
                        .paragraph(Tr.t("Port by xu@shiinobu, continuing the work of WitherBlitz with the help of "
                                + "WiiCompiled's code."))
                        .paragraph(Tr.t("Menu and touch button design, and the Unlock All idea, by hikki@datfooldive."))
                        .paragraph(Tr.t("Based on NSMB-Wii-Multiplayer-Recompilation and WiiCompiled (GPLv3). "
                                + "Renderer techniques studied from Dolphin (GPLv2+)."))
                        .note(Tr.t("Touch control icons from game-icons.net by Lorc, Delapouite and contributors "
                                + "(CC BY 3.0)."))
                        .action(Tr.t("Close"), null, true, false, null)
                        .show());
        rows.actionRow(Tr.t("Project page on GitHub"), LauncherActivity.PROJECT_URL.replace("https://", ""),
                Tr.t("Open"), () -> {
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(LauncherActivity.PROJECT_URL)));
                    } catch (android.content.ActivityNotFoundException ignored) {
                        // no browser
                    }
                });
    }

    /**
     * "Quit Game": closes the whole app, not just the game. The task (launcher included) leaves the
     * recent apps, the launcher's process ends, and onDestroy ends this one.
     */
    private void quitApp() {
        final android.app.ActivityManager am = getSystemService(android.app.ActivityManager.class);
        // The launcher can sit in a task of its own: every task of the app goes, or it would come back.
        if (am != null) {
            for (android.app.ActivityManager.AppTask task : am.getAppTasks()) {
                task.finishAndRemoveTask();
            }
        }
        if (am != null && am.getRunningAppProcesses() != null) {
            for (android.app.ActivityManager.RunningAppProcessInfo info : am.getRunningAppProcesses()) {
                if (info.pid != Process.myPid() && info.uid == Process.myUid()
                        && info.processName.equals(getPackageName())) {
                    Process.killProcess(info.pid);
                }
            }
        }
        finishAndRemoveTask();
    }

    /** "Leave LAN Game": keep the host's progress, then start the game again on its own. */
    private void leaveSession() {
        nativePrepareToLeave();
        restartForSession();  // with the session file gone, the next start is an ordinary one
    }

    /** The name the user gave the phone, else its model. */
    private String deviceName() {
        String name = Settings.Global.getString(getContentResolver(), Settings.Global.DEVICE_NAME);
        if (name == null || name.trim().isEmpty()) {
            name = Build.MODEL;
        }
        return name == null ? "Phone" : name.trim();
    }

    /**
     * Network play: the runtime has written the session (netplay_start.cpp); start the game again in a
     * fresh process. The launcher waits for this one to end, then opens the game, which finds the
     * session and boots into it. Called from the game thread.
     */
    void restartForSession() {
        runOnUiThread(() -> {
            final Intent intent = new Intent(this, LauncherActivity.class)
                    .putExtra(LauncherActivity.EXTRA_RESTART_GAME, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(intent);
            finish();
        });
    }

    @Override
    protected void onDestroy() {
        if (multicastLock != null && multicastLock.isHeld()) {
            multicastLock.release();
        }
        super.onDestroy();
        if (isFinishing()) {
            GpuDrivers.endedOnPurpose(this);
            CrashReport.endedOnPurpose(this, Process.myPid());
            // The runtime starts once per process; the next start must get a fresh one.
            Process.killProcess(Process.myPid());
        }
    }

    // --- input ---------------------------------------------------------------------------------

    @Override
    public void onTouchInput(int buttons) {
        touchButtons = buttons;
        pushInput();
    }

    @Override
    public void onTouchSticks(float leftX, float leftY, float rightX, float rightY) {
        if (!nativeReady) return;
        try {
            nativeSetTouchSticks(leftX, leftY, rightX, rightY);
        } catch (UnsatisfiedLinkError e) {
            nativeReady = false;
            Log.e(TAG, "touch input bridge missing from libmain.so", e);
        }
    }

    @Override
    public void onMotion(float tilt, boolean shake) {
        motionTilt = tilt;
        motionShake = shake;
        pushInput();
    }

    private void pushInput() {
        if (!nativeReady) return;
        try {
            nativeSetTouchState(touchButtons, motionShake, motionTilt);
        } catch (UnsatisfiedLinkError e) {
            nativeReady = false;
            Log.e(TAG, "touch input bridge missing from libmain.so", e);
        }
    }

    private void applyControlSettings() {
        touch.setControlsVisible(settings.touchControls());
        touch.setHaptics(settings.haptics());
        touch.setTouchCamera(settings.touchCamera());
        touch.setStyle(TouchControlsView.STYLE_MODERN);  // one look only, from PR #3
        touch.setInvertCamera(settings.invertCamera());
        if (layoutEditor != null && layoutEditor.isOpen()) return;  // the editor holds unsaved changes
        touch.setOpacity(settings.touchOpacity());
        touch.setFixedStick(settings.fixedStick());
        touch.setLayout(settings.touchLayout());
    }

    /** What the game can change while it runs: picture, upscaler, frame pacing, overlay, cheats. */
    private void applyNativeSettings() {
        if (!nativeReady) return;
        nativeApplyDisplaySettings(settings.aspectMode(), settings.resolutionScale(), settings.showFps(),
                settings.dynamicResolution(), settings.overlayFlags(), settings.frameGraph(),
                settings.thermalControl(), settings.textureFilter());
        nativeApplyCheats(settings.cheatMoney(), settings.cheatMoneyAmount(), settings.cheatMoneySeq(),
                settings.cheatInfiniteHp(), settings.cheatInfiniteBasara(), settings.cheatUnlockAll());
        try {
            nativeSetCameraDistance(settings.cameraDistanceFactor());
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "camera distance bridge missing from libmain.so", e);
        }
        try {
            nativeSetDisableFog(settings.disableFog());
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "fog bridge missing from libmain.so", e);
        }
        try {
            nativeSetReduceEffects(settings.reduceEffects());
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "effects bridge missing from libmain.so", e);
        }
        try {
            nativeSetGamepadMap(settings.gamepadMap());
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "gamepad map bridge missing from libmain.so", e);
        }
        try {
            nativeApplyLiveSettings(settings.fsr(), settings.fpsLock30() ? 2 : settings.frameskip() ? 1 : 0);
            // Only a change: the start's own choice (or env.txt's BASARA_CPU_BOOST) stays otherwise.
            if (sustainedMode() != appliedSustained) {
                appliedSustained = sustainedMode();
                nativeSetSustained(appliedSustained);
            }
            nativeSetVSync(settings.vsync());
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "live settings bridge missing from libmain.so", e);
        }
    }

    /** A change in the in-game settings: everything that applies while playing, at once. */
    private void applyLiveSettings() {
        settings = new AppSettings(this);
        applyNativeSettings();
        applyDisplayResolution();
        getWindow().setSustainedPerformanceMode(sustainedMode());
        applyControlSettings();
        if (motion != null) {
            motion.stop();
            motion.start(settings.motionTilt(), settings.motionShake());
        }
    }

    private void hideSystemBars() {
        SystemBars.hide(getWindow());
    }

    // --- menu ----------------------------------------------------------------------------------

    /**
     * The in-game menu, from the phone's Back gesture or the gear on the touch controls: the game
     * pauses while it is open.
     */
    @Override
    public void onMenu() {
        if (layoutEditor != null && layoutEditor.isOpen()) {
            layoutEditor.save();  // Back while editing: keep what was arranged
            return;
        }
        touch.release();
        final boolean inSession = nativeReady && nativeSessionActive();
        // The game stands still while the menu is open (no backup save: it isn't leaving the screen).
        menuOpen = true;
        setMenuPause(true);
        new PauseMenu(inSession).show();
    }

    /**
     * The in-game menu, as an ImGui window over the dimmed game (the look hikki made for DefJam): a
     * title bar, the sections as a row of tabs, their settings, and the actions in the footer. What the
     * game can change while playing applies at once; settings read only at start carry a "*".
     */
    private final class PauseMenu {
        private final android.app.Dialog dialog;
        private final android.widget.LinearLayout tabs;
        private final android.widget.ScrollView rowsScroll;
        private final SettingsMenu menu;
        private final int[] sections = SettingsMenu.gameSections();
        private android.widget.TextView note;
        private android.widget.TextView restartButton;
        private android.graphics.drawable.Drawable restartLook;
        private android.content.res.ColorStateList restartText;

        /** The footer note, or the settings waiting for Restart, with Restart lit in amber. */
        private void showPending() {
            if (note == null || restartButton == null) return;
            final java.util.Map<String, String> pending = SettingsMenu.pendingRestarts();
            if (pending.isEmpty()) {
                note.setText(Tr.t("Changes are saved as you make them. ⟳ Restart = applies after Restart."));
                note.setTextColor(Ui.MUTED);
                note.setBackground(null);
                note.setPadding(0, 0, 0, 0);
                restartButton.setBackground(restartLook);
                restartButton.setTextColor(restartText);
                return;
            }
            note.setText("⟳ " + (pending.size() == 1 ? Tr.t("1 change waiting for Restart")
                    : pending.size() + Tr.t(" changes waiting for Restart")));
            note.setTextColor(Ui.PENDING);
            note.setBackground(Ui.box(GameActivity.this, Ui.PENDING_BG, Ui.PENDING, 0));
            note.setPadding(dp(6), dp(2), dp(6), dp(2));
            restartButton.setBackground(Ui.box(GameActivity.this, Ui.PENDING, Ui.PENDING, 0));
            restartButton.setTextColor(0xFF1B1300);
        }

        PauseMenu(boolean inSession) {
            final GameActivity a = GameActivity.this;
            final android.widget.LinearLayout window = new android.widget.LinearLayout(a);
            window.setOrientation(android.widget.LinearLayout.VERTICAL);
            window.setBackground(Ui.box(a, Ui.WINDOW, Ui.BORDER, 0));
            window.setPadding(1, 1, 1, 1);
            window.addView(Ui.titleBar(a, Tr.t("Settings"),
                    "port by xu@shiinobu · designed by hikki@datfooldive"), Ui.matchWidth(0));

            final android.widget.LinearLayout root = new android.widget.LinearLayout(a);
            root.setOrientation(android.widget.LinearLayout.VERTICAL);
            root.setPadding(dp(10), dp(6), dp(10), dp(8));
            window.addView(root, new android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

            tabs = new android.widget.LinearLayout(a);
            root.addView(tabs, Ui.matchWidth(0));
            root.addView(Ui.line(a));

            final android.widget.LinearLayout rows = new android.widget.LinearLayout(a);
            rows.setOrientation(android.widget.LinearLayout.VERTICAL);
            rowsScroll = new android.widget.ScrollView(a);
            // ImGui's scrollbar beside the rows, not over their buttons.
            rowsScroll.setScrollbarFadingEnabled(false);
            rowsScroll.setScrollBarStyle(android.view.View.SCROLLBARS_OUTSIDE_OVERLAY);
            rowsScroll.setPadding(0, 0, dp(8), 0);
            rowsScroll.addView(rows);
            root.addView(rowsScroll, new android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
            root.addView(Ui.line(a));

            // The footer: what the "⟳ Restart" tag means, or (once one changed) the settings that wait
            // for Restart in amber; then the actions with Resume last (the bright one).
            final android.widget.LinearLayout footer = new android.widget.LinearLayout(a);
            footer.setGravity(android.view.Gravity.CENTER_VERTICAL);
            note = Ui.text(a, "", 11, Ui.MUTED);
            note.setMaxLines(2);
            note.setEllipsize(android.text.TextUtils.TruncateAt.END);
            footer.addView(note, new android.widget.LinearLayout.LayoutParams(0,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            footer.addView(action(Tr.t("Layout"), false, v -> {
                close();
                startLayoutEdit();
            }), actionParams());
            restartButton = action(Tr.t("Restart"), false, v -> confirmRestart(null));
            restartLook = restartButton.getBackground();
            restartText = restartButton.getTextColors();
            footer.addView(restartButton, actionParams());
            if (inSession) {
                footer.addView(action(Tr.t("Leave LAN"), false, v -> {
                    close();
                    leaveSession();
                }), actionParams());
            }
            footer.addView(action(Tr.t("Quit"), false, v -> confirmQuit()), actionParams());
            footer.addView(action(Tr.t("Resume"), true, v -> close()), actionParams());
            root.addView(footer, Ui.matchWidth(dp(2)));

            menu = new SettingsMenu(a, rows, true, GameActivity.this::applyLiveSettings);
            menu.pendingChanged = this::showPending;
            showPending();
            menu.systemRows = GameActivity.this::systemRows;
            // A new language: the window is made again around the same tab (the game stays paused).
            menu.languageChanged = () -> tabs.post(() -> {
                final int open = menu.section();
                PauseMenu.this.dialog.dismiss();
                final PauseMenu again = new PauseMenu(inSession);
                again.select(open);
                again.show();
            });

            // The game shows dimmed around the window, as DefJam's panel (most of the screen's height).
            final android.widget.FrameLayout backdrop = new android.widget.FrameLayout(a);
            backdrop.setBackgroundColor(android.graphics.Color.argb(166, 0, 0, 0));
            final android.util.DisplayMetrics screen = getResources().getDisplayMetrics();
            final int width = Math.min(dp(800), Math.round(screen.widthPixels * 0.84f));  // wide enough for eight tabs at full size
            final int height = Math.round(screen.heightPixels * 0.9f);
            backdrop.addView(window, new android.widget.FrameLayout.LayoutParams(width, height,
                    android.view.Gravity.CENTER));
            backdrop.setOnClickListener(v -> close());  // a tap beside the window resumes, as DefJam's
            window.setClickable(true);

            dialog = new android.app.Dialog(a, android.R.style.Theme_DeviceDefault_NoActionBar_Fullscreen);
            dialog.setContentView(backdrop);
            final android.view.Window w = dialog.getWindow();
            if (w != null) {
                w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
                w.setLayout(android.view.WindowManager.LayoutParams.MATCH_PARENT,
                        android.view.WindowManager.LayoutParams.MATCH_PARENT);
            }
            dialog.setOnShowListener(d -> SystemBars.hide(w));
            dialog.setOnCancelListener(d -> resumeFromMenu());
            dialog.setOnDismissListener(d -> hideSystemBars());
            select(SettingsMenu.DISPLAY);
        }

        void show() { dialog.show(); }

        private void close() {
            dialog.dismiss();
            resumeFromMenu();
        }

        /** A footer button: ImGui's, the primary one (Resume) in the brighter blue. */
        private android.widget.TextView action(String label, boolean primary, android.view.View.OnClickListener onClick) {
            final android.widget.TextView button = Ui.chip(GameActivity.this,
                    label.toUpperCase(java.util.Locale.ROOT), primary, true, onClick);
            button.setPadding(dp(8), 0, dp(8), 0);
            button.setMinWidth(dp(78));
            button.setContentDescription(label);
            return button;
        }

        private android.widget.LinearLayout.LayoutParams actionParams() {
            // At least 78 dp, wider when the label needs it ("MULAI ULANG"): the note beside them shrinks.
            final android.widget.LinearLayout.LayoutParams params = new android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, dp(30));
            params.leftMargin = dp(4);
            return params;
        }

        /** The tabs, ImGui buttons sharing the width: the open one in the brighter blue. */
        private void select(int section) {
            menu.show(section);
            rowsScroll.scrollTo(0, 0);
            tabs.removeAllViews();
            for (int id : sections) {
                final android.widget.TextView tab = Ui.chip(GameActivity.this,
                        SettingsMenu.name(id).toUpperCase(java.util.Locale.ROOT), id == section,
                        !SettingsMenu.disabled(id), v -> select(id));
                tab.setPadding(dp(2), 0, dp(2), 0);
                // 11 sp, smaller once laid out when the name is wider than its tab (eight tabs share a row).
                tab.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11);
                tab.post(() -> {
                    final float room = tab.getWidth() - tab.getPaddingLeft() - tab.getPaddingRight();
                    float sp = 11;
                    while (sp > 7 && tab.getPaint().measureText(tab.getText().toString()) > room) {
                        sp -= 0.5f;
                        tab.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, sp);
                    }
                });
                final android.widget.LinearLayout.LayoutParams params = new android.widget.LinearLayout.LayoutParams(
                        0, dp(28), 1f);
                if (tabs.getChildCount() > 0) params.leftMargin = dp(2);
                tabs.addView(tab, params);
            }
        }
    }

    private void resumeFromMenu() {
        menuOpen = false;
        setMenuPause(false);
    }

    private android.view.View line() {
        final android.view.View view = new android.view.View(this);
        view.setBackgroundColor(Ui.BORDER);
        return view;
    }

    private int dp(int value) {
        return Ui.dp(this, value);
    }
}
