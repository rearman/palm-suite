// The desktop settings in desktop.properties, as Settings.kt: the Java .properties format, so the Swing app and the
// Rust app share the file. The token and the key are in it, so on Linux only the user can read it. The records in
// desktop-data.json are plain text, as in Palm Desktop.
#pragma once

#include <expected>
#include <filesystem>
#include <map>
#include <string>
#include <string_view>

namespace palm::settings {

// The name of the settings file.
inline constexpr std::string_view FILE_NAME = "desktop.properties";

// %APPDATA%\palm-suite, else $XDG_CONFIG_HOME/palm-suite, else ~/.config/palm-suite. An empty variable counts as unset.
std::filesystem::path config_dir();

// The key-value pairs of a .properties text, sorted by key.
using Props = std::map<std::string, std::string>;

// The settings of one file. Each set saves the file at once.
class Settings {
public:
	// The settings in `dir`. A missing or unreadable file gives no settings, as Settings.kt.
	static Settings open(const std::filesystem::path& dir);

	std::string get(std::string_view key, std::string_view def) const;
	std::expected<void, std::string> set(std::string_view key, std::string_view value);
	std::expected<void, std::string> remove(std::string_view key);

	int get_int(std::string_view key, int def) const;
	std::expected<void, std::string> set_int(std::string_view key, int value);

	// Only "true" and "false" count, as Kotlin's toBooleanStrictOrNull.
	bool get_bool(std::string_view key, bool def) const;
	std::expected<void, std::string> set_bool(std::string_view key, bool value);

private:
	Settings(std::filesystem::path path, Props props);

	std::expected<void, std::string> save() const;

	std::filesystem::path path;
	Props props;
};

// Reads the key-value pairs of a .properties text, as java.util.Properties.load. The text is UTF-8.
Props parse(std::string_view text);

// The .properties text of `props`, as java.util.Properties.store: a comment line, a date line, then one escaped
// key=value line each. Chars outside printable ASCII become \uXXXX, one per UTF-16 unit.
std::string store(const Props& props, std::string_view comment, std::string_view stamp);

} // namespace palm::settings
