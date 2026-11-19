#!/usr/bin/env python3
"""Confirm a Bluetooth pairing request on the N1 without a remote.

Watches the box screen over ADB and taps "配对" once, but only when the pairing dialog
names the expected device (so a stranger's pairing request is never accepted). The N1
shows two dialogs at once (phone-style Settings and TV Settings); the TV one is on top
and is the one that counts.

    python3 tools/confirm_pairing.py 192.168.1.50 "Xiaomi 13 Ultra"

Start it, then tap "连接" in the phone app. Times out after 5 minutes by default.
"""
import argparse
import re
import sys
import time

from adb_tcp import shell

BUTTON = re.compile(r'<node[^>]*text="([^"]*)"[^>]*resource-id="android:id/button1"[^>]*'
                    r'package="com\.android(?:\.tv)?\.settings"[^>]*'
                    r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
TITLE = re.compile(r'text="([^"]*)"[^>]*resource-id="android:id/alertTitle"')
CODE = re.compile(r'text="(\d{6})"')


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument('host', help='box IP address')
    ap.add_argument('name', help='Bluetooth name of your phone, as shown in the dialog title')
    ap.add_argument('--timeout', type=int, default=300, help='seconds to wait (default 300)')
    a = ap.parse_args()

    end = time.time() + a.timeout
    seen = set()
    print('waiting for a pairing dialog from', repr(a.name))
    while time.time() < end:
        xml = shell(a.host, 'uiautomator dump /data/local/tmp/ui.xml >/dev/null 2>&1; '
                            'cat /data/local/tmp/ui.xml; rm -f /data/local/tmp/ui.xml')
        b, t = BUTTON.search(xml), TITLE.search(xml)
        if b and t:
            title = t.group(1)
            if a.name in title and ('配对' in b.group(1) or 'pair' in b.group(1).lower()):
                x1, y1, x2, y2 = map(int, b.groups()[1:])
                shell(a.host, f'input tap {(x1 + x2) // 2} {(y1 + y2) // 2}')
                code = CODE.search(xml)
                print(f'confirmed: {title}' + (f' (code {code.group(1)})' if code else ''))
                return 0
            if title not in seen:
                seen.add(title)
                print('ignoring dialog:', title)
        time.sleep(0.3)
    print('no matching pairing dialog within', a.timeout, 'seconds')
    return 1


if __name__ == '__main__':
    sys.exit(main())
