package n1.tools;

import android.bluetooth.BluetoothAdapter;

/**
 * Turns the box's Bluetooth off or on and waits for the state change.
 * Runs on the box via app_process (root). Usage: BtCtl off|on|state
 */
public final class BtCtl {
    public static void main(String[] args) throws Exception {
        BluetoothAdapter a = BluetoothAdapter.getDefaultAdapter();
        String cmd = args.length > 0 ? args[0] : "state";
        int want = -1;
        if (cmd.equals("off")) {
            a.disable();
            want = BluetoothAdapter.STATE_OFF;
        } else if (cmd.equals("on")) {
            a.enable();
            want = BluetoothAdapter.STATE_ON;
        }
        for (int i = 0; want != -1 && i < 60 && a.getState() != want; i++) Thread.sleep(250);
        int s = a.getState();
        System.out.println("bluetooth state=" + (s == BluetoothAdapter.STATE_ON ? "on" : s == BluetoothAdapter.STATE_OFF ? "off" : String.valueOf(s)));
        System.exit(want == -1 || s == want ? 0 : 1);
    }
}
