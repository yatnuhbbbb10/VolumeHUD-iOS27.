package id.volumehud.ios27;

import android.content.Context;
import android.content.Intent;
import android.os.UserHandle;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.view.KeyEvent;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicLong;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Passive system_server observer. It never edits the KeyEvent, hook arguments,
 * method result, or policy flags. Only trusted, first ACTION_DOWN events are relayed.
 */
public final class SystemServerKeyHook implements IXposedHookLoadPackage {
    private static final String TAG = "VolumeHUD27";
    // Legacy Xposed API 82 reports system_server as packageName "android".
    // In LSPosed Manager, select the scope labelled "System" for this process.
    private static final String SYSTEM_SERVER = "android";
    private static final String POLICY = "com.android.server.policy.PhoneWindowManager";
    private static final String POLICY_INTERFACE = "com.android.server.policy.WindowManagerPolicy";
    static final String ACTION_PHYSICAL_VOLUME_KEY = "id.volumehud.ios27.action.PHYSICAL_VOLUME_KEY";
    static final String EXTRA_KEYCODE = "keycode";
    static final String EXTRA_DOWN_TIME = "down_time";
    static final String EXTRA_EVENT_TIME = "event_time";
    static final String EXTRA_SEQUENCE = "sequence";

    private static final AtomicLong sequence = new AtomicLong(0L);
    private static final long[] lastDownTime = new long[] {-1L, -1L}; // volume-up, volume-down
    private static volatile boolean installed;
    private static volatile int injectedMask;
    private static volatile int virtualMask;
    private static HandlerThread relayThread;
    private static Handler relayHandler;

    @Override public void handleLoadPackage(XC_LoadPackage.LoadPackageParam p) {
        if (!SYSTEM_SERVER.equals(p.packageName) || installed) return;
        synchronized (SystemServerKeyHook.class) {
            if (installed) return;
            try {
                Class<?> policyClass = XposedHelpers.findClass(POLICY, p.classLoader);
                Class<?> keyEventClass = KeyEvent.class;
                Method target = policyClass.getDeclaredMethod("interceptKeyBeforeQueueing",
                        keyEventClass, int.class);
                if (target.getReturnType() != int.class) {
                    throw new NoSuchMethodException("Unexpected interceptKeyBeforeQueueing return type");
                }
                Class<?> policyInterface = XposedHelpers.findClass(POLICY_INTERFACE, p.classLoader);
                injectedMask = XposedHelpers.getStaticIntField(policyInterface, "FLAG_INJECTED");
                virtualMask = XposedHelpers.getStaticIntField(policyInterface, "FLAG_VIRTUAL");
                if (injectedMask == 0 || virtualMask == 0) {
                    throw new IllegalStateException("FLAG_INJECTED/FLAG_VIRTUAL unavailable; refusing observer install");
                }
                relayThread = new HandlerThread("VolumeHUD27-relay");
                relayThread.start();
                relayHandler = new Handler(relayThread.getLooper());
                target.setAccessible(true);
                XposedBridge.hookMethod(target, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        // Do not relay if the original policy method failed.
                        if (param.hasThrowable() || param.args == null || param.args.length != 2) return;
                        try {
                            KeyEvent event = (KeyEvent) param.args[0];
                            int flags = (Integer) param.args[1];
                            if (!isEligible(event, flags)) return;
                            Object rawContext = XposedHelpers.getObjectField(param.thisObject, "mContext");
                            if (!(rawContext instanceof Context)) return;
                            final Context context = (Context) rawContext;
                            final int keyCode = event.getKeyCode();
                            final long downTime = event.getDownTime();
                            final long eventTime = event.getEventTime();
                            final int deviceId = event.getDeviceId();
                            Handler handler = relayHandler;
                            if (handler == null) return;
                            // Never perform Binder broadcast work on the input-policy thread.
                            if (!handler.post(() -> relay(context, keyCode, downTime, eventTime, deviceId))) {
                                Log.w(TAG, "RELAY_QUEUE_REJECTED keyCode=" + keyCode + " downTime=" + downTime);
                            }
                        } catch (Throwable t) {
                            // Strictly fail-open: diagnostic relay must never affect key policy.
                            Log.w(TAG, "system_server key observation failed; input left untouched", t);
                        }
                    }
                });
                installed = true;
                Log.i(TAG, "HOOK_INSTALLED side=system_server package=" + p.packageName
                        + " process=" + p.processName
                        + " class=" + POLICY
                        + " method=interceptKeyBeforeQueueing(KeyEvent,int)"
                        + " FLAG_INJECTED=0x" + Integer.toHexString(injectedMask)
                        + " FLAG_VIRTUAL=0x" + Integer.toHexString(virtualMask)
                        + " relayThread=" + relayThread.getName());
            } catch (Throwable t) {
                Log.e(TAG, "system_server hook unavailable; Transsion volume handling is untouched", t);
            }
        }
    }

    private static boolean isEligible(KeyEvent e, int policyFlags) {
        if (e == null) return false;
        int key = e.getKeyCode();
        if (key != KeyEvent.KEYCODE_VOLUME_UP && key != KeyEvent.KEYCODE_VOLUME_DOWN) return false;
        if (e.getAction() != KeyEvent.ACTION_DOWN || e.getRepeatCount() != 0
                || e.isCanceled() || (e.getFlags() & KeyEvent.FLAG_FALLBACK) != 0
                || (e.getFlags() & KeyEvent.FLAG_VIRTUAL_HARD_KEY) != 0
                || (policyFlags & injectedMask) != 0
                || (policyFlags & virtualMask) != 0) {
            return false;
        }
        // Avoid InputDevice.getDevice() here: it may perform Binder work on the
        // latency-sensitive input-policy thread. Policy + KeyEvent virtual flags
        // are the non-blocking discriminator; deviceId is logged for OEM diagnosis.
        return true;
    }

    private static void relay(Context systemContext, int keyCode, long downTime, long eventTime, int deviceId) {
        final int slot = keyCode == KeyEvent.KEYCODE_VOLUME_UP ? 0 : 1;
        synchronized (lastDownTime) {
            // Defensive duplicate suppression for OEM policy wrappers that might invoke
            // the same initial DOWN twice. Real subsequent presses have a new downTime.
            if (lastDownTime[slot] == downTime) return;
            lastDownTime[slot] = downTime;
        }

        Intent intent = new Intent(ACTION_PHYSICAL_VOLUME_KEY)
                .setPackage("com.android.systemui")
                .addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY)
                .putExtra(EXTRA_KEYCODE, keyCode)
                .putExtra(EXTRA_DOWN_TIME, downTime)
                .putExtra(EXTRA_EVENT_TIME, eventTime);
        // SystemUI registers this as RECEIVER_NOT_EXPORTED. Android's system UID
        // can deliver to it; ordinary third-party UIDs cannot forge the toggle.
        long seq = sequence.incrementAndGet();
        intent.putExtra(EXTRA_SEQUENCE, seq);
        try {
            systemContext.sendBroadcastAsUser(intent, UserHandle.CURRENT);
            Log.i(TAG, "RELAY_SENT seq=" + seq + " keyCode=" + keyCode
                    + " downTime=" + downTime + " eventTime=" + eventTime
                    + " deviceId=" + deviceId + " target=com.android.systemui user=CURRENT"
                    + " senderContextUser=" + systemContext.getUserId());
        } catch (Throwable t) {
            Log.e(TAG, "RELAY_FAILED seq=" + seq + " keyCode=" + keyCode
                    + " deviceId=" + deviceId + "; native key handling remains untouched", t);
        }
    }
}
