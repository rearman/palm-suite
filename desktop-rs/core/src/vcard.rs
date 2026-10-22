//! vCard files of Address Book records, as VCard.kt. Import reads vCard 2.1, 3.0 and 4.0. Export writes
//! vCard 3.0. The id, the sync uid and the category id stay local; a category travels as its name.

use crate::ical::{escape, fold, unescape};
use crate::model::{is_blank, Address, EMAIL, FAX, HOME, LABELS, MAIN, MOBILE, OTHER, PAGER, WORK};
use std::collections::HashMap;

/// One card: its address, and the name of its first category (None when it has none).
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Parsed {
	pub address: Address,
	pub category_name: Option<String>,
}

struct Prop {
	name: String,
	params: HashMap<String, Vec<String>>,
	value: String,
}

/// A phone or e-mail field of a card, before it takes one of the five phone slots.
struct PhoneEntry {
	label: i32,
	number: String,
	pref: bool,
}

const ENCODINGS: [&str; 5] = ["QUOTED-PRINTABLE", "BASE64", "B", "8BIT", "7BIT"];

/// Reads every BEGIN:VCARD to END:VCARD card of `text`. A card with no usable field is skipped. The
/// phones fill the five phone slots in file order; phones past five and later ADR fields go to the note.
pub fn parse(text: &str) -> Vec<Parsed> {
	let mut out = Vec::new();
	let mut card: Option<Vec<Prop>> = None;
	for l in unfold(text) {
		let Some(p) = prop(&l) else { continue };
		if p.name == "BEGIN" && p.value.trim().eq_ignore_ascii_case("VCARD") {
			card = Some(Vec::new());
		} else if p.name == "END" && p.value.trim().eq_ignore_ascii_case("VCARD") {
			if let Some(open) = card.take() {
				if let Some(c) = build(&open) {
					out.push(c);
				}
			}
		} else if let Some(open) = card.as_mut() {
			open.push(p);
		}
	}
	out
}

/// Writes `list` as vCard 3.0 with CRLF line ends. `category_name` gives the name of a category id;
/// category 0 (Unfiled) writes no CATEGORIES line.
pub fn export(list: &[Address], category_name: impl Fn(i32) -> Option<String>) -> String {
	let mut out = String::new();
	let mut line = |s: &str| fold(s, &mut out);
	for a in list {
		line("BEGIN:VCARD");
		line("VERSION:3.0");
		line(&format!("N:{};{};;;", escape(&a.last_name), escape(&a.first_name)));
		// FN is the full name. Without a name it is the company, which parse reads as a company.
		let full = [a.first_name.as_str(), a.last_name.as_str()]
			.into_iter()
			.filter(|s| !is_blank(s))
			.collect::<Vec<_>>()
			.join(" ");
		let full = full.trim();
		line(&format!(
			"FN:{}",
			escape(if full.is_empty() { &a.company } else { full })
		));
		if !a.company.is_empty() {
			line(&format!("ORG:{}", escape(&a.company)));
		}
		if !a.title.is_empty() {
			line(&format!("TITLE:{}", escape(&a.title)));
		}
		for i in 0..5 {
			let number = a.phones.get(i).map(String::as_str).unwrap_or("");
			if number.is_empty() {
				continue;
			}
			let pref = if i as i32 == a.show_phone { ",PREF" } else { "" };
			line(&format!(
				"{}{pref}:{}",
				phone_type(a.phone_labels.get(i).copied().unwrap_or(OTHER)),
				escape(number)
			));
		}
		let adr = ["", "", &a.address, &a.city, &a.state, &a.zip, &a.country];
		if adr.iter().any(|s| !s.is_empty()) {
			line(&format!(
				"ADR;TYPE=HOME:{}",
				adr.iter().map(|s| escape(s)).collect::<Vec<_>>().join(";")
			));
		}
		if !a.note.is_empty() {
			line(&format!("NOTE:{}", escape(&a.note)));
		}
		for (i, c) in a.custom.iter().take(4).enumerate() {
			if !c.is_empty() {
				line(&format!("X-PALM-CUSTOM{}:{}", i + 1, escape(c)));
			}
		}
		if a.category != 0 {
			if let Some(name) = category_name(a.category) {
				line(&format!("CATEGORIES:{}", escape(&name)));
			}
		}
		if a.is_private {
			line("CLASS:PRIVATE");
		}
		line("END:VCARD");
	}
	out
}

/// The TEL or EMAIL type of a phone label, with the Palm label kept on the way back.
fn phone_type(label: i32) -> &'static str {
	match label {
		EMAIL => "EMAIL;TYPE=INTERNET",
		WORK => "TEL;TYPE=WORK,VOICE",
		HOME => "TEL;TYPE=HOME,VOICE",
		FAX => "TEL;TYPE=WORK,FAX",
		MAIN => "TEL;TYPE=X-MAIN",
		PAGER => "TEL;TYPE=PAGER",
		MOBILE => "TEL;TYPE=CELL",
		_ => "TEL;TYPE=VOICE",
	}
}

/// Splits at whitespace as Java's "\\s+" regex: space, tab, line feed, vertical tab, form feed, return.
fn words(s: &str) -> Vec<&str> {
	s.split([' ', '\t', '\n', '\u{b}', '\u{c}', '\r'])
		.filter(|w| !w.is_empty())
		.collect()
}

/// Builds the address of one card. None when the card has no usable field.
fn build(props: &[Prop]) -> Option<Parsed> {
	let first = |n: &str| props.iter().find(|p| p.name == n);
	let text = |n: &str| first(n).map(|p| unescape(&value(p))).unwrap_or_default();
	let company = first("ORG").map(|p| parts(p, ';').swap_remove(0)).unwrap_or_default();
	let n = first("N").map(|p| parts(p, ';')).unwrap_or_default();
	let mut last = n.first().cloned().unwrap_or_default();
	let mut given = n.get(1).cloned().unwrap_or_default();
	if last.is_empty() && given.is_empty() {
		// An FN that repeats the company names the company only, so it gives no name.
		let full = text("FN");
		let full = full.trim();
		if !full.is_empty() && full != company.trim() {
			let w = words(full);
			last = w.last().copied().unwrap_or("").to_string();
			given = w[..w.len().saturating_sub(1)].join(" ");
		}
	}

	let mut entries = Vec::new();
	for p in props {
		if p.name != "TEL" && p.name != "EMAIL" {
			continue;
		}
		let mut number = unescape(&value(p));
		if p.name == "TEL" && number.get(..4).is_some_and(|h| h.eq_ignore_ascii_case("tel:")) {
			number = number[4..].to_string();
		}
		if number.is_empty() {
			continue;
		}
		let label = if p.name == "EMAIL" { EMAIL } else { tel_label(&types(p)) };
		entries.push(PhoneEntry {
			label,
			number,
			pref: is_pref(p),
		});
	}
	// The first five take slots 0 to 4. The other slots keep the default labels that no kept phone uses.
	let kept = &entries[..entries.len().min(5)];
	let mut phones = vec![String::new(); 5];
	let mut labels = vec![OTHER; 5];
	for (i, e) in kept.iter().enumerate() {
		phones[i] = e.number.clone();
		labels[i] = e.label;
	}
	let used: Vec<i32> = kept.iter().map(|e| e.label).collect();
	let free: Vec<i32> = [WORK, HOME, FAX, OTHER, EMAIL]
		.into_iter()
		.filter(|l| !used.contains(l))
		.collect();
	for (i, label) in labels.iter_mut().enumerate().skip(kept.len()) {
		*label = free[i - kept.len()];
	}

	let adrs: Vec<Vec<String>> = props
		.iter()
		.filter(|p| p.name == "ADR")
		.map(|p| parts(p, ';'))
		.collect();
	let adr = adrs.first().cloned().unwrap_or_default();
	let mut note_lines = Vec::new();
	let note = text("NOTE");
	if !note.is_empty() {
		note_lines.push(note);
	}
	for c in adrs.iter().skip(1) {
		let s = c
			.iter()
			.filter(|x| !is_blank(x))
			.cloned()
			.collect::<Vec<_>>()
			.join(", ");
		if !s.is_empty() {
			note_lines.push(format!("Address: {s}"));
		}
	}
	for e in entries.iter().skip(5) {
		note_lines.push(format!("{}: {}", LABELS[e.label as usize], e.number));
	}
	let at = |i: usize| adr.get(i).cloned().unwrap_or_default();
	let card = Address {
		last_name: last,
		first_name: given,
		title: text("TITLE"),
		company,
		phone_labels: labels,
		phones,
		address: at(2),
		city: at(3),
		state: at(4),
		zip: at(5),
		country: at(6),
		custom: (1..=4)
			.map(|i| {
				first(&format!("X-PALM-CUSTOM{i}"))
					.map(|p| unescape(&value(p)))
					.unwrap_or_default()
			})
			.collect(),
		note: note_lines.join("\n"),
		show_phone: kept.iter().position(|e| e.pref).unwrap_or(0) as i32,
		is_private: first("CLASS").is_some_and(|p| value(p).trim().eq_ignore_ascii_case("PRIVATE")),
		..Address::default()
	};
	if card.is_empty() {
		return None;
	}
	let category_name = first("CATEGORIES")
		.map(|p| parts(p, ',').swap_remove(0))
		.filter(|c| !c.is_empty());
	Some(Parsed {
		address: card,
		category_name,
	})
}

/// The Palm label of a TEL field, from its TYPE values. FAX wins over WORK and HOME.
fn tel_label(t: &[String]) -> i32 {
	let has = |x: &str| t.iter().any(|v| v == x);
	if has("FAX") {
		FAX
	} else if has("CELL") || has("MOBILE") {
		MOBILE
	} else if has("PAGER") {
		PAGER
	} else if has("MAIN") || has("X-MAIN") || has("COMPANY") {
		MAIN
	} else if has("WORK") {
		WORK
	} else if has("HOME") {
		HOME
	} else {
		OTHER
	}
}

fn types(p: &Prop) -> Vec<String> {
	p.params
		.get("TYPE")
		.map(|v| v.iter().map(|s| s.to_uppercase()).collect())
		.unwrap_or_default()
}

fn is_pref(p: &Prop) -> bool {
	types(p).iter().any(|t| t == "PREF") || p.params.contains_key("PREF")
}

/// The windows-1252 chars of bytes 0x80 to 0x9F; the bytes Microsoft left undefined keep their C1 char.
const CP1252_HIGH: [char; 32] = [
	'\u{20AC}', '\u{81}', '\u{201A}', '\u{192}', '\u{201E}', '\u{2026}', '\u{2020}', '\u{2021}', '\u{2C6}', '\u{2030}',
	'\u{160}', '\u{2039}', '\u{152}', '\u{8D}', '\u{17D}', '\u{8F}', '\u{90}', '\u{2018}', '\u{2019}', '\u{201C}',
	'\u{201D}', '\u{2022}', '\u{2013}', '\u{2014}', '\u{2DC}', '\u{2122}', '\u{161}', '\u{203A}', '\u{153}', '\u{9D}',
	'\u{17E}', '\u{178}',
];

fn cp1252(b: u8) -> char {
	if (0x80..0xA0).contains(&b) {
		CP1252_HIGH[(b - 0x80) as usize]
	} else {
		b as char
	}
}

#[derive(Clone, Copy)]
enum Charset {
	Utf8,
	Latin1,
	Cp1252,
}

/// The charset of a CHARSET name. Shortcut: UTF-8, US-ASCII, ISO-8859-1 and windows-1252 only, and UTF-8
/// for any other name. Upgrade path: the encoding_rs crate.
fn charset(name: &str) -> Charset {
	match name.to_ascii_uppercase().as_str() {
		"ISO-8859-1" | "ISO8859-1" | "ISO_8859_1" | "LATIN1" => Charset::Latin1,
		"WINDOWS-1252" | "CP1252" => Charset::Cp1252,
		_ => Charset::Utf8,
	}
}

fn decode(bytes: &[u8], cs: Charset) -> String {
	match cs {
		Charset::Latin1 => bytes.iter().map(|b| *b as char).collect(),
		Charset::Cp1252 => bytes.iter().map(|b| cp1252(*b)).collect(),
		Charset::Utf8 => String::from_utf8_lossy(bytes).into_owned(),
	}
}

/// The bytes of a char; an unmappable char gives "?", as Java's encoder.
fn encode(c: char, cs: Charset) -> Vec<u8> {
	match cs {
		Charset::Latin1 => vec![if (c as u32) < 256 { c as u8 } else { b'?' }],
		Charset::Cp1252 => vec![(0..=255u8).find(|b| cp1252(*b) == c).unwrap_or(b'?')],
		Charset::Utf8 => c.to_string().into_bytes(),
	}
}

/// The value of `p` with quoted-printable bytes decoded. Bytes decode as UTF-8 unless CHARSET names
/// another charset.
fn value(p: &Prop) -> String {
	let qp = p
		.params
		.get("ENCODING")
		.is_some_and(|v| v.iter().any(|e| e.eq_ignore_ascii_case("QUOTED-PRINTABLE")));
	if !qp {
		return p.value.clone();
	}
	let cs = charset(
		p.params
			.get("CHARSET")
			.and_then(|v| v.first())
			.map(String::as_str)
			.unwrap_or("UTF-8"),
	);
	decode_qp(&p.value, cs)
}

/// Decodes "=XX" to one byte. A "=" with no hex digits is a soft break that unfolding did not join.
fn decode_qp(s: &str, cs: Charset) -> String {
	let chars: Vec<char> = s.chars().collect();
	let mut bytes = Vec::new();
	let mut i = 0;
	while i < chars.len() {
		let c = chars[i];
		let hex = if c == '='
			&& i + 2 < chars.len()
			&& chars[i + 1].is_ascii_hexdigit()
			&& chars[i + 2].is_ascii_hexdigit()
		{
			u8::from_str_radix(&chars[i + 1..i + 3].iter().collect::<String>(), 16).ok()
		} else {
			None
		};
		if let Some(b) = hex {
			bytes.push(b);
			i += 3;
		} else if c == '=' {
			i += 1;
		} else {
			bytes.extend(encode(c, cs));
			i += 1;
		}
	}
	decode(&bytes, cs)
}

/// The structured parts of `p` split at `sep`, each unescaped. Never empty.
fn parts(p: &Prop, sep: char) -> Vec<String> {
	split(&value(p), sep).iter().map(|s| unescape(s)).collect()
}

/// Splits `s` at `sep` where `sep` is not escaped. The parts keep their escapes.
fn split(s: &str, sep: char) -> Vec<String> {
	let mut out = Vec::new();
	let mut cur = String::new();
	let mut it = s.chars();
	while let Some(c) = it.next() {
		if c == '\\' {
			cur.push(c);
			if let Some(n) = it.next() {
				cur.push(n);
			}
		} else if c == sep {
			out.push(std::mem::take(&mut cur));
		} else {
			cur.push(c);
		}
	}
	out.push(cur);
	out
}

/// Joins the continuation lines of `text`: a line that starts with a space or tab continues the line
/// before it, and a quoted-printable line that ends in "=" continues on the next line.
fn unfold(text: &str) -> Vec<String> {
	let mut out: Vec<String> = Vec::new();
	let mut soft = false;
	for raw in text.strip_prefix('\u{FEFF}').unwrap_or(text).split('\n') {
		let l = raw.strip_suffix('\r').unwrap_or(raw);
		if let Some(last) = out
			.last_mut()
			.filter(|_| soft || l.starts_with(' ') || l.starts_with('\t'))
		{
			if soft {
				last.pop();
				last.push_str(l);
			} else {
				last.push_str(&l[1..]);
			}
			soft = is_soft(last);
		} else if !is_blank(l) {
			out.push(l.to_string());
			soft = is_soft(l);
		}
	}
	out
}

fn is_soft(line: &str) -> bool {
	line.ends_with('=')
		&& line
			.split(':')
			.next()
			.unwrap_or("")
			.to_uppercase()
			.contains("QUOTED-PRINTABLE")
}

fn prop(line: &str) -> Option<Prop> {
	let mut quoted = false;
	let mut colon = None;
	for (i, c) in line.char_indices() {
		if c == '"' {
			quoted = !quoted;
		} else if c == ':' && !quoted {
			colon = Some(i);
			break;
		}
	}
	let colon = colon?;
	let head: Vec<&str> = line[..colon].split(';').collect();
	let mut params: HashMap<String, Vec<String>> = HashMap::new();
	for p in &head[1..] {
		if let Some(i) = p.find('=').filter(|i| *i > 0) {
			params
				.entry(p[..i].to_uppercase())
				.or_default()
				.extend(p[i + 1..].trim_matches('"').split(',').map(String::from));
		} else if !is_blank(p) {
			// A 2.1 parameter with no name: an encoding, else a type such as CELL.
			let word = p.trim().to_uppercase();
			let key = if ENCODINGS.contains(&word.as_str()) {
				"ENCODING"
			} else {
				"TYPE"
			};
			params.entry(key.to_string()).or_default().push(word);
		}
	}
	let name = head[0].rsplit('.').next().unwrap_or("").to_uppercase();
	Some(Prop {
		name,
		params,
		value: line[colon + 1..].to_string(),
	})
}
