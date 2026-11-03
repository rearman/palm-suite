// Sync encryption: PBKDF2-HMAC-SHA256 key derivation and AES-256-GCM sealing of records, as
// SyncCrypto.kt. The wire format is in sync-server/PROTOCOL.md, section 5.
#pragma once

#include <cstdint>
#include <expected>
#include <span>
#include <string>
#include <string_view>
#include <vector>

namespace palm::crypto {

// The PBKDF2 rounds of a new sync key.
inline constexpr std::uint32_t ROUNDS = 600000;
// The record id and the text of the check blob, which proves that a key opens the account.
inline constexpr std::string_view CHECK_ID = "check";
inline constexpr std::string_view CHECK_TEXT = "palm-suite";

// The standard base64 text of bytes, with padding.
std::string b64(std::span<const std::uint8_t> bytes);

// The bytes of standard base64 text. Whitespace, a bad symbol, or a non-canonical padding is an error.
std::expected<std::vector<std::uint8_t>, std::string> unb64(std::string_view text);

// The 16-byte salt of a new passphrase.
std::vector<std::uint8_t> new_salt();

// n bytes from the system random source (OpenSSL RAND_bytes); a failed source throws std::runtime_error.
std::vector<std::uint8_t> random_bytes(std::size_t n);

// The 32-byte key of a passphrase. Java's PBEKeySpec feeds the passphrase as UTF-8.
std::vector<std::uint8_t> derive_key(std::string_view passphrase, std::span<const std::uint8_t> salt,
                                     std::uint32_t rounds);

// Seals `plain` for record `id` with a new random nonce.
std::expected<std::string, std::string> seal(std::span<const std::uint8_t> key, std::string_view id,
                                             std::span<const std::uint8_t> plain);

// Seals with a given nonce. Only the test vectors pass one: a nonce must never repeat under one key.
std::expected<std::string, std::string> seal_with_nonce(std::span<const std::uint8_t> key, std::string_view id,
                                                        std::span<const std::uint8_t> plain,
                                                        std::span<const std::uint8_t> nonce);

// Opens a blob of record `id`. Fails on a wrong key, a wrong id or changed bytes.
std::expected<std::vector<std::uint8_t>, std::string> open(std::span<const std::uint8_t> key, std::string_view id,
                                                           std::string_view blob);

// The check blob of a key: the check text sealed under the check id.
std::expected<std::string, std::string> check_blob(std::span<const std::uint8_t> key);

// Whether a check blob opens with this key to the check text.
bool check_ok(std::span<const std::uint8_t> key, std::string_view check);

// The bytes that are sealed for one record, as PlainBytes in SyncRunner.kt. JSON always starts with
// "{", so a first byte of 0x01 marks raw deflate bytes; a record that deflate does not shorten is
// sealed as its UTF-8 text. The deflate bytes differ from Java's, which no reader depends on.
namespace plain_bytes {

// The bytes to seal for the text of one record.
std::vector<std::uint8_t> pack(std::string_view plain);

// The text of opened bytes. Bytes that start with the marker are inflated first.
std::expected<std::string, std::string> unpack(std::span<const std::uint8_t> raw);

} // namespace plain_bytes

} // namespace palm::crypto
