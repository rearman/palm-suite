#include "palm/shot.hpp"
#include "palm/platform.hpp"

#include <FL/Fl_Device.H>
#include <FL/Fl_Image_Surface.H>
#include <FL/Fl_RGB_Image.H>
#include <FL/Fl_Window.H>

#include <cstddef>
#include <fstream>
#include <memory>
#include <stdexcept>
#include <string>

namespace palm::shot {

std::expected<void, std::string> save(Fl_Window& win, const std::filesystem::path& path) {
	Fl_Image_Surface surf(win.w(), win.h(), 1);
	Fl_Surface_Device::push_current(&surf);
	surf.draw(&win, 0, 0);
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

} // namespace palm::shot
