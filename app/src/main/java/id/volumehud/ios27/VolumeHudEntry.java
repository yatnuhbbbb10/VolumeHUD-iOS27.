package id.volumehud.ios27;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.util.Log;
import android.os.Process;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/** LSPosed entry point. Observes the single Transsion volume-dialog callback instance. */
public final class VolumeHudEntry implements IXposedHookLoadPackage {
    private static final String TAG = "VolumeHUD27";
    private static final String TARGET = "com.android.systemui";
    private static final String CONTROLLER = "com.android.systemui.volume.VolumeDialogControllerImpl";
    private static final String CALLBACK = "com.android.systemui.volume.TrVolumeDialogImpl$14";
    private static final String CALLBACKS = "com.android.systemui.plugins.VolumeDialogController$Callbacks";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final AtomicBoolean installed = new AtomicBoolean(false);
    private static final AtomicLong keyCallbackSequence = new AtomicLong();
    private static final AtomicLong lastRelayedSequence = new AtomicLong(-1L);
    private static final AtomicLong relayReceivedCount = new AtomicLong(0L);
    private static final AtomicLong toggleCount = new AtomicLong(0L);
    private static volatile boolean keyReceiverRegistered;
    private static volatile Object controller;
    private static volatile HudOverlay hud;

    @Override public void handleLoadPackage(XC_LoadPackage.LoadPackageParam p) {
        if (!TARGET.equals(p.packageName) || !installed.compareAndSet(false, true)) return;
        try {
            Class<?> c = XposedHelpers.findClass(CONTROLLER, p.classLoader);
            hookControllerRegistration(c);
            hookControllerMethods(c);
            hookTranssionCallback(p.classLoader);
            Log.i(TAG, "HOOK_INSTALLED side=systemui package=" + p.packageName
                    + " controller=" + CONTROLLER + " callback=" + CALLBACK
                    + " physicalToggleSource=system_server nativeDialog=enabled");
        } catch (Throwable t) {
            installed.set(false);
            Log.e(TAG, "hook setup failed; native volume dialog remains untouched", t);
        }
    }

    /** Capture the controller only when Transsion's own callback is registered. */
    private void hookControllerRegistration(Class<?> controllerClass) {
        for (Method m : controllerClass.getDeclaredMethods()) {
            if (!"addCallback".equals(m.getName()) || m.getParameterTypes().length != 2) continue;
            if (!CALLBACKS.equals(m.getParameterTypes()[0].getName())) continue;
            XposedBridge.hookMethod(m, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    Object cb = p.args == null ? null : p.args[0];
                    if (cb != null && CALLBACK.equals(cb.getClass().getName())) {
                        controller = p.thisObject;
                        Object raw = null;
                        try { raw = field(p.thisObject, "mContext"); } catch (Throwable ignored) { }
                        if (raw instanceof Context) registerPhysicalKeyReceiver((Context) raw);
                        Log.i(TAG, "Transsion callback registered; controller captured");
                    }
                }
            });
        }
    }

    /**
     * Hook only TrVolumeDialogImpl$14, rather than proxying every registered client.
     * This prevents other callback clients from toggling the HUD for the same key event.
     */
    private void hookTranssionCallback(ClassLoader loader) throws Exception {
        Class<?> callbackClass = XposedHelpers.findClass(CALLBACK, loader);
        boolean keyHooked = false;
        boolean stateHooked = false;
        for (Method m : callbackClass.getDeclaredMethods()) {
            Class<?>[] a = m.getParameterTypes();
            if ("onVolumeChangedFromKey".equals(m.getName()) && a.length == 0) {
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam p) {
                        long seq = keyCallbackSequence.incrementAndGet();
                        Log.i(TAG, "TRANSSION_KEY_STATE_CALLBACK seq=" + seq + " uptime="
                                + SystemClock.uptimeMillis() + " (state-only; no HUD toggle)");
                        Object owner = controller;
                        if (owner != null) MAIN.post(() -> refreshFromController(owner));
                    }
                });
                keyHooked = true;
            } else if ("onStateChanged".equals(m.getName()) && a.length == 1
                    && a[0].getName().equals("com.android.systemui.plugins.VolumeDialogController$State")) {
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam p) {
                        Object owner = controller;
                        if (owner != null) MAIN.post(() -> refreshFromController(owner));
                    }
                });
                stateHooked = true;
            }
        }
        if (!keyHooked || !stateHooked) {
            throw new NoSuchMethodException("Expected callback signatures missing: key="
                    + keyHooked + " state=" + stateHooked);
        }
    }

    /**
     * Receive only the explicit system_server relay. RECEIVER_NOT_EXPORTED is
     * intentional: the sender is UID 1000 (system_server), not an app process.
     */
    private static void registerPhysicalKeyReceiver(Context context) {
        if (keyReceiverRegistered || context == null) return;
        synchronized (VolumeHudEntry.class) {
            if (keyReceiverRegistered) return;
            try {
                Context appContext = context;
                IntentFilter filter = new IntentFilter(SystemServerKeyHook.ACTION_PHYSICAL_VOLUME_KEY);
                BroadcastReceiver receiver = new BroadcastReceiver() {
                    @Override public void onReceive(Context c, Intent intent) {
                        if (intent == null || !SystemServerKeyHook.ACTION_PHYSICAL_VOLUME_KEY.equals(intent.getAction())) return;
                        int senderUid = getSentFromUid();
                        if (senderUid != Process.SYSTEM_UID) {
                            Log.w(TAG, "RELAY_REJECTED senderUid=" + senderUid + " expected=" + Process.SYSTEM_UID);
                            return;
                        }
                        int key = intent.getIntExtra(SystemServerKeyHook.EXTRA_KEYCODE, 0);
                        if (key != android.view.KeyEvent.KEYCODE_VOLUME_UP
                                && key != android.view.KeyEvent.KEYCODE_VOLUME_DOWN) return;
                        long seq = intent.getLongExtra(SystemServerKeyHook.EXTRA_SEQUENCE, -1L);
                        if (seq < 0L) return;
                        for (;;) {
                            long prev = lastRelayedSequence.get();
                            if (seq <= prev) return;
                            if (lastRelayedSequence.compareAndSet(prev, seq)) break;
                        }
                        long received = relayReceivedCount.incrementAndGet();
                        long downTime = intent.getLongExtra(SystemServerKeyHook.EXTRA_DOWN_TIME, -1L);
                        long eventTime = intent.getLongExtra(SystemServerKeyHook.EXTRA_EVENT_TIME, -1L);
                        Log.i(TAG, "RELAY_RECEIVED count=" + received + " seq=" + seq
                                + " keyCode=" + key + " downTime=" + downTime + " eventTime=" + eventTime);
                        Object owner = controller;
                        if (owner != null) MAIN.post(() -> onPhysicalVolumeKey(owner, key, seq));
                        else Log.w(TAG, "RELAY_RECEIVED_NO_CONTROLLER seq=" + seq
                                + " native volume handling remains active");
                    }
                };
                // Android 15 supports this API. A non-exported dynamic receiver still
                // accepts broadcasts sent by the system UID, but not third-party apps.
                appContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
                keyReceiverRegistered = true;
                Log.i(TAG, "RECEIVER_REGISTERED action="
                        + SystemServerKeyHook.ACTION_PHYSICAL_VOLUME_KEY
                        + " exported=false sender=system_uid_only context=" + context.getClass().getName());
            } catch (Throwable t) {
                Log.e(TAG, "secure key receiver registration failed; native volume dialog remains fallback", t);
            }
        }
    }

    private void hookControllerMethods(Class<?> c) {
        for (Method m : c.getDeclaredMethods()) {
            Class<?>[] a = m.getParameterTypes();
            boolean exactSetVolume = "setStreamVolume".equals(m.getName())
                    && a.length == 2 && a[0] == int.class && a[1] == int.class;
            boolean exactSetActive = "setActiveStream".equals(m.getName())
                    && a.length == 1 && a[0] == int.class;
            if (!exactSetVolume && !exactSetActive) continue;
            XposedBridge.hookMethod(m, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) { controller = p.thisObject; }
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    Object owner = p.thisObject;
                    MAIN.post(() -> refreshFromController(owner));
                }
            });
        }
    }

    private static void onPhysicalVolumeKey(Object owner, int keyCode, long sequence) {
        controller = owner;
        if (hud == null) {
            try { hud = new HudOverlay(owner.getClass().getClassLoader(), owner); }
            catch (Throwable t) { Log.e(TAG, "HUD init failed; native dialog remains available", t); return; }
        }
        // The sole toggle source is the validated system_server relay.
        // Transsion's key callback is state-only; no callback-count debounce is used.
        try {
            hud.toggleOnPhysicalKey();
            long count = toggleCount.incrementAndGet();
            Log.i(TAG, "TOGGLE_COUNT count=" + count + " seq=" + sequence
                    + " keyCode=" + keyCode + " source=system_server_relay");
            refreshFromController(owner);
        } catch (Throwable t) {
            Log.e(TAG, "HUD_TOGGLE_FAILED seq=" + sequence
                    + "; native Transsion dialog remains enabled", t);
        }
    }

    private static void refreshFromController(Object owner) {
        if (owner == null) return;
        try {
            Object state = field(owner, "mState");
            int stream = intField(state, "activeStream", -1);
            if (stream < 0) return;
            Object states = field(state, "states");
            Object streamState = invoke(states, "get", new Class<?>[]{int.class}, stream);
            if (streamState == null) return;
            int level = intField(streamState, "level", 0);
            int min = intField(streamState, "levelMin", 0);
            int max = intField(streamState, "levelMax", 15);
            boolean muted = boolField(streamState, "muted", false);
            if (hud == null) hud = new HudOverlay(owner.getClass().getClassLoader(), owner);
            hud.update(stream, level, min, max, muted);
        } catch (Throwable t) {
            Log.w(TAG, "state read failed; awaiting next controller callback", t);
        }
    }

    /** Calls the original Transsion controller API; it queues the native audio update. */
    static boolean setVolume(Object owner, int stream, int level) {
        if (owner == null || stream < 0) return false;
        try {
            for (Class<?> k = owner.getClass(); k != null; k = k.getSuperclass()) {
                for (Method m : k.getDeclaredMethods()) {
                    Class<?>[] a = m.getParameterTypes();
                    if ("setStreamVolume".equals(m.getName()) && a.length == 2
                            && a[0] == int.class && a[1] == int.class) {
                        m.setAccessible(true);
                        m.invoke(owner, stream, level);
                        return true;
                    }
                }
            }
            Log.e(TAG, "exact setStreamVolume(int,int) method not found");
        } catch (Throwable t) {
            Log.e(TAG, "setStreamVolume invocation failed", t);
        }
        return false;
    }

    private static Object field(Object o, String name) throws Exception {
        for (Class<?> k = o.getClass(); k != null; k = k.getSuperclass()) {
            try { Field f = k.getDeclaredField(name); f.setAccessible(true); return f.get(o); }
            catch (NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException(name);
    }
    private static int intField(Object o, String n, int d) {
        try {
            for (Class<?> k=o.getClass(); k!=null; k=k.getSuperclass()) try {
                Field f=k.getDeclaredField(n); f.setAccessible(true); return f.getInt(o);
            } catch (NoSuchFieldException ignored) { }
        } catch (Throwable ignored) { }
        return d;
    }
    private static boolean boolField(Object o, String n, boolean d) {
        try {
            for (Class<?> k=o.getClass(); k!=null; k=k.getSuperclass()) try {
                Field f=k.getDeclaredField(n); f.setAccessible(true); return f.getBoolean(o);
            } catch (NoSuchFieldException ignored) { }
        } catch (Throwable ignored) { }
        return d;
    }
    private static Object invoke(Object o, String n, Class<?>[] types, Object... args) throws Exception {
        Method m=o.getClass().getMethod(n,types); m.setAccessible(true); return m.invoke(o,args);
    }
}
