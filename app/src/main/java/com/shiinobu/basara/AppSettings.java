package com.shiinobu.basara;

import android.content.Context;
import android.content.SharedPreferences;

/** Player settings, kept in one SharedPreferences file shared by the launcher and the game. */
final class AppSettings {
    static final int ASPECT_4_3 = 0;
    static final int ASPECT_16_9 = 1;
    static final int ASPECT_FILL = 2;

    /** GPU driver workarounds: picked from the phone's GPU, or forced (AuroraGpuCompat). */
    static final int GPU_COMPAT_AUTO = 0;
    static final int GPU_COMPAT_ON = 1;
    static final int GPU_COMPAT_OFF = 2;

    /** Render scales offered in the menu; 1x renders 480 lines, whatever the aspect; 0 the screen's own. */
    static final float[] RESOLUTION_SCALES = {0f, 0.5f, 0.75f, 1f, 1.5f, 1.75f, 1.8f, 2f, 2.5f, 3f, 4f};

    private static final String FILE = "settings";
    private final SharedPreferences prefs;

    AppSettings(Context context) {
        // Both processes (launcher and :game) read it. Writes use commit(): the game process is
        // ended right after its menu closes, which would drop a pending apply().
        // MODE_MULTI_PROCESS: each new AppSettings rereads the file when the other process changed it
        // (the :game process otherwise keeps its first copy, and Continue missed launcher changes).
        //noinspection deprecation
        prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE | Context.MODE_MULTI_PROCESS);
        // Once: the FPS / performance overlay costs frame time on slow phones, so installs that had
        // it on start with it off again (it can still be turned back on in Display settings).
        if (!prefs.getBoolean("show_fps_reset_1", false)) {
            prefs.edit().putBoolean("show_fps", false).putBoolean("show_fps_reset_1", true).commit();
        }
    }

    /** DeviceProfile has not run yet on this install. */
    boolean needsDeviceProfile() { return !prefs.getBoolean("device_profile_1", false); }

    /** Thermal Control was on by default and still misjudges: turn it off once on every install. */
    void resetThermalOnce() {
        if (prefs.getBoolean("thermal_off_1", false)) return;
        prefs.edit().putBoolean("thermal_off_1", true).putBoolean("thermal_control", false).commit();
    }

    // --- cheats (runtime/src/cheats.cpp, [cheats] in Config.toml) ---------------------------------
    static final int MONEY_OFF = 0, MONEY_INFINITE = 1, MONEY_ONCE = 2;
    static final int MAX_MONEY = 9_999_999;

    int cheatMoney() { return !BuildConfig.CHEATS ? MONEY_OFF : Math.max(0, Math.min(2, prefs.getInt("cheat_money", MONEY_OFF))); }
    void setCheatMoney(int mode) { prefs.edit().putInt("cheat_money", mode).commit(); }
    int cheatMoneyAmount() { return Math.max(0, Math.min(MAX_MONEY, prefs.getInt("cheat_money_amount", MAX_MONEY))); }
    void setCheatMoneyAmount(int amount) {
        prefs.edit().putInt("cheat_money_amount", Math.max(0, Math.min(MAX_MONEY, amount))).commit();
    }
    /** Bumped by "Set Now": the game sets the money once when it sees a new number. */
    int cheatMoneySeq() { return prefs.getInt("cheat_money_seq", 0); }
    void bumpCheatMoneySeq() { prefs.edit().putInt("cheat_money_seq", cheatMoneySeq() + 1).commit(); }
    boolean cheatInfiniteHp() { return BuildConfig.CHEATS && prefs.getBoolean("cheat_infinite_hp", false); }
    void setCheatInfiniteHp(boolean on) { prefs.edit().putBoolean("cheat_infinite_hp", on).commit(); }
    boolean cheatInfiniteBasara() { return BuildConfig.CHEATS && prefs.getBoolean("cheat_infinite_basara", false); }
    void setCheatInfiniteBasara(boolean on) { prefs.edit().putBoolean("cheat_infinite_basara", on).commit(); }
    boolean cheatUnlockAll() { return BuildConfig.CHEATS && prefs.getBoolean("cheat_unlock_all", false); }
    void setCheatUnlockAll(boolean on) { prefs.edit().putBoolean("cheat_unlock_all", on).commit(); }
    /** Any cheat on (the launcher's IN USE panel). */
    boolean anyCheat() {
        return cheatMoney() == MONEY_INFINITE || cheatInfiniteHp() || cheatInfiniteBasara() || cheatUnlockAll();
    }

    /** GX Thread was on by default for slower phones and flickers: turn it off once on every install. */
    void resetGxThreadOnce() {
        if (prefs.getBoolean("gx_thread_off_1", false)) return;
        prefs.edit().putBoolean("gx_thread_off_1", true).putBoolean("gx_thread", false).commit();
    }

    /**
     * 1.2 turned GX Thread on for CPU-bound phones; battles then ran less evenly (frame jitter 5.5 ms
     * against 3.1 ms without it, Redmi Adreno 618, 2026-11-08) and players found 1.0 smoother. Off
     * again once on every install; the player can still turn it on in Performance.
     */
    void gxThreadOffAgainOnce() {
        if (prefs.getBoolean("gx_thread_off_2", false)) return;
        prefs.edit().putBoolean("gx_thread_off_2", true).putBoolean("gx_thread", false).commit();
    }

    /** The device profile's picks, for the settings the player never set; runs once. */
    void applyDeviceProfile(float scale, boolean frameskip, boolean gxThread) {
        final SharedPreferences.Editor e = prefs.edit().putBoolean("device_profile_1", true)
                .putBoolean("gx_thread_off_1", true).putBoolean("gx_thread_off_2", true)
                .putBoolean("thermal_off_1", true);
        if (!prefs.contains("resolution_scale")) e.putFloat("resolution_scale", scale);
        if (!prefs.contains("frameskip")) e.putBoolean("frameskip", frameskip);
        if (!prefs.contains("gx_thread")) e.putBoolean("gx_thread", gxThread);
        e.commit();
    }

    /** The second device profile (Low-End preset on a weak phone) has not run yet on this install. */
    boolean needsLowEndProfile() { return !prefs.getBoolean("device_profile_2", false); }
    void markLowEndProfile() { prefs.edit().putBoolean("device_profile_2", true).commit(); }
    boolean has(String key) { return prefs.contains(key); }

    /** GL_RENDERER seen by the launcher's device profile, for the Low-End preset in either process. */
    String gpuName() { return prefs.getString("gpu_name", ""); }
    void setGpuName(String name) { prefs.edit().putString("gpu_name", name == null ? "" : name).commit(); }

    // --- Low-End preset -----------------------------------------------------------------------------
    /** Every setting the Low-End preset may change; what they were before is kept for Undo. */
    static final String[] LOW_END_KEYS = {"display_lines", "resolution_scale", "texture_filter", "fsr_on",
        "frameskip", "dynamic_resolution", "hd_textures", "show_fps", "overlay_items", "frame_graph", "fps_lock_30"};
    /** Preset keys the player may still change while it is on. */
    private static final java.util.Set<String> LOW_END_FREE = new java.util.HashSet<>(java.util.Arrays.asList(
            "resolution_scale", "show_fps", "overlay_items", "frame_graph", "fps_lock_30"));

    boolean lowEndApplied() { return prefs.contains("low_end_backup"); }

    /** 0 off, 1 Low-End, 2 Ultra Low (DeviceProfile.lowEndValues). */
    int lowEndLevel() { return lowEndApplied() ? Math.max(1, Math.min(2, prefs.getInt("low_end_level", 1))) : 0; }

    /**
     * A setting the Low-End preset holds while it is on: it still has the preset's value, so it cannot be
     * changed by hand (a key the preset left alone, like V-Sync the player had set, stays free). Render
     * Resolution and the performance overlay are never held (LOW_END_FREE).
     */
    boolean lowEndHolds(String key) {
        if (!lowEndApplied() || LOW_END_FREE.contains(key)) return false;
        final Object want = DeviceProfile.lowEndValues(gpuName(), lowEndLevel()).get(key);
        return want != null && want.equals(prefs.getAll().get(key));
    }

    /** Saves what the LOW_END_KEYS hold now (a missing key stays missing on Undo), then writes `values`. */
    void applyLowEnd(java.util.Map<String, Object> values) { applyLowEnd(values, 1); }

    void applyLowEnd(java.util.Map<String, Object> values, int level) {
        final java.util.Map<String, ?> all = prefs.getAll();
        final StringBuilder backup = new StringBuilder();
        for (String key : LOW_END_KEYS) {
            final Object v = all.get(key);
            final String type = v instanceof Boolean ? "b" : v instanceof Integer ? "i" : v instanceof Float ? "f" : "-";
            backup.append(key).append('=').append(type).append(':').append(v == null ? "" : v).append('\n');
        }
        final SharedPreferences.Editor e = prefs.edit().putString("low_end_backup", backup.toString())
                .putInt("low_end_level", level);
        for (java.util.Map.Entry<String, Object> entry : values.entrySet()) {
            final Object v = entry.getValue();
            if (v instanceof Boolean) e.putBoolean(entry.getKey(), (Boolean) v);
            else if (v instanceof Integer) e.putInt(entry.getKey(), (Integer) v);
            else if (v instanceof Float) e.putFloat(entry.getKey(), (Float) v);
        }
        e.commit();
    }

    /**
     * A Low-End preset applied by an older build (its backup has no overlay_items) gets the overlay keys the
     * preset now sets, once; their old values join the backup so Undo still puts them back.
     */
    void upgradeLowEnd() {
        final String backup = prefs.getString("low_end_backup", null);
        if (backup == null || backup.contains("overlay_items=")) return;
        final java.util.Map<String, ?> all = prefs.getAll();
        final StringBuilder more = new StringBuilder(backup);
        if (more.length() > 0 && more.charAt(more.length() - 1) != '\n') more.append('\n');
        for (String key : new String[] {"overlay_items", "frame_graph"}) {
            final Object v = all.get(key);
            final String type = v instanceof Boolean ? "b" : v instanceof Integer ? "i" : "-";
            more.append(key).append('=').append(type).append(':').append(v == null ? "" : v).append('\n');
        }
        prefs.edit().putString("low_end_backup", more.toString())
                .putBoolean("show_fps", true).putInt("overlay_items", OVERLAY_FPS).putBoolean("frame_graph", false)
                .commit();
    }

    /** Puts back the settings from before the Low-End preset. */
    void undoLowEnd() {
        final String backup = prefs.getString("low_end_backup", null);
        if (backup == null) return;
        final SharedPreferences.Editor e = prefs.edit().remove("low_end_backup").remove("low_end_level");
        for (String line : backup.split("\n")) {
            final int eq = line.indexOf('='), colon = line.indexOf(':', eq + 1);
            if (eq <= 0 || colon < 0) continue;
            final String key = line.substring(0, eq), type = line.substring(eq + 1, colon), value = line.substring(colon + 1);
            try {
                switch (type) {
                    case "b": e.putBoolean(key, Boolean.parseBoolean(value)); break;
                    case "i": e.putInt(key, Integer.parseInt(value)); break;
                    case "f": e.putFloat(key, Float.parseFloat(value)); break;
                    default: e.remove(key); break;
                }
            } catch (NumberFormatException ignored) {
                e.remove(key);
            }
        }
        e.commit();
    }

    /**
     * Display Resolution: the short side of the surface the game draws into (0: the screen's own). The
     * compositor stretches it to the screen for free, so every full-screen pass gets cheaper.
     */
    static final int[] DISPLAY_LINES = {0, 960, 720, 480, 360};
    int displayLines() { return Math.max(0, prefs.getInt("display_lines", 0)); }
    void setDisplayLines(int lines) { prefs.edit().putInt("display_lines", lines).commit(); }

    /** Settings > Display > Camera Distance: how far the camera stays from what it looks at (cheats.cpp). */
    static final float[] CAMERA_DISTANCES = {1f, 1.5f, 2f};
    int cameraDistance() { return Math.max(0, Math.min(CAMERA_DISTANCES.length - 1, prefs.getInt("camera_distance", 0))); }
    void setCameraDistance(int which) { prefs.edit().putInt("camera_distance", which).commit(); }
    boolean disableFog() { return prefs.getBoolean("disable_fog", false); }
    void setDisableFog(boolean on) { prefs.edit().putBoolean("disable_fog", on).commit(); }
    boolean reduceEffects() { return prefs.getBoolean("reduce_effects", false); }
    void setReduceEffects(boolean on) { prefs.edit().putBoolean("reduce_effects", on).commit(); }
    float cameraDistanceFactor() { return CAMERA_DISTANCES[cameraDistance()]; }
    int aspectMode() { return prefs.getInt("aspect_mode", ASPECT_FILL); }
    void setAspectMode(int mode) { prefs.edit().putInt("aspect_mode", mode).commit(); }

    float resolutionScale() { return prefs.getFloat("resolution_scale", 1f); }
    void setResolutionScale(float scale) { prefs.edit().putFloat("resolution_scale", scale).commit(); }

    int gpuCompat() { return prefs.getInt("gpu_compat", GPU_COMPAT_AUTO); }
    void setGpuCompat(int mode) { prefs.edit().putInt("gpu_compat", mode).commit(); }

    /** Frameskip Auto: skip drawing a frame while the game runs behind (off by default). */
    boolean frameskip() { return prefs.getBoolean("frameskip", false); }
    void setFrameskip(boolean on) { prefs.edit().putBoolean("frameskip", on).commit(); }
    /** 30 FPS Lock: draw every other frame, game logic and audio still at full speed (off by default). */
    boolean fpsLock30() { return prefs.getBoolean("fps_lock_30", false); }
    void setFpsLock30(boolean on) { prefs.edit().putBoolean("fps_lock_30", on).commit(); }
    /**
     * Upscaling when the game renders below the screen's resolution (on by default): 0 FSR Light
     * (sharpen the stretched picture), 1 FSR Full (FSR1's edge-aware upscale, heavier on the GPU),
     * 2 SGSR (Qualcomm's single-pass edge-aware upscale).
     */
    boolean fsrOn() { return prefs.getBoolean("fsr_on", true); }
    void setFsrOn(boolean on) { prefs.edit().putBoolean("fsr_on", on).commit(); }
    boolean fsrFull() { return prefs.getBoolean("fsr_full", false); }
    void setFsrFull(boolean full) { prefs.edit().putBoolean("fsr_full", full).commit(); }
    int upscaler() {
        return Math.max(0, Math.min(2, prefs.getInt("upscaler", prefs.getBoolean("fsr_full", false) ? 1 : 0)));
    }
    void setUpscaler(int mode) { prefs.edit().putInt("upscaler", Math.max(0, Math.min(2, mode))).commit(); }
    /** The config value: 0 off, 1 FSR light, 2 FSR full, 3 SGSR. */
    int fsr() { return fsrOn() ? (upscaler() + 1) : 0; }
    /** 0 off, 1 FSR Light, 2 FSR Full, 3 SGSR (Upscaling on/off plus the upscaler). */
    void setFsr(int mode) {
        setFsrOn(mode > 0);
        if (mode > 0) setUpscaler(mode - 1);
    }

    /**
     * Texture Detail for the 3D world: 0 original (as the game asks), 1 sharp (default: the Wii's
     * mip level even below its resolution + 4x anisotropic), 2 sharpest (16x anisotropic).
     */
    int textureFilter() { return Math.max(0, Math.min(2, prefs.getInt("texture_filter", 0))); }
    void setTextureFilter(int mode) { prefs.edit().putInt("texture_filter", mode).commit(); }

    /** Auto Update: opening the app installs the latest GitHub release (off by default). */
    boolean autoUpdate() { return prefs.getBoolean("auto_update", false); }
    /** The newest version offered at start without Auto Update: each one is offered once. */
    String offeredUpdate() { return prefs.getString("offered_update", ""); }
    void setOfferedUpdate(String version) { prefs.edit().putString("offered_update", version).apply(); }
    void setAutoUpdate(boolean on) { prefs.edit().putBoolean("auto_update", on).commit(); }

    boolean showFps() { return prefs.getBoolean("show_fps", false); }
    void setShowFps(boolean show) { prefs.edit().putBoolean("show_fps", show).commit(); }
    boolean spinningCoins() { return prefs.getBoolean("spinning_coins", true); }
    void setSpinningCoins(boolean on) { prefs.edit().putBoolean("spinning_coins", on).commit(); }

    /** What the performance overlay shows: OVERLAY_* bits (the runtime's kOverlay* in settings_overlay.h). */
    static final int OVERLAY_FPS = 1, OVERLAY_CPU = 2, OVERLAY_GPU = 4, OVERLAY_BATTERY = 8, OVERLAY_TEMPS = 16,
            OVERLAY_DETAILS = 32, OVERLAY_DEFAULT = 31;
    /** Not an item: the Minimal overlay style, sent to the runtime in the same bits (kOverlayMinimal). */
    static final int OVERLAY_MINIMAL = 64;
    int overlayItems() { return prefs.getInt("overlay_items", OVERLAY_DEFAULT); }
    void setOverlayItems(int items) { prefs.edit().putInt("overlay_items", items).commit(); }
    /** Settings > Display > Overlay Style: 0 = Bar (top centre), 1 = Minimal (top left, no bar). */
    int overlayStyle() { return prefs.getInt("overlay_style", 0) == 1 ? 1 : 0; }
    void setOverlayStyle(int style) { prefs.edit().putInt("overlay_style", style).commit(); }
    /** The overlay items plus the style bit, as the runtime reads them. */
    int overlayFlags() { return (overlayItems() & ~OVERLAY_MINIMAL) | (overlayStyle() == 1 ? OVERLAY_MINIMAL : 0); }

    boolean frameGraph() { return prefs.getBoolean("frame_graph", false); }
    void setFrameGraph(boolean on) { prefs.edit().putBoolean("frame_graph", on).commit(); }

    /**
     * V-Sync: frames go out in step with the display and the game's clock follows its vsync. Off, the
     * game keeps its own 59.94 Hz clock (vi.cpp) and a late frame does not wait for the next refresh.
     */
    boolean vsync() { return prefs.getBoolean("vsync", true); }
    void setVSync(boolean on) { prefs.edit().putBoolean("vsync", on).commit(); }

    boolean touchControls() { return prefs.getBoolean("touch_controls", true); }
    void setTouchControls(boolean on) { prefs.edit().putBoolean("touch_controls", on).commit(); }

    float touchOpacity() { return prefs.getFloat("touch_opacity", 0.6f); }
    void setTouchOpacity(float opacity) { prefs.edit().putFloat("touch_opacity", opacity).commit(); }

    boolean motionTilt() { return prefs.getBoolean("motion_tilt", true); }
    void setMotionTilt(boolean on) { prefs.edit().putBoolean("motion_tilt", on).commit(); }

    boolean motionShake() { return prefs.getBoolean("motion_shake", true); }
    void setMotionShake(boolean on) { prefs.edit().putBoolean("motion_shake", on).commit(); }

    boolean haptics() { return prefs.getBoolean("haptics", true); }
    void setHaptics(boolean on) { prefs.edit().putBoolean("haptics", on).commit(); }
    boolean touchCamera() { return prefs.getBoolean("touch_camera", false); }
    /** TouchControlsView.STYLE_MODERN or STYLE_CLASSIC. */
    int touchStyle() { return prefs.getInt("touch_style", TouchControlsView.STYLE_MODERN); }
    void setTouchStyle(int style) { prefs.edit().putInt("touch_style", style).commit(); }
    /** TouchIcons.COLOR_*: gold, red, blue or white; A, B, X and Y keep their own colours. */
    int touchColor() { return prefs.getInt("touch_color", TouchIcons.COLOR_BLUE); }
    void setTouchColor(int color) { prefs.edit().putInt("touch_color", color).commit(); }
    void setTouchCamera(boolean on) { prefs.edit().putBoolean("touch_camera", on).commit(); }
    /** Settings > Controls > Gamepad Buttons (GamepadButtons): "a=b,b=a,...", "" = the default layout. */
    String gamepadMap() { return prefs.getString("gamepad_map", ""); }
    void setGamepadMap(String map) { prefs.edit().putString("gamepad_map", map).commit(); }
    /** Off: a drag to the right turns the view to the right. On (default): the scene follows the finger. */
    boolean invertCamera() { return prefs.getBoolean("invert_camera", true); }
    void setInvertCamera(boolean on) { prefs.edit().putBoolean("invert_camera", on).commit(); }

    /** The touch controls as the player arranged them in the layout editor ("" is the default). */
    boolean fixedStick() { return prefs.getBoolean("touch_fixed_stick", false); }
    void setFixedStick(boolean on) { prefs.edit().putBoolean("touch_fixed_stick", on).commit(); }

    String touchLayout() { return prefs.getString("touch_layout", ""); }

    /** The installed GPU driver (GpuDrivers id) the game loads; "" = the phone's own. */
    String gpuDriver() { return prefs.getString("gpu_driver", ""); }

    /** 0 = Auto (Dawn; the Vulkan backend only for a driver Dawn cannot use), 1 = Vulkan backend first. */
    /**
     * Renderer: 0 Auto, 1 Vulkan (aurora's own), 2 OpenGL ES (Dawn on OpenGL ES), 3 Dawn, 4 WebGPU 1.0
     * (libmain10.so: the 1.0 renderer, GpuDrivers.useRenderer10).
     */
    int renderer() { return prefs.getInt("renderer", 0); }
    /** The Renderer choice in menu order: Auto, Vulkan, WebGPU 1.2 (Dawn), WebGPU 1.0, OpenGL ES. */
    static final int[] RENDERER_ORDER = {0, 1, 3, 4, 2};
    static String rendererName(int which) {
        switch (which) {
            case 1: return "Vulkan";
            case 2: return "OpenGL ES (Experimental)";
            case 3: return "WebGPU 1.2";
            case 4: return "WebGPU 1.0";
            default: return "Auto";
        }
    }
    void setRenderer(int which) { prefs.edit().putInt("renderer", which).commit(); }
    void setGpuDriver(String id) { prefs.edit().putString("gpu_driver", id == null ? "" : id).commit(); }
    void setTouchLayout(String layout) { prefs.edit().putString("touch_layout", layout).commit(); }

    /** GX Thread: GX work on its own thread; read when the game starts. */
    boolean gxThread() { return prefs.getBoolean("gx_thread", false); }
    /**
     * What the game starts with: Ultra Low under the 30 FPS Lock always runs GX Thread (Redmi bench: the
     * Lock alone saved 23% of the game thread's work a step, with GX Thread 36%, and steadier frames).
     * The menu's switch keeps the player's own choice. The GL backend still turns it off by itself.
     */
    boolean gxThreadAtStart() { return gxThread() || (lowEndLevel() == 2 && fpsLock30()); }
    /** Real (Hor+) widescreen; off stretches the 4:3 picture, lighter on the CPU. */
    boolean realWidescreen() { return prefs.getBoolean("real_widescreen", false); }
    void setRealWidescreen(boolean on) { prefs.edit().putBoolean("real_widescreen", on).commit(); }
    void setGxThread(boolean on) { prefs.edit().putBoolean("gx_thread", on).commit(); }
    /** Thermal Control (off by default). */
    boolean thermalControl() { return prefs.getBoolean("thermal_control", false); }
    void setThermalControl(boolean on) { prefs.edit().putBoolean("thermal_control", on).commit(); }
    /** Sustained Performance (off by default): clocks the phone can hold for a long session. */
    boolean sustainedPerformance() { return prefs.getBoolean("sustained_performance", false); }
    void setSustainedPerformance(boolean on) { prefs.edit().putBoolean("sustained_performance", on).commit(); }
    /** Recording-friendly audio (off by default): the sound stays on the path a screen recording captures. */
    boolean recordingAudio() { return prefs.getBoolean("recording_audio", false); }
    void setRecordingAudio(boolean on) { prefs.edit().putBoolean("recording_audio", on).commit(); }
    /** Upload Memory (Vulkan renderer): 0 Auto (measured at start), 1 Standard (write-combined), 2 Cached. */
    int uploadMemory() { return prefs.getInt("upload_memory", 0); }
    void setUploadMemory(int mode) { prefs.edit().putInt("upload_memory", mode).commit(); }

    /** Whether the phone has Android's sustained performance mode (Window.setSustainedPerformanceMode). */
    static boolean sustainedSupported(android.content.Context context) {
        final android.os.PowerManager pm = context.getSystemService(android.os.PowerManager.class);
        return pm != null && pm.isSustainedPerformanceModeSupported();
    }

    /**
     * Thermal Control works on every phone: the native side reads the system's thermal headroom
     * where there is one and the battery temperature otherwise. Not probed here: Android answers
     * NaN to headroom calls less than 10 s apart, and a probe right before the game spent its call.
     */
    static boolean thermalSupported(android.content.Context context) {
        return true;
    }

    /** Replacement textures (Dolphin-style tex1_*.png) from /sdcard/Basara2Heroes[-Beta]/Textures. */
    boolean customTextures() { return prefs.getBoolean("custom_textures", false); }
    void setCustomTextures(boolean on) { prefs.edit().putBoolean("custom_textures", on).commit(); }
    /** The texture mods in order with their ticks (TexturePacks: "1|name" or "0|name" per line). */
    String texturePacks() { return prefs.getString("texture_packs", ""); }
    void setTexturePacks(String value) { prefs.edit().putString("texture_packs", value).commit(); }
    /** With custom textures: every texture the game uses goes to Textures/Dump, named for its replacement. */
    boolean dumpTextures() { return prefs.getBoolean("dump_textures", false); }
    void setDumpTextures(boolean on) { prefs.edit().putBoolean("dump_textures", on).commit(); }

    /** HD Textures: 0 off, 1 live (upscaled 2x while playing, kept in HD-Cache). */
    int hdTextures() { return Math.max(0, Math.min(1, prefs.getInt("hd_textures", 0))); }
    void setHdTextures(int mode) { prefs.edit().putInt("hd_textures", mode).commit(); }
    /** Disk budget of the HD texture cache, in MB. */
    static final int[] HD_CACHE_SIZES = {512, 1024, 2048};
    int hdCacheMb() { return prefs.getInt("hd_cache_mb", 1024); }
    void setHdCacheMb(int mb) { prefs.edit().putInt("hd_cache_mb", mb).commit(); }
    /** Report Texture Keys: the log lists the name of each texture the game uses (no images). */
    boolean textureKeyReport() { return prefs.getBoolean("texture_key_report", false); }
    void setTextureKeyReport(boolean on) { prefs.edit().putBoolean("texture_key_report", on).commit(); }

    /** The launcher's language: Tr.AUTO (from the phone's region), Tr.ENGLISH or Tr.INDONESIAN. */
    int language() { return prefs.getInt("language", Tr.AUTO); }
    void setLanguage(int language) { prefs.edit().putInt("language", language).commit(); }

    /** The build whose release notes the player has seen ("" before the first). */
    String notesSeen() { return prefs.getString("notes_seen", ""); }
    void setNotesSeen(String build) { prefs.edit().putString("notes_seen", build).commit(); }

    /** Lowers the render resolution while the GPU holds the game back (the chosen one is the ceiling). */
    boolean dynamicResolution() { return prefs.getBoolean("dynamic_resolution", false); }
    void setDynamicResolution(boolean on) { prefs.edit().putBoolean("dynamic_resolution", on).commit(); }
    /** Skip Boot: the logos run at full speed, undrawn, up to the game's save check. */
    boolean skipBoot() { return prefs.getBoolean("skip_boot", true); }
    void setSkipBoot(boolean on) { prefs.edit().putBoolean("skip_boot", on).commit(); }

    /** Beta only: why the Beta ended, once BetaGate has seen it end (kept, so going offline won't reopen it). */
    /**
     * Why this Beta ended, or null. Kept with the versionCode it was seen on: an update installed over
     * an ended Beta starts clean (the next check locks it again if it is still too old).
     */
    String betaEnded() {
        final String reason = prefs.getString("beta_ended", null);
        if (reason == null) return null;
        if (prefs.getInt("beta_ended_code", -1) != BuildConfig.VERSION_CODE) {
            prefs.edit().remove("beta_ended").remove("beta_ended_code").commit();
            return null;
        }
        return reason;
    }
    void setBetaEnded(String reason) {
        prefs.edit().putString("beta_ended", reason).putInt("beta_ended_code", BuildConfig.VERSION_CODE).commit();
    }

    /** "2× (960p)": the scale and the number of lines it renders, as the menu shows it. */
    static String resolutionLabel(float scale) {
        if (scale <= 0f) return Tr.t("Match screen");
        final String factor = scale == Math.rint(scale)
                ? String.valueOf((int) scale) : String.valueOf(scale);
        return factor + "× (" + Math.round(480 * scale) + "p)";
    }
}
