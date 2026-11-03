// The Address Book view, as desktop-rs/app/src/address.rs. The Rust closures of the list, the card, the buttons and the
// Dial dialog become subclasses that override handle() and draw(), and static callbacks that take the Sender through
// their data pointer.
#include "palm/address.hpp"

#include "palm/address_dialog.hpp"
#include "palm/combo.hpp"
#include "palm/dialogs.hpp"
#include "palm/split.hpp"
#include "palm/text.hpp"
#include "palm/theme.hpp"

#include <FL/Enumerations.H>
#include <FL/Fl.H>
#include <FL/Fl_Box.H>
#include <FL/Fl_Button.H>
#include <FL/Fl_Double_Window.H>
#include <FL/Fl_Group.H>
#include <FL/Fl_Table_Row.H>
#include <FL/Fl_Window.H>
#include <FL/filename.H>
#include <FL/fl_draw.H>

#include <algorithm>
#include <cstddef>
#include <cstdint>
#include <initializer_list>
#include <string>
#include <type_traits>
#include <variant>

namespace palm::address {

namespace {

// The height of a row of the Address List, as Palm Desktop 3.1.1.
constexpr int ROW_H = 16;
// The gap after the number column of the Address List, measured in Palm Desktop.
constexpr int RIGHT = 36;
// The most of a narrow row that the number column takes.
constexpr double MAX_SHARE = 0.55;

} // namespace

namespace detail {

// The lines of `s`, as Rust's str::lines: a final newline ends the last line, and a carriage return before a newline
// is dropped.
std::vector<std::string_view> lines(std::string_view s) {
	auto parts = text::split(s, '\n');
	if (!parts.empty() && parts.back().empty()) {
		parts.pop_back();
	}
	for (auto& p : parts) {
		if (!p.empty() && p.back() == '\r') {
			p.remove_suffix(1);
		}
	}
	return parts;
}

// The parts that are not blank, joined by `sep`.
std::string join_filled(std::initializer_list<std::string_view> parts, std::string_view sep) {
	std::string out;
	for (std::string_view p : parts) {
		if (model::is_blank(p)) {
			continue;
		}
		if (!out.empty()) {
			out += sep;
		}
		out += p;
	}
	return out;
}

// Draws `s` at (x, y) with the current font. FLTK takes a C string.
void draw_text(std::string_view s, int x, int y) {
	fl_draw(std::string(s).c_str(), x, y);
}

// The card of an address: the name and the category on the first line, then the filled fields.
struct Card {
	model::Address address;
	std::string category;
	std::array<std::string, 4> labels;
};

// The card of the selected address, a sunken frame on the panel gray. Its draw replaces the default one, as the Rust
// draw closure does.
class CardFrame : public Fl_Box {
public:
	using Fl_Box::Fl_Box;

	std::optional<Card> card;

	void draw() override {
		Fl_Box::draw();
		if (!card) {
			return;
		}
		draw::Clip clip(x() + 2, y() + 2, w() - 4, h() - 4);
		draw_card(*card, x(), y(), w());
	}

private:
	static void draw_card(const Card& c, int x, int y, int w) {
		const auto& fonts = theme::fonts();
		const auto& a = c.address;
		constexpr int pad = 6;
		std::string name = join_filled({a.first_name, a.last_name}, " ");
		if (model::is_blank(name)) {
			name = a.company;
		}
		fl_color(FL_BLACK);
		fl_font(fonts.sans_bold, 16);
		const int name_descent = fl_descent();
		int base = y + pad + fl_height() - name_descent;
		draw_text(name, x + pad, base);
		// The category sits at the right end of the name line.
		fl_font(fonts.sans, 13);
		draw_text(c.category, x + w - pad - draw::text_w(c.category), base);
		base += name_descent + 4 + fl_height() - fl_descent();
		for (const auto& line : card_lines(a, c.labels)) {
			draw_text(line, x + pad, base);
			base += fl_height();
		}
	}
};

// The reply of the buttons: the message that the Sender takes from each callback.
void look_changed(Fl_Widget*, void* data) {
	static_cast<msg::Sender*>(data)->send(msg::Address{msg::address::Look{}});
}

void list_by_pressed(Fl_Widget*, void* data) {
	static_cast<msg::Sender*>(data)->send(msg::ListBy{});
}

void edit_pressed(Fl_Widget*, void* data) {
	static_cast<msg::Sender*>(data)->send(msg::Address{msg::address::Edit{}});
}

void new_pressed(Fl_Widget*, void* data) {
	static_cast<msg::Sender*>(data)->send(msg::Address{msg::address::New{}});
}

msg::Msg filter_msg() {
	return msg::Address{msg::address::Filter{}};
}

// The messages of the Address List. A double click edits the row.
const records::ListMsgs LIST_MSGS{
    .picked = []() -> msg::Msg { return msg::Address{msg::address::Picked{}}; },
    .menu = []() -> msg::Msg { return msg::Address{msg::address::ListMenu{}}; },
    .paste = [](std::string t) -> msg::Msg { return msg::Address{msg::address::PasteText{std::move(t)}}; },
    .typed = std::nullopt,
    .open = []() -> msg::Msg { return msg::Address{msg::address::Edit{}}; },
    .cell = std::nullopt,
};

// The table of the Address List: a record list over the Rows of the list. The list events run after FLTK's own
// handler, as the Rust handle closure does.
class AddressList : public records::RecordTable {
public:
	AddressList(int x, int y, int w, int h, std::shared_ptr<int> lead, msg::Sender s)
	    : records::RecordTable(x, y, w, h), lead_(std::move(lead)), s_(std::move(s)) {}

	// The rows, in list order.
	std::vector<Row> list_rows;

	int handle(int event) override {
		const int handled = Fl_Table_Row::handle(event);
		if (records::list_event(*this, event, lead_, s_, LIST_MSGS)) {
			return 1;
		}
		return handled;
	}

	void draw_cell(TableContext context, int row, int /*col*/, int x, int y, int w, int h) override {
		if (context == CONTEXT_STARTPAGE) {
			records::fit_column(*this, ROW_H);
			widest_ = widest_number(list_rows);
		} else if (context == CONTEXT_CELL) {
			if (row < 0 || static_cast<std::size_t>(row) >= list_rows.size()) {
				return;
			}
			draw::Clip clip(x, y, w, h);
			draw_row(list_rows[static_cast<std::size_t>(row)], row_selected(row) != 0, draw::Rect{x, y, w, h}, widest_);
		}
	}

private:
	std::shared_ptr<int> lead_;
	msg::Sender s_;
	// The width of the widest number, measured at the start of each draw.
	int widest_ = 0;
};

// The phone label text of `a` at phone slot `i`, "Other" when the label is missing.
std::string_view phone_label_of(const model::Address& a, std::size_t i) {
	if (i < a.phone_labels.size()) {
		const std::int32_t l = a.phone_labels[i];
		if (l >= 0 && static_cast<std::size_t>(l) < model::LABELS.size()) {
			return model::LABELS[static_cast<std::size_t>(l)];
		}
	}
	return "Other";
}

// The Dial dialog's state: the button sets `go` and hides the window.
struct DialState {
	Fl_Window* win = nullptr;
	bool go = false;
};

void dial_pressed(Fl_Widget*, void* data) {
	auto* st = static_cast<DialState*>(data);
	st->go = true;
	st->win->hide();
}

void cancel_pressed(Fl_Widget*, void* data) {
	static_cast<Fl_Window*>(data)->hide();
}

// The Dial dialog: the phones of `a` that have a number, the list number first. Palm Desktop dialed through a modem;
// a tel: link goes to the calling app of the system.
void dial_address(const model::Address& a) {
	const auto slots = phone_slots(a);
	if (slots.empty()) {
		return;
	}
	Fl_Double_Window win(280, 84, "Dial");
	auto* pick = new combo::Choice(14, 14, 252, 22);
	for (const std::size_t i : slots) {
		const std::string label = category::menu_label(std::string(phone_label_of(a, i)) + ": " + a.phones[i]);
		pick->add(label.c_str());
	}
	draw::style_menu(pick);
	std::int32_t start = 0;
	for (std::size_t k = 0; k < slots.size(); ++k) {
		if (static_cast<std::int32_t>(slots[k]) == a.show_phone) {
			start = static_cast<std::int32_t>(k);
			break;
		}
	}
	pick->value(start);
	DialState st;
	st.win = &win;
	auto* dial_b = new Fl_Button(64, 48, 72, 23, "&Dial");
	auto* cancel = new Fl_Button(144, 48, 72, 23, "Cancel");
	dial_b->callback(dial_pressed, &st);
	cancel->callback(cancel_pressed, &win);
	win.end();
	dialogs::run_modal(&win);
	if (!st.go) {
		return;
	}
	const std::size_t idx = slots[static_cast<std::size_t>(std::max(pick->value(), 0))];
	const std::string uri = "tel:" + tel_digits(a.phones[idx]);
	char msg_buf[255] = {};
	// fl_open_uri gives 1 when the link opened, as fltk-rs reads it.
	if (fl_open_uri(uri.c_str(), msg_buf, sizeof msg_buf) != 1) {
		dialogs::message("Dial", dialogs::Icon::Warning, "This computer has no app that makes phone calls.");
	}
}

std::vector<std::size_t> phone_slots(const model::Address& a) {
	std::vector<std::size_t> out;
	for (std::size_t i = 0; i < 5; ++i) {
		const bool has = i < a.phones.size() && !model::is_blank(a.phones[i]);
		const bool email = i < a.phone_labels.size() && a.phone_labels[i] == model::EMAIL;
		if (has && !email) {
			out.push_back(i);
		}
	}
	return out;
}

std::string tel_digits(std::string_view number) {
	std::string out;
	for (const char c : number) {
		if ((c >= '0' && c <= '9') || c == '+') {
			out += c;
		}
	}
	return out;
}

std::string fit_name(std::string_view name, int w, const std::function<int(std::string_view)>& measure) {
	if (measure(name) <= w) {
		return std::string(name);
	}
	const auto pos = name.find(", ");
	if (pos == std::string_view::npos) {
		return draw::fit(name, w, measure);
	}
	const std::string_view first = name.substr(0, pos);
	const std::string_view last = name.substr(pos + 2);
	const int each = (w - measure(", ")) / 2;
	return draw::fit(first, each, measure) + ", " + draw::fit(last, each, measure);
}

} // namespace detail

Row row_of(const model::Address& a, bool by_company) {
	Row r;
	r.name = a.list_name(by_company);
	if (const auto p = a.list_phone()) {
		r.number = p->first;
		r.letter = std::string(1, p->second);
	}
	return r;
}

int widest_number(std::span<const Row> rows) {
	fl_font(theme::fonts().sans, 11);
	int widest = 0;
	for (const auto& x : rows) {
		if (!x.number.empty()) {
			widest = std::max(widest, draw::text_w(x.number + " " + x.letter));
		}
	}
	return widest;
}

bool by_company(const settings::Settings& settings) {
	return settings.get_int("addressListBy", 0) == 1;
}

std::vector<model::Address> visible(const view::Ctx& ctx) {
	const std::int32_t cat = ctx.settings.get_int("addressCategory", category::ALL);
	std::vector<model::Address> shown;
	for (const auto& a : ctx.store.addresses) {
		if ((ctx.show_private || !a.is_private) && (cat == category::ALL || a.category == cat)) {
			shown.push_back(a);
		}
	}
	return model::address_order(shown, by_company(ctx.settings));
}

std::vector<std::string> card_lines(const model::Address& a, const std::array<std::string, 4>& labels) {
	std::vector<std::string> out;
	const auto add = [&out](std::string_view text) {
		if (!model::is_blank(text)) {
			out.emplace_back(text);
		}
	};
	add(a.title);
	add(a.company);
	for (std::size_t i = 0; i < 5; ++i) {
		const std::string number = i < a.phones.size() ? a.phones[i] : std::string();
		if (!model::is_blank(number)) {
			add(std::string(detail::phone_label_of(a, i)) + ":  " + number);
		}
	}
	for (const auto line : detail::lines(a.address)) {
		add(line);
	}
	const std::string head = detail::join_filled({a.city, a.state}, ", ");
	add(detail::join_filled({head, a.zip}, " "));
	add(a.country);
	for (std::size_t i = 0; i < labels.size(); ++i) {
		const std::string value = i < a.custom.size() ? a.custom[i] : std::string();
		if (!model::is_blank(value)) {
			add(labels[i] + ": " + value);
		}
	}
	for (const auto line : detail::lines(a.note)) {
		add(line);
	}
	return out;
}

void draw_row(const Row& r, bool selected, draw::Rect rect, int widest) {
	const auto [x, y, w, h] = rect;
	fl_font(theme::fonts().sans, 11);
	const int room = std::max(w - RIGHT, 0);
	const int number_w = std::min(widest, static_cast<int>(static_cast<double>(room) * MAX_SHARE));
	const int number_x = room - number_w;
	fl_rectf(x, y, w, h, FL_WHITE);
	const int base = y + (h - fl_height()) / 2 + fl_height() - fl_descent();
	const int letter_w = r.letter.empty() ? 0 : draw::text_w(" " + r.letter);
	std::string number;
	if (r.number.empty()) {
		number.clear();
	} else if (r.letter.empty()) {
		number = draw::fit(r.number, number_w, draw::text_w);
	} else {
		number = draw::fit(r.number, number_w - letter_w, draw::text_w) + " " + r.letter;
	}
	// The number ends at the room's edge, the RIGHT gap is left after it.
	const int number_left = room - draw::text_w(number);
	if (selected) {
		// The fill runs 4 px past the end of the number.
		const int end = number.empty() ? number_x : room + 4;
		fl_rectf(x, y, end, h, theme::SELECT);
	}
	fl_color(selected ? FL_WHITE : FL_BLACK);
	// The name stops 6 px short of the number column.
	detail::draw_text(detail::fit_name(r.name, number_x - 9, draw::text_w), x + 3, base);
	detail::draw_text(number, x + number_left, base);
}

AddressView::AddressView(int x, int y, int w, int h, msg::Sender s, const settings::Settings& settings)
    : s_(std::make_unique<msg::Sender>(std::move(s))), lead_(std::make_shared<int>(-1)) {
	const auto& fonts = theme::fonts();
	// 302 px: the AddressCard width of Palm Desktop.
	const auto sp = split::split(x, y, w, h, settings.get_int("addressSplit", 416), "addressSplit", *s_);

	// The list side: the title, the filter, the list, Look up, and the List by, Edit and New buttons.
	sp.left->begin();
	const int lx = sp.left->x();
	const int ly = sp.left->y();
	const int lw = sp.left->w();
	const int lh = sp.left->h();
	auto* title = new Fl_Box(lx + 4, ly + 4, 120, 22, "Address List");
	title->box(FL_DOWN_BOX);
	title->labelfont(fonts.arial_bold);
	title->labelsize(14);
	title->align(FL_ALIGN_CENTER | FL_ALIGN_INSIDE);
	auto* filter_menu = new combo::Choice(lx + lw - 114, ly + 4, 110, 22);
	draw::style_menu(filter_menu);
	filter_.emplace(filter_menu, 2, *s_, detail::filter_msg);

	const int by = ly + lh - 29;
	const int look_y = by - 5 - 21;
	table_ = new detail::AddressList(lx + 4, ly + 30, lw - 8, look_y - 4 - (ly + 30), lead_, *s_);
	table_->type(Fl_Table_Row::SELECT_MULTI);
	table_->rows(0);
	table_->cols(1);
	table_->row_height_all(ROW_H);
	table_->col_header(0);
	table_->row_header(0);
	table_->box(FL_DOWN_BOX);
	table_->color(FL_WHITE);
	table_->end();

	auto* look_label = new Fl_Box(lx + 4, look_y, 46, 21, "Look up:");
	look_label->align(FL_ALIGN_LEFT | FL_ALIGN_INSIDE);
	look_ = new text_edit::Input(lx + 52, look_y, lw - 56, 21);
	look_->when(FL_WHEN_CHANGED);
	look_->callback(detail::look_changed, s_.get());

	auto* list_by_b = new Fl_Button(lx + lw - 247, by, 75, 23, "&List by...");
	list_by_b->callback(detail::list_by_pressed, s_.get());
	edit_b_ = new Fl_Button(lx + lw - 166, by, 75, 23, "Ed&it...");
	edit_b_->callback(detail::edit_pressed, s_.get());
	edit_b_->deactivate();
	auto* new_b = new Fl_Button(lx + lw - 85, by, 75, 23, "&New...");
	new_b->callback(detail::new_pressed, s_.get());
	// The title and Look up's label keep their place, the filter and the buttons keep to the right and the bottom, Look
	// up widens and the list grows. The resizable is 1 px wide, as the Rust one is: FLTK widens a child whose span
	// holds the resizable's left edge and moves one to the right of it.
	auto* grow = new Fl_Box(lx + 128, ly + 30, 1, look_y - 4 - (ly + 30), nullptr);
	sp.left->resizable(grow);
	sp.left->end();

	// The card side: the card of the selected address on the panel gray, inside a sunken edge.
	sp.right->begin();
	const int rx = sp.right->x();
	const int ry = sp.right->y();
	const int rw = sp.right->w();
	const int rh = sp.right->h();
	card_ = new detail::CardFrame(rx + 8, ry + 8, rw - 16, rh - 12, nullptr);
	card_->box(FL_DOWN_BOX);
	card_->color(theme::PANEL);
	sp.right->resizable(card_);
	sp.right->end();

	root = sp.outer;
}

records::RecordTable* AddressView::table() const {
	return table_;
}

text_edit::Input* AddressView::look() const {
	return look_;
}

void AddressView::focus() {
	table_->take_focus();
}

std::string AddressView::position() const {
	const auto rows = selected_rows();
	if (rows.empty()) {
		return std::string();
	}
	return std::to_string(rows.front() + 1) + " of " + std::to_string(shown_.size());
}

text_edit::EditState AddressView::list_edit_state() const {
	return records::list_state(!selected_rows().empty(), !shown_.empty());
}

void AddressView::refresh(view::Ctx& ctx) {
	refresh_combos(ctx);
	rebuild_list(ctx);
}

void AddressView::refresh_combos(view::Ctx& ctx) {
	const std::int32_t cur = ctx.settings.get_int("addressCategory", category::ALL);
	std::vector<std::pair<std::string, std::int32_t>> items{{"All", category::ALL}};
	for (const auto& c : ctx.store.address_categories) {
		items.emplace_back(c.name, c.id);
	}
	items.emplace_back("Unfiled", category::UNFILED);
	const bool known = std::any_of(items.begin(), items.end(), [cur](const auto& it) { return it.second == cur; });
	if (cur != category::ALL && !known) {
		ctx.set("addressCategory", std::to_string(category::ALL));
	}
	filter_->set_items(items, true, cur);
}

// Fills the list with the visible addresses and selects the one of `selected_id_`. As in Palm Desktop, the card always
// shows a record: the first one when none is selected.
void AddressView::rebuild_list(view::Ctx& ctx) {
	const bool by_co = by_company(ctx.settings);
	shown_ = visible(ctx);
	table_->list_rows.clear();
	for (const auto& a : shown_) {
		table_->list_rows.push_back(row_of(a, by_co));
	}
	table_->rows(static_cast<int>(shown_.size()));
	// New rows take FLTK's default height.
	table_->row_height_all(ROW_H);
	std::optional<std::size_t> row;
	if (selected_id_) {
		for (std::size_t i = 0; i < shown_.size(); ++i) {
			if (shown_[i].id == *selected_id_) {
				row = i;
				break;
			}
		}
	}
	if (!row && !shown_.empty()) {
		row = 0;
	}
	selected_id_ = row ? std::optional<std::int64_t>(shown_[*row].id) : std::nullopt;
	records::pick_row(*table_, *lead_, row);
	show_card(ctx);
}

void AddressView::show_card(view::Ctx& ctx) {
	const auto lead = lead_address();
	if (lead) {
		edit_b_->activate();
	} else {
		edit_b_->deactivate();
	}
	if (lead) {
		card_->card =
		    detail::Card{*lead, ctx.store.category_name(lead->category), address_dialog::custom_labels(ctx.settings)};
	} else {
		card_->card = std::nullopt;
	}
	card_->redraw();
}

void AddressView::new_record(view::Ctx& ctx) {
	// A new address goes in the category the list shows, else Unfiled.
	const std::int32_t shown = ctx.settings.get_int("addressCategory", category::ALL);
	address_dialog::edit(ctx, std::nullopt, std::max(shown, category::UNFILED));
}

void AddressView::edit_selected(view::Ctx& ctx) {
	if (const auto a = lead_address()) {
		address_dialog::edit(ctx, a, category::UNFILED);
	}
}

std::vector<std::size_t> AddressView::selected_rows() const {
	return records::selected_rows(*table_, shown_.size());
}

std::vector<model::Address> AddressView::selected() const {
	std::vector<model::Address> out;
	for (const std::size_t i : selected_rows()) {
		out.push_back(shown_[i]);
	}
	return out;
}

std::optional<model::Address> AddressView::lead_address() const {
	const int l = *lead_;
	if (l >= 0 && static_cast<std::size_t>(l) < shown_.size() && table_->row_selected(l) != 0) {
		return shown_[static_cast<std::size_t>(l)];
	}
	const auto rows = selected_rows();
	if (!rows.empty()) {
		return shown_[rows.front()];
	}
	return std::nullopt;
}

void AddressView::delete_selected(view::Ctx& ctx) {
	const auto picked = selected();
	if (picked.empty() || !dialogs::confirm_delete_titled(ctx.settings, ctx.no_confirm, "Delete Address Book Items",
	                                                      "Delete the selected Address Book item(s)?")) {
		return;
	}
	selected_id_ = std::nullopt;
	for (const auto& a : picked) {
		ctx.store.delete_address(a.id);
	}
}

void AddressView::select(view::Ctx& ctx, std::int64_t id) {
	const auto& all = ctx.store.addresses;
	const auto it = std::find_if(all.begin(), all.end(), [id](const model::Address& a) { return a.id == id; });
	if (it == all.end()) {
		return;
	}
	const model::Address a = *it;
	const std::int32_t cat = ctx.settings.get_int("addressCategory", category::ALL);
	if (cat != category::ALL && a.category != cat) {
		ctx.set("addressCategory", std::to_string(category::ALL));
	}
	selected_id_ = a.id;
	refresh(ctx);
	table_->take_focus();
}

std::pair<std::string, std::vector<std::string>> AddressView::print_lines(const view::Ctx& ctx) const {
	const bool by_co = by_company(ctx.settings);
	std::vector<std::string> out;
	for (const auto& a : shown_) {
		std::string text = a.list_name(by_co);
		if (const auto p = a.list_phone()) {
			text += "  " + p->first + " " + std::string(1, p->second);
		}
		out.push_back(text);
	}
	return {"Address List", out};
}

void AddressView::list_by(view::Ctx& ctx) {
	const std::size_t now = by_company(ctx.settings) ? 1 : 0;
	const auto order = dialogs::list_by("Field Order", {"&Last name, first name", "&Company, last name"}, now);
	if (order) {
		ctx.set("addressListBy", std::to_string(*order));
		refresh(ctx);
	}
}

void AddressView::handle(const msg::AddressMsg& m, view::Ctx& ctx) {
	std::visit(
	    [&](const auto& ev) {
		    using T = std::decay_t<decltype(ev)>;
		    if constexpr (std::is_same_v<T, msg::address::Picked>) {
			    const auto a = lead_address();
			    selected_id_ = a ? std::optional<std::int64_t>(a->id) : std::nullopt;
			    show_card(ctx);
		    } else if constexpr (std::is_same_v<T, msg::address::Filter>) {
			    ctx.set("addressCategory", std::to_string(filter_->id()));
			    refresh(ctx);
		    } else if constexpr (std::is_same_v<T, msg::address::Look>) {
			    find_by_name(ctx);
		    } else if constexpr (std::is_same_v<T, msg::address::Edit>) {
			    edit_selected(ctx);
		    } else if constexpr (std::is_same_v<T, msg::address::New>) {
			    new_record(ctx);
		    } else if constexpr (std::is_same_v<T, msg::address::ListMenu>) {
			    const auto lead = lead_address();
			    const bool dial = lead.has_value() && !detail::phone_slots(*lead).empty();
			    const std::array<std::pair<const char*, bool>, 2> extra{
			        {{"&Edit...", lead.has_value()}, {"&Dial...", dial}}};
			    const auto act = records::list_menu(list_edit_state(), extra);
			    if (!act) {
				    return;
			    }
			    if (const auto* e = std::get_if<records::action::Extra>(&*act)) {
				    if (e->index == 0) {
					    edit_selected(ctx);
				    } else if (lead) {
					    detail::dial_address(*lead);
				    }
			    } else {
				    list_action(*act, ctx);
			    }
		    } else if constexpr (std::is_same_v<T, msg::address::Dial>) {
			    const auto a = lead_address();
			    if (a && !detail::phone_slots(*a).empty()) {
				    detail::dial_address(*a);
			    }
		    } else if constexpr (std::is_same_v<T, msg::address::PasteText>) {
			    paste_text(ctx, ev.text);
		    }
	    },
	    m);
}

// Selects the first address whose list name starts with the Look up text, ignoring case. A text that matches no name is
// refused: the field goes back to the last text that matched.
void AddressView::find_by_name(view::Ctx& ctx) {
	const std::string typed = look_->value();
	const std::string key = text::to_lowercase(typed);
	if (key.empty()) {
		look_ok_.clear();
		return;
	}
	const bool by_co = by_company(ctx.settings);
	const auto it = std::find_if(shown_.begin(), shown_.end(), [&](const model::Address& a) {
		return text::to_lowercase(a.list_name(by_co)).starts_with(key);
	});
	if (it == shown_.end()) {
		refuse_look(typed);
		return;
	}
	const auto i = static_cast<std::size_t>(it - shown_.begin());
	look_ok_ = typed;
	records::pick_row(*table_, *lead_, i);
	selected_id_ = shown_[i].id;
	show_card(ctx);
}

// Puts back the Look up text that last matched. The cursor returns to the start of the refused text, so a typed
// character leaves the field as it was.
void AddressView::refuse_look(const std::string& typed) {
	const int grew = std::max(0, static_cast<int>(typed.size()) - static_cast<int>(look_ok_.size()));
	const int at = std::max(0, look_->insert_position());
	look_->value(look_ok_.c_str());
	look_->insert_position(std::max(0, at - grew));
}

// Cut, Copy, Paste, Delete and Select All of the selected addresses. The text of an address is its list name, then the
// lines of its card.
void AddressView::list_action(const records::ListAction& a, view::Ctx& ctx) {
	if (std::holds_alternative<records::action::Copy>(a) || std::holds_alternative<records::action::Cut>(a)) {
		const auto picked = selected();
		if (picked.empty()) {
			return;
		}
		const bool by_co = by_company(ctx.settings);
		const auto labels = address_dialog::custom_labels(ctx.settings);
		std::string text;
		for (std::size_t i = 0; i < picked.size(); ++i) {
			std::string block = picked[i].list_name(by_co);
			for (const auto& line : card_lines(picked[i], labels)) {
				block += "\n" + line;
			}
			if (i > 0) {
				text += "\n";
			}
			text += block;
		}
		std::vector<runner::Record> recs(picked.begin(), picked.end());
		records::put("address", std::move(recs), text);
		if (std::holds_alternative<records::action::Cut>(a)) {
			selected_id_ = std::nullopt;
			for (const auto& x : picked) {
				ctx.store.delete_address(x.id);
			}
		}
	} else if (std::holds_alternative<records::action::Paste>(a)) {
		Fl::paste(*table_, 1);
	} else if (std::holds_alternative<records::action::Delete>(a)) {
		delete_selected(ctx);
	} else if (std::holds_alternative<records::action::SelectAll>(a)) {
		table_->select_all_rows(1);
		table_->redraw();
	}
}

// A paste into the list gives copies of the kept addresses when the text is theirs. Other text makes no address, as in
// Palm Desktop.
void AddressView::paste_text(view::Ctx& ctx, const std::string& text) {
	const auto recs = records::records_for("address", text);
	if (!recs) {
		return;
	}
	for (const auto& r : *recs) {
		if (const auto* a = std::get_if<model::Address>(&r)) {
			auto copy = a->dup_key();
			ctx.store.save_address(copy);
		}
	}
}

} // namespace palm::address
