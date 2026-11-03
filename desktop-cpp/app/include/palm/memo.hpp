// The Memo Pad view, as desktop-rs/app/src/memo.rs: the Memo List with its category filter on the left, and the memo
// editor with its category, private flag, and Apply button on the right.
#pragma once

#include "palm/category.hpp"
#include "palm/model.hpp"
#include "palm/msg.hpp"
#include "palm/records.hpp"
#include "palm/settings.hpp"
#include "palm/text_edit.hpp"
#include "palm/view.hpp"

#include <cstdint>
#include <memory>
#include <optional>
#include <string>
#include <utility>
#include <vector>

class Fl_Button;
class Fl_Check_Button;
class Fl_Group;
class Fl_Text_Buffer;
class Fl_Text_Editor;

namespace palm::memo {

// The Memo List: a record list of one column with a row for each memo. Defined in memo.cpp.
class MemoTable;

class MemoView {
public:
	// The split that holds the view; the view group makes it its resizable widget.
	Fl_Group* root = nullptr;

	// Builds the view in (x, y, w, h); call it between begin() and end() of the view's group. The split width and the
	// saved category filter come from `settings`. The view keeps its sender by pointer in its buttons, so it must not
	// move, and its window must outlive it.
	MemoView(int x, int y, int w, int h, msg::Sender s, const settings::Settings& settings);
	MemoView(const MemoView&) = delete;
	MemoView& operator=(const MemoView&) = delete;
	~MemoView();

	Fl_Text_Editor* editor() const;

	records::RecordTable* table() const;

	// The list takes the focus when the view opens, as in Palm Desktop.
	void focus();

	// "3 of 12" for the status bar.
	std::string position() const;

	text_edit::EditState list_edit_state() const;

	// Reads the store again: the lists and the combo boxes. Keeps the editor when it holds unsaved text.
	void refresh(view::Ctx& ctx);

	void new_record(view::Ctx& ctx);

	// Shows the memo `id`, with the filter on All when it hides the memo, and selects it.
	void select(view::Ctx& ctx, std::int64_t id);

	// The title and the lines that Print prints: the memo in the editor, else the titles of the list.
	std::pair<std::string, std::vector<std::string>> print_lines() const;

	void list_by(view::Ctx& ctx);

	void handle(msg::MemoMsg event, view::Ctx& ctx);

	// Cut, Copy, Paste, Delete and Select All of the selected memos.
	void list_action(records::ListAction a, view::Ctx& ctx);

private:
	// Sends the messages of the buttons. The buttons keep a pointer to it.
	msg::Sender s_;
	// The lead row, shared with the list events.
	std::shared_ptr<int> lead_;
	// The category filter: an id of Memo Pad categories, ALL, or UNFILED.
	std::int32_t filter_id_;

	std::optional<category::IdChoice> filter_;
	MemoTable* table_ = nullptr;
	Fl_Text_Editor* editor_ = nullptr;
	// The view owns the buffer that the editor shows.
	std::unique_ptr<Fl_Text_Buffer> buf_;
	std::optional<category::IdChoice> category_;
	Fl_Check_Button* private_box_ = nullptr;
	Fl_Button* apply_ = nullptr;

	// The memos of the list, in list order.
	std::vector<model::Memo> shown_;
	// The memo in the editor. Edits go to it on Apply or on a change of selection.
	std::optional<model::Memo> current_;

	std::vector<std::size_t> selected_rows() const;
	std::vector<model::Memo> selected() const;
	// The memo of the lead row when it is selected, else the first selected memo.
	std::optional<model::Memo> lead_memo() const;
	void reflow(view::Ctx& ctx, std::optional<model::Memo> old, bool dirty);
	std::vector<model::Memo> visible(const view::Ctx& ctx) const;
	// Fills the list. Keeps the selected memos that are still shown, and selects the memo `want` only when nothing is
	// left selected. Returns the lead memo, which the editor shows.
	std::optional<model::Memo> rebuild_list(const view::Ctx& ctx, std::optional<std::int64_t> want);
	// Selects the memo with `id` alone. With no `id`, selects the first memo, as the view opens on it; with no memo,
	// nothing.
	void pick(std::optional<std::int64_t> id);
	void refresh_combos(const view::Ctx& ctx);
	void load_editor(std::optional<model::Memo> m);
	bool has_changes() const;
	void save_if_changed(view::Ctx& ctx);
	// Writes the editor to the current memo. A memo with blank text is deleted, as on the Palm.
	void apply_edits(view::Ctx& ctx);
	// A new memo with `text`, in the category the filter shows.
	model::Memo new_memo(const std::string& text) const;
	void delete_selected(view::Ctx& ctx);
	// A paste into the list: the kept memos as copies when the text is theirs, else one new memo.
	void paste_text(view::Ctx& ctx, const std::string& text);
};

} // namespace palm::memo
