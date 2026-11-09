#!/bin/sh
# Points the overlay at a desktop release: renames the ebuild to VERSION and writes the Manifest from
# the two Linux packages of the release desktop-vVERSION. Run it after CI attached them to that release.
# The overlay keeps one ebuild, so the rename picks the only one there.
# Usage: gentoo/bump.sh 0.24
set -eu
v=${1:?usage: gentoo/bump.sh VERSION}
dir=$(dirname "$0")/app-office/palm-desktop-bin
new=$dir/palm-desktop-bin-$v.ebuild
tmp=$(mktemp)
manifest=$(mktemp)
trap 'rm -f "$tmp" "$manifest"' EXIT
# The Manifest is written aside and the overlay changes only after both packages downloaded, so a release whose
# packages are not attached yet leaves the overlay as it was.
# Each package is named in the Manifest as the ebuild's SRC_URI renames it.
for pair in amd64:x86_64 arm64:arm64; do
	arch=${pair%%:*}
	file=palm-desktop-linux-${pair#*:}.tar.gz
	curl -fsSL -o "$tmp" "https://github.com/rearman/palm-suite/releases/download/desktop-v$v/$file"
	size=$(wc -c < "$tmp" | tr -d ' ')
	b2=$(b2sum "$tmp" | cut -d ' ' -f 1)
	sha=$(sha512sum "$tmp" | cut -d ' ' -f 1)
	echo "DIST palm-desktop-bin-$v-$arch.tar.gz $size BLAKE2B $b2 SHA512 $sha" >> "$manifest"
done
if [ ! -e "$new" ]; then
	mv "$(ls "$dir"/palm-desktop-bin-*.ebuild)" "$new"
fi
cp "$manifest" "$dir/Manifest"
echo "$new and its Manifest now point at desktop-v$v."
