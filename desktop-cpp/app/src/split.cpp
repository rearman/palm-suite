// The detail split, as desktop-rs/app/src/split.rs. The Rust resize callback is the resize() of Outer, and the Rust
// handle closure of the divider is the handle(int) of Bar, which runs FLTK's own handler first.
#include "palm/split.hpp"

#include "palm/draw.hpp"
#include "palm/theme.hpp"

#include <FL/Enumerations.H>
#include <FL/Fl.H>
#include <FL/Fl_Box.H>
#include <FL/Fl_Group.H>
#include <FL/Fl_Window.H>
#include <FL/fl_draw.H>

#include <algorithm>
#include <array>
#include <cstddef>
#include <utility>

namespace palm::split {
namespace {

constexpr int DIVIDER = 14;
// The narrowest list: the List by or Show button and New (166 px from the right) must stay right of the 1 px
// resizable of the list pane (128 px from the left), or FLTK widens them when the list widens again.
constexpr int MIN_LIST = 300;
constexpr int MIN_DETAIL = 200;
// The window width at which a `Grow::Share` detail width is saved: the normal 1000 px window.
constexpr int REF_W = 1000;

// The detail width in a window of `total` px. A `Grow::Share` width is scaled up or down from `REF_W`.
int detail_for(Grow grow, int detail, int total) {
	int d = detail;
	if (grow == Grow::Share) {
		// Known limit: integer division rounds the share down, by under 1 px per resize.
		d = detail * (total - DIVIDER) / (REF_W - DIVIDER);
	}
	return std::clamp(d, MIN_DETAIL, std::max(total - DIVIDER - MIN_LIST, MIN_DETAIL));
}

// The detail width of `detail` px in a window of `total` px, scaled to `REF_W`.
int to_ref(int detail, int total) {
	return detail * (REF_W - DIVIDER) / std::max(total - DIVIDER, 1);
}

// Lays the list, the divider and the detail pane in `o`, with the list `lw` px wide.
void place(Fl_Group* o, Fl_Group* l, Fl_Widget* b, Fl_Group* r, int lw) {
	l->resize(o->x(), o->y(), lw, o->h());
	b->resize(o->x() + lw, o->y(), DIVIDER, o->h());
	r->resize(o->x() + lw + DIVIDER, o->y(), o->w() - lw - DIVIDER, o->h());
}

// The group of the split. On a resize it lays the panes out again when the rule is Grow::Share.
class Outer : public Fl_Group {
public:
	Outer(int x, int y, int w, int h, Grow grow, int ref_detail, std::string_view key, msg::Sender s)
	    : Fl_Group(x, y, w, h), grow_(grow), ref_detail_(ref_detail), key_(key), sender_(std::move(s)) {}

	void resize(int x, int y, int w, int h) override {
		Fl_Group::resize(x, y, w, h);
		if (grow_ == Grow::Share) {
			const int d = detail_for(Grow::Share, ref_detail_, this->w());
			place(this, left_, bar_, right_, this->w() - DIVIDER - d);
		}
	}

	Grow grow_;
	// The detail width at REF_W, kept current by the drag, so that a resize scales from it.
	int ref_detail_;
	std::string_view key_;
	msg::Sender sender_;
	Fl_Group* left_ = nullptr;
	Fl_Group* right_ = nullptr;
	Fl_Widget* bar_ = nullptr;
};

// The divider: the raised bar of Palm Desktop, and the drag that moves it.
class Bar : public Fl_Box {
public:
	Bar(int x, int y, int w, int h, Outer* owner) : Fl_Box(x, y, w, h), owner_(owner) {}

	void draw() override {
		// FLTK's own draw runs first, as fltk-rs does before the Rust closure.
		Fl_Box::draw();
		fl_color(theme::PANEL);
		fl_rectf(x(), y(), w(), h());
		// The raised bar: 8 px of panel gray, then white, light gray three times, shadow gray and black.
		const draw::Dev d = draw::Dev::begin();
		const std::array<Fl_Color, 6> columns = {
		    draw::HIGHLIGHT,
		    fl_rgb_color(200, 200, 200),
		    fl_rgb_color(200, 200, 200),
		    fl_rgb_color(200, 200, 200),
		    draw::SHADOW,
		    FL_BLACK,
		};
		const draw::Rect r = d.rect(x(), y(), w(), h());
		const int k = d.lw();
		const int x0 = d.p(x() + 8);
		for (std::size_t i = 0; i < columns.size(); ++i) {
			fl_color(columns[i]);
			fl_rectf(x0 + static_cast<int>(i) * k, r.y, k, r.h);
		}
	}

	int handle(int e) override {
		// FLTK's own handler runs first, as fltk-rs does before the Rust closure.
		const int base = Fl_Box::handle(e);
		switch (e) {
		case FL_ENTER: {
			if (Fl_Window* win = window()) {
				win->cursor(FL_CURSOR_WE);
			}
			return 1;
		}
		case FL_LEAVE: {
			if (Fl_Window* win = window()) {
				win->cursor(FL_CURSOR_DEFAULT);
			}
			return 1;
		}
		case FL_PUSH:
			return 1;
		case FL_DRAG: {
			Fl_Group* o = owner_;
			const int total = o->w();
			const int mx = Fl::event_x() - o->x() - DIVIDER / 2;
			const int lw = std::clamp(mx, MIN_LIST, std::max(total - DIVIDER - MIN_DETAIL, MIN_LIST));
			place(o, owner_->left_, this, owner_->right_, lw);
			if (owner_->grow_ == Grow::Share) {
				owner_->ref_detail_ = to_ref(total - lw - DIVIDER, total);
			}
			o->init_sizes();
			o->redraw();
			return 1;
		}
		case FL_RELEASE: {
			const int width = owner_->grow_ == Grow::Detail ? owner_->right_->w() : owner_->ref_detail_;
			owner_->sender_.send(msg::SplitMoved{owner_->key_, width});
			return 1;
		}
		default:
			return base;
		}
	}

private:
	Outer* owner_;
};

} // namespace

Split split(int x, int y, int w, int h, int detail_w, std::string_view key, msg::Sender s) {
	return split_grow(x, y, w, h, detail_w, key, Grow::Detail, std::move(s));
}

Split split_grow(int x, int y, int w, int h, int detail_w, std::string_view key, Grow grow, msg::Sender s) {
	const int d = detail_for(grow, detail_w, w);
	// The Outer group begins, so the panes below become its children.
	auto* outer = new Outer(x, y, w, h, grow, detail_w, key, std::move(s));
	const int lw = w - DIVIDER - d;
	// The panes paint the panel gray under their widgets, so a widget that moves leaves no copy behind.
	auto* left = new Fl_Group(x, y, lw, h);
	left->end();
	left->box(FL_FLAT_BOX);
	left->color(theme::PANEL);
	auto* bar = new Bar(x + lw, y, DIVIDER, h, outer);
	bar->box(FL_FLAT_BOX);
	auto* right = new Fl_Group(x + lw + DIVIDER, y, d, h);
	right->end();
	right->box(FL_FLAT_BOX);
	right->color(theme::PANEL);
	outer->left_ = left;
	outer->right_ = right;
	outer->bar_ = bar;
	outer->resizable(grow == Grow::Detail ? right : left);
	outer->end();
	return Split{outer, left, right};
}

} // namespace palm::split
