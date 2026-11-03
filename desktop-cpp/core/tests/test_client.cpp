// links: client
// The client vectors of sync-core/testdata, checked as in desktop-rs/core/tests/vectors.rs, and the address
// rule of SyncClient::new_client.
#include "palm/client.hpp"
#include "vectors.hpp"

#include <doctest/doctest.h>
#include <nlohmann/json.hpp>

#include <cstdint>
#include <string>
#include <vector>

using palm::client::Change;
using palm::client::parse_meta;
using palm::client::parse_result;
using palm::client::push_body;
using palm::client::PushItem;
using palm::client::SyncClient;

TEST_CASE("client_vectors") {
	const auto v = vectors::load("client");
	const auto& pb = v.at("pushBody");
	std::vector<PushItem> push;
	for (const auto& p : vectors::arr(pb.at("push"))) {
		push.push_back(PushItem{vectors::s(p.at("id")), static_cast<std::int64_t>(vectors::i(p.at("baseRev"))),
		                        vectors::b(p.at("deleted")), vectors::s(p.at("blob"))});
	}
	CHECK(vectors::json_of(push_body(static_cast<std::int64_t>(vectors::i(pb.at("since"))), push)) ==
	      vectors::json_of(vectors::s(pb.at("text"))));
	for (const auto& x : vectors::arr(v.at("parseResult"))) {
		const auto r = parse_result(vectors::s(x.at("text")));
		REQUIRE(r.has_value());
		CHECK(r->rev == vectors::i(x.at("rev")));
		nlohmann::json accepted = nlohmann::json::object();
		for (const auto& [id, rev] : r->accepted) {
			accepted[id] = rev;
		}
		CHECK(accepted == x.at("accepted"));
		nlohmann::json conflicts = nlohmann::json::array();
		for (const auto& id : r->conflicts) {
			conflicts.push_back(id);
		}
		CHECK(conflicts == x.at("conflicts"));
		nlohmann::json changes = nlohmann::json::array();
		for (const Change& c : r->changes) {
			changes.push_back(nlohmann::json{{"id", c.id}, {"rev", c.rev}, {"deleted", c.deleted}, {"blob", c.blob}});
		}
		CHECK(changes == x.at("changes"));
	}
	for (const auto& x : vectors::arr(v.at("parseMeta"))) {
		const auto m = parse_meta(vectors::s(x.at("text")));
		REQUIRE(m.has_value());
		CHECK(m->salt == vectors::s(x.at("salt")));
		CHECK(m->check == vectors::s(x.at("check")));
	}
}

TEST_CASE("client_address_rule") {
	// https is allowed for any host. Plain http is allowed for a loopback host with a port, as the Rust
	// prefixes "http://127.0.0.1:", "http://[::1]:" and "http://localhost:". A bare "http://localhost" has no
	// port, so the Rust rule rejects it.
	CHECK(SyncClient::new_client("https://host", "t").has_value());
	CHECK(SyncClient::new_client("http://127.0.0.1:8640", "t").has_value());
	CHECK(SyncClient::new_client("http://[::1]:8640", "t").has_value());
	CHECK(SyncClient::new_client("http://localhost:8640", "t").has_value());
	CHECK_FALSE(SyncClient::new_client("http://localhost", "t").has_value());
	const auto plain = SyncClient::new_client("http://example.com", "t");
	REQUIRE_FALSE(plain.has_value());
	CHECK(plain.error() == "The server address must start with https://");
}

TEST_CASE("client_address_trims_unicode") {
	// The address is trimmed as Rust's str::trim: U+00A0 (NBSP, UTF-8 C2 A0) is White_Space, so it goes.
	CHECK(SyncClient::new_client(" https://host", "t").has_value());
	CHECK(SyncClient::new_client("https://host\xC2\xA0", "t").has_value());
}

TEST_CASE("client_token_control_byte_refused_before_connect") {
	// A CR LF in the token would split the Authorization line. The client is built for a closed loopback port,
	// so a call that reached the connect would fail with another message, and the status would still be 0.
	const auto client = SyncClient::new_client("http://127.0.0.1:1", "a\r\nX-Evil: 1");
	REQUIRE(client.has_value());
	const auto meta = client->get_meta();
	REQUIRE_FALSE(meta.has_value());
	CHECK(meta.error().status == 0);
	CHECK(meta.error().message == "invalid header value");
}
