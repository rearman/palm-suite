//! The palmsync HTTP client and its wire format, as SyncClient.kt. The protocol is in
//! sync-server/PROTOCOL.md. The calls block; run them off the UI thread.

use crate::json::*;
use serde_json::{json, Value};
use std::collections::{BTreeSet, HashMap};
use std::time::Duration;

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct SyncMeta {
	pub salt: String,
	pub check: String,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct PushItem {
	pub id: String,
	pub base_rev: i64,
	pub deleted: bool,
	pub blob: String,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Change {
	pub id: String,
	pub rev: i64,
	pub deleted: bool,
	pub blob: String,
}

#[derive(Clone, Debug, PartialEq, Eq, Default)]
pub struct SyncResult {
	pub rev: i64,
	pub accepted: HashMap<String, i64>,
	pub conflicts: BTreeSet<String>,
	pub changes: Vec<Change>,
}

/// An error answer or a failed call. `status` is the HTTP status code, or 0 when no answer came.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct SyncError {
	pub status: u16,
	pub message: String,
}

impl std::fmt::Display for SyncError {
	fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
		f.write_str(&self.message)
	}
}

impl std::error::Error for SyncError {}

impl From<String> for SyncError {
	fn from(message: String) -> SyncError {
		SyncError { status: 0, message }
	}
}

/// The JSON body of POST /v1/sync. A deleted item sends an empty blob.
pub fn push_body(since: i64, push: &[PushItem]) -> String {
	let items: Vec<Value> = push
		.iter()
		.map(
			|p| json!({"id": p.id, "baseRev": p.base_rev, "deleted": p.deleted, "blob": if p.deleted { "" } else { &p.blob }}),
		)
		.collect();
	json!({"since": since, "push": items}).to_string()
}

fn objects<'a>(o: &'a Obj, key: &str) -> Result<Vec<&'a Obj>, String> {
	match o.get(key) {
		Some(Value::Array(a)) => a
			.iter()
			.map(|v| {
				v.as_object()
					.ok_or_else(|| format!("{key} holds a value that is not an object"))
			})
			.collect(),
		_ => Ok(Vec::new()),
	}
}

/// Reads the body of a 200 answer from POST /v1/sync.
pub fn parse_result(text: &str) -> Result<SyncResult, String> {
	let o = parse_obj(text)?;
	let mut accepted = HashMap::new();
	for a in objects(&o, "accepted")? {
		accepted.insert(get_str(a, "id")?, get_i64(a, "rev")?);
	}
	let mut conflicts = BTreeSet::new();
	if let Some(Value::Array(a)) = o.get("conflicts") {
		for v in a {
			conflicts.insert(
				v.as_str()
					.ok_or("conflicts holds a value that is not a string")?
					.to_string(),
			);
		}
	}
	let mut changes = Vec::new();
	for c in objects(&o, "changes")? {
		changes.push(Change {
			id: get_str(c, "id")?,
			rev: get_i64(c, "rev")?,
			deleted: opt_bool(c, "deleted"),
			blob: opt_str(c, "blob"),
		});
	}
	Ok(SyncResult {
		rev: get_i64(&o, "rev")?,
		accepted,
		conflicts,
		changes,
	})
}

/// Reads the body of GET /v1/meta. Missing fields are empty strings.
pub fn parse_meta(text: &str) -> Result<SyncMeta, String> {
	let o = parse_obj(text)?;
	Ok(SyncMeta {
		salt: opt_str(&o, "salt"),
		check: opt_str(&o, "check"),
	})
}

pub struct SyncClient {
	base: String,
	token: String,
	agent: ureq::Agent,
}

impl SyncClient {
	/// A client for a server address that starts with https://. Plain http is allowed for a loopback
	/// address only (127.0.0.1, [::1], localhost), where the test server runs.
	pub fn new(base_url: &str, token: &str) -> Result<SyncClient, String> {
		let base = base_url.trim().trim_end_matches('/').to_string();
		let loopback = ["http://127.0.0.1:", "http://[::1]:", "http://localhost:"]
			.iter()
			.any(|p| base.starts_with(p));
		if !base.starts_with("https://") && !loopback {
			return Err("The server address must start with https://".to_string());
		}
		let config = ureq::Agent::config_builder()
			.http_status_as_error(false)
			.timeout_connect(Some(Duration::from_secs(15)))
			.timeout_global(Some(Duration::from_secs(75)))
			.build();
		Ok(SyncClient {
			base,
			token: token.to_string(),
			agent: config.into(),
		})
	}

	fn call(&self, method: &str, path: &str, body: Option<&str>) -> Result<(u16, String), SyncError> {
		let url = format!("{}{}", self.base, path);
		let auth = format!("Bearer {}", self.token);
		let result = match (method, body) {
			("GET", _) => self.agent.get(&url).header("Authorization", &auth).call(),
			("PUT", Some(b)) => self
				.agent
				.put(&url)
				.header("Authorization", &auth)
				.header("Content-Type", "application/json")
				.send(b),
			(_, b) => self
				.agent
				.post(&url)
				.header("Authorization", &auth)
				.header("Content-Type", "application/json")
				.send(b.unwrap_or("")),
		};
		let mut response = result.map_err(|e| SyncError {
			status: 0,
			message: e.to_string(),
		})?;
		let status = response.status().as_u16();
		let text = response.body_mut().read_to_string().unwrap_or_default();
		Ok((status, text))
	}

	fn failure(status: u16, text: &str) -> SyncError {
		if status == 401 {
			return SyncError {
				status,
				message: "The server refused the token.".to_string(),
			};
		}
		let error = parse_obj(text).map(|o| opt_str(&o, "error")).unwrap_or_default();
		SyncError {
			status,
			message: if error.is_empty() {
				format!("HTTP {status}")
			} else {
				error
			},
		}
	}

	/// Reads the setup values. Fails on any status other than 200.
	pub fn get_meta(&self) -> Result<SyncMeta, SyncError> {
		let (status, text) = self.call("GET", "/v1/meta", None)?;
		if status != 200 {
			return Err(Self::failure(status, &text));
		}
		Ok(parse_meta(&text)?)
	}

	/// Sets the setup values. True on 204, false on 409 (already set).
	pub fn put_meta(&self, m: &SyncMeta) -> Result<bool, SyncError> {
		let body = json!({"salt": m.salt, "check": m.check}).to_string();
		let (status, text) = self.call("PUT", "/v1/meta", Some(&body))?;
		match status {
			204 => Ok(true),
			409 => Ok(false),
			_ => Err(Self::failure(status, &text)),
		}
	}

	/// Pushes the local changes and returns the server changes after `since`.
	pub fn sync(&self, since: i64, push: &[PushItem]) -> Result<SyncResult, SyncError> {
		let (status, text) = self.call("POST", "/v1/sync", Some(&push_body(since, push)))?;
		if status != 200 {
			return Err(Self::failure(status, &text));
		}
		Ok(parse_result(&text)?)
	}
}

/// The sync key for `passphrase`, as SyncKeys.firstKey. When the server has no setup values yet, it
/// makes a salt and a check blob and stores them; when another device stores them first (409), it
/// reads them again. None for a wrong passphrase.
pub fn first_key(client: &SyncClient, passphrase: &str) -> Result<Option<Vec<u8>>, SyncError> {
	let meta = client.get_meta()?;
	if meta.salt.is_empty() {
		let salt = crate::crypto::new_salt();
		let key = crate::crypto::derive_key(passphrase, &salt, crate::crypto::ROUNDS);
		let stored = SyncMeta {
			salt: crate::crypto::b64(&salt),
			check: crate::crypto::check_blob(&key)?,
		};
		if client.put_meta(&stored)? {
			return Ok(Some(key));
		}
		return Ok(key_from_meta(passphrase, &client.get_meta()?)?);
	}
	Ok(key_from_meta(passphrase, &meta)?)
}

/// Derives the key from the salt in `meta` and checks it against the check blob. None for a wrong
/// passphrase; an error when the salt is empty.
pub fn key_from_meta(passphrase: &str, meta: &SyncMeta) -> Result<Option<Vec<u8>>, String> {
	if meta.salt.is_empty() {
		return Err("Sync setup failed.".to_string());
	}
	let key = crate::crypto::derive_key(passphrase, &crate::crypto::unb64(&meta.salt)?, crate::crypto::ROUNDS);
	Ok(if crate::crypto::check_ok(&key, &meta.check) {
		Some(key)
	} else {
		None
	})
}
