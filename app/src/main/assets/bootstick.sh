#!/system/bin/sh
# BootStick engine: show disk images to a PC as USB drives or CD-ROMs via the configfs (or legacy android_usb) gadget.
# usage: su -c 'sh bootstick.sh attach /sdcard/Download/x.iso [disk|ro|cdrom] [<file> <mode>]...'   detach: su -c 'sh bootstick.sh detach'
# other commands: info | scan | kind <file> | bios <file> | diag | read <file> | put <file> | commit <file> <size> | rm <file> | mv <file> <name>

# Run in init's mount namespace so /data/media and /mnt/media_rw resolve the same whichever su started us.
if [ -e /proc/1/ns/mnt ] && [ "$(readlink /proc/1/ns/mnt)" != "$(readlink /proc/$$/ns/mnt)" ] &&
   nsenter -t 1 -m -- true 2>/dev/null; then
  exec nsenter -t 1 -m -- sh "$0" "$@"
fi

die() { echo "error=$*"; exit 1; }

# /sdcard is a per-app view; the kernel needs the real path. The /mnt/user and /mnt/pass_through forms are what
# /proc/self/fd shows for files opened through the system file picker.
fixpath() {
  case $1 in
    /sdcard/*) echo "/data/media/0/${1#/sdcard/}" ;;
    /storage/emulated/0/*|/mnt/*/0/emulated/0/*|/mnt/runtime/*/emulated/0/*) echo "/data/media/0/${1#*/emulated/0/}" ;;
    /storage/self/primary/*) echo "/data/media/0/${1#/storage/self/primary/}" ;;
    /storage/*-*/*) echo "/mnt/media_rw/${1#/storage/}" ;;
    /mnt/user/0/*-*/*|/mnt/pass_through/0/*-*/*) echo "/mnt/media_rw/${1#/mnt/*/0/}" ;;
    *) echo "$1" ;;
  esac
}

# Sets BACKEND, G (gadget) C (config) F (mass_storage function) L (first lun) UDC, or REASON on failure.
# Phones whose kernel predates configfs gadgets get BACKEND=legacy with A (the android_usb gadget) instead.
discover() {
  BACKEND=configfs CFS= G= C= F= UDC=
  while read -r _ mnt type _; do [ "$type" = configfs ] && { CFS=$mnt; break; }; done < /proc/mounts
  if [ ! -d "$CFS/usb_gadget" ]; then
    A=/sys/class/android_usb/android0
    [ -d "$A/f_mass_storage" ] || { REASON=no_configfs_usb_gadget; return 1; }
    BACKEND=legacy F=$A/f_mass_storage
    L=$F/lun; [ -d "$L" ] || L=$F/lun0
    [ -d "$L" ] || { REASON=kernel_lacks_mass_storage; return 1; }
    return 0
  fi
  for g in "$CFS"/usb_gadget/*; do [ -n "$(cat "$g/UDC" 2>/dev/null)" ] && { G=$g; break; }; done
  if [ -z "$G" ]; then
    for g in "$CFS"/usb_gadget/*; do for c in "$g"/configs/*; do [ -d "$c" ] && G=$g; done; [ -n "$G" ] && break; done
  fi
  [ -n "$G" ] || { REASON=no_usb_gadget; return 1; }
  for c in "$G"/configs/*; do [ -d "$c" ] && { C=$c; break; }; done
  [ -n "$C" ] || { REASON=no_usb_config; return 1; }
  for f in "$G"/functions/mass_storage.*; do [ -d "$f" ] && { F=$f; break; }; done
  if [ -z "$F" ]; then
    mkdir "$G/functions/mass_storage.0" 2>/dev/null || { REASON=kernel_lacks_mass_storage; return 1; }
    F=$G/functions/mass_storage.0
  fi
  L=$F/lun.0
  UDC=$(getprop sys.usb.controller)
  [ -n "$UDC" ] || UDC=$(cat "$G/UDC" 2>/dev/null)
  if [ -z "$UDC" ]; then
    for u in /sys/class/udc/*; do case ${u##*/} in dummy*|'*') ;; *) UDC=${u##*/}; break ;; esac; done
  fi
  [ -n "$UDC" ] || { REASON=no_udc; return 1; }
}

# Every LUN, in order: one drive each. lun.0 always exists; more are made by attach and removed by detach.
luns() {
  if [ "$BACKEND" = legacy ]; then echo "$L"; return; fi
  for l in "$F"/lun.*; do [ -d "$l" ] && echo "$l"; done
}
lunmode() { [ "$(cat "$1/cdrom" 2>/dev/null)" = 1 ] && echo cdrom || { [ "$(cat "$1/ro" 2>/dev/null)" = 1 ] && echo ro || echo disk; }; }
clear_luns() { for l in $(luns); do echo > "$l/file"; [ "${l##*.}" = 0 ] || [ "$BACKEND" = legacy ] || rmdir "$l"; done; }

# Every link in the config that points at a mass_storage function (ours, or the old bootstick.sh "f9").
ms_links() { for l in "$C"/*; do [ -L "$l" ] && case $(readlink "$l") in *mass_storage*) echo "$l" ;; esac; done; }
unbind() { echo none > "$G/UDC" 2>/dev/null; }
# The USB HAL re-binds the UDC ~300 ms after we unbind it (its FunctionFS monitor sees adb come back),
# so only touch links in a tight unbind+link loop, and leave the bind to it when it's faster than us.
bind() {
  if [ "$BACKEND" = legacy ]; then echo 1 > "$A/enable"; return; fi
  [ -n "$(cat "$G/UDC")" ] || echo "$UDC" > "$G/UDC" || [ -n "$(cat "$G/UDC")" ]
}
# Links become USB interfaces in link order, and many PC firmwares (HP's among them) only look at interface 0
# when building the boot menu. So mass storage goes first and the HAL's own functions (adb, mtp...) after it.
relink() {
  for l in "$C"/*; do [ -L "$l" ] && rm -f "$l"; done
  [ "$1" = ms ] && { ln -s "$F" "$C/bootstick" 2>/dev/null || return 1; }
  for o in $others; do ln -s "$G/functions/${o#*:}" "$C/${o%%:*}" 2>/dev/null || return 1; done
}
link() {
  if [ "$BACKEND" = legacy ]; then
    # The legacy gadget has a fixed function list; mass storage first, and adb kept if it was on.
    fns=mass_storage; case $(cat "$A/functions") in *adb*) fns=$fns,adb ;; esac
    echo "$fns" > "$A/functions"; return
  fi
  others=$(for l in "$C"/*; do [ -L "$l" ] && t=$(readlink "$l") && echo "${l##*/}:${t##*/}"; done)
  i=0
  until unbind; relink ms; do
    i=$((i + 1)); [ $i -lt 20 ] || { unbind; relink; return 1; }
  done
}
unlink_all() {
  if [ "$BACKEND" = legacy ]; then echo 0 > "$A/enable"; return; fi
  unbind; for l in $(ms_links); do rm -f "$l"; done
}

info() {
  [ "$(id -u)" = 0 ] && echo root=1 || echo root=0
  if discover; then
    echo "backend=$BACKEND"
    if [ "$BACKEND" = legacy ]; then
      echo "gadget=$A"; echo "func=$F"
      case $(cat "$A/functions") in *mass_storage*) echo linked=1 ;; *) echo linked=0 ;; esac
      [ "$(cat "$A/enable")" = 1 ] && echo bound=1 || echo bound=0
    else
      echo "gadget=$G"; echo "config=$C"; echo "func=$F"; echo "udc=$UDC"
      [ -n "$(ms_links)" ] && echo linked=1 || echo linked=0
      [ -n "$(cat "$G/UDC")" ] && echo bound=1 || echo bound=0
    fi
    echo "file=$(cat "$L/file")"; echo "ro=$(cat "$L/ro" 2>/dev/null)"; echo "cdrom=$(cat "$L/cdrom" 2>/dev/null)"
    # One line per drive: lun<n>=<mode>:<file>. The file is empty once the computer ejects it.
    i=0; for l in $(luns); do echo "lun$i=$(lunmode "$l"):$(cat "$l/file")"; i=$((i + 1)); done
  else
    echo backend=none; echo "reason=$REASON"
  fi
}

# lun file mode: ro and cdrom can only change while no file is open, so they go first.
setlun() {
  [ -e "$1/cdrom" ] && { [ "$3" = cdrom ] && echo 1 > "$1/cdrom" || echo 0 > "$1/cdrom"; }
  [ -e "$1/ro" ] && { [ "$3" = disk ] && echo 0 > "$1/ro" || echo 1 > "$1/ro"; }
  # Removable lets the computer eject it; MountService puts it back the next time the cable goes into a computer.
  [ -e "$1/removable" ] && echo 1 > "$1/removable"
  # Shown as the drive name in Disk Utility and BIOS boot menus: vendor(8) product(16) rev(4).
  # Built first and written with one echo: toybox printf issues a write() per field and configfs keeps only the last.
  name=$(printf '%-8.8s%-16.16s%-4.4s' BootStik "${2##*/}" 1.0)
  [ -e "$1/inquiry_string" ] && echo "$name" > "$1/inquiry_string" 2>/dev/null
  echo "$2" > "$1/file"
}

# attach <file> [mode] [<file> <mode>]...: every pair becomes its own drive, in order.
attach() {
  [ $# -gt 0 ] || die "nothing to attach"
  [ $# -le 16 ] || die "at most 8 images at once"
  odd=1
  for a; do
    if [ $odd = 1 ]; then [ -f "$(fixpath "$a")" ] || die "no such file: $(fixpath "$a")"
    else case $a in disk|ro|cdrom) ;; *) die "unknown mode $a (use disk, ro or cdrom)" ;; esac
    fi
    odd=$((1 - odd))
  done
  discover || die "$REASON"
  if [ "$BACKEND" = legacy ]; then
    [ $# -le 2 ] || die "this phone's USB system can show only one image at a time"
    [ "$2" = cdrom ] && [ ! -e "$L/cdrom" ] && die "this phone's kernel can't show a CD-ROM; mount it as a read-only USB disk"
  fi
  unlink_all
  clear_luns
  n=0
  while [ $# -gt 0 ]; do
    img=$(fixpath "$1"); mode=${2:-disk}; shift; [ $# -gt 0 ] && shift
    l=$L; [ $n = 0 ] || l=$F/lun.$n
    [ -d "$l" ] || mkdir "$l" 2>/dev/null || { clear_luns; bind; die "the kernel couldn't add drive $((n + 1))"; }
    setlun "$l" "$img" "$mode" || { clear_luns; bind; die "kernel could not open $img"; }
    n=$((n + 1))
  done
  link || { clear_luns; bind; die "could not link mass storage into $C"; }
  bind || die "could not bind USB controller $UDC"
  info
}

detach() {
  discover || die "$REASON"
  unlink_all
  clear_luns
  [ -e "$L/ro" ] && echo 0 > "$L/ro"; [ -e "$L/cdrom" ] && echo 0 > "$L/cdrom"
  if [ "$BACKEND" = legacy ]; then
    # Let init rebuild the USB mode the user had (MTP, adb...).
    setprop sys.usb.config none; setprop sys.usb.config "$(getprop persist.sys.usb.config)"
  else
    bind
  fi
  info
}

# Same rules as kindOf() in Inspect.kt: hybrid = ISO with an MBR (boots as a USB disk), iso = plain ISO 9660
# (needs CD-ROM, e.g. Windows), disk = partitioned or formatted image, blank = all zeros, other = anything else.
# compressed = an .iso or .img packed as .xz, .gz or .zip, which has to be unpacked before it can be mounted.
kind() {
  case $(echo "$1" | tr A-Z a-z) in *.iso.xz|*.img.xz|*.iso.gz|*.img.gz|*.iso.zip|*.img.zip) echo compressed; return ;; esac
  mbr= iso=
  [ "$(od -An -tx1 -j510 -N2 "$1" 2>/dev/null | tr -d ' \n')" = 55aa ] && mbr=1
  [ "$(dd if="$1" bs=1 skip=32769 count=5 2>/dev/null)" = CD001 ] && iso=1
  if [ -n "$iso" ]; then [ -n "$mbr" ] && echo hybrid || echo iso
  elif [ -n "$mbr" ]; then echo disk
  # od prints "*" for repeated lines, so strip it along with the zeros.
  elif [ -z "$(od -An -tx1 -N4096 "$1" 2>/dev/null | tr -d ' 0\n*')" ]; then echo blank
  else echo other
  fi
}

# Same rule as biosOnly() in Inspect.kt: a bootable ISO whose El Torito catalog has no UEFI (platform 0xEF) entry.
# UEFI-only PCs (most since ~2020) leave these out of the boot menu. Prints "1" if so.
bios() {
  [ "$(dd if="$1" bs=1 skip=34823 count=23 2>/dev/null)" = "EL TORITO SPECIFICATION" ] || return
  cat=$(od -An -tu4 -j34887 -N4 "$1" | tr -d ' ')
  # Catalog entries are 32 bytes (two od lines); headers (01, 90, 91) carry the platform in their second byte.
  dd if="$1" bs=2048 skip="$cat" count=1 2>/dev/null | od -An -tx1 -v |
    while read -r a b _; do read -r _; case "$a$b" in 01ef|90ef|91ef) exit 1 ;; esac; done && echo 1
}

# Shared storage, plus BootStick's own folder on SD cards (where downloads go when saved to the SD card).
scan() {
  find /data/media/0 /mnt/media_rw/* /mnt/media_rw/*/Android/data/dev.aasim.bootstick/files \
    \( -path '*/Android/data' -o -path '*/Android/obb' \) -prune -o -type f \( -iname '*.iso' -o -iname '*.img' \
    -o -iname '*.bin' -o -iname '*.raw' -o -iname '*.ima' -o -iname '*.dsk' -o -iname '*.vhd' \
    -o -iname '*.iso.xz' -o -iname '*.img.xz' -o -iname '*.iso.gz' -o -iname '*.img.gz' -o -iname '*.iso.zip' -o -iname '*.img.zip' \) \
    -size +1M -print 2>/dev/null |
  while IFS= read -r f; do printf '%s\t%s\t%s\t%s\n' "$(stat -c %s "$f")" "$(kind "$f")" "$(bios "$f")" "$f"; done
}

diag() {
  echo "== device"
  echo "$(getprop ro.product.manufacturer) $(getprop ro.product.model) ($(getprop ro.product.device))"
  echo "Android $(getprop ro.build.version.release) (SDK $(getprop ro.build.version.sdk)), $(getprop ro.build.display.id)"
  uname -a
  echo "su: $(su -v 2>&1)  selinux: $(getenforce 2>&1)"
  echo "== usb props"; getprop | grep -E 'sys\.usb\.'
  echo "== udc"; ls /sys/class/udc
  echo "== engine"; info
  while read -r _ mnt type _; do [ "$type" = configfs ] && { CFS=$mnt; break; }; done < /proc/mounts
  for g in "$CFS"/usb_gadget/*; do
    echo "== gadget $g UDC=$(cat "$g/UDC" 2>/dev/null)"; ls -l "$g"/configs/*/
    echo "functions: $(ls "$g/functions" | tr '\n' ' ')"
  done
  for l in $(luns); do echo "== $l"; for a in "$l"/*; do echo "${a##*/}=$(cat "$a")"; done; done
  echo "== kernel config"
  zcat /proc/config.gz 2>/dev/null | grep -E 'USB_CONFIGFS|MASS_STORAGE' || echo "(no /proc/config.gz)"
  echo "== modules"; grep -iE 'mass|gadget|configfs' /proc/modules
  echo "== dmesg"; dmesg 2>/dev/null | grep -iE 'mass_storage|f_mass|lun|configfs|udc' | tail -n 30
  echo "== legacy android_usb"; ls /sys/class/android_usb 2>&1
  [ -d /sys/class/android_usb/android0 ] && for a in enable functions; do echo "$a=$(cat /sys/class/android_usb/android0/$a)"; done
}

# The file commands below are for images scan found: other apps' files are only readable as root.
# They only touch image files in shared storage, so a bug in the app can't reach anything else.
target() {
  f=$(fixpath "$1")
  case $f in /data/media/0/*|/mnt/media_rw/*) ;; *) echo "error=not in shared storage: $1"; return 1 ;; esac
  case $f in */../*|*/..) echo "error=bad path: $1"; return 1 ;; esac
  case $(echo "${f##*/}" | tr A-Z a-z) in
    *.iso|*.img|*.bin|*.raw|*.ima|*.dsk|*.vhd|*.iso.xz|*.img.xz|*.iso.gz|*.img.gz|*.iso.zip|*.img.zip) echo "$f" ;;
    *) echo "error=not an image file: $1"; return 1 ;;
  esac
}
inuse() { discover && for l in $(luns); do [ "$(cat "$l/file")" = "$1" ] && return 0; done; return 1; }
# A file root made should look like its neighbours, so the phone's file managers can open it.
own() {
  chown "$(stat -c %u:%g "${1%/*}")" "$1" 2>/dev/null; chmod 0660 "$1"
  chcon "$(stat -c %C "${1%/*}")" "$1" 2>/dev/null
}

cmd=$1; [ $# -gt 0 ] && shift
case $cmd in
  info) info ;;
  attach) attach "$@" ;;
  detach|off) detach ;;
  scan) scan ;;
  kind) kind "$1" ;;
  bios) bios "$1" ;;
  diag) diag ;;
  read) f=$(target "$1") || { echo "$f"; exit 1; }; cat "$f" ;;
  # put writes stdin to <file>.part; commit then keeps it if it has <size> bytes, and deletes it otherwise.
  put) f=$(target "$1") || { echo "$f"; exit 1; }; [ -e "$f" ] && die "${f##*/} already exists"; cat > "$f.part" ;;
  commit)
    f=$(target "$1") || { echo "$f"; exit 1; }
    [ "$(stat -c %s "$f.part" 2>/dev/null)" = "$2" ] || { rm -f "$f.part"; die "the file was not written completely"; }
    mv "$f.part" "$f" && own "$f" && echo "file=$f" ;;
  rm)
    f=$(target "$1") || { echo "$f"; exit 1; }
    inuse "$f" && die "it's mounted; eject it first"
    rm -f "$f" && echo "file=$f" ;;
  mv)
    f=$(target "$1") || { echo "$f"; exit 1; }
    case $2 in ''|*/*) die "bad name: $2" ;; esac
    t=$(target "${f%/*}/$2") || { echo "$t"; exit 1; }
    [ -e "$t" ] && die "$2 already exists"
    inuse "$f" && die "it's mounted; eject it first"
    mv "$f" "$t" && echo "file=$t" ;;
  ''|-h|help) sed -n '2,4p' "$0" ;;
  *) attach "$cmd" "$@" ;; # old usage: bootstick.sh <path> [cdrom]
esac
