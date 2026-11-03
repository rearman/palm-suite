// The model form of the vector tests: the converters between palm::model records and the JSON of
// sync-core/testdata, as the model-form helpers of desktop-rs/core/tests/vectors.rs. Later tests include it.
#pragma once

#include "palm/model.hpp"
#include "vectors.hpp"

#include <cstdint>
#include <span>
#include <string>
#include <unordered_map>
#include <utility>
#include <vector>

namespace vectors {

// The 32-bit field of a JSON integer.
inline std::int32_t i32(const Value& v) {
	return static_cast<std::int32_t>(i(v));
}

inline palm::model::Event ev_from(const Value& v) {
	palm::model::Event e;
	e.id = i(v.at("id"));
	e.title = s(v.at("title"));
	e.note = s(v.at("note"));
	e.date = i(v.at("date"));
	e.untimed = b(v.at("untimed"));
	e.start = i32(v.at("start"));
	e.end = i32(v.at("end"));
	e.alarm = b(v.at("alarm"));
	e.alarm_advance = i32(v.at("alarmAdvance"));
	e.alarm_unit = i32(v.at("alarmUnit"));
	e.repeat = i32(v.at("repeat"));
	e.every = i32(v.at("every"));
	e.repeat_end = i(v.at("repeatEnd"));
	e.week_days = i32(v.at("weekDays"));
	for (const Value& x : arr(v.at("exceptions"))) {
		e.exceptions.insert(i(x));
	}
	e.is_private = b(v.at("private"));
	e.uid = s(v.at("uid"));
	return e;
}

inline Value ev_to(const palm::model::Event& e) {
	return Value{
	    {"id", e.id},
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
	    {"exceptions", Value(e.exceptions)},
	    {"private", e.is_private},
	    {"uid", e.uid},
	};
}

inline Value events_to(std::span<const palm::model::Event> list) {
	Value out = Value::array();
	for (const palm::model::Event& e : list) {
		out.push_back(ev_to(e));
	}
	return out;
}

inline palm::model::Todo todo_from(const Value& v) {
	palm::model::Todo t;
	t.id = i(v.at("id"));
	t.title = s(v.at("title"));
	t.note = s(v.at("note"));
	t.priority = i32(v.at("priority"));
	t.due = i(v.at("due"));
	t.done = b(v.at("done"));
	t.category = i32(v.at("category"));
	t.is_private = b(v.at("private"));
	t.uid = s(v.at("uid"));
	return t;
}

inline Value todo_to(const palm::model::Todo& t) {
	return Value{
	    {"id", t.id},     {"title", t.title},       {"note", t.note},          {"priority", t.priority}, {"due", t.due},
	    {"done", t.done}, {"category", t.category}, {"private", t.is_private}, {"uid", t.uid},
	};
}

inline palm::model::Memo memo_from(const Value& v) {
	palm::model::Memo m;
	m.id = i(v.at("id"));
	m.text = s(v.at("text"));
	m.category = i32(v.at("category"));
	m.is_private = b(v.at("private"));
	m.pos = i32(v.at("pos"));
	m.uid = s(v.at("uid"));
	return m;
}

inline Value memo_to(const palm::model::Memo& m) {
	return Value{
	    {"id", m.id},   {"text", m.text}, {"category", m.category}, {"private", m.is_private},
	    {"pos", m.pos}, {"uid", m.uid},
	};
}

// The strings of a JSON array, in order.
inline std::vector<std::string> strs_of(const Value& v) {
	std::vector<std::string> out;
	for (const Value& x : arr(v)) {
		out.push_back(s(x));
	}
	return out;
}

inline palm::model::Address addr_from(const Value& v) {
	palm::model::Address a;
	a.id = i(v.at("id"));
	a.last_name = s(v.at("lastName"));
	a.first_name = s(v.at("firstName"));
	a.title = s(v.at("title"));
	a.company = s(v.at("company"));
	a.phone_labels.clear();
	for (const Value& x : arr(v.at("phoneLabels"))) {
		a.phone_labels.push_back(i32(x));
	}
	a.phones = strs_of(v.at("phones"));
	a.address = s(v.at("address"));
	a.city = s(v.at("city"));
	a.state = s(v.at("state"));
	a.zip = s(v.at("zip"));
	a.country = s(v.at("country"));
	a.custom = strs_of(v.at("custom"));
	a.note = s(v.at("note"));
	a.show_phone = i32(v.at("showPhone"));
	a.category = i32(v.at("category"));
	a.is_private = b(v.at("private"));
	a.uid = s(v.at("uid"));
	return a;
}

inline Value addr_to(const palm::model::Address& a) {
	return Value{
	    {"id", a.id},
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
	    {"category", a.category},
	    {"private", a.is_private},
	    {"uid", a.uid},
	};
}

inline palm::model::Category cat_from(const Value& v) {
	palm::model::Category c;
	c.id = i32(v.at("id"));
	c.name = s(v.at("name"));
	c.app = i32(v.at("app"));
	c.uid = s(v.at("uid"));
	return c;
}

inline Value cat_to(const palm::model::Category& c) {
	return Value{
	    {"id", c.id},
	    {"name", c.name},
	    {"app", c.app},
	    {"uid", c.uid},
	};
}

// The [a, b] pairs of a JSON array of two-string arrays.
inline std::vector<std::pair<std::string, std::string>> pairs(const Value& v) {
	std::vector<std::pair<std::string, std::string>> out;
	for (const Value& p : arr(v)) {
		out.emplace_back(s(p.at(0)), s(p.at(1)));
	}
	return out;
}

// The names of a {"3": "Business"} object, by category id.
inline std::unordered_map<std::int32_t, std::string> names_by_id(const Value& v) {
	std::unordered_map<std::int32_t, std::string> out;
	for (const auto& [key, name] : v.items()) {
		out.emplace(std::stoi(key), s(name));
	}
	return out;
}

} // namespace vectors
