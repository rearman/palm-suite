// Print, as desktop-rs/app/src/printing.rs: the title of the view in bold, then its lines in 10 pt, wrapped at the page
// width and split into pages. The system print dialog sets the printer and the paper.
#include "palm/printing.hpp"

#include "palm/dialogs.hpp"
#include "palm/draw.hpp"
#include "palm/text.hpp"
#include "palm/theme.hpp"

#include <FL/Enumerations.H>
#include <FL/Fl_Printer.H>
#include <FL/fl_draw.H>

#include <cstddef>
#include <string>
#include <utility>

namespace palm::printing {
namespace {

// The byte offset after the first `n` code points of `s`, as Rust's chars().take(n) ends.
std::size_t offset_after(std::string_view s, std::size_t n) {
	std::size_t i = 0;
	for (std::size_t k = 0; k < n && i < s.size(); ++k) {
		i += palm::text::decode_at(s, i).len;
	}
	return i;
}

// The number of code points of `s`, as Rust's chars().count().
std::size_t code_points(std::string_view s) {
	std::size_t n = 0;
	for (std::size_t i = 0; i < s.size(); ++n) {
		i += palm::text::decode_at(s, i).len;
	}
	return n;
}

// Rust's str::trim_end: `s` without the White_Space code points at its end.
std::string_view trim_end(std::string_view s) {
	std::size_t keep = 0;
	for (std::size_t i = 0; i < s.size();) {
		const palm::text::Decoded d = palm::text::decode_at(s, i);
		if (!palm::text::is_white_space(d.cp)) {
			keep = i + d.len;
		}
		i += d.len;
	}
	return s.substr(0, keep);
}

// The parts of `s` that each end after a space, as Rust's split_inclusive(' ').
std::vector<std::string_view> split_after_space(std::string_view s) {
	std::vector<std::string_view> parts;
	std::size_t start = 0;
	for (std::size_t i = 0; i < s.size(); ++i) {
		if (s[i] == ' ') {
			parts.push_back(s.substr(start, i + 1 - start));
			start = i + 1;
		}
	}
	if (start < s.size()) {
		parts.push_back(s.substr(start));
	}
	return parts;
}

// Sets the font of the title (bold, 12 pt) or of a line (10 pt).
void set_font(bool bold) {
	const theme::Fonts& f = theme::fonts();
	fl_font(bold ? f.sans_bold : f.sans, bold ? 12 : 10);
}

// The width of a text in the bold or the plain print font, in whole pixels.
std::function<int(std::string_view)> measure_in(bool bold) {
	return [bold](std::string_view s) {
		set_font(bold);
		return draw::text_w(s);
	};
}

// Says that the job failed, with the error text on a second line, as the Rust app does.
void job_failed(std::string_view detail) {
	dialogs::message("Palm Desktop", dialogs::Icon::Warning, "The print job failed.\n" + std::string(detail));
}

} // namespace

std::vector<std::string> wrap(std::string_view text, int width, const std::function<int(std::string_view)>& measure) {
	std::vector<std::string> out;
	for (std::string_view para : palm::text::split(text, '\n')) {
		if (para.empty()) {
			out.emplace_back();
			continue;
		}
		std::string line;
		for (std::string_view word : split_after_space(para)) {
			const std::string candidate = line + std::string(word);
			if (measure(trim_end(candidate)) <= width) {
				line += word;
				continue;
			}
			if (!line.empty()) {
				out.emplace_back(trim_end(line));
				line.clear();
			}
			// A word wider than the row is cut by its characters.
			std::string rest(word);
			while (measure(trim_end(rest)) > width && code_points(rest) > 1) {
				std::size_t n = code_points(rest) - 1;
				while (n > 1 && measure(rest.substr(0, offset_after(rest, n))) > width) {
					--n;
				}
				out.emplace_back(rest.substr(0, offset_after(rest, n)));
				rest = rest.substr(offset_after(rest, n));
			}
			line = rest;
		}
		if (!trim_end(line).empty() || out.empty()) {
			out.emplace_back(trim_end(line));
		}
	}
	return out;
}

void print(std::string_view title, const std::vector<std::string>& lines) {
	Fl_Printer p;
	char* perr = nullptr;
	if (p.begin_job(0, nullptr, nullptr, &perr) != 0) {
		// The Rust returns silently on any begin_job error (a canceled dialog or a failure); FLTK's message
		// buffer is freed unread.
		delete[] perr;
		return;
	}
	// Starts a page; on failure ends the job and says so, and returns false.
	const auto start_page = [&p]() {
		const int rc = p.begin_page();
		if (rc == 0) {
			return true;
		}
		p.end_job();
		job_failed("begin_page() failed with error code " + std::to_string(rc));
		return false;
	};
	if (!start_page()) {
		return;
	}
	int pw = 0;
	int ph = 0;
	p.printable_rect(&pw, &ph);
	// The rows are measured on the printer, in its fonts.
	std::vector<std::pair<bool, std::string>> rows;
	for (std::string& r : wrap(title, pw, measure_in(true))) {
		rows.emplace_back(true, std::move(r));
	}
	for (const std::string& l : lines) {
		for (std::string& r : wrap(l, pw, measure_in(false))) {
			rows.emplace_back(false, std::move(r));
		}
	}
	int y = 0;
	for (const auto& [bold, text] : rows) {
		set_font(bold);
		const int h = fl_height();
		if (y + h > ph && y > 0) {
			p.end_page();
			if (!start_page()) {
				return;
			}
			y = 0;
			set_font(bold);
		}
		fl_color(FL_BLACK);
		fl_draw(text.c_str(), 0, y + h - fl_descent());
		y += h;
	}
	p.end_page();
	p.end_job();
}

} // namespace palm::printing
