package app.n1remote;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class MainActivity extends Activity implements HidRemote.Listener {

    /** No default box: the user types its Bluetooth address or picks it from bonded devices. */
    private static final String DEFAULT_MAC = "";
    private static final int REQ_PERMISSION = 1, REQ_ENABLE_BT = 2;

    private TextView status;
    private EditText mac;
    private Button connectBtn;
    private SharedPreferences prefs;
    private BluetoothAdapter adapter;
    private HidRemote remote;
    private boolean receiverRegistered;
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
        findViewById(R.id.pick).setOnClickListener(v -> pickBonded());

        if (needsPermission()) {
            requestPermissions(new String[] {Manifest.permission.BLUETOOTH_CONNECT}, REQ_PERMISSION);
        } else {
            initBluetooth();
        }
    }

    private boolean needsPermission() {
        return Build.VERSION.SDK_INT >= 31
                && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        if (requestCode != REQ_PERMISSION) return;
        if (needsPermission()) {
            onStatus(HidRemote.ST_ERROR, "没有「附近的设备」权限就没法用蓝牙，请在系统设置里给这个 App 开权限");
        } else {
            initBluetooth();
        }
    }

    private void initBluetooth() {
        if (Build.VERSION.SDK_INT < 28) {
            onStatus(HidRemote.ST_ERROR, "需要 Android 9 及以上才能当蓝牙遥控器");
            return;
        }
        BluetoothManager bm = getSystemService(BluetoothManager.class);
        adapter = bm == null ? null : bm.getAdapter();
        if (adapter == null) {
            onStatus(HidRemote.ST_ERROR, "这台手机没有蓝牙");
            return;
        }
        if (!adapter.isEnabled()) {
            onStatus(HidRemote.ST_IDLE, "请先打开蓝牙");
            startActivityForResult(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), REQ_ENABLE_BT);
            return;
        }
        if (!receiverRegistered) {
            IntentFilter f = new IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(bondReceiver, f, Context.RECEIVER_EXPORTED);
            else registerReceiver(bondReceiver, f);
            receiverRegistered = true;
        }
        remote = new HidRemote(this, adapter, getMainExecutor(), this);
        BluetoothDevice saved = deviceFromField(false);
        if (saved != null && saved.getBondState() == BluetoothDevice.BOND_BONDED) {
            remote.connect(saved); // auto-reconnect once the HID app is registered
        }
        remote.start();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_ENABLE_BT) {
            if (adapter != null && adapter.isEnabled()) initBluetooth();
            else onStatus(HidRemote.ST_ERROR, "蓝牙没打开");
        }
    }

    private final BroadcastReceiver bondReceiver = new BroadcastReceiver() {
        @Override
        @SuppressWarnings("deprecation")
        public void onReceive(Context context, Intent intent) {
            BluetoothDevice d = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
            int state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR);
            if (d != null && remote != null) remote.onBondStateChanged(d, state);
        }
    };

    private BluetoothDevice deviceFromField(boolean complain) {
        String addr = mac.getText().toString().trim().toUpperCase(Locale.ROOT);
        if (!BluetoothAdapter.checkBluetoothAddress(addr)) {
            if (complain) onStatus(HidRemote.ST_ERROR, "地址格式不对，应该像 AA:BB:CC:DD:EE:FF（盒子设置 → 关于 → 状态里能看到）");
            return null;
        }
        return adapter.getRemoteDevice(addr);
    }

    private void onConnectClicked() {
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

    private void pickBonded() {
        if (adapter == null) return;
        Set<BluetoothDevice> bonded;
        try {
            bonded = adapter.getBondedDevices();
        } catch (SecurityException e) {
            return;
        }
        if (bonded == null || bonded.isEmpty()) {
            Toast.makeText(this, "手机还没有配对过任何设备", Toast.LENGTH_SHORT).show();
            return;
        }
        final List<String> addrs = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        for (BluetoothDevice d : bonded) {
            addrs.add(d.getAddress());
            String n = d.getName();
            labels.add((n == null ? "（无名称）" : n) + "\n" + d.getAddress());
        }
        new AlertDialog.Builder(this)
                .setTitle("选择盒子")
                .setItems(labels.toArray(new String[0]), (dlg, which) -> mac.setText(addrs.get(which)))
                .show();
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
        if (remote != null) remote.resume(); // re-register / reconnect after being in the background
    }

    /** While connected, the phone's own volume keys change the box's volume, not the phone's. */
    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int code = e.getKeyCode();
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
        ((TextView) findViewById(R.id.mode)).setText(on ? "方向键" : "触控板");
    }

    /** One key press and release, for the swipe pad. */
    private void tapKey(int key) {
        if (remote == null || !remote.isConnected()) {
            Toast.makeText(this, "还没连上盒子", Toast.LENGTH_SHORT).show();
            return;
        }
        remote.press(key);
        remote.release(key);
    }

    private void pressKey(int key) {
        if (remote == null || !remote.isConnected()) {
            Toast.makeText(this, "还没连上盒子", Toast.LENGTH_SHORT).show();
            return;
        }
        remote.press(key);
    }

    private void releaseKey(int key) {
        if (remote != null) remote.release(key);
    }

    @Override
    public void onStatus(int state, String text) {
        hidState = state;
        hidText = text;
        render();
    }

    private void render() {
        int state = hidState;
        String text = hidText;
        status.setText(text);
        int color;
        switch (state) {
            case HidRemote.ST_CONNECTED: color = 0xFF30D158; break;
            case HidRemote.ST_ERROR: color = 0xFFFF6961; break;
            case HidRemote.ST_BUSY: color = 0xFFFFD60A; break;
            default: color = 0xFFB0B0B5; break;
        }
        status.setTextColor(color);
        connectBtn.setText(hidState == HidRemote.ST_CONNECTED ? "断开" : "连接");
    }

    @Override
    protected void onDestroy() {
        if (remote != null) remote.stop();
        if (receiverRegistered) unregisterReceiver(bondReceiver);
        super.onDestroy();
    }
}
