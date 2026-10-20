# What

This Repository hosts two packages: Palm-Launcher and Datebook.  Together,
these provide kotlin-based work-and-look-alike versions of the PalmOS 3.5
DateBook, ToDo, MemoPad, and Address Book apps, as well as a launcher after
the PalmOS 3.5 home page.  No PalmOS code was referenced in the making of
these apks, only manuals, images, my own physical Palm IIIxe, and my
experience using said device.

It also hosts Palm Desktop for Windows and Linux, after Palm Desktop 3.1.1,
and `palmsync`, the sync server that keeps the phone and the desktop in step.

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

## Address Book

- Address List shows the names by category. Use the category pop-up to select
  a category.
- Names show as "Last, First" or "Company, Last". Select Options > Preferences
  to set List By.
- Each name shows the number you choose, with a letter for its label: W (Work),
  H (Home), F (Fax), O (Other), E (E-mail), M (Main or Mobile) or P (Pager).
- Look Up field: type a name to go to the first match.
- Address View: tap a number to call it, tap Msg to send a text message, tap an
  e-mail address to write an e-mail, and tap the address to open the map. The
  phone's own apps do these actions, as the stock Contacts app does.
- Touch and hold a value in Address View to copy it.
- Address Edit has these fields:
	- Last name, First name, Title and Company.
	- Five phone fields. Each has a label: Work, Home, Fax, Other, E-mail, Main,
	  Pager or Mobile.
	- Address, City, State, Zip Code and Country.
	- Four custom fields. Select Options > Rename Custom Fields to change their
	  names.
- Details: Show in List, Category and Private. Show in List sets which phone
  number the list shows.
- Each record has a note.
- Share Address, Share Category and Share Business Card send a vCard (.vcf) file
  through the Android share sheet. They replace the Palm Beam commands. Select
  Record > Select Business Card to choose your card.
- Import vCard and Export vCard in the Record menu read and write .vcf files.
  Opening a .vcf file with Address imports it. Duplicate records are skipped.
- Phone Lookup in the Edit menu (Ctrl+L) inserts a name and a number. It works
  in all four apps.
- Address Book has its own categories.
- Address Book records stay on the phone. They do not sync yet.

## All four apps

- One APK, four icons. Date Book and To Do List use the same records.
- Type a character with nothing selected to start a new event, item or memo.
  Press Backspace in an empty one to delete it. In the Address List, the
  character goes to the Look Up field.
- Find searches the descriptions and notes of all records and the addresses.
- Font (small, normal and large Palm sizes), Security (show, mask or hide
  private records) and Purge.
- Export Backup and Import Backup save and load all records, including addresses, as a JSON file. Import skips a record that is the same as a record on the device.
  Delete can save an archive copy of the record first.
- The Palm OS font and the Green LCD color on all screens. All screens use
  the proportions of the Palm IIIxe, 160 Palm pixels wide.
- ShortCuts: type ^ or press Ctrl+Space, then a ShortCut name. When the
  letters after ^ match a full name, the ^ and the name change to the
  ShortCut text. For example, ^me gives Meeting. Options > ShortCuts...
  edits the list. Export Backup does not include ShortCuts.
- The category pop-ups and Edit Categories list the categories in
  alphabetical order. All is first and Unfiled is last.

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
  and run it. It installs Palm Desktop for your user account. The installer
  asks whether to add a Start menu entry and a desktop shortcut. If the
  official Palm Desktop is installed, clear the desktop shortcut box: both
  shortcuts are named "Palm Desktop". Run a newer MSI to update. Your
  records stay.
- Linux: download `palm-desktop-linux.tar.gz`, unpack it and run
  `palm-desktop/bin/palm-desktop`. To update, unpack a newer one over it.
- Both packages hold their own Java runtime.
- Gentoo: see [Gentoo](#gentoo).
- The app keeps its records in `desktop-data.json` in your config folder
  (`%APPDATA%\palm-suite` on Windows, `~/.config/palm-suite` on Linux). It
  works offline.
- To set up sync, choose Tools > Options..., open the Sync tab, enter the
  server, the device token and the sync passphrase, and click Set Up. The
  app saves the token and the sync key in a file that only your user
  account can read.
- The app syncs with `palmsync` at start, 30 seconds after a change, every
  hour, and when you choose Tools > Sync Now.
- When the same record changed on two devices, the app keeps both copies.
- The app uses the fonts and sizes that Palm Desktop 3.1.1 draws with, in
  their TrueType versions.
- Dialogs, lists, buttons, the time labels and the calendars use MS Sans
  Serif 8 pt, through its TrueType version Microsoft Sans Serif (11 px).
  Today's date is bold.
- The status bar uses Microsoft Sans Serif 12 px, and the launcher labels
  use Segoe UI 12 px.
- Arial: the Date view title (bold 13 px), the To Do List and Memo Pad
  captions (bold 14 px), the Day, Week and Month event text (13 px) and the
  Memo Pad text (12 px).
- On Linux, the app uses these Microsoft fonts when they are installed.
  Each font it cannot find falls back to Arial, then to Liberation Sans, so
  all text uses one family.
- The toolbar, launcher, window and marker icons are the Palm Desktop 3.1.1
  bitmaps. See [Palm Desktop artwork](#palm-desktop-artwork).
- Each category list ends with Edit Categories..., which opens the category
  editor of the To Do List or the Memo Pad.
- The category lists show the categories in alphabetical order. Edit
  Categories... stays last in each list.

### Gentoo

The folder `gentoo/` is a Portage overlay with one package,
`app-office/palm-desktop-bin`. It installs the jars of a `desktop-v*`
release (`palm-desktop-jars.tar.gz`), a `palm-desktop` command and a menu
entry. It runs on the system Java 17 or later and uses Liberation Sans. The
first release with the jars is `desktop-v0.8`.

Portage can sync an overlay only from the root of a git repository, and the
overlay is a folder of this one. So clone the repository, and point Portage
at the folder. As root:

```sh
git clone https://github.com/rearman/palm-suite.git /var/db/repos/palm-suite-src
cat > /etc/portage/repos.conf/palm-suite.conf <<'EOF'
[palm-suite]
location = /var/db/repos/palm-suite-src/gentoo
auto-sync = no
EOF
mkdir -p /etc/portage/package.accept_keywords /etc/portage/package.license
echo 'app-office/palm-desktop-bin ~amd64' > /etc/portage/package.accept_keywords/palm-desktop
echo 'app-office/palm-desktop-bin all-rights-reserved' > /etc/portage/package.license/palm-desktop
emerge --ask app-office/palm-desktop-bin
```

On arm64, write `~arm64` in place of `~amd64`. The license line is necessary
because the project has no license file and the icons are Palm artwork (see
[Palm Desktop artwork](#palm-desktop-artwork)).

To update:

```sh
git -C /var/db/repos/palm-suite-src pull
emerge --ask --update app-office/palm-desktop-bin
```

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
