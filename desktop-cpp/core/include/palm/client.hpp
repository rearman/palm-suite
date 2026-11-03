// The palmsync HTTP client and its wire format, as desktop-rs/core/src/client.rs. The protocol is in
// sync-server/PROTOCOL.md. The calls block; run them off the UI thread.
#pragma once

#include <cstdint>
#include <expected>
#include <optional>
#include <set>
#include <span>
#include <string>
#include <string_view>
#include <unordered_map>
#include <utility>
#include <vector>

namespace palm::client {

// The setup values of the server: the salt of the key and the check blob.
struct SyncMeta {
	std::string salt;
	std::string check;
	bool operator==(const SyncMeta&) const = default;
};

// One local record to push: its id, the revision it was based on, and its sealed blob.
struct PushItem {
	std::string id;
	std::int64_t base_rev = 0;
	bool deleted = false;
	std::string blob;
	bool operator==(const PushItem&) const = default;
};

// One server record that changed after the sync point.
struct Change {
	std::string id;
	std::int64_t rev = 0;
	bool deleted = false;
	std::string blob;
	bool operator==(const Change&) const = default;
};

// The answer of POST /v1/sync: the new sync point, the accepted pushes, the conflicts, and the changes.
struct SyncResult {
	std::int64_t rev = 0;
	std::unordered_map<std::string, std::int64_t> accepted;
	std::set<std::string> conflicts;
	std::vector<Change> changes;
	bool operator==(const SyncResult&) const = default;
};

// An error answer or a failed call. `status` is the HTTP status code, or 0 when no answer came.
struct SyncError {
	std::uint16_t status = 0;
	std::string message;
	bool operator==(const SyncError&) const = default;
};

// The JSON body of POST /v1/sync. A deleted item sends an empty blob.
std::string push_body(std::int64_t since, std::span<const PushItem> push);

// Reads the body of a 200 answer from POST /v1/sync.
std::expected<SyncResult, std::string> parse_result(std::string_view text);

// Reads the body of GET /v1/meta. Missing fields are empty strings.
std::expected<SyncMeta, std::string> parse_meta(std::string_view text);

// A client for one palmsync server and its bearer token. Each call makes its own libcurl handle.
class SyncClient {
public:
	// A client for a server address that starts with https://. Plain http is allowed for a loopback
	// address only (127.0.0.1, [::1], localhost), where the test server runs.
	static std::expected<SyncClient, std::string> new_client(std::string_view base_url, std::string_view token);

	// Reads the setup values. Fails on any status other than 200.
	std::expected<SyncMeta, SyncError> get_meta() const;

	// Sets the setup values. True on 204, false on 409 (already set).
	std::expected<bool, SyncError> put_meta(const SyncMeta& m) const;

	// Pushes the local changes and returns the server changes after `since`.
	std::expected<SyncResult, SyncError> sync(std::int64_t since, std::span<const PushItem> push) const;

private:
	SyncClient(std::string base, std::string token);

	// One HTTP call: the status and the body text. `body` is sent with PUT and POST only.
	std::expected<std::pair<std::uint16_t, std::string>, SyncError> call(std::string_view method, std::string_view path,
	                                                                     std::optional<std::string_view> body) const;

	// The error of a status other than the expected one: the server's "error" text, or "HTTP <status>".
	static SyncError failure(std::uint16_t status, std::string_view text);

	std::string base;
	std::string token;
};

// The sync key for `passphrase`, as SyncKeys.firstKey. When the server has no setup values yet, it
// makes a salt and a check blob and stores them; when another device stores them first (409), it
// reads them again. None for a wrong passphrase.
std::expected<std::optional<std::vector<std::uint8_t>>, SyncError> first_key(const SyncClient& client,
                                                                             std::string_view passphrase);

// Derives the key from the salt in `meta` and checks it against the check blob. None for a wrong
// passphrase; an error when the salt is empty.
std::expected<std::optional<std::vector<std::uint8_t>>, std::string> key_from_meta(std::string_view passphrase,
                                                                                   const SyncMeta& meta);

} // namespace palm::client
