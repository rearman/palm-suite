// The sync encryption of crypto.hpp. OpenSSL does the AES-256-GCM, PBKDF2, random and base64 steps; zlib
// does the raw deflate of the record bytes, as miniz_oxide does.
#include "palm/crypto.hpp"

#include "palm/text.hpp"

#include <openssl/evp.h>
#include <openssl/rand.h>
#include <zlib.h>

#include <algorithm>
#include <array>
#include <cstddef>
#include <format>
#include <limits>
#include <memory>
#include <stdexcept>

namespace palm::crypto {
namespace {

// The sizes of the sealing: a 12-byte nonce, a 16-byte tag, a 32-byte key, and a 16-byte salt.
constexpr std::size_t NONCE_BYTES = 12;
constexpr std::size_t TAG_BYTES = 16;
constexpr std::size_t KEY_BYTES = 32;
constexpr std::size_t SALT_BYTES = 16;

// The error of a deflate stream that does not end.
constexpr const char* TRUNCATED_DEFLATE = "Truncated deflate data";

// Frees an OpenSSL cipher context on every path.
struct CipherFree {
	void operator()(EVP_CIPHER_CTX* ctx) const noexcept { EVP_CIPHER_CTX_free(ctx); }
};
using Cipher = std::unique_ptr<EVP_CIPHER_CTX, CipherFree>;

// Ends a zlib deflate stream and frees it. deflateEnd on a stream that failed to start only returns an error.
struct DeflateEnd {
	void operator()(z_stream* strm) const noexcept {
		deflateEnd(strm);
		delete strm;
	}
};
using Deflater = std::unique_ptr<z_stream, DeflateEnd>;

// Ends a zlib inflate stream and frees it. inflateEnd on a stream that failed to start only returns an error.
struct InflateEnd {
	void operator()(z_stream* strm) const noexcept {
		inflateEnd(strm);
		delete strm;
	}
};
using Inflater = std::unique_ptr<z_stream, InflateEnd>;

// The bytes of a string, as the Rust code's as_bytes.
std::span<const std::uint8_t> bytes_of(std::string_view text) {
	return {reinterpret_cast<const std::uint8_t*>(text.data()), text.size()};
}

// The bytes as text, for the UTF-8 decoding of opened records.
std::string_view as_chars(std::span<const std::uint8_t> bytes) {
	return {reinterpret_cast<const char*>(bytes.data()), bytes.size()};
}

// n bytes from the system random source. A failed source is fatal, as the expect in the Rust code.
std::vector<std::uint8_t> random(std::size_t n) {
	std::vector<std::uint8_t> b(n);
	if (RAND_bytes(b.data(), static_cast<int>(n)) != 1) {
		throw std::runtime_error("the system random source failed");
	}
	return b;
}

// The value of a standard base64 symbol, or -1 for a byte outside the alphabet.
int symbol_value(std::uint8_t c) {
	if (c >= 'A' && c <= 'Z') {
		return c - 'A';
	}
	if (c >= 'a' && c <= 'z') {
		return c - 'a' + 26;
	}
	if (c >= '0' && c <= '9') {
		return c - '0' + 52;
	}
	if (c == '+') {
		return 62;
	}
	if (c == '/') {
		return 63;
	}
	return -1;
}

// The raw deflate of bytes at a level, with no zlib header, as miniz_oxide's compress_to_vec.
std::vector<std::uint8_t> deflate_raw(std::span<const std::uint8_t> in, int level) {
	if (in.size() > std::numeric_limits<uInt>::max()) {
		throw std::length_error("the record is too large to deflate");
	}
	auto strm = Deflater(new z_stream{});
	if (deflateInit2(strm.get(), level, Z_DEFLATED, -15, 8, Z_DEFAULT_STRATEGY) != Z_OK) {
		throw std::runtime_error("deflate failed");
	}
	std::vector<std::uint8_t> out(deflateBound(strm.get(), static_cast<uLong>(in.size())));
	strm->next_in = const_cast<Bytef*>(in.data());
	strm->avail_in = static_cast<uInt>(in.size());
	strm->next_out = out.data();
	strm->avail_out = static_cast<uInt>(out.size());
	if (deflate(strm.get(), Z_FINISH) != Z_STREAM_END) {
		throw std::runtime_error("deflate failed");
	}
	out.resize(out.size() - strm->avail_out);
	return out;
}

// The bytes of a raw deflate stream. A stream that does not end is an error; bytes after its end are ignored.
std::expected<std::vector<std::uint8_t>, std::string> inflate_raw(std::span<const std::uint8_t> in) {
	if (in.size() > std::numeric_limits<uInt>::max()) {
		return std::unexpected(TRUNCATED_DEFLATE);
	}
	auto strm = Inflater(new z_stream{});
	if (inflateInit2(strm.get(), -15) != Z_OK) {
		return std::unexpected(TRUNCATED_DEFLATE);
	}
	strm->next_in = const_cast<Bytef*>(in.data());
	strm->avail_in = static_cast<uInt>(in.size());
	std::vector<std::uint8_t> out;
	std::array<std::uint8_t, 16384> chunk{};
	int rc = Z_OK;
	do {
		strm->next_out = chunk.data();
		strm->avail_out = static_cast<uInt>(chunk.size());
		rc = inflate(strm.get(), Z_NO_FLUSH);
		if (rc != Z_OK && rc != Z_STREAM_END) {
			return std::unexpected(TRUNCATED_DEFLATE);
		}
		const std::size_t produced = chunk.size() - strm->avail_out;
		out.insert(out.end(), chunk.begin(), chunk.begin() + static_cast<std::ptrdiff_t>(produced));
	} while (rc != Z_STREAM_END);
	return out;
}

} // namespace

// The standard base64 text of bytes, with padding. EVP_EncodeBlock writes a NUL after the text.
std::string b64(std::span<const std::uint8_t> bytes) {
	if (bytes.empty()) {
		return {};
	}
	std::string out(4 * ((bytes.size() + 2) / 3) + 1, '\0');
	const int n =
	    EVP_EncodeBlock(reinterpret_cast<unsigned char*>(out.data()), bytes.data(), static_cast<int>(bytes.size()));
	out.resize(static_cast<std::size_t>(n));
	return out;
}

// The rust base64 engine is strict: the text is padded to 4 symbols, the padding is at the end only, every
// other byte is in the alphabet, and the bits after the last byte are zero. Each check gives the error of the
// Rust base64 crate, so OpenSSL decodes only text that the Rust engine would decode.
std::expected<std::vector<std::uint8_t>, std::string> unb64(std::string_view text) {
	std::size_t pad = 0;
	if (text.ends_with("==")) {
		pad = 2;
	} else if (text.ends_with('=')) {
		pad = 1;
	}
	const std::size_t body = text.size() - pad;
	for (std::size_t i = 0; i < body; ++i) {
		const auto c = static_cast<std::uint8_t>(text[i]);
		if (symbol_value(c) < 0) {
			return std::unexpected(std::format("Invalid symbol {}, offset {}.", static_cast<unsigned>(c), i));
		}
	}
	if (body % 4 == 1) {
		return std::unexpected(std::format("Invalid input length: {}", body));
	}
	if (text.size() % 4 != 0) {
		return std::unexpected("Invalid padding");
	}
	const std::size_t rest = body % 4;
	if (rest >= 2) {
		const std::size_t at = body - 1;
		const int value = symbol_value(static_cast<std::uint8_t>(text[at]));
		const int unused_bits = rest == 2 ? 0x0F : 0x03;
		if ((value & unused_bits) != 0) {
			return std::unexpected(std::format("Invalid last symbol 0x{:02x} ('{}') at offset {}, decoded as 0b{:08b}.",
			                                   static_cast<unsigned>(text[at]), text[at], at, value));
		}
	}
	if (text.empty()) {
		return std::vector<std::uint8_t>{};
	}
	// EVP_DecodeBlock counts the padding bytes in its length, so the count of padding is taken off.
	std::vector<std::uint8_t> out(text.size() / 4 * 3);
	const int n = EVP_DecodeBlock(out.data(), bytes_of(text).data(), static_cast<int>(text.size()));
	if (n < 0 || static_cast<std::size_t>(n) < pad) {
		return std::unexpected("Invalid base64 input.");
	}
	out.resize(static_cast<std::size_t>(n) - pad);
	return out;
}

// The 16-byte salt of a new passphrase.
std::vector<std::uint8_t> new_salt() {
	return random(SALT_BYTES);
}

// The 32-byte key of a passphrase. Java's PBEKeySpec feeds the passphrase as UTF-8.
std::vector<std::uint8_t> derive_key(std::string_view passphrase, std::span<const std::uint8_t> salt,
                                     std::uint32_t rounds) {
	std::vector<std::uint8_t> key(KEY_BYTES);
	if (PKCS5_PBKDF2_HMAC(passphrase.data(), static_cast<int>(passphrase.size()), salt.data(),
	                      static_cast<int>(salt.size()), static_cast<int>(rounds), EVP_sha256(),
	                      static_cast<int>(key.size()), key.data()) != 1) {
		throw std::runtime_error("the key derivation failed");
	}
	return key;
}

// Seals `plain` for record `id` with a new random nonce.
std::expected<std::string, std::string> seal(std::span<const std::uint8_t> key, std::string_view id,
                                             std::span<const std::uint8_t> plain) {
	return seal_with_nonce(key, id, plain, random(NONCE_BYTES));
}

// Seals with a given nonce. Only the test vectors pass one: a nonce must never repeat under one key.
// The output is the nonce, the ciphertext and the tag, base64 encoded.
std::expected<std::string, std::string> seal_with_nonce(std::span<const std::uint8_t> key, std::string_view id,
                                                        std::span<const std::uint8_t> plain,
                                                        std::span<const std::uint8_t> nonce) {
	if (nonce.size() != NONCE_BYTES) {
		return std::unexpected("The nonce must be 12 bytes.");
	}
	if (key.size() != KEY_BYTES) {
		return std::unexpected("The sync key must be 32 bytes.");
	}
	std::vector<std::uint8_t> out(NONCE_BYTES + plain.size() + TAG_BYTES);
	std::copy(nonce.begin(), nonce.end(), out.begin());
	std::uint8_t* const cipher_text = out.data() + NONCE_BYTES;
	std::uint8_t* const tag = cipher_text + plain.size();

	auto ctx = Cipher(EVP_CIPHER_CTX_new());
	if (!ctx || EVP_EncryptInit_ex(ctx.get(), EVP_aes_256_gcm(), nullptr, nullptr, nullptr) != 1 ||
	    EVP_EncryptInit_ex(ctx.get(), nullptr, nullptr, key.data(), nonce.data()) != 1) {
		return std::unexpected("Sealing failed.");
	}
	// An AAD-only update sets its length to the AAD length, so the length of the ciphertext is not read from it.
	int len = 0;
	if (!id.empty() &&
	    EVP_EncryptUpdate(ctx.get(), nullptr, &len, bytes_of(id).data(), static_cast<int>(id.size())) != 1) {
		return std::unexpected("Sealing failed.");
	}
	if (!plain.empty() &&
	    EVP_EncryptUpdate(ctx.get(), cipher_text, &len, plain.data(), static_cast<int>(plain.size())) != 1) {
		return std::unexpected("Sealing failed.");
	}
	if (EVP_EncryptFinal_ex(ctx.get(), cipher_text + plain.size(), &len) != 1) {
		return std::unexpected("Sealing failed.");
	}
	if (EVP_CIPHER_CTX_ctrl(ctx.get(), EVP_CTRL_GCM_GET_TAG, static_cast<int>(TAG_BYTES), tag) != 1) {
		return std::unexpected("Sealing failed.");
	}
	return b64(out);
}

// Opens a blob of record `id`. Fails on a wrong key, a wrong id or changed bytes.
std::expected<std::vector<std::uint8_t>, std::string> open(std::span<const std::uint8_t> key, std::string_view id,
                                                           std::string_view blob) {
	auto raw = unb64(blob);
	if (!raw) {
		return std::unexpected(raw.error());
	}
	if (raw->size() <= NONCE_BYTES + TAG_BYTES) {
		return std::unexpected("sync blob too short");
	}
	if (key.size() != KEY_BYTES) {
		return std::unexpected("The sync key must be 32 bytes.");
	}
	const std::span<const std::uint8_t> nonce(raw->data(), NONCE_BYTES);
	const std::size_t cipher_size = raw->size() - NONCE_BYTES - TAG_BYTES;
	const std::span<const std::uint8_t> cipher_text(raw->data() + NONCE_BYTES, cipher_size);
	std::array<std::uint8_t, TAG_BYTES> tag{};
	std::copy_n(raw->data() + NONCE_BYTES + cipher_size, TAG_BYTES, tag.begin());

	const std::string_view failed = "The blob does not open with this key.";
	auto ctx = Cipher(EVP_CIPHER_CTX_new());
	if (!ctx || EVP_DecryptInit_ex(ctx.get(), EVP_aes_256_gcm(), nullptr, nullptr, nullptr) != 1 ||
	    EVP_DecryptInit_ex(ctx.get(), nullptr, nullptr, key.data(), nonce.data()) != 1) {
		return std::unexpected(std::string(failed));
	}
	int len = 0;
	if (!id.empty() &&
	    EVP_DecryptUpdate(ctx.get(), nullptr, &len, bytes_of(id).data(), static_cast<int>(id.size())) != 1) {
		return std::unexpected(std::string(failed));
	}
	std::vector<std::uint8_t> plain(cipher_size);
	if (EVP_DecryptUpdate(ctx.get(), plain.data(), &len, cipher_text.data(), static_cast<int>(cipher_size)) != 1) {
		return std::unexpected(std::string(failed));
	}
	if (EVP_CIPHER_CTX_ctrl(ctx.get(), EVP_CTRL_GCM_SET_TAG, static_cast<int>(TAG_BYTES), tag.data()) != 1) {
		return std::unexpected(std::string(failed));
	}
	// The final call checks the tag, so the plaintext is returned only when the tag matches.
	if (EVP_DecryptFinal_ex(ctx.get(), plain.data() + cipher_size, &len) != 1) {
		return std::unexpected(std::string(failed));
	}
	return plain;
}

// The check blob of a key: the check text sealed under the check id.
std::expected<std::string, std::string> check_blob(std::span<const std::uint8_t> key) {
	return seal(key, CHECK_ID, bytes_of(CHECK_TEXT));
}

// Whether a check blob opens with this key to the check text.
bool check_ok(std::span<const std::uint8_t> key, std::string_view check) {
	const auto plain = open(key, CHECK_ID, check);
	return plain && std::ranges::equal(*plain, bytes_of(CHECK_TEXT));
}

namespace plain_bytes {
namespace {

// The first byte of sealed bytes that are raw deflate.
constexpr std::uint8_t DEFLATED = 0x01;

// The deflate level of a record, as the Rust code.
constexpr int LEVEL = 9;

} // namespace

// The bytes to seal for the text of one record: deflated behind the marker when deflate shortens them.
std::vector<std::uint8_t> pack(std::string_view plain) {
	const auto bytes = bytes_of(plain);
	const auto deflated = deflate_raw(bytes, LEVEL);
	if (1 + deflated.size() < bytes.size()) {
		std::vector<std::uint8_t> out;
		out.reserve(1 + deflated.size());
		out.push_back(DEFLATED);
		out.insert(out.end(), deflated.begin(), deflated.end());
		return out;
	}
	return std::vector<std::uint8_t>(bytes.begin(), bytes.end());
}

// The text of opened bytes. Bytes that start with the marker are inflated first.
std::expected<std::string, std::string> unpack(std::span<const std::uint8_t> raw) {
	if (raw.empty() || raw[0] != DEFLATED) {
		return text::from_utf8_lossy(as_chars(raw));
	}
	const auto bytes = inflate_raw(raw.subspan(1));
	if (!bytes) {
		return std::unexpected(bytes.error());
	}
	return text::from_utf8_lossy(as_chars(*bytes));
}

} // namespace plain_bytes

} // namespace palm::crypto
