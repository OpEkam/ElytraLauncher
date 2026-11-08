package net.kdt.pojavlaunch;

import static net.kdt.pojavlaunch.Tools.currentDisplayMetrics;
import static net.kdt.pojavlaunch.Tools.dialogForceClose;
import static net.kdt.pojavlaunch.Tools.hasMods;
import static net.kdt.pojavlaunch.Tools.runMethodbyReflection;
import static net.kdt.pojavlaunch.prefs.LauncherPreferences.PREF_ENABLE_GYRO;
import static net.kdt.pojavlaunch.prefs.LauncherPreferences.PREF_SHOW_CONTROLS_IN_RECORDING;
import static net.kdt.pojavlaunch.prefs.LauncherPreferences.PREF_SUSTAINED_PERFORMANCE;
import static net.kdt.pojavlaunch.prefs.LauncherPreferences.PREF_USE_ALTERNATE_SURFACE;
import static net.kdt.pojavlaunch.prefs.LauncherPreferences.PREF_VIRTUAL_MOUSE_START;
import static org.lwjgl.glfw.CallbackBridge.sendKeyPress;
import static org.lwjgl.glfw.CallbackBridge.windowHeight;
import static org.lwjgl.glfw.CallbackBridge.windowWidth;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.content.ServiceConnection;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.RectF;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.IBinder;
import android.util.Log;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.FrameLayout;
import android.widget.ListView;
import android.widget.Toast;

import androidx.annotation.Keep;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsAnimationCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.drawerlayout.widget.DrawerLayout;

import com.kdt.LoggerView;

import net.kdt.pojavlaunch.customcontrols.ControlButtonMenuListener;
import net.kdt.pojavlaunch.customcontrols.ControlData;
import net.kdt.pojavlaunch.customcontrols.ControlDrawerData;
import net.kdt.pojavlaunch.customcontrols.ControlJoystickData;
import net.kdt.pojavlaunch.customcontrols.ControlLayout;
import net.kdt.pojavlaunch.customcontrols.CustomControls;
import net.kdt.pojavlaunch.customcontrols.EditorExitable;
import net.kdt.pojavlaunch.customcontrols.keyboard.LwjglCharSender;
import net.kdt.pojavlaunch.customcontrols.keyboard.TouchCharInput;
import net.kdt.pojavlaunch.customcontrols.mouse.GyroControl;
import net.kdt.pojavlaunch.customcontrols.mouse.HotbarView;
import net.kdt.pojavlaunch.customcontrols.mouse.Touchpad;
import net.kdt.pojavlaunch.lifecycle.ContextExecutor;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;
import net.kdt.pojavlaunch.prefs.QuickSettingSideDialog;
import net.kdt.pojavlaunch.services.GameService;
import net.kdt.pojavlaunch.services.GameplayMediaProjectionService;
import net.kdt.pojavlaunch.utils.JREUtils;
import net.kdt.pojavlaunch.utils.GameplayFrameRecorder;
import net.kdt.pojavlaunch.utils.MCOptionUtils;
import net.kdt.pojavlaunch.utils.TouchControllerInputView;
import net.kdt.pojavlaunch.utils.TouchControllerUtils;
import net.kdt.pojavlaunch.value.MinecraftAccount;
import net.kdt.pojavlaunch.value.launcherprofiles.LauncherProfiles;
import net.kdt.pojavlaunch.value.launcherprofiles.MinecraftProfile;

import org.libsdl.app.SDL;
import org.libsdl.app.SDLSurface;
import org.lwjgl.glfw.CallbackBridge;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;
import java.util.Objects;

public class MainActivity extends BaseActivity implements ControlButtonMenuListener, EditorExitable, ServiceConnection, TouchControllerInputView.InputAreaRectListener {
    public static volatile ClipboardManager GLOBAL_CLIPBOARD;
    public static final String TAG = "MainActivity";
    public static final String INTENT_MINECRAFT_VERSION = "intent_version";

    volatile public static boolean isInputStackCall;

    public static TouchCharInput touchCharInput;
    private TouchControllerInputView touchControllerInputView;
    private MinecraftGLSurface minecraftGLView;
    private static Touchpad touchpad;
    private LoggerView loggerView;
    private DrawerLayout drawerLayout;
    private ListView navDrawer;
    private View mDrawerPullButton;
    private GyroControl mGyroControl = null;
    private ControlLayout mControlLayout;
    private HotbarView mHotbarView;
    private FrameLayout contentFrame;

    @Nullable
    private RectF inputAreaRect;
    private int imeHeight;
    private boolean hasOngoingImeAnimation;

    MinecraftProfile minecraftProfile;

    private ArrayAdapter<String> gameActionArrayAdapter;
    private AdapterView.OnItemClickListener gameActionClickListener;
    public ArrayAdapter<String> ingameControlsEditorArrayAdapter;
    public AdapterView.OnItemClickListener ingameControlsEditorListener;
    private GameService.LocalBinder mServiceBinder;

    private QuickSettingSideDialog mQuickSettingSideDialog;
    private GameplayFrameRecorder gameplayFrameRecorder;
    private File latestRecordingFile;
    private static final int REQUEST_RECORD_AUDIO = 7401;
    private static final int REQUEST_MEDIA_PROJECTION = 7403;
    private boolean pendingMicrophoneRecording;
    private boolean pendingSystemAudioRecording;
    private boolean pendingSystemAudioMicrophone;
    private GameplayMediaProjectionService projectionService;
    private boolean projectionServiceBound;

    private final ServiceConnection projectionServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            projectionService = ((GameplayMediaProjectionService.LocalBinder) binder).getService();
            MediaProjection projection = projectionService.getProjection();
            if (projection == null) {
                Toast.makeText(MainActivity.this,
                        "Android did not grant gameplay audio capture. Try again.", Toast.LENGTH_LONG).show();
                releaseProjectionService();
                return;
            }
            startGameplayRecording(pendingSystemAudioMicrophone, projection);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            projectionService = null;
            projectionServiceBound = false;
        }
    };

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);

        minecraftProfile = LauncherProfiles.getCurrentProfile();
        Tools.useANGLE = minecraftProfile.useANGLE;

        String gameDirPath = Tools.getGameDirPath(minecraftProfile).getAbsolutePath();
        MCOptionUtils.load(gameDirPath);

        Intent gameServiceIntent = new Intent(this, GameService.class);
        // Start the service a bit early
        ContextCompat.startForegroundService(this, gameServiceIntent);
        initLayout(R.layout.activity_basemain);
        CallbackBridge.addGrabListener(touchpad);
        CallbackBridge.addGrabListener(minecraftGLView);

        if (Tools.hasTouchController(new File(gameDirPath)) || LauncherPreferences.PREF_FORCE_ENABLE_TOUCHCONTROLLER) {
            TouchControllerUtils.initialize(this, touchControllerInputView);
        }
        if (LauncherPreferences.PREF_GAMEPAD_FORCEDSDL_PASSTHRU) {
            CallbackBridge.notifyLauncher(CallbackBridge.NOTIF_TYPE_SDL, CallbackBridge.ACTION_INIT_LAUNCHER_INTEGRATION);
        }

        mGyroControl = new GyroControl(this);

        // Enabling this on TextureView results in a broken white result
        if(PREF_USE_ALTERNATE_SURFACE) getWindow().setBackgroundDrawable(null);
        else getWindow().setBackgroundDrawable(new ColorDrawable(Color.BLACK));

        // Set the sustained performance mode for available APIs
        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
            getWindow().setSustainedPerformanceMode(PREF_SUSTAINED_PERFORMANCE);

        ingameControlsEditorArrayAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_list_item_1, getResources().getStringArray(R.array.menu_customcontrol));
        ingameControlsEditorListener = (parent, view, position, id) -> {
            switch(position) {
                case 0: mControlLayout.addControlButton(new ControlData("New")); break;
                case 1: mControlLayout.addDrawer(new ControlDrawerData()); break;
                case 2: mControlLayout.addJoystickButton(new ControlJoystickData()); break;
                case 3: mControlLayout.openLoadDialog(); break;
                case 4: mControlLayout.openSaveDialog(this); break;
                case 5: mControlLayout.openSetDefaultDialog(); break;
                case 6: mControlLayout.openExitDialog(this);
            }
        };

        // Recompute the gui scale when options are changed
        MCOptionUtils.MCOptionListener optionListener = MCOptionUtils::getMcScale;
        MCOptionUtils.addMCOptionListener(optionListener);
        mControlLayout.setModifiable(false);

        // Listen to IME insets animation
        ViewCompat.setWindowInsetsAnimationCallback(contentFrame, new WindowInsetsAnimationCompat.Callback(WindowInsetsAnimationCompat.Callback.DISPATCH_MODE_STOP) {
            @Override
            public void onPrepare(@NonNull WindowInsetsAnimationCompat animation) {
                if ((animation.getTypeMask() & WindowInsetsCompat.Type.ime()) != 0) {
                    hasOngoingImeAnimation = true;
                }
            }

            @NonNull
            @Override
            public WindowInsetsCompat onProgress(@NonNull WindowInsetsCompat insets, @NonNull List<WindowInsetsAnimationCompat> runningAnimations) {
                imeHeight = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom;
                refreshImeTranslation();
                return insets;
            }

            @Override
            public void onEnd(@NonNull WindowInsetsAnimationCompat animation) {
                if ((animation.getTypeMask() & WindowInsetsCompat.Type.ime()) != 0) {
                    hasOngoingImeAnimation = false;
                }
            }
        });
        ViewCompat.setOnApplyWindowInsetsListener(contentFrame, (v, insets) -> {
            // Only refresh translation if IME change insets itself, not the animation
            if (!hasOngoingImeAnimation) {
                imeHeight = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom;
                refreshImeTranslation();
            }
            return insets;
        });

        // Set the activity for the executor. Must do this here, or else Tools.showErrorRemote() may not
        // execute the correct method
        ContextExecutor.setActivity(this);
        //Now, attach to the service. The game will only start when this happens, to make sure that we know the right state.
        bindService(gameServiceIntent, this, 0);
    }

    protected void initLayout(int resId) {
        setContentView(resId);
        bindValues();
        mControlLayout.setMenuListener(this);

        mDrawerPullButton.setOnClickListener(v -> onClickedMenu());
        drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED);

        try {
            File latestLogFile = new File(Tools.DIR_GAME_HOME, "latestlog.txt");
            if(!latestLogFile.exists() && !latestLogFile.createNewFile())
                throw new IOException("Failed to create a new log file");
            Logger.begin(latestLogFile.getAbsolutePath());
            // FIXME: is it safe for multi thread?
            GLOBAL_CLIPBOARD = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            touchCharInput.setCharacterSender(new LwjglCharSender());

            touchControllerInputView.setInputAreaRectListener(this);

            if(minecraftProfile.pojavRendererName != null) {
                Log.i("RdrDebug","__P_renderer="+minecraftProfile.pojavRendererName);
                Tools.LOCAL_RENDERER = minecraftProfile.pojavRendererName;
                // TODO: Remove this jank when it's not relevant anymore
                // Shitty hack to make OSMZink smoothly transition into kopper
                if (minecraftProfile.pojavRendererName.equals("vulkan_zink")) Tools.LOCAL_RENDERER = "opengles3_desktopgl_zink_kopper";
            }

            setTitle("Minecraft " + minecraftProfile.lastVersionId);

            // Minecraft 1.13+

            String version = getIntent().getStringExtra(INTENT_MINECRAFT_VERSION);
            version = version == null ? minecraftProfile.lastVersionId : version;

            JMinecraftVersionList.Version mVersionInfo = Tools.getVersionInfo(version);
            isInputStackCall = mVersionInfo.arguments != null;
            CallbackBridge.nativeSetUseInputStackQueue(isInputStackCall);

            Tools.getDisplayMetrics(this);
            windowWidth = Tools.getDisplayFriendlyRes(currentDisplayMetrics.widthPixels, 1f);
            windowHeight = Tools.getDisplayFriendlyRes(currentDisplayMetrics.heightPixels, 1f);


            // Menu
            List<String> gameActions = new java.util.ArrayList<>(
                    java.util.Arrays.asList(getResources().getStringArray(R.array.menu_ingame)));
            gameActions.add("Start gameplay recording");
            gameActions.add("Pause / resume recording");
            gameActions.add("Stop gameplay recording");
            gameActionArrayAdapter = new ArrayAdapter<>(this,
                    android.R.layout.simple_list_item_1, gameActions);
            gameActionClickListener = (parent, view, position, id) -> {
                switch(position) {
                    case 0: dialogForceClose(MainActivity.this); break;
                    case 1: openLogOutput(); break;
                    case 2: dialogSendCustomKey(); break;
                    case 3: openQuickSettings(); break;
                    case 4: openCustomControls(); break;
                    case 5: startGameplayRecording(); break;
                    case 6: toggleGameplayRecordingPause(); break;
                    case 7: stopGameplayRecording(); break;
                }
                drawerLayout.closeDrawers();
            };
            navDrawer.setAdapter(gameActionArrayAdapter);
            navDrawer.setOnItemClickListener(gameActionClickListener);
            drawerLayout.closeDrawers();

            final String finalVersion = version;
            minecraftGLView.setSurfaceReadyListener(() -> {
                try {
                    // Setup virtual mouse right before launching
                    if (PREF_VIRTUAL_MOUSE_START) {
                        touchpad.post(() -> touchpad.switchState());
                    }

                    // At this time, correct size is known
                    touchControllerInputView.setSize(minecraftGLView.getWidth(), minecraftGLView.getHeight());

                    runCraft(finalVersion, mVersionInfo);
                }catch (Throwable e){
                    Tools.showErrorRemote(e);
                }
            });
        } catch (Throwable e) {
            Tools.showError(this, e, true);
        }
    }

    private void loadControls() {
        try {
            // Load keys
            mControlLayout.loadLayout(
                    minecraftProfile.controlFile == null
                            ? LauncherPreferences.PREF_DEFAULTCTRL_PATH
                            : Tools.CTRLMAP_PATH + "/" + minecraftProfile.controlFile);
        } catch(IOException e) {
            try {
                Log.w("MainActivity", "Unable to load the control file, loading the default now", e);
                mControlLayout.loadLayout(Tools.CTRLDEF_FILE);
            } catch (IOException ioException) {
                Tools.showError(this, ioException);
            }
        } catch (Throwable th) {
            Tools.showError(this, th);
        }
        mDrawerPullButton.setVisibility(mControlLayout.hasMenuButton() ? View.GONE : View.VISIBLE);
        mControlLayout.toggleControlVisible();
    }

    @Override
    public void onAttachedToWindow() {
        // Post to get the correct display dimensions after layout.
        LauncherPreferences.computeNotchSize(this);
        mControlLayout.post(()->{
            Tools.getDisplayMetrics(this);
            loadControls();
        });
    }

    /** Boilerplate binding */
    private void bindValues(){
        mControlLayout = findViewById(R.id.main_control_layout);
        minecraftGLView = findViewById(R.id.main_game_render_view);
        touchpad = findViewById(R.id.main_touchpad);
        drawerLayout = findViewById(R.id.main_drawer_options);
        navDrawer = findViewById(R.id.main_navigation_view);
        loggerView = findViewById(R.id.mainLoggerView);
        mControlLayout = findViewById(R.id.main_control_layout);
        touchCharInput = findViewById(R.id.mainTouchCharInput);
        touchControllerInputView = findViewById(R.id.touch_controller_input);
        mDrawerPullButton = findViewById(R.id.drawer_button);
        mHotbarView = findViewById(R.id.hotbar_view);
        contentFrame = findViewById(R.id.content_frame);
    }

    @Override
    public void onResume() {
        super.onResume();
        if(PREF_ENABLE_GYRO) mGyroControl.enable();
        CallbackBridge.nativeSetWindowAttrib(LwjglGlfwKeycode.GLFW_FOCUSED, 1);
        CallbackBridge.nativeSetWindowAttrib(LwjglGlfwKeycode.GLFW_HOVERED, 1);
    }

    @Override
    protected void onPause() {
        mGyroControl.disable();
        if (CallbackBridge.isGrabbing()){
            sendKeyPress(LwjglGlfwKeycode.GLFW_KEY_ESCAPE);
        }
        if(mQuickSettingSideDialog != null) {
            mQuickSettingSideDialog.cancel();
        }
        CallbackBridge.nativeSetWindowAttrib(LwjglGlfwKeycode.GLFW_FOCUSED, 0);
        CallbackBridge.nativeSetWindowAttrib(LwjglGlfwKeycode.GLFW_HOVERED, 0);

        super.onPause();
    }

    @Override
    protected void onStart() {
        super.onStart();
        CallbackBridge.nativeSetWindowAttrib(LwjglGlfwKeycode.GLFW_VISIBLE, 1);
    }

    @Override
    protected void onStop() {
        CallbackBridge.nativeSetWindowAttrib(LwjglGlfwKeycode.GLFW_VISIBLE, 0);
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        if (gameplayFrameRecorder != null) {
            try { gameplayFrameRecorder.stop(); }
            catch (IOException error) { Log.w(TAG, "Unable to finalize gameplay recording", error); }
            gameplayFrameRecorder = null;
        }
        releaseProjectionService();
        super.onDestroy();
        CallbackBridge.removeGrabListener(touchpad);
        CallbackBridge.removeGrabListener(minecraftGLView);
        ContextExecutor.clearActivity();
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if(mGyroControl != null) mGyroControl.updateOrientation();
        // Layout resize is practically guaranteed on a configuration change, and `onConfigurationChanged`
        // does not implicitly start a layout. So, request a layout and expect the screen dimensions to be valid after the]
        // post.
        mControlLayout.requestLayout();
        mControlLayout.post(()->{
            // Child of mControlLayout, so refreshing size here is correct
            Tools.setFullscreen(this, setFullscreen());
            minecraftGLView.refreshSize();
            Tools.updateWindowSize(this);
            mControlLayout.refreshControlButtonPositions();
            touchControllerInputView.setSize(minecraftGLView.getWidth(), minecraftGLView.getHeight());
        });
    }

    @Override
    protected void onPostResume() {
        super.onPostResume();
        if(minecraftGLView != null)  // Useful when backing out of the app
            Tools.MAIN_HANDLER.postDelayed(() -> minecraftGLView.refreshSize(), 500);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQUEST_MEDIA_PROJECTION) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                startProjectionService(resultCode, data);
            } else {
                pendingSystemAudioRecording = false;
                pendingSystemAudioMicrophone = false;
                Toast.makeText(this, "Gameplay audio permission was not granted", Toast.LENGTH_SHORT).show();
            }
            return;
        }

        if (requestCode == 1 && resultCode == Activity.RESULT_OK) {
            // Reload PREF_DEFAULTCTRL_PATH
            // If the storage root got unmounted/unreadable we won't be able to load the file anyway,
            // and MissingStorageActivity will be started.
            if(!Tools.checkStorageRoot(this)) return;
            LauncherPreferences.loadPreferences(getApplicationContext());
            try {
                mControlLayout.loadLayout(LauncherPreferences.PREF_DEFAULTCTRL_PATH);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private void runCraft(String versionId, JMinecraftVersionList.Version version) throws Throwable {
        String assetVersion;
        try {
            if (version.inheritsFrom != null) { // We are almost definitely modded if this runs
                File vanillaJsonFile = new File(Tools.DIR_HOME_VERSION + "/" + version.inheritsFrom + "/" + version.inheritsFrom + ".json");
                JMinecraftVersionList.Version vanillaJson;
                try { // Get the vanilla json from modded instance
                    vanillaJson = Tools.GLOBAL_GSON.fromJson(Tools.read(vanillaJsonFile.getAbsolutePath()), JMinecraftVersionList.Version.class);
                } catch (IOException ignored) { // Should never happen, we check for this in MinecraftDownloader().start()
                    throw new RuntimeException(getString(R.string.error_vanilla_json_corrupt));
                }
                // Something went wrong if this is somehow not the case anymore
                if (!Objects.equals(vanillaJson.assets, vanillaJson.assetIndex.id))
                    Tools.showErrorRemote(new RuntimeException(getString(R.string.error_vanilla_json_corrupt)));
                assetVersion = vanillaJson.assets;
            } else {
                // Else assume we are vanilla
                if (!Objects.equals(version.assets, version.assetIndex.id))
                    Tools.showErrorRemote(new RuntimeException(getString(R.string.error_vanilla_json_corrupt)));
                assetVersion = version.assets;
            }
       } catch (RuntimeException ignored){
            assetVersion = "legacy";
       } // If this fails.. oh well.

        // FIXME: Automatic detection should be based on provided hint GLFW_CONTEXT_VERSION_MAJOR and GLFW_CONTEXT_VERSION_MINOR
        // Autoselect renderer
        boolean hasAngelica = hasMods("angelica");
        if (Tools.LOCAL_RENDERER == null) {
            // Choose a safe default renderer on the first launch and save it to
            // the selected profile so it remains selected on later launches.
            Tools.LOCAL_RENDERER = "opengles_mobileglues";
            minecraftProfile.pojavRendererName = Tools.LOCAL_RENDERER;
            LauncherProfiles.write();
            Log.i("runCraft", "Saved default renderer: " + Tools.LOCAL_RENDERER);
        }
        // Angelica IS the FPE emulator
        if (hasAngelica) Tools.useSFPEW = false;

        // TODO: Detection for if a mod is present that can use system GLES as driver, set
        // Tools.LOCAL_RENDERER = "opengles_system_gles";

        // This only happens if an old renderer that was selected is removed. Uses renderer_values
        // as the list of priority to use, highest to lowest.
        if(!Tools.checkRendererCompatible(this, Tools.LOCAL_RENDERER)) {
            Tools.RenderersList renderersList = Tools.getCompatibleRenderers(this);
            if (renderersList.rendererIds.isEmpty()) {
                throw new IllegalStateException("No compatible renderer is available on this device");
            }
            String firstCompatibleRenderer = renderersList.rendererIds.get(0);
            Log.i("runCraft","Missing renderer "+Tools.LOCAL_RENDERER+ " will be replaced with "+firstCompatibleRenderer);
            Tools.LOCAL_RENDERER = firstCompatibleRenderer;
            minecraftProfile.pojavRendererName = firstCompatibleRenderer;
            LauncherProfiles.write();
            Tools.releaseRenderersCache();
        }

        // MCL-3732 Mitigation
        // I don't trust the bug tracker. 'server-resource-pack" was removed in 1.20.3-pre3
        // so we use 12 to detect that. We still generate till 1.20.5 else we don't cover
        // 1.20.3-pre2 and such. Better to over than to under.
        File folder = new File(Tools.getGameDirPath(minecraftProfile), "server-resource-pack");
        try {
            if (Integer.parseInt(assetVersion) <= 12) folder.mkdir();
        } catch (NumberFormatException e) { folder.mkdir(); }

        MinecraftAccount minecraftAccount = PojavProfile.getCurrentProfileContent(this, null);
        if (hasMods("sodium"))
            Logger.appendToLog("WARNING: Sodium is being used, Amethyst-Android does NOT support this mod, you are on your own");
        Logger.appendToLog("--------- Starting game with Launcher Debug!");
        Tools.printLauncherInfo(versionId, Tools.isValidString(minecraftProfile.javaArgs) ? minecraftProfile.javaArgs : LauncherPreferences.PREF_CUSTOM_JAVA_ARGS, Tools.getTotalDeviceMemory(this));
        if(Tools.LOCAL_RENDERER.equals("opengles_mobileglues")) {
            LauncherPreferences.writeMGRendererSettings();
        }
        JREUtils.redirectAndPrintJRELog();
        LauncherProfiles.load();
        int requiredJavaVersion = 8;
        if(version.javaVersion != null) requiredJavaVersion = version.javaVersion.majorVersion;

        // Ensure a compatible runtime exists before launch. Modern versions may use a
        // bundled runtime; legacy versions without javaVersion metadata require Java 8.
        boolean requiresLegacyJava8 = version.javaVersion == null
                || "jre-legacy".equalsIgnoreCase(version.javaVersion.component);
        if (requiresLegacyJava8) {
            // Legacy versions (including 1.7.10) use Java 8 even if metadata has
            // a javaVersion object with component "jre-legacy".
            if (net.kdt.pojavlaunch.multirt.MultiRTUtils.getExactJreName(8) == null) {
                try {
                    NewJREUtil.ExternalRuntime.JRE_8.downloadRuntime(this);
                } catch (RuntimeException e) {
                    Log.e("runCraft", "Could not automatically install Java 8", e);
                    Tools.showError(this, e);
                    Tools.runOnUiThread(() -> mServiceBinder.isActive = false);
                    return;
                }
            }
        } else if (!NewJREUtil.installNewJreIfNeeded(this, version)) {
            Tools.runOnUiThread(() -> mServiceBinder.isActive = false);
            return;
        }

        LauncherProfiles.load();
        minecraftProfile = LauncherProfiles.getCurrentProfile();
        Tools.launchMinecraft(this, minecraftAccount, minecraftProfile, versionId, requiredJavaVersion);
        //Note that we actually stall in the above function, even if the game crashes. But let's be safe.
        Tools.runOnUiThread(()-> mServiceBinder.isActive = false);
    }

    public void setmLastIndex(int a){
        mHotbarView.setmLastIndex(a);
    }

    private void dialogSendCustomKey() {
        AlertDialog.Builder dialog = new AlertDialog.Builder(this);
        dialog.setTitle(R.string.control_customkey);
        dialog.setItems(EfficientAndroidLWJGLKeycode.generateKeyName(), (dInterface, position) -> EfficientAndroidLWJGLKeycode.execKeyIndex(position));
        dialog.show();
    }

    boolean isInEditor;
    private void openCustomControls() {
        if(ingameControlsEditorListener == null || ingameControlsEditorArrayAdapter == null) return;

        mControlLayout.setModifiable(true);
        navDrawer.setAdapter(ingameControlsEditorArrayAdapter);
        navDrawer.setOnItemClickListener(ingameControlsEditorListener);
        mDrawerPullButton.setVisibility(View.VISIBLE);
        isInEditor = true;
    }

    private void startGameplayRecording() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            new AlertDialog.Builder(this)
                    .setTitle("Gameplay recording")
                    .setItems(new String[]{
                            "Video only",
                            "Video + microphone",
                            "Video + game audio",
                            "Video + game audio + microphone"
                    }, (dialog, which) -> {
                        if (which == 0) startGameplayRecording(false);
                        else if (which == 1) requestMicrophoneAndStart();
                        else if (which == 2) requestSystemAudioAndStart(false);
                        else requestSystemAudioAndStart(true);
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        } else {
            new AlertDialog.Builder(this)
                    .setTitle("Gameplay recording")
                    .setItems(new String[]{"Video only", "Video + microphone"}, (dialog, which) -> {
                        if (which == 0) startGameplayRecording(false);
                        else requestMicrophoneAndStart();
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        }
    }

    private void requestSystemAudioAndStart(boolean withMicrophone) {
        if (withMicrophone && ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            pendingSystemAudioRecording = true;
            pendingSystemAudioMicrophone = true;
            pendingMicrophoneRecording = true;
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_RECORD_AUDIO);
            return;
        }
        pendingSystemAudioMicrophone = withMicrophone;
        MediaProjectionManager manager = (MediaProjectionManager)
                getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        if (manager == null) {
            Toast.makeText(this, "Android gameplay audio capture is unavailable", Toast.LENGTH_LONG).show();
            return;
        }
        startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_MEDIA_PROJECTION);
    }

    private void startProjectionService(int resultCode, Intent resultData) {
        Intent serviceIntent = new Intent(this, GameplayMediaProjectionService.class)
                .putExtra(GameplayMediaProjectionService.EXTRA_RESULT_CODE, resultCode)
                .putExtra(GameplayMediaProjectionService.EXTRA_RESULT_DATA, resultData);
        try {
            ContextCompat.startForegroundService(this, serviceIntent);
            projectionServiceBound = bindService(serviceIntent, projectionServiceConnection,
                    Context.BIND_AUTO_CREATE);
            if (!projectionServiceBound) {
                stopService(serviceIntent);
                Toast.makeText(this, "Could not connect to gameplay audio service", Toast.LENGTH_LONG).show();
            }
        } catch (RuntimeException error) {
            Toast.makeText(this, "Could not start gameplay audio service: " + error.getMessage(),
                    Toast.LENGTH_LONG).show();
        }
    }

    private void releaseProjectionService() {
        if (projectionServiceBound) {
            try { unbindService(projectionServiceConnection); }
            catch (IllegalArgumentException ignored) {}
            projectionServiceBound = false;
        }
        projectionService = null;
        stopService(new Intent(this, GameplayMediaProjectionService.class));
    }

    private void requestMicrophoneAndStart() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED) {
            startGameplayRecording(true);
            return;
        }
        pendingMicrophoneRecording = true;
        requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_RECORD_AUDIO);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_RECORD_AUDIO) {
            boolean granted = grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            boolean shouldStart = pendingMicrophoneRecording;
            pendingMicrophoneRecording = false;
            if (granted && shouldStart) {
                if (pendingSystemAudioRecording) {
                    pendingSystemAudioRecording = false;
                    requestSystemAudioAndStart(pendingSystemAudioMicrophone);
                } else {
                    startGameplayRecording(true);
                }
            } else {
                pendingSystemAudioRecording = false;
                pendingSystemAudioMicrophone = false;
                Toast.makeText(this, "Microphone permission denied; choose a mode without microphone audio.",
                        Toast.LENGTH_LONG).show();
            }
        }
    }

    private void startGameplayRecording(boolean recordMicrophone) {
        startGameplayRecording(recordMicrophone, null);
    }

    private void startGameplayRecording(boolean recordMicrophone, MediaProjection projection) {
        if (gameplayFrameRecorder != null && gameplayFrameRecorder.isRecording()) {
            Toast.makeText(this, "A gameplay recording is already active", Toast.LENGTH_SHORT).show();
            return;
        }
        File movies = getExternalFilesDir(Environment.DIRECTORY_MOVIES);
        if (movies == null) {
            Toast.makeText(this, "Recording storage is unavailable", Toast.LENGTH_LONG).show();
            return;
        }
        latestRecordingFile = new File(movies,
                "ElytraGameplay-" + System.currentTimeMillis() + ".mp4");
        gameplayFrameRecorder = new GameplayFrameRecorder(error ->
                runOnUiThread(() -> showRecordingError(error)));
        try {
            gameplayFrameRecorder.start(latestRecordingFile, 8_000_000, 30,
                    minecraftGLView.mSurface, recordMicrophone, projection);
            Toast.makeText(this, projection != null
                            ? (recordMicrophone ? "Starting gameplay recording with game audio and microphone."
                                    : "Starting gameplay recording with game audio.")
                            : (recordMicrophone ? "Starting gameplay recording with microphone."
                                    : "Starting gameplay video recording."),
                    Toast.LENGTH_LONG).show();
        } catch (IOException error) {
            Toast.makeText(this, "Could not start recording: " + error.getMessage(),
                    Toast.LENGTH_LONG).show();
            if (projection != null) releaseProjectionService();
        }
    }

    private void showRecordingError(IOException error) {
        StringWriter writer = new StringWriter();
        error.printStackTrace(new PrintWriter(writer));
        String details = writer.toString();
        // Keep the dialog manageable while retaining the complete root-cause chain.
        if (details.length() > 12000) details = details.substring(0, 12000)
                + "\\n\\n[Error details truncated]";
        final String copyDetails = details;
        new AlertDialog.Builder(this)
                .setTitle("Gameplay recording failed")
                .setMessage(details)
                .setPositiveButton("Copy error details", (dialog, which) -> {
                    ClipboardManager clipboard = (ClipboardManager)
                            getSystemService(CLIPBOARD_SERVICE);
                    if (clipboard != null) {
                        clipboard.setPrimaryClip(ClipData.newPlainText(
                                "ElytraLauncher recording error", copyDetails));
                        Toast.makeText(this, "Recording error details copied",
                                Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Close", null)
                .show();
    }

    private void toggleGameplayRecordingPause() {
        if (gameplayFrameRecorder == null || !gameplayFrameRecorder.isRecording()) {
            Toast.makeText(this, "No active gameplay recording", Toast.LENGTH_SHORT).show();
            return;
        }
        if (gameplayFrameRecorder.isPaused()) {
            gameplayFrameRecorder.resume();
            Toast.makeText(this, "Recording resumed", Toast.LENGTH_SHORT).show();
        } else {
            gameplayFrameRecorder.pause();
            Toast.makeText(this, "Recording paused", Toast.LENGTH_SHORT).show();
        }
    }

    private void stopGameplayRecording() {
        if (gameplayFrameRecorder == null) {
            Toast.makeText(this, "No active gameplay recording", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            gameplayFrameRecorder.stop();
            Toast.makeText(this, "Recording saved: " + latestRecordingFile.getAbsolutePath(),
                    Toast.LENGTH_LONG).show();
            releaseProjectionService();
        } catch (IOException error) {
            Toast.makeText(this, "Could not finalize recording: " + error.getMessage(),
                    Toast.LENGTH_LONG).show();
        }
    }

    private void openLogOutput() {
        loggerView.setVisibility(View.VISIBLE);
    }

    private void openQuickSettings() {
        if(mQuickSettingSideDialog == null) {
            mQuickSettingSideDialog = new QuickSettingSideDialog(this, mControlLayout) {
                @Override
                public void onResolutionChanged() {
                    minecraftGLView.refreshSize();
                    mHotbarView.onResolutionChanged();
                }

                @Override
                public void onGyroStateChanged() {
                    mGyroControl.updateOrientation();
                    if (PREF_ENABLE_GYRO) {
                        mGyroControl.enable();
                    } else {
                        mGyroControl.disable();
                    }
                }
            };
        }
        mQuickSettingSideDialog.appear(true);
    }

    public static void toggleMouse(Context ctx) {
        if (CallbackBridge.isGrabbing()) return;

        Toast.makeText(ctx, touchpad.switchState()
                        ? R.string.control_mouseon : R.string.control_mouseoff,
                Toast.LENGTH_SHORT).show();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if(isInEditor) {
            if(event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
                if(event.getAction() == KeyEvent.ACTION_DOWN) mControlLayout.askToExit(this);
                return true;
            }
            return super.dispatchKeyEvent(event);
        }
        boolean handleEvent;
        if(!(handleEvent = minecraftGLView.processKeyEvent(event))) {
            if (event.getKeyCode() == KeyEvent.KEYCODE_BACK && !touchCharInput.isEnabled()) {
                if(event.getAction() != KeyEvent.ACTION_UP) return true; // We eat it anyway
                sendKeyPress(LwjglGlfwKeycode.GLFW_KEY_ESCAPE);
                return true;
            }
        }
        return handleEvent;
    }

    public static void switchKeyboardState() {
        if(touchCharInput != null) touchCharInput.switchKeyboardState();
    }

    @Keep
    public static void openLink(String link) {
        Context ctx = touchpad.getContext(); // no more better way to obtain a context statically
        ((Activity)ctx).runOnUiThread(() -> {
            try {
                if(link.startsWith("file:")) {
                    int truncLength = 5;
                    if(link.startsWith("file://")) truncLength = 7;
                    String path = link.substring(truncLength);
                    Tools.openPath(ctx, new File(path), false);
                }else {
                    Intent intent = new Intent(Intent.ACTION_VIEW);
                    intent.setDataAndType(Uri.parse(link), "*/*");
                    ctx.startActivity(intent);
                }
            } catch (Throwable th) {
                Tools.showError(ctx, th);
            }
        });
    }

    @SuppressWarnings("unused") //TODO: actually use it
    public static void openPath(String path) {
        Context ctx = touchpad.getContext(); // no more better way to obtain a context statically
        ((Activity)ctx).runOnUiThread(() -> {
            try {
                Tools.openPath(ctx, new File(path), false);
            } catch (Throwable th) {
                Tools.showError(ctx, th);
            }
        });
    }

    @Keep
    public static void querySystemClipboard() {
        Tools.runOnUiThread(()->{
            ClipData clipData = GLOBAL_CLIPBOARD.getPrimaryClip();
            if(clipData == null) {
                AWTInputBridge.nativeClipboardReceived(null, null);
                return;
            }
            ClipData.Item firstClipItem = clipData.getItemAt(0);
            //TODO: coerce to HTML if the clip item is styled
            CharSequence clipItemText = firstClipItem.getText();
            if(clipItemText == null) {
                AWTInputBridge.nativeClipboardReceived(null, null);
                return;
            }
            AWTInputBridge.nativeClipboardReceived(clipItemText.toString(), "plain");
        });
    }

    @Keep
    public static void putClipboardData(String data, String mimeType) {
        Tools.runOnUiThread(()-> {
            ClipData clipData = null;
            switch(mimeType) {
                case "text/plain":
                    clipData = ClipData.newPlainText("AWT Paste", data);
                    break;
                case "text/html":
                    clipData = ClipData.newHtmlText("AWT Paste", data, data);
            }
            if(clipData != null) GLOBAL_CLIPBOARD.setPrimaryClip(clipData);
        });
    }

    @Override
    public void onClickedMenu() {
        drawerLayout.openDrawer(navDrawer);
        navDrawer.requestLayout();
    }

    @Override
    public void exitEditor() {
        try {
            mControlLayout.loadLayout((CustomControls)null);
            mControlLayout.setModifiable(false);
            System.gc();
            mControlLayout.loadLayout(
                    minecraftProfile.controlFile == null
                            ? LauncherPreferences.PREF_DEFAULTCTRL_PATH
                            : Tools.CTRLMAP_PATH + "/" + minecraftProfile.controlFile);
            mDrawerPullButton.setVisibility(mControlLayout.hasMenuButton() ? View.GONE : View.VISIBLE);
        } catch (IOException e) {
            Tools.showError(this,e);
        }

        navDrawer.setAdapter(gameActionArrayAdapter);
        navDrawer.setOnItemClickListener(gameActionClickListener);
        isInEditor = false;
    }

    @Override
    public void onServiceConnected(ComponentName name, IBinder service) {
        GameService.LocalBinder localBinder = (GameService.LocalBinder) service;
        mServiceBinder = localBinder;
        minecraftGLView.start(localBinder.isActive, touchpad);
        localBinder.isActive = true;
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {

    }

    /*
     * Android 14 (or some devices, at least) seems to dispatch the the captured mouse events as trackball events
     * due to a bug(?) somewhere(????)
     */
    @RequiresApi(api = Build.VERSION_CODES.O)
    private boolean checkCaptureDispatchConditions(MotionEvent event) {
        int eventSource = event.getSource();
        // On my device, the mouse sends events as a relative mouse device.
        // Not comparing with == here because apparently `eventSource` is a mask that can
        // sometimes indicate multiple sources, like in the case of InputDevice.SOURCE_TOUCHPAD
        // (which is *also* an InputDevice.SOURCE_MOUSE when controlling a cursor)
        return (eventSource & InputDevice.SOURCE_MOUSE_RELATIVE) != 0 ||
                (eventSource & InputDevice.SOURCE_MOUSE) != 0;
    }

    @Override
    public boolean dispatchTrackballEvent(MotionEvent ev) {
        if(Tools.isAndroid8OrHigher() && checkCaptureDispatchConditions(ev))
            return minecraftGLView.dispatchCapturedPointerEvent(ev);
        else return super.dispatchTrackballEvent(ev);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        if (hasFocus) {
            Tools.setFullscreen(this, setFullscreen());
        }
        super.onWindowFocusChanged(hasFocus);
        CallbackBridge.nativeSetWindowAttrib(LwjglGlfwKeycode.GLFW_FOCUSED, hasFocus ? 1 : 0);
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
    }

    @Override
    public void onBackPressed() {
        super.onBackPressed();
    }

    @Override
    public void updateInputAreaRect(@Nullable RectF rect) {
        inputAreaRect = rect;
        refreshImeTranslation();
    }

    private void refreshImeTranslation() {
        if (imeHeight == 0) {
            // Early exit
            contentFrame.setTranslationY(0);
            return;
        }

        int inputAreaBottom;
        if (inputAreaRect != null) {
            inputAreaBottom = (int) inputAreaRect.bottom;
        } else if (LauncherPreferences.PREF_KEYBOARD_PANNING) {
            inputAreaBottom = contentFrame.getHeight();
        } else {
            contentFrame.setTranslationY(0);
            return;
        }

        int bottomDistance = contentFrame.getHeight() - inputAreaBottom;
        int bottomPadding = Math.max(imeHeight - bottomDistance, 0);

        contentFrame.setTranslationY(-bottomPadding);
    }
}
