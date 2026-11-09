// The private free functions and constants of the Date view, as desktop-rs/app/src/date.rs: the layout of the Day grid,
// the right pane, the calendar, and the tabs. date.cpp and date_view.cpp include it. The structs and the methods of the
// view are declared in palm/date.hpp.
#pragma once

#include "palm/date.hpp"
#include "palm/date_shared.hpp"
#include "palm/msg.hpp"
#include "palm/records.hpp"

#include <cstddef>
#include <cstdint>
#include <optional>
#include <span>
#include <string>
#include <string_view>
#include <tuple>
#include <utility>
#include <vector>

namespace palm::date::detail {

// The height of one untimed row: a header of one row is 18 px with the 4 px selection bar, as in 3.1.1.
inline constexpr std::int32_t UNTIMED_H = 14;

// Pixels per half hour: Palm Desktop draws 38 px per hour.
inline constexpr std::int32_t HALF = 19;

// The width of the right pane, 234 px as in Palm Desktop 3.1.1.
inline constexpr std::int32_t PANE_W = 234;

// The width of the tab strip on the right edge.
inline constexpr std::int32_t TABS_W = 24;

// The height of the title box of the Day view.
inline constexpr std::int32_t TITLE_H = 18;

// The gap between the title box and the grid.
inline constexpr std::int32_t TITLE_GAP = 4;

// The size of the Today, Edit... and New... buttons.
inline constexpr std::int32_t BTN_W = 70;

// The height of the Today, Edit... and New... buttons.
inline constexpr std::int32_t BTN_H = 20;

// The height of one row of month buttons.
inline constexpr std::int32_t MONTH_H = 15;

// Sets the range of `bar` for a view of `view` px over content of `total` px, keeping the offset in range.
void set_bar_range(Fl_Scrollbar& bar, std::int32_t view, std::int32_t total);

// Pixel row on the grid of a time in minutes after midnight.
std::int32_t y_of(std::int32_t min);

// Minutes after midnight at a pixel row of the grid.
std::int32_t min_at(std::int32_t y);

// The start of an event dropped at pixel row `y`: the half hour under the pointer. It lasts one hour.
std::int32_t drop_min(std::int32_t y);

// Rounds `min` to the nearest half hour, halves rounding up.
std::int32_t round_half(std::int32_t min);

// The box of a one-hour event from `min` in a grid `width` px wide.
date_shared::Rect hour_box(std::int32_t min, std::int32_t width);

// The outline of a dragged event from `start` to `end` minutes, at least a half hour tall.
date_shared::Rect outline_box(std::int32_t start, std::int32_t end, std::int32_t width);

// The bounds of each event for a width of `width` px: an untimed event on its own full-width row of the
// header, as in Palm Desktop, and a timed event on the grid, in columns where events overlap. Returns
// the index into `occs`, the box and true for a header row.
std::vector<std::tuple<std::size_t, date_shared::Rect, bool>> layout_day(std::span<const date_shared::Occ> occs,
                                                                         std::int32_t width);

// The title of an event made by dragging an address to the Day view: the name and the number of its row.
std::string event_title_of(const model::Address& a);

// The selected row of a single-selection list.
std::optional<std::size_t> records_selected(records::RecordTable& t, std::size_t n);

// Shows a menu of `labels` at the pointer; the index of the pick.
std::optional<std::size_t> popup(std::span<const std::string_view> labels);

// The events of a list of the right pane: a press and a drag go to the view, which drags a row to the Day
// grid; the list keeps its own selection of the pressed row.
bool pane_event(records::RecordTable& t, int ev, msg::Sender s);

// An x range from the left of a row.
using Span = std::optional<std::pair<std::int32_t, std::int32_t>>;

// The x ranges of the priority and the due date in a To Do pane row `w` px wide, when they show.
std::pair<Span, Span> pane_parts(const PaneModel& p, std::int32_t w);

// One To Do row of the pane, as Palm Desktop shows it after the Show Options: the check box, the priority,
// the description and the due date column, then the note and private marks. A selected row highlights
// the description only.
void draw_pane_todo(const PaneTodo& r, const PaneModel& p, bool selected,
                    std::tuple<std::int32_t, std::int32_t, std::int32_t, std::int32_t> box,
                    std::pair<Fl_PNG_Image&, Fl_PNG_Image&> icons);

// The first day of the week that opens the first row of the calendar of the month of `day`.
std::int64_t calendar_start(std::int64_t day, std::uint32_t begins);

// The month calendar of the right pane: the day letters on the panel gray, then six weeks. The selected day
// is filled; today is red and bold.
void draw_calendar(const Fl_Box& f, const PaneModel& p);

// Draws the box of `e` in `r`. The box of a title being edited is white, the field lies over its title.
void paint_box(date_shared::Rect r, const model::Event& e, const DayModel& m, bool centered);

// The untimed rows above the Day grid: one raised cell with the diamond at its middle, a black line under
// the rows, and a box for each untimed event.
void draw_header(const Fl_Box& f, const DayModel& m);

// The hours of the Day view: the time column of raised cells with their labels, the gray hour lines, and
// the timed boxes, the selected one last so its blue bar is on top.
void draw_grid(const Fl_Box& f, const DayModel& m);

// The length of a tab along the strip: the caption plus the Windows tab padding.
std::int32_t tab_len(msg::DateMode mode);

// The caption of a tab: Day, Week or Month.
std::string_view tab_caption(msg::DateMode mode);

// The tabs on the right edge, with their captions turned clockwise and their Alt letter underlined. The
// selected tab is 4 px longer and starts 2 px higher, so it covers the edge of the body it joins.
void draw_tabs(const Fl_Box& f, msg::DateMode mode);

} // namespace palm::date::detail
