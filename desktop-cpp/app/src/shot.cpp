#include "palm/shot.hpp"
#include "palm/platform.hpp"
#include "palm/text.hpp"

#ifdef _WIN32
#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif
#ifndef NOMINMAX
#define NOMINMAX
#endif
#endif

#include <FL/Enumerations.H>
#include <FL/Fl.H>
#include <FL/Fl_Device.H>
#include <FL/Fl_Image_Surface.H>
#include <FL/Fl_RGB_Image.H>
#include <FL/Fl_Window.H>
#include <FL/fl_draw.H>
#include <FL/platform.H>

#include <algorithm>
#include <cmath>
#include <cstddef>
#include <cstdlib>
#include <fstream>
#include <memory>
#include <stdexcept>
#include <string>
#include <tuple>
#include <type_traits>

#ifdef __linux__
#include <X11/Xlib.h>
#endif

namespace palm::shot {

std::expected<std::vector<Step>, std::string> parse_steps(std::string_view text) {
	std::vector<Step> steps;
	for (std::string_view part : text::split(text, ';')) {
		part = text::trim(part);
		if (part.empty()) {
			continue;
		}
		const auto bad = [&] {
			return std::unexpected("bad step \"" + std::string(part) +
			                       "\": use click, rclick, dclick or move, then :X,Y");
		};
		const auto colon = part.find(':');
		if (colon == std::string_view::npos) {
			return bad();
		}
		const std::string_view kind = part.substr(0, colon);
		const std::string_view point = part.substr(colon + 1);
		const auto comma = point.find(',');
		if (comma == std::string_view::npos) {
			return bad();
		}
		const auto x = text::parse_i32(text::trim(point.substr(0, comma)));
		const auto y = text::parse_i32(text::trim(point.substr(comma + 1)));
		if (!x || !y) {
			return bad();
		}
		const std::string_view k = text::trim(kind);
		if (k == "click") {
			steps.emplace_back(Click{*x, *y});
		} else if (k == "rclick") {
			steps.emplace_back(RightClick{*x, *y});
		} else if (k == "dclick") {
			steps.emplace_back(DoubleClick{*x, *y});
		} else if (k == "move") {
			steps.emplace_back(Move{*x, *y});
		} else {
			return bad();
		}
	}
	return steps;
}

namespace {

// The shown, visible top-level windows, back to front: `main` first, then the others from the oldest shown.
std::vector<Fl_Window*> shown_windows(Fl_Window& main) {
	std::vector<Fl_Window*> others;
	for (Fl_Window* w = Fl::first_window(); w; w = Fl::next_window(w)) {
		if (w->shown() && w->visible() && w != &main) {
			others.push_back(w);
		}
	}
	// FLTK puts a window at the head of its list when it is shown.
	std::ranges::reverse(others);
	std::vector<Fl_Window*> all{&main};
	all.insert(all.end(), others.begin(), others.end());
	return all;
}

} // namespace

std::expected<void, std::string> save(Fl_Window& main, const std::filesystem::path& path) {
	const auto wins = shown_windows(main);
	const int mx = main.x_root();
	const int my = main.y_root();
	int left = 0;
	int top = 0;
	int right = main.w();
	int bottom = main.h();
	for (Fl_Window* w : wins) {
		const int x = w->x_root() - mx;
		const int y = w->y_root() - my;
		left = std::min(left, x);
		top = std::min(top, y);
		right = std::max(right, x + w->w());
		bottom = std::max(bottom, y + w->h());
	}
	Fl_Image_Surface surf(right - left, bottom - top, 1);
	Fl_Surface_Device::push_current(&surf);
	fl_rectf(0, 0, right - left, bottom - top, FL_BLACK);
	for (Fl_Window* w : wins) {
		surf.draw(w, w->x_root() - mx - left, w->y_root() - my - top);
	}
	Fl_Surface_Device::pop_current();
	std::unique_ptr<Fl_RGB_Image> img(surf.image());
	if (!img || !img->data() || !img->data()[0]) {
		throw std::runtime_error("surface image");
	}
	// Channels of the raw data: 1 gray, 2 gray & alpha, 3 RGB, 4 RGBA. The raw data is read as Rust reads it, one
	// block of data_w * data_h * channels bytes, with no line padding skipped.
	const int channels = img->d();
	if (channels < 1 || channels > 4) {
		throw std::runtime_error("image depth");
	}
	const auto w = img->data_w();
	const auto h = img->data_h();
	const std::string header = "P6\n" + std::to_string(w) + " " + std::to_string(h) + "\n255\n";
	const auto* raw = reinterpret_cast<const unsigned char*>(img->data()[0]);
	const auto count = static_cast<std::size_t>(w) * static_cast<std::size_t>(h) * static_cast<std::size_t>(channels);
	std::string out = header;
	out.reserve(header.size() + static_cast<std::size_t>(w) * static_cast<std::size_t>(h) * 3);
	for (std::size_t i = 0; i < count; i += static_cast<std::size_t>(channels)) {
		const unsigned char* px = raw + i;
		if (channels <= 2) {
			out.append(3, static_cast<char>(px[0]));
		} else {
			out.append(reinterpret_cast<const char*>(px), 3);
		}
	}
	std::ofstream file(path, std::ios::binary | std::ios::trunc);
	if (!file) {
		return std::unexpected(platform::last_os_error_text());
	}
	file.write(out.data(), static_cast<std::streamsize>(out.size()));
	file.close();
	if (!file) {
		return std::unexpected(platform::last_os_error_text());
	}
	return {};
}

void exit(int code) {
	std::_Exit(code);
}

namespace {

// A press of a button (1 left, 3 right).
struct Press {
	unsigned button;
};

// A release of a button.
struct Release {
	unsigned button;
};

// The second press of a double click.
struct Double {
	unsigned button;
};

// A move with no button.
struct MoveTo {};

// One mouse event to post: a press, a release or a move.
using Mouse = std::variant<Press, Release, Double, MoveTo>;

// The point of an event in device pixels: in the window, then on the screen.
using At = std::tuple<int, int, int, int>;

#ifdef _WIN32

// The mark in the high word of wParam of each posted message; FLTK reads only the low word's button flags.
constexpr WPARAM POSTED = 0x5a5a0000;
constexpr WPARAM POSTED_MASK = 0xffff0000;

// True (1) for keyboard and mouse input that `post` did not send, which FLTK then drops.
int drop_real_input(void* event, void* /*data*/) {
	const auto* msg = static_cast<const MSG*>(event);
	// WM_KEYFIRST to WM_KEYLAST, WM_MOUSEFIRST to WM_MOUSELAST, and the mouse messages of the window frame.
	const UINT m = msg->message;
	const bool input = (m >= 0x0100 && m <= 0x0109) || (m >= 0x0200 && m <= 0x020e) || (m >= 0x00a0 && m <= 0x00ad);
	return input && (msg->wParam & POSTED_MASK) != POSTED ? 1 : 0;
}

// Posts one mouse message at client point (`x`, `y`) in device pixels; FLTK reads the buttons from wParam.
void os_post(const Fl_Window& win, const Mouse& m, const At& at, std::uint32_t /*time*/) {
	const auto [x, y, root_x, root_y] = at;
	UINT msg = WM_MOUSEMOVE;
	WPARAM wparam = 0;
	if (const auto* p = std::get_if<Press>(&m)) {
		msg = p->button == 1 ? WM_LBUTTONDOWN : WM_RBUTTONDOWN;
		wparam = p->button == 1 ? MK_LBUTTON : MK_RBUTTON;
	} else if (const auto* d = std::get_if<Double>(&m)) {
		msg = d->button == 1 ? WM_LBUTTONDBLCLK : WM_RBUTTONDBLCLK;
		wparam = d->button == 1 ? MK_LBUTTON : MK_RBUTTON;
	} else if (const auto* r = std::get_if<Release>(&m)) {
		msg = r->button == 1 ? WM_LBUTTONUP : WM_RBUTTONUP;
	}
	const auto lparam = static_cast<LPARAM>((y & 0xffff) << 16 | (x & 0xffff));
	PostMessageW(fl_win32_xid(&win), msg, wparam | POSTED, lparam);
}

#elif defined(__linux__)

// True (1) for keyboard and pointer input from the server itself, which FLTK then drops. The server marks each event
// that XSendEvent sent with `send_event`.
int drop_real_input(void* event, void* /*data*/) {
	const auto* ev = static_cast<const XEvent*>(event);
	return ev->type >= KeyPress && ev->type <= LeaveNotify && !ev->xany.send_event ? 1 : 0;
}

// Sends one mouse event to the window at (`x`, `y`) in device pixels, (`root_x`, `root_y`) on the screen.
void os_post(const Fl_Window& win, const Mouse& m, const At& at, std::uint32_t time) {
	const auto [x, y, root_x, root_y] = at;
	int kind = MotionNotify;
	unsigned button = 0;
	if (const auto* p = std::get_if<Press>(&m)) {
		kind = ButtonPress;
		button = p->button;
	} else if (const auto* d = std::get_if<Double>(&m)) {
		kind = ButtonPress;
		button = d->button;
	} else if (const auto* r = std::get_if<Release>(&m)) {
		kind = ButtonRelease;
		button = r->button;
	}
	Display* display = fl_x11_display();
	const ::Window xid = fl_x11_xid(&win);
	XEvent ev{};
	// XMotionEvent matches XButtonEvent field for field (`button` is `is_hint` there, 0 for a plain move).
	ev.xbutton.type = kind;
	ev.xbutton.serial = 0;
	ev.xbutton.send_event = 1;
	ev.xbutton.display = display;
	ev.xbutton.window = xid;
	ev.xbutton.root = XDefaultRootWindow(display);
	ev.xbutton.subwindow = 0;
	ev.xbutton.time = time;
	ev.xbutton.x = x;
	ev.xbutton.y = y;
	ev.xbutton.x_root = root_x;
	ev.xbutton.y_root = root_y;
	ev.xbutton.state = kind == ButtonRelease ? Button1Mask << (button - 1) : 0;
	ev.xbutton.button = button;
	ev.xbutton.same_screen = 1;
	XSendEvent(display, xid, 0, 0, &ev);
	XFlush(display);
}

#endif

} // namespace

int device(int v, float s) {
	return static_cast<int>(std::ceil(static_cast<float>(v) * s));
}

void post(Fl_Window& main, const Step& step, std::uint32_t time) {
	Fl_Window* target = &main;
	for (Fl_Window* w : {Fl::grab(), Fl::modal()}) {
		if (w && w->shown()) {
			target = w;
			break;
		}
	}
	const auto [x, y, events] = std::visit(
	    [](const auto& s) -> std::tuple<int, int, std::vector<Mouse>> {
		    using T = std::decay_t<decltype(s)>;
		    if constexpr (std::is_same_v<T, Click>) {
			    return {s.x, s.y, {Press{1}, Release{1}}};
		    } else if constexpr (std::is_same_v<T, RightClick>) {
			    return {s.x, s.y, {Press{3}, Release{3}}};
		    } else if constexpr (std::is_same_v<T, DoubleClick>) {
			    return {s.x, s.y, {Press{1}, Release{1}, Double{1}, Release{1}}};
		    } else {
			    return {s.x, s.y, {MoveTo{}}};
		    }
	    },
	    step);
	// The point in the target window, and on the screen, in logical pixels.
	const int root_x = main.x_root() + x;
	const int root_y = main.y_root() + y;
	const int local_x = root_x - target->x_root();
	const int local_y = root_y - target->y_root();
	const float s = Fl::screen_scale(target->screen_num());
	const At at{device(local_x, s), device(local_y, s), device(root_x, s), device(root_y, s)};
	for (const Mouse& m : events) {
		os_post(*target, m, at, time);
	}
}

void ignore_real_input() {
	Fl::add_system_handler(drop_real_input, nullptr);
}

} // namespace palm::shot
