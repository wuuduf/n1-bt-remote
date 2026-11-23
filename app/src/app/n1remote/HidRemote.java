package app.n1remote;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHidDevice;
import android.bluetooth.BluetoothHidDeviceAppSdpSettings;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import java.util.concurrent.Executor;

/**
 * Makes the phone a Bluetooth HID remote control (BluetoothHidDevice, Android 9+).
 *
 * The report is a 14-bit Consumer Control bitmap, one bit per key. Verified on the
 * Phicomm N1 (Android 7.1.2, kernel 3.14) through /dev/uhid: each usage below maps
 * to the Linux key in the comment, and Generic.kl turns that into the Android key
 * the original Phicomm remote produces.
 */
final class HidRemote {

    interface Listener {
        void onStatus(int state, String text);
    }

    static final int ST_ERROR = -1, ST_IDLE = 0, ST_READY = 1, ST_BUSY = 2, ST_CONNECTED = 3;

    // Bit index in the report. Order must match the usages in DESCRIPTOR.
    static final int KEY_POWER = 0;    // 0x0030 Power        -> KEY_POWER      -> POWER
    static final int KEY_MENU = 1;     // 0x0040 Menu         -> KEY_MENU       -> MENU
    static final int KEY_OK = 2;       // 0x0041 Menu Pick    -> KEY_SELECT     -> DPAD_CENTER
    static final int KEY_UP = 3;       // 0x0042 Menu Up      -> KEY_UP         -> DPAD_UP
    static final int KEY_DOWN = 4;     // 0x0043 Menu Down    -> KEY_DOWN       -> DPAD_DOWN
    static final int KEY_LEFT = 5;     // 0x0044 Menu Left    -> KEY_LEFT       -> DPAD_LEFT
    static final int KEY_RIGHT = 6;    // 0x0045 Menu Right   -> KEY_RIGHT      -> DPAD_RIGHT
    static final int KEY_PLAY_PAUSE = 7; // 0x00CD Play/Pause -> KEY_PLAYPAUSE -> MEDIA_PLAY_PAUSE (was Voice Command 0x00CF, which nothing on the box handles)
    static final int KEY_VOL_UP = 8;   // 0x00E9 Volume Inc   -> KEY_VOLUMEUP   -> VOLUME_UP
    static final int KEY_VOL_DOWN = 9; // 0x00EA Volume Dec   -> KEY_VOLUMEDOWN -> VOLUME_DOWN
    static final int KEY_HOME = 10;    // 0x0223 AC Home      -> KEY_HOMEPAGE   -> HOME
    static final int KEY_BACK = 11;    // 0x0224 AC Back      -> KEY_BACK       -> BACK
    /** Phone's power button. The box helper (app.n1standby) turns it into a soft standby. */
    static final int KEY_STANDBY = 12; // 0x0089 Media Sel. TV -> KEY_TV        -> TV
    /** Phone's TV button. The box helper turns the TV on or off over HDMI-CEC. */
    static final int KEY_TV_POWER = 13; // 0x008D Program Guide -> KEY_PROGRAM   -> GUIDE

    static final int REPORT_ID = 1;

    static final byte[] DESCRIPTOR = {
        0x05, 0x0C,                    // Usage Page (Consumer)
        0x09, 0x01,                    // Usage (Consumer Control)
        (byte) 0xA1, 0x01,             // Collection (Application)
        (byte) 0x85, REPORT_ID,        //   Report ID (1)
        0x15, 0x00,                    //   Logical Minimum (0)
        0x25, 0x01,                    //   Logical Maximum (1)
        0x75, 0x01,                    //   Report Size (1)
        (byte) 0x95, 0x0E,             //   Report Count (14)
        0x09, 0x30,                    //   Usage (Power)
        0x09, 0x40,                    //   Usage (Menu)
        0x09, 0x41,                    //   Usage (Menu Pick)
        0x09, 0x42,                    //   Usage (Menu Up)
        0x09, 0x43,                    //   Usage (Menu Down)
        0x09, 0x44,                    //   Usage (Menu Left)
        0x09, 0x45,                    //   Usage (Menu Right)
        0x09, (byte) 0xCD,             //   Usage (Play/Pause)
        0x09, (byte) 0xE9,             //   Usage (Volume Increment)
        0x09, (byte) 0xEA,             //   Usage (Volume Decrement)
        0x0A, 0x23, 0x02,              //   Usage (AC Home)
        0x0A, 0x24, 0x02,              //   Usage (AC Back)
        0x09, (byte) 0x89,             //   Usage (Media Select TV)
        0x09, (byte) 0x8D,             //   Usage (Media Select Program Guide)
        (byte) 0x81, 0x02,             //   Input (Data, Variable, Absolute)
        0x75, 0x02,                    //   Report Size (2)
        (byte) 0x95, 0x01,             //   Report Count (1)
        (byte) 0x81, 0x03,             //   Input (Constant) padding to 2 bytes
        (byte) 0xC0,                   // End Collection
    };

    private final Context ctx;
    private final BluetoothAdapter adapter;
    private final Executor executor;
    private final Listener listener;

    private BluetoothHidDevice hid;
    private boolean registered;
    private BluetoothDevice target;
    private BluetoothDevice connected;
    private int pressed;
    private int registerAttempts;
    private final Handler retry = new Handler(Looper.getMainLooper());

    // Auto-reconnect after the link drops (box rebooted, phone out of range, ...).
    private static final long RECONNECT_WINDOW_MS = 3 * 60_000, RECONNECT_EVERY_MS = 5_000;
    private boolean reconnecting;
    private long reconnectUntil;
    private int reconnectTries;
    private final Runnable reconnectTick = this::reconnectTick;
    /** Set by Disconnect, cleared by Connect: stops a later re-registration from reconnecting. */
    private boolean userDisconnected;

    HidRemote(Context ctx, BluetoothAdapter adapter, Executor executor, Listener listener) {
        this.ctx = ctx;
        this.adapter = adapter;
        this.executor = executor;
        this.listener = listener;
    }

    void start() {
        status(ST_BUSY, s(R.string.st_starting));
        boolean ok = adapter.getProfileProxy(ctx, new BluetoothProfile.ServiceListener() {
            @Override
            public void onServiceConnected(int profile, BluetoothProfile proxy) {
                hid = (BluetoothHidDevice) proxy;
                register();
            }

            @Override
            public void onServiceDisconnected(int profile) {
                hid = null;
                registered = false;
                connected = null;
                status(ST_ERROR, s(R.string.st_service_lost));
            }
        }, BluetoothProfile.HID_DEVICE);
        if (!ok) status(ST_ERROR, s(R.string.st_no_hid));
    }

    private void register() {
        BluetoothHidDeviceAppSdpSettings sdp = new BluetoothHidDeviceAppSdpSettings(
                "N1 Remote", "Phone as Bluetooth remote", "n1remote",
                (byte) (BluetoothHidDevice.SUBCLASS1_NONE | BluetoothHidDevice.SUBCLASS2_REMOTE_CONTROL),
                DESCRIPTOR);
        // The return value is not trustworthy: on MIUI (Android 13) it came back false while the
        // registration had succeeded. Wait for onAppStatusChanged and retry only if it never comes
        // (e.g. a previous instance was still unregistering).
        hid.registerApp(sdp, null, null, executor, callback);
        retry.postDelayed(() -> {
            if (hid == null || registered) return;
            if (++registerAttempts < 5) {
                status(ST_BUSY, s(R.string.st_registering));
                // Registering while the previous process is still being unregistered fails
                // natively, yet the service keeps our uid as "registered" and refuses every
                // retry ("another app is registered"). Clearing our own stale entry fixes it.
                try {
                    hid.unregisterApp();
                } catch (RuntimeException ignored) {
                }
                retry.postDelayed(() -> {
                    if (hid != null && !registered) register();
                }, 500);
            } else {
                status(ST_ERROR, s(R.string.st_register_failed));
            }
        }, 1500);
    }

    void stop() {
        retry.removeCallbacksAndMessages(null);
        if (hid != null) {
            if (connected != null) hid.disconnect(connected);
            if (registered) hid.unregisterApp();
            adapter.closeProfileProxy(BluetoothProfile.HID_DEVICE, hid);
        }
        hid = null;
        registered = false;
        connected = null;
    }

    boolean isConnected() {
        return connected != null;
    }

    /** Pairs first if needed, then opens the HID connection. */
    void connect(BluetoothDevice device) {
        target = device;
        userDisconnected = false;
        if (!registered || hid == null) {
            status(ST_BUSY, s(R.string.st_not_ready));
            return;
        }
        if (device.getBondState() != BluetoothDevice.BOND_BONDED) {
            status(ST_BUSY, s(R.string.st_pairing));
            if (device.getBondState() == BluetoothDevice.BOND_NONE && !device.createBond()) {
                status(ST_ERROR, s(R.string.st_pair_failed_start));
            }
            return;
        }
        status(ST_BUSY, s(R.string.st_connecting, label(device)));
        if (!hid.connect(device)) status(ST_ERROR, s(R.string.st_connect_failed));
    }

    /** User asked to disconnect: forget the target so nothing reconnects behind their back. */
    void disconnect() {
        target = null;
        userDisconnected = true;
        stopReconnecting();
        if (hid != null && connected != null) hid.disconnect(connected);
        else status(ST_READY, s(R.string.st_disconnected)); // was only retrying: no callback will come
    }

    /** Back in the foreground: register again if Android dropped us, then reconnect. */
    void resume() {
        if (hid == null) return;
        if (!registered) {
            registerAttempts = 0;
            status(ST_BUSY, s(R.string.st_reregistering));
            register();
        } else {
            ensureConnected();
        }
    }

    /**
     * Keeps trying to get the link back, every 5 s for 3 minutes. Called when the link drops
     * and when the app comes back to the foreground. No-op if already connected or if the user
     * disconnected on purpose.
     */
    void ensureConnected() {
        if (reconnecting || target == null || connected != null || hid == null || !registered) return;
        if (target.getBondState() != BluetoothDevice.BOND_BONDED) return;
        reconnecting = true;
        reconnectUntil = SystemClock.uptimeMillis() + RECONNECT_WINDOW_MS;
        reconnectTries = 0;
        retry.removeCallbacks(reconnectTick);
        retry.postDelayed(reconnectTick, 1000);
    }

    private void reconnectTick() {
        if (target == null || connected != null || hid == null || !registered) {
            reconnecting = false;
            return;
        }
        if (SystemClock.uptimeMillis() > reconnectUntil) {
            reconnecting = false;
            status(ST_READY, s(R.string.st_reconnect_gave_up));
            return;
        }
        reconnectTries++;
        status(ST_BUSY, s(R.string.st_reconnecting, reconnectTries));
        hid.connect(target);
        retry.postDelayed(reconnectTick, RECONNECT_EVERY_MS);
    }

    private void stopReconnecting() {
        reconnecting = false;
        retry.removeCallbacks(reconnectTick);
    }

    void onBondStateChanged(BluetoothDevice device, int state) {
        if (target == null || !target.equals(device)) return;
        if (state == BluetoothDevice.BOND_BONDED) {
            connect(device);
        } else if (state == BluetoothDevice.BOND_NONE) {
            status(ST_ERROR, s(R.string.st_pair_failed));
        }
    }

    void press(int key) {
        pressed |= 1 << key;
        send();
    }

    void release(int key) {
        pressed &= ~(1 << key);
        send();
    }

    private void send() {
        if (hid == null || connected == null) return;
        boolean ok = hid.sendReport(connected, REPORT_ID, new byte[] {(byte) pressed, (byte) (pressed >> 8)});
        if (!ok) Log.w("N1Remote", "sendReport failed, mask=" + Integer.toHexString(pressed));
    }

    private final BluetoothHidDevice.Callback callback = new BluetoothHidDevice.Callback() {
        @Override
        public void onAppStatusChanged(BluetoothDevice plugged, boolean isRegistered) {
            registered = isRegistered;
            if (!isRegistered) {
                // Android drops the HID app as soon as it leaves the foreground
                // (HidDeviceService unregisters apps below foreground-service importance).
                // RemoteService prevents that while connected by running in the foreground.
                connected = null;
                pressed = 0;
                stopReconnecting();
                status(ST_IDLE, s(R.string.st_backgrounded));
                return;
            }
            // Android hands back the device we last used. Don't take it after the user disconnected:
            // the notification's Disconnect drops the foreground service, Android then unregisters
            // the app, and coming back to the screen registers again.
            if (target == null && plugged != null && !userDisconnected) target = plugged;
            if (target != null && target.getBondState() == BluetoothDevice.BOND_BONDED) {
                connect(target);
            } else {
                status(ST_READY, s(R.string.st_ready));
            }
        }

        @Override
        public void onConnectionStateChanged(BluetoothDevice device, int state) {
            switch (state) {
                case BluetoothProfile.STATE_CONNECTED:
                    connected = device;
                    pressed = 0;
                    stopReconnecting();
                    status(ST_CONNECTED, s(R.string.st_connected, label(device)));
                    break;
                case BluetoothProfile.STATE_CONNECTING:
                    if (!reconnecting) status(ST_BUSY, s(R.string.st_connecting, label(device)));
                    break;
                case BluetoothProfile.STATE_DISCONNECTED:
                    if (connected == null || connected.equals(device)) {
                        connected = null;
                        pressed = 0;
                        if (reconnecting) break;           // a retry failed; the loop goes on
                        if (target != null) ensureConnected();
                        if (!reconnecting) status(ST_READY, s(R.string.st_disconnected));
                    }
                    break;
                default:
                    break;
            }
        }

        @Override
        public void onGetReport(BluetoothDevice device, byte type, byte id, int bufferSize) {
            if (hid != null) hid.replyReport(device, type, id, new byte[] {(byte) pressed, (byte) (pressed >> 8)});
        }

        @Override
        public void onSetReport(BluetoothDevice device, byte type, byte id, byte[] data) {
            if (hid != null) hid.reportError(device, BluetoothHidDevice.ERROR_RSP_SUCCESS);
        }
    };

    private String s(int id, Object... args) {
        return ctx.getString(id, args);
    }

    private String label(BluetoothDevice d) {
        String name = null;
        try {
            name = d.getName();
        } catch (SecurityException ignored) {
        }
        return name == null || name.isEmpty() ? d.getAddress() : s(R.string.device_label, name, d.getAddress());
    }

    private void status(int state, String text) {
        listener.onStatus(state, text);
    }
}
