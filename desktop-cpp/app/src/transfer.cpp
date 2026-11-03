#include "palm/transfer.hpp"

#include "palm/address.hpp"
#include "palm/backup.hpp"
#include "palm/dialogs.hpp"
#include "palm/ical.hpp"
#include "palm/json.hpp"
#include "palm/model.hpp"
#include "palm/platform.hpp"
#include "palm/store.hpp"
#include "palm/text.hpp"
#include "palm/vcard.hpp"

#include <FL/Fl_Native_File_Chooser.H>

#include <algorithm>
#include <chrono>
#include <cstddef>
#include <cstdint>
#include <expected>
#include <format>
#include <fstream>
#include <iterator>
#include <map>
#include <optional>
#include <string>
#include <string_view>
#include <unordered_set>
#include <vector>

namespace palm::transfer {

namespace fs = std::filesystem;

namespace {

// The path of a UTF-8 text, as the file chooser gives its paths.
fs::path from_utf8(std::string_view s) {
	return fs::path(std::u8string(reinterpret_cast<const char8_t*>(s.data()), s.size()));
}

// The UTF-8 text of a path.
std::string to_utf8(const fs::path& p) {
	const std::u8string u = p.u8string();
	return std::string(reinterpret_cast<const char*>(u.data()), u.size());
}

// The lowercase extension of `path` as Rust's Path::extension gives it, or "" when it has none: the text after the
// last dot of the file name, unless that dot is the first character of the name.
std::string extension_of(const fs::path& path) {
	const std::string name = to_utf8(path.filename());
	const std::size_t dot = name.rfind('.');
	if (dot == std::string::npos || dot == 0) {
		return "";
	}
	return text::to_lowercase(std::string_view(name).substr(dot + 1));
}

// Writes `text` to `path`, replacing the file as std::fs::write does. Returns the OS error text on a failure.
std::optional<std::string> write_file(const fs::path& path, const std::string& text) {
	std::ofstream out(path, std::ios::binary | std::ios::trunc);
	if (!out) {
		return platform::last_os_error_text();
	}
	out.write(text.data(), static_cast<std::streamsize>(text.size()));
	out.close();
	if (!out) {
		return platform::last_os_error_text();
	}
	return std::nullopt;
}

// The whole file at `path` as text, as std::fs::read_to_string reads it. A file that is not valid UTF-8 fails.
std::expected<std::string, std::string> read_text(const fs::path& path) {
	std::ifstream in(path, std::ios::binary);
	if (!in) {
		return std::unexpected(platform::last_os_error_text());
	}
	std::string bytes((std::istreambuf_iterator<char>(in)), std::istreambuf_iterator<char>());
	// from_utf8_lossy replaces every invalid byte, so its result equals the input only when the input is valid UTF-8.
	if (text::from_utf8_lossy(bytes) != bytes) {
		return std::unexpected(std::string("stream did not contain valid UTF-8"));
	}
	return bytes;
}

// Whether `s` starts with `prefix`, ignoring ASCII case.
bool starts_with_ignore_case(std::string_view s, std::string_view prefix) {
	return s.size() >= prefix.size() && text::eq_ignore_ascii_case(s.substr(0, prefix.size()), prefix);
}

// `s` without its leading byte-order marks, then without its leading White_Space: Rust's trim_start_matches of U+FEFF
// and trim_start.
std::string_view trim_start_body(std::string_view s) {
	constexpr std::string_view bom = "\xEF\xBB\xBF";
	while (s.starts_with(bom)) {
		s.remove_prefix(bom.size());
	}
	while (!s.empty()) {
		const text::Decoded d = text::decode_at(s, 0);
		if (!text::is_white_space(d.cp)) {
			break;
		}
		s.remove_prefix(d.len);
	}
	return s;
}

// The key text of a dup key: every field as JSON, so two keys have one text when their records are equal, as the ==
// of the record compares them. The key of Rust's HashSet is the Debug text of the same fields.
std::string key_of(const model::Event& k) {
	return json::dump(nlohmann::json{
	    {"id", k.id},
	    {"title", k.title},
	    {"note", k.note},
	    {"date", k.date},
	    {"untimed", k.untimed},
	    {"start", k.start},
	    {"end", k.end},
	    {"alarm", k.alarm},
	    {"alarmAdvance", k.alarm_advance},
	    {"alarmUnit", k.alarm_unit},
	    {"repeat", k.repeat},
	    {"every", k.every},
	    {"repeatEnd", k.repeat_end},
	    {"weekDays", k.week_days},
	    {"exceptions", nlohmann::json(k.exceptions)},
	    {"private", k.is_private},
	    {"uid", k.uid},
	});
}

std::string key_of(const model::Todo& k) {
	return json::dump(nlohmann::json{
	    {"id", k.id},
	    {"title", k.title},
	    {"note", k.note},
	    {"priority", k.priority},
	    {"due", k.due},
	    {"done", k.done},
	    {"category", k.category},
	    {"private", k.is_private},
	    {"uid", k.uid},
	});
}

std::string key_of(const model::Memo& k) {
	return json::dump(nlohmann::json{
	    {"id", k.id},
	    {"text", k.text},
	    {"category", k.category},
	    {"private", k.is_private},
	    {"pos", k.pos},
	    {"uid", k.uid},
	});
}

std::string key_of(const model::Address& k) {
	return json::dump(nlohmann::json{
	    {"id", k.id},
	    {"lastName", k.last_name},
	    {"firstName", k.first_name},
	    {"title", k.title},
	    {"company", k.company},
	    {"phoneLabels", k.phone_labels},
	    {"phones", k.phones},
	    {"address", k.address},
	    {"city", k.city},
	    {"state", k.state},
	    {"zip", k.zip},
	    {"country", k.country},
	    {"custom", k.custom},
	    {"note", k.note},
	    {"showPhone", k.show_phone},
	    {"category", k.category},
	    {"private", k.is_private},
	    {"uid", k.uid},
	});
}

// The key texts of the dup keys of `records`, the set that a new record is looked up in.
template <typename T> std::unordered_set<std::string> dup_keys(const std::vector<T>& records) {
	std::unordered_set<std::string> keys;
	keys.reserve(records.size());
	for (const T& r : records) {
		keys.insert(key_of(r.dup_key()));
	}
	return keys;
}

// Saves each of `records` whose dup key is not among `have`, the keys of the store before the import, and counts the
// others in `skipped`. Returns the number saved.
template <typename T, typename Save>
std::size_t save_new(const std::vector<T>& records, const std::unordered_set<std::string>& have, std::size_t& skipped,
                     const Save& save) {
	std::size_t saved = 0;
	for (T r : records) {
		if (have.contains(key_of(r.dup_key()))) {
			++skipped;
			continue;
		}
		save(r);
		++saved;
	}
	return saved;
}

// The backup file of the store, with the same keys and values as the Android app.
std::string backup_json(const store::DesktopStore& s) {
	const auto names = [](const std::vector<model::Category>& list) {
		std::vector<std::string> out;
		for (const model::Category& c : list) {
			out.push_back(c.name);
		}
		return out;
	};
	backup::Content content;
	content.events = s.events;
	content.todos = s.todos;
	content.memos = s.memos;
	content.addresses = s.addresses;
	content.todo_categories = names(s.categories);
	content.memo_categories = names(s.memo_categories);
	content.address_categories = names(s.address_categories);
	return backup::write(content, [&s](std::int32_t id) { return s.category_name(id); });
}

std::string import_ics(view::Ctx& ctx, std::string_view text) {
	const ical::Parsed parsed = ical::parse(text, platform::local_zone());
	const std::unordered_set<std::string> have = dup_keys(ctx.store.events);
	std::size_t added = 0;
	for (model::Event e : parsed.events) {
		if (have.contains(key_of(e.dup_key()))) {
			continue;
		}
		ctx.store.save_event(e);
		++added;
	}
	std::string m = std::format("Added {} events. Skipped {} duplicate events.", added, parsed.events.size() - added);
	if (parsed.simplified > 0) {
		m += std::format(" {} events were simplified to fit Date Book.", parsed.simplified);
	}
	return m;
}

// Adds the cards of `text` as addresses. A category name is matched ignoring case, else Unfiled. A card equal to one
// already stored, or to one earlier in the file, is skipped.
std::string import_vcard(view::Ctx& ctx, std::string_view text) {
	const std::vector<vcard::Parsed> parsed = vcard::parse(text);
	std::unordered_set<std::string> have = dup_keys(ctx.store.addresses);
	std::size_t added = 0;
	for (const vcard::Parsed& p : parsed) {
		std::int32_t cat = 0;
		if (p.category_name) {
			const std::string want = text::to_lowercase(*p.category_name);
			for (const model::Category& c : ctx.store.address_categories) {
				if (text::to_lowercase(c.name) == want) {
					cat = c.id;
					break;
				}
			}
		}
		model::Address a = p.address;
		a.id = 0;
		a.category = cat;
		a.uid.clear();
		if (!have.insert(key_of(a.dup_key())).second) {
			continue;
		}
		ctx.store.save_address(a);
		++added;
	}
	return std::format("Added {} addresses. Skipped {} duplicate addresses.", added, parsed.size() - added);
}

// Adds the records of a backup file. Every record is read before the first write, so a bad record adds nothing; a
// category that does not exist yet gets a provisional id (-1, -2, ...) and is made after the read.
std::expected<std::string, std::string> import_json(view::Ctx& ctx, std::string_view text) {
	struct Made {
		std::int32_t app;
		std::string key;
		std::string name;
	};
	std::vector<Made> made;
	const store::DesktopStore& s = ctx.store;
	std::expected<backup::Content, std::string> read =
	    backup::read(text, [&s, &made](std::string_view name, std::int32_t app) -> std::int32_t {
		    const std::string lower = text::to_lowercase(name);
		    for (const model::Category& c : s.categories_for(app)) {
			    if (text::to_lowercase(c.name) == lower) {
				    return c.id;
			    }
		    }
		    const auto it = std::ranges::find_if(made, [&](const Made& m) { return m.app == app && m.key == lower; });
		    if (it != made.end()) {
			    return -static_cast<std::int32_t>(std::distance(made.begin(), it) + 1);
		    }
		    made.push_back({app, lower, std::string(name)});
		    return -static_cast<std::int32_t>(made.size());
	    });
	if (!read) {
		return std::unexpected(read.error());
	}
	backup::Content& content = *read;
	std::map<std::int32_t, std::int32_t> remap;
	for (std::size_t i = 0; i < made.size(); ++i) {
		remap[-static_cast<std::int32_t>(i + 1)] = ctx.store.add_category(made[i].name, made[i].app).id;
	}
	const auto real = [&remap](std::int32_t id) -> std::int32_t {
		if (id >= 0) {
			return id;
		}
		const auto it = remap.find(id);
		return it == remap.end() ? 0 : it->second;
	};
	std::size_t skipped = 0;
	const std::size_t ne = save_new(content.events, dup_keys(ctx.store.events), skipped,
	                                [&ctx](model::Event& e) { ctx.store.save_event(e); });
	for (model::Todo& t : content.todos) {
		t.category = real(t.category);
	}
	const std::size_t nt =
	    save_new(content.todos, dup_keys(ctx.store.todos), skipped, [&ctx](model::Todo& t) { ctx.store.save_todo(t); });
	for (model::Memo& m : content.memos) {
		m.category = real(m.category);
	}
	const std::size_t nm =
	    save_new(content.memos, dup_keys(ctx.store.memos), skipped, [&ctx](model::Memo& m) { ctx.store.save_memo(m); });
	for (model::Address& a : content.addresses) {
		a.category = real(a.category);
	}
	const std::size_t na = save_new(content.addresses, dup_keys(ctx.store.addresses), skipped,
	                                [&ctx](model::Address& a) { ctx.store.save_address(a); });
	return std::format("Added {} events, {} To Do items, {} memos and {} addresses. Skipped {} duplicate records.", ne,
	                   nt, nm, na, skipped);
}

} // namespace

std::optional<std::string> export_transfer(view::Ctx& ctx, bool addresses) {
	Fl_Native_File_Chooser c(Fl_Native_File_Chooser::BROWSE_SAVE_FILE);
	c.title("Export");
	c.options(Fl_Native_File_Chooser::SAVEAS_CONFIRM);
	if (addresses) {
		c.filter("vCard (*.vcf)\t*.vcf");
	} else {
		c.filter("iCalendar (*.ics)\t*.ics\nDate Book backup (*.json)\t*.json");
	}
	c.show();
	const char* chosen = c.filename();
	if (chosen == nullptr || *chosen == '\0') {
		return std::nullopt;
	}
	const fs::path typed = from_utf8(chosen);
	fs::path path;
	std::string text;
	if (addresses) {
		const std::vector<model::Address> list = address::visible(ctx);
		text = vcard::export_vcard(
		    list, [&ctx](std::int32_t id) -> std::optional<std::string> { return ctx.store.category_name(id); });
		path = detail::with_ext(typed, "vcf");
	} else {
		// The typed extension wins. Without one, the chosen filter gives the format.
		const std::string ext = extension_of(typed);
		bool as_json = c.filter_value() == 1;
		if (ext == "json") {
			as_json = true;
		} else if (ext == "ics") {
			as_json = false;
		}
		if (as_json) {
			text = backup_json(ctx.store);
		} else {
			std::vector<model::Event> visible;
			for (const model::Event& e : ctx.store.events) {
				if (ctx.show_private || !e.is_private) {
					visible.push_back(e);
				}
			}
			text =
			    ical::export_ical(visible, std::chrono::floor<std::chrono::seconds>(std::chrono::system_clock::now()));
		}
		path = detail::with_ext(typed, as_json ? "json" : "ics");
	}
	if (const std::optional<std::string> why = write_file(path, text)) {
		dialogs::message("Palm Desktop", dialogs::Icon::Warning, std::format("The file could not be saved.\n{}", *why));
		return std::nullopt;
	}
	return std::format("Exported to {}.", to_utf8(path.filename()));
}

void import(view::Ctx& ctx) {
	Fl_Native_File_Chooser c(Fl_Native_File_Chooser::BROWSE_FILE);
	c.title("Import");
	c.filter("iCalendar (*.ics)\t*.ics\nDate Book backup (*.json)\t*.json\nvCard (*.vcf)\t*.vcf");
	c.show();
	const char* chosen = c.filename();
	if (chosen == nullptr || *chosen == '\0') {
		return;
	}
	const std::expected<std::string, std::string> loaded = read_text(from_utf8(chosen));
	if (!loaded) {
		dialogs::message("Palm Desktop", dialogs::Icon::Warning,
		                 std::format("The file could not be read.\n{}", loaded.error()));
		return;
	}
	const std::string_view body = trim_start_body(*loaded);
	std::expected<std::string, std::string> message;
	if (starts_with_ignore_case(body, "BEGIN:VCALENDAR")) {
		message = import_ics(ctx, *loaded);
	} else if (starts_with_ignore_case(body, "BEGIN:VCARD")) {
		message = import_vcard(ctx, body);
	} else {
		message = import_json(ctx, body);
	}
	if (message) {
		dialogs::message("Palm Desktop", dialogs::Icon::Info, *message);
	} else {
		dialogs::message("Palm Desktop", dialogs::Icon::Warning,
		                 "This file is not a Date Book backup or an iCalendar file.");
	}
}

namespace detail {

std::filesystem::path with_ext(const std::filesystem::path& path, std::string_view ext) {
	const std::string have = extension_of(path);
	if (have == "ics" || have == "json" || have == "vcf") {
		return path;
	}
	fs::path out = path;
	out += ".";
	out += std::string(ext);
	return out;
}

} // namespace detail

} // namespace palm::transfer
