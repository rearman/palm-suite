// The Month view, as desktop-rs/app/src/month.rs and MonthView.kt: a grid of six weeks, with the events of each day in
// its cell.
//
// The grid is a subclass that overrides draw() and handle(int): its handler takes the printable keys (a new event on
// the selected day, titled with the key) and the mouse, after FLTK's own handler, as the Rust closure does.
#pragma once

#include "palm/date.hpp"
#include "palm/date_shared.hpp"
#include "palm/msg.hpp"
#include "palm/view.hpp"

#include <FL/Enumerations.H>
#include <FL/Fl_Box.H>
#include <FL/Fl_Group.H>

#include <cstdint>
#include <memory>
#include <optional>
#include <vector>

namespace palm::month {

namespace detail {

// What the month grid draws.
struct MonthModel {
	std::int64_t start = 0;
	std::int64_t day = 0;
	std::int64_t today = 0;
	std::uint32_t begins = 0;
	// The events of each day of the six weeks, from `start`.
	std::vector<std::vector<date_shared::Occ>> days;
	bool h24 = false;
};

// The six weeks of the month. Draws the model; its keys and mouse events go to the view, and a printable key makes a
// new event on the selected day.
class MonthGridFrame : public Fl_Box {
public:
	MonthGridFrame(int x, int y, int w, int h, msg::Sender s, std::shared_ptr<MonthModel> model);
	void draw() override;
	int handle(int event) override;

private:
	msg::Sender s_;
	std::shared_ptr<MonthModel> model_;
};

} // namespace detail

// The Month view. The Day view owns it and shows it when the Month tab is picked.
class MonthView {
public:
	// Builds the view in (x, y, w, h) with the navigation row on top. The group is hidden until the view is shown.
	MonthView(int x, int y, int w, int h, msg::Sender s);

	// The group of the whole Month view.
	Fl_Group* root = nullptr;

	// Moves the focus into the grid.
	void focus();

	// Reads the store, the settings and the day again: the six weeks that hold the month of the day.
	void refresh(view::Ctx& ctx);

	// A press selects the day under it; a right press shows the cell menu; a double click opens the Day view, which
	// the caller shows. Returns the view to show, when the press asks for one.
	std::optional<msg::DateMode> mouse(view::Ctx& ctx, msg::Mouse m);

private:
	// The index (0 to 41) of the cell at window point (x, y), or no value on the name row.
	std::optional<std::int64_t> cell_at(int x, int y) const;

	Fl_Box* title_ = nullptr;
	detail::MonthGridFrame* grid_ = nullptr;
	std::shared_ptr<detail::MonthModel> model_;
};

} // namespace palm::month
