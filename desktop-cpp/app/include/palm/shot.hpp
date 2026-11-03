// The offscreen capture of `--shot`, for the screenshots of each phase of the port.
#pragma once

#include <expected>
#include <filesystem>
#include <string>

class Fl_Window;

namespace palm::shot {

// Draws `win` offscreen at the display scale and writes it to `path` as a binary PPM. The error is the OS error text
// when the file cannot be written.
std::expected<void, std::string> save(Fl_Window& win, const std::filesystem::path& path);

} // namespace palm::shot
