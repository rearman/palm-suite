// The Address Book view, as AddressPanel.kt and desktop-rs/app/src/address.rs: the Address List with its category
// filter, Look up and the List by, Edit and New buttons on the left, and the card of the selected address on the right.
#pragma once

#include "palm/category.hpp"
#include "palm/draw.hpp"
#include "palm/model.hpp"
#include "palm/msg.hpp"
#include "palm/records.hpp"
#include "palm/settings.hpp"
#include "palm/text_edit.hpp"
#include "palm/view.hpp"

#include <array>
#include <cstddef>
#include <cstdint>
#include <functional>
#include <memory>
#include <optional>
#include <span>
#include <string>
#include <string_view>
#include <utility>
#include <vector>

class Fl_Button;
class Fl_Group;

namespace palm::address {

// One row of the Address List: the list name, and the number and its letter. The address pane of the Day view draws
// the same rows.
struct Row {
	std::string name;
	std::string number;
	std::string letter;
};

// The row of `a` in an address list.
Row row_of(const model::Address& a, bool by_company);

// The width of the widest "number L" of `rows`, which sets the number column.
int widest_number(std::span<const Row> rows);

// True when the Address List sorts by company (the setting "addressListBy" 1).
bool by_company(const settings::Settings& settings);

// The addresses the Address List shows: the category of the setting "addressCategory" (-1 is All), the private ones
// only when they are shown, in list order.
std::vector<model::Address> visible(const view::Ctx& ctx);

// The text lines of the card below the name. Empty fields are skipped.
std::vector<std::string> card_lines(const model::Address& a, const std::array<std::string, 4>& labels);

// One row of the Address List, laid out as Palm Desktop 3.1.1 does. The number column is as wide as the widest number
// of the list and ends 36 px from the right edge. The name gets the rest. A selected row is filled from the left edge
// to the end of the number.
void draw_row(const Row& r, bool selected, draw::Rect rect, int widest);

namespace detail {

// The table of the Address List and the card, subclasses of their FLTK widgets. Defined in address.cpp.
class AddressList;
class CardFrame;

// The phone slots of `a` that Dial offers: a number that is not blank, with a label other than E-mail.
std::vector<std::size_t> phone_slots(const model::Address& a);

// The digits and the plus sign of a phone number, for a tel: link.
std::string tel_digits(std::string_view number);

// The list name cut to `w` px. "Last, First" is cut part by part, so both parts keep their start.
std::string fit_name(std::string_view name, int w, const std::function<int(std::string_view)>& measure);

} // namespace detail

// The Address Book view. The view owns its widgets through its group; the copies of its pointers stay valid while the
// group lives.
class AddressView {
public:
	// Builds the view in (x, y, w, h); call it between begin() and end() of the view's group.
	AddressView(int x, int y, int w, int h, msg::Sender s, const settings::Settings& settings);

	// The split that holds the view; the view group makes it its resizable widget.
	Fl_Group* root = nullptr;

	records::RecordTable* table() const;
	text_edit::Input* look() const;

	// The list takes the focus when the view opens.
	void focus();

	// "3 of 12" for the status bar.
	std::string position() const;

	text_edit::EditState list_edit_state() const;

	// Reads the store and the settings again: the filter, the list and the card.
	void refresh(view::Ctx& ctx);

	void new_record(view::Ctx& ctx);
	void edit_selected(view::Ctx& ctx);

	// Shows the address `id`, with the filter on All when it hides the address, and selects it.
	void select(view::Ctx& ctx, std::int64_t id);

	// The title and the lines that Print prints: each row of the list, its name and its number.
	std::pair<std::string, std::vector<std::string>> print_lines(const view::Ctx& ctx) const;

	// The Address Book List By form: 0 last name first, 1 company first.
	void list_by(view::Ctx& ctx);

	void handle(const msg::AddressMsg& m, view::Ctx& ctx);

	// Cut, Copy, Paste, Delete and Select All of the selected addresses.
	void list_action(const records::ListAction& a, view::Ctx& ctx);

private:
	void refresh_combos(view::Ctx& ctx);
	void rebuild_list(view::Ctx& ctx);
	void show_card(view::Ctx& ctx);
	std::vector<std::size_t> selected_rows() const;
	std::vector<model::Address> selected() const;
	// The address of the lead row when it is selected, else the first selected address.
	std::optional<model::Address> lead_address() const;
	void delete_selected(view::Ctx& ctx);
	// Selects the first address whose list name starts with the Look up text, ignoring case.
	void find_by_name(view::Ctx& ctx);
	// Puts back the Look up text that last matched.
	void refuse_look(const std::string& typed);
	// A paste into the list gives copies of the kept addresses when the text is theirs.
	void paste_text(view::Ctx& ctx, const std::string& text);

	// Heap-allocated, so the callbacks of the widgets keep a valid pointer when the view moves.
	std::unique_ptr<msg::Sender> s_;
	std::optional<category::IdChoice> filter_;
	detail::AddressList* table_ = nullptr;
	// The row clicked or moved to last, shared with the table.
	std::shared_ptr<int> lead_;
	text_edit::Input* look_ = nullptr;
	Fl_Button* edit_b_ = nullptr;
	detail::CardFrame* card_ = nullptr;
	// The addresses of the list, in list order.
	std::vector<model::Address> shown_;
	// The address the user selected, kept over a rebuild.
	std::optional<std::int64_t> selected_id_;
	// The Look up text that last matched a name; a typed character that matches none is refused back to it.
	std::string look_ok_;
};

} // namespace palm::address
