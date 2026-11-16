// The category drop-down lists, with "Edit Categories..." at the end as in Palm Desktop, and the Edit Categories
// dialog of Dialogs.kt, as desktop-rs/app/src/category.rs.
#pragma once

#include "palm/msg.hpp"

#include <cstdint>
#include <memory>
#include <optional>
#include <string>
#include <string_view>
#include <utility>
#include <vector>

class Fl_Choice;

namespace palm::store {
class DesktopStore;
}

namespace palm::category {

// The last item of every category drop-down list, as in Palm Desktop.
inline constexpr const char* EDIT_CATEGORIES = "Edit Categories...";
inline constexpr std::int32_t ALL = -1;
inline constexpr std::int32_t UNFILED = 0;

// A text as one FLTK menu label: "/" would start a submenu, "&" an Alt key and "@" a symbol.
std::string menu_label(std::string_view s);

// The state shared by the copies of an IdChoice. Defined in category.cpp.
struct IdChoiceState;

// A drop-down list of (name, id) items. With `edit`, its last item opens Edit Categories for `app` and keeps the
// selection. The copies share one state, as the Rust clones share one Rc.
class IdChoice {
public:
	// `changed` is sent when the user picks an item other than Edit Categories.
	IdChoice(Fl_Choice* choice, std::optional<std::int32_t> edit_app, msg::Sender s, msg::Msg (*changed)());

	// Replaces the items, then selects `select`, or the first item when no item has it.
	void set_items(const std::vector<std::pair<std::string, std::int32_t>>& items, bool with_edit, std::int32_t select);

	void select(std::int32_t id);

	// The id of the selected item, or the first id when nothing is selected.
	std::int32_t id() const;

	// The widget is owned by its group, so the list keeps a raw pointer to it.
	Fl_Choice* choice;

private:
	std::shared_ptr<IdChoiceState> state_;
};

// One merge of Edit Categories: the records of `from` moved to `into`, and `from` was deleted.
struct Merge {
	std::int32_t from;
	std::int32_t into;
};

// The category that a filter showing `id` shows after `merges`, taken in their order: a merged category gives the
// one it went into, and any other id stays.
std::int32_t after_merges(std::int32_t id, const std::vector<Merge>& merges);

// The Edit Categories dialog of one app (0 To Do, 1 Memo Pad, 2 Address Book). It writes the store as the user
// works; the app reads the store again when the dialog closes. As on the Palm, a rename to the name of another
// category (ignoring case, Unfiled too) merges the two, and New with a name in use adds nothing. Gives the merges
// made, in their order, so the caller can point each filter that showed a merged category at the kept one.
std::vector<Merge> edit_categories(palm::store::DesktopStore& store, std::int32_t app);

// Merges the category `from` of `app` into `into`: its records move to `into`, each saved so it syncs, then `from`
// is deleted, which sends its sync tombstone. Unfiled is never merged away.
void merge_category(palm::store::DesktopStore& store, std::int32_t app, std::int32_t from, std::int32_t into);

} // namespace palm::category
