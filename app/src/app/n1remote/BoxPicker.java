package app.n1remote;

import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Typeface;
import android.os.Build;
import android.util.TypedValue;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The "Find" dialog: paired devices first, then what a Bluetooth scan finds nearby, nearest
 * first. Picking a device hands it back. A box shows up in the scan only while it is
 * discoverable, which on the N1 means its Bluetooth settings page is open.
 */
final class BoxPicker {

    interface Callback {
        void onPicked(BluetoothDevice device);
    }

    /** The N1's model name, which is also its default Bluetooth name. */
    private static final String N1_NAME = "p230";

    private static final class Found {
        final BluetoothDevice device;
        String name;
        int rssi;

        Found(BluetoothDevice device) {
            this.device = device;
        }
    }

    private final Activity activity;
    private final BluetoothAdapter adapter;
    private final boolean canScan;
    private final Callback callback;
    private final float density;
    private final TextView status;
    private final LinearLayout list;
    private final AlertDialog dialog;
    private final Map<String, Found> found = new LinkedHashMap<>();
    private boolean scanning, receiverRegistered;

    BoxPicker(Activity activity, BluetoothAdapter adapter, boolean canScan, Callback callback) {
        this.activity = activity;
        this.adapter = adapter;
        this.canScan = canScan;
        this.callback = callback;
        density = activity.getResources().getDisplayMetrics().density;

        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(24), dp(4), dp(24), dp(8));
        TextView hint = text(R.string.pick_hint, 13, 0xFF8E8E93);
        content.addView(hint);
        status = text(0, 13, 0xFFFFD60A);
        status.setPadding(0, dp(8), 0, 0);
        content.addView(status);
        list = new LinearLayout(activity);
        list.setOrientation(LinearLayout.VERTICAL);
        content.addView(list);
        ScrollView scroll = new ScrollView(activity);
        scroll.addView(content);

        AlertDialog.Builder b = new AlertDialog.Builder(activity)
                .setTitle(R.string.pick_title)
                .setView(scroll)
                .setNegativeButton(android.R.string.cancel, null);
        if (canScan) b.setNeutralButton(R.string.scan_again, null);
        dialog = b.create();
        dialog.setOnShowListener(d -> {
            Button again = dialog.getButton(AlertDialog.BUTTON_NEUTRAL);
            if (again != null) again.setOnClickListener(v -> startScan()); // don't close the dialog
        });
        dialog.setOnDismissListener(d -> stopScan());
    }

    void show() {
        dialog.show();
        if (canScan) startScan();
        else render();
    }

    private void startScan() {
        if (!receiverRegistered) {
            IntentFilter f = new IntentFilter(BluetoothDevice.ACTION_FOUND);
            f.addAction(BluetoothDevice.ACTION_NAME_CHANGED);
            f.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
            if (Build.VERSION.SDK_INT >= 33) activity.registerReceiver(receiver, f, Context.RECEIVER_EXPORTED);
            else activity.registerReceiver(receiver, f);
            receiverRegistered = true;
        }
        try {
            if (adapter.isDiscovering()) adapter.cancelDiscovery();
            scanning = adapter.startDiscovery();
        } catch (SecurityException e) {
            scanning = false;
        }
        render();
    }

    /** Discovery slows down pairing and connecting, so it stops as soon as the dialog closes. */
    private void stopScan() {
        if (scanning) {
            try {
                adapter.cancelDiscovery();
            } catch (SecurityException ignored) {
            }
        }
        scanning = false;
        if (receiverRegistered) activity.unregisterReceiver(receiver);
        receiverRegistered = false;
    }

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        @SuppressWarnings("deprecation")
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(action)) {
                scanning = false;
                render();
                return;
            }
            BluetoothDevice d = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
            if (d == null) return;
            if (BluetoothDevice.ACTION_FOUND.equals(action)) {
                // BLE-only gadgets can't take a classic Bluetooth remote: leave them out.
                if (type(d) == BluetoothDevice.DEVICE_TYPE_LE) return;
                Found f = found.get(d.getAddress());
                if (f == null) found.put(d.getAddress(), f = new Found(d));
                f.rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE);
                String name = intent.getStringExtra(BluetoothDevice.EXTRA_NAME);
                if (name != null && !name.isEmpty()) f.name = name;
            } else if (BluetoothDevice.ACTION_NAME_CHANGED.equals(action)) {
                Found f = found.get(d.getAddress());
                String name = intent.getStringExtra(BluetoothDevice.EXTRA_NAME);
                if (f == null || name == null || name.isEmpty()) return;
                f.name = name;
            }
            render();
        }
    };

    private void render() {
        list.removeAllViews();
        Set<BluetoothDevice> bonded = bonded();
        if (!bonded.isEmpty()) {
            list.addView(header(R.string.pick_paired));
            List<BluetoothDevice> paired = new ArrayList<>(bonded);
            Collections.sort(paired, (a, b) -> Boolean.compare(isN1(name(b, null)), isN1(name(a, null))));
            for (BluetoothDevice d : paired) list.addView(row(d, name(d, null)));
        }
        List<Found> nearby = new ArrayList<>();
        for (Found f : found.values()) {
            if (!bonded.contains(f.device)) nearby.add(f);
        }
        // Likely N1 boxes first, then named devices, each nearest first.
        Collections.sort(nearby, (a, b) -> {
            int c = Boolean.compare(isN1(name(b.device, b.name)), isN1(name(a.device, a.name)));
            if (c != 0) return c;
            c = Boolean.compare(name(b.device, b.name) != null, name(a.device, a.name) != null);
            return c != 0 ? c : Integer.compare(b.rssi, a.rssi);
        });
        if (canScan) {
            list.addView(header(R.string.pick_nearby));
            for (Found f : nearby) list.addView(row(f.device, name(f.device, f.name)));
            if (nearby.isEmpty() && !scanning) list.addView(text(R.string.scan_none, 14, 0xFF8E8E93));
        }
        if (!canScan) status.setText(R.string.scan_no_permission);
        else status.setText(scanning ? R.string.scan_running : R.string.scan_done);
        status.setTextColor(scanning ? 0xFFFFD60A : 0xFF8E8E93);
    }

    private View row(BluetoothDevice d, String name) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, dp(10), 0, dp(10));
        TypedValue tv = new TypedValue();
        activity.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true);
        row.setBackgroundResource(tv.resourceId);
        String label = name == null ? activity.getString(R.string.unnamed) : name;
        if (isN1(name)) label = activity.getString(R.string.likely_n1, label);
        TextView title = text(0, 16, 0xFFFFFFFF);
        title.setText(label);
        TextView addr = text(0, 13, 0xFF8E8E93);
        addr.setText(d.getAddress());
        addr.setTypeface(Typeface.MONOSPACE);
        row.addView(title);
        row.addView(addr);
        row.setOnClickListener(v -> {
            dialog.dismiss(); // stops the scan before pairing or connecting
            callback.onPicked(d);
        });
        return row;
    }

    private View header(int res) {
        TextView t = text(res, 13, 0xFF0A84FF);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, dp(14), 0, dp(2));
        return t;
    }

    private TextView text(int res, int sp, int color) {
        TextView t = new TextView(activity);
        if (res != 0) t.setText(res);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        return t;
    }

    private Set<BluetoothDevice> bonded() {
        try {
            Set<BluetoothDevice> s = adapter.getBondedDevices();
            if (s != null) return s;
        } catch (SecurityException ignored) {
        }
        return Collections.emptySet();
    }

    private static String name(BluetoothDevice d, String fallback) {
        String n = null;
        try {
            n = d.getName();
        } catch (SecurityException ignored) {
        }
        if (n == null || n.isEmpty()) n = fallback;
        return n == null || n.isEmpty() ? null : n;
    }

    private static int type(BluetoothDevice d) {
        try {
            return d.getType();
        } catch (SecurityException e) {
            return BluetoothDevice.DEVICE_TYPE_UNKNOWN;
        }
    }

    private static boolean isN1(String name) {
        return name != null && name.trim().toLowerCase(Locale.ROOT).equals(N1_NAME);
    }

    private int dp(int v) {
        return Math.round(v * density);
    }
}
