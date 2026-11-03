// The Options dialog of Dialogs.kt: the General tab (the startup view and the delete question) and the Date Book tab
// (the work day, the first day of the week and the alarm preset), as desktop-rs/app/src/options.rs.
#pragma once

namespace palm::settings {
class Settings;
}

namespace palm::options {

// Shows Options and saves the values on OK; true when they were saved.
bool show(settings::Settings& settings, bool h24);

} // namespace palm::options
