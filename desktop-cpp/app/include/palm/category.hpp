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

// The Edit Categories dialog of one app (0 To Do, 1 Memo Pad, 2 Address Book). It writes the store as the user
// works; the app reads the store again when the dialog closes.
void edit_categories(palm::store::DesktopStore& store, std::int32_t app);

} // namespace palm::category
