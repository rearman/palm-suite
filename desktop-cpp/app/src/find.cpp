// The Find dialog of Dialogs.kt, as desktop-rs/app/src/find.rs. The Rust closures of the buttons and the field become
// static callbacks that reach a Dialog through their data pointer, and the Rust Rc<Cell> of the action becomes a
// member of that Dialog.
#include "palm/find.hpp"

#include "palm/address.hpp"
#include "palm/browser.hpp"
#include "palm/days.hpp"
#include "palm/dialogs.hpp"
#include "palm/draw.hpp"
#include "palm/mnemonics.hpp"
#include "palm/model.hpp"
#include "palm/text.hpp"
#include "palm/theme.hpp"

#include <FL/Enumerations.H>
#include <FL/Fl.H>
#include <FL/Fl_Box.H>
#include <FL/Fl_Button.H>
#include <FL/Fl_Check_Button.H>
#include <FL/Fl_Double_Window.H>
#include <FL/Fl_Hold_Browser.H>
#include <FL/Fl_Input.H>
#include <FL/Fl_Window.H>
#include <FL/fl_draw.H>

#include <algorithm>
#include <array>
#include <cstddef>
#include <format>
#include <memory>
#include <optional>
#include <string>
#include <string_view>
#include <utility>
#include <variant>
#include <vector>

namespace palm::find {

namespace {

// The text Find searches in a record: the title and note of an event or a To Do, the text of a memo, or the fields
// of an address, one field to a line.
std::string join_lines(const std::vector<std::string_view>& parts) {
	std::string out;
	for (std::size_t i = 0; i < parts.size(); ++i) {
		if (i > 0) {
			out += '\n';
		}
		out += parts[i];
	}
	return out;
}

std::string searchable(const runner::Record& r) {
	if (const auto* e = std::get_if<model::Event>(&r)) {
		return e->title + "\n" + e->note;
	}
	if (const auto* t = std::get_if<model::Todo>(&r)) {
		return t->title + "\n" + t->note;
	}
	if (const auto* m = std::get_if<model::Memo>(&r)) {
		return m->text;
	}
	if (const auto* a = std::get_if<model::Address>(&r)) {
		std::vector<std::string_view> parts{a->last_name, a->first_name, a->title, a->company, a->address,
		                                    a->city,      a->state,      a->zip,   a->country, a->note};
		parts.insert(parts.end(), a->phones.begin(), a->phones.end());
		parts.insert(parts.end(), a->custom.begin(), a->custom.end());
		return join_lines(parts);
	}
	// The store finds no categories.
	return {};
}

bool is_private(const runner::Record& r) {
	if (const auto* e = std::get_if<model::Event>(&r)) {
		return e->is_private;
	}
	if (const auto* t = std::get_if<model::Todo>(&r)) {
		return t->is_private;
	}
	if (const auto* m = std::get_if<model::Memo>(&r)) {
		return m->is_private;
	}
	if (const auto* a = std::get_if<model::Address>(&r)) {
		return a->is_private;
	}
	return false;
}

// A character of a word, as \w of a Java regular expression.
bool word_char(char32_t c) {
	return (c >= U'0' && c <= U'9') || (c >= U'a' && c <= U'z') || (c >= U'A' && c <= U'Z') || c == U'_';
}

// The code point that ends just before byte `at` of `s`, which is not 0.
char32_t cp_before(std::string_view s, std::size_t at) {
	std::size_t i = at - 1;
	while (i > 0 && (static_cast<unsigned char>(s[i]) & 0xC0U) == 0x80U) {
		--i;
	}
	return text::decode_at(s, i).cp;
}

// The text in day form, "10/19/2026": the month, the day and the four-digit year.
std::string day_text(std::int64_t day) {
	const days::Ymd d = days::ymd(day);
	return std::format("{}/{}/{}", d.month, d.day, d.year);
}

// The app name, the text and the right-hand column of a result row, as the official Find dialog shows them.
struct Parts {
	std::string_view app;
	std::string text;
	std::string right;
};

Parts row_parts(const runner::Record& r, bool by) {
	if (const auto* e = std::get_if<model::Event>(&r)) {
		const std::string when = e->untimed
		                             ? day_text(e->date)
		                             : std::format("{} {:02}:{:02}", day_text(e->date), e->start / 60, e->start % 60);
		return {"Date Book", e->title, when};
	}
	if (const auto* t = std::get_if<model::Todo>(&r)) {
		return {"To Do List", t->title, t->due >= 0 ? day_text(t->due) : std::string()};
	}
	if (const auto* m = std::get_if<model::Memo>(&r)) {
		return {"Memo Pad", m->title(), std::string()};
	}
	if (const auto* a = std::get_if<model::Address>(&r)) {
		std::string right;
		if (const auto phone = a->list_phone()) {
			right = std::format("{} {}", phone->first, phone->second);
		}
		return {"Address Book", a->list_name(by), right};
	}
	return {"", std::string(), std::string()};
}

// The text with each tab and newline as a space: a tab is the column break of the list.
std::string no_breaks(std::string s) {
	std::ranges::replace_if(s, [](char c) { return c == '\t' || c == '\n'; }, ' ');
	return s;
}

// What a button or the field asked for.
enum class Act {
	Find,
	Go,
};

// The state that the widgets of the dialog share, as the Rust Rc<Cell>s and closures do. show() declares it before
// the window, so it outlives the widgets that the window deletes.
struct Dialog {
	// What the last click or key asked for. The loop takes it.
	std::optional<Act> act;
	Fl_Window* win = nullptr;
	Fl_Widget* field = nullptr;
	Fl_Check_Button* whole = nullptr;
	Fl_Check_Button* match_case = nullptr;
	Fl_Button* find_b = nullptr;
	Fl_Button* go_b = nullptr;
	Fl_Hold_Browser* list = nullptr;
	Fl_Box* status = nullptr;
};

} // namespace

namespace detail {

ResultKey result_order(const runner::Record& r) {
	if (std::holds_alternative<model::Address>(r)) {
		return {0, 0, 0};
	}
	if (const auto* e = std::get_if<model::Event>(&r)) {
		// An untimed event sorts before the timed ones of its day.
		return {1, e->date, e->untimed ? -1 : e->start};
	}
	if (std::holds_alternative<model::Todo>(r)) {
		return {2, 0, 0};
	}
	if (std::holds_alternative<model::Memo>(r)) {
		return {3, 0, 0};
	}
	return {4, 0, 0};
}

bool has_whole_word(std::string_view subject, std::string_view word, bool match_case) {
	const std::string hay = match_case ? std::string(subject) : text::to_lowercase(subject);
	const std::string needle = match_case ? std::string(word) : text::to_lowercase(word);
	if (needle.empty()) {
		return false;
	}
	const bool needle_starts_word = word_char(text::decode_at(needle, 0).cp);
	const bool needle_ends_word = word_char(cp_before(needle, needle.size()));
	std::size_t from = 0;
	while (true) {
		const std::size_t at = hay.find(needle, from);
		if (at == std::string::npos) {
			return false;
		}
		const std::size_t end = at + needle.size();
		const bool before_word = at > 0 && word_char(cp_before(hay, at));
		const bool after_word = end < hay.size() && word_char(text::decode_at(hay, end).cp);
		// \b sits between a word character and another character, so it needs a word character at the edge.
		if (needle_starts_word != before_word && needle_ends_word != after_word) {
			return true;
		}
		from = at + text::decode_at(hay, at).len;
	}
}

} // namespace detail

std::vector<runner::Record> matches(const view::Ctx& ctx, std::string_view query, bool whole, bool match_case) {
	std::vector<runner::Record> out;
	for (runner::Record& r : ctx.store.find(query)) {
		if (!ctx.show_private && is_private(r)) {
			continue;
		}
		const std::string hay = searchable(r);
		if (match_case && hay.find(query) == std::string::npos) {
			continue;
		}
		if (whole && !detail::has_whole_word(hay, query, match_case)) {
			continue;
		}
		out.push_back(std::move(r));
	}
	return out;
}

std::optional<runner::Record> show(const view::Ctx& ctx) {
	const int w = 371;
	const int short_h = 106;
	const int tall_h = 245;
	// The column widths of the list. The browser keeps the pointer, so the array outlives the window.
	std::array<int, 4> widths{};
	Dialog dlg;
	auto win = std::make_unique<Fl_Double_Window>(w, short_h, "Find");
	dlg.win = win.get();
	auto* field = new Fl_Input(72, 8, 196, 21);
	dlg.field = field;
	auto* find_l = dialogs::key_label("Find &what:", 10, 10, 60, 21, FL_ALIGN_LEFT);
	find_l->callback([](Fl_Widget*, void* d) { static_cast<Dialog*>(d)->field->take_focus(); }, &dlg);
	dlg.whole = draw::check(10, 38, 200, 20, "&Match whole word only");
	dlg.match_case = draw::check(10, 60, 200, 20, "Match &case");
	dlg.find_b = new Fl_Button(286, 8, 75, 23, "&Find");
	dlg.find_b->deactivate();
	dlg.go_b = new Fl_Button(286, 38, 75, 23, "&Go To");
	dlg.go_b->deactivate();
	auto* cancel = new Fl_Button(286, 68, 75, 23, "Cancel");
	dlg.list = new browser::Keep<Fl_Hold_Browser>(10, 88, w - 20, 130);
	dlg.list->hide();
	dlg.status = new Fl_Box(10, 224, w - 20, 16);
	dlg.status->align(FL_ALIGN_LEFT | FL_ALIGN_INSIDE);
	dlg.status->hide();
	dlg.find_b->callback([](Fl_Widget*, void* d) { static_cast<Dialog*>(d)->act = Act::Find; }, &dlg);
	dlg.go_b->callback([](Fl_Widget*, void* d) { static_cast<Dialog*>(d)->act = Act::Go; }, &dlg);
	cancel->callback([](Fl_Widget*, void* d) { static_cast<Dialog*>(d)->win->hide(); }, &dlg);
	// Find stays gray until Find what has text; Enter in the field finds.
	field->when(FL_WHEN_CHANGED | FL_WHEN_ENTER_KEY_ALWAYS);
	field->callback(
	    [](Fl_Widget* w, void* d) {
		    auto* dl = static_cast<Dialog*>(d);
		    if (text::trim(static_cast<Fl_Input*>(w)->value()).empty()) {
			    dl->find_b->deactivate();
		    } else {
			    dl->find_b->activate();
		    }
		    const int key = Fl::event_key();
		    if (Fl::event() == FL_KEYDOWN && (key == FL_Enter || key == FL_KP_Enter)) {
			    dl->act = Act::Find;
		    }
	    },
	    &dlg);
	dlg.list->callback(
	    [](Fl_Widget* w, void* d) {
		    auto* dl = static_cast<Dialog*>(d);
		    if (static_cast<Fl_Hold_Browser*>(w)->value() > 0) {
			    dl->go_b->activate();
			    if (Fl::event_clicks() != 0) {
				    dl->act = Act::Go;
			    }
		    }
	    },
	    &dlg);
	win->end();
	mnemonics::ForgetOnExit forget_win(*win);
	// The name column is as wide as "Address Book", so the text columns line up.
	fl_font(theme::fonts().sans, 11);
	const int name_w = draw::text_w("Address Book") + 12;
	widths = {name_w, w - 20 - name_w - 130, 120, 0};
	dlg.list->column_widths(widths.data());
	dlg.list->column_char('\t');

	std::vector<runner::Record> found;
	dialogs::show_modal(win.get());
	field->take_focus();
	while (win->shown()) {
		Fl::wait();
		if (!dlg.act) {
			continue;
		}
		const Act a = *dlg.act;
		dlg.act.reset();
		if (a == Act::Find) {
			const std::string query(text::trim(field->value()));
			if (query.empty()) {
				continue;
			}
			// The first search adds the list and its status line, and the dialog grows to show them.
			// Fl_Browser hides Fl_Widget::visible() behind its line overload, so the list asks Fl_Widget::visible().
			if (!dlg.list->Fl_Widget::visible()) {
				win->resize(win->x(), win->y(), w, tall_h);
				dlg.list->show();
				dlg.status->show();
			}
			found = matches(ctx, query, dlg.whole->value() != 0, dlg.match_case->value() != 0);
			std::stable_sort(found.begin(), found.end(), [](const runner::Record& x, const runner::Record& y) {
				return detail::result_order(x) < detail::result_order(y);
			});
			dlg.list->clear();
			const bool by = address::by_company(ctx.settings);
			for (const runner::Record& r : found) {
				const Parts p = row_parts(r, by);
				// "@." stops the format codes of a column, so a title may start with "@".
				const std::string line = std::format("@.{}\t@.{}\t@r@.{}", p.app, no_breaks(p.text), p.right);
				dlg.list->add(line.c_str());
			}
			const std::size_t n = found.size();
			const std::string status = std::format("{} Item(s) found", n);
			dlg.status->copy_label(status.c_str());
			// The first row is selected, so Go To is on.
			if (n > 0) {
				dlg.list->select(1);
				dlg.go_b->activate();
			} else {
				dlg.go_b->deactivate();
			}
			win->redraw();
		} else {
			const int i = dlg.list->value();
			if (i > 0 && static_cast<std::size_t>(i) <= found.size()) {
				win->hide();
				return found[static_cast<std::size_t>(i - 1)];
			}
		}
	}
	return std::nullopt;
}

} // namespace palm::find
