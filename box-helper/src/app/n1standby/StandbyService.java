package app.n1standby;

import android.accessibilityservice.AccessibilityService;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Soft power for the Phicomm N1, driven by the phone remote over Bluetooth.
 *
 * The firmware removed Android sleep, and a real shutdown can only be undone by
 * unplugging the box. So the phone's power button sends KEYCODE_TV, and this service
 * catches it and fakes a standby as root through the box's own adbd on 127.0.0.1:
 * go home, blank the screen, switch off the HDMI signal and ask the TV to sleep over
 * CEC. While in standby every other key is swallowed. A marker on tmpfs holds the
 * state, so a reboot always comes back awake.
 */
public final class StandbyService extends AccessibilityService {

    private static final String TAG = "N1Standby";

    /** What the phone remote's power button sends (HID Consumer 0x89, KEY_TV). */
    static final int TOGGLE_KEY = KeyEvent.KEYCODE_TV;
    /** Virtual remote that tools/selftest.py registers; its keys get logged for the test. */
    static final String SELFTEST_DEVICE = "N1 SelfTest";

    /** What the phone remote's TV button sends (HID Consumer 0x8D, KEY_PROGRAM). */
    static final int TV_POWER_KEY = KeyEvent.KEYCODE_GUIDE;

    private static final String MARK = "/dev/.n1remote_standby";
    private static final String CPU = "/sys/devices/system/cpu/cpu0/cpufreq/scaling_max_freq";
    private static final String CPU_SAVED = "/dev/.n1remote_maxfreq";
    /** CPU ceiling while in standby; the box has 100/250/500/667/1000/1200/1512 MHz steps. */
    private static final String STANDBY_MAX_KHZ = "500000";

    private static final String TV_ON =
            "printf '\\100\\004' > /dev/cec 2>/dev/null; "                // CEC Image View On -> TV
            + "PA=$(cat /sys/class/cec/physical_addr); "
            + "printf \"\\117\\202\\x${PA%??}\\x${PA#??}\" > /dev/cec 2>/dev/null; " // CEC Active Source
            + "echo tv-on";
    private static final String TV_OFF =
            "printf '\\100\\066' > /dev/cec 2>/dev/null; echo tv-off";       // CEC Standby -> TV

    private static final String WAKE =
            "[ -f " + CPU_SAVED + " ] && cat " + CPU_SAVED + " > " + CPU + "; rm -f " + CPU_SAVED + "; "
            + "echo 1 > /sys/class/amhdmitx/amhdmitx0/phy; "
            + "echo 0 > /sys/class/graphics/fb0/blank; "
            + "printf '\\100\\004' > /dev/cec 2>/dev/null; "                // CEC Image View On -> TV
            + "PA=$(cat /sys/class/cec/physical_addr); "
            + "printf \"\\117\\202\\x${PA%??}\\x${PA#??}\" > /dev/cec 2>/dev/null; " // CEC Active Source
            + "rm -f " + MARK + "; echo \"awake max=$(cat " + CPU + ")\"";
    private static final String SLEEP =
            "input keyevent HOME; "
            + "echo 1 > /sys/class/graphics/fb0/blank; "
            + "echo 0 > /sys/class/amhdmitx/amhdmitx0/phy; "
            + "printf '\\100\\066' > /dev/cec 2>/dev/null; "                // CEC Standby -> TV
            + "cat " + CPU + " > " + CPU_SAVED + "; echo " + STANDBY_MAX_KHZ + " > " + CPU + "; "
            + "touch " + MARK + "; echo \"standby max=$(cat " + CPU + ")\"";
    private static final String TOGGLE = "if [ -e " + MARK + " ]; then " + WAKE + "; else " + SLEEP + "; fi";
    private static final String QUERY = "[ -e " + MARK + " ] && echo standby || echo awake";

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean standby;
    private boolean busy;
    /** Best guess of the TV's power: CEC answers can't be read back from the shell. */
    private boolean tvOn = true;

    @Override
    protected void onServiceConnected() {
        io.execute(() -> {
            String out = sh(QUERY);
            main.post(() -> {
                if (out != null) standby = out.contains("standby");
                Log.i(TAG, "connected, standby=" + standby);
            });
        });
    }

    @Override
    public boolean onKeyEvent(KeyEvent e) {
        if (e.getDevice() != null && SELFTEST_DEVICE.equals(e.getDevice().getName())) {
            Log.i(TAG, "selftest key code=" + e.getKeyCode() + " action=" + e.getAction()
                    + " repeat=" + e.getRepeatCount() + " standby=" + standby);
        }
        if (e.getKeyCode() == TOGGLE_KEY) {
            if (e.getAction() == KeyEvent.ACTION_UP && !busy) toggle();
            return true;
        }
        if (e.getKeyCode() == TV_POWER_KEY) {
            if (e.getAction() == KeyEvent.ACTION_UP && !busy) toggleTv();
            return true;
        }
        return standby; // in standby nothing else gets through
    }

    private void toggle() {
        busy = true;
        Log.i(TAG, "power key, standby=" + standby);
        io.execute(() -> {
            String out = sh(TOGGLE);
            main.post(() -> {
                busy = false;
                if (out != null) {
                    standby = out.contains("standby");
                    tvOn = !standby;
                }
                Log.i(TAG, "toggled -> " + (out == null ? "adb unreachable" : out.trim()));
            });
        });
    }

    /** adbd serves one TCP client at a time, so retry if something else holds it briefly. */
    /** Turns only the TV on or off over HDMI-CEC; the box itself stays as it is. */
    private void toggleTv() {
        busy = true;
        final boolean turnOn = !tvOn;
        Log.i(TAG, "tv key, tvOn=" + tvOn);
        io.execute(() -> {
            String out = sh(turnOn ? TV_ON : TV_OFF);
            main.post(() -> {
                busy = false;
                if (out != null) tvOn = turnOn;
                Log.i(TAG, "tv -> " + (out == null ? "adb unreachable" : out.trim()));
            });
        });
    }

    private static String sh(String command) {
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                return BoxShell.run("127.0.0.1", command, 3000);
            } catch (IOException e) {
                Log.w(TAG, "local adb attempt " + attempt + " failed: " + e);
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ie) {
                    return null;
                }
            }
        }
        return null;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public void onDestroy() {
        io.shutdownNow();
        super.onDestroy();
    }
}
