package app.n1remote;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** The remote screen. The Bluetooth side lives in {@link RemoteService} so it survives the screen. */
public final class MainActivity extends Activity implements HidRemote.Listener {

    /** No default box: the user types its Bluetooth address or picks it from bonded devices. */
    private static final String DEFAULT_MAC = "";
    private static final int REQ_PERMISSION = 1, REQ_ENABLE_BT = 2, REQ_SCAN = 3;

    private TextView status;
    private EditText mac;
    private Button connectBtn;
    private View pickBtn;
    private LinearLayout linkRow;
    private SharedPreferences prefs;
    private BluetoothAdapter adapter;
    private RemoteService service;
    private boolean bound;
    private int hidState = HidRemote.ST_IDLE;
    private String hidText = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        prefs = getSharedPreferences("remote", MODE_PRIVATE);

        status = findViewById(R.id.status);
        mac = findViewById(R.id.mac);
        connectBtn = findViewById(R.id.connect);
        pickBtn = findViewById(R.id.pick);
        linkRow = findViewById(R.id.link_row);
        mac.setText(prefs.getString("mac", DEFAULT_MAC));

        bindKey(R.id.btn_power, HidRemote.KEY_STANDBY);
        bindKey(R.id.btn_back, HidRemote.KEY_BACK);
        bindKey(R.id.btn_voice, HidRemote.KEY_VOICE);
        bindKey(R.id.btn_vol_up, HidRemote.KEY_VOL_UP);
        bindKey(R.id.btn_home, HidRemote.KEY_HOME);
        bindKey(R.id.btn_menu, HidRemote.KEY_MENU);
        bindKey(R.id.btn_vol_down, HidRemote.KEY_VOL_DOWN);
        DpadView dpad = findViewById(R.id.dpad);
        dpad.setKeyListener((key, down) -> {
            if (down) pressKey(key);
            else releaseKey(key);
        });
        bindKey(R.id.btn_tv, HidRemote.KEY_TV_POWER);
        TouchpadView pad = findViewById(R.id.touchpad);
        pad.setListener(new TouchpadView.Listener() {
            @Override
            public void onTap(int key) {
                tapKey(key);
            }

            @Override
            public void onHold(int key, boolean down) {
                if (down) pressKey(key);
                else releaseKey(key);
            }
        });
        TextView modeBtn = findViewById(R.id.mode);
        modeBtn.setOnClickListener(v -> setTouchpad(!prefs.getBoolean("touchpad", false)));
        setTouchpad(prefs.getBoolean("touchpad", false));

        connectBtn.setOnClickListener(v -> onConnectClicked());
        pickBtn.setOnClickListener(v -> onFindClicked());

        List<String> ask = new ArrayList<>();
        if (needsPermission()) ask.add(Manifest.permission.BLUETOOTH_CONNECT);
        if (shouldAskNotifications()) ask.add(Manifest.permission.POST_NOTIFICATIONS);
        if (!ask.isEmpty()) requestPermissions(ask.toArray(new String[0]), REQ_PERMISSION);
        if (!needsPermission()) initBluetooth();
    }

    private boolean needsPermission() {
        return Build.VERSION.SDK_INT >= 31
                && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED;
    }

    /**
     * The notification carries the standby and disconnect buttons while the app is in the
     * background. Without the permission the link still stays up, the notification just isn't
     * shown, so ask only once.
     */
    private boolean shouldAskNotifications() {
        if (Build.VERSION.SDK_INT < 33 || prefs.getBoolean("asked_notifications", false)) return false;
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            return false;
        }
        prefs.edit().putBoolean("asked_notifications", true).apply();
        return true;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        if (requestCode == REQ_SCAN) {
            if (adapter != null) openPicker(); // without the permission it lists paired devices only
            return;
        }
        if (requestCode != REQ_PERMISSION) return;
        List<String> asked = Arrays.asList(permissions);
        // Connected before the user answered: the first notification was dropped, post it again.
        if (asked.contains(Manifest.permission.POST_NOTIFICATIONS) && service != null) {
            service.refreshNotification();
        }
        if (!asked.contains(Manifest.permission.BLUETOOTH_CONNECT)) return; // Bluetooth already running
        if (needsPermission()) {
            onStatus(HidRemote.ST_ERROR, getString(R.string.err_permission));
        } else {
            initBluetooth();
        }
    }

    private void initBluetooth() {
        if (Build.VERSION.SDK_INT < 28) {
            onStatus(HidRemote.ST_ERROR, getString(R.string.err_android9));
            return;
        }
        BluetoothManager bm = getSystemService(BluetoothManager.class);
        adapter = bm == null ? null : bm.getAdapter();
        if (adapter == null) {
            onStatus(HidRemote.ST_ERROR, getString(R.string.err_no_bt));
            return;
        }
        if (!adapter.isEnabled()) {
            onStatus(HidRemote.ST_IDLE, getString(R.string.bt_turn_on));
            startActivityForResult(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), REQ_ENABLE_BT);
            return;
        }
        if (!bound) bound = bindService(new Intent(this, RemoteService.class), conn, BIND_AUTO_CREATE);
    }

    private final ServiceConnection conn = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            service = ((RemoteService.LocalBinder) binder).service();
            service.setUi(MainActivity.this);
            service.onUiVisible(deviceFromField(false));
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            service = null;
        }
    };

    private HidRemote remote() {
        return service == null ? null : service.remote();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_ENABLE_BT) {
            if (adapter != null && adapter.isEnabled()) initBluetooth();
            else onStatus(HidRemote.ST_ERROR, getString(R.string.bt_off));
        }
    }

    private BluetoothDevice deviceFromField(boolean complain) {
        String addr = mac.getText().toString().trim().toUpperCase(Locale.ROOT);
        if (adapter == null || !BluetoothAdapter.checkBluetoothAddress(addr)) {
            if (complain) onStatus(HidRemote.ST_ERROR, getString(R.string.err_mac));
            return null;
        }
        return adapter.getRemoteDevice(addr);
    }

    private void onConnectClicked() {
        HidRemote remote = remote();
        if (remote == null) {
            if (needsPermission()) {
                requestPermissions(new String[] {Manifest.permission.BLUETOOTH_CONNECT}, REQ_PERMISSION);
            } else {
                initBluetooth();
            }
            return;
        }
        if (remote.isConnected()) {
            remote.disconnect();
            return;
        }
        BluetoothDevice d = deviceFromField(true);
        if (d == null) return;
        prefs.edit().putString("mac", d.getAddress()).apply();
        remote.connect(d);
    }

    /** Scanning needs its own permission: "Nearby devices" scan on Android 12+, location before. */
    private static String scanPermission() {
        return Build.VERSION.SDK_INT >= 31 ? Manifest.permission.BLUETOOTH_SCAN : Manifest.permission.ACCESS_FINE_LOCATION;
    }

    private void onFindClicked() {
        if (adapter == null) {
            onConnectClicked(); // Bluetooth isn't up yet: same path as Connect (permission, turn it on)
            return;
        }
        if (checkSelfPermission(scanPermission()) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {scanPermission()}, REQ_SCAN); // opens the picker either way
            return;
        }
        openPicker();
    }

    private void openPicker() {
        boolean canScan = checkSelfPermission(scanPermission()) == PackageManager.PERMISSION_GRANTED;
        new BoxPicker(this, adapter, canScan, device -> {
            mac.setText(device.getAddress());
            prefs.edit().putString("mac", device.getAddress()).apply();
            HidRemote remote = remote();
            if (remote != null && !remote.isConnected()) remote.connect(device); // pairs first if needed
        }).show();
    }

    private void bindKey(int viewId, final int key) {
        View v = findViewById(viewId);
        v.setOnTouchListener((view, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    view.setPressed(true);
                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                    pressKey(key);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    view.setPressed(false);
                    releaseKey(key);
                    return true;
                default:
                    return true;
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Re-register / reconnect if Android dropped the link while the app was in the background.
        if (service != null) service.onUiVisible(deviceFromField(false));
    }

    /** While connected, the phone's own volume keys change the box's volume, not the phone's. */
    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int code = e.getKeyCode();
        HidRemote remote = remote();
        if ((code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN)
                && remote != null && remote.isConnected()) {
            int key = code == KeyEvent.KEYCODE_VOLUME_UP ? HidRemote.KEY_VOL_UP : HidRemote.KEY_VOL_DOWN;
            if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) remote.press(key);
            else if (e.getAction() == KeyEvent.ACTION_UP) remote.release(key);
            return true; // held keys repeat on the box side
        }
        return super.dispatchKeyEvent(e);
    }

    /** Swaps the D-pad for the swipe pad (same place on the remote) and remembers the choice. */
    private void setTouchpad(boolean on) {
        prefs.edit().putBoolean("touchpad", on).apply();
        findViewById(R.id.dpad).setVisibility(on ? View.GONE : View.VISIBLE);
        findViewById(R.id.touchpad).setVisibility(on ? View.VISIBLE : View.GONE);
        ((TextView) findViewById(R.id.mode)).setText(on ? R.string.mode_dpad : R.string.mode_touchpad);
    }

    /** One key press and release, for the swipe pad. */
    private void tapKey(int key) {
        HidRemote remote = connectedRemote();
        if (remote == null) return;
        remote.press(key);
        remote.release(key);
    }

    private void pressKey(int key) {
        HidRemote remote = connectedRemote();
        if (remote != null) remote.press(key);
    }

    private void releaseKey(int key) {
        HidRemote remote = remote();
        if (remote != null) remote.release(key);
    }

    private HidRemote connectedRemote() {
        HidRemote remote = remote();
        if (remote == null || !remote.isConnected()) {
            Toast.makeText(this, R.string.not_connected, Toast.LENGTH_SHORT).show();
            return null;
        }
        return remote;
    }

    @Override
    public void onStatus(int state, String text) {
        hidState = state;
        hidText = text;
        render();
    }

    private void render() {
        int state = hidState;
        status.setText(hidText);
        int color;
        switch (state) {
            case HidRemote.ST_CONNECTED: color = 0xFF30D158; break;
            case HidRemote.ST_ERROR: color = 0xFFFF6961; break;
            case HidRemote.ST_BUSY: color = 0xFFFFD60A; break;
            default: color = 0xFFB0B0B5; break;
        }
        status.setTextColor(color);
        boolean connected = state == HidRemote.ST_CONNECTED;
        connectBtn.setText(connected ? R.string.disconnect : R.string.connect);
        // Once connected the address is noise: leave just the Disconnect button.
        if (connected && mac.hasFocus()) {
            InputMethodManager imm = getSystemService(InputMethodManager.class);
            if (imm != null) imm.hideSoftInputFromWindow(mac.getWindowToken(), 0);
            mac.clearFocus();
        }
        mac.setVisibility(connected ? View.GONE : View.VISIBLE);
        pickBtn.setVisibility(connected ? View.GONE : View.VISIBLE);
        linkRow.setGravity(connected ? Gravity.CENTER : Gravity.CENTER_VERTICAL);
    }

    @Override
    protected void onDestroy() {
        if (service != null) service.setUi(null);
        if (bound) unbindService(conn);
        bound = false;
        service = null;
        super.onDestroy();
    }
}
