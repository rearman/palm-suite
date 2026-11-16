// The palmsync HTTP client of client.hpp. libcurl does the HTTP steps that ureq does in the Rust code; the
// wire format and the status handling are the same.
#include "palm/client.hpp"

#include "palm/crypto.hpp"
#include "palm/json.hpp"
#include "palm/text.hpp"

#include <cstddef>
#include <curl/curl.h>
#include <format>
#include <memory>
#include <mutex>
#include <new>
#include <optional>
#include <stdexcept>
#include <utility>

namespace palm::client {
namespace {

// The connect timeout and the stall limit, as the 15 s connect and 60 s read timeouts of the Kotlin client: a
// call fails when it moves fewer than STALL_BYTES_PER_SECOND bytes a second for STALL_SECONDS seconds. A whole
// call has no time limit, so a large answer on a slow link still arrives.
constexpr long CONNECT_TIMEOUT_SECONDS = 15;
constexpr long STALL_BYTES_PER_SECOND = 1;
constexpr long STALL_SECONDS = 60;

// Frees a libcurl URL handle on every path.
struct UrlFree {
	void operator()(CURLU* handle) const noexcept { curl_url_cleanup(handle); }
};
using Url = std::unique_ptr<CURLU, UrlFree>;

// Frees a string that libcurl gave out.
struct CurlFree {
	void operator()(char* text) const noexcept { curl_free(text); }
};

// One part of a parsed URL, or none when the URL lacks it.
std::optional<std::string> url_part(CURLU* url, CURLUPart part) {
	char* raw = nullptr;
	if (curl_url_get(url, part, &raw, 0) != CURLUE_OK || raw == nullptr) {
		return std::nullopt;
	}
	const std::unique_ptr<char, CurlFree> owned(raw);
	return std::string(owned.get());
}

// Whether a host is a loopback name: 127.0.0.1, ::1 (given as [::1]), or localhost, with no other text.
bool loopback_host(std::string_view host) {
	return host == "127.0.0.1" || host == "[::1]" || palm::text::eq_ignore_ascii_case(host, "localhost");
}

// Frees a libcurl easy handle on every path.
struct EasyFree {
	void operator()(CURL* handle) const noexcept { curl_easy_cleanup(handle); }
};
using Easy = std::unique_ptr<CURL, EasyFree>;

// Frees a libcurl header list on every path.
struct SlistFree {
	void operator()(curl_slist* list) const noexcept { curl_slist_free_all(list); }
};
using Slist = std::unique_ptr<curl_slist, SlistFree>;

// The error of a failed call before any answer: a SyncError with status 0.
SyncError from_message(std::string message) {
	return SyncError{0, std::move(message)};
}

// Starts libcurl once for the process.
void curl_start() {
	static std::once_flag once;
	std::call_once(once, [] {
		if (curl_global_init(CURL_GLOBAL_DEFAULT) != CURLE_OK) {
			throw std::runtime_error("libcurl did not start");
		}
	});
}

// Adds one header line to a list. curl_slist_append keeps the old list when it fails, so the old head is
// released from the owner only after the new head is known.
void add_header(Slist& list, const std::string& line) {
	curl_slist* next = curl_slist_append(list.get(), line.c_str());
	if (next == nullptr) {
		throw std::bad_alloc{};
	}
	list.release();
	list.reset(next);
}

// Receives the body of an answer. The server does not page the changes of POST /v1/sync, and it sets no limit
// on an answer, so the body has no size limit here either, as the Kotlin client.
// Shortcut: the whole body is held in memory, so a hostile server could fill it; a streaming JSON reader of
// the changes would bound it.
std::size_t append_body(char* data, std::size_t size, std::size_t count, void* out) {
	const std::size_t n = size * count;
	static_cast<std::string*>(out)->append(data, n);
	return n;
}

// Whether a header value holds only bytes that http accepts: no control bytes, save the tab, and no 0x7F.
bool valid_header_value(std::string_view value) {
	for (const char c : value) {
		const auto b = static_cast<unsigned char>(c);
		if ((b < 0x20 && b != '\t') || b == 0x7F) {
			return false;
		}
	}
	return true;
}

// The array of a key that holds only objects. Missing or non-array keys give no elements, as the Rust code.
std::expected<std::vector<const nlohmann::json*>, std::string> objects(const json::Obj& o, std::string_view key) {
	std::vector<const nlohmann::json*> out;
	if (const auto* a = json::opt_array(o, key)) {
		for (const auto& v : *a) {
			if (!v.is_object()) {
				return std::unexpected(std::format("{} holds a value that is not an object", key));
			}
			out.push_back(&v);
		}
	}
	return out;
}

} // namespace

// The JSON body of POST /v1/sync. A deleted item sends an empty blob.
std::string push_body(std::int64_t since, std::span<const PushItem> push) {
	nlohmann::json items = nlohmann::json::array();
	for (const auto& p : push) {
		items.push_back(nlohmann::json{{"id", p.id},
		                               {"baseRev", p.base_rev},
		                               {"deleted", p.deleted},
		                               {"blob", p.deleted ? std::string() : p.blob}});
	}
	return json::dump(nlohmann::json{{"since", since}, {"push", items}});
}

// Reads the body of a 200 answer from POST /v1/sync.
std::expected<SyncResult, std::string> parse_result(std::string_view text) {
	auto parsed = json::parse_obj(text);
	if (!parsed) {
		return std::unexpected(parsed.error());
	}
	const json::Obj& o = *parsed;
	SyncResult result;
	auto accepted = objects(o, "accepted");
	if (!accepted) {
		return std::unexpected(accepted.error());
	}
	for (const auto* a : *accepted) {
		auto id = json::get_str(*a, "id");
		if (!id) {
			return std::unexpected(id.error());
		}
		auto rev = json::get_i64(*a, "rev");
		if (!rev) {
			return std::unexpected(rev.error());
		}
		result.accepted[*id] = *rev;
	}
	if (const auto* a = json::opt_array(o, "conflicts")) {
		for (const auto& v : *a) {
			if (!v.is_string()) {
				return std::unexpected("conflicts holds a value that is not a string");
			}
			result.conflicts.insert(v.get<std::string>());
		}
	}
	auto changes = objects(o, "changes");
	if (!changes) {
		return std::unexpected(changes.error());
	}
	for (const auto* c : *changes) {
		auto id = json::get_str(*c, "id");
		if (!id) {
			return std::unexpected(id.error());
		}
		auto rev = json::get_i64(*c, "rev");
		if (!rev) {
			return std::unexpected(rev.error());
		}
		result.changes.push_back(Change{*id, *rev, json::opt_bool(*c, "deleted"), json::opt_str(*c, "blob")});
	}
	auto rev = json::get_i64(o, "rev");
	if (!rev) {
		return std::unexpected(rev.error());
	}
	result.rev = *rev;
	return result;
}

// Reads the body of GET /v1/meta. Missing fields are empty strings.
std::expected<SyncMeta, std::string> parse_meta(std::string_view text) {
	auto parsed = json::parse_obj(text);
	if (!parsed) {
		return std::unexpected(parsed.error());
	}
	return SyncMeta{json::opt_str(*parsed, "salt"), json::opt_str(*parsed, "check")};
}

// A client for a server address that starts with https://. Plain http is allowed for a loopback
// address only (127.0.0.1, [::1], localhost) with a port, where the test server runs. libcurl parses the
// address, so the host compared is the host it connects to, and an address with a user name or password
// (e.g. http://localhost:1@evil.host, whose host is evil.host) is refused.
std::expected<SyncClient, std::string> SyncClient::new_client(std::string_view base_url, std::string_view token) {
	std::string_view text = palm::text::trim(base_url);
	while (text.ends_with('/')) {
		text.remove_suffix(1);
	}
	std::string base(text);
	const std::string must_https = "The server address must start with https://";
	Url url(curl_url());
	if (!url) {
		throw std::bad_alloc{};
	}
	if (curl_url_set(url.get(), CURLUPART_URL, base.c_str(), 0) != CURLUE_OK) {
		return std::unexpected(must_https);
	}
	if (url_part(url.get(), CURLUPART_USER) || url_part(url.get(), CURLUPART_PASSWORD)) {
		return std::unexpected("The server address must not hold a user name or password.");
	}
	const std::string scheme = url_part(url.get(), CURLUPART_SCHEME).value_or("");
	const std::string host = url_part(url.get(), CURLUPART_HOST).value_or("");
	const bool loopback = scheme == "http" && loopback_host(host) && url_part(url.get(), CURLUPART_PORT);
	if (scheme != "https" && !loopback) {
		return std::unexpected(must_https);
	}
	return SyncClient(std::move(base), std::string(token));
}

SyncClient::SyncClient(std::string base, std::string token) : base(std::move(base)), token(std::move(token)) {}

// One HTTP call: the status and the body text. `body` is sent with PUT and POST only.
std::expected<std::pair<std::uint16_t, std::string>, SyncError>
SyncClient::call(std::string_view method, std::string_view path, std::optional<std::string_view> body) const {
	// A token with a control byte would end the header line early, so it is refused before any connect.
	if (!valid_header_value(token)) {
		return std::unexpected(from_message("invalid header value"));
	}
	curl_start();
	const std::string url = base + std::string(path);
	Easy easy(curl_easy_init());
	if (!easy) {
		return std::unexpected(from_message("The HTTP client did not start."));
	}
	CURL* h = easy.get();
	Slist headers;
	add_header(headers, "Authorization: Bearer " + token);
	std::string received;
	curl_easy_setopt(h, CURLOPT_URL, url.c_str());
	curl_easy_setopt(h, CURLOPT_NOSIGNAL, 1L);
	curl_easy_setopt(h, CURLOPT_CONNECTTIMEOUT, CONNECT_TIMEOUT_SECONDS);
	curl_easy_setopt(h, CURLOPT_LOW_SPEED_LIMIT, STALL_BYTES_PER_SECOND);
	curl_easy_setopt(h, CURLOPT_LOW_SPEED_TIME, STALL_SECONDS);
	// No redirects: ureq drops the Authorization header on a redirect, so a followed one could only fail,
	// and following it to another scheme or host would put the token at risk. A 3xx reports as "HTTP 3xx".
	curl_easy_setopt(h, CURLOPT_FOLLOWLOCATION, 0L);
#ifdef _WIN32
	// The Windows certificate store, as ureq's platform verifier; the MinGW curl has no CA file of its own
	// once the app is installed.
	curl_easy_setopt(h, CURLOPT_SSL_OPTIONS, static_cast<long>(CURLSSLOPT_NATIVE_CA));
#endif
	curl_easy_setopt(h, CURLOPT_WRITEFUNCTION, append_body);
	curl_easy_setopt(h, CURLOPT_WRITEDATA, &received);
	if (method == "GET") {
		// A GET sends no body and no Content-Type, as the Rust code.
	} else {
		// A PUT with a body is sent as PUT. Any other call, and a PUT with no body, is sent as POST.
		if (method == "PUT" && body) {
			curl_easy_setopt(h, CURLOPT_CUSTOMREQUEST, "PUT");
		}
		const std::string_view payload = body.value_or(std::string_view(""));
		curl_easy_setopt(h, CURLOPT_POSTFIELDS, payload.data());
		curl_easy_setopt(h, CURLOPT_POSTFIELDSIZE_LARGE, static_cast<curl_off_t>(payload.size()));
		add_header(headers, "Content-Type: application/json");
	}
	curl_easy_setopt(h, CURLOPT_HTTPHEADER, headers.get());

	const CURLcode rc = curl_easy_perform(h);
	long code = 0;
	curl_easy_getinfo(h, CURLINFO_RESPONSE_CODE, &code);
	if (rc != CURLE_OK) {
		// No status means no answer. A status with a failed body read keeps the status and gives an empty text,
		// as the Rust code's unwrap_or_default.
		if (code == 0) {
			return std::unexpected(from_message(curl_easy_strerror(rc)));
		}
		return std::pair{static_cast<std::uint16_t>(code), std::string{}};
	}
	// A body that is not UTF-8 (ureq's read_to_string fails on it) keeps the status and gives an empty text, as
	// the Rust code's unwrap_or_default.
	if (!text::is_utf8(received)) {
		return std::pair{static_cast<std::uint16_t>(code), std::string{}};
	}
	return std::pair{static_cast<std::uint16_t>(code), std::move(received)};
}

// The error of a status other than the expected one: the server's "error" text, or "HTTP <status>".
SyncError SyncClient::failure(std::uint16_t status, std::string_view text) {
	if (status == 401) {
		return SyncError{status, "The server refused the token."};
	}
	const auto parsed = json::parse_obj(text);
	const std::string error = parsed ? json::opt_str(*parsed, "error") : std::string{};
	return SyncError{status, error.empty() ? std::format("HTTP {}", status) : error};
}

// Reads the setup values. Fails on any status other than 200.
std::expected<SyncMeta, SyncError> SyncClient::get_meta() const {
	auto answer = call("GET", "/v1/meta", std::nullopt);
	if (!answer) {
		return std::unexpected(answer.error());
	}
	const auto& [status, text] = *answer;
	if (status != 200) {
		return std::unexpected(failure(status, text));
	}
	auto meta = parse_meta(text);
	if (!meta) {
		return std::unexpected(from_message(meta.error()));
	}
	return *meta;
}

// Sets the setup values. True on 204, false on 409 (already set).
std::expected<bool, SyncError> SyncClient::put_meta(const SyncMeta& m) const {
	const std::string body = json::dump(nlohmann::json{{"salt", m.salt}, {"check", m.check}});
	auto answer = call("PUT", "/v1/meta", std::string_view(body));
	if (!answer) {
		return std::unexpected(answer.error());
	}
	const auto& [status, text] = *answer;
	switch (status) {
	case 204:
		return true;
	case 409:
		return false;
	default:
		return std::unexpected(failure(status, text));
	}
}

// Pushes the local changes and returns the server changes after `since`.
std::expected<SyncResult, SyncError> SyncClient::sync(std::int64_t since, std::span<const PushItem> push) const {
	const std::string body = push_body(since, push);
	auto answer = call("POST", "/v1/sync", std::string_view(body));
	if (!answer) {
		return std::unexpected(answer.error());
	}
	const auto& [status, text] = *answer;
	if (status != 200) {
		return std::unexpected(failure(status, text));
	}
	auto result = parse_result(text);
	if (!result) {
		return std::unexpected(from_message(result.error()));
	}
	return *result;
}

// The sync key for `passphrase`, as SyncKeys.firstKey. When the server has no setup values yet, it
// makes a salt and a check blob and stores them; when another device stores them first (409), it
// reads them again. None for a wrong passphrase.
std::expected<std::optional<std::vector<std::uint8_t>>, SyncError> first_key(const SyncClient& client,
                                                                             std::string_view passphrase) {
	auto meta = client.get_meta();
	if (!meta) {
		return std::unexpected(meta.error());
	}
	if (meta->salt.empty()) {
		const auto salt = crypto::new_salt();
		const auto key = crypto::derive_key(passphrase, salt, crypto::ROUNDS);
		auto check = crypto::check_blob(key);
		if (!check) {
			return std::unexpected(from_message(check.error()));
		}
		const SyncMeta stored{crypto::b64(salt), *check};
		auto stored_now = client.put_meta(stored);
		if (!stored_now) {
			return std::unexpected(stored_now.error());
		}
		if (*stored_now) {
			return std::optional<std::vector<std::uint8_t>>(key);
		}
		auto again = client.get_meta();
		if (!again) {
			return std::unexpected(again.error());
		}
		auto found = key_from_meta(passphrase, *again);
		if (!found) {
			return std::unexpected(from_message(found.error()));
		}
		return *found;
	}
	auto found = key_from_meta(passphrase, *meta);
	if (!found) {
		return std::unexpected(from_message(found.error()));
	}
	return *found;
}

// Derives the key from the salt in `meta` and checks it against the check blob. None for a wrong
// passphrase; an error when the salt is empty.
std::expected<std::optional<std::vector<std::uint8_t>>, std::string> key_from_meta(std::string_view passphrase,
                                                                                   const SyncMeta& meta) {
	if (meta.salt.empty()) {
		return std::unexpected("Sync setup failed.");
	}
	auto salt = crypto::unb64(meta.salt);
	if (!salt) {
		return std::unexpected(salt.error());
	}
	const auto key = crypto::derive_key(passphrase, *salt, crypto::ROUNDS);
	if (crypto::check_ok(key, meta.check)) {
		return std::optional<std::vector<std::uint8_t>>(key);
	}
	return std::optional<std::vector<std::uint8_t>>{};
}

} // namespace palm::client
