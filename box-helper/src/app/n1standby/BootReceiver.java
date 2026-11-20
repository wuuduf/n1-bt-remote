package app.n1standby;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * After a reboot or an app update, quietly makes sure the helper is still set up (for
 * example if a settings reset turned the accessibility service off). Leaves the Phicomm
 * remote layout as it is; that is the user's choice on the setup screen.
 *
 * On this firmware BOOT_COMPLETED never reaches non-system apps (ActivityManagerService drops
 * them unless listed in config_boot_completed_white_list, which is empty), so the boot check
 * actually runs off MEDIA_MOUNTED, sent when storage mounts at boot (and for USB drives).
 */
public final class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        final PendingResult pending = goAsync();
        new Thread(() -> {
            try {
                for (int attempt = 0; attempt < 6; attempt++) { // adbd may still be starting
                    Setup.Status s = Setup.status();
                    if (s.adbOk) {
                        if (!s.ready()) s = Setup.apply(false);
                        Log.i("N1Standby", "boot check (" + intent.getAction() + "): ready=" + s.ready());
                        return;
                    }
                    Thread.sleep(5000);
                }
                Log.w("N1Standby", "boot check: local adb unreachable");
            } catch (InterruptedException ignored) {
            } finally {
                pending.finish();
            }
        }).start();
    }
}
