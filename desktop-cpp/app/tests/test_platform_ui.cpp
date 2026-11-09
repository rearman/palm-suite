// links: platform_ui
// The placement of the main window that needs no display: the settings text, and the box a maximized X11 window
// restores to.
#include "palm/platform_ui.hpp"

#include <doctest/doctest.h>

#include <optional>

namespace pui = palm::platform_ui;

TEST_CASE("a placement reads back from its settings text, and a bad text gives none") {
	const pui::Placement p{pui::Box{-1920, 40, 1200, 800}, true};
	CHECK(pui::format_placement(p) == "-1920,40,1200,800,1");
	CHECK(pui::parse_placement(pui::format_placement(p)) == p);
	CHECK(pui::parse_placement("10,20,640,480,0") == pui::Placement{pui::Box{10, 20, 640, 480}, false});
	CHECK_FALSE(pui::parse_placement(""));
	CHECK_FALSE(pui::parse_placement("10,20,640,480"));
	CHECK_FALSE(pui::parse_placement("10,20,640,480,0,0"));
	CHECK_FALSE(pui::parse_placement("10,20,0,480,0"));
	CHECK_FALSE(pui::parse_placement("10,20,640,-5,0"));
	CHECK_FALSE(pui::parse_placement("10,20,640,480,2"));
	CHECK_FALSE(pui::parse_placement("10, 20,640,480,0"));
	CHECK_FALSE(pui::parse_placement("2147483000,20,1000,480,0"));
}

TEST_CASE("a maximized window restores to its last box ere the maximized size, in either order of the events") {
	const pui::Box normal{100, 100, 800, 600};
	const pui::Box full{0, 0, 1920, 1050};

	// The maximized state came first, so only the normal box was noted.
	pui::BoxKeeper state_first;
	state_first.note(pui::Box{50, 50, 640, 480});
	state_first.note(normal);
	CHECK(state_first.restored(full) == normal);

	// The maximized size came ere the state, so it was noted too.
	pui::BoxKeeper size_first;
	size_first.note(normal);
	size_first.note(full);
	size_first.note(full);
	CHECK(size_first.restored(full) == normal);

	CHECK_FALSE(pui::BoxKeeper{}.restored(full));
}
