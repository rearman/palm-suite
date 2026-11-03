// The edit actions of a text field, its right-click menu (Undo; Cut, Copy, Paste, Delete; Select All, as the Windows
// edit menu of Palm Desktop, TextMenu.kt) and the enabled state of the Edit items, as desktop-rs/app/src/text_edit.rs.
// A field is a multi-line Fl_Text_Editor or a one-line Fl_Input.
#pragma once

#include <FL/Fl_Input.H>
#include <FL/Fl_Text_Editor.H>

#include <string>
#include <variant>

class Fl_Text_Buffer;

namespace palm::text_edit {

// Which Edit items apply to the focus owner, as MainFrame.updateEditState grays them.
struct EditState {
	bool undo = false;
	bool cut = false;
	bool copy = false;
	bool paste = false;
	// The Rust field `delete` is a C++ keyword, so it takes the module name.
	bool delete_text_edit = false;
	bool select_all = false;

	bool operator==(const EditState&) const = default;
};

// The text commands of the Edit menu and the right-click menu.
enum class TextAction {
	Undo,
	Cut,
	Copy,
	Paste,
	Delete,
	SelectAll,
};

// True when the clipboard holds text.
bool clipboard_has_text();

// The text of a buffer. Fl_Text_Buffer::text gives a malloc'd copy, which this frees.
std::string buffer_text(Fl_Text_Buffer& buf);

// A text field that takes the Edit commands: a multi-line editor or a one-line input. The widget belongs to its
// group, so the variant holds the pointer.
using Field = std::variant<Fl_Text_Editor*, Fl_Input*>;

// Which Edit items apply to the field.
EditState state(const Field& field);

// Runs one Edit command on the field.
void run(const Field& field, TextAction action);

// Shows the right-click menu of the field at the pointer and runs the chosen item.
void popup(const Field& field);

// A multi-line field with its right-click menu. Rust attaches the menu to a widget with a handle closure; C++ has
// no closure on a widget, so the menu is a subclass. FLTK's own handler runs first, then the menu, as in Rust.
class Editor : public Fl_Text_Editor {
public:
	using Fl_Text_Editor::Fl_Text_Editor;
	int handle(int event) override;
};

// A one-line field with its right-click menu, as Editor.
class Input : public Fl_Input {
public:
	using Fl_Input::Fl_Input;
	int handle(int event) override;
};

} // namespace palm::text_edit
