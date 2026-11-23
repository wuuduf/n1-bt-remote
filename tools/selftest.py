#!/usr/bin/env python3
"""One-command self-test of the box side, with a virtual Bluetooth remote.

Registers a virtual remote through /dev/uhid (the same kernel and Android path a phone
paired over Bluetooth takes) and checks:

  - the kernel key codes and the key layout Android picks for the remote
  - every remote key reaching Android with the right key code
  - a held key repeating on the box
  - the TV key reaching the standby helper
  - standby: CPU capped at 500 MHz, other keys swallowed; then wake and CPU restored
  - with --phicomm: the remapped remote layouts: the power key of the Phicomm remote, of a
    remote that uses Generic.kl and of the Xiaomi Bluetooth voice remote toggles standby, while
    power from HDMI-CEC (cec_input) is ignored
    and its voice key into play / pause

The box screen moves around while it runs (about a minute) and ends on the home screen.
Nothing is installed; only a copy of n1-tools.dex lands in /data/local/tmp. Needs the
standby helper 1.2+ (scripts/box.sh <ip> install-helper) and nothing else holding the
box's adb (it serves one client at a time).

    python3 tools/selftest.py 192.168.1.50
    python3 tools/selftest.py 192.168.1.50 --phicomm
"""
import argparse
import os
import re
import subprocess
import sys
import time

from adb_tcp import push, shell

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DEX_LOCAL = os.path.join(ROOT, 'dist', 'n1-tools.dex')
DEX = '/data/local/tmp/n1-tools.dex'
LOG = '/data/local/tmp/n1-selftest.log'
NAME = 'N1 SelfTest'
CPU = '/sys/devices/system/cpu/cpu0/cpufreq/scaling_max_freq'
MARK = '/dev/.n1remote_standby'
SVC = 'app.n1standby/app.n1standby.StandbyService'
KL = '/system/usr/keylayout/Vendor_7045_Product_1820.kl'
XM = '/system/usr/keylayout/Vendor_2717_Product_32b8.kl'  # Xiaomi Bluetooth voice remote

# report bit -> (name, Linux key code, Android key code)
KEYS = {
    1: ('MENU', '008b', 82), 2: ('OK', '0161', 23), 3: ('UP', '0067', 19), 4: ('DOWN', '006c', 20),
    5: ('LEFT', '0069', 21), 6: ('RIGHT', '006a', 22), 7: ('PLAY/PAUSE', '00a4', 85),
    8: ('VOL+', '0073', 24), 9: ('VOL-', '0072', 25), 10: ('HOME', '00ac', 3), 11: ('BACK', '009e', 4),
}
LINUX_ALL = {v[1] for v in KEYS.values()} | {'0074', '0179', '016a'}  # + POWER, TV, PROGRAM

# Each press takes ~0.7 s in UhidTest; total ~42 s plus app_process start.
SEQUENCE = ('6000 1 2 3 4 5 6 7 8 9 10 11 w1000 h4:1300 w1500 13 w3000 '
            '12 w4000 3 w1500 12 w5000')
SEQUENCE_SECONDS = 45

results = []


def check(ok, what, detail=''):
    results.append(ok)
    print(f"  {'PASS' if ok else 'FAIL'}  {what}" + (f'  ({detail})' if detail else ''), flush=True)
    return ok


def launch(host, seq, env='', name=NAME):
    """Starts UhidTest detached from the adb session so the slot is free for the helper."""
    shell(host, f'logcat -c; rm -f {LOG}; setsid sh -c "{env} UHID_NAME=\'{name}\' '
                f'CLASSPATH={DEX} app_process /system/bin n1.tools.UhidTest {seq} > {LOG} 2>&1" '
                f'< /dev/null > /dev/null 2>&1 & sleep 1')


def wait_done(host, seconds):
    time.sleep(seconds)  # stay off adb meanwhile: the helper needs it
    for _ in range(8):
        if 'destroyed' in shell(host, f'cat {LOG} 2>/dev/null'):
            return True
        time.sleep(5)
    return False


def helper_log(host):
    return shell(host, 'logcat -d -s N1Standby:I').splitlines()


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument('host', help='box IP address')
    ap.add_argument('--phicomm', action='store_true',
                    help='also test the remapped remote layouts (Phicomm and Xiaomi)')
    a = ap.parse_args()
    host = a.host

    print('preflight')
    banner = shell(host, 'getprop ro.product.name').strip()
    if not check(banner == 'p230', 'box answers on adb and is an N1', banner):
        return 1
    pre = shell(host, f'dumpsys package app.n1standby | grep -m1 versionCode; '
                      f'echo acc=$(settings get secure enabled_accessibility_services); '
                      f'echo pkd=$(settings get system power_key_definition); '
                      f'[ -e {MARK} ] && echo state=standby || echo state=awake; echo cpu=$(cat {CPU})')
    code = re.search(r'versionCode=(\d+)', pre)
    if not check(code is not None and int(code.group(1)) >= 3 and SVC in pre,
                 'standby helper 1.2+ installed and enabled',
                 'install the helper and open it once on the box (or scripts/box.sh <ip> install-helper)' if not code else f'versionCode {code.group(1)}'):
        return 1
    if not check('state=awake' in pre, 'box is awake before the test'):
        return 1
    cpu_before = re.search(r'cpu=(\d+)', pre).group(1)
    pkd = re.search(r'pkd=(\S+)', pre).group(1)
    print(f'        power_key_definition={pkd}, CPU max={cpu_before} kHz')

    if not os.path.exists(DEX_LOCAL):
        subprocess.run([os.path.join(ROOT, 'build.sh'), 'tools'], check=True)
    push(host, DEX_LOCAL, DEX)

    print('virtual remote (about a minute, the box screen will move)')
    launch(host, SEQUENCE)
    time.sleep(3)
    dev = shell(host, f'getevent -p 2>/dev/null | awk \'/^add device/{{f=0}} /"{NAME}"/{{f=1}} f\'; '
                      f'dumpsys input | grep -A9 ": {NAME}$" | grep -E "Classes|KeyLayoutFile"')
    key_table = dev.split('KEY (0001):', 1)[-1].split('MSC')[0].split('input props')[0]
    codes = set(re.findall(r'\b0[0-9a-f]{3}\b', key_table))
    check(codes == LINUX_ALL, 'kernel key codes match the descriptor',
          f'missing {sorted(LINUX_ALL - codes)} extra {sorted(codes - LINUX_ALL)}' if codes != LINUX_ALL else '14 keys')
    check('Generic.kl' in dev, 'Android uses Generic.kl for the remote')
    check('Classes: 0x80000021' in dev, 'seen as KEYBOARD|DPAD|EXTERNAL, not as a keyboard or gamepad')

    if not check(wait_done(host, SEQUENCE_SECONDS), 'virtual remote finished'):
        return 1
    log = helper_log(host)
    keys = [tuple(map(int, m.groups()[:3])) + (m.group(4) == "true",)
            for m in (re.search(r'selftest key code=(\d+) action=(\d+) repeat=(\d+) standby=(\w+)', l) for l in log) if m]
    down = {k for k, act, rep, sb in keys if act == 0 and rep == 0 and not sb}
    for bit, (label, _, android) in KEYS.items():
        check(android in down, f'{label:6} reaches Android as key code {android}')
    # Repeats are synthesized by InputDispatcher after the accessibility filter, so the helper
    # only sees DOWN ... UP. Check the key really stayed down; Android repeats after 0.5 s.
    held, down_at = 0.0, None
    for l in log:
        m = re.search(r'(\d\d):(\d\d):(\d\d\.\d+).*selftest key code=20 action=(\d)', l)
        if not m:
            continue
        t = int(m.group(1)) * 3600 + int(m.group(2)) * 60 + float(m.group(3))
        if m.group(4) == '0':
            down_at = t
        elif down_at is not None:
            held, down_at = max(held, t - down_at), None
    check(held >= 1.0, 'DOWN can be held (Android repeats a key held over 0.5 s)', f'held {held:.2f} s')
    check(any('tv -> tv-' in l for l in log), 'TV key handled by the helper (CEC command sent)')
    sb = next((re.search(r'standby max=(\d+)', l).group(1) for l in log if 'toggled -> standby max=' in l), None)
    check(sb == '500000', 'standby caps CPU at 500 MHz', f'max={sb}')
    check(any(k == 19 and act == 0 and s for k, act, rep, s in keys), 'keys are swallowed while in standby')
    aw = next((re.search(r'awake max=(\d+)', l).group(1) for l in log if 'toggled -> awake max=' in l), None)
    check(aw == cpu_before, 'wake restores the CPU limit', f'max={aw}')
    end = shell(host, f'[ -e {MARK} ] && echo standby || echo awake; cat {CPU}').split()
    check(end[:1] == ['awake'] and end[1:2] == [cpu_before], 'box is awake again with its CPU limit restored')

    if a.phicomm:
        print('Phicomm remote layout')
        kl = shell(host, f"grep -E '^key (116|194) ' {KL}")
        check(re.search(r'key 194\s+MEDIA_PLAY_PAUSE', kl) is not None, 'its voice key plays / pauses (key 194 MEDIA_PLAY_PAUSE)',
              ' '.join(kl.split()) or 'missing')
        # A layout with one bad line is not loaded at all, so "Android picks the Phicomm layout"
        # below also proves the patched file still parses.
        if check(re.search(r'key 116\s+TV', kl) is not None, 'Phicomm layout patched (key 116 TV)',
                 ' '.join(kl.split()) or 'missing'):
            launch(host, '3000 0 w6000 0 w4000', env='UHID_ID=7045:1820')
            time.sleep(2.5)
            lay = shell(host, f'dumpsys input | grep -A9 ": {NAME}$" | grep KeyLayoutFile')
            check('Vendor_7045_Product_1820.kl' in lay, 'Android picks the Phicomm layout for ID 7045:1820')
            wait_done(host, 18)
            log = helper_log(host)
            check(any('toggled -> standby' in l for l in log) and any('toggled -> awake' in l for l in log),
                  'its power key puts the box into standby and wakes it')

        print('Generic.kl (remotes without a layout of their own)')
        gen = shell(host, "grep '^key 116 ' /system/usr/keylayout/Generic.kl")
        if check(re.search(r'key 116\s+TV', gen) is not None, 'Generic.kl power -> TV', ' '.join(gen.split()) or 'missing'):
            launch(host, '3000 0 w6000 0 w4000')  # default ID: no layout of its own, so Generic.kl
            wait_done(host, 18)
            log = helper_log(host)
            check(any('toggled -> standby' in l for l in log) and any('toggled -> awake' in l for l in log),
                  'a remote using Generic.kl: power puts the box into standby and wakes it')
            # The TV sends KEY_POWER over HDMI-CEC when it goes to standby; that must not toggle.
            launch(host, '3000 0 w4000', name='cec_input')
            wait_done(host, 10)
            log = helper_log(host)
            check(any('from cec_input ignored' in l for l in log) and not any('toggled' in l for l in log),
                  'power from HDMI-CEC (cec_input) is ignored')

        print('Xiaomi Bluetooth voice remote layout')
        xm = shell(host, f"grep -E '^key (116|102|63) ' {XM} 2>/dev/null")
        want = [r'key 116\s+TV', r'key 102\s+HOME', r'key 63\s+MEDIA_PLAY_PAUSE']
        if check(all(re.search(w, xm) for w in want),
                 'Xiaomi layout: power -> TV, Home -> HOME, voice -> MEDIA_PLAY_PAUSE', ' '.join(xm.split()) or 'missing'):
            # Same virtual remote, posing as the Xiaomi one: its power usage is KEY_POWER (116).
            launch(host, '3000 0 w6000 0 w4000', env='UHID_ID=2717:32b8')
            time.sleep(2.5)
            lay = shell(host, f'dumpsys input | grep -A9 ": {NAME}$" | grep KeyLayoutFile')
            check('Vendor_2717_Product_32b8.kl' in lay, 'Android picks the Xiaomi layout for ID 2717:32b8')
            wait_done(host, 18)
            log = helper_log(host)
            check(any('toggled -> standby' in l for l in log) and any('toggled -> awake' in l for l in log),
                  'its power key puts the box into standby and wakes it')

    shell(host, f'rm -f {LOG}')
    failed = results.count(False)
    print(f'\n{len(results) - failed} passed, {failed} failed')
    return 1 if failed else 0


if __name__ == '__main__':
    sys.exit(main())
