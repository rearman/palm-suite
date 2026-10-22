//! Sync encryption: PBKDF2-HMAC-SHA256 key derivation and AES-256-GCM sealing of records, as
//! SyncCrypto.kt. The wire format is in sync-server/PROTOCOL.md, section 5.

use aes_gcm::aead::{Aead, KeyInit, Payload};
use aes_gcm::{Aes256Gcm, Nonce};
use base64::engine::general_purpose::STANDARD;
use base64::Engine;

pub const ROUNDS: u32 = 600_000;
pub const CHECK_ID: &str = "check";
pub const CHECK_TEXT: &str = "palm-suite";
const NONCE_BYTES: usize = 12;
const TAG_BYTES: usize = 16;

pub fn b64(bytes: &[u8]) -> String {
	STANDARD.encode(bytes)
}

pub fn unb64(text: &str) -> Result<Vec<u8>, String> {
	STANDARD.decode(text).map_err(|e| e.to_string())
}

fn random(n: usize) -> Vec<u8> {
	let mut b = vec![0u8; n];
	getrandom::fill(&mut b).expect("the system random source failed");
	b
}

pub fn new_salt() -> Vec<u8> {
	random(16)
}

/// The 32-byte key of a passphrase. Java's PBEKeySpec feeds the passphrase as UTF-8.
pub fn derive_key(passphrase: &str, salt: &[u8], rounds: u32) -> Vec<u8> {
	let mut key = vec![0u8; 32];
	pbkdf2::pbkdf2_hmac::<sha2::Sha256>(passphrase.as_bytes(), salt, rounds, &mut key);
	key
}

fn cipher(key: &[u8]) -> Result<Aes256Gcm, String> {
	Aes256Gcm::new_from_slice(key).map_err(|_| "The sync key must be 32 bytes.".to_string())
}

/// Seals `plain` for record `id` with a new random nonce.
pub fn seal(key: &[u8], id: &str, plain: &[u8]) -> Result<String, String> {
	seal_with_nonce(key, id, plain, &random(NONCE_BYTES))
}

/// Seals with a given nonce. Only the test vectors pass one: a nonce must never repeat under one key.
pub fn seal_with_nonce(key: &[u8], id: &str, plain: &[u8], nonce: &[u8]) -> Result<String, String> {
	let nonce = Nonce::try_from(nonce).map_err(|_| "The nonce must be 12 bytes.".to_string())?;
	let sealed = cipher(key)?
		.encrypt(
			&nonce,
			Payload {
				msg: plain,
				aad: id.as_bytes(),
			},
		)
		.map_err(|_| "Sealing failed.".to_string())?;
	let mut out = nonce.to_vec();
	out.extend_from_slice(&sealed);
	Ok(b64(&out))
}

/// Opens a blob of record `id`. Fails on a wrong key, a wrong id or changed bytes.
pub fn open(key: &[u8], id: &str, blob: &str) -> Result<Vec<u8>, String> {
	let raw = unb64(blob)?;
	if raw.len() <= NONCE_BYTES + TAG_BYTES {
		return Err("sync blob too short".to_string());
	}
	let nonce = Nonce::try_from(&raw[..NONCE_BYTES]).map_err(|_| "bad nonce".to_string())?;
	cipher(key)?
		.decrypt(
			&nonce,
			Payload {
				msg: &raw[NONCE_BYTES..],
				aad: id.as_bytes(),
			},
		)
		.map_err(|_| "The blob does not open with this key.".to_string())
}

pub fn check_blob(key: &[u8]) -> Result<String, String> {
	seal(key, CHECK_ID, CHECK_TEXT.as_bytes())
}

pub fn check_ok(key: &[u8], check: &str) -> bool {
	open(key, CHECK_ID, check).is_ok_and(|p| p == CHECK_TEXT.as_bytes())
}

/// The bytes that are sealed for one record, as PlainBytes in SyncRunner.kt. JSON always starts with
/// "{", so a first byte of 0x01 marks raw deflate bytes; a record that deflate does not shorten is
/// sealed as its UTF-8 text. The deflate bytes differ from Java's, which no reader depends on.
pub mod plain_bytes {
	const DEFLATED: u8 = 0x01;

	pub fn pack(plain: &str) -> Vec<u8> {
		let bytes = plain.as_bytes();
		let deflated = miniz_oxide::deflate::compress_to_vec(bytes, 9);
		if 1 + deflated.len() < bytes.len() {
			let mut out = Vec::with_capacity(1 + deflated.len());
			out.push(DEFLATED);
			out.extend_from_slice(&deflated);
			out
		} else {
			bytes.to_vec()
		}
	}

	/// The text of opened bytes. Bytes that start with the marker are inflated first.
	pub fn unpack(raw: &[u8]) -> Result<String, String> {
		if raw.first() != Some(&DEFLATED) {
			return Ok(String::from_utf8_lossy(raw).into_owned());
		}
		let bytes =
			miniz_oxide::inflate::decompress_to_vec(&raw[1..]).map_err(|_| "Truncated deflate data".to_string())?;
		Ok(String::from_utf8_lossy(&bytes).into_owned())
	}
}
