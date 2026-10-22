//! Facts of the system that the app reads once.

/// True when the system shows the time in 24-hour form. On Windows the short time format comes from the
/// Regional settings in the registry, as Main.kt reads it.
pub fn uses_24_hours() -> bool {
	#[cfg(windows)]
	{
		use std::os::windows::process::CommandExt;
		const CREATE_NO_WINDOW: u32 = 0x0800_0000;
		let out = std::process::Command::new("reg")
			.args(["query", r"HKCU\Control Panel\International", "/v", "sShortTime"])
			.creation_flags(CREATE_NO_WINDOW)
			.output();
		if let Ok(out) = out {
			let text = String::from_utf8_lossy(&out.stdout);
			if let Some(format) = text.lines().find_map(|l| l.split("REG_SZ").nth(1)).map(str::trim) {
				return format.contains('H');
			}
		}
	}
	// Shortcut: elsewhere the locale name decides, and only the United States English locales use the
	// 12-hour clock. Upgrade path: read LC_TIME's t_fmt through nl_langinfo.
	let locale = ["LC_ALL", "LC_TIME", "LANG"]
		.iter()
		.find_map(|k| std::env::var(k).ok().filter(|v| !v.is_empty()));
	match locale {
		Some(l) => !(l.starts_with("en_US") || l == "C" || l == "POSIX"),
		None => false,
	}
}
