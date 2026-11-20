#!/usr/bin/env python3
"""Minimal ADB-over-TCP client: run one shell command on the box and print its output.

The Phicomm N1 firmware runs adbd on port 5555 as root with ro.adb.secure=0, so no
key exchange is needed. Its adbd also serves only ONE TCP client at a time, and this
client disconnects right after the command, which keeps the slot free for the box
standby helper. No adb binary needed, standard library only.

    python3 tools/adb_tcp.py 192.168.1.50 'getprop ro.build.display.id'
    python3 tools/adb_tcp.py 192.168.1.50 --probe
    python3 tools/adb_tcp.py 192.168.1.50 --push dist/n1-tools.dex /data/local/tmp/n1-tools.dex
"""
import argparse
import socket
import struct
import sys
import time

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
    s, banner, _ = _connect(host, port, timeout)
    return s, banner


def _connect(host, port, timeout):
    s = socket.create_connection((host, port), timeout=timeout)
    s.sendall(_msg(CNXN, 0x01000000, 256 * 1024, b'host::\x00'))
    cmd, _, maxdata, data = _recv(s)
    if cmd == AUTH:
        s.close()
        raise PermissionError('adbd wants key authentication (ro.adb.secure=1)')
    if cmd != CNXN:
        s.close()
        raise ConnectionError('not an adb server')
    return s, data.decode(errors='replace'), maxdata


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


def push(host, local, remote, mode=0o644, port=5555, timeout=8):
    """Copies a local file to the device over the ADB sync protocol."""
    payload = open(local, 'rb').read()
    s, _, maxdata = _connect(host, port, timeout)
    s.settimeout(30)
    chunk = max(1024, min(maxdata or 4096, 4096)) - 8  # legacy adbd: 4 KB per packet
    try:
        s.sendall(_msg(OPEN, 1, 0, b'sync:\x00'))
        cmd, remote_id, _, _ = _recv(s)
        if cmd != OKAY:
            raise ConnectionError('sync service refused')

        def write(data):
            s.sendall(_msg(WRTE, 1, remote_id, data))
            while True:
                c, a0, _, d = _recv(s)
                if c == OKAY:
                    return
                if c == CLSE:
                    raise ConnectionError('sync closed by device')

        spec = f'{remote},{0o100000 | mode}'.encode()
        write(b'SEND' + struct.pack('<I', len(spec)) + spec)
        for i in range(0, len(payload), chunk):
            part = payload[i:i + chunk]
            write(b'DATA' + struct.pack('<I', len(part)) + part)
        s.sendall(_msg(WRTE, 1, remote_id, b'DONE' + struct.pack('<I', int(time.time()))))
        reply = b''
        while len(reply) < 8:
            c, a0, _, d = _recv(s)
            if c == WRTE:
                reply += d
                s.sendall(_msg(OKAY, 1, a0))
        if reply[:4] != b'OKAY':
            raise IOError('push failed: ' + reply[8:].decode(errors='replace'))
    finally:
        s.close()


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument('host')
    ap.add_argument('command', nargs='?')
    ap.add_argument('--port', type=int, default=5555)
    ap.add_argument('--probe', action='store_true', help='only print the adbd banner')
    ap.add_argument('--push', nargs=2, metavar=('LOCAL', 'REMOTE'), help='copy a file to the box')
    a = ap.parse_args()
    if a.push:
        push(a.host, a.push[0], a.push[1], port=a.port)
        print('pushed', a.push[0], '->', a.push[1])
        return
    if a.probe or not a.command:
        s, banner = connect(a.host, a.port)
        s.close()
        print(banner)
        return
    sys.stdout.write(shell(a.host, a.command, a.port))


if __name__ == '__main__':
    main()
