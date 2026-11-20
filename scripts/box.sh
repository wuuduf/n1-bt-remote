#!/usr/bin/env bash
# Box-side setup for the Phicomm N1, over its network ADB (port 5555, root).
#
#   scripts/box.sh <box-ip> install-helper     install + enable the standby helper,
#                                              and stop the native power key from shutting down
#   scripts/box.sh <box-ip> patch-remote       make the Phicomm remote's power key send TV
#                                              (so the helper handles it); backs up first
#   scripts/box.sh <box-ip> unpatch-remote     restore the original Phicomm key layout
#   scripts/box.sh <box-ip> status             helper, power key setting, standby state
#   scripts/box.sh <box-ip> update-descriptor <phone-mac>  after an app update that adds keys:
#                                              swap the descriptor the box stored, no re-pairing
#   scripts/box.sh <box-ip> uninstall-helper   remove the helper, power key back to factory (shutdown)
#
# Needs adb (Android platform-tools). Disconnects at the end on purpose: the N1's adbd
# serves only one TCP client at a time, and the helper needs that slot.
set -euo pipefail
cd "$(dirname "$0")/.."

usage() { sed -n '2,15p' "$0" | sed 's/^# \{0,1\}//'; exit 2; }
[ $# -ge 2 ] || usage
HOST=$1 CMD=$2 T="$1:5555"

KL=/system/usr/keylayout/Vendor_7045_Product_1820.kl
BAK=/data/local/tmp/btbak
SVC=app.n1standby/app.n1standby.StandbyService

adb connect "$T" >/dev/null
trap 'adb disconnect "$T" >/dev/null 2>&1 || true' EXIT
sh_() { adb -s "$T" shell "$1"; }

case "$CMD" in
install-helper)
    [ -f dist/n1-standby-helper.apk ] || ./build.sh helper
    adb -s "$T" install -r dist/n1-standby-helper.apk
    sh_ "cur=\$(settings get secure enabled_accessibility_services)
         case \"\$cur\" in
           *$SVC*) ;;
           null|'') settings put secure enabled_accessibility_services $SVC ;;
           *) settings put secure enabled_accessibility_services \"\$cur:$SVC\" ;;
         esac
         settings put secure accessibility_enabled 1
         settings put system power_key_definition 0
         echo 'helper enabled; native power key no longer shuts the box down'"
    ;;
patch-remote)
    sh_ "mount -o remount,rw /system 2>/dev/null; mkdir -p $BAK
         if grep -q '^key 116 *TV\$' $KL; then echo 'already patched'; exit 0; fi
         grep -q '^key 116 *POWER\$' $KL || { echo 'unexpected layout, not touching it'; exit 1; }
         [ -f $BAK/Vendor_7045_Product_1820.kl.orig ] || cp $KL $BAK/Vendor_7045_Product_1820.kl.orig
         sed 's/^key 116\\( *\\)POWER\$/key 116\\1TV/' $KL > $BAK/patched.kl
         grep -q '^key 116 *TV\$' $BAK/patched.kl && cat $BAK/patched.kl > $KL && sync
         rm -f $BAK/patched.kl; grep '^key 116' $KL
         echo 'patched; re-pair or reconnect the remote for it to take effect'"
    ;;
unpatch-remote)
    sh_ "mount -o remount,rw /system 2>/dev/null
         if [ -f $BAK/Vendor_7045_Product_1820.kl.orig ]; then cat $BAK/Vendor_7045_Product_1820.kl.orig > $KL
         else sed 's/^key 116\\( *\\)TV\$/key 116\\1POWER/' $KL > /data/local/tmp/kl.tmp && cat /data/local/tmp/kl.tmp > $KL && rm /data/local/tmp/kl.tmp; fi
         sync; grep '^key 116' $KL"
    ;;
status)
    sh_ "echo \"firmware:           \$(getprop ro.build.display.id)\"
         echo \"helper installed:   \$(pm list packages app.n1standby | grep -c n1standby)\"
         echo \"accessibility:      \$(settings get secure enabled_accessibility_services)\"
         echo \"power_key_definition: \$(settings get system power_key_definition)  (0 none, 1 shutdown, 2 reboot)\"
         echo \"phicomm remote:     \$(grep '^key 116' $KL)\"
         [ -e /dev/.n1remote_standby ] && echo 'state:              standby' || echo 'state:              awake'
         logcat -d | grep N1Standby | tail -3"
    ;;
update-descriptor)
    # Bluedroid keeps the HID descriptor it got at pairing time. After an app update that
    # adds keys, swap the stored copy instead of re-pairing (see docs/findings.md §6).
    [ -n "${3:-}" ] || { echo "usage: $0 <box-ip> update-descriptor <phone-bt-mac>" >&2; exit 2; }
    MAC=$(echo "$3" | tr 'A-Z' 'a-z')
    HEX=$(python3 - <<'PY'
import re
s = open('app/src/app/n1remote/HidRemote.java').read()
i = s.index('DESCRIPTOR = {'); j = s.index('};', i)
body = re.sub(r'//[^\n]*', '', s[i + 14:j]).replace('REPORT_ID', '1').replace('(byte)', '')
print(''.join('%02x' % (int(t, 16) if t.lower().startswith('0x') else int(t))
              for t in re.findall(r'0x[0-9A-Fa-f]+|\b\d+\b', body)))
PY
)
    [ -f dist/n1-tools.dex ] || ./build.sh tools
    adb -s "$T" push -q dist/n1-tools.dex /data/local/tmp/n1-tools.dex
    sh_ "D=/data/misc/bluedroid; B=$BAK; BT='CLASSPATH=/data/local/tmp/n1-tools.dex app_process /system/bin n1.tools.BtCtl'
         grep -q '^\[$MAC\]' \$D/bt_config.conf || { echo 'phone $MAC is not paired with this box'; exit 1; }
         mkdir -p \$B; cp \$D/bt_config.conf \$B/bt_config.conf.before-update; cp \$D/bt_config.bak \$B/bt_config.bak.before-update
         eval \$BT off; sleep 4
         for f in bt_config.conf bt_config.bak; do
           awk -v sec='[$MAC]' -v hex='$HEX' '/^\[/{s=\$0} s==sec && /^HidDescriptor = /{print \"HidDescriptor = \" hex; next} {print}' \$D/\$f > \$B/\$f.new && cat \$B/\$f.new > \$D/\$f; rm -f \$B/\$f.new
         done
         sleep 3; n=\$(grep -c 'HidDescriptor = $HEX' \$D/bt_config.conf)
         eval \$BT on
         [ \"\$n\" = 1 ] && echo 'descriptor updated; reopen the phone app to reconnect' || echo 'update did not stick, backups in '\$B"
    ;;
uninstall-helper)
    sh_ "cur=\$(settings get secure enabled_accessibility_services)
         new=\$(echo \"\$cur\" | sed -e 's#$SVC##' -e 's/::*/:/g' -e 's/^://' -e 's/:\$//' -e 's/^null\$//')
         if [ -n \"\$new\" ]; then settings put secure enabled_accessibility_services \"\$new\"
         else settings delete secure enabled_accessibility_services; settings put secure accessibility_enabled 0; fi
         [ -e /dev/.n1remote_standby ] && { echo 1 > /sys/class/amhdmitx/amhdmitx0/phy; echo 0 > /sys/class/graphics/fb0/blank; rm -f /dev/.n1remote_standby; }
         settings put system power_key_definition 1"
    adb -s "$T" uninstall app.n1standby || true
    echo "helper removed; power key is back to factory (shutdown)"
    ;;
*)
    usage
    ;;
esac
