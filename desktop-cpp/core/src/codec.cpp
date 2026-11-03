// The sync codec of codec.hpp, as desktop-rs/core/src/codec.rs.
#include "palm/codec.hpp"

#include "palm/json.hpp"

#include <algorithm>
#include <cstddef>
#include <cstdint>
#include <set>
#include <span>
#include <string>
#include <string_view>
#include <utility>
#include <vector>

namespace palm::codec {

namespace {

// Writes a text field only when it is not empty.
void put_str(json::Obj& o, std::string_view key, std::string_view value) {
	if (!value.empty()) {
		o[std::string(key)] = std::string(value);
	}
}

// Writes an int field only when it is not its default.
void put_i32(json::Obj& o, std::string_view key, std::int32_t value, std::int32_t default_value) {
	if (value != default_value) {
		o[std::string(key)] = value;
	}
}

// Writes a long field only when it is not its default.
void put_i64(json::Obj& o, std::string_view key, std::int64_t value, std::int64_t default_value) {
	if (value != default_value) {
		o[std::string(key)] = value;
	}
}

// Writes a flag only when it is true.
void put_bool(json::Obj& o, std::string_view key, bool value) {
	if (value) {
		o[std::string(key)] = true;
	}
}

// A new object with the kind in "k".
json::Obj kinded(std::string_view k) {
	json::Obj o = json::Obj::object();
	o["k"] = std::string(k);
	return o;
}

// The compact JSON text of an object.
std::string dump_text(const json::Obj& o) {
	return o.dump();
}

// The long kind name of a short kind, or "" when the kind is unknown.
std::string_view long_kind(std::string_view k) {
	if (k == "e") {
		return "event";
	}
	if (k == "t") {
		return "todo";
	}
	if (k == "m") {
		return "memo";
	}
	if (k == "a") {
		return "address";
	}
	if (k == "c") {
		return "category";
	}
	if (k == "s") {
		return "shortcuts";
	}
	return "";
}

// True when the object is format 2 (it has the short kind key "k").
bool is_v2(const json::Obj& o) {
	return o.contains("k");
}

// The format 2 key when `v2`, else the format 1 key.
std::string_view key(bool v2, std::string_view f2, std::string_view f1) {
	return v2 ? f2 : f1;
}

// The elements of an array that a key may hold; none when the key is missing or not an array.
std::span<const json::Obj> elements(const nlohmann::json::array_t* a) {
	if (a == nullptr) {
		return {};
	}
	return *a;
}

// True when one phone label differs from its default, which is its index.
bool labels_differ(const std::vector<std::int32_t>& labels) {
	for (std::size_t i = 0; i < labels.size(); ++i) {
		if (labels[i] != static_cast<std::int32_t>(i)) {
			return true;
		}
	}
	return false;
}

} // namespace

// The format 2 text of an event.
std::string event(const model::Event& e) {
	json::Obj o = kinded("e");
	put_str(o, "t", e.title);
	put_str(o, "n", e.note);
	put_i64(o, "d", e.date, 0);
	put_bool(o, "u", e.untimed);
	put_i32(o, "s", e.start, 480);
	put_i32(o, "e", e.end, 540);
	put_bool(o, "a", e.alarm);
	put_i32(o, "aa", e.alarm_advance, 5);
	put_i32(o, "au", e.alarm_unit, 0);
	put_i32(o, "r", e.repeat, 0);
	put_i32(o, "ev", e.every, 1);
	put_i64(o, "re", e.repeat_end, -1);
	put_i32(o, "w", e.week_days, 0);
	if (!e.exceptions.empty()) {
		o["x"] = std::vector<std::int64_t>(e.exceptions.begin(), e.exceptions.end());
	}
	put_bool(o, "p", e.is_private);
	return dump_text(o);
}

// The format 2 text of a To Do item; `category_uid` is the uid of its category, or "" when unfiled.
std::string todo(const model::Todo& t, std::string_view category_uid) {
	json::Obj o = kinded("t");
	put_str(o, "t", t.title);
	put_str(o, "n", t.note);
	put_i32(o, "pr", t.priority, 1);
	put_i64(o, "du", t.due, -1);
	put_bool(o, "dn", t.done);
	put_str(o, "c", category_uid);
	put_bool(o, "p", t.is_private);
	return dump_text(o);
}

// The format 2 text of a memo.
std::string memo(const model::Memo& m, std::string_view category_uid) {
	json::Obj o = kinded("m");
	put_str(o, "t", m.text);
	put_str(o, "c", category_uid);
	put_bool(o, "p", m.is_private);
	put_i32(o, "o", m.pos, 0);
	return dump_text(o);
}

// The format 2 text of an address.
std::string address(const model::Address& a, std::string_view category_uid) {
	json::Obj o = kinded("a");
	put_str(o, "ln", a.last_name);
	put_str(o, "fn", a.first_name);
	put_str(o, "ti", a.title);
	put_str(o, "co", a.company);
	// A list is written whole when one entry differs from its default.
	if (labels_differ(a.phone_labels)) {
		o["pl"] = a.phone_labels;
	}
	if (std::ranges::any_of(a.phones, [](const std::string& p) { return !p.empty(); })) {
		o["ph"] = a.phones;
	}
	put_str(o, "ad", a.address);
	put_str(o, "ci", a.city);
	put_str(o, "st", a.state);
	put_str(o, "z", a.zip);
	put_str(o, "cn", a.country);
	if (std::ranges::any_of(a.custom, [](const std::string& c) { return !c.empty(); })) {
		o["cu"] = a.custom;
	}
	put_str(o, "n", a.note);
	put_i32(o, "sp", a.show_phone, 0);
	put_str(o, "c", category_uid);
	put_bool(o, "p", a.is_private);
	return dump_text(o);
}

// The format 2 text of a category.
std::string category(const model::Category& c) {
	json::Obj o = kinded("c");
	put_str(o, "n", c.name);
	put_i32(o, "ap", c.app, 0);
	return dump_text(o);
}

// The format 2 text of a ShortCuts list.
std::string shortcuts(std::span<const ShortCuts::value_type> list) {
	json::Obj items = json::Obj::array();
	for (const auto& [name, t] : list) {
		json::Obj item = json::Obj::object();
		put_str(item, "n", name);
		put_str(item, "t", t);
		items.push_back(item);
	}
	json::Obj o = kinded("s");
	o["l"] = items;
	return dump_text(o);
}

// The event of a blob, in format 2 or format 1. The id is 0; the caller stores it.
std::expected<model::Event, std::string> to_event(std::string_view text, std::string_view uid) {
	auto parsed = json::parse_obj(text);
	if (!parsed) {
		return std::unexpected(parsed.error());
	}
	const json::Obj& o = *parsed;
	const bool v2 = is_v2(o);
	std::set<std::int64_t> exceptions;
	if (const auto* a = json::opt_array(o, key(v2, "x", "exceptions"))) {
		for (std::size_t i = 0; i < a->size(); ++i) {
			exceptions.insert(json::at_i64(*a, i));
		}
	}
	return model::Event{
	    .id = 0,
	    .title = json::opt_str(o, key(v2, "t", "title")),
	    .note = json::opt_str(o, key(v2, "n", "note")),
	    .date = json::opt_i64(o, key(v2, "d", "date"), 0),
	    .untimed = json::opt_bool(o, key(v2, "u", "untimed")),
	    .start = json::opt_i32(o, key(v2, "s", "start"), 480),
	    .end = json::opt_i32(o, key(v2, "e", "end"), 540),
	    .alarm = json::opt_bool(o, key(v2, "a", "alarm")),
	    .alarm_advance = json::opt_i32(o, key(v2, "aa", "alarmAdvance"), 5),
	    .alarm_unit = json::opt_i32(o, key(v2, "au", "alarmUnit"), 0),
	    .repeat = json::opt_i32(o, key(v2, "r", "repeat"), 0),
	    .every = json::opt_i32(o, key(v2, "ev", "every"), 1),
	    .repeat_end = json::opt_i64(o, key(v2, "re", "repeatEnd"), -1),
	    .week_days = json::opt_i32(o, key(v2, "w", "weekDays"), 0),
	    .exceptions = std::move(exceptions),
	    .is_private = json::opt_bool(o, key(v2, "p", "private")),
	    .uid = std::string(uid),
	};
}

// The To Do item of a blob. `category_id` maps the category uid to its local id.
std::expected<model::Todo, std::string> to_todo(std::string_view text, std::string_view uid,
                                                std::function<std::int32_t(std::string_view)> category_id) {
	auto parsed = json::parse_obj(text);
	if (!parsed) {
		return std::unexpected(parsed.error());
	}
	const json::Obj& o = *parsed;
	const bool v2 = is_v2(o);
	return model::Todo{
	    .id = 0,
	    .title = json::opt_str(o, key(v2, "t", "title")),
	    .note = json::opt_str(o, key(v2, "n", "note")),
	    .priority = json::opt_i32(o, key(v2, "pr", "priority"), 1),
	    .due = json::opt_i64(o, key(v2, "du", "due"), -1),
	    .done = json::opt_bool(o, key(v2, "dn", "done")),
	    .category = category_id(json::opt_str(o, key(v2, "c", "category"))),
	    .is_private = json::opt_bool(o, key(v2, "p", "private")),
	    .uid = std::string(uid),
	};
}

// The memo of a blob. `category_id` maps the category uid to its local id.
std::expected<model::Memo, std::string> to_memo(std::string_view text, std::string_view uid,
                                                std::function<std::int32_t(std::string_view)> category_id) {
	auto parsed = json::parse_obj(text);
	if (!parsed) {
		return std::unexpected(parsed.error());
	}
	const json::Obj& o = *parsed;
	const bool v2 = is_v2(o);
	return model::Memo{
	    .id = 0,
	    .text = json::opt_str(o, key(v2, "t", "text")),
	    .category = category_id(json::opt_str(o, key(v2, "c", "category"))),
	    .is_private = json::opt_bool(o, key(v2, "p", "private")),
	    .pos = json::opt_i32(o, key(v2, "o", "pos"), 0),
	    .uid = std::string(uid),
	};
}

// The address of a blob. `category_id` maps the category uid to its local id.
std::expected<model::Address, std::string> to_address(std::string_view text, std::string_view uid,
                                                      std::function<std::int32_t(std::string_view)> category_id) {
	auto parsed = json::parse_obj(text);
	if (!parsed) {
		return std::unexpected(parsed.error());
	}
	const json::Obj& o = *parsed;
	const bool v2 = is_v2(o);
	const auto labels = elements(json::opt_array(o, key(v2, "pl", "phoneLabels")));
	const auto phones = elements(json::opt_array(o, key(v2, "ph", "phones")));
	const auto custom = elements(json::opt_array(o, key(v2, "cu", "custom")));
	std::vector<std::int32_t> phone_labels;
	for (std::size_t i = 0; i < 5; ++i) {
		const auto index = static_cast<std::int32_t>(i);
		phone_labels.push_back(std::clamp(json::at_i32(labels, i, index), 0, 7));
	}
	std::vector<std::string> phone_numbers;
	for (std::size_t i = 0; i < 5; ++i) {
		phone_numbers.push_back(json::at_str(phones, i, ""));
	}
	std::vector<std::string> custom_fields;
	for (std::size_t i = 0; i < 4; ++i) {
		custom_fields.push_back(json::at_str(custom, i, ""));
	}
	return model::Address{
	    .id = 0,
	    .last_name = json::opt_str(o, key(v2, "ln", "lastName")),
	    .first_name = json::opt_str(o, key(v2, "fn", "firstName")),
	    .title = json::opt_str(o, key(v2, "ti", "title")),
	    .company = json::opt_str(o, key(v2, "co", "company")),
	    .phone_labels = std::move(phone_labels),
	    .phones = std::move(phone_numbers),
	    .address = json::opt_str(o, key(v2, "ad", "address")),
	    .city = json::opt_str(o, key(v2, "ci", "city")),
	    .state = json::opt_str(o, key(v2, "st", "state")),
	    .zip = json::opt_str(o, key(v2, "z", "zip")),
	    .country = json::opt_str(o, key(v2, "cn", "country")),
	    .custom = std::move(custom_fields),
	    .note = json::opt_str(o, key(v2, "n", "note")),
	    .show_phone = std::clamp(json::opt_i32(o, key(v2, "sp", "showPhone"), 0), 0, 4),
	    .category = category_id(json::opt_str(o, key(v2, "c", "category"))),
	    .is_private = json::opt_bool(o, key(v2, "p", "private")),
	    .uid = std::string(uid),
	};
}

// The category of a blob, with the local id `id`.
std::expected<model::Category, std::string> to_category(std::string_view text, std::string_view uid, std::int32_t id) {
	auto parsed = json::parse_obj(text);
	if (!parsed) {
		return std::unexpected(parsed.error());
	}
	const json::Obj& o = *parsed;
	const bool v2 = is_v2(o);
	return model::Category{
	    .id = id,
	    .name = json::opt_str(o, key(v2, "n", "name")),
	    .app = json::opt_i32(o, key(v2, "ap", "app"), 0),
	    .uid = std::string(uid),
	};
}

// The ShortCuts list of a blob. A blob with no list gives an empty list.
std::expected<ShortCuts, std::string> to_shortcuts(std::string_view text) {
	auto parsed = json::parse_obj(text);
	if (!parsed) {
		return std::unexpected(parsed.error());
	}
	const json::Obj& o = *parsed;
	const bool v2 = is_v2(o);
	const auto* arr = json::opt_array(o, key(v2, "l", "list"));
	if (arr == nullptr) {
		return ShortCuts{};
	}
	ShortCuts out;
	for (const json::Obj& s : *arr) {
		if (!s.is_object()) {
			return std::unexpected(std::string("JSONArray item is not a JSONObject."));
		}
		out.emplace_back(json::opt_str(s, key(v2, "n", "name")), json::opt_str(s, key(v2, "t", "text")));
	}
	return out;
}

// The "kind" of a blob, or "" when the text is not a JSON object or has no kind.
std::string kind(std::string_view text) {
	auto parsed = json::parse_obj(text);
	if (!parsed) {
		return {};
	}
	const json::Obj& o = *parsed;
	if (is_v2(o)) {
		return std::string(long_kind(json::opt_str(o, "k")));
	}
	return json::opt_str(o, "kind");
}

} // namespace palm::codec
