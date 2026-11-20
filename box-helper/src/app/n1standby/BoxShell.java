package app.n1standby;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * Minimal ADB-over-TCP client: runs one shell command on the box and returns its output.
 *
 * The N1 firmware opens adbd on port 5555 as root with ro.adb.secure=0, so no key
 * exchange is needed. Blocking; call it off the main thread.
 */
final class BoxShell {

    static final int PORT = 5555;

    private static final int CNXN = 0x4e584e43, OPEN = 0x4e45504f, OKAY = 0x59414b4f,
            WRTE = 0x45545257, CLSE = 0x45534c43, AUTH = 0x48545541;

    /** Banner the box answers with, e.g. "device::ro.product.name=p230;..." */
    static String banner(String host, int timeoutMs) throws IOException {
        try (Socket s = open(host, timeoutMs)) {
            return handshake(s);
        }
    }

    static String run(String host, String command, int timeoutMs) throws IOException {
        try (Socket s = open(host, timeoutMs)) {
            handshake(s);
            OutputStream out = s.getOutputStream();
            DataInputStream in = new DataInputStream(s.getInputStream());
            send(out, OPEN, 1, 0, ("shell:" + command + "\0").getBytes(StandardCharsets.UTF_8));
            ByteArrayOutputStream result = new ByteArrayOutputStream();
            while (true) {
                Msg m = read(in);
                if (m.cmd == WRTE) {
                    result.write(m.data);
                    send(out, OKAY, 1, m.arg0, new byte[0]);
                } else if (m.cmd == CLSE) {
                    break;
                }
            }
            return result.toString("UTF-8");
        }
    }

    private static Socket open(String host, int timeoutMs) throws IOException {
        Socket s = new Socket();
        s.connect(new InetSocketAddress(host, PORT), timeoutMs);
        s.setSoTimeout(timeoutMs);
        return s;
    }

    private static String handshake(Socket s) throws IOException {
        send(s.getOutputStream(), CNXN, 0x01000000, 256 * 1024,
                "host::\0".getBytes(StandardCharsets.UTF_8));
        Msg m = read(new DataInputStream(s.getInputStream()));
        if (m.cmd == AUTH) throw new IOException("adbd wants key authentication");
        if (m.cmd != CNXN) throw new IOException("not an adb server");
        return new String(m.data, StandardCharsets.UTF_8);
    }

    private static final class Msg {
        int cmd, arg0, arg1;
        byte[] data;
    }

    private static void send(OutputStream out, int cmd, int arg0, int arg1, byte[] data) throws IOException {
        int sum = 0;
        for (byte b : data) sum += b & 0xFF;
        ByteBuffer h = ByteBuffer.allocate(24 + data.length).order(ByteOrder.LITTLE_ENDIAN);
        h.putInt(cmd).putInt(arg0).putInt(arg1).putInt(data.length).putInt(sum).putInt(~cmd);
        h.put(data);
        out.write(h.array());
        out.flush();
    }

    private static Msg read(DataInputStream in) throws IOException {
        byte[] hdr = new byte[24];
        in.readFully(hdr);
        ByteBuffer h = ByteBuffer.wrap(hdr).order(ByteOrder.LITTLE_ENDIAN);
        Msg m = new Msg();
        m.cmd = h.getInt();
        m.arg0 = h.getInt();
        m.arg1 = h.getInt();
        int len = h.getInt();
        if (len < 0 || len > 1024 * 1024) throw new IOException("bad adb packet");
        m.data = new byte[len];
        in.readFully(m.data);
        return m;
    }
}
