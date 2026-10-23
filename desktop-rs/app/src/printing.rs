//! Print, as Printing.kt: the title of the view in bold, then its lines in 10 pt, wrapped at the page width
//! and split into pages. The system print dialog sets the printer and the paper.

use crate::theme;
use fltk::dialog;
use fltk::draw;
use fltk::printer::Printer;

/// `text` cut into rows of at most `width` px, at the spaces where it can, as LineBreakMeasurer does. A new
/// line starts a new row; a word wider than the row is cut by its characters.
pub fn wrap(text: &str, width: i32, measure: &dyn Fn(&str) -> i32) -> Vec<String> {
	let mut out = Vec::new();
	for para in text.split('\n') {
		if para.is_empty() {
			out.push(String::new());
			continue;
		}
		let mut line = String::new();
		for word in para.split_inclusive(' ') {
			if measure(format!("{line}{word}").trim_end()) <= width {
				line.push_str(word);
				continue;
			}
			if !line.is_empty() {
				out.push(line.trim_end().to_string());
				line.clear();
			}
			// A word wider than the row is cut by its characters.
			let mut rest = word.to_string();
			while measure(rest.trim_end()) > width && rest.chars().count() > 1 {
				let mut n = rest.chars().count() - 1;
				while n > 1 && measure(&rest.chars().take(n).collect::<String>()) > width {
					n -= 1;
				}
				out.push(rest.chars().take(n).collect());
				rest = rest.chars().skip(n).collect();
			}
			line = rest;
		}
		if !line.trim_end().is_empty() || out.is_empty() {
			out.push(line.trim_end().to_string());
		}
	}
	out
}

/// The font of the title (bold, 12 pt) or of a line (10 pt).
fn set_font(bold: bool) {
	let fonts = theme::fonts();
	draw::set_font(
		if bold { fonts.sans_bold } else { fonts.sans },
		if bold { 12 } else { 10 },
	);
}

/// Prints `title` and `lines` through the system print dialog. A canceled dialog prints nothing.
pub fn print(title: &str, lines: &[String]) {
	let mut p = Printer::default();
	if p.begin_job(0).is_err() {
		return;
	}
	if let Err(e) = p.begin_page() {
		p.end_job();
		dialog::alert_default(&format!("The print job failed.\n{e}"));
		return;
	}
	let (pw, ph) = p.printable_rect();
	// The rows are measured on the printer, in its fonts.
	let measure = |bold: bool| {
		move |s: &str| {
			set_font(bold);
			draw::width(s).ceil() as i32
		}
	};
	let mut rows: Vec<(bool, String)> = wrap(title, pw, &measure(true)).into_iter().map(|r| (true, r)).collect();
	for l in lines {
		rows.extend(wrap(l, pw, &measure(false)).into_iter().map(|r| (false, r)));
	}
	let mut y = 0;
	for (bold, text) in rows {
		set_font(bold);
		let h = draw::height();
		if y + h > ph && y > 0 {
			let _ = p.end_page();
			if let Err(e) = p.begin_page() {
				p.end_job();
				dialog::alert_default(&format!("The print job failed.\n{e}"));
				return;
			}
			y = 0;
			set_font(bold);
		}
		draw::set_draw_color(fltk::enums::Color::Black);
		draw::draw_text(&text, 0, y + h - draw::descent());
		y += h;
	}
	let _ = p.end_page();
	p.end_job();
}

#[cfg(test)]
mod tests {
	use super::*;

	#[test]
	fn rows_break_at_spaces_and_cut_a_long_word() {
		// One pixel per character.
		let m = |s: &str| s.chars().count() as i32;
		assert_eq!(wrap("one two three", 7, &m), ["one two", "three"]);
		assert_eq!(wrap("a\n\nb", 10, &m), ["a", "", "b"]);
		assert_eq!(wrap("abcdefghij", 4, &m), ["abcd", "efgh", "ij"]);
		assert_eq!(wrap("", 4, &m), [""]);
	}
}
