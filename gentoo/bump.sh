#!/bin/sh
# Points the overlay at a desktop release: renames the ebuild to VERSION and writes the Manifest from
# the jars tarball of the release desktop-vVERSION. Run it after CI attached the jars to that release.
# The overlay keeps one ebuild, so the rename picks the only one there.
# Usage: gentoo/bump.sh 0.8
set -eu
v=${1:?usage: gentoo/bump.sh VERSION}
dir=$(dirname "$0")/app-office/palm-desktop-bin
new=$dir/palm-desktop-bin-$v.ebuild
if [ ! -e "$new" ]; then
	mv "$(ls "$dir"/palm-desktop-bin-*.ebuild)" "$new"
fi
tmp=$(mktemp)
trap 'rm -f "$tmp"' EXIT
curl -fsSL -o "$tmp" "https://github.com/rearman/palm-suite/releases/download/desktop-v$v/palm-desktop-jars.tar.gz"
size=$(wc -c < "$tmp" | tr -d ' ')
b2=$(b2sum "$tmp" | cut -d ' ' -f 1)
sha=$(sha512sum "$tmp" | cut -d ' ' -f 1)
echo "DIST palm-desktop-bin-$v.tar.gz $size BLAKE2B $b2 SHA512 $sha" > "$dir/Manifest"
echo "$new and its Manifest now point at desktop-v$v."
