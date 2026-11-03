#include "palm/backup.hpp"

#include "palm/json.hpp"
#include "palm/model.hpp"
#include "palm/text.hpp"

#include <algorithm>
#include <array>
#include <compare>
#include <cstddef>
#include <cstdint>
#include <expected>
#include <functional>
#include <limits>
#include <optional>
#include <set>
#include <span>
#include <string>
#include <string_view>
#include <utility>
#include <vector>

namespace palm::backup {

namespace {

// The category name of category 0. It is never stored as a category of its own.
constexpr std::string_view UNFILED = "Unfiled";

// The elements of the array under `key`, or none when the key is missing or not an array.
std::span<const nlohmann::json> elements(const json::Obj& o, std::string_view key) {
	const nlohmann::json::array_t* a = json::opt_array(o, key);
	return a ? std::span<const nlohmann::json>(*a) : std::span<const nlohmann::json>();
}

// The category id of each record of `list`.
template <class T> std::vector<std::int32_t> category_ids(const std::vector<T>& list) {
	std::vector<std::int32_t> ids;
	ids.reserve(list.size());
	for (const T& x : list) {
		ids.push_back(x.category);
	}
	return ids;
}

// The JSON array of `list`, one `to_json` value per record.
template <class T, class F> nlohmann::json array_of(const std::vector<T>& list, F to_json) {
	nlohmann::json out = nlohmann::json::array();
	for (const T& x : list) {
		out.push_back(to_json(x));
	}
	return out;
}

// `given`, then the names of the categories `ids` use that `given` lacks. Unfiled and blank names are left out.
std::vector<std::string> with_names(const std::vector<std::string>& given, const std::vector<std::int32_t>& ids,
                                    const std::function<std::string(std::int32_t)>& category_name) {
	std::vector<std::string> out = given;
	for (std::int32_t id : ids) {
		if (id == 0) {
			continue;
		}
		std::string u = category_name(id);
		if (model::is_blank(u) || text::eq_ignore_ascii_case(u, UNFILED)) {
			continue;
		}
		const bool in_given = std::ranges::any_of(
		    given, [&](const std::string& g) { return text::to_lowercase(g) == text::to_lowercase(u); });
		const auto added_from = out.begin() + static_cast<std::ptrdiff_t>(given.size());
		const bool added = std::find(added_from, out.end(), u) != out.end();
		if (!in_given && !added) {
			out.push_back(u);
		}
	}
	return out;
}

// The JSON of one event. Its id and uid are not written.
nlohmann::json event_json(const model::Event& e) {
	return nlohmann::json{
	    {"title", e.title},
	    {"note", e.note},
	    {"date", e.date},
	    {"untimed", e.untimed},
	    {"start", e.start},
	    {"end", e.end},
	    {"alarm", e.alarm},
	    {"alarmAdvance", e.alarm_advance},
	    {"alarmUnit", e.alarm_unit},
	    {"repeat", e.repeat},
	    {"every", e.every},
	    {"repeatEnd", e.repeat_end},
	    {"weekDays", e.week_days},
	    {"exceptions", nlohmann::json(e.exceptions)},
	    {"private", e.is_private},
	};
}

// The 64-bit integer of a JSON number, as serde_json's as_i64: a float, or an unsigned value above INT64_MAX,
// has none.
std::optional<std::int64_t> as_i64(const json::Obj& v) {
	if (v.is_number_unsigned()) {
		const auto u = v.get<std::uint64_t>();
		if (u > static_cast<std::uint64_t>(std::numeric_limits<std::int64_t>::max())) {
			return std::nullopt;
		}
		return static_cast<std::int64_t>(u);
	}
	if (v.is_number_integer()) {
		return v.get<std::int64_t>();
	}
	return std::nullopt;
}

// One event of the file. Fails when a value of "exceptions" is not a number, or when "date" is not one.
std::expected<model::Event, std::string> event_from(const json::Obj& o) {
	std::set<std::int64_t> exceptions;
	const auto it = o.find("exceptions");
	if (it != o.end() && it->is_array()) {
		for (const json::Obj& v : it->get_ref<const nlohmann::json::array_t&>()) {
			const std::optional<std::int64_t> n = as_i64(v);
			if (!n) {
				return std::unexpected(std::string("exceptions holds a value that is not a number"));
			}
			exceptions.insert(*n);
		}
	}
	const auto date = json::get_i64(o, "date");
	if (!date) {
		return std::unexpected(date.error());
	}
	model::Event e;
	e.title = json::opt_str(o, "title");
	e.note = json::opt_str(o, "note");
	e.date = *date;
	e.untimed = json::opt_bool(o, "untimed");
	e.start = json::opt_i32(o, "start", 480);
	e.end = json::opt_i32(o, "end", 540);
	e.alarm = json::opt_bool(o, "alarm");
	e.alarm_advance = json::opt_i32(o, "alarmAdvance", 5);
	e.alarm_unit = json::opt_i32(o, "alarmUnit", 0);
	e.repeat = json::opt_i32(o, "repeat", 0);
	e.every = json::opt_i32(o, "every", 1);
	e.repeat_end = json::opt_i64(o, "repeatEnd", -1);
	e.week_days = json::opt_i32(o, "weekDays", 0);
	e.exceptions = std::move(exceptions);
	e.is_private = json::opt_bool(o, "private");
	return e;
}

} // namespace

std::string write(const Content& c, const std::function<std::string(std::int32_t)>& category_name) {
	auto names = [&](const std::vector<std::string>& given, const std::vector<std::int32_t>& ids) {
		return with_names(given, ids, category_name);
	};
	nlohmann::json root = {
	    {"format", std::string(FORMAT)},
	    {"categories", names(c.todo_categories, category_ids(c.todos))},
	    {"memoCategories", names(c.memo_categories, category_ids(c.memos))},
	    {"addressCategories", names(c.address_categories, category_ids(c.addresses))},
	    {"events", array_of(c.events, event_json)},
	    {"todos", array_of(c.todos,
	                       [&](const model::Todo& t) {
		                       return nlohmann::json{
		                           {"title", t.title},        {"note", t.note},
		                           {"priority", t.priority},  {"due", t.due},
		                           {"done", t.done},          {"category", category_name(t.category)},
		                           {"private", t.is_private},
		                       };
	                       })},
	    {"memos", array_of(c.memos,
	                       [&](const model::Memo& m) {
		                       return nlohmann::json{
		                           {"text", m.text},
		                           {"category", category_name(m.category)},
		                           {"private", m.is_private},
		                       };
	                       })},
	    {"addresses", array_of(c.addresses,
	                           [&](const model::Address& a) {
		                           return nlohmann::json{
		                               {"lastName", a.last_name},
		                               {"firstName", a.first_name},
		                               {"title", a.title},
		                               {"company", a.company},
		                               {"phoneLabels", a.phone_labels},
		                               {"phones", a.phones},
		                               {"address", a.address},
		                               {"city", a.city},
		                               {"state", a.state},
		                               {"zip", a.zip},
		                               {"country", a.country},
		                               {"custom", a.custom},
		                               {"note", a.note},
		                               {"showPhone", a.show_phone},
		                               {"category", category_name(a.category)},
		                               {"private", a.is_private},
		                           };
	                           })},
	};
	// org.json's toString(1): one space for each level.
	return root.dump(1);
}

std::expected<Content, std::string>
read(std::string_view text, const std::function<std::int32_t(std::string_view, std::int32_t)>& category_id) {
	const auto parsed = json::parse_obj(text);
	if (!parsed) {
		return std::unexpected(parsed.error());
	}
	const json::Obj& root = *parsed;
	if (json::opt_str(root, "format") != FORMAT) {
		return std::unexpected(std::string("Not a Date Book backup."));
	}
	// Parse every record first, so that a bad record creates no category.
	std::vector<std::pair<std::string, std::int32_t>> top_names;
	constexpr std::array<std::pair<std::string_view, std::int32_t>, 3> keys = {
	    {{"categories", 0}, {"memoCategories", 1}, {"addressCategories", 2}}};
	for (const auto& [key, app] : keys) {
		for (const json::Obj& v : elements(root, key)) {
			if (!v.is_string()) {
				return std::unexpected(std::string(key) + " holds a value that is not a string");
			}
			top_names.emplace_back(v.get<std::string>(), app);
		}
	}
	auto objects = [&](std::string_view key) -> std::expected<std::vector<const json::Obj*>, std::string> {
		std::vector<const json::Obj*> out;
		for (const json::Obj& v : elements(root, key)) {
			if (!v.is_object()) {
				return std::unexpected(std::string(key) + " holds a value that is not an object");
			}
			out.push_back(&v);
		}
		return out;
	};
	const auto event_objects = objects("events");
	if (!event_objects) {
		return std::unexpected(event_objects.error());
	}
	std::vector<model::Event> events;
	for (const json::Obj* o : *event_objects) {
		auto e = event_from(*o);
		if (!e) {
			return std::unexpected(e.error());
		}
		events.push_back(std::move(*e));
	}
	const auto todo_objects = objects("todos");
	if (!todo_objects) {
		return std::unexpected(todo_objects.error());
	}
	std::vector<std::pair<model::Todo, std::string>> todos;
	for (const json::Obj* o : *todo_objects) {
		model::Todo t;
		t.title = json::opt_str(*o, "title");
		t.note = json::opt_str(*o, "note");
		t.priority = json::opt_i32(*o, "priority", 1);
		t.due = json::opt_i64(*o, "due", -1);
		t.done = json::opt_bool(*o, "done");
		t.is_private = json::opt_bool(*o, "private");
		todos.emplace_back(std::move(t), json::opt_str_or(*o, "category", UNFILED));
	}
	const auto memo_objects = objects("memos");
	if (!memo_objects) {
		return std::unexpected(memo_objects.error());
	}
	std::vector<std::pair<model::Memo, std::string>> memos;
	for (const json::Obj* o : *memo_objects) {
		model::Memo m;
		m.text = json::opt_str(*o, "text");
		m.is_private = json::opt_bool(*o, "private");
		memos.emplace_back(std::move(m), json::opt_str_or(*o, "category", UNFILED));
	}
	const auto address_objects = objects("addresses");
	if (!address_objects) {
		return std::unexpected(address_objects.error());
	}
	std::vector<std::pair<model::Address, std::string>> addresses;
	for (const json::Obj* o : *address_objects) {
		const std::span<const nlohmann::json> labels = elements(*o, "phoneLabels");
		model::Address a;
		a.last_name = json::opt_str(*o, "lastName");
		a.first_name = json::opt_str(*o, "firstName");
		a.title = json::opt_str(*o, "title");
		a.company = json::opt_str(*o, "company");
		a.phone_labels.clear();
		for (std::size_t i = 0; i < 5; ++i) {
			const auto label = json::at_i32(labels, i, static_cast<std::int32_t>(i));
			a.phone_labels.push_back(std::clamp(label, 0, static_cast<std::int32_t>(model::LABELS.size()) - 1));
		}
		a.phones.clear();
		for (std::size_t i = 0; i < 5; ++i) {
			a.phones.push_back(json::at_str(elements(*o, "phones"), i, ""));
		}
		a.address = json::opt_str(*o, "address");
		a.city = json::opt_str(*o, "city");
		a.state = json::opt_str(*o, "state");
		a.zip = json::opt_str(*o, "zip");
		a.country = json::opt_str(*o, "country");
		a.custom.clear();
		for (std::size_t i = 0; i < 4; ++i) {
			a.custom.push_back(json::at_str(elements(*o, "custom"), i, ""));
		}
		a.note = json::opt_str(*o, "note");
		a.show_phone = std::clamp(json::opt_i32(*o, "showPhone", 0), 0, 4);
		a.is_private = json::opt_bool(*o, "private");
		addresses.emplace_back(std::move(a), json::opt_str_or(*o, "category", UNFILED));
	}

	auto id_of = [&](std::string_view name, std::int32_t app) -> std::int32_t {
		if (model::is_blank(name) || text::eq_ignore_ascii_case(name, UNFILED)) {
			return 0;
		}
		return category_id(name, app);
	};
	for (const auto& [name, app] : top_names) {
		id_of(name, app);
	}
	auto list = [&](std::int32_t app) {
		std::vector<std::string> names;
		for (const auto& [name, a] : top_names) {
			if (a == app) {
				names.push_back(name);
			}
		}
		return names;
	};
	Content out;
	out.events = std::move(events);
	for (auto& [t, name] : todos) {
		t.category = id_of(name, 0);
		out.todos.push_back(std::move(t));
	}
	for (auto& [m, name] : memos) {
		m.category = id_of(name, 1);
		out.memos.push_back(std::move(m));
	}
	for (auto& [a, name] : addresses) {
		a.category = id_of(name, 2);
		out.addresses.push_back(std::move(a));
	}
	out.todo_categories = list(0);
	out.memo_categories = list(1);
	out.address_categories = list(2);
	return out;
}

} // namespace palm::backup
