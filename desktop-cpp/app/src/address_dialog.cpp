// The Edit Address dialog and the Custom Field Labels dialog, as desktop-rs/app/src/address_dialog.rs. The Rust
// closures of the buttons and the keys become static callbacks, and the key handler of the window is a subclass
// that overrides handle(int).
#include "palm/address_dialog.hpp"

#include "palm/category.hpp"
#include "palm/combo.hpp"
#include "palm/dialogs.hpp"
#include "palm/draw.hpp"
#include "palm/json.hpp"
#include "palm/mnemonics.hpp"
#include "palm/msg.hpp"
#include "palm/note_dialog.hpp"
#include "palm/store.hpp"
#include "palm/text.hpp"
#include "palm/text_edit.hpp"

#include <FL/Enumerations.H>
#include <FL/Fl.H>
#include <FL/Fl_Button.H>
#include <FL/Fl_Check_Button.H>
#include <FL/Fl_Double_Window.H>
#include <FL/Fl_Group.H>
#include <FL/Fl_Input.H>
#include <FL/Fl_Round_Button.H>
#include <FL/Fl_Tabs.H>
#include <FL/Fl_Text_Buffer.H>
#include <FL/Fl_Text_Display.H>
#include <FL/Fl_Window.H>

#include <nlohmann/json.hpp>

#include <algorithm>
#include <array>
#include <cctype>
#include <cstddef>
#include <memory>
#include <optional>
#include <span>
#include <string>
#include <string_view>
#include <utility>
#include <vector>

namespace palm::address_dialog {
namespace {

// The name of custom field `i` (from 0) when its label is blank or missing.
std::string custom_name(std::size_t i) {
	return "Custom " + std::to_string(i + 1);
}

// The elements of a setting that is a JSON array, or nothing when the text does not parse or is not an array.
std::optional<nlohmann::json::array_t> parse_array(std::string_view text) {
	auto v = json::parse_value(text);
	if (!v || !v->is_array()) {
		return std::nullopt;
	}
	return v->get<nlohmann::json::array_t>();
}

// The text of element `i` of a list of strings, or "" past its end.
const char* text_at(const std::vector<std::string>& v, std::size_t i) {
	return i < v.size() ? v[i].c_str() : "";
}

// A key of the dialog: Alt and `key` moves the focus to `field`, on its tab page `page` when it has one.
struct AltKey {
	char key;
	Fl_Group* page;
	Fl_Widget* field;
};

// The window of a dialog. Its keys: Alt and a letter moves the focus, and Enter outside a multi-line field presses
// OK. `tabs` is the tab row of the form, or nullptr.
class KeyWindow : public Fl_Double_Window {
public:
	KeyWindow(int w, int h, const char* title) : Fl_Double_Window(w, h, title) {}

	int handle(int e) override;

	std::vector<AltKey> keys;
	Fl_Tabs* tabs = nullptr;
	Fl_Button* ok = nullptr;
};

int KeyWindow::handle(int e) {
	// FLTK's own handler runs first, as fltk-rs does before the Rust closure. The closure takes only a shortcut.
	const int base = Fl_Double_Window::handle(e);
	if (e != FL_SHORTCUT) {
		return base;
	}
	const int key = Fl::event_key();
	if (key == FL_Enter || key == FL_KP_Enter) {
		ok->do_callback();
		return 1;
	}
	if (!(Fl::event_state() & FL_ALT) || key < 0x20 || key > 0x7e) {
		return base;
	}
	const char c = static_cast<char>(std::tolower(key));
	const auto it = std::find_if(keys.begin(), keys.end(), [c](const AltKey& k) { return k.key == c; });
	if (it == keys.end()) {
		return base;
	}
	if (tabs && it->page) {
		tabs->value(it->page);
		tabs->redraw();
	}
	it->field->take_focus();
	return 1;
}

// A field label at (x, y), with the Alt key of its text.
Fl_Button* label(std::string_view text, int x, int y, int w, int h, Fl_Align align) {
	return dialogs::key_label(text, x, y, w, h, align);
}

// A one-line field with its right-click menu.
text_edit::Input* field(int x, int y, int w, int h) {
	return new text_edit::Input(x, y, w, h);
}

// A multi-line field that wraps its lines, showing its own scroll bar at the right.
text_edit::Editor* area(int x, int y, int w, int h, Fl_Text_Buffer& buf) {
	auto* e = new text_edit::Editor(x, y, w, h);
	e->buffer(&buf);
	e->wrap_mode(Fl_Text_Display::WRAP_AT_BOUNDS, 0);
	e->scrollbar_align(FL_ALIGN_RIGHT);
	return e;
}

// What the buttons ask for.
enum class Act {
	Ok,
	New,
};

// The answer of the form: the action of the button pressed, and the window that Cancel closes.
struct Pending {
	Fl_Window* win = nullptr;
	std::optional<Act> act;
};

void ok_pressed(Fl_Widget*, void* data) {
	static_cast<Pending*>(data)->act = Act::Ok;
}

void new_pressed(Fl_Widget*, void* data) {
	static_cast<Pending*>(data)->act = Act::New;
}

void cancel_pressed(Fl_Widget*, void* data) {
	static_cast<Pending*>(data)->win->hide();
}

// The widgets of the form. The fields hold the working copy.
struct Form {
	text_edit::Input* last;
	text_edit::Input* first;
	text_edit::Input* title;
	text_edit::Input* company;
	std::vector<Fl_Round_Button*> show;
	std::vector<palm::combo::Choice*> labels;
	std::vector<text_edit::Input*> phones;
	Fl_Check_Button* default_box;
	Fl_Text_Buffer* address;
	text_edit::Input* city;
	text_edit::Input* state;
	text_edit::Input* zip;
	text_edit::Input* country;
	std::vector<text_edit::Input*> custom;
	Fl_Text_Buffer* note;
	palm::category::IdChoice category;
	Fl_Check_Button* private_box;

	void fill(const model::Address& a) {
		last->value(a.last_name.c_str());
		first->value(a.first_name.c_str());
		title->value(a.title.c_str());
		company->value(a.company.c_str());
		for (std::size_t i = 0; i < 5; ++i) {
			labels[i]->value(i < a.phone_labels.size() ? a.phone_labels[i] : static_cast<std::int32_t>(i));
			phones[i]->value(text_at(a.phones, i));
			show[i]->value(static_cast<std::int32_t>(i) == std::clamp(a.show_phone, 0, 4));
		}
		address->text(a.address.c_str());
		city->value(a.city.c_str());
		state->value(a.state.c_str());
		zip->value(a.zip.c_str());
		country->value(a.country.c_str());
		for (std::size_t i = 0; i < 4; ++i) {
			custom[i]->value(text_at(a.custom, i));
		}
		note->text(a.note.c_str());
		category.select(a.category);
		private_box->value(a.is_private);
	}

	// The five phone label choices of the form.
	std::array<std::int32_t, 5> label_choices() const {
		std::array<std::int32_t, 5> out{};
		for (std::size_t i = 0; i < 5; ++i) {
			out[i] = std::max(labels[i]->value(), 0);
		}
		return out;
	}

	// The form as a record. An old record keeps its id and uid.
	model::Address collect(const model::Address* orig) const {
		const auto t = [](const Fl_Input* i) { return std::string(text::trim(i->value())); };
		model::Address rec = orig ? *orig : model::Address{};
		rec.last_name = t(last);
		rec.first_name = t(first);
		rec.title = t(title);
		rec.company = t(company);
		const auto choices = label_choices();
		rec.phone_labels.assign(choices.begin(), choices.end());
		rec.phones.clear();
		for (const auto* p : phones) {
			rec.phones.push_back(t(p));
		}
		rec.address = text_edit::buffer_text(*address);
		rec.city = t(city);
		rec.state = t(state);
		rec.zip = t(zip);
		rec.country = t(country);
		rec.custom.clear();
		for (const auto* c : custom) {
			rec.custom.push_back(t(c));
		}
		rec.note = text_edit::buffer_text(*note);
		rec.show_phone = 0;
		for (std::size_t i = 0; i < show.size(); ++i) {
			if (show[i]->value()) {
				rec.show_phone = static_cast<std::int32_t>(i);
				break;
			}
		}
		rec.category = category.id();
		rec.is_private = private_box->value();
		return rec;
	}
};

} // namespace

std::array<std::string, 4> custom_labels(const settings::Settings& settings) {
	const auto saved = parse_array(settings.get("addressLabels", "[]"));
	std::array<std::string, 4> out;
	for (std::size_t i = 0; i < out.size(); ++i) {
		const std::string raw = saved ? json::at_str(*saved, i, "") : std::string();
		const std::string_view t = text::trim(raw);
		out[i] = t.empty() ? custom_name(i) : std::string(t);
	}
	return out;
}

std::array<std::int32_t, 5> default_labels(const settings::Settings& settings) {
	const auto saved = parse_array(settings.get("addressDefaultLabels", ""));
	std::array<std::int32_t, 5> out{0, 1, 2, 3, 4};
	if (!saved) {
		return out;
	}
	const auto last = static_cast<std::int32_t>(model::LABELS.size()) - 1;
	for (std::size_t i = 0; i < out.size(); ++i) {
		out[i] = std::clamp(json::at_i32(*saved, i, static_cast<std::int32_t>(i)), 0, last);
	}
	return out;
}

namespace detail {

std::string phone_label(std::string_view l) {
	const std::string_view text = l == "E-mail" ? "E-Mail" : l;
	return std::string(text) + ":";
}

} // namespace detail

void edit(view::Ctx& ctx, std::optional<model::Address> orig, std::int32_t new_category) {
	const auto blank = [&ctx](std::int32_t cat) {
		model::Address a;
		a.category = cat;
		const auto labels = default_labels(ctx.settings);
		a.phone_labels.assign(labels.begin(), labels.end());
		return a;
	};
	// The buffers and the note box are declared before the window, so the window is deleted first, and the
	// widgets that refer to the buffers go with it.
	Pending pending;
	Fl_Text_Buffer address_buf;
	std::optional<note_dialog::NoteBox> note_box;
	auto win = std::make_unique<KeyWindow>(448, 341, "Edit Address");
	pending.win = win.get();

	auto* tabs = new Fl_Tabs(9, 8, 326, 328);
	win->tabs = tabs;
	// The pages start below the tab row; (px, py) is the origin of the page layouts of AddressDialog.kt.
	constexpr int px = 11;
	constexpr int py = 31;
	const auto page = [](const char* name) {
		auto* g = new Fl_Group(9, 29, 326, 307);
		g->label(name);
		g->box(FL_UP_BOX);
		return g;
	};

	Fl_Group* name_page = page("&Name");
	// A labeled field of a page: the label at px + 14 and the field at px + 83, with its Alt key.
	const auto row = [kw = win.get()](std::string_view text, char key, int y, Fl_Group* pg, Fl_Align align) {
		label(text, px + 14, py + y, 66, 21, align);
		auto* f = field(px + 83, py + y, 221, 21);
		kw->keys.push_back(AltKey{key, pg, f});
		return f;
	};
	auto* last = row("&Last name:", 'l', 10, name_page, FL_ALIGN_LEFT);
	auto* first = row("&First name:", 'f', 33, name_page, FL_ALIGN_LEFT);
	auto* title = row("&Title:", 't', 55, name_page, FL_ALIGN_LEFT);
	auto* company = row("Compan&y:", 'y', 78, name_page, FL_ALIGN_LEFT);
	label("S&how in list:", px + 14, py + 107, 90, 21, FL_ALIGN_LEFT);
	std::vector<Fl_Round_Button*> show;
	std::vector<palm::combo::Choice*> labels;
	std::vector<text_edit::Input*> phones;
	for (const int y : {127, 151, 177, 203, 229}) {
		show.push_back(draw::radio(px + 60, py + y, 18, 21, ""));
		auto* c = new palm::combo::Choice(px + 83, py + y, 72, 21);
		for (const std::string_view l : model::LABELS) {
			c->add(detail::phone_label(l).c_str());
		}
		draw::style_menu(c);
		labels.push_back(c);
		phones.push_back(field(px + 158, py + y, 144, 21));
	}
	win->keys.push_back(AltKey{'h', name_page, show.front()});
	auto* default_box = draw::check(px + 60, py + 260, 130, 18, "Sa&ve as default");
	name_page->end();

	Fl_Group* address_page = page("&Address");
	label("A&ddress:", px + 14, py + 10, 66, 21, FL_ALIGN_RIGHT);
	auto* address_e = area(px + 83, py + 10, 221, 88, address_buf);
	win->keys.push_back(AltKey{'d', address_page, address_e});
	auto* city = row("C&ity:", 'i', 101, address_page, FL_ALIGN_RIGHT);
	auto* state = row("&State:", 's', 122, address_page, FL_ALIGN_RIGHT);
	auto* zip = row("&Zip:", 'z', 143, address_page, FL_ALIGN_RIGHT);
	auto* country = row("Co&untry:", 'u', 164, address_page, FL_ALIGN_RIGHT);
	const auto names = custom_labels(ctx.settings);
	std::vector<text_edit::Input*> custom;
	constexpr std::array<int, 4> custom_y{198, 219, 241, 262};
	for (std::size_t i = 0; i < custom_y.size(); ++i) {
		label(draw::escape_label(names[i] + ":"), px + 14, py + custom_y[i], 66, 21, FL_ALIGN_RIGHT);
		custom.push_back(field(px + 84, py + custom_y[i], 221, 21));
	}
	address_page->end();

	// Alt+O is OK, so the Note tab has no Alt key. The toolbar above the note has the Cut, Copy, Paste and Undo
	// of the Note Editor; the note box has its vertical scroll bar.
	Fl_Group* note_page = page("Note");
	note_box.emplace(px + 10, 30, 24, draw::Rect{px + 12, 60, 288, 250});
	note_page->end();
	tabs->end();

	auto* ok = new Fl_Button(342, 27, 105, 24, "&OK");
	ok->callback(ok_pressed, &pending);
	auto* cancel = new Fl_Button(342, 57, 105, 24, "&Cancel");
	cancel->callback(cancel_pressed, &pending);
	auto* neu = new Fl_Button(342, 87, 105, 24, "Ne&w");
	neu->callback(new_pressed, &pending);
	auto* menu = new palm::combo::Choice(342, 289, 105, 22);
	draw::style_menu(menu);
	std::vector<std::pair<std::string, std::int32_t>> items{{"Unfiled", 0}};
	for (const auto& c : ctx.store.address_categories) {
		items.emplace_back(c.name, c.id);
	}
	palm::category::IdChoice category(menu, std::nullopt, ctx.s, nullptr);
	category.set_items(items, false, 0);
	auto* private_box = draw::check(342, 317, 105, 21, "&Private");
	win->end();
	mnemonics::ForgetOnExit forget_win(*win);
	win->ok = ok;

	Form form{last, first, title, company, show,   labels,         phones,   default_box, &address_buf,
	          city, state, zip,   country, custom, &note_box->buf, category, private_box};
	form.fill(orig ? *orig : blank(new_category));

	dialogs::show_modal(win.get());
	// The Last name text is selected on open.
	last->take_focus();
	text_edit::run(text_edit::Field{static_cast<Fl_Input*>(last)}, text_edit::TextAction::SelectAll);
	while (win->shown()) {
		Fl::wait();
		note_box->sync();
		if (!pending.act) {
			continue;
		}
		const Act a = *pending.act;
		pending.act.reset();
		if (form.default_box->value()) {
			ctx.set("addressDefaultLabels", json::dump(nlohmann::json(form.label_choices())));
		}
		model::Address rec = form.collect(orig ? &*orig : nullptr);
		if (orig || !rec.is_empty()) {
			ctx.store.save_address(rec);
		}
		if (a == Act::Ok) {
			win->hide();
			continue;
		}
		// New clears the form for another new address; the category stays.
		orig.reset();
		const std::int32_t cat = form.category.id();
		form.fill(blank(cat));
		form.last->take_focus();
	}
}

bool custom_field_labels(view::Ctx& ctx) {
	const auto labels = custom_labels(ctx.settings);
	// The Palm Desktop 3.1.1 dialog is 194 x 184 px: labels at the left, fields to their right, OK and Cancel at the
	// bottom.
	auto win = std::make_unique<KeyWindow>(194, 184, "Custom Field Labels");
	std::vector<text_edit::Input*> fields;
	for (std::size_t i = 0; i < labels.size(); ++i) {
		const int y = 18 + 28 * static_cast<int>(i);
		label("Label " + std::to_string(i + 1) + ":", 14, y, 50, 21, FL_ALIGN_LEFT);
		auto* f = field(68, y, 106, 21);
		f->value(labels[i].c_str());
		fields.push_back(f);
	}
	// The answer of the dialog: whether OK was pressed, and the window that the buttons close.
	struct Answer {
		Fl_Window* win;
		bool saved;
	} answer{win.get(), false};
	auto* ok = new Fl_Button(42, 142, 50, 23, "OK");
	ok->callback(
	    [](Fl_Widget*, void* data) {
		    auto* a = static_cast<Answer*>(data);
		    a->saved = true;
		    a->win->hide();
	    },
	    &answer);
	auto* cancel = new Fl_Button(106, 142, 50, 23, "Cancel");
	cancel->callback([](Fl_Widget*, void* data) { static_cast<Answer*>(data)->win->hide(); }, &answer);
	win->end();
	win->ok = ok;
	dialogs::run_modal(win.get());
	if (!answer.saved) {
		return false;
	}
	std::vector<std::string> values;
	for (std::size_t i = 0; i < fields.size(); ++i) {
		const std::string_view t = text::trim(fields[i]->value());
		values.push_back(t.empty() ? custom_name(i) : std::string(t));
	}
	ctx.set("addressLabels", json::dump(nlohmann::json(values)));
	return true;
}

} // namespace palm::address_dialog
