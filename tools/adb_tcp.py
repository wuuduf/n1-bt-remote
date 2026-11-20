#!/usr/bin/env python3
"""Minimal ADB-over-TCP client: run one shell command on the box and print its output.

The Phicomm N1 firmware runs adbd on port 5555 as root with ro.adb.secure=0, so no
key exchange is needed. Its adbd also serves only ONE TCP client at a time, and this
client disconnects right after the command, which keeps the slot free for the box
standby helper. No adb binary needed, standard library only.

    python3 tools/adb_tcp.py 192.168.1.50 'getprop ro.build.display.id'
    python3 tools/adb_tcp.py 192.168.1.50 --probe
"""
import argparse
import socket
import struct
import sys

CNXN, OPEN, OKAY, WRTE, CLSE, AUTH = (struct.unpack('<I', c)[0] for c in
                                      (b'CNXN', b'OPEN', b'OKAY', b'WRTE', b'CLSE', b'AUTH'))


def _msg(cmd, a0, a1, data=b''):
    return struct.pack('<6I', cmd, a0, a1, len(data), sum(data) & 0xffffffff, cmd ^ 0xffffffff) + data


def _recv(s):
    h = b''
    while len(h) < 24:
        chunk = s.recv(24 - len(h))
        if not chunk:
            raise ConnectionError('connection closed')
        h += chunk
    cmd, a0, a1, ln, _, _ = struct.unpack('<6I', h)
    d = b''
    while len(d) < ln:
        chunk = s.recv(ln - len(d))
        if not chunk:
            raise ConnectionError('connection closed')
        d += chunk
    return cmd, a0, a1, d


def connect(host, port=5555, timeout=8):
    """Opens a transport and returns (socket, banner)."""
    s = socket.create_connection((host, port), timeout=timeout)
    s.sendall(_msg(CNXN, 0x01000000, 256 * 1024, b'host::\x00'))
    cmd, _, _, data = _recv(s)
    if cmd == AUTH:
        s.close()
        raise PermissionError('adbd wants key authentication (ro.adb.secure=1)')
    if cmd != CNXN:
        s.close()
        raise ConnectionError('not an adb server')
    return s, data.decode(errors='replace')


def shell(host, command, port=5555, timeout=8, run_timeout=None):
    """Runs a shell command and returns its output (stdout and stderr merged).

    `timeout` covers connecting and the handshake only. A command may run for a long
    time without printing anything, so by default there is no limit on that part.
    """
    s, _ = connect(host, port, timeout)
    s.settimeout(run_timeout)
    try:
        s.sendall(_msg(OPEN, 1, 0, ('shell:' + command).encode() + b'\x00'))
        out = b''
        while True:
            cmd, a0, _, d = _recv(s)
            if cmd == WRTE:
                out += d
                s.sendall(_msg(OKAY, 1, a0))
            elif cmd == CLSE:
                break
        return out.decode(errors='replace')
    finally:
        s.close()


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument('host')
    ap.add_argument('command', nargs='?')
    ap.add_argument('--port', type=int, default=5555)
    ap.add_argument('--probe', action='store_true', help='only print the adbd banner')
    a = ap.parse_args()
    if a.probe or not a.command:
        s, banner = connect(a.host, a.port)
        s.close()
        print(banner)
        return
    sys.stdout.write(shell(a.host, a.command, a.port))


if __name__ == '__main__':
    main()
