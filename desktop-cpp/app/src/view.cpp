#include "palm/view.hpp"

namespace palm::view {

void Ctx::set(std::string_view key, std::string_view value) {
	// The error is dropped, as the Rust `let _ =`.
	static_cast<void>(settings.set(key, value));
}

} // namespace palm::view
