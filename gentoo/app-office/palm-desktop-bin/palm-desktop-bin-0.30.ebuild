# Copyright 2026 rearman
# Distributed under the terms of the GNU General Public License v2

EAPI=8

inherit desktop

DESCRIPTION="Palm Desktop 3.1.1 look-alike: Date Book, To Do List, Memo Pad, Address Book and palmsync"
HOMEPAGE="https://github.com/rearman/palm-suite"
SRC_URI="
	amd64? ( https://github.com/rearman/palm-suite/releases/download/desktop-v${PV}/palm-desktop-linux-x86_64.tar.gz -> ${P}-amd64.tar.gz )
	arm64? ( https://github.com/rearman/palm-suite/releases/download/desktop-v${PV}/palm-desktop-linux-arm64.tar.gz -> ${P}-arm64.tar.gz )
"
S="${WORKDIR}/palm-desktop"

# The project has no license file, and the icons are Palm Desktop 3.1.1 artwork (see the README).
LICENSE="all-rights-reserved"
SLOT="0"
KEYWORDS="~amd64 ~arm64"
RESTRICT="bindist mirror strip"

# The program is built on Ubuntu 24.04 with g++ 14: it needs glibc 2.38 and the libstdc++ of GCC 13.2 or later.
# It draws with Cairo and Pango, opens a Wayland window, else an X11 one, and uses the Liberation fonts where
# the Windows fonts of Palm Desktop are missing.
RDEPEND="
	>=sys-libs/glibc-2.38
	>=sys-devel/gcc-13.2
	dev-libs/glib:2
	dev-libs/openssl:0/3
	dev-libs/wayland
	media-fonts/liberation-fonts
	net-misc/curl
	sys-apps/dbus
	sys-libs/zlib
	x11-libs/cairo[X]
	x11-libs/libX11
	x11-libs/libXcursor
	x11-libs/libXfixes
	x11-libs/libXinerama
	x11-libs/libxkbcommon
	x11-libs/pango[X]
"

QA_PREBUILT="usr/bin/palm-desktop"

src_install() {
	dobin palm-desktop
	doicon palm-desktop.png
	make_desktop_entry palm-desktop "Palm Desktop" palm-desktop "Office;Calendar;"
}
