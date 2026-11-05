// The category drop-down lists and the Edit Categories dialog, as desktop-rs/app/src/category.rs.
#include "palm/category.hpp"

#include "palm/browser.hpp"
#include "palm/dialogs.hpp"
#include "palm/draw.hpp"
#include "palm/mnemonics.hpp"
#include "palm/store.hpp"

#include <FL/Fl.H>
#include <FL/Fl_Button.H>
#include <FL/Fl_Choice.H>
#include <FL/Fl_Double_Window.H>
#include <FL/Fl_Multi_Browser.H>
#include <FL/Fl_Round_Button.H>
#include <FL/Fl_Window.H>

#include <algorithm>
#include <cstddef>
#include <iterator>
#include <memory>
#include <string>
#include <utility>
#include <vector>

namespace palm::category {

namespace {

// Replaces each `from` of `s` with `to`.
std::string replace_all(std::string_view s, char from, std::string_view to) {
	std::string out;
	out.reserve(s.size());
	for (const char c : s) {
		if (c == from) {
			out.append(to);
		} else {
			out.push_back(c);
		}
	}
	return out;
}

// What the Edit Categories buttons ask for.
enum class Action {
	New,
	Rename,
	Delete,
};

// The records of `rows` whose category is `id` go to `del`. The ids are taken first, as `del` changes the rows.
template <typename Row, typename Del> void delete_in(const std::vector<Row>& rows, std::int32_t id, Del del) {
	std::vector<std::int64_t> ids;
	for (const auto& r : rows) {
		if (r.category == id) {
			ids.push_back(r.id);
		}
	}
	for (const auto i : ids) {
		del(i);
	}
}

void delete_records_in(palm::store::DesktopStore& store, std::int32_t app, std::int32_t id) {
	switch (app) {
	case 1:
		delete_in(store.memos, id, [&](std::int64_t i) { store.delete_memo(i); });
		break;
	case 2:
		delete_in(store.addresses, id, [&](std::int64_t i) { store.delete_address(i); });
		break;
	default:
		delete_in(store.todos, id, [&](std::int64_t i) { store.delete_todo(i); });
		break;
	}
}

// The state of the Delete dialog: the window, its "Delete all items" radio, and the answer on OK.
struct AskDelete {
	Fl_Window* win;
	Fl_Round_Button* del;
	std::optional<bool> answer;
};

void ask_delete_ok(Fl_Widget*, void* data) {
	auto* a = static_cast<AskDelete*>(data);
	a->answer = a->del->value() != 0;
	a->win->hide();
}

void ask_delete_cancel(Fl_Widget*, void* data) {
	static_cast<Fl_Window*>(data)->hide();
}

// The Delete dialog of Palm Desktop 3.1.1, titled "Delete <name> Category": two radios, Move all items to 'Unfiled'
// (the default) and Delete all items, then OK and Cancel. true deletes the items, false moves them to Unfiled, and no
// value is Cancel or the close box.
std::optional<bool> ask_delete(const std::string& name) {
	std::unique_ptr<Fl_Double_Window> win(new Fl_Double_Window(221, 124));
	win->copy_label(("Delete " + name + " Category").c_str());
	Fl_Round_Button* mv = draw::radio(28, 22, 180, 20, "&Move all items to 'Unfiled'");
	mv->value(1);
	Fl_Round_Button* del = draw::radio(28, 54, 180, 20, "&Delete all items");
	AskDelete state{win.get(), del, std::nullopt};
	Fl_Button* ok = new Fl_Button(35, 84, 75, 23, "OK");
	Fl_Button* cancel = new Fl_Button(116, 84, 75, 23, "Cancel");
	ok->callback(ask_delete_ok, &state);
	cancel->callback(ask_delete_cancel, win.get());
	win->end();
	mnemonics::ForgetOnExit forget_win(*win);
	palm::dialogs::show_modal(win.get());
	while (win->shown()) {
		Fl::wait();
	}
	return state.answer;
}

// Selects or deselects the line `row` of the list.
void set_row(Fl_Multi_Browser* list, int row, bool on) {
	list->select(row, on ? 1 : 0);
}

// Shows `row` (1-based, 0 for none) as the focus item of the list. With `selected` it is also selected (blue);
// without, only the focus rectangle marks it. The other items are deselected first, and that moves the focus, so
// `row` is selected last.
void show_row(Fl_Multi_Browser* list, int row, bool selected) {
	for (int r = 1; r <= list->size(); ++r) {
		set_row(list, r, false);
	}
	if (row >= 1 && row <= list->size()) {
		set_row(list, row, true);
		if (!selected) {
			set_row(list, row, false);
		}
	}
}

// The button callbacks set the action they stand for, which the dialog loop takes.
template <Action A> void pick(Fl_Widget*, void* data) {
	*static_cast<std::optional<Action>*>(data) = A;
}

} // namespace

std::string menu_label(std::string_view s) {
	std::string out = replace_all(s, '\\', "\\\\");
	out = replace_all(out, '/', "\\/");
	out = replace_all(out, '&', "&&");
	return replace_all(out, '@', "@@");
}

// The state shared by the copies of an IdChoice.
struct IdChoiceState {
	std::vector<std::int32_t> ids;
	// The index of the last item that was selected, to which an Edit Categories pick goes back.
	int last = 0;
	msg::Sender sender;
	std::optional<std::int32_t> edit_app;
	msg::Msg (*changed)() = nullptr;
};

namespace {

// The callback of the drop-down list: Edit Categories sends its message and keeps the selection; any other pick
// sends `changed`.
void choice_changed(Fl_Widget* w, void* data) {
	auto* st = static_cast<IdChoiceState*>(data);
	auto* ch = static_cast<Fl_Choice*>(w);
	const int i = ch->value();
	if (i >= 0 && static_cast<std::size_t>(i) >= st->ids.size()) {
		ch->value(st->last);
		if (st->edit_app) {
			st->sender.send(msg::EditCategories{*st->edit_app});
		}
		return;
	}
	st->last = i;
	if (st->changed) {
		st->sender.send(st->changed());
	}
}

} // namespace

IdChoice::IdChoice(Fl_Choice* choice, std::optional<std::int32_t> edit_app, msg::Sender s, msg::Msg (*changed)())
    : choice(choice), state_(std::make_shared<IdChoiceState>(IdChoiceState{{}, 0, std::move(s), edit_app, changed})) {
	choice->callback(choice_changed, state_.get());
}

void IdChoice::set_items(const std::vector<std::pair<std::string, std::int32_t>>& items, bool with_edit,
                         std::int32_t select_id) {
	choice->clear();
	for (const auto& item : items) {
		choice->add(menu_label(item.first).c_str());
	}
	if (with_edit) {
		choice->add(EDIT_CATEGORIES);
	}
	state_->ids.clear();
	for (const auto& item : items) {
		state_->ids.push_back(item.second);
	}
	select(select_id);
}

void IdChoice::select(std::int32_t id) {
	const auto& ids = state_->ids;
	const auto it = std::find(ids.begin(), ids.end(), id);
	const int i = it == ids.end() ? 0 : static_cast<int>(it - ids.begin());
	choice->value(i);
	state_->last = i;
}

std::int32_t IdChoice::id() const {
	const auto& ids = state_->ids;
	const int v = choice->value();
	const std::size_t idx = v < 0 ? 0 : static_cast<std::size_t>(v);
	if (idx < ids.size()) {
		return ids[idx];
	}
	return ids.empty() ? UNFILED : ids.front();
}

void edit_categories(palm::store::DesktopStore& store, std::int32_t app) {
	const char* title = "Edit To Do Categories";
	if (app == 1) {
		title = "Edit Memo Categories";
	} else if (app == 2) {
		title = "Edit Address Categories";
	}
	std::unique_ptr<Fl_Double_Window> win(new Fl_Double_Window(209, 158, title));
	Fl_Multi_Browser* list = new palm::browser::Keep<Fl_Multi_Browser>(10, 10, 110, 138);
	// The browser's default font is Helvetica, the dialog font.
	list->textsize(11);
	std::optional<Action> action;
	Fl_Button* ok = new Fl_Button(124, 10, 75, 23, "OK");
	std::vector<Fl_Button*> buttons;
	const struct {
		const char* label;
		Fl_Callback* cb;
	} commands[] = {
	    {"&New...", pick<Action::New>},
	    {"&Delete...", pick<Action::Delete>},
	    {"&Rename...", pick<Action::Rename>},
	};
	for (std::size_t i = 0; i < std::size(commands); ++i) {
		Fl_Button* b = new Fl_Button(124, static_cast<int>(39 + 29 * i), 75, 23, commands[i].label);
		b->callback(commands[i].cb, &action);
		buttons.push_back(b);
	}
	ok->callback([](Fl_Widget*, void* w) { static_cast<Fl_Window*>(w)->hide(); }, win.get());
	win->end();
	mnemonics::ForgetOnExit forget_win(*win);

	// The rows follow the store's sorted list; `select_id` picks the row of a category id.
	auto reload = [app](Fl_Multi_Browser* browser, const palm::store::DesktopStore& st,
	                    std::optional<std::int32_t> select_id) {
		const int keep = browser->value();
		browser->clear();
		const auto& cats = st.categories_for(app);
		for (const auto& c : cats) {
			browser->add(replace_all(c.name, '@', "@@").c_str());
		}
		int row = 1;
		const auto pos = select_id ? std::find_if(cats.begin(), cats.end(),
		                                          [&](const model::Category& c) { return c.id == *select_id; })
		                           : cats.end();
		if (pos != cats.end()) {
			row = static_cast<int>(pos - cats.begin()) + 1;
		} else if (keep >= 1 && keep <= browser->size()) {
			row = keep;
		}
		show_row(browser, row, true);
	};
	reload(list, store, std::nullopt);
	palm::dialogs::show_modal(win.get());
	// On open the first item has the focus rectangle only, not the selection, as in Palm Desktop.
	show_row(list, 1, false);
	list->take_focus();
	int shown = list->value();
	while (win->shown()) {
		Fl::wait();
		// A key or a click that moves the focus item selects it, as the list of Palm Desktop does.
		const int focus = list->value();
		if (focus >= 1 && focus != shown) {
			show_row(list, focus, true);
		}
		shown = list->value();
		const bool has = list->value() >= 1;
		for (std::size_t i = 1; i < buttons.size(); ++i) {
			if (has) {
				buttons[i]->activate();
			} else {
				buttons[i]->deactivate();
			}
		}
		if (!action) {
			continue;
		}
		const Action a = *action;
		action.reset();
		const auto& cats = store.categories_for(app);
		const auto idx = static_cast<std::size_t>(std::max(list->value() - 1, 0));
		std::optional<model::Category> selected;
		if (has && idx < cats.size()) {
			selected = cats[idx];
		}
		switch (a) {
		case Action::New: {
			if (auto name = palm::dialogs::prompt_titled("New Category", "Enter a new category name:", "")) {
				const model::Category c = store.add_category(*name, app);
				reload(list, store, c.id);
			}
			break;
		}
		case Action::Rename: {
			if (!selected) {
				break;
			}
			if (auto name =
			        palm::dialogs::prompt_titled("Rename Category", "Enter a new category name:", selected->name)) {
				store.rename_category(app, selected->id, *name);
				reload(list, store, selected->id);
			}
			break;
		}
		case Action::Delete: {
			if (!selected) {
				break;
			}
			const std::optional<bool> del = ask_delete(selected->name);
			if (!del) {
				break;
			}
			// Without the records, delete_category moves the items to Unfiled.
			if (*del) {
				delete_records_in(store, app, selected->id);
			}
			store.delete_category(app, selected->id);
			reload(list, store, std::nullopt);
			break;
		}
		}
	}
}

} // namespace palm::category
