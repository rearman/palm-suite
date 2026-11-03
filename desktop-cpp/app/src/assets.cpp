#include "palm/assets.hpp"

#include <algorithm>

namespace palm::assets {

std::span<const unsigned char> png(std::string_view name) {
	const auto table = all();
	const auto it = std::ranges::find(table, name, &Asset::name);
	return it == table.end() ? std::span<const unsigned char>{} : it->bytes;
}

} // namespace palm::assets
