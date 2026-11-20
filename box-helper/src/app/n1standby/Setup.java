package app.n1standby;

import android.util.Log;

import java.io.IOException;

/**
 * Configures the box for the helper, as root through the box's own adbd on 127.0.0.1.
 *
 * Nothing runs when an app is installed, and the accessibility service can't start until
 * it is enabled, so the first launch of the setup screen (or the boot receiver later) does
 * the configuration here. Every step is idempotent. This is the single place that knows
 * how to set the box up; scripts/box.sh only installs the APK and opens the setup screen.
 */
final class Setup {

    private static final String TAG = "N1Standby";

    static final String SVC = "app.n1standby/app.n1standby.StandbyService";
    private static final String KL = "/system/usr/keylayout/Vendor_7045_Product_1820.kl";
    private static final String BAK = "/data/local/tmp/btbak";
    private static final String MARK = "/dev/.n1remote_standby";

    /** Enable our accessibility service and stop the native power key from shutting down. */
    private static final String ENABLE =
            "cur=$(settings get secure enabled_accessibility_services); "
            + "case \"$cur\" in *" + SVC + "*) ;; "
            + "null|'') settings put secure enabled_accessibility_services " + SVC + " ;; "
            + "*) settings put secure enabled_accessibility_services \"$cur:" + SVC + "\" ;; esac; "
            + "settings put secure accessibility_enabled 1; "
            + "settings put system power_key_definition 0; ";

    /** Phicomm remote: map its power key (KEY_POWER) to TV so the helper gets it. */
    private static final String PATCH_REMOTE =
            "if grep -q '^key 116 *POWER$' " + KL + " 2>/dev/null; then "
            + "mount -o remount,rw /system 2>/dev/null; mkdir -p " + BAK + "; "
            + "[ -f " + BAK + "/Vendor_7045_Product_1820.kl.orig ] || cp " + KL + " " + BAK + "/Vendor_7045_Product_1820.kl.orig; "
            + "sed 's/^key 116\\( *\\)POWER$/key 116\\1TV/' " + KL + " > " + BAK + "/patched.kl && "
            + "grep -q '^key 116 *TV$' " + BAK + "/patched.kl && cat " + BAK + "/patched.kl > " + KL + "; "
            + "rm -f " + BAK + "/patched.kl; sync; fi; ";

    private static final String UNPATCH_REMOTE =
            "if grep -q '^key 116 *TV$' " + KL + " 2>/dev/null; then "
            + "mount -o remount,rw /system 2>/dev/null; "
            + "if [ -f " + BAK + "/Vendor_7045_Product_1820.kl.orig ]; then cat " + BAK + "/Vendor_7045_Product_1820.kl.orig > " + KL + "; "
            + "else sed 's/^key 116\\( *\\)TV$/key 116\\1POWER/' " + KL + " > " + BAK + "/kl.tmp && cat " + BAK + "/kl.tmp > " + KL + "; rm -f " + BAK + "/kl.tmp; fi; "
            + "sync; fi; ";

    /** Back to factory: helper disabled, power key shuts down, Phicomm remote untouched. */
    private static final String RESTORE =
            "cur=$(settings get secure enabled_accessibility_services); "
            + "new=$(echo \"$cur\" | sed -e 's#" + SVC + "##' -e 's/::*/:/g' -e 's/^://' -e 's/:$//' -e 's/^null$//'); "
            + "if [ -n \"$new\" ]; then settings put secure enabled_accessibility_services \"$new\"; "
            + "else settings delete secure enabled_accessibility_services; settings put secure accessibility_enabled 0; fi; "
            + "settings put system power_key_definition 1; " + UNPATCH_REMOTE;

    private static final String STATUS =
            "echo acc=$(settings get secure enabled_accessibility_services); "
            + "echo pkd=$(settings get system power_key_definition); "
            + "if [ -f " + KL + " ]; then echo kl=$(grep '^key 116' " + KL + " | tr -s ' ' | cut -d' ' -f3); else echo kl=none; fi; "
            + "[ -e " + MARK + " ] && echo state=standby || echo state=awake; "
            + "echo fw=$(getprop ro.build.display.id)";

    /** What the box looks like right now; adbOk false means none of the rest is known. */
    static final class Status {
        boolean adbOk, accessibility, powerKeySafe, standby;
        String remote = "none"; // "TV" (patched), "POWER" (original) or "none" (no such layout)
        String firmware = "";

        boolean ready() {
            return adbOk && accessibility && powerKeySafe;
        }

        static Status parse(String out) {
            Status s = new Status();
            if (out == null) return s;
            s.adbOk = true;
            for (String line : out.split("\n")) {
                line = line.trim();
                if (line.startsWith("acc=")) s.accessibility = line.contains(SVC);
                else if (line.startsWith("pkd=")) s.powerKeySafe = "0".equals(line.substring(4));
                else if (line.startsWith("kl=")) s.remote = line.substring(3).isEmpty() ? "none" : line.substring(3);
                else if (line.startsWith("state=")) s.standby = line.endsWith("standby");
                else if (line.startsWith("fw=")) s.firmware = line.substring(3);
            }
            return s;
        }
    }

    /** Full setup: enable the helper, make the power key safe, optionally patch the remote. */
    static Status apply(boolean patchRemote) {
        return Status.parse(run(ENABLE + (patchRemote ? PATCH_REMOTE : "") + STATUS));
    }

    static Status setRemotePatched(boolean patched) {
        return Status.parse(run((patched ? PATCH_REMOTE : UNPATCH_REMOTE) + STATUS));
    }

    static Status status() {
        return Status.parse(run(STATUS));
    }

    /** Restores factory settings and uninstalls the helper (this process dies with it). */
    static void restoreAndUninstall() {
        run(RESTORE + "pm uninstall app.n1standby");
    }

    /** adbd serves one TCP client at a time, so retry if something else holds it briefly. */
    static String run(String command) {
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                return BoxShell.run("127.0.0.1", command, 3000, 30000);
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

    private Setup() {
    }
}
