// The helpers of the vector tests: sync-core/testdata/<name>.json read as JSON, and strict reads of its
// values, as in desktop-rs/core/tests/vectors.rs.
#pragma once

#include <doctest/doctest.h>
#include <fstream>
#include <nlohmann/json.hpp>
#include <sstream>
#include <stdexcept>
#include <string>

namespace vectors {

using Value = nlohmann::json;

inline Value load(const std::string& name) {
	std::string path = std::string(PALM_TESTDATA) + "/" + name + ".json";
	std::ifstream in(path, std::ios::binary);
	if (!in) {
		throw std::runtime_error(path + ": cannot open");
	}
	std::stringstream text;
	text << in.rdbuf();
	return Value::parse(text.str());
}

inline std::string s(const Value& v) {
	if (!v.is_string()) {
		throw std::runtime_error("not a string: " + v.dump());
	}
	return v.get<std::string>();
}

inline long long i(const Value& v) {
	if (!v.is_number_integer()) {
		throw std::runtime_error("not a number: " + v.dump());
	}
	return v.get<long long>();
}

inline bool b(const Value& v) {
	if (!v.is_boolean()) {
		throw std::runtime_error("not a boolean: " + v.dump());
	}
	return v.get<bool>();
}

inline const Value::array_t& arr(const Value& v) {
	if (!v.is_array()) {
		throw std::runtime_error("not an array: " + v.dump());
	}
	return v.get_ref<const Value::array_t&>();
}

inline Value json_of(const std::string& text) {
	return Value::parse(text);
}

} // namespace vectors
