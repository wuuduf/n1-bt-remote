package n1.tools;

import java.io.RandomAccessFile;
import java.lang.reflect.Field;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Runs on the TV box via app_process (root). Registers a virtual Bluetooth HID
 * device through /dev/uhid with the same report descriptor as the phone app,
 * so the kernel hid-input mapping and Android key layout can be checked
 * without the phone.
 *
 * Usage: [UHID_ID=vvvv:pppp] [UHID_NAME=name] UhidTest <holdMs> [bit ...]
 *   holdMs   how long the device stays registered before and after the presses
 *   bit      bit index in the 14-key bitmap to press and release, e.g. 8 9;
 *            wN waits N ms; hB:N holds bit B down for N ms
 */
public final class UhidTest {
    // Must stay identical to HidRemote.DESCRIPTOR in the app.
    static final byte[] DESC = {
        0x05, 0x0C, 0x09, 0x01, (byte) 0xA1, 0x01, (byte) 0x85, 0x01,
        0x15, 0x00, 0x25, 0x01, 0x75, 0x01, (byte) 0x95, 0x0E,
        0x09, 0x30, 0x09, 0x40, 0x09, 0x41, 0x09, 0x42, 0x09, 0x43, 0x09, 0x44,
        0x09, 0x45, 0x09, (byte) 0xCF, 0x09, (byte) 0xE9, 0x09, (byte) 0xEA,
        0x0A, 0x23, 0x02, 0x0A, 0x24, 0x02, 0x09, (byte) 0x89, 0x09, (byte) 0x8D,
        (byte) 0x81, 0x02, 0x75, 0x02, (byte) 0x95, 0x01, (byte) 0x81, 0x03, (byte) 0xC0,
    };

    static final int UHID_CREATE = 0, UHID_DESTROY = 1, UHID_INPUT = 8;
    static final int UHID_CREATE2 = 11, UHID_INPUT2 = 12;
    static final int BUS_BLUETOOTH = 0x05;
    static boolean v2;

    static int vid = 0x1234, pid = 0x5678;
    static String name = "N1 Test Remote";

    public static void main(String[] args) throws Exception {
        String id = System.getenv("UHID_ID"); // e.g. 7045:1820 to pose as the Phicomm remote
        String n = System.getenv("UHID_NAME");
        if (n != null && !n.isEmpty()) name = n;
        if (id != null && id.contains(":")) {
            vid = Integer.parseInt(id.split(":")[0], 16);
            pid = Integer.parseInt(id.split(":")[1], 16);
        }
        int hold = args.length > 0 ? Integer.parseInt(args[0]) : 5000;
        RandomAccessFile f = new RandomAccessFile("/dev/uhid", "rw");
        try {
            createV2(f);
            v2 = true;
            System.out.println("created with UHID_CREATE2");
        } catch (Exception e) {
            System.out.println("UHID_CREATE2 failed (" + e.getMessage() + "), trying UHID_CREATE");
            createV1(f);
            System.out.println("created with UHID_CREATE");
        }
        Thread.sleep(hold);
        for (int i = 1; i < args.length; i++) {
            if (args[i].startsWith("w")) { // wait, e.g. w5000
                Thread.sleep(Integer.parseInt(args[i].substring(1)));
                continue;
            }
            int holdMs = 80;
            String bit = args[i];
            if (bit.startsWith("h")) { // hold, e.g. h4:1500 keeps bit 4 down for 1.5 s
                holdMs = Integer.parseInt(bit.substring(bit.indexOf(':') + 1));
                bit = bit.substring(1, bit.indexOf(':'));
            }
            int mask = 1 << Integer.parseInt(bit);
            input(f, new byte[] {1, (byte) mask, (byte) (mask >> 8)});
            Thread.sleep(holdMs);
            input(f, new byte[] {1, 0, 0});
            System.out.println("pressed bit " + args[i]);
            Thread.sleep(600);
        }
        if (args.length > 1) Thread.sleep(hold);
        ByteBuffer d = le(4);
        d.putInt(UHID_DESTROY);
        f.write(d.array());
        f.close();
        System.out.println("destroyed");
    }

    static ByteBuffer le(int n) {
        return ByteBuffer.allocate(n).order(ByteOrder.LITTLE_ENDIAN);
    }

    static void putStr(ByteBuffer b, String s, int len) {
        byte[] bs = s.getBytes();
        byte[] out = new byte[len];
        System.arraycopy(bs, 0, out, 0, Math.min(bs.length, len - 1));
        b.put(out);
    }

    static void createV2(RandomAccessFile f) throws Exception {
        ByteBuffer b = le(4 + 128 + 64 + 64 + 2 + 2 + 16 + 4096);
        b.putInt(UHID_CREATE2);
        putStr(b, name, 128);
        putStr(b, "", 64);
        putStr(b, "", 64);
        b.putShort((short) DESC.length);
        b.putShort((short) BUS_BLUETOOTH);
        b.putInt(vid).putInt(pid).putInt(1).putInt(0);
        b.put(DESC);
        f.write(b.array());
    }

    static void createV1(RandomAccessFile f) throws Exception {
        ByteBuffer desc = ByteBuffer.allocateDirect(DESC.length);
        desc.put(DESC);
        Field fa = Buffer.class.getDeclaredField("address");
        fa.setAccessible(true);
        long addr = fa.getLong(desc);
        ByteBuffer b = le(4 + 128 + 64 + 64 + 8 + 2 + 2 + 16);
        b.putInt(UHID_CREATE);
        putStr(b, name, 128);
        putStr(b, "", 64);
        putStr(b, "", 64);
        b.putLong(addr);
        b.putShort((short) DESC.length);
        b.putShort((short) BUS_BLUETOOTH);
        b.putInt(vid).putInt(pid).putInt(1).putInt(0);
        f.write(b.array());
    }

    static void input(RandomAccessFile f, byte[] report) throws Exception {
        ByteBuffer b = le(4 + 2 + 4096);
        if (v2) {
            b.putInt(UHID_INPUT2);
            b.putShort((short) report.length);
            b.put(report);
        } else {
            b.putInt(UHID_INPUT);
            b.put(report);
            b.position(4 + 4096);
            b.putShort((short) report.length);
        }
        f.write(b.array());
    }
}
