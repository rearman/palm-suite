//! Import and Export, as Transfer.kt: iCalendar files, the Date Book backup file and vCard files.

use crate::address;
use crate::dialogs::{self, Icon};
use crate::view::Ctx;
use fltk::dialog::{FileDialogOptions, FileDialogType, NativeFileChooser};
use palm_core::backup::{self, Content};
use palm_core::{ical, vcard};
use std::collections::{HashMap, HashSet};
use std::path::PathBuf;

/// The key that finds a duplicate record: the Debug text of its dup_key, since the model types do not
/// derive Hash.
fn key<T: std::fmt::Debug>(t: &T) -> String {
	format!("{t:?}")
}

/// Exports the records of the view: the visible addresses as vCard in the Address Book, else the Date Book
/// as iCalendar or as the backup file. Returns the status text, or None when nothing was written.
pub fn export(ctx: &mut Ctx, addresses: bool) -> Option<String> {
	let mut c = NativeFileChooser::new(FileDialogType::BrowseSaveFile);
	c.set_title("Export");
	c.set_option(FileDialogOptions::SaveAsConfirm);
	if addresses {
		c.set_filter("vCard (*.vcf)\t*.vcf");
	} else {
		c.set_filter("iCalendar (*.ics)\t*.ics\nDate Book backup (*.json)\t*.json");
	}
	c.show();
	let typed = c.filename();
	if typed.as_os_str().is_empty() {
		return None;
	}
	let (path, text) = if addresses {
		let list = address::visible(ctx);
		let text = vcard::export(&list, |id| Some(ctx.store.category_name(id)));
		(with_ext(typed, "vcf"), text)
	} else {
		// The typed extension wins. Without one, the chosen filter gives the format.
		let ext = typed.extension().map(|e| e.to_string_lossy().to_lowercase());
		let as_json = match ext.as_deref() {
			Some("json") => true,
			Some("ics") => false,
			_ => c.filter_value() == 1,
		};
		let text = if as_json {
			backup_json(ctx)
		} else {
			let visible: Vec<_> = ctx
				.store
				.events
				.iter()
				.filter(|e| ctx.show_private || !e.is_private)
				.cloned()
				.collect();
			ical::export(&visible, jiff::Timestamp::now())
		};
		(with_ext(typed, if as_json { "json" } else { "ics" }), text)
	};
	if let Err(e) = std::fs::write(&path, text) {
		dialogs::message(
			"Palm Desktop",
			Icon::Warning,
			&format!("The file could not be saved.\n{e}"),
		);
		return None;
	}
	let name = path
		.file_name()
		.map_or(String::new(), |n| n.to_string_lossy().to_string());
	Some(format!("Exported to {name}."))
}

/// `path` with the extension `ext`, added when it has none of the formats of the dialog.
fn with_ext(path: PathBuf, ext: &str) -> PathBuf {
	let known = path
		.extension()
		.is_some_and(|e| ["ics", "json", "vcf"].contains(&e.to_string_lossy().to_lowercase().as_str()));
	if known {
		path
	} else {
		let mut s = path.into_os_string();
		s.push(format!(".{ext}"));
		PathBuf::from(s)
	}
}

/// The backup file of the store, with the same keys and values as the Android app.
fn backup_json(ctx: &Ctx) -> String {
	let s = &*ctx.store;
	let names = |list: &[palm_core::model::Category]| list.iter().map(|c| c.name.clone()).collect();
	let content = Content {
		events: s.events.clone(),
		todos: s.todos.clone(),
		memos: s.memos.clone(),
		addresses: s.addresses.clone(),
		todo_categories: names(&s.categories),
		memo_categories: names(&s.memo_categories),
		address_categories: names(&s.address_categories),
	};
	backup::write(&content, |id| s.category_name(id))
}

/// Imports a file the user picks: an iCalendar file, a vCard file or a backup file, by its first line. Shows
/// what was added.
pub fn import(ctx: &mut Ctx) {
	let mut c = NativeFileChooser::new(FileDialogType::BrowseFile);
	c.set_title("Import");
	c.set_filter("iCalendar (*.ics)\t*.ics\nDate Book backup (*.json)\t*.json\nvCard (*.vcf)\t*.vcf");
	c.show();
	let path = c.filename();
	if path.as_os_str().is_empty() {
		return;
	}
	let text = match std::fs::read_to_string(&path) {
		Ok(t) => t,
		Err(e) => {
			dialogs::message(
				"Palm Desktop",
				Icon::Warning,
				&format!("The file could not be read.\n{e}"),
			);
			return;
		}
	};
	let body = text.trim_start_matches('\u{feff}').trim_start();
	let upper = body.get(..15).unwrap_or(body).to_ascii_uppercase();
	let message = if upper.starts_with("BEGIN:VCALENDAR") {
		Ok(import_ics(ctx, &text))
	} else if upper.starts_with("BEGIN:VCARD") {
		Ok(import_vcard(ctx, body))
	} else {
		import_json(ctx, body)
	};
	match message {
		Ok(m) => dialogs::message("Palm Desktop", Icon::Info, &m),
		Err(_) => dialogs::message(
			"Palm Desktop",
			Icon::Warning,
			"This file is not a Date Book backup or an iCalendar file.",
		),
	}
}

fn import_ics(ctx: &mut Ctx, text: &str) -> String {
	let parsed = ical::parse(text, &jiff::tz::TimeZone::system());
	let have: HashSet<String> = ctx.store.events.iter().map(|e| key(&e.dup_key())).collect();
	let mut added = 0;
	for mut e in parsed.events.iter().cloned() {
		if have.contains(&key(&e.dup_key())) {
			continue;
		}
		ctx.store.save_event(&mut e);
		added += 1;
	}
	let mut m = format!(
		"Added {added} events. Skipped {} duplicate events.",
		parsed.events.len() - added
	);
	if parsed.simplified > 0 {
		m += &format!(" {} events were simplified to fit Date Book.", parsed.simplified);
	}
	m
}

/// Adds the cards of `text` as addresses. A category name is matched ignoring case, else Unfiled. A card
/// equal to one already stored, or to one earlier in the file, is skipped.
fn import_vcard(ctx: &mut Ctx, text: &str) -> String {
	let parsed = vcard::parse(text);
	let mut have: HashSet<String> = ctx.store.addresses.iter().map(|a| key(&a.dup_key())).collect();
	let mut added = 0;
	for p in &parsed {
		let cat = p
			.category_name
			.as_deref()
			.and_then(|n| {
				ctx.store
					.address_categories
					.iter()
					.find(|c| c.name.to_lowercase() == n.to_lowercase())
			})
			.map_or(0, |c| c.id);
		let mut a = palm_core::model::Address {
			id: 0,
			category: cat,
			uid: String::new(),
			..p.address.clone()
		};
		if !have.insert(key(&a.dup_key())) {
			continue;
		}
		ctx.store.save_address(&mut a);
		added += 1;
	}
	format!(
		"Added {added} addresses. Skipped {} duplicate addresses.",
		parsed.len() - added
	)
}

/// Adds the records of a backup file. Every record is read before the first write, so a bad record adds
/// nothing; a category that does not exist yet gets a provisional id (-1, -2, ...) and is made after the read.
fn import_json(ctx: &mut Ctx, text: &str) -> Result<String, String> {
	let mut made: Vec<((i32, String), String)> = Vec::new();
	let store = &*ctx.store;
	let content = backup::read(text, |name, app| {
		if let Some(c) = store
			.categories_for(app)
			.iter()
			.find(|c| c.name.to_lowercase() == name.to_lowercase())
		{
			return c.id;
		}
		let k = (app, name.to_lowercase());
		match made.iter().position(|(m, _)| *m == k) {
			Some(i) => -(i as i32 + 1),
			None => {
				made.push((k, name.to_string()));
				-(made.len() as i32)
			}
		}
	})?;
	let mut remap: HashMap<i32, i32> = HashMap::new();
	for (i, ((app, _), name)) in made.iter().enumerate() {
		remap.insert(-(i as i32 + 1), ctx.store.add_category(name, *app).id);
	}
	let real = |id: i32| {
		if id < 0 {
			remap.get(&id).copied().unwrap_or(0)
		} else {
			id
		}
	};
	let mut skipped = 0;
	let have: HashSet<String> = ctx.store.events.iter().map(|e| key(&e.dup_key())).collect();
	let mut ne = 0;
	for mut e in content.events {
		if have.contains(&key(&e.dup_key())) {
			skipped += 1;
			continue;
		}
		ctx.store.save_event(&mut e);
		ne += 1;
	}
	let have: HashSet<String> = ctx.store.todos.iter().map(|t| key(&t.dup_key())).collect();
	let mut nt = 0;
	for mut t in content.todos {
		t.category = real(t.category);
		if have.contains(&key(&t.dup_key())) {
			skipped += 1;
			continue;
		}
		ctx.store.save_todo(&mut t);
		nt += 1;
	}
	let have: HashSet<String> = ctx.store.memos.iter().map(|m| key(&m.dup_key())).collect();
	let mut nm = 0;
	for mut m in content.memos {
		m.category = real(m.category);
		if have.contains(&key(&m.dup_key())) {
			skipped += 1;
			continue;
		}
		ctx.store.save_memo(&mut m);
		nm += 1;
	}
	let have: HashSet<String> = ctx.store.addresses.iter().map(|a| key(&a.dup_key())).collect();
	let mut na = 0;
	for mut a in content.addresses {
		a.category = real(a.category);
		if have.contains(&key(&a.dup_key())) {
			skipped += 1;
			continue;
		}
		ctx.store.save_address(&mut a);
		na += 1;
	}
	Ok(format!(
		"Added {ne} events, {nt} To Do items, {nm} memos and {na} addresses. Skipped {skipped} duplicate records."
	))
}

#[cfg(test)]
mod tests {
	use super::*;

	#[test]
	fn an_export_name_takes_the_extension_of_its_format_when_it_has_none() {
		assert_eq!(with_ext(PathBuf::from("a/b"), "ics"), PathBuf::from("a/b.ics"));
		assert_eq!(with_ext(PathBuf::from("a/b.JSON"), "ics"), PathBuf::from("a/b.JSON"));
		assert_eq!(with_ext(PathBuf::from("a/b.txt"), "vcf"), PathBuf::from("a/b.txt.vcf"));
	}
}
