// Print, as desktop-rs/app/src/printing.rs: the title of the view in bold, then its lines in 10 pt, wrapped at the page
// width and split into pages. The system print dialog sets the printer and the paper.
#pragma once

#include <functional>
#include <string>
#include <string_view>
#include <vector>

namespace palm::printing {

// `text` cut into rows of at most `width` px, at the spaces where it can, as LineBreakMeasurer does. A new line starts
// a new row; a word wider than the row is cut by its characters. `measure` gives the width of a string in pixels.
std::vector<std::string> wrap(std::string_view text, int width, const std::function<int(std::string_view)>& measure);

// Prints `title` and `lines` through the system print dialog. A canceled dialog prints nothing.
void print(std::string_view title, const std::vector<std::string>& lines);

} // namespace palm::printing
