#include "palm/settings.hpp"
#include "palm/platform.hpp"
#include "palm/text.hpp"

#include <algorithm>
#include <charconv>
#include <chrono>
#include <cstddef>
#include <cstdlib>
#include <format>
#include <fstream>
#include <iterator>
#include <optional>
#include <string>
#include <system_error>
#include <utility>
#include <vector>

#ifdef _WIN32
#include <stdlib.h> // _wgetenv
#endif

namespace palm::settings {

namespace fs = std::filesystem;

namespace {

// The comment line of every save, as Settings.kt.
constexpr std::string_view COMMENT = "The token and the key are saved in a file that only the user can read. "
                                     "The records in desktop-data.json are plain text, as in Palm Desktop.";

// The blanks of a .properties line: space, tab, and form feed.
constexpr std::string_view BLANK = " \t\f";

// The value of an environment variable, or nothing when it is unset or empty.
std::optional<fs::path> var(const char* name) {
#ifdef _WIN32
	const std::wstring wide(name, name + std::char_traits<char>::length(name));
	const wchar_t* value = _wgetenv(wide.c_str());
#else
	const char* value = std::getenv(name);
#endif
	if (value == nullptr || *value == '\0') {
		return std::nullopt;
	}
	return fs::path(value);
}

// The UTF-8 text of ISO-8859-1 bytes, as Properties.load(InputStream) reads them.
std::string latin1(std::string_view bytes) {
	std::string out;
	for (const char b : bytes) {
		text::append_utf8(out, static_cast<char32_t>(static_cast<unsigned char>(b)));
	}
	return out;
}

// The text without its leading blanks.
std::string_view skip_blanks(std::string_view s) {
	const std::size_t n = s.find_first_not_of(BLANK);
	return n == std::string_view::npos ? std::string_view() : s.substr(n);
}

// How many backslashes end the text.
std::size_t trailing_backslashes(std::string_view s) {
	std::size_t n = 0;
	while (n < s.size() && s[s.size() - 1 - n] == '\\') {
		++n;
	}
	return n;
}

// Appends the UTF-16 units of a code point.
void append_utf16(std::vector<char16_t>& out, char32_t cp) {
	if (cp < 0x10000) {
		out.push_back(static_cast<char16_t>(cp));
		return;
	}
	cp -= 0x10000;
	out.push_back(static_cast<char16_t>(0xD800 + (cp >> 10)));
	out.push_back(static_cast<char16_t>(0xDC00 + (cp & 0x3FF)));
}

// UTF-16 units as UTF-8, a surrogate pair joined and each unpaired surrogate one U+FFFD, as Rust's from_utf16_lossy.
std::string utf16_to_utf8(const std::vector<char16_t>& units) {
	std::string out;
	for (std::size_t i = 0; i < units.size(); ++i) {
		const char32_t u = units[i];
		const bool high = u >= 0xD800 && u <= 0xDBFF;
		const bool low_follows = i + 1 < units.size() && units[i + 1] >= 0xDC00 && units[i + 1] <= 0xDFFF;
		if (high && low_follows) {
			const char32_t cp = 0x10000 + ((u - 0xD800) << 10) + (units[i + 1] - 0xDC00);
			text::append_utf8(out, cp);
			++i;
		} else if (u >= 0xD800 && u <= 0xDFFF) {
			text::append_utf8(out, 0xFFFD);
		} else {
			text::append_utf8(out, u);
		}
	}
	return out;
}

// The four hex digits at `i` of the code points, as one UTF-16 unit. None unless all four are hex digits.
std::optional<char16_t> hex_unit(const std::vector<char32_t>& cps, std::size_t i) {
	if (i + 4 > cps.size()) {
		return std::nullopt;
	}
	std::string hex;
	for (std::size_t k = i; k < i + 4; ++k) {
		if (cps[k] > 0x7f) {
			return std::nullopt;
		}
		hex += static_cast<char>(cps[k]);
	}
	// Rust's u16::from_str_radix takes one leading '+' before the digits, so "+041" reads as 0x41. A '-' is not
	// taken, as from_chars does not take a sign.
	std::string_view digits = hex;
	if (digits.front() == '+') {
		digits.remove_prefix(1);
	}
	unsigned value = 0;
	const auto [end, ec] = std::from_chars(digits.data(), digits.data() + digits.size(), value, 16);
	if (ec != std::errc() || end != digits.data() + digits.size()) {
		return std::nullopt;
	}
	return static_cast<char16_t>(value);
}

// Undoes the escapes: \t, \n, \r, \f, \uXXXX (UTF-16 units, so a surrogate pair joins), and \c for c.
std::string unescape(std::string_view s) {
	std::vector<char32_t> cps;
	for (std::size_t i = 0; i < s.size();) {
		const auto d = text::decode_at(s, i);
		cps.push_back(d.cp);
		i += d.len;
	}
	std::vector<char16_t> units;
	std::size_t i = 0;
	while (i < cps.size()) {
		const char32_t c = cps[i++];
		if (c != U'\\' || i >= cps.size()) {
			append_utf16(units, c);
			continue;
		}
		const char32_t n = cps[i++];
		switch (n) {
		case U't':
			units.push_back(u'\t');
			break;
		case U'n':
			units.push_back(u'\n');
			break;
		case U'r':
			units.push_back(u'\r');
			break;
		case U'f':
			units.push_back(u'\f');
			break;
		case U'u':
			if (const auto unit = hex_unit(cps, i)) {
				units.push_back(*unit);
				i += 4;
			} else {
				units.push_back(u'u');
			}
			break;
		default:
			append_utf16(units, n);
			break;
		}
	}
	return utf16_to_utf8(units);
}

// Escapes one key or value. A leading space is escaped, and every space too when `escape_space` is set.
std::string escape(std::string_view s, bool escape_space) {
	std::string out;
	std::size_t index = 0;
	for (std::size_t pos = 0; pos < s.size(); ++index) {
		const auto d = text::decode_at(s, pos);
		pos += d.len;
		const char32_t c = d.cp;
		if (c == U' ' && (index == 0 || escape_space)) {
			out += "\\ ";
		} else if (c == U'\\') {
			out += "\\\\";
		} else if (c == U'\t') {
			out += "\\t";
		} else if (c == U'\n') {
			out += "\\n";
		} else if (c == U'\r') {
			out += "\\r";
		} else if (c == U'\f') {
			out += "\\f";
		} else if (c == U'=' || c == U':' || c == U'#' || c == U'!') {
			out += '\\';
			out += static_cast<char>(c);
		} else if (c < 0x20 || c > 0x7e) {
			std::vector<char16_t> units;
			append_utf16(units, c);
			for (const char16_t u : units) {
				out += std::format("\\u{:04X}", static_cast<unsigned>(u));
			}
		} else {
			out += static_cast<char>(c);
		}
	}
	return out;
}

// The date line of a save: the local time as java.util.Date.toString writes it, e.g. "Tue Nov 03 10:15:00 CST 2026".
// The zone abbreviation comes from platform::local_zone, as jiff's %Z gives it ("EST"); the C runtime's %Z gives
// the Windows name ("Eastern Standard Time").
std::string local_stamp() {
	const std::chrono::zoned_time now{platform::local_zone(),
	                                  std::chrono::floor<std::chrono::seconds>(std::chrono::system_clock::now())};
	return std::format("{:%a %b %d %H:%M:%S %Z %Y}", now);
}

} // namespace

fs::path config_dir() {
	if (const auto app_data = var("APPDATA")) {
		return *app_data / "palm-suite";
	}
	if (const auto xdg = var("XDG_CONFIG_HOME")) {
		return *xdg / "palm-suite";
	}
	auto home = var("HOME");
	if (!home) {
		home = var("USERPROFILE");
	}
	return (home ? *home : fs::path(".")) / ".config" / "palm-suite";
}

Settings::Settings(fs::path path, Props props) : path(std::move(path)), props(std::move(props)) {}

Settings Settings::open(const fs::path& dir) {
	const fs::path path = dir / fs::path(FILE_NAME);
	Props props;
	std::ifstream in(path, std::ios::binary);
	if (in) {
		const std::string bytes((std::istreambuf_iterator<char>(in)), std::istreambuf_iterator<char>());
		props = parse(latin1(bytes));
	}
	return Settings(path, std::move(props));
}

std::string Settings::get(std::string_view key, std::string_view def) const {
	const auto it = props.find(std::string(key));
	return it == props.end() ? std::string(def) : it->second;
}

std::expected<void, std::string> Settings::set(std::string_view key, std::string_view value) {
	props.insert_or_assign(std::string(key), std::string(value));
	return save();
}

std::expected<void, std::string> Settings::remove(std::string_view key) {
	props.erase(std::string(key));
	return save();
}

int Settings::get_int(std::string_view key, int def) const {
	const auto it = props.find(std::string(key));
	if (it == props.end()) {
		return def;
	}
	return text::parse_i32(it->second).value_or(def);
}

std::expected<void, std::string> Settings::set_int(std::string_view key, int value) {
	return set(key, std::to_string(value));
}

bool Settings::get_bool(std::string_view key, bool def) const {
	const auto it = props.find(std::string(key));
	if (it == props.end()) {
		return def;
	}
	if (it->second == "true") {
		return true;
	}
	if (it->second == "false") {
		return false;
	}
	return def;
}

std::expected<void, std::string> Settings::set_bool(std::string_view key, bool value) {
	return set(key, value ? "true" : "false");
}

std::expected<void, std::string> Settings::save() const {
	std::error_code ec;
	if (path.has_parent_path()) {
		fs::create_directories(path.parent_path(), ec);
		if (ec) {
			return std::unexpected("cannot create " + path.parent_path().string() + ": " + ec.message());
		}
	}
	const std::string stamp = local_stamp();
	{
		// store() writes pure ASCII, so its bytes are the ISO-8859-1 bytes that Java reads.
		std::ofstream out(path, std::ios::binary | std::ios::trunc);
		out << store(props, COMMENT, stamp);
		out.close();
		if (out.fail()) {
			return std::unexpected("cannot write " + path.string());
		}
	}
#ifndef _WIN32
	// Only the user can read the file, as Settings.kt: the token and the key are in it.
	fs::permissions(path, fs::perms::owner_read | fs::perms::owner_write, ec);
	if (ec) {
		return std::unexpected("cannot set the permissions of " + path.string() + ": " + ec.message());
	}
#endif
	return {};
}

Props parse(std::string_view text) {
	// The lines, split at LF and CR, as str::split(['\n', '\r']) gives them.
	std::vector<std::string_view> lines;
	std::size_t start = 0;
	for (std::size_t i = 0; i <= text.size(); ++i) {
		if (i == text.size() || text[i] == '\n' || text[i] == '\r') {
			lines.push_back(text.substr(start, i - start));
			start = i + 1;
		}
	}
	Props out;
	std::size_t i = 0;
	while (i < lines.size()) {
		std::string line(skip_blanks(lines[i]));
		++i;
		if (line.empty() || line[0] == '#' || line[0] == '!') {
			continue;
		}
		// A line that ends in an odd number of backslashes continues on the next line.
		while (trailing_backslashes(line) % 2 == 1) {
			line.pop_back();
			if (i >= lines.size()) {
				break;
			}
			line += skip_blanks(lines[i]);
			++i;
		}
		// The key ends at a separator or a blank, and an escaped char is skipped whole. The separators are ASCII, so
		// the bytes of a UTF-8 char never end a key.
		std::size_t k = 0;
		while (k < line.size() && line[k] != '=' && line[k] != ':' && BLANK.find(line[k]) == std::string_view::npos) {
			k += line[k] == '\\' ? 2 : 1;
		}
		const std::string key = unescape(std::string_view(line).substr(0, k));
		while (k < line.size() && BLANK.find(line[k]) != std::string_view::npos) {
			++k;
		}
		if (k < line.size() && (line[k] == '=' || line[k] == ':')) {
			++k;
		}
		while (k < line.size() && BLANK.find(line[k]) != std::string_view::npos) {
			++k;
		}
		const std::string value = unescape(std::string_view(line).substr(std::min(k, line.size())));
		out.insert_or_assign(key, value);
	}
	return out;
}

std::string store(const Props& props, std::string_view comment, std::string_view stamp) {
	std::string out = "#" + std::string(comment) + "\n#" + std::string(stamp) + "\n";
	for (const auto& [k, v] : props) {
		out += escape(k, true);
		out += '=';
		out += escape(v, false);
		out += '\n';
	}
	return out;
}

} // namespace palm::settings
