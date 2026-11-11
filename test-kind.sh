#!/bin/sh
# Self-check for `bootstick.sh kind` (runs on macOS or Linux): sh test-kind.sh
# The same fixtures are checked against the Kotlin copy in app/src/test/.../InspectTest.kt.
S=$(dirname "$0")/app/src/main/assets/bootstick.sh
T=$(mktemp -d); trap 'rm -rf "$T"' EXIT
put() { printf "$2" | dd of="$1" bs=1 seek="$3" conv=notrunc 2>/dev/null; }
dd if=/dev/zero of="$T/blank" bs=1024 count=40 2>/dev/null
cp "$T/blank" "$T/disk"; put "$T/disk" '\125\252' 510
cp "$T/blank" "$T/iso"; put "$T/iso" 'CD001' 32769
cp "$T/iso" "$T/hybrid"; put "$T/hybrid" '\125\252' 510
cp "$T/blank" "$T/other"; put "$T/other" 'ANDROID!' 0
for k in blank disk iso hybrid other; do
  r=$(sh "$S" kind "$T/$k")
  [ "$r" = "$k" ] || { echo "FAIL $k: got '$r'"; exit 1; }
done
# El Torito boot record pointing at a catalog in sector 19: BIOS entry only, then with a UEFI section added.
cp "$T/iso" "$T/bios"; put "$T/bios" 'EL TORITO SPECIFICATION' 34823; put "$T/bios" '\023' 34887; put "$T/bios" '\001' 38912
cp "$T/bios" "$T/efi"; put "$T/efi" '\221\357' 38976
[ "$(sh "$S" bios "$T/bios")" = 1 ] && [ -z "$(sh "$S" bios "$T/efi")" ] && [ -z "$(sh "$S" bios "$T/hybrid")" ] ||
  { echo "FAIL bios"; exit 1; }
# Packed images are known by name alone, whatever is inside.
cp "$T/hybrid" "$T/x.IMG.xz"
[ "$(sh "$S" kind "$T/x.IMG.xz")" = compressed ] || { echo "FAIL compressed"; exit 1; }
# Paths from the file picker's /proc/self/fd links map to the kernel's view.
fix() { sh -c ". /dev/stdin; fixpath '$1'" <<EOF
$(sed -n '/^fixpath()/,/^}/p' "$S")
EOF
}
[ "$(fix /mnt/user/0/emulated/0/Download/a.iso)" = /data/media/0/Download/a.iso ] &&
  [ "$(fix /mnt/pass_through/0/1234-ABCD/a.iso)" = /mnt/media_rw/1234-ABCD/a.iso ] &&
  [ "$(fix /storage/1234-ABCD/a.iso)" = /mnt/media_rw/1234-ABCD/a.iso ] || { echo "FAIL fixpath"; exit 1; }
echo ok
