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

    /**
     * Phicomm remote: map its power key (KEY_POWER, 116) to TV so the helper gets it, and its
     * voice key (194, VOICE_ASSIST, which nothing on the box handles) to play / pause, like the
     * phone app. The factory file is kept the first time; the patch is checked before it lands.
     */
    private static final String PATCH_PHICOMM =
            "if grep -q -E '^key (116 *POWER|194 *VOICE_ASSIST)$' " + KL + " 2>/dev/null; then "
            + "mount -o remount,rw /system 2>/dev/null; mkdir -p " + BAK + "; "
            + "[ -f " + BAK + "/Vendor_7045_Product_1820.kl.orig ] || cp " + KL + " " + BAK + "/Vendor_7045_Product_1820.kl.orig; "
            + "sed -e 's/^key 116\\( *\\)POWER$/key 116\\1TV/' "
            + "-e 's/^key 194\\( *\\)VOICE_ASSIST$/key 194\\1MEDIA_PLAY_PAUSE/' " + KL + " > " + BAK + "/patched.kl && "
            + "grep -q '^key 116 *TV$' " + BAK + "/patched.kl && cat " + BAK + "/patched.kl > " + KL + "; "
            + "rm -f " + BAK + "/patched.kl; sync; fi; ";

    private static final String UNPATCH_PHICOMM =
            "if grep -q -E '^key (116 *TV|194 *MEDIA_PLAY_PAUSE)$' " + KL + " 2>/dev/null; then "
            + "mount -o remount,rw /system 2>/dev/null; "
            + "if [ -f " + BAK + "/Vendor_7045_Product_1820.kl.orig ]; then cat " + BAK + "/Vendor_7045_Product_1820.kl.orig > " + KL + "; "
            + "else sed -e 's/^key 116\\( *\\)TV$/key 116\\1POWER/' -e 's/^key 194\\( *\\)MEDIA_PLAY_PAUSE$/key 194\\1VOICE_ASSIST/' "
            + KL + " > " + BAK + "/kl.tmp && cat " + BAK + "/kl.tmp > " + KL + "; rm -f " + BAK + "/kl.tmp; fi; "
            + "sync; fi; ";

    /**
     * Xiaomi Bluetooth voice remote (2717:32b8), often used with TV boxes. The firmware has no
     * layout for it, so Generic.kl applies and three keys go wrong: power does nothing here
     * (power_key_definition is 0), Home arrives as MOVE_HOME (the remote sends keyboard-page
     * usages) and the voice key as F5 (its mic uses Xiaomi's own protocol over hidraw, which
     * Android does not see). Our layout is Generic.kl with those three changed; its first line
     * marks it as ours, so undoing never removes a layout someone else put there.
     */
    private static final String XM_KL = "/system/usr/keylayout/Vendor_2717_Product_32b8.kl";
    private static final String GENERIC_KL = "/system/usr/keylayout/Generic.kl";
    private static final String XM_MARK = "made from Generic.kl";

    private static final String PATCH_XIAOMI =
            "if [ ! -e " + XM_KL + " ] && grep -q '^key 102 *MOVE_HOME$' " + GENERIC_KL + " 2>/dev/null; then "
            + "mount -o remount,rw /system 2>/dev/null; mkdir -p " + BAK + "; "
            + "{ echo '# Xiaomi Bluetooth voice remote (2717:32b8) on the Phicomm N1, " + XM_MARK + " by the N1 standby helper.'; "
            + "echo '# Changed: power -> TV (standby), Home -> HOME (Generic.kl has MOVE_HOME), voice (F5) -> play/pause.'; "
            + "sed -e 's/^key 116\\( *\\)POWER$/key 116\\1TV/' -e 's/^key 102\\( *\\)MOVE_HOME$/key 102\\1HOME/' "
            + "-e 's/^key 63\\( *\\)F5$/key 63\\1MEDIA_PLAY_PAUSE/' " + GENERIC_KL + "; } > " + BAK + "/xm.kl; "
            + "[ \"$(grep -c -E '^key (116 +TV|102 +HOME|63 +MEDIA_PLAY_PAUSE)$' " + BAK + "/xm.kl)\" = 3 ] "
            + "&& cat " + BAK + "/xm.kl > " + XM_KL + " && chmod 644 " + XM_KL + "; "
            + "rm -f " + BAK + "/xm.kl; sync; fi; ";

    private static final String UNPATCH_XIAOMI =
            "if grep -q '" + XM_MARK + "' " + XM_KL + " 2>/dev/null; then "
            + "mount -o remount,rw /system 2>/dev/null; rm -f " + XM_KL + "; sync; fi; ";

    /**
     * Every remote without a layout of its own uses Generic.kl, and its power key does nothing
     * on the N1 (power_key_definition is 0). Mapping power to TV there hands it to us, so any
     * such Bluetooth remote can toggle standby. cec_input uses Generic.kl as well and sends that
     * key when the TV goes to standby; StandbyService ignores it from there. Generic.kl matters
     * to every such device, so the new file is checked (same length, only that line changed)
     * and moved into place in one step; the original is kept once in BAK.
     */
    private static final String GENERIC_TMP = GENERIC_KL + ".n1tmp";

    private static final String PATCH_GENERIC =
            "if grep -q '^key 116 *POWER$' " + GENERIC_KL + " 2>/dev/null; then "
            + "mount -o remount,rw /system 2>/dev/null; mkdir -p " + BAK + "; "
            + "[ -f " + BAK + "/Generic.kl.orig ] || cp " + GENERIC_KL + " " + BAK + "/Generic.kl.orig; "
            + "sed 's/^key 116\\( *\\)POWER$/key 116\\1TV/' " + GENERIC_KL + " > " + GENERIC_TMP + "; "
            + "if [ \"$(wc -l < " + GENERIC_TMP + ")\" = \"$(wc -l < " + GENERIC_KL + ")\" ] "
            + "&& [ \"$(grep -c '^key 116 *TV$' " + GENERIC_TMP + ")\" = 1 ]; then "
            + "chmod 644 " + GENERIC_TMP + " && mv " + GENERIC_TMP + " " + GENERIC_KL + "; "
            + "else rm -f " + GENERIC_TMP + "; fi; sync; fi; ";

    private static final String UNPATCH_GENERIC =
            "if grep -q '^key 116 *TV$' " + GENERIC_KL + " 2>/dev/null; then "
            + "mount -o remount,rw /system 2>/dev/null; "
            + "if [ -f " + BAK + "/Generic.kl.orig ]; then cp " + BAK + "/Generic.kl.orig " + GENERIC_TMP + "; "
            + "else sed 's/^key 116\\( *\\)TV$/key 116\\1POWER/' " + GENERIC_KL + " > " + GENERIC_TMP + "; fi; "
            + "if grep -q '^key 116 *POWER$' " + GENERIC_TMP + "; then "
            + "chmod 644 " + GENERIC_TMP + " && mv " + GENERIC_TMP + " " + GENERIC_KL + "; "
            + "else rm -f " + GENERIC_TMP + "; fi; sync; fi; ";

    /** "Remap the remotes": the Phicomm remote, Generic.kl and the Xiaomi remote. */
    private static final String PATCH_REMOTE = PATCH_PHICOMM + PATCH_GENERIC + PATCH_XIAOMI;
    private static final String UNPATCH_REMOTE = UNPATCH_PHICOMM + UNPATCH_XIAOMI + UNPATCH_GENERIC;

    /** Back to factory: helper disabled, power key shuts down, remote layouts as they came. */
    private static final String RESTORE =
            "cur=$(settings get secure enabled_accessibility_services); "
            + "new=$(echo \"$cur\" | sed -e 's#" + SVC + "##' -e 's/::*/:/g' -e 's/^://' -e 's/:$//' -e 's/^null$//'); "
            + "if [ -n \"$new\" ]; then settings put secure enabled_accessibility_services \"$new\"; "
            + "else settings delete secure enabled_accessibility_services; settings put secure accessibility_enabled 0; fi; "
            + "settings put system power_key_definition 1; " + UNPATCH_REMOTE;

    private static final String STATUS =
            "echo acc=$(settings get secure enabled_accessibility_services); "
            + "echo pkd=$(settings get system power_key_definition); "
            // kl=TV only when the Phicomm keys are remapped and the Xiaomi layout is in place (when
            // this firmware's Generic.kl needs it); POWER = factory or half done; empty = no layout
            + "if [ -f " + KL + " ]; then p=$(grep '^key 116' " + KL + " | tr -s ' ' | cut -d' ' -f3); "
            + "v=$(grep '^key 194' " + KL + " | tr -s ' ' | cut -d' ' -f3); "
            + "if [ \"$p\" = TV ] && [ \"$v\" != VOICE_ASSIST ] && grep -q '^key 116 *TV$' " + GENERIC_KL + " && { [ -e " + XM_KL + " ] "
            + "|| ! grep -q '^key 102 *MOVE_HOME$' " + GENERIC_KL + "; }; then echo kl=TV; else echo kl=${p:+POWER}; fi; "
            + "else echo kl=none; fi; "
            + "[ -e " + MARK + " ] && echo state=standby || echo state=awake; "
            + "echo fw=$(getprop ro.build.display.id)";

    /** What the box looks like right now; adbOk false means none of the rest is known. */
    static final class Status {
        boolean adbOk, accessibility, powerKeySafe, standby;
        String remote = "none"; // "TV" (remotes remapped), "POWER" (not, or only partly) or "none" (no Phicomm layout)
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
