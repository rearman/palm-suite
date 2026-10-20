# Copyright 2026 rearman
# Distributed under the terms of the GNU General Public License v2

EAPI=8

inherit desktop java-pkg-2

DESCRIPTION="Palm Desktop 3.1.1 look-alike: Date Book, To Do List, Memo Pad and palmsync"
HOMEPAGE="https://github.com/rearman/palm-suite"
SRC_URI="https://github.com/rearman/palm-suite/releases/download/desktop-v${PV}/palm-desktop-jars.tar.gz -> ${P}.tar.gz"
S="${WORKDIR}/palm-desktop"

# The project has no license file, and the icons are Palm Desktop 3.1.1 artwork (see the README).
LICENSE="all-rights-reserved"
SLOT="0"
KEYWORDS="~amd64 ~arm64"
RESTRICT="bindist mirror"

RDEPEND="
	>=virtual/jre-17:*
	media-fonts/liberation-fonts
"

src_install() {
	java-pkg_dojar lib/*.jar
	java-pkg_dolauncher palm-desktop --main com.rearman.palmdesktop.MainKt
	doicon palm-desktop.png
	make_desktop_entry palm-desktop "Palm Desktop" palm-desktop "Office;Calendar;"
}
