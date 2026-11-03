// links: assets draw msg combo
// The test of desktop-rs/app/src/combo.rs: the step through a list stays inside the list.
#include "palm/combo.hpp"

#include <doctest/doctest.h>

namespace combo = palm::combo;

TEST_CASE("step_index stays inside the list") {
	CHECK_EQ(combo::step_index(0, 1, 3), 1);
	CHECK_EQ(combo::step_index(2, 1, 3), 2);
	CHECK_EQ(combo::step_index(2, -1, 3), 1);
	CHECK_EQ(combo::step_index(0, -1, 3), 0);
	CHECK_EQ(combo::step_index(-1, 1, 3), 0);
	CHECK_EQ(combo::step_index(-1, -1, 3), 0);
	CHECK_EQ(combo::step_index(0, 1, 0), -1);
	CHECK_EQ(combo::step_index(0, 1, 1), 0);
}
