//! The desktop settings in desktop.properties, as Settings.kt: the Java .properties format, so the
//! Swing app and this one share the file. The token and the key are in it, so on Linux only the user
//! can read it. The records in desktop-data.json are plain text, as in Palm Desktop.

use std::collections::BTreeMap;
use std::io;
use std::path::{Path, PathBuf};

pub const FILE_NAME: &str = "desktop.properties";
const COMMENT: &str = "The token and the key are saved in a file that only the user can read. \
	The records in desktop-data.json are plain text, as in Palm Desktop.";

/// %APPDATA%\palm-suite, else $XDG_CONFIG_HOME/palm-suite, else ~/.config/palm-suite.
pub fn config_dir() -> PathBuf {
	let var = |k: &str| std::env::var(k).ok().filter(|v| !v.is_empty());
	if let Some(app_data) = var("APPDATA") {
		return Path::new(&app_data).join("palm-suite");
	}
	if let Some(xdg) = var("XDG_CONFIG_HOME") {
		return Path::new(&xdg).join("palm-suite");
	}
	let home = var("HOME").or_else(|| var("USERPROFILE")).unwrap_or_else(|| ".".into());
	Path::new(&home).join(".config").join("palm-suite")
}

/// The settings of one file. Each set saves the file at once.
pub struct Settings {
	path: PathBuf,
	props: BTreeMap<String, String>,
}

impl Settings {
	/// The settings in `dir`. A missing or unreadable file gives no settings, as Settings.kt.
	pub fn open(dir: &Path) -> Settings {
		let path = dir.join(FILE_NAME);
		let props = std::fs::read(&path).map(|b| parse(&latin1(&b))).unwrap_or_default();
		Settings { path, props }
	}

	pub fn get(&self, key: &str, default: &str) -> String {
		self.props.get(key).cloned().unwrap_or_else(|| default.to_string())
	}

	pub fn set(&mut self, key: &str, value: &str) -> io::Result<()> {
		self.props.insert(key.to_string(), value.to_string());
		self.save()
	}

	pub fn remove(&mut self, key: &str) -> io::Result<()> {
		self.props.remove(key);
		self.save()
	}

	pub fn get_int(&self, key: &str, default: i32) -> i32 {
		self.props.get(key).and_then(|v| v.parse().ok()).unwrap_or(default)
	}

	pub fn set_int(&mut self, key: &str, value: i32) -> io::Result<()> {
		self.set(key, &value.to_string())
	}

	/// Only "true" and "false" count, as Kotlin's toBooleanStrictOrNull.
	pub fn get_bool(&self, key: &str, default: bool) -> bool {
		match self.props.get(key).map(String::as_str) {
			Some("true") => true,
			Some("false") => false,
			_ => default,
		}
	}

	pub fn set_bool(&mut self, key: &str, value: bool) -> io::Result<()> {
		self.set(key, if value { "true" } else { "false" })
	}

	fn save(&self) -> io::Result<()> {
		if let Some(dir) = self.path.parent() {
			std::fs::create_dir_all(dir)?;
		}
		let stamp = jiff::Zoned::now().strftime("%a %b %d %H:%M:%S %Z %Y").to_string();
		// store() writes pure ASCII, so its bytes are the ISO-8859-1 bytes that Java reads.
		std::fs::write(&self.path, store(&self.props, COMMENT, &stamp))?;
		#[cfg(unix)]
		{
			use std::os::unix::fs::PermissionsExt;
			std::fs::set_permissions(&self.path, std::fs::Permissions::from_mode(0o600))?;
		}
		Ok(())
	}
}

/// The chars of ISO-8859-1 bytes, as Properties.load(InputStream) reads them.
fn latin1(bytes: &[u8]) -> String {
	bytes.iter().map(|b| *b as char).collect()
}

const BLANK: [char; 3] = [' ', '\t', '\u{c}'];

/// Reads the key-value pairs of a .properties text, as java.util.Properties.load.
pub fn parse(text: &str) -> BTreeMap<String, String> {
	let mut out = BTreeMap::new();
	let lines: Vec<&str> = text.split(['\n', '\r']).collect();
	let mut i = 0;
	while i < lines.len() {
		let mut line = lines[i].trim_start_matches(BLANK).to_string();
		i += 1;
		if line.is_empty() || line.starts_with('#') || line.starts_with('!') {
			continue;
		}
		// A line that ends in an odd number of backslashes continues on the next line.
		while line.chars().rev().take_while(|c| *c == '\\').count() % 2 == 1 {
			line.pop();
			match lines.get(i) {
				Some(next) => {
					line.push_str(next.trim_start_matches(BLANK));
					i += 1;
				}
				None => break,
			}
		}
		let chars: Vec<char> = line.chars().collect();
		let mut k = 0;
		while k < chars.len() && !matches!(chars[k], '=' | ':' | ' ' | '\t' | '\u{c}') {
			k += if chars[k] == '\\' { 2 } else { 1 };
		}
		let key: String = chars[..k.min(chars.len())].iter().collect();
		while k < chars.len() && BLANK.contains(&chars[k]) {
			k += 1;
		}
		if k < chars.len() && matches!(chars[k], '=' | ':') {
			k += 1;
		}
		while k < chars.len() && BLANK.contains(&chars[k]) {
			k += 1;
		}
		let value: String = chars[k.min(chars.len())..].iter().collect();
		out.insert(unescape(&key), unescape(&value));
	}
	out
}

/// Undoes the escapes: \t, \n, \r, \f, \uXXXX (UTF-16 units, so a surrogate pair joins), and \c for c.
fn unescape(s: &str) -> String {
	let chars: Vec<char> = s.chars().collect();
	let mut units: Vec<u16> = Vec::new();
	let push = |units: &mut Vec<u16>, c: char| {
		let mut b = [0u16; 2];
		units.extend_from_slice(c.encode_utf16(&mut b));
	};
	let mut i = 0;
	while i < chars.len() {
		let c = chars[i];
		i += 1;
		if c != '\\' || i >= chars.len() {
			push(&mut units, c);
			continue;
		}
		let n = chars[i];
		i += 1;
		match n {
			't' => push(&mut units, '\t'),
			'n' => push(&mut units, '\n'),
			'r' => push(&mut units, '\r'),
			'f' => push(&mut units, '\u{c}'),
			'u' => {
				let hex: String = chars[i..(i + 4).min(chars.len())].iter().collect();
				match u16::from_str_radix(&hex, 16).ok().filter(|_| hex.len() == 4) {
					Some(u) => {
						units.push(u);
						i += 4;
					}
					None => push(&mut units, 'u'),
				}
			}
			other => push(&mut units, other),
		}
	}
	String::from_utf16_lossy(&units)
}

/// The .properties text of `props`, as java.util.Properties.store: a comment line, a date line, then one
/// escaped key=value line each. Chars outside printable ASCII become \uXXXX, one per UTF-16 unit.
pub fn store(props: &BTreeMap<String, String>, comment: &str, stamp: &str) -> String {
	let mut out = format!("#{comment}\n#{stamp}\n");
	for (k, v) in props {
		out.push_str(&escape(k, true));
		out.push('=');
		out.push_str(&escape(v, false));
		out.push('\n');
	}
	out
}

fn escape(s: &str, escape_space: bool) -> String {
	let mut out = String::new();
	for (i, c) in s.chars().enumerate() {
		match c {
			' ' if i == 0 || escape_space => out.push_str("\\ "),
			'\\' => out.push_str("\\\\"),
			'\t' => out.push_str("\\t"),
			'\n' => out.push_str("\\n"),
			'\r' => out.push_str("\\r"),
			'\u{c}' => out.push_str("\\f"),
			'=' | ':' | '#' | '!' => {
				out.push('\\');
				out.push(c);
			}
			c if (c as u32) < 0x20 || (c as u32) > 0x7e => {
				let mut b = [0u16; 2];
				for u in c.encode_utf16(&mut b) {
					out.push_str(&format!("\\u{:04X}", u));
				}
			}
			c => out.push(c),
		}
	}
	out
}
