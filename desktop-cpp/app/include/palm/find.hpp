// The Find dialog of Dialogs.kt, as desktop-rs/app/src/find.rs: Find what, Match whole word only and Match case, then a
// list of the records found in every app. Go To closes it and shows the record in its view.
#pragma once

#include "palm/runner.hpp"
#include "palm/view.hpp"

#include <cstdint>
#include <optional>
#include <string_view>
#include <tuple>
#include <vector>

namespace palm::find {

// The records that match `text`: the store finds it ignoring case, then Match case and Match whole word filter it.
// Private records show only when the context shows them.
std::vector<runner::Record> matches(const view::Ctx& ctx, std::string_view text, bool whole, bool match_case);

// The Find dialog. Returns the record that Go To picked, or no value on Cancel.
std::optional<runner::Record> show(const view::Ctx& ctx);

namespace detail {

// The order of a result row, as the 3.1.1 Find lists it: the app, then the date and the start, then the rest.
using ResultKey = std::tuple<std::uint8_t, std::int64_t, std::int32_t>;

// The sort key of a result row. The sort is stable, so rows that tie keep the store order.
ResultKey result_order(const runner::Record& r);

// True when `text` holds `word` with no word character just before or after it. Case counts only with `match_case`.
// Exposed for test_find.cpp.
bool has_whole_word(std::string_view text, std::string_view word, bool match_case);

} // namespace detail

} // namespace palm::find
