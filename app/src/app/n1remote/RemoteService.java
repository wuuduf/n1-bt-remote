package app.n1remote;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Log;

/**
 * Owns the Bluetooth HID remote so the link can outlive the screen.
 *
 * Android 13's HidDeviceService unregisters a HID app as soon as its process drops below
 * foreground-service importance, which used to cut the link whenever the app left the screen.
 * So while the box is connected, or a dropped link is being brought back, this runs as a
 * foreground service with a notification: the remote stays connected in the background and
 * with the phone locked, and the notification can put the box in standby or disconnect.
 * Otherwise it only lives while the remote screen is bound to it.
 */
public final class RemoteService extends Service implements HidRemote.Listener {

    static final String ACTION_POWER = "app.n1remote.action.POWER";
    static final String ACTION_DISCONNECT = "app.n1remote.action.DISCONNECT";
    private static final String CHANNEL = "link";
    private static final int NOTIFICATION_ID = 1;
    private static final String TAG = "N1Remote";

    final class LocalBinder extends Binder {
        RemoteService service() {
            return RemoteService.this;
        }
    }

    private final IBinder binder = new LocalBinder();
    private BluetoothAdapter adapter;
    private HidRemote remote;
    private HidRemote.Listener ui;
    private boolean receiverRegistered, foreground;
    private int state = HidRemote.ST_IDLE;
    private String text = "";
    /** Shown in the notification for a few seconds after a button tap, instead of the status. */
    private String note;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable clearNote = () -> {
        note = null;
        refreshNotification();
    };

    @Override
    public void onCreate() {
        super.onCreate();
        BluetoothManager bm = getSystemService(BluetoothManager.class);
        adapter = bm == null ? null : bm.getAdapter();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    /** Notification buttons. */
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_POWER.equals(action)) {
            if (remote != null && remote.isConnected()) {
                remote.press(HidRemote.KEY_STANDBY);
                remote.release(HidRemote.KEY_STANDBY); // the box helper acts on key up
                buzz();
                // The phone can't see whether the box is in standby, so confirm the send instead.
                note = getString(R.string.notif_sent_power);
                refreshNotification();
                main.removeCallbacks(clearNote);
                main.postDelayed(clearNote, 3000);
            }
        } else if (ACTION_DISCONNECT.equals(action)) {
            if (remote != null) {
                buzz();
                remote.disconnect(); // the notification goes away once the link is down
            }
        }
        if (!foreground) stopSelf(startId); // only a live link keeps the service started
        return START_NOT_STICKY;
    }

    /** Status updates for the remote screen; null when it goes away. */
    void setUi(HidRemote.Listener l) {
        ui = l;
        if (l != null) l.onStatus(state, text);
    }

    HidRemote remote() {
        return remote;
    }

    /**
     * The remote screen is showing: start the HID app (connecting to {@code saved} if it is
     * paired), or register it again if Android dropped it while the app was in the background.
     */
    void onUiVisible(BluetoothDevice saved) {
        if (adapter == null) return;
        if (remote != null) {
            refreshNotification();
            remote.resume();
            return;
        }
        if (!receiverRegistered) {
            IntentFilter f = new IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(bondReceiver, f, Context.RECEIVER_EXPORTED);
            else registerReceiver(bondReceiver, f);
            receiverRegistered = true;
        }
        remote = new HidRemote(this, adapter, getMainExecutor(), this);
        if (saved != null && saved.getBondState() == BluetoothDevice.BOND_BONDED) {
            remote.connect(saved); // connects once the HID app is registered
        }
        remote.start();
    }

    private final BroadcastReceiver bondReceiver = new BroadcastReceiver() {
        @Override
        @SuppressWarnings("deprecation")
        public void onReceive(Context context, Intent intent) {
            BluetoothDevice d = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
            int bond = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR);
            if (d != null && remote != null) remote.onBondStateChanged(d, bond);
        }
    };

    @Override
    public void onStatus(int state, String text) {
        this.state = state;
        this.text = text;
        Log.i(TAG, "status " + state + ": " + text);
        if (ui != null) ui.onStatus(state, text);
        // In the foreground while connected, and while bringing a dropped link back.
        if (state == HidRemote.ST_CONNECTED || (foreground && state == HidRemote.ST_BUSY)) {
            showForeground();
        } else if (foreground) {
            leaveForeground();
        }
    }

    private void showForeground() {
        Notification n = notification();
        if (foreground) {
            getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, n);
            return;
        }
        try {
            // Started, not just bound, so it keeps running after the remote screen closes.
            startService(new Intent(this, RemoteService.class));
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
            } else {
                startForeground(NOTIFICATION_ID, n);
            }
            foreground = true;
        } catch (RuntimeException e) {
            Log.w(TAG, "cannot keep the link in the background", e);
        }
    }

    /**
     * Posts the notification again. The app usually connects within a second or two of its
     * first start, before the user has answered the notification permission prompt, and a
     * notification posted without the permission is dropped for good, not held back.
     */
    void refreshNotification() {
        if (foreground) getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, notification());
    }

    private void leaveForeground() {
        stopForeground(STOP_FOREGROUND_REMOVE);
        foreground = false;
        stopSelf(); // stays alive while the remote screen is bound
    }

    private Notification notification() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm.getNotificationChannel(CHANNEL) == null) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
        Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        Notification.Builder b = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(note != null ? note : text)
                .setContentIntent(PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setVisibility(Notification.VISIBILITY_PUBLIC);
        if (Build.VERSION.SDK_INT >= 31) b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        if (state == HidRemote.ST_CONNECTED) b.addAction(action(ACTION_POWER, R.drawable.ic_power, R.string.notif_power));
        b.addAction(action(ACTION_DISCONNECT, R.drawable.ic_remove, R.string.disconnect));
        return b.build();
    }

    private Notification.Action action(String what, int icon, int label) {
        Intent i = new Intent(this, RemoteService.class).setAction(what);
        PendingIntent pi = PendingIntent.getService(
                this, what.hashCode(), i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Action.Builder(Icon.createWithResource(this, icon), getString(label), pi).build();
    }

    /** A tap you can feel: the notification shade gives no feedback of its own. */
    private void buzz() {
        Vibrator v = getSystemService(Vibrator.class);
        if (v == null || !v.hasVibrator()) return;
        v.vibrate(Build.VERSION.SDK_INT >= 29
                ? VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK)
                : VibrationEffect.createOneShot(30, VibrationEffect.DEFAULT_AMPLITUDE));
    }

    @Override
    public void onDestroy() {
        main.removeCallbacks(clearNote);
        if (remote != null) remote.stop();
        remote = null;
        if (receiverRegistered) unregisterReceiver(bondReceiver);
        receiverRegistered = false;
        if (foreground) stopForeground(STOP_FOREGROUND_REMOVE);
        foreground = false;
        super.onDestroy();
    }
}
