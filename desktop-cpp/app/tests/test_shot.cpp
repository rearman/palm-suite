// links: shot
// The tests of desktop-rs/app/src/shot.rs: the steps of `--shot-open`, and the device pixel of a logical one.
#include "palm/shot.hpp"

#include <doctest/doctest.h>

#include <vector>

namespace shot = palm::shot;

TEST_CASE("parse_steps reads each kind") {
	const auto steps = shot::parse_steps("click:40,10; rclick:5,6;dclick: 7 , 8 ;move:1,2;");
	REQUIRE(steps);
	CHECK(*steps == std::vector<shot::Step>{shot::Click{40, 10}, shot::RightClick{5, 6}, shot::DoubleClick{7, 8},
	                                        shot::Move{1, 2}});
	const auto none = shot::parse_steps("");
	REQUIRE(none);
	CHECK(none->empty());
	CHECK_FALSE(shot::parse_steps("tap:1,2"));
	CHECK_FALSE(shot::parse_steps("click:1"));
	CHECK_FALSE(shot::parse_steps("click:a,2"));
	CHECK_FALSE(shot::parse_steps("click"));
}

TEST_CASE("device reads back as the logical pixel") {
	for (const float s : {1.0f, 1.25f, 1.5f, 1.75f, 2.0f}) {
		for (int v = 0; v < 400; ++v) {
			CHECK_EQ(static_cast<int>(static_cast<float>(shot::device(v, s)) / s), v);
		}
	}
}
