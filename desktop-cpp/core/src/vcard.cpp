#include "palm/vcard.hpp"

#include "palm/ical.hpp"
#include "palm/model.hpp"
#include "palm/text.hpp"

#include <algorithm>
#include <array>
#include <cstddef>
#include <cstdint>
#include <format>
#include <map>
#include <optional>
#include <ranges>
#include <span>
#include <string>
#include <string_view>
#include <utility>
#include <vector>

namespace palm::vcard {

namespace {

// The encodings that a 2.1 parameter with no name can name.
constexpr std::array<std::string_view, 5> ENCODINGS = {"QUOTED-PRINTABLE", "BASE64", "B", "8BIT", "7BIT"};

// The parameters of a property, by upper-case name, each with its values.
using Params = std::map<std::string, std::vector<std::string>>;

// One content line, as name, parameters, and value.
struct Prop {
	std::string name;
	Params params;
	std::string value;
};

// A phone or e-mail field of a card, before it takes one of the five phone slots.
struct PhoneEntry {
	std::int32_t label = 0;
	std::string number;
	bool pref = false;
};

// The charsets that decode the bytes of a quoted-printable value.
enum class Charset { Utf8, Latin1, Cp1252 };

// The windows-1252 code points of bytes 0x80 to 0x9F; the bytes Microsoft left undefined keep their C1 code point.
constexpr std::array<char32_t, 32> CP1252_HIGH = {
    0x20AC, 0x81,   0x201A, 0x192,  0x201E, 0x2026, 0x2020, 0x2021, 0x2C6,  0x2030, 0x160,
    0x2039, 0x152,  0x8D,   0x17D,  0x8F,   0x90,   0x2018, 0x2019, 0x201C, 0x201D, 0x2022,
    0x2013, 0x2014, 0x2DC,  0x2122, 0x161,  0x203A, 0x153,  0x9D,   0x17E,  0x178,
};

// Splits `s` at `sep` where `sep` is not escaped. The parts keep their escapes.
std::vector<std::string> split_escaped(std::string_view s, char sep) {
	std::vector<std::string> out;
	std::string cur;
	for (std::size_t i = 0; i < s.size(); ++i) {
		if (s[i] == '\\') {
			cur += s[i];
			if (i + 1 < s.size()) {
				cur += s[++i];
			}
		} else if (s[i] == sep) {
			out.push_back(std::move(cur));
			cur.clear();
		} else {
			cur += s[i];
		}
	}
	out.push_back(std::move(cur));
	return out;
}

// Splits at whitespace as Java's "\\s+" regex: space, tab, line feed, vertical tab, form feed, return.
bool is_split_space(char c) {
	return c == ' ' || c == '\t' || c == '\n' || c == '\v' || c == '\f' || c == '\r';
}

std::vector<std::string_view> words(std::string_view s) {
	std::vector<std::string_view> out;
	std::size_t start = 0;
	for (std::size_t i = 0; i < s.size(); ++i) {
		if (is_split_space(s[i])) {
			if (i > start) {
				out.push_back(s.substr(start, i - start));
			}
			start = i + 1;
		}
	}
	if (s.size() > start) {
		out.push_back(s.substr(start));
	}
	return out;
}

// The view of `s` without its quote characters at either end, as Rust's trim_matches('"').
std::string_view trim_quotes(std::string_view s) {
	while (s.starts_with('"')) {
		s.remove_prefix(1);
	}
	while (s.ends_with('"')) {
		s.remove_suffix(1);
	}
	return s;
}

// The Palm label of a TEL field, from its TYPE values. FAX wins over WORK and HOME.
std::int32_t tel_label(const std::vector<std::string>& t) {
	auto has = [&t](std::string_view x) { return std::ranges::find(t, x) != t.end(); };
	if (has("FAX")) {
		return model::FAX;
	}
	if (has("CELL") || has("MOBILE")) {
		return model::MOBILE;
	}
	if (has("PAGER")) {
		return model::PAGER;
	}
	if (has("MAIN") || has("X-MAIN") || has("COMPANY")) {
		return model::MAIN;
	}
	if (has("WORK")) {
		return model::WORK;
	}
	if (has("HOME")) {
		return model::HOME;
	}
	return model::OTHER;
}

// The TYPE values of `p`, upper-cased.
std::vector<std::string> types(const Prop& p) {
	std::vector<std::string> out;
	if (auto it = p.params.find("TYPE"); it != p.params.end()) {
		for (const std::string& t : it->second) {
			out.push_back(text::to_uppercase(t));
		}
	}
	return out;
}

bool is_pref(const Prop& p) {
	std::vector<std::string> t = types(p);
	return std::ranges::find(t, "PREF") != t.end() || p.params.contains("PREF");
}

// The charset of a CHARSET name. Shortcut: UTF-8, ISO-8859-1 and windows-1252 only, and UTF-8 for any other
// name. Upgrade path: a charset library such as ICU's converters or libiconv.
Charset charset(std::string_view name) {
	if (text::eq_ignore_ascii_case(name, "ISO-8859-1") || text::eq_ignore_ascii_case(name, "ISO8859-1") ||
	    text::eq_ignore_ascii_case(name, "ISO_8859_1") || text::eq_ignore_ascii_case(name, "LATIN1")) {
		return Charset::Latin1;
	}
	if (text::eq_ignore_ascii_case(name, "WINDOWS-1252") || text::eq_ignore_ascii_case(name, "CP1252")) {
		return Charset::Cp1252;
	}
	return Charset::Utf8;
}

// The code point of the windows-1252 byte `b`.
char32_t cp1252(std::uint8_t b) {
	if (b >= 0x80 && b < 0xA0) {
		return CP1252_HIGH[b - 0x80];
	}
	return b;
}

// The text of bytes in `cs`.
std::string decode(std::string_view bytes, Charset cs) {
	if (cs == Charset::Utf8) {
		return text::from_utf8_lossy(bytes);
	}
	std::string out;
	for (char b : bytes) {
		auto u = static_cast<std::uint8_t>(b);
		text::append_utf8(out, cs == Charset::Latin1 ? static_cast<char32_t>(u) : cp1252(u));
	}
	return out;
}

// The bytes of a char; an unmappable char gives "?", as Java's encoder.
std::string encode(char32_t c, Charset cs) {
	if (cs == Charset::Latin1) {
		return c < 256 ? std::string(1, static_cast<char>(c)) : std::string("?");
	}
	if (cs == Charset::Cp1252) {
		for (int b = 0; b <= 255; ++b) {
			if (cp1252(static_cast<std::uint8_t>(b)) == c) {
				return std::string(1, static_cast<char>(b));
			}
		}
		return "?";
	}
	std::string out;
	text::append_utf8(out, c);
	return out;
}

// Whether a code point is an ASCII hex digit.
bool is_hex(char32_t c) {
	return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
}

// The value of an ASCII hex digit, which `is_hex` accepts.
std::uint32_t hex_value(char32_t c) {
	if (c >= '0' && c <= '9') {
		return c - '0';
	}
	if (c >= 'a' && c <= 'f') {
		return c - 'a' + 10;
	}
	return c - 'A' + 10;
}

// Decodes "=XX" to one byte. A "=" with no hex digits is a soft break that unfolding did not join.
std::string decode_qp(std::string_view s, Charset cs) {
	std::vector<char32_t> chars;
	for (std::size_t i = 0; i < s.size();) {
		text::Decoded d = text::decode_at(s, i);
		chars.push_back(d.cp);
		i += d.len;
	}
	std::string bytes;
	std::size_t i = 0;
	while (i < chars.size()) {
		char32_t c = chars[i];
		if (c == '=' && i + 2 < chars.size() && is_hex(chars[i + 1]) && is_hex(chars[i + 2])) {
			bytes.push_back(static_cast<char>(hex_value(chars[i + 1]) * 16 + hex_value(chars[i + 2])));
			i += 3;
		} else if (c == '=') {
			++i;
		} else {
			bytes += encode(c, cs);
			++i;
		}
	}
	return decode(bytes, cs);
}

// The value of `p` with quoted-printable bytes decoded. Bytes decode as UTF-8 unless CHARSET names another charset.
std::string value(const Prop& p) {
	bool qp = false;
	if (auto it = p.params.find("ENCODING"); it != p.params.end()) {
		qp = std::ranges::any_of(
		    it->second, [](const std::string& e) { return text::eq_ignore_ascii_case(e, "QUOTED-PRINTABLE"); });
	}
	if (!qp) {
		return p.value;
	}
	std::string_view name = "UTF-8";
	if (auto it = p.params.find("CHARSET"); it != p.params.end() && !it->second.empty()) {
		name = it->second.front();
	}
	return decode_qp(p.value, charset(name));
}

// The structured parts of `p` split at `sep`, each unescaped. Never empty.
std::vector<std::string> parts(const Prop& p, char sep) {
	std::vector<std::string> out;
	for (const std::string& s : split_escaped(value(p), sep)) {
		out.push_back(ical::unescape(s));
	}
	return out;
}

// Whether a content line ends in a quoted-printable soft break: "=" at its end, in a QUOTED-PRINTABLE property.
bool is_soft(std::string_view line) {
	if (!line.ends_with('=')) {
		return false;
	}
	std::string head = text::to_uppercase(line.substr(0, line.find(':')));
	return head.find("QUOTED-PRINTABLE") != std::string::npos;
}

// Joins the continuation lines of `text`: a line that starts with a space or tab continues the line before it, and a
// quoted-printable line that ends in "=" continues on the next line.
std::vector<std::string> unfold(std::string_view text) {
	if (text.starts_with("\xEF\xBB\xBF")) {
		text.remove_prefix(3);
	}
	std::vector<std::string> out;
	bool soft = false;
	std::size_t start = 0;
	for (;;) {
		std::size_t nl = text.find('\n', start);
		std::string_view l = text.substr(start, nl == std::string_view::npos ? std::string_view::npos : nl - start);
		if (l.ends_with('\r')) {
			l.remove_suffix(1);
		}
		if (!out.empty() && (soft || l.starts_with(' ') || l.starts_with('\t'))) {
			std::string& last = out.back();
			if (soft) {
				last.pop_back();
				last += l;
			} else {
				last += l.substr(1);
			}
			soft = is_soft(last);
		} else if (!model::is_blank(l)) {
			out.emplace_back(l);
			soft = is_soft(l);
		}
		if (nl == std::string_view::npos) {
			break;
		}
		start = nl + 1;
	}
	return out;
}

// Splits a content line into its name, parameters, and value, or nullopt when it has no colon outside quotes.
std::optional<Prop> prop(std::string_view line) {
	bool quoted = false;
	std::optional<std::size_t> colon;
	for (std::size_t i = 0; i < line.size(); ++i) {
		if (line[i] == '"') {
			quoted = !quoted;
		} else if (line[i] == ':' && !quoted) {
			colon = i;
			break;
		}
	}
	if (!colon) {
		return std::nullopt;
	}
	std::vector<std::string_view> head = text::split(line.substr(0, *colon), ';');
	Prop p;
	for (std::size_t k = 1; k < head.size(); ++k) {
		std::string_view part = head[k];
		auto eq = part.find('=');
		if (eq != std::string_view::npos && eq > 0) {
			std::string key = text::to_uppercase(part.substr(0, eq));
			for (std::string_view v : text::split(trim_quotes(part.substr(eq + 1)), ',')) {
				p.params[key].emplace_back(v);
			}
		} else if (!model::is_blank(part)) {
			// A 2.1 parameter with no name: an encoding, else a type such as CELL.
			std::string word = text::to_uppercase(text::trim(part));
			bool encoding = std::ranges::find(ENCODINGS, std::string_view(word)) != ENCODINGS.end();
			p.params[encoding ? "ENCODING" : "TYPE"].push_back(word);
		}
	}
	std::string_view name = head[0];
	if (auto dot = name.rfind('.'); dot != std::string_view::npos) {
		name = name.substr(dot + 1);
	}
	p.name = text::to_uppercase(name);
	p.value = std::string(line.substr(*colon + 1));
	return p;
}

// The TEL or EMAIL type of a phone label, with the Palm label kept on the way back.
std::string_view phone_type(std::int32_t label) {
	switch (label) {
	case model::EMAIL:
		return "EMAIL;TYPE=INTERNET";
	case model::WORK:
		return "TEL;TYPE=WORK,VOICE";
	case model::HOME:
		return "TEL;TYPE=HOME,VOICE";
	case model::FAX:
		return "TEL;TYPE=WORK,FAX";
	case model::MAIN:
		return "TEL;TYPE=X-MAIN";
	case model::PAGER:
		return "TEL;TYPE=PAGER";
	case model::MOBILE:
		return "TEL;TYPE=CELL";
	default:
		return "TEL;TYPE=VOICE";
	}
}

// Whether `s` starts with "tel:" in ASCII case. Rust's get(..4) gives None when byte 4 is inside a character, so
// such a value does not start with "tel:" here either.
bool starts_with_tel(std::string_view s) {
	if (s.size() < 4) {
		return false;
	}
	if (s.size() > 4 && (static_cast<std::uint8_t>(s[4]) & 0xC0) == 0x80) {
		return false;
	}
	return text::eq_ignore_ascii_case(s.substr(0, 4), "tel:");
}

// Builds the address of one card. nullopt when the card has no usable field.
std::optional<Parsed> build(const std::vector<Prop>& props) {
	auto first = [&props](std::string_view n) -> const Prop* {
		for (const Prop& p : props) {
			if (p.name == n) {
				return &p;
			}
		}
		return nullptr;
	};
	auto field = [&first](std::string_view n) -> std::string {
		const Prop* p = first(n);
		return p != nullptr ? ical::unescape(value(*p)) : std::string();
	};
	std::string company;
	if (const Prop* p = first("ORG")) {
		company = parts(*p, ';').front();
	}
	std::vector<std::string> n;
	if (const Prop* p = first("N")) {
		n = parts(*p, ';');
	}
	std::string last = n.size() > 0 ? n[0] : std::string();
	std::string given = n.size() > 1 ? n[1] : std::string();
	if (last.empty() && given.empty()) {
		// An FN that repeats the company names the company only, so it gives no name.
		std::string full_text = field("FN");
		std::string_view full = text::trim(full_text);
		if (!full.empty() && full != text::trim(company)) {
			std::vector<std::string_view> w = words(full);
			last = w.empty() ? std::string() : std::string(w.back());
			given.clear();
			for (std::size_t k = 0; k + 1 < w.size(); ++k) {
				if (k > 0) {
					given += ' ';
				}
				given += w[k];
			}
		}
	}

	std::vector<PhoneEntry> entries;
	for (const Prop& p : props) {
		if (p.name != "TEL" && p.name != "EMAIL") {
			continue;
		}
		std::string number = ical::unescape(value(p));
		if (p.name == "TEL" && starts_with_tel(number)) {
			number.erase(0, 4);
		}
		if (number.empty()) {
			continue;
		}
		std::int32_t label = p.name == "EMAIL" ? model::EMAIL : tel_label(types(p));
		entries.push_back(PhoneEntry{label, std::move(number), is_pref(p)});
	}
	// The first five take slots 0 to 4. The other slots keep the default labels that no kept phone uses.
	std::size_t kept = std::min<std::size_t>(entries.size(), 5);
	std::vector<std::string> phones(5);
	std::vector<std::int32_t> labels(5, model::OTHER);
	std::vector<std::int32_t> used;
	for (std::size_t i = 0; i < kept; ++i) {
		phones[i] = entries[i].number;
		labels[i] = entries[i].label;
		used.push_back(entries[i].label);
	}
	std::vector<std::int32_t> free_labels;
	for (std::int32_t l : {model::WORK, model::HOME, model::FAX, model::OTHER, model::EMAIL}) {
		if (std::ranges::find(used, l) == used.end()) {
			free_labels.push_back(l);
		}
	}
	for (std::size_t i = kept; i < 5; ++i) {
		labels[i] = free_labels[i - kept];
	}

	std::vector<std::vector<std::string>> adrs;
	for (const Prop& p : props) {
		if (p.name == "ADR") {
			adrs.push_back(parts(p, ';'));
		}
	}
	std::vector<std::string> adr = adrs.empty() ? std::vector<std::string>() : adrs.front();
	std::vector<std::string> note_lines;
	std::string note = field("NOTE");
	if (!note.empty()) {
		note_lines.push_back(note);
	}
	for (std::size_t c = 1; c < adrs.size(); ++c) {
		std::string s;
		for (const std::string& x : adrs[c]) {
			if (model::is_blank(x)) {
				continue;
			}
			if (!s.empty()) {
				s += ", ";
			}
			s += x;
		}
		if (!s.empty()) {
			note_lines.push_back("Address: " + s);
		}
	}
	for (std::size_t k = 5; k < entries.size(); ++k) {
		note_lines.push_back(std::string(model::LABELS[static_cast<std::size_t>(entries[k].label)]) + ": " +
		                     entries[k].number);
	}
	auto at = [&adr](std::size_t i) -> std::string { return i < adr.size() ? adr[i] : std::string(); };

	model::Address card;
	card.last_name = last;
	card.first_name = given;
	card.title = field("TITLE");
	card.company = company;
	card.phone_labels = labels;
	card.phones = phones;
	card.address = at(2);
	card.city = at(3);
	card.state = at(4);
	card.zip = at(5);
	card.country = at(6);
	card.custom.clear();
	for (int i = 1; i <= 4; ++i) {
		card.custom.push_back(field(std::format("X-PALM-CUSTOM{}", i)));
	}
	card.note = note_lines.empty() ? std::string() : note_lines.front();
	for (std::size_t i = 1; i < note_lines.size(); ++i) {
		card.note += '\n';
		card.note += note_lines[i];
	}
	card.show_phone = 0;
	for (std::size_t i = 0; i < kept; ++i) {
		if (entries[i].pref) {
			card.show_phone = static_cast<std::int32_t>(i);
			break;
		}
	}
	if (const Prop* p = first("CLASS")) {
		card.is_private = text::eq_ignore_ascii_case(text::trim(value(*p)), "PRIVATE");
	}
	if (card.is_empty()) {
		return std::nullopt;
	}
	std::optional<std::string> category;
	if (const Prop* p = first("CATEGORIES")) {
		std::string name = parts(*p, ',').front();
		if (!name.empty()) {
			category = std::move(name);
		}
	}
	return Parsed{std::move(card), std::move(category)};
}

} // namespace

std::vector<Parsed> parse(std::string_view text) {
	std::vector<Parsed> out;
	std::optional<std::vector<Prop>> card;
	for (const std::string& l : unfold(text)) {
		std::optional<Prop> p = prop(l);
		if (!p) {
			continue;
		}
		if (p->name == "BEGIN" && text::eq_ignore_ascii_case(text::trim(p->value), "VCARD")) {
			card = std::vector<Prop>{};
		} else if (p->name == "END" && text::eq_ignore_ascii_case(text::trim(p->value), "VCARD")) {
			if (card) {
				if (std::optional<Parsed> c = build(*card)) {
					out.push_back(std::move(*c));
				}
				card.reset();
			}
		} else if (card) {
			card->push_back(std::move(*p));
		}
	}
	return out;
}

std::string export_vcard(std::span<const model::Address> list,
                         std::function<std::optional<std::string>(std::int32_t)> category_name) {
	std::string out;
	auto line = [&out](std::string_view s) { ical::fold(s, out); };
	for (const model::Address& a : list) {
		line("BEGIN:VCARD");
		line("VERSION:3.0");
		line("N:" + ical::escape(a.last_name) + ";" + ical::escape(a.first_name) + ";;;");
		// FN is the full name. Without a name it is the company, which parse reads as a company.
		std::vector<std::string_view> kept;
		for (std::string_view s : {std::string_view(a.first_name), std::string_view(a.last_name)}) {
			if (!model::is_blank(s)) {
				kept.push_back(s);
			}
		}
		std::string joined;
		for (std::size_t k = 0; k < kept.size(); ++k) {
			if (k > 0) {
				joined += ' ';
			}
			joined += kept[k];
		}
		std::string_view full = text::trim(joined);
		line("FN:" + ical::escape(full.empty() ? std::string_view(a.company) : full));
		if (!a.company.empty()) {
			line("ORG:" + ical::escape(a.company));
		}
		if (!a.title.empty()) {
			line("TITLE:" + ical::escape(a.title));
		}
		for (std::size_t i = 0; i < 5; ++i) {
			std::string_view number = i < a.phones.size() ? std::string_view(a.phones[i]) : std::string_view();
			if (number.empty()) {
				continue;
			}
			std::string_view pref = static_cast<std::int32_t>(i) == a.show_phone ? ",PREF" : "";
			std::int32_t label = i < a.phone_labels.size() ? a.phone_labels[i] : model::OTHER;
			line(std::format("{}{}:{}", phone_type(label), pref, ical::escape(number)));
		}
		std::array<std::string_view, 7> adr = {"", "", a.address, a.city, a.state, a.zip, a.country};
		if (std::ranges::any_of(adr, [](std::string_view s) { return !s.empty(); })) {
			std::string v;
			for (std::size_t k = 0; k < adr.size(); ++k) {
				if (k > 0) {
					v += ';';
				}
				v += ical::escape(adr[k]);
			}
			line("ADR;TYPE=HOME:" + v);
		}
		if (!a.note.empty()) {
			line("NOTE:" + ical::escape(a.note));
		}
		for (std::size_t i = 0; i < std::min<std::size_t>(a.custom.size(), 4); ++i) {
			if (!a.custom[i].empty()) {
				line(std::format("X-PALM-CUSTOM{}:{}", i + 1, ical::escape(a.custom[i])));
			}
		}
		if (a.category != 0) {
			if (std::optional<std::string> name = category_name(a.category)) {
				line("CATEGORIES:" + ical::escape(*name));
			}
		}
		if (a.is_private) {
			line("CLASS:PRIVATE");
		}
		line("END:VCARD");
	}
	return out;
}

} // namespace palm::vcard
