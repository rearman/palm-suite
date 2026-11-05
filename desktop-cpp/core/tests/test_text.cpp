// links: text
// The case mapping of text.hpp against what Rust's str::to_lowercase and to_uppercase give (the tables come from
// the former tools/gen_case), and the UTF-8 check.
#include "palm/text.hpp"

#include <doctest/doctest.h>

#include <limits>

using palm::text::is_utf8;
using palm::text::lower_one;
using palm::text::parse_i32;
using palm::text::to_lowercase;
using palm::text::to_uppercase;
using palm::text::upper_one;

TEST_CASE("case mapping covers scripts past Latin") {
	CHECK(to_lowercase("\xD0\x9F\xD0\xA0\xD0\x98\xD0\x92\xD0\x95\xD0\xA2") ==
	      "\xD0\xBF\xD1\x80\xD0\xB8\xD0\xB2\xD0\xB5\xD1\x82");
	CHECK(to_uppercase("\xCE\xB1\xCE\xB2") == "\xCE\x91\xCE\x92");
	// Deseret, outside the BMP.
	CHECK(to_lowercase("\xF0\x90\x90\x80") == "\xF0\x90\x90\xA8");
	// The titlecase digraph U+01C5 has an upper and a lower form.
	CHECK(upper_one(0x1C5) == 0x1C4);
	CHECK(lower_one(0x1C5) == 0x1C6);
}

TEST_CASE("multi-code-point mappings") {
	// \x65 is "e", written as an escape so it does not run into the hex escape before it.
	CHECK(to_uppercase("stra\xC3\x9F\x65") == "STRASSE");
	CHECK(to_uppercase("\xEF\xAC\x81") == "FI");
	CHECK(to_uppercase("\xC5\x89") == "\xCA\xBCN");
	CHECK(to_lowercase("\xC4\xB0") == "i\xCC\x87");
	// The single-code-point form keeps a code point whose mapping is several.
	CHECK(lower_one(0x130) == 0x130);
	CHECK(upper_one(0xDF) == 0xDF);
}

TEST_CASE("capital sigma lowercases to the final form at the end of a word") {
	// "ΟΔΟΣ" gives "οδος" with the final sigma; a sigma with a letter after it, or none before it, is the medial form.
	CHECK(to_lowercase("\xCE\x9F\xCE\x94\xCE\x9F\xCE\xA3") == "\xCE\xBF\xCE\xB4\xCE\xBF\xCF\x82");
	CHECK(to_lowercase("\xCE\xA3\xCE\x91") == "\xCF\x83\xCE\xB1");
	CHECK(to_lowercase("\xCE\xA3") == "\xCF\x83");
	// Case-ignorable code points (an apostrophe, a combining acute) are skipped on both sides.
	CHECK(to_lowercase("A\xCE\xA3.") == "a\xCF\x82.");
	CHECK(to_lowercase("A'\xCE\xA3'") == "a'\xCF\x82'");
	CHECK(to_lowercase("A\xCE\xA3\xCC\x81\x42") == "a\xCF\x83\xCC\x81\x62");
}

TEST_CASE("a byte that is not UTF-8 passes through the case mapping") {
	CHECK(to_lowercase("A\xFF") == "a\xFF");
	CHECK(to_uppercase("\xC3") == "\xC3");
}

TEST_CASE("is_utf8 tells valid UTF-8 from bytes that are not") {
	CHECK(is_utf8(""));
	CHECK(is_utf8("plain \xC3\xA9 \xF0\x9F\x98\x80 \xEF\xBF\xBD"));
	CHECK_FALSE(is_utf8("\xFF"));
	CHECK_FALSE(is_utf8("\xC3"));
	CHECK_FALSE(is_utf8("\xED\xA0\x80"));
}

TEST_CASE("parse_i32 takes what Rust's str::parse takes") {
	CHECK_EQ(parse_i32("42"), 42);
	CHECK_EQ(parse_i32("+7"), 7);
	CHECK_EQ(parse_i32("-7"), -7);
	CHECK_EQ(parse_i32("-2147483648"), std::numeric_limits<int>::min());
	CHECK_FALSE(parse_i32("2147483648"));
	CHECK_FALSE(parse_i32(""));
	CHECK_FALSE(parse_i32("+"));
	CHECK_FALSE(parse_i32("+-1"));
	CHECK_FALSE(parse_i32(" 1"));
	CHECK_FALSE(parse_i32("1a"));
}
