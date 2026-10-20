# What

This Repository hosts two packages: Palm-Launcher and Datebook.  Together,
these provide kotlin-based work-and-look-alike versions of the PalmOS 3.5
DateBook, ToDo, and MemoPad apps, as well as a launcher after the PalmOS 3.5
home page.  No PalmOS code was referenced in the making of these apks, only
manuals, images, my own physical Palm IIIxe, and my experience using said
device.

# Why

After buying the Palm IIIxe on a whim some months ago, I became enamored of
the device, its interface, its seamless interconnection.  It came from a time
when only Men with TASTE were allowed to design, and they designed for
themselves.  It truly puts to shame the absolute JEET SLOP Google tries to
pass off as "PIM" software.

Alas, dailying a nearly 30 year old device is not practical, especially with
[Palm Day](https://palmdb.net/about/palm-day) looming ever nearer.  This suite
is my answer: remake Android in Palm's image.

# How

If you are using a device with an aspect ratio less than 1:1.25, the app
assumes you are using something like a [Titan
2](https://www.unihertz.com/products/titan-2), with a physical keyboard always
available.  The launcher and apps therefore take up the full screen.  For
devices with a larger aspect ratio, the device is assumed to be a slab, and
the keyboard is forced to be displayed (as much as possible).  If you are in
the latter camp, I reccommend using
[Graffiti for Android](https://play.google.com/store/apps/details?id=com.access_company.graffiti_pro&hl=en-US)
as your keyboard.

The forcing of the keyboard is to a. Go towards a square aspect ratio, and b. allow some features from PalmOS:
1. Type a letter on the homescreen to scroll to the first entry starting with that letter
1. Type on a ToDo/Memo page with nothing selected to make a new entry
1. Use control-letter shortcuts to access menu options from any screen
	- Tap the Darkened part of the header of any page in the suite to open the menu, and see the shortcut.
	- For keyboards without a `control` key, you can enter the letter of the shortcut when the menu is open to select that entry.
	- For keyboards with a `control` key, hold down control while pressing the letter, even without the menu open.
	- Sadly, the Graffiti for Android keyboard listed above does not have the Palm's gesture (displayed next to the letter in the menu) implemented.  An issue for another time.

# Features

## Launcher

- One A to Z list of all apps, as on the Palm III Applications screen. Show
  it as a grid (3 or 4 columns) or as a list (2 columns).
- Categories: All, Games, Main, System, Utilities and Unfiled. Use the
  category pop-up at the top right to select a category, or to add, rename
  and delete categories. Press Home while the launcher shows to go to the
  next category.
- A dock of 4 apps at the bottom of the screen.
- Touch and hold an app to change its category, put it in a dock slot, see
  its Info or delete it.
- The title tab shows the time. Tap it to open the menus:
	- App: Category, Info and Delete.
	- Options: View as List or Grid, Grid Columns, Wallpaper, Text Size, Icon
	  Size and Change Wallpaper.
- The battery gauge of the Palm IIIxe, with a bolt while the phone charges.
- The Android status bar is hidden, and the header goes to the top edge of
  the screen. Swipe down on the header, or on the top of the app list, to
  open the notifications.
- Type a letter to scroll to the first app with that letter.

## Date Book

- Day, Week, Month and Agenda views. Select one with the view icons at the
  bottom. Swipe left or right for the next or previous day, week or month.
- Day view: time lines, time bars for overlapping events, untimed events at
  the top, and note, alarm and repeat icons. Tap a line and type to make an
  event.
- Week view: a seven-day time grid with events in the Palm 50% gray.
- Agenda view: the events for one day, plus all open To Do items.
- Event Details: time, date, alarm, repeat, private and note.
- Repeat: daily, weekly, monthly by day or by date, and yearly, with an
  optional end date. Change or delete the Current, Future or All occurrences.
- Alarms ring while the app is closed, with reminders and Snooze.
- Export iCalendar and Import iCalendar save and load events as .ics files,
  for Google Calendar, Outlook and Apple Calendar.

## To Do List

- Priority 1 to 5, due date, category, private and note.
- Show Options: sort order, completed items, only due items, completion date
  and the columns to show.
- Up to 15 categories.

## Memo Pad

- The Memo List shows numbered memo titles, by category. The title is the
  first line of the memo.
- Manual or alphabetic sort. In a manual sort, touch and hold a memo and drag
  it to move it.
- The text is saved as you type. An empty memo is deleted.
- Memo Pad has its own categories.

## All three apps

- One APK, three icons. Date Book and To Do List use the same records.
- Type a character with nothing selected to start a new event, item or memo.
  Press Backspace in an empty one to delete it.
- Find searches the descriptions and notes of all records.
- Font (small, normal and large Palm sizes), Security (show, mask or hide
  private records) and Purge.
- Export Backup and Import Backup save and load all records as a JSON file. Import skips a record that is the same as a record on the device.
  Delete can save an archive copy of the record first.
- The Palm OS font and the Green LCD color on all screens. All screens use
  the proportions of the Palm IIIxe, 160 Palm pixels wide.
- ShortCuts: type ^ or press Ctrl+Space, then a ShortCut name. When the
  letters after ^ match a full name, the ^ and the name change to the
  ShortCut text. For example, ^me gives Meeting. Options > ShortCuts...
  edits the list. Export Backup does not include ShortCuts.

## Sync

- The apps sync through `palmsync` on your own VPS (Options > Sync...).
- Records are encrypted on the device. The server stores only ciphertext.
- When a record changed on two devices, the apps keep both copies, as Palm
  HotSync did.
- `sync-server/` holds `palmsync`. See `sync-server/README.md` to install the
  server and `sync-server/PROTOCOL.md` for the protocol.

## Desktop

Palm Desktop for Windows and Linux follows Palm Desktop 3.1.1. It has the Date
Book (Day, Week and Month views), the To Do List and the Memo Pad. It also has
categories, private records, Find, Purge, Import and Export, and Print.
Import and Export use iCalendar files and the Date Book backup file.

- Windows: download `palm-desktop-windows.msi` from a `desktop-v*` release
  and run it. It installs Palm Desktop for your user account, with a Start
  menu entry and a desktop shortcut. Run a newer MSI to update. Your
  records stay.
- Linux: download `palm-desktop-linux.tar.gz`, unpack it and run
  `palm-desktop/bin/palm-desktop`.
- Both packages hold their own Java runtime.
- The app keeps its records in `desktop-data.json` in your config folder
  (`%APPDATA%\palm-suite` on Windows, `~/.config/palm-suite` on Linux). It
  works offline.
- To set up sync, choose Tools > Sync Setup... and enter the server, the
  device token and the sync passphrase. The app saves the token and the sync
  key in a file that only your user account can read.
- The app syncs with `palmsync` at start, 30 seconds after a change, every
  hour, and when you choose Tools > Sync Now.
- When the same record changed on two devices, the app keeps both copies.
- The app uses the fonts of Palm Desktop 3.1.1. Dialogs, lists and buttons
  use MS Sans Serif 8 pt, through its TrueType version Microsoft Sans Serif.
  The launcher labels, the Date view title and the Day and Week views use
  Arial. On Linux, Liberation Sans replaces both fonts.
- The toolbar, launcher, window and marker icons are the Palm Desktop 3.1.1
  bitmaps. See [Palm Desktop artwork](#palm-desktop-artwork).

# Palm Desktop artwork

The icons in `desktop/src/main/resources/com/rearman/palmdesktop/icons/` and
`desktop/packaging/palm-desktop.ico` come from Palm Desktop 3.1.1 for Windows
(`palm.exe`, `date20.dll`, `todo20.dll`, `memo20.dll` and `addr20.dll`). They are Copyright © 2000 Palm, Inc. or its
subsidiaries. All rights reserved by their current rights holders.

This project is not affiliated with the former Palm Computing company or its
current rights holders in any official capacity. The project treats Palm
Desktop 3.1.1 as abandonware and uses its artwork only to match the original
look. The artwork comes without guarantee or support.

If you hold the rights to this artwork and want it removed, open an issue on
this repository and it will be removed.
