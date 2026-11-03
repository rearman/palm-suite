// The Edit Address dialog of AddressDialog.kt, after the Palm Desktop 3.1.1 Edit Address dialog, and the Custom Field
// Labels dialog of Dialogs.kt, as desktop-rs/app/src/address_dialog.rs.
#pragma once

#include "palm/model.hpp"
#include "palm/settings.hpp"
#include "palm/view.hpp"

#include <array>
#include <cstdint>
#include <optional>
#include <string>
#include <string_view>

namespace palm::address_dialog {

// The four custom field labels, from the setting "addressLabels" (a JSON array). A blank or missing label gives
// "Custom 1" to "Custom 4".
std::array<std::string, 4> custom_labels(const settings::Settings& settings);

// The five phone label choices of a new address, from the setting "addressDefaultLabels". Work, Home, Fax, Other and
// E-mail when none is saved or the value is bad.
std::array<std::int32_t, 5> default_labels(const settings::Settings& settings);

// Opens Edit Address for `orig`, or for a new address in `new_category` when `orig` is empty. OK and New save the form:
// an old record always, a new record only when it has text. "Save as default" keeps the five label choices for the next
// new address.
void edit(view::Ctx& ctx, std::optional<model::Address> orig, std::int32_t new_category);

// The Custom Field Labels dialog. Returns true on OK, which saves the four labels; a blank label keeps the default name
// "Custom n".
bool custom_field_labels(view::Ctx& ctx);

namespace detail {

// The text of a phone label choice, with the colon of the form: "E-Mail:" as Palm Desktop 3.1.1 writes it. Exposed for
// test_address_dialog.cpp.
std::string phone_label(std::string_view l);

} // namespace detail

} // namespace palm::address_dialog
