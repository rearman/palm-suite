// The Palm Desktop 3.1.1 bitmaps, embedded at build time from the icons of the Swing app
// (desktop/src/main/resources/com/rearman/palmdesktop/icons/*.png; CMakeLists.txt generates the data).
#pragma once

#include <span>
#include <string_view>

namespace palm::assets {

// One embedded PNG file: its name (the file name without .png) and its bytes.
struct Asset {
	std::string_view name;
	std::span<const unsigned char> bytes;
};

// Every embedded file.
std::span<const Asset> all();

// The bytes of icons/<name>.png, or an empty span when there is none.
std::span<const unsigned char> png(std::string_view name);

} // namespace palm::assets
