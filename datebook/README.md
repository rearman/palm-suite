# Date Book, To Do List and Memo Pad

Four Android apps after the Palm OS 3.5 Date Book, To Do List, Memo Pad and Address Book. One APK installs all four apps. Each app has its own icon. The Date Book and To Do List apps use the same records, so the Date Book Agenda view shows your To Do items. All four apps keep their records in one file on the phone.

## Build the APK with GitHub

1. Put all files from this folder into the `rearman/datebook` repository. Keep the folder structure. The `.github` folder must go in too.
2. Open the **Actions** tab in the repository.
3. Select **Build APK**, then select **Run workflow**. The workflow also runs after each push to `main`.
4. When the run is complete, open it. Download **datebook.apk** from the **Artifacts** list, then install it on your phone.

The project has a fixed signing key (`app/debug.keystore`). Thus a new build installs over the old one and keeps your data.

## Features

### Date Book

- Day view: time lines from the start time to the end time, time bars for overlapping events, untimed events at the top, and note, alarm and repeat icons. Tap a line and type to make an event. With Show Time Bars, an event that ends between hours gets an empty line at its end time.
- Week view: a seven-day time grid. Tap an event to see its description. Tap it again to open its day. Touch and hold an event to edit it.
- Month view: marks for timed events, untimed events (+) and daily repeating events (dashed line).
- Agenda view: the events for one day, plus all open To Do items.
- Swipe left for the next day, week or month. Swipe right for the previous one. In Day view, a swipe moves one day and the arrows move one week.
- Event Details: time, date, alarm, repeat, private and note.
- Repeat: daily, weekly (on selected days), monthly by day or by date, yearly, with an optional end date. For changes or deletions of a repeating event, select Current, Future or All. Future applies to this and all later occurrences. Earlier occurrences stay.
- Alarms ring while the app is closed. Preferences set the alarm preset, the number of reminders and the interval. Snooze adds 5 minutes.
- In the Day view, type a character when no event is selected to start a new event. The event starts at the next hour (today) or at the Day Start time, and the text you type is its description.
- Press Backspace in an empty event to delete it. An event with a note or a repeat stays.
- Export iCalendar saves all events as an .ics file for Google Calendar, Outlook or Apple Calendar. Import iCalendar adds the events of an .ics file and skips events that are already in Date Book. Times change to the time zone of the device. A timed event that goes past midnight becomes one event for each day it covers. A repeat rule that Date Book cannot show gives a one-time event. The Import dialog shows how many events were simplified.

### To Do List

- Priority 1 to 5, due date, category, private and note.
- Show options: sort order, completed items, only due items, completion date, and the columns to show.
- Any number of categories, with names of any length.
- Type a character when no item is selected to start a new item with that text.
- Press Backspace in an empty item to delete it. An item with a note stays.

### Memo Pad

- Memo List shows the memos. Use the All or category pop-up to select a category.
- The Memo List and the Memo view have a Palm scroll bar on the right.
- Each memo title is numbered. The title is the first line of the memo.
- Tap New to make a memo.
- Sort: manual or alphabetic. Select Options, then Preferences to set the sort order.
- Manual sort: touch and hold a memo, then drag it to move it.
- Memo view shows "Memo N of M". The page has a full page of dotted lines.
- Memo view has the Done and Details buttons.
- Details: Category, Private and Delete.
- The text is saved as you type. An empty memo is deleted.
- Memo Pad has its own categories. Select Edit Categories in Options. The Edit Categories form has OK, New, Rename and Delete buttons. Tap a name to select it.
- Find searches the memos.
- Export Backup and Import Backup include the memos.
- In the Memo List, type a character to start a new memo with that text.
- Press Backspace in an empty memo to delete it and go back to the Memo List.

### Address Book

- Address List shows the names by category. Use the category pop-up to select a category.
- Names show as "Last, First" or "Company, Last". Select Options > Preferences to set List By.
- Each name shows the number you choose, with a letter for its label: W (Work), H (Home), F (Fax), O (Other), E (E-mail), M (Main or Mobile) or P (Pager).
- Look Up field: type a name to go to the first match.
- Address View: tap a number to call it, tap Msg to send a text message, tap an e-mail address to write an e-mail, and tap the address to open the map. The phone's own apps do these actions, as the stock Contacts app does.
- Touch and hold a value in Address View to copy it.
- Address Edit has these fields: Last name, First name, Title and Company.
- Address Edit has five phone fields. Each has a label: Work, Home, Fax, Other, E-mail, Main, Pager or Mobile.
- Address Edit has these fields: Address, City, State, Zip Code and Country.
- Address Edit has four custom fields. Select Options > Rename Custom Fields to change their names.
- Details: Show in List, Category and Private. Show in List sets which phone number the list shows. Each record has a note.
- Share Address, Share Category and Share Business Card send a vCard (.vcf) file through the Android share sheet. They replace the Palm Beam commands. Select Record > Select Business Card to choose your card.
- Import vCard and Export vCard in the Record menu read and write .vcf files. Opening a .vcf file with Address imports it. Duplicate records are skipped.
- Phone Lookup in the Edit menu (Ctrl+L) inserts a name and a number. It works in all four apps.
- Address Book has its own categories.
- Address Book records and their categories sync like the other records.

### Both

- The view icons at the bottom of Date Book select Day, Week, Month or Agenda.
- A tap on the title tab opens the Record, Edit and Options menus, as on the Palm III. Tap outside the menus to close them. With the menus open, type a command letter (shown at the right of a command) to run that command. At any time, press Ctrl and the command letter on a keyboard.
- Tap outside a line in edit to save it and end the edit. Tap another line to edit that line.
- The apps hide the Android status bar. Swipe down from the top edge of the screen to show it for a moment.
- **Find** (the magnifier icon at the bottom right) searches the descriptions and notes of all records and the addresses.
- Font, Security (show, mask or hide private records) and Purge. The screen is always the Green LCD color.
- All screens use the proportions of the Palm IIIxe: the screen width is 160 Palm pixels, and the Font option selects the Palm small, normal or large font size. Select Font shows three A buttons (small, normal and large) and no sample text. All text uses the Palm OS font.
- On a screen that is taller than it is wide, the keyboard stays open below the app, and the app uses the area above the keyboard. On a square screen, the keyboard opens only when you select a line of text.
- Export Backup and Import Backup save and load all records, including addresses, as a JSON file. Import skips a record that is the same as a record on the device.
- Delete Event, Delete To Do and Delete Memo have a Save archive copy on PC check box. When it is set, the app saves a backup file of the record before it deletes the record.
- ShortCuts: type ^ or press Ctrl+Space, then a ShortCut name. When the letters after ^ match a full name, the ^ and the name change to the ShortCut text. Options > ShortCuts... edits the list. Export Backup does not include ShortCuts.
- Sync: Options > Sync... connects the apps to your own palmsync server (see sync-server/README.md). Enter the server address, the device token and the sync passphrase. Use the same passphrase on every device. The apps sync about 30 seconds after a change and every hour, and Sync Now syncs at once. The server stores only encrypted records. When the same record changed on two devices, the apps keep both copies, as Palm HotSync did.

## Limits

- Graffiti and pen gestures are not included.
- Beam is not included.
- Android 8.0 or later is necessary.

## Credits

The Palm OS fonts (app/src/main/assets/fonts) are by Damien Guard, a recreation of the Palm OS system fonts: https://damieng.com/typography/palmos-font/. The license is Creative Commons Attribution-ShareAlike 3.0 (https://creativecommons.org/licenses/by-sa/3.0/). The font files are not changed.
