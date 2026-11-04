#!/usr/bin/env bash
# Packages the C++ app for release.
#
#	packaging/package.sh <app binary> <version> <output file>
#
# Windows (an MSYS2 MINGW64 shell, or Git Bash with the mingw64 bin folder first on PATH, and the WiX 3 bin
# folder on PATH): an MSI of palm-desktop.wxs that holds the program and the MSYS2 DLLs it loads. Linux: a
# tar.gz of one palm-desktop folder with the program and the icon. The version is a.b.c; the MSI takes it as
# its product version, and the tar.gz ignores it.
set -euo pipefail

if [ $# -ne 3 ]; then
	echo "usage: $0 <app binary> <version> <output file>" >&2
	exit 2
fi
app=$1
version=$2
out=$3
here=$(cd "$(dirname "$0")" && pwd)
icons="$here/../../desktop/packaging"
echo "$version" | grep -Eq '^[0-9]+\.[0-9]+\.[0-9]+$' || { echo "bad version $version" >&2; exit 1; }

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

case "$(uname -s)" in
MINGW* | MSYS*)
	stage="$work/stage"
	mkdir -p "$stage"
	cp "$app" "$stage/palm-desktop.exe"
	# The DLLs of MSYS2 that the program loads, as ldd finds them; the DLLs of Windows stay out.
	ldd "$app" | awk '$3 ~ /\/mingw64\/bin\// { print $3 }' | sort -u | while read -r dll; do
		cp "$dll" "$stage/"
	done
	strip "$stage"/*.exe "$stage"/*.dll
	# One File element for each staged file; the program has a fixed id.
	{
		echo '<?xml version="1.0" encoding="utf-8"?>'
		echo '<Include>'
		n=0
		for f in "$stage"/*; do
			name=$(basename "$f")
			if [ "$name" = palm-desktop.exe ]; then
				id=PalmDesktopExe
			else
				n=$((n + 1))
				id="Dll$n"
			fi
			echo "	<File Id=\"$id\" Name=\"$name\" Source=\"$(cygpath -w "$f")\" />"
		done
		echo '</Include>'
	} > "$work/files.wxi"
	candle.exe -nologo -arch x64 -ext WixUIExtension -dVersion="$version" \
		-dIcon="$(cygpath -w "$icons/palm-desktop.ico")" -I"$(cygpath -w "$work")" \
		-out "$(cygpath -w "$work/palm-desktop.wixobj")" "$(cygpath -w "$here/palm-desktop.wxs")"
	light.exe -nologo -ext WixUIExtension -cultures:en-us -out "$(cygpath -w "$work/palm-desktop.msi")" \
		"$(cygpath -w "$work/palm-desktop.wixobj")"
	cp "$work/palm-desktop.msi" "$out"
	;;
Linux)
	mkdir -p "$work/palm-desktop"
	cp "$app" "$work/palm-desktop/palm-desktop"
	strip "$work/palm-desktop/palm-desktop"
	cp "$icons/palm-desktop.png" "$work/palm-desktop/palm-desktop.png"
	tar -C "$work" -czf "$out" palm-desktop
	;;
*)
	echo "no package for $(uname -s)" >&2
	exit 1
	;;
esac
echo "wrote $out"
