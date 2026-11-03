// links: crypto
// The crypto vectors of sync-core/testdata, checked as in desktop-rs/core/tests/vectors.rs.
#include "palm/crypto.hpp"
#include "vectors.hpp"

#include <doctest/doctest.h>

#include <cstdint>
#include <string>

using palm::crypto::b64;
using palm::crypto::check_ok;
using palm::crypto::derive_key;
using palm::crypto::open;
using palm::crypto::seal_with_nonce;
using palm::crypto::unb64;
namespace plain_bytes = palm::crypto::plain_bytes;

TEST_CASE("crypto_vectors") {
	const auto v = vectors::load("crypto");
	for (const auto& k : vectors::arr(v.at("kdf"))) {
		INFO(k.dump());
		const auto salt = unb64(vectors::s(k.at("salt")));
		REQUIRE(salt.has_value());
		const auto key =
		    derive_key(vectors::s(k.at("passphrase")), *salt, static_cast<std::uint32_t>(vectors::i(k.at("rounds"))));
		REQUIRE(b64(key) == vectors::s(k.at("key")));
	}
	for (const auto& x : vectors::arr(v.at("seal"))) {
		const auto key = unb64(vectors::s(x.at("key")));
		REQUIRE(key.has_value());
		const auto plain = unb64(vectors::s(x.at("plain")));
		REQUIRE(plain.has_value());
		const auto nonce = unb64(vectors::s(x.at("nonce")));
		REQUIRE(nonce.has_value());
		const std::string id = vectors::s(x.at("id"));
		const std::string blob = vectors::s(x.at("blob"));
		const auto sealed = seal_with_nonce(*key, id, *plain, *nonce);
		REQUIRE(sealed.has_value());
		REQUIRE(*sealed == blob);
		const auto opened = open(*key, id, blob);
		REQUIRE(opened.has_value());
		REQUIRE(*opened == *plain);
		REQUIRE_FALSE(open(*key, "other-id", blob).has_value());
		if (id == palm::crypto::CHECK_ID) {
			REQUIRE(check_ok(*key, blob));
		}
	}
}

TEST_CASE("pack_vectors") {
	const auto doc = vectors::load("pack");
	for (const auto& x : vectors::arr(doc.at("pack"))) {
		const std::string text = vectors::s(x.at("text"));
		INFO(text);
		const auto packed = unb64(vectors::s(x.at("packed")));
		REQUIRE(packed.has_value());
		const auto unpacked = plain_bytes::unpack(*packed);
		REQUIRE(unpacked.has_value());
		REQUIRE(*unpacked == text);
		const auto repacked = plain_bytes::pack(text);
		const auto round_trip = plain_bytes::unpack(repacked);
		REQUIRE(round_trip.has_value());
		REQUIRE(*round_trip == text);
		// Both sides deflate the same texts, whatever their deflate bytes.
		REQUIRE((repacked.at(0) == 1) == (packed->at(0) == 1));
	}
}
