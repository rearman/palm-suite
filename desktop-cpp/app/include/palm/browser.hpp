// An FLTK browser that keeps its selection on a click in the blank area below its lines. FLTK selects the line under
// the pointer, and none there, so such a click cleared the selection; a Windows list keeps it.
#pragma once

#include <FL/Fl.H>

namespace palm::browser {

// `B` is Fl_Hold_Browser or Fl_Multi_Browser. Build it in place of `B`.
template <class B> class Keep : public B {
public:
	using B::B;

	int handle(int event) override {
		// A press or a drag over the lines' area with no line under it goes no further: it takes the focus, as FLTK
		// does, and selects nothing. A drag outside the area still reaches FLTK, which scrolls the list.
		if (event == FL_PUSH || event == FL_DRAG) {
			int x = 0;
			int y = 0;
			int w = 0;
			int h = 0;
			this->bbox(x, y, w, h);
			if (Fl::event_inside(x, y, w, h) && this->find_item(Fl::event_y()) == nullptr) {
				if (event == FL_PUSH && Fl::visible_focus()) {
					static_cast<void>(this->take_focus());
				}
				return 1;
			}
		}
		return B::handle(event);
	}
};

} // namespace palm::browser
