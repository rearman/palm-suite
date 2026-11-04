// The lenient reads of org.json (optString, optInt, optLong, optBoolean, optJSONArray) over JSON values, so a record
// that the Kotlin apps read the same way reads the same here.
#pragma once

#include <cstddef>
#include <cstdint>
#include <expected>
#include <nlohmann/json.hpp>
#include <optional>
#include <span>
#include <string>
#include <string_view>

namespace palm::json {

// The JSON object of a record (serde_json Map): nlohmann::json holding an object.
using Obj = nlohmann::json;

// optString(key): "" when the key is missing or null.
std::string opt_str(const Obj& o, std::string_view key);

// optString(key, default): the default when the key is missing or null.
std::string opt_str_or(const Obj& o, std::string_view key, std::string_view default_value);

// optInt(key, default). A long value keeps its low 32 bits, as Java's intValue.
std::int32_t opt_i32(const Obj& o, std::string_view key, std::int32_t default_value);

// optLong(key, default).
std::int64_t opt_i64(const Obj& o, std::string_view key, std::int64_t default_value);

// optBoolean(key): false when the key is missing or not a boolean.
bool opt_bool(const Obj& o, std::string_view key);

// optJSONArray(key): the array under the key, or nullptr when it is missing or not an array.
const nlohmann::json::array_t* opt_array(const Obj& o, std::string_view key);

// JSONArray.optInt(i, default).
std::int32_t at_i32(std::span<const nlohmann::json> a, std::size_t i, std::int32_t default_value);

// JSONArray.optLong(i): 0 when missing.
std::int64_t at_i64(std::span<const nlohmann::json> a, std::size_t i);

// JSONArray.optString(i, default).
std::string at_str(std::span<const nlohmann::json> a, std::size_t i, std::string_view default_value);

// getLong(key): a number or a numeric string, else an error.
std::expected<std::int64_t, std::string> get_i64(const Obj& o, std::string_view key);

// getString(key): a string, else an error.
std::expected<std::string, std::string> get_str(const Obj& o, std::string_view key);

// The JSON value of a text, as serde_json::from_str::<Value> reads it (the same values, floats included), or
// serde_json's error text ("expected value at line 1 column 1"), the 128-level recursion limit included.
std::expected<nlohmann::json, std::string> parse_value(std::string_view text);

// The JSON text of a value, as serde_json writes it: raw UTF-8, `indent` spaces per level (-1 for one line), and
// floats as format_double gives them. A string that holds invalid UTF-8 (which a Rust String cannot) gets U+FFFD
// in place of each bad byte, so a save never throws.
std::string dump(const nlohmann::json& v, int indent = -1);

// The text of a double as serde_json 1.0.154 writes it ("1.0", "1e+16", "0.00001", "1e-6"); null when not finite.
std::string format_double(double f);

// The JSON object of a text, or an error when the text is not one.
std::expected<Obj, std::string> parse_obj(std::string_view text);

} // namespace palm::json
