// The messages of the event loop, as desktop-rs/app/src/msg.rs. Every widget callback, timer and worker thread sends
// one; the main loop handles them one at a time, so no callback borrows the app state.
//
// A Rust enum with data is a std::variant of one struct per variant, named as the variant. The inner enum of a
// variant that wraps one (Msg::Memo(MemoMsg)) lives in its own namespace (palm::msg::memo), because the names repeat
// across the views (New, Picked, Filter, &c). Rust `match` arms become std::visit or std::get_if.
#pragma once

#include "palm/client.hpp"

#include <array>
#include <cstdint>
#include <expected>
#include <memory>
#include <optional>
#include <string>
#include <string_view>
#include <utility>
#include <variant>
#include <vector>

namespace palm::msg {

// Builds the visitor of a std::visit from one lambda per alternative.
template <class... Ts> struct Overload : Ts... {
	using Ts::operator()...;
};
template <class... Ts> Overload(Ts...) -> Overload<Ts...>;

// The four views of the main window.
enum class View {
	Date,
	Address,
	Todo,
	Memo,
};

// All four views, in the order of the Rust ALL.
inline constexpr std::array<View, 4> ALL = {View::Date, View::Address, View::Todo, View::Memo};

// The name in the settings ("view", "startView").
std::string_view key(View view);

// The view of a settings name, or no value for an unknown name.
std::optional<View> from_key(std::string_view name);

// The Edit menu and toolbar commands. They go to the focused text field, or to the current view.
enum class EditCmd {
	Undo,
	Cut,
	Copy,
	Paste,
	Delete,
	// Shift+Delete: delete with no question.
	DeleteNoConfirm,
	SelectAll,
	New,
};

// The events of the Memo Pad.
namespace memo {

// The selection of the list moved.
struct Picked {};

// A printable key in the list: a new memo that holds it.
struct TypeNew {
	char32_t ch;
};

struct New {};
struct Apply {};
struct Filter {};

// A right click on the list.
struct ListMenu {};

// The clipboard text of a Paste into the list.
struct PasteText {
	std::string text;
};

} // namespace memo

using MemoMsg =
    std::variant<memo::Picked, memo::TypeNew, memo::New, memo::Apply, memo::Filter, memo::ListMenu, memo::PasteText>;

// The events of the Address Book.
namespace address {

// The selection of the list moved.
struct Picked {};

struct Filter {};

// The text of Look up changed.
struct Look {};

// A double click on a row, or the Edit button.
struct Edit {};

struct New {};

// A right click on the list.
struct ListMenu {};

// Edit > Dial... (Ctrl+I): the Dial dialog of the lead address.
struct Dial {};

// The clipboard text of a Paste into the list.
struct PasteText {
	std::string text;
};

} // namespace address

using AddressMsg = std::variant<address::Picked, address::Filter, address::Look, address::Edit, address::New,
                                address::ListMenu, address::Dial, address::PasteText>;

// The events of the To Do List.
namespace todo {

// The selection of the table moved.
struct Picked {};

struct Filter {};

// A printable key in the table: a new item that holds it.
struct TypeNew {
	char32_t ch;
};

struct New {};
struct Apply {};

// The Show Options dialog.
struct Show {};

// A pick in the Due box of the details.
struct DuePick {};

// The note button of the details.
struct Note {};

// A left click on a cell: row, column, and true for the second click of a double click.
struct Cell {
	int row;
	int column;
	bool second_click;
};

// A right click on the table.
struct ListMenu {};

// The clipboard text of a Paste into the table.
struct PasteText {
	std::string text;
};

} // namespace todo

using TodoMsg = std::variant<todo::Picked, todo::Filter, todo::TypeNew, todo::New, todo::Apply, todo::Show,
                             todo::DuePick, todo::Note, todo::Cell, todo::ListMenu, todo::PasteText>;

// The Day, Week and Month views of the Date Book.
enum class DateMode {
	Day,
	Week,
	Month,
};

// The name in the setting "dateMode".
std::string_view key(DateMode mode);

// What a mouse event of a Date Book view was.
namespace mouse {

struct Press {};
struct Drag {};
struct Release {};
struct Move {};

// The wheel turned by this many lines.
struct Wheel {
	int lines;
};

} // namespace mouse

using MouseKind = std::variant<mouse::Press, mouse::Drag, mouse::Release, mouse::Move, mouse::Wheel>;

// A mouse event on a Date Book view, in window coordinates.
struct Mouse {
	MouseKind kind;
	int x;
	int y;
	bool right;
	// The second click of a double click.
	bool double_click;
};

// An event of the field that types a new event or a title into a box.
enum class FieldEv {
	Enter,
	Escape,
	// The field lost the focus.
	Lost,
	Double,
};

// The events of the Date Book.
namespace date {

struct Mode {
	DateMode mode;
};

// Today, or a double click on a tab.
struct Today {};

struct New {};

// The Edit button, or Edit Event of the Edit menu.
struct Edit {};

// A double click on the Day title: pick a day.
struct TitleDouble {};

// The year arrows of the right pane: back (-1) or forward (1).
struct Year {
	int delta;
};

// A month button of the right pane, 1 to 12.
struct Month {
	std::uint32_t month;
};

// A click on the calendar of the right pane.
struct Calendar {
	Mouse mouse;
};

// The Address (true) or To Do segment of the right pane.
struct Pane {
	bool address;
};

struct Category {};
struct Look {};

// The mouse on the list of the right pane.
struct PaneList {
	Mouse mouse;
};

// The mouse on the untimed rows (true) or the hours of the Day view.
struct Day {
	bool untimed;
	Mouse mouse;
};

struct Scrolled {};

struct Field {
	FieldEv event;
};

// The arrows of the Week and Month views: back (-1) or forward (1).
struct Step {
	int delta;
};

struct GoTo {};

struct WeekHeader {
	Mouse mouse;
};

struct WeekGrid {
	Mouse mouse;
};

struct MonthGrid {
	Mouse mouse;
};

// A printable key in the Month view: a new event that holds it.
struct MonthKey {
	char32_t ch;
};

} // namespace date

using DateMsg = std::variant<date::Mode, date::Today, date::New, date::Edit, date::TitleDouble, date::Year, date::Month,
                             date::Calendar, date::Pane, date::Category, date::Look, date::PaneList, date::Day,
                             date::Scrolled, date::Field, date::Step, date::GoTo, date::WeekHeader, date::WeekGrid,
                             date::MonthGrid, date::MonthKey>;

// One struct per variant of Msg. A variant with an inner enum wraps it in `event`, as Msg::Memo(MemoMsg).
struct Memo {
	MemoMsg event;
};

struct Date {
	DateMsg event;
};

// Shows an item in the To Do List: a double click on the To Do pane of the Day view.
struct ShowTodo {
	std::int64_t id;
};

struct Address {
	AddressMsg event;
};

struct Todo {
	TodoMsg event;
};

struct Edit {
	EditCmd cmd;
};

// The focus or a selection moved: gray the Edit items that do not apply.
struct EditState {};

// Edit Categories of an app: 0 To Do, 1 Memo Pad, 2 Address Book.
struct EditCategories {
	int app;
};

// Tools > Categories in the current view.
struct Categories {};

struct ListBy {};

// Tools > Custom Field Labels of the Address Book.
struct CustomLabels {};

// Tools > Purge in the current view.
struct Purge {};

struct Import {};
struct Export {};
struct Print {};
struct Find {};
struct Options {};

// A divider was released: save the width of its detail pane.
struct SplitMoved {
	std::string_view key;
	int width;
};

struct ShowView {
	View view;
};

struct SetShowPrivate {
	bool on;
};

struct SaveAll {};
struct SyncNow {};

// A timer or a local change asks for a sync.
struct SyncRequest {};

// The window came to the front: sync when the last sync is more than a minute old.
struct SyncIfStale {};

// A local write: sync 1 s after the last one.
struct Changed {};

// The server answer of the round on the wire, or the error text of the call.
struct SyncAnswer {
	std::expected<client::SyncResult, std::string> answer;
};

struct SyncOptions {};
struct SyncDialogSetUp {};
struct SyncDialogSyncNow {};
struct SyncDialogTurnOff {};
struct SyncDialogClose {};

// The key of a set up, None for a wrong passphrase, or the error text.
struct SetUpDone {
	std::expected<std::optional<std::vector<std::uint8_t>>, std::string> result;
	std::string server;
	std::string token;
};

struct About {};
struct Exit {};

using Msg =
    std::variant<Memo, Date, ShowTodo, Address, Todo, Edit, EditState, EditCategories, Categories, ListBy, CustomLabels,
                 Purge, Import, Export, Print, Find, Options, SplitMoved, ShowView, SetShowPrivate, SaveAll, SyncNow,
                 SyncRequest, SyncIfStale, Changed, SyncAnswer, SyncOptions, SyncDialogSetUp, SyncDialogSyncNow,
                 SyncDialogTurnOff, SyncDialogClose, SetUpDone, About, Exit>;

// The queue of one channel: the Sender pushes, the Receiver pops, and the mutex guards both. Defined in msg.cpp.
struct Queue;

class Receiver;

// Sends a Msg to the main loop from any thread. A copy shares the queue, so a copy costs one pointer.
class Sender {
public:
	// Pushes the message under the mutex, then calls Fl::awake() to wake the main loop.
	void send(Msg msg) const;

private:
	friend std::pair<Sender, Receiver> channel();

	explicit Sender(std::shared_ptr<Queue> queue);

	std::shared_ptr<Queue> queue;
};

// Takes the messages of a channel in the order they were sent, on the main thread.
class Receiver {
public:
	// The oldest message, or no value when the queue is empty. Never blocks.
	std::optional<Msg> recv();

private:
	friend std::pair<Sender, Receiver> channel();

	explicit Receiver(std::shared_ptr<Queue> queue);

	std::shared_ptr<Queue> queue;
};

// A new channel, as fltk-rs app::channel::<Msg>(). Fl::lock() must run once in main, before any thread starts that
// sends (main.cpp: call Fl::lock() first, then start the worker threads).
std::pair<Sender, Receiver> channel();

} // namespace palm::msg
