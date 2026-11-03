// The Palm Desktop look, as desktop-rs/app/src/theme.rs.
#include "palm/theme.hpp"

#include "palm/assets.hpp"
#include "palm/draw.hpp"

#include <FL/Fl.H>
#include <FL/Fl_PNG_Image.H>
#include <FL/Fl_RGB_Image.H>
#include <FL/fl_draw.H>

#include <algorithm>
#include <cmath>
#include <cstddef>
#include <initializer_list>
#include <optional>
#include <stdexcept>
#include <string>
#include <string_view>

namespace palm::theme {
namespace {

// The fonts of install(), empty until it runs.
std::optional<Fonts> installed;

// The font index of the first installed face of `names`, or no value. Like get_font_names() in fltk-rs, this compares
// the raw names of Fl::get_font(), which keep FLTK's style prefix (" " plain, "B" bold) on a Windows face and hold the
// family and style words of a Pango face.
std::optional<Fl_Font> face(std::initializer_list<const char*> names) {
	const int count = Fl::set_fonts("*");
	for (const char* name : names) {
		for (int i = 0; i < count; ++i) {
			if (std::string_view(Fl::get_font(i)) == name) {
				return static_cast<Fl_Font>(i);
			}
		}
	}
	return std::nullopt;
}

// The font index of the first installed face of `names`, or `fallback`.
Fl_Font font_of(std::initializer_list<const char*> names, Fl_Font fallback) {
	return face(names).value_or(fallback);
}

} // namespace

const Fonts& fonts() {
	if (!installed) {
		throw std::runtime_error("theme::install runs first");
	}
	return *installed;
}

void install() {
	Fl::scheme("base");
	Fl::background(240, 240, 240);
	Fl::background2(255, 255, 255);
	Fl::foreground(0, 0, 0);
	Fl::set_color(FL_SELECTION_COLOR, 0, 120, 215);
	draw::install_classic_boxes();
	const Fl_Font arial = font_of({" Arial", "Arial", "Liberation Sans"}, FL_HELVETICA);
	const Fl_Font arial_bold = font_of({"BArial", "Arial Bold", "Liberation Sans Bold"}, FL_HELVETICA_BOLD);
	const Fl_Font segoe = font_of({" Segoe UI", "Segoe UI"}, FL_HELVETICA);
	// The dialog font replaces Helvetica, the default of every widget.
	if (const auto f = face({" Microsoft Sans Serif", "Microsoft Sans Serif", "Liberation Sans"})) {
		Fl::set_font(FL_HELVETICA, Fl::get_font(*f));
	}
	if (const auto f = face({"BMicrosoft Sans Serif", "Microsoft Sans Serif Bold", "Liberation Sans Bold"})) {
		Fl::set_font(FL_HELVETICA_BOLD, Fl::get_font(*f));
	}
	FL_NORMAL_SIZE = 11;
	// A popup item is the text height plus the menu line spacing, so the spacing makes it MENU_ITEM_H tall.
	fl_font(FL_HELVETICA, 11);
	Fl::menu_linespacing(std::max(MENU_ITEM_H - fl_height(), 0));
	installed = Fonts{FL_HELVETICA, FL_HELVETICA_BOLD, arial, arial_bold, segoe};
}

std::unique_ptr<Fl_PNG_Image> icon(std::string_view name) {
	const auto bytes = assets::png(name);
	if (bytes.empty()) {
		throw std::runtime_error("no icon " + std::string(name));
	}
	auto img = std::make_unique<Fl_PNG_Image>(nullptr, bytes.data(), static_cast<int>(bytes.size()));
	if (img->fail()) {
		throw std::runtime_error("the icons are valid PNG files");
	}
	return img;
}

std::unique_ptr<Fl_RGB_Image> gray_icon(std::string_view name) {
	const auto img = icon(name);
	const int depth = img->d();
	const int w = img->data_w();
	const int h = img->data_h();
	const std::size_t row = static_cast<std::size_t>(w) * static_cast<std::size_t>(depth);
	const int ld = img->ld() ? img->ld() : static_cast<int>(row);
	// The copy is owned by the new image (alloc_array), which frees it with delete[].
	auto* pixels = new unsigned char[row * static_cast<std::size_t>(h)];
	for (int y = 0; y < h; ++y) {
		std::copy_n(img->array + static_cast<std::size_t>(y) * static_cast<std::size_t>(ld), row,
		            pixels + static_cast<std::size_t>(y) * row);
	}
	// Only the color depths have RGB channels to gray; L8 and LA8 keep their data.
	if (depth >= 3) {
		for (unsigned char* px = pixels; px < pixels + row * static_cast<std::size_t>(h); px += depth) {
			const int g = static_cast<int>(0.30f * px[0] + 0.59f * px[1] + 0.11f * px[2]);
			const auto v = static_cast<unsigned char>(255 - (255 - g) / 2);
			px[0] = v;
			px[1] = v;
			px[2] = v;
		}
	}
	auto out = std::make_unique<Fl_RGB_Image>(pixels, w, h, depth);
	out->alloc_array = 1;
	return out;
}

} // namespace palm::theme
