// links: json
#include "palm/json.hpp"

#include <doctest/doctest.h>
#include <nlohmann/json.hpp>
#include <span>
#include <string>

using palm::json::Obj;

TEST_CASE("opt_str gives the JSON text of a number and a bool") {
	Obj o = nlohmann::json::parse(R"({"n": 12, "b": true, "s": "text", "z": null})");
	CHECK(palm::json::opt_str(o, "n") == "12");
	CHECK(palm::json::opt_str(o, "b") == "true");
	CHECK(palm::json::opt_str(o, "s") == "text");
	CHECK(palm::json::opt_str(o, "z") == "");
	CHECK(palm::json::opt_str(o, "missing") == "");
	CHECK(palm::json::opt_str_or(o, "z", "dflt") == "dflt");
	CHECK(palm::json::opt_str_or(o, "missing", "dflt") == "dflt");
}

TEST_CASE("opt_i32 reads a number, a numeric string, and falls back") {
	Obj o = nlohmann::json::parse(
	    R"({"num": 7, "str": "12", "dec": "12.7", "neg": "-3.9", "bad": "abc", "big": 4294967297})");
	CHECK(palm::json::opt_i32(o, "num", -1) == 7);
	CHECK(palm::json::opt_i32(o, "str", -1) == 12);
	CHECK(palm::json::opt_i32(o, "dec", -1) == 12);
	CHECK(palm::json::opt_i32(o, "neg", -1) == -3);
	CHECK(palm::json::opt_i32(o, "bad", -1) == -1);
	CHECK(palm::json::opt_i32(o, "missing", -1) == -1);
	CHECK(palm::json::opt_i32(o, "big", -1) == 1);
}

TEST_CASE("opt_i64 reads a number, a numeric string, and falls back") {
	Obj o = nlohmann::json::parse(R"({"num": 5000000000, "str": "12", "dec": "12.7", "bad": "x"})");
	CHECK(palm::json::opt_i64(o, "num", 0) == 5000000000LL);
	CHECK(palm::json::opt_i64(o, "str", 0) == 12);
	CHECK(palm::json::opt_i64(o, "dec", 0) == 12);
	CHECK(palm::json::opt_i64(o, "bad", 9) == 9);
	CHECK(palm::json::opt_i64(o, "missing", 9) == 9);
}

TEST_CASE("opt_bool reads a boolean or a true or false string, else false") {
	Obj o = nlohmann::json::parse(R"({"t": true, "f": false, "ts": "TRUE", "fs": "False", "n": 1, "x": "yes"})");
	CHECK(palm::json::opt_bool(o, "t"));
	CHECK_FALSE(palm::json::opt_bool(o, "f"));
	CHECK(palm::json::opt_bool(o, "ts"));
	CHECK_FALSE(palm::json::opt_bool(o, "fs"));
	CHECK_FALSE(palm::json::opt_bool(o, "n"));
	CHECK_FALSE(palm::json::opt_bool(o, "x"));
	CHECK_FALSE(palm::json::opt_bool(o, "missing"));
}

TEST_CASE("opt_array gives the array, or nullptr when not an array") {
	Obj o = nlohmann::json::parse(R"({"a": [1, 2, 3], "s": "text"})");
	const nlohmann::json::array_t* a = palm::json::opt_array(o, "a");
	REQUIRE(a != nullptr);
	CHECK(a->size() == 3);
	CHECK(palm::json::opt_array(o, "s") == nullptr);
	CHECK(palm::json::opt_array(o, "missing") == nullptr);
}

TEST_CASE("at_* reads an array element, and falls back when missing or null") {
	const nlohmann::json::array_t arr = nlohmann::json::parse(R"([1, "x", null, "12.9", true])");
	std::span<const nlohmann::json> a(arr);
	CHECK(palm::json::at_i32(a, 0, 9) == 1);
	CHECK(palm::json::at_str(a, 1, "") == "x");
	CHECK(palm::json::at_str(a, 2, "d") == "d");
	CHECK(palm::json::at_str(a, 4, "") == "true");
	CHECK(palm::json::at_i64(a, 3) == 12);
	CHECK(palm::json::at_i32(a, 9, 5) == 5);
	CHECK(palm::json::at_i64(a, 9) == 0);
	CHECK(palm::json::at_str(a, 9, "e") == "e");
}

TEST_CASE("get_i64 and get_str give an error on a missing key or a wrong type") {
	Obj o = nlohmann::json::parse(R"({"n": 12, "ns": "12", "s": "text", "b": true})");
	REQUIRE(palm::json::get_i64(o, "n").has_value());
	CHECK(palm::json::get_i64(o, "n").value() == 12);
	CHECK(palm::json::get_i64(o, "ns").value() == 12);
	CHECK_FALSE(palm::json::get_i64(o, "missing").has_value());
	CHECK(palm::json::get_i64(o, "missing").error() == "JSONObject[\"missing\"] is not a number.");
	CHECK_FALSE(palm::json::get_i64(o, "s").has_value());
	REQUIRE(palm::json::get_str(o, "s").has_value());
	CHECK(palm::json::get_str(o, "s").value() == "text");
	CHECK_FALSE(palm::json::get_str(o, "n").has_value());
	CHECK(palm::json::get_str(o, "n").error() == "JSONObject[\"n\"] is not a string.");
	CHECK_FALSE(palm::json::get_str(o, "missing").has_value());
}

TEST_CASE("parse_obj accepts an object and rejects a non-object") {
	REQUIRE(palm::json::parse_obj(R"({"a": 1})").has_value());
	CHECK(palm::json::opt_i32(palm::json::parse_obj(R"({"a": 1})").value(), "a", 0) == 1);
	CHECK_FALSE(palm::json::parse_obj("[1]").has_value());
	CHECK(palm::json::parse_obj("[1]").error() == "A JSONObject text must begin with '{'.");
	CHECK_FALSE(palm::json::parse_obj("\"text\"").has_value());
	CHECK_FALSE(palm::json::parse_obj("{").has_value());
	CHECK_FALSE(palm::json::parse_obj("").has_value());
}

TEST_CASE("parse_obj rejects a number out of range") {
	CHECK_FALSE(palm::json::parse_obj(R"({"a":1e400})").has_value());
}

TEST_CASE("parse_obj allows 127 levels of arrays and objects and rejects 128") {
	// An object holding levels - 1 nested arrays, so the text nests levels deep.
	auto nested = [](std::size_t levels) {
		return R"({"a":)" + std::string(levels - 1, '[') + std::string(levels - 1, ']') + "}";
	};
	CHECK(palm::json::parse_obj(nested(127)).has_value());
	REQUIRE_FALSE(palm::json::parse_obj(nested(128)).has_value());
	CHECK(palm::json::parse_obj(nested(128)).error() == "recursion limit exceeded");
	// Brackets inside a string do not count toward the depth.
	CHECK(palm::json::parse_obj(R"({"s":")" + std::string(300, '[') + R"("})").has_value());
}

TEST_CASE("a numeric string that underflows reads 0, and one that overflows falls back") {
	Obj o = nlohmann::json::parse(R"({"under": "1e-400", "neg": "-1e-400", "over": "1e400"})");
	CHECK(palm::json::opt_i64(o, "under", 9) == 0);
	CHECK(palm::json::opt_i64(o, "neg", 9) == 0);
	CHECK(palm::json::opt_i64(o, "over", 9) == 9);
	CHECK(palm::json::opt_i32(o, "over", 9) == 9);
}
