// The sync codec: the plaintext of a sync blob, as JSON (sync-server/PROTOCOL.md section 6), as SyncCodec.kt.
// Writers write format 2: short keys, the kind in "k" ("e", "t", "m", "a", "c", "s"), and no field that holds
// its default. Readers accept format 2 and format 1, which uses the long keys and the kind in "kind". The key
// order differs from org.json's, which no reader depends on. As desktop-rs/core/src/codec.rs.
#pragma once

#include "palm/model.hpp"

#include <cstdint>
#include <expected>
#include <functional>
#include <span>
#include <string>
#include <string_view>
#include <utility>
#include <vector>

namespace palm::codec {

// A list of (name, text) ShortCuts.
using ShortCuts = std::vector<std::pair<std::string, std::string>>;

// The format 2 text of an event.
std::string event(const model::Event& e);

// The format 2 text of a To Do item; `category_uid` is the uid of its category, or "" when unfiled.
std::string todo(const model::Todo& t, std::string_view category_uid);

// The format 2 text of a memo.
std::string memo(const model::Memo& m, std::string_view category_uid);

// The format 2 text of an address.
std::string address(const model::Address& a, std::string_view category_uid);

// The format 2 text of a category.
std::string category(const model::Category& c);

// The format 2 text of a ShortCuts list.
std::string shortcuts(std::span<const ShortCuts::value_type> list);

// The event of a blob, in format 2 or format 1. The id is 0; the caller stores it.
std::expected<model::Event, std::string> to_event(std::string_view text, std::string_view uid);

// The To Do item of a blob. `category_id` maps the category uid to its local id.
std::expected<model::Todo, std::string> to_todo(std::string_view text, std::string_view uid,
                                                std::function<std::int32_t(std::string_view)> category_id);

// The memo of a blob. `category_id` maps the category uid to its local id.
std::expected<model::Memo, std::string> to_memo(std::string_view text, std::string_view uid,
                                                std::function<std::int32_t(std::string_view)> category_id);

// The address of a blob. `category_id` maps the category uid to its local id.
std::expected<model::Address, std::string> to_address(std::string_view text, std::string_view uid,
                                                      std::function<std::int32_t(std::string_view)> category_id);

// The category of a blob, with the local id `id`.
std::expected<model::Category, std::string> to_category(std::string_view text, std::string_view uid, std::int32_t id);

// The ShortCuts list of a blob. A blob with no list gives an empty list.
std::expected<ShortCuts, std::string> to_shortcuts(std::string_view text);

// The "kind" of a blob, or "" when the text is not a JSON object or has no kind.
std::string kind(std::string_view text);

} // namespace palm::codec
