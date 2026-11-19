package app.n1remote;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHidDevice;
import android.bluetooth.BluetoothHidDeviceAppSdpSettings;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.Executor;

/**
 * Makes the phone a Bluetooth HID remote control (BluetoothHidDevice, Android 9+).
 *
 * The report is a 13-bit Consumer Control bitmap, one bit per key. Verified on the
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
    static final int KEY_VOICE = 7;    // 0x00CF Voice Cmd    -> KEY_VOICECOMMAND -> VOICE_ASSIST
    static final int KEY_VOL_UP = 8;   // 0x00E9 Volume Inc   -> KEY_VOLUMEUP   -> VOLUME_UP
    static final int KEY_VOL_DOWN = 9; // 0x00EA Volume Dec   -> KEY_VOLUMEDOWN -> VOLUME_DOWN
    static final int KEY_HOME = 10;    // 0x0223 AC Home      -> KEY_HOMEPAGE   -> HOME
    static final int KEY_BACK = 11;    // 0x0224 AC Back      -> KEY_BACK       -> BACK
    /** Phone's power button. The box helper (app.n1standby) turns it into a soft standby. */
    static final int KEY_STANDBY = 12; // 0x0089 Media Sel. TV -> KEY_TV        -> TV

    static final int REPORT_ID = 1;

    static final byte[] DESCRIPTOR = {
        0x05, 0x0C,                    // Usage Page (Consumer)
        0x09, 0x01,                    // Usage (Consumer Control)
        (byte) 0xA1, 0x01,             // Collection (Application)
        (byte) 0x85, REPORT_ID,        //   Report ID (1)
        0x15, 0x00,                    //   Logical Minimum (0)
        0x25, 0x01,                    //   Logical Maximum (1)
        0x75, 0x01,                    //   Report Size (1)
        (byte) 0x95, 0x0D,             //   Report Count (13)
        0x09, 0x30,                    //   Usage (Power)
        0x09, 0x40,                    //   Usage (Menu)
        0x09, 0x41,                    //   Usage (Menu Pick)
        0x09, 0x42,                    //   Usage (Menu Up)
        0x09, 0x43,                    //   Usage (Menu Down)
        0x09, 0x44,                    //   Usage (Menu Left)
        0x09, 0x45,                    //   Usage (Menu Right)
        0x09, (byte) 0xCF,             //   Usage (Voice Command)
        0x09, (byte) 0xE9,             //   Usage (Volume Increment)
        0x09, (byte) 0xEA,             //   Usage (Volume Decrement)
        0x0A, 0x23, 0x02,              //   Usage (AC Home)
        0x0A, 0x24, 0x02,              //   Usage (AC Back)
        0x09, (byte) 0x89,             //   Usage (Media Select TV)
        (byte) 0x81, 0x02,             //   Input (Data, Variable, Absolute)
        0x75, 0x03,                    //   Report Size (3)
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

    HidRemote(Context ctx, BluetoothAdapter adapter, Executor executor, Listener listener) {
        this.ctx = ctx;
        this.adapter = adapter;
        this.executor = executor;
        this.listener = listener;
    }

    void start() {
        status(ST_BUSY, "正在启动蓝牙 HID 服务…");
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
                status(ST_ERROR, "蓝牙 HID 服务断开了，重开 App 试试");
            }
        }, BluetoothProfile.HID_DEVICE);
        if (!ok) status(ST_ERROR, "这台手机没有蓝牙 HID 设备模式（需要 Android 9 及以上）");
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
                status(ST_BUSY, "正在注册蓝牙 HID…");
                register();
            } else {
                status(ST_ERROR, "注册 HID 失败：可能有别的蓝牙键盘/遥控 App 正占用，关掉它再试");
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
        if (!registered || hid == null) {
            status(ST_BUSY, "HID 服务还没就绪，就绪后会自动连接");
            return;
        }
        if (device.getBondState() != BluetoothDevice.BOND_BONDED) {
            status(ST_BUSY, "正在配对…手机和电视上都会弹出确认，两边都点「配对」");
            if (device.getBondState() == BluetoothDevice.BOND_NONE && !device.createBond()) {
                status(ST_ERROR, "发起配对失败，确认盒子蓝牙已打开、地址没填错");
            }
            return;
        }
        status(ST_BUSY, "正在连接 " + label(device) + "…");
        if (!hid.connect(device)) status(ST_ERROR, "发起连接失败");
    }

    void disconnect() {
        target = null;
        if (hid != null && connected != null) hid.disconnect(connected);
    }

    void onBondStateChanged(BluetoothDevice device, int state) {
        if (target == null || !target.equals(device)) return;
        if (state == BluetoothDevice.BOND_BONDED) {
            connect(device);
        } else if (state == BluetoothDevice.BOND_NONE) {
            status(ST_ERROR, "配对没成功（被拒绝或超时），再点一次「连接」");
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
        hid.sendReport(connected, REPORT_ID, new byte[] {(byte) pressed, (byte) (pressed >> 8)});
    }

    private final BluetoothHidDevice.Callback callback = new BluetoothHidDevice.Callback() {
        @Override
        public void onAppStatusChanged(BluetoothDevice plugged, boolean isRegistered) {
            registered = isRegistered;
            if (!isRegistered) {
                connected = null;
                status(ST_IDLE, "HID 服务已注销");
                return;
            }
            if (target == null && plugged != null) target = plugged;
            if (target != null && target.getBondState() == BluetoothDevice.BOND_BONDED) {
                connect(target);
            } else {
                status(ST_READY, "就绪：确认盒子地址后点「连接」");
            }
        }

        @Override
        public void onConnectionStateChanged(BluetoothDevice device, int state) {
            switch (state) {
                case BluetoothProfile.STATE_CONNECTED:
                    connected = device;
                    pressed = 0;
                    status(ST_CONNECTED, "已连接：" + label(device));
                    break;
                case BluetoothProfile.STATE_CONNECTING:
                    status(ST_BUSY, "正在连接 " + label(device) + "…");
                    break;
                case BluetoothProfile.STATE_DISCONNECTED:
                    if (connected == null || connected.equals(device)) {
                        connected = null;
                        pressed = 0;
                        status(ST_READY, "已断开，点「连接」重连");
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

    private static String label(BluetoothDevice d) {
        String name = null;
        try {
            name = d.getName();
        } catch (SecurityException ignored) {
        }
        return name == null || name.isEmpty() ? d.getAddress() : name + "（" + d.getAddress() + "）";
    }

    private void status(int state, String text) {
        listener.onStatus(state, text);
    }
}
