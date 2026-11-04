#!/usr/bin/env bash
# The screenshot check of the C++ port against the Rust app: each state of steps.txt, in both apps, at the
# three scales, from the store of this folder. Prints each pair that differs and exits 1 when any does.
#
# usage: run.sh <rust palm-desktop> <c++ palm-desktop> <out dir> [state name pattern]
# SCALES="1" limits the scales, e.g. to check new coordinates quickly. PRUNE=1 deletes each pair that matches,
# so only the differing captures stay (as CI keeps them).
#
# Run it from one shell, on one day (the views show today), with the same clipboard (Paste grays when it is
# empty). On Windows put C:/tools/msys64/mingw64/bin first on PATH, or the C++ app stops at a loader error.
# On Linux run it under an X server (xvfb-run); the shot run forces X11.
set -u
here=$(cd "$(dirname "$0")" && pwd)
rust=$1
cpp=$2
out=$3
only=${4:-}
mkdir -p "$out/config/palm-suite"
# The apps read the store from APPDATA on Windows and XDG_CONFIG_HOME elsewhere.
export APPDATA="$out/config"
export XDG_CONFIG_HOME="$out/config"
differ=0

# Captures state $name in both apps at $scale; true when the pair matches.
shoot_pair() {
	local app exe
	for app in rs cpp; do
		exe=$rust
		[ $app = cpp ] && exe=$cpp
		# A fresh store and settings for each run, since a step can change them.
		cp "$here/desktop-data.json" "$out/config/palm-suite/desktop-data.json"
		printf 'startView=%s\n' "$view" >"$out/config/palm-suite/desktop.properties"
		rm -f "$out/$app-$name-$scale.ppm"
		if ! FLTK_SCALING_FACTOR=$scale timeout 60 "$exe" --shot "$out/$app-$name-$scale.ppm" --shot-open "$steps"; then
			echo "$app $name $scale: the run failed"
		fi
	done
	cmp -s "$out/rs-$name-$scale.ppm" "$out/cpp-$name-$scale.ppm"
}

while read -r name view steps; do
	case $name in '' | '#'*) continue ;; esac
	if [ -n "$only" ] && [[ $name != $only ]]; then
		continue
	fi
	# A dash stands for no steps.
	[ "$steps" = - ] && steps=
	for scale in ${SCALES:-1 1.25 1.5}; do
		# A step comes one second after the last, and now and then a dialog is not up yet when it lands
		# (c. 1 run in 180); a port difference repeats, so a pair that differs is taken once more.
		if ! shoot_pair && ! shoot_pair; then
			echo "$name $scale: differs"
			differ=1
		elif [ -n "${PRUNE:-}" ]; then
			rm -f "$out/rs-$name-$scale.ppm" "$out/cpp-$name-$scale.ppm"
		fi
	done
done <"$here/steps.txt"
exit $differ
