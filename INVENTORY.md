# Android UI inventory

Every screen, dialog, control, menu command, and gesture of the Android apps (Date Book, To Do List, Memo Pad, Address Book, and the launcher), for checking each against the Palm original. A check from memory does not count: compare with the Palm IIIxe handbook (manualslib 754038), the Palm OS 3.5 resources, or a Palm OS emulator.

- **Look**: the item matches the Palm reference in position, size, font, and wording. **Works**: tapping or using it does what the Palm does, and what the README says.
- Each cell holds the date of the check and its method: `S` screenshot compared with the reference, `D` tried on a device, `T` a test covers it. Example: `2026-10-30 D`. An empty cell is unchecked.
- **Notes** holds a found difference until it is fixed, or the reason a difference is kept.
- A new screen, dialog, control, or menu command gets its row in the same change. `python3 tools/ui_inventory.py --check` lists the controls in the code that have no row here.
- When a fault is found in one place, add its class to **Rules found from faults** and check the whole parity table for it.

## Date Book

### Day view

Palm reference: Date Book: Day View. Code: `MainActivity.buildDay`.

| Kind   | Item                                        | Code       | Look | Works | Notes |
| ------ | ------------------------------------------- | ---------- | ---- | ----- | ----- |
| screen | Title tab: date (tap opens the menus)       | tab        |      |       |       |
| button | Week arrows ◀ ▶                             | buildDay   |      |       |       |
| button | Day buttons S M T W T F S                   | buildDay   |      |       |       |
| field  | Event line: tap & type a description        | rowEdit    |      |       |       |
| button | Time of a line: Set Time, or new event      | timeTapped |      |       |       |
| icon   | Note icon                                   | noteIcon   |      |       |       |
| icon   | Alarm icon: Event Details                   | buildDay   |      |       |       |
| icon   | Repeat icon: Event Details                  | buildDay   |      |       |       |
| icon   | Gray bar of a masked record: Private Record | revealAsk  |      |       |       |
| icon   | View icons: Day, Week, Month, Agenda        | viewIcons  |      |       |       |
| button | New                                         | buildDay   |      |       |       |
| button | Details                                     | buildDay   |      |       |       |
| alert  | Select Event (Details with no event)        | buildDay   |      |       |       |
| button | Go To: Go to Date                           | buildDay   |      |       |       |
| icon   | Find                                        | render     |      |       |       |

### Week view

Palm reference: Date Book: Week View. Code: `MainActivity.buildWeek`.

| Kind   | Item                                                           | Code      | Look | Works | Notes |
| ------ | -------------------------------------------------------------- | --------- | ---- | ----- | ----- |
| screen | Title tab: month & year                                        | tab       |      |       |       |
| button | Week arrows ◀ ▶                                                | buildWeek |      |       |       |
| button | Day heading: opens the Day view                                | buildWeek |      |       |       |
| icon   | Event bar: tap shows the description, second tap opens the day | WeekGrid  |      |       |       |
| icon   | Event bar: touch & hold opens Event Details                    | WeekGrid  |      |       |       |
| icon   | View icons                                                     | viewIcons |      |       |       |
| button | Go To                                                          | buildWeek |      |       |       |

### Month view

Palm reference: Date Book: Month View. Code: `MainActivity.buildMonth`.

| Kind   | Item                                                            | Code       | Look | Works | Notes |
| ------ | --------------------------------------------------------------- | ---------- | ---- | ----- | ----- |
| screen | Title tab: month & year                                         | tab        |      |       |       |
| button | Month arrows ◀ ▶                                                | buildMonth |      |       |       |
| icon   | Day cell: marks for timed, untimed (+) & daily repeating events | MonthGrid  |      |       |       |
| button | Day cell: opens the Day view                                    | MonthGrid  |      |       |       |
| icon   | View icons                                                      | viewIcons  |      |       |       |
| button | Go To                                                           | buildMonth |      |       |       |

### Agenda view

Palm reference: Palm 2200. Code: `MainActivity.buildAgenda`.

| Kind    | Item                                                     | Code         | Look | Works | Notes |
| ------- | -------------------------------------------------------- | ------------ | ---- | ----- | ----- |
| screen  | Title tab, day arrows ◀ ▶, date (tap opens the Day view) | buildAgenda  |      |       |       |
| button  | Appointment row: opens the Day view at the event         | buildAgenda  |      |       |       |
| icon    | Appointment note icon                                    | noteIcon     |      |       |       |
| trigger | To Do category                                           | buildAgenda  |      |       |       |
| check   | To Do check box                                          | completeTodo |      |       |       |
| button  | To Do row: opens the To Do List at the item              | openTodoApp  |      |       |       |
| icon    | To Do note icon                                          | noteIcon     |      |       |       |
| icon    | View icons                                               | viewIcons    |      |       |       |
| button  | Go To                                                    | buildAgenda  |      |       |       |

### Event Details

Palm reference: Date Book: Event Details. Code: `EventDetails`.

| Kind    | Item                               | Code         | Look | Works | Notes |
| ------- | ---------------------------------- | ------------ | ---- | ----- | ----- |
| dialog  | "Event Details"                    | EventDetails |      |       |       |
| trigger | Time                               | EventDetails |      |       |       |
| trigger | Date                               | EventDetails |      |       |       |
| check   | Alarm, with advance & unit trigger | EventDetails |      |       |       |
| trigger | Repeat: Change Repeat              | EventDetails |      |       |       |
| check   | Private                            | EventDetails |      |       |       |
| button  | OK                                 | EventDetails |      |       |       |
| button  | Cancel                             | EventDetails |      |       |       |
| button  | Delete...                          | EventDetails |      |       |       |
| button  | Note                               | EventDetails |      |       |       |

### Set Time

Palm reference: Date Book: Set Time. Code: `TimeDialog`.

| Kind   | Item                                        | Code       | Look | Works | Notes |
| ------ | ------------------------------------------- | ---------- | ---- | ----- | ----- |
| dialog | "Set Time"                                  | TimeDialog |      |       |       |
| field  | Start & end time boxes, hour & minute lists | TimeDialog |      |       |       |
| button | No Time                                     | TimeDialog |      |       |       |
| button | OK                                          | TimeDialog |      |       |       |
| button | Cancel                                      | TimeDialog |      |       |       |

### Date dialog (Go to Date, Ending On, Due Date)

Palm reference: Date Book: Go to Date. Code: `DateDialog`.

| Kind   | Item              | Code       | Look | Works | Notes |
| ------ | ----------------- | ---------- | ---- | ----- | ----- |
| dialog | Title from caller | DateDialog |      |       |       |
| button | Year arrows       | DateDialog |      |       |       |
| push   | Month buttons     | DateDialog |      |       |       |
| button | Day grid          | DateDialog |      |       |       |
| button | Cancel            | DateDialog |      |       |       |
| button | Today             | DateDialog |      |       |       |

### Change Repeat

Palm reference: Date Book: Change Repeat. Code: `RepeatDialog`.

| Kind    | Item                                | Code          | Look | Works | Notes |
| ------- | ----------------------------------- | ------------- | ---- | ----- | ----- |
| dialog  | "Change Repeat"                     | RepeatDialog  |      |       |       |
| push    | None, Day, Week, Month, Year        | RepeatDialog  |      |       |       |
| field   | Every                               | buildArea     |      |       |       |
| trigger | End on: No End Date, Choose Date... | buildArea     |      |       |       |
| push    | Repeat on: S M T W T F S (Week)     | buildArea     |      |       |       |
| push    | Repeat by: Day, Date (Month)        | buildArea     |      |       |       |
| icon    | Summary text                        | updateSummary |      |       |       |
| button  | OK                                  | RepeatDialog  |      |       |       |
| button  | Cancel                              | RepeatDialog  |      |       |       |

### Repeating record choice

Palm reference: Palm form 2100. Code: `MainActivity.repeatForm`.

| Kind   | Item              | Code       | Look | Works | Notes |
| ------ | ----------------- | ---------- | ---- | ----- | ----- |
| dialog | Title from caller | repeatForm |      |       |       |
| icon   | Confirmation icon | repeatForm |      |       |       |
| button | Current           | repeatForm |      |       |       |
| button | Future            | repeatForm |      |       |       |
| button | All               | repeatForm |      |       |       |
| button | Cancel            | repeatForm |      |       |       |

### Delete Event

Palm reference: Date Book: Delete Event. Code: `MainActivity.deleteEvent`.

| Kind   | Item                    | Code        | Look | Works | Notes |
| ------ | ----------------------- | ----------- | ---- | ----- | ----- |
| alert  | "Delete Event"          | deleteEvent |      |       |       |
| check  | Save archive copy on PC | palmAlert   |      |       |       |
| button | OK                      | palmAlert   |      |       |       |
| button | Cancel                  | palmAlert   |      |       |       |

### Purge (events)

Palm reference: Date Book: Purge. Code: `showPurgeEvents`.

| Kind    | Item                     | Code            | Look | Works | Notes |
| ------- | ------------------------ | --------------- | ---- | ----- | ----- |
| dialog  | "Purge"                  | showPurgeEvents |      |       |       |
| icon    | Confirmation icon        | showPurgeEvents |      |       |       |
| trigger | Delete events older than | showPurgeEvents |      |       |       |
| check   | Save archive copy on PC  | showPurgeEvents |      |       |       |
| button  | OK                       | showPurgeEvents |      |       |       |
| button  | Cancel                   | showPurgeEvents |      |       |       |

### Preferences

Palm reference: Date Book: Preferences. Code: `showPreferences`.

| Kind    | Item                       | Code            | Look | Works | Notes |
| ------- | -------------------------- | --------------- | ---- | ----- | ----- |
| dialog  | "Preferences"              | showPreferences |      |       |       |
| trigger | Start Time                 | showPreferences |      |       |       |
| trigger | End Time                   | showPreferences |      |       |       |
| trigger | Alarm Preset: advance unit | showPreferences |      |       |       |
| check   | Alarm Preset               | showPreferences |      |       |       |
| button  | Alarm Sound: Change...     | showPreferences |      |       |       |
| trigger | Remind Me                  | showPreferences |      |       |       |
| trigger | Play Every                 | showPreferences |      |       |       |
| button  | OK                         | showPreferences |      |       |       |
| button  | Cancel                     | showPreferences |      |       |       |

### Display Options

Palm reference: Date Book: Display Options. Code: `showDisplayOptions`.

| Kind   | Item                        | Code               | Look | Works | Notes |
| ------ | --------------------------- | ------------------ | ---- | ----- | ----- |
| dialog | "Display Options"           | showDisplayOptions |      |       |       |
| check  | Show Time Bars              | showDisplayOptions |      |       |       |
| check  | Compress Day View           | showDisplayOptions |      |       |       |
| check  | Show Timed Items            | showDisplayOptions |      |       |       |
| check  | Show Untimed Items          | showDisplayOptions |      |       |       |
| check  | Show Daily Repeating Events | showDisplayOptions |      |       |       |
| button | OK                          | showDisplayOptions |      |       |       |
| button | Cancel                      | showDisplayOptions |      |       |       |

### Date Book menus

Palm reference: Date Book: menus. Code: `MainActivity.menus`.

| Kind | Item                        | Code  | Look | Works | Notes |
| ---- | --------------------------- | ----- | ---- | ----- | ----- |
| menu | Record: New Event           | menus |      |       |       |
| menu | Record: Delete Event...     | menus |      |       |       |
| menu | Record: Attach Note         | menus |      |       |       |
| menu | Record: Delete Note...      | menus |      |       |       |
| menu | Record: Purge...            | menus |      |       |       |
| menu | Record: Export Backup...    | menus |      |       |       |
| menu | Record: Import Backup...    | menus |      |       |       |
| menu | Record: Export iCalendar... | menus |      |       |       |
| menu | Record: Import iCalendar... | menus |      |       |       |
| menu | Options: Font...            | menus |      |       |       |
| menu | Options: Preferences...     | menus |      |       |       |
| menu | Options: Display Options... | menus |      |       |       |
| menu | Options: Sync...            | menus |      |       |       |
| menu | Options: ShortCuts...       | menus |      |       |       |
| menu | Options: Security...        | menus |      |       |       |
| menu | Options: About Date Book    | menus |      |       |       |

## To Do List

### To Do List

Palm reference: To Do List. Code: `MainActivity.buildTodo`.

| Kind    | Item                                        | Code              | Look | Works | Notes |
| ------- | ------------------------------------------- | ----------------- | ---- | ----- | ----- |
| screen  | Title tab "To Do"                           | tab               |      |       |       |
| trigger | Category                                    | buildTodo         |      |       |       |
| check   | Check box: complete                         | completeTodo      |      |       |       |
| button  | Priority: tap to change                     | todoPriorityLabel |      |       |       |
| field   | Item line: tap & type                       | rowEdit           |      |       |       |
| icon    | Note icon                                   | noteIcon          |      |       |       |
| button  | Due date: tap to change                     | todoDueLabel      |      |       |       |
| button  | Category column: tap to change              | todoCategoryLabel |      |       |       |
| icon    | Gray bar of a masked record: Private Record | revealAsk         |      |       |       |
| button  | New                                         | buildTodo         |      |       |       |
| button  | Details...                                  | buildTodo         |      |       |       |
| alert   | Select Item (Details with no item)          | buildTodo         |      |       |       |
| button  | Show...                                     | buildTodo         |      |       |       |
| icon    | Find                                        | render            |      |       |       |

### To Do Item Details

Palm reference: To Do List: To Do Item Details. Code: `TodoDetails`.

| Kind    | Item                 | Code        | Look | Works | Notes |
| ------- | -------------------- | ----------- | ---- | ----- | ----- |
| dialog  | "To Do Item Details" | TodoDetails |      |       |       |
| push    | Priority 1 to 5      | TodoDetails |      |       |       |
| trigger | Category             | TodoDetails |      |       |       |
| trigger | Due Date             | TodoDetails |      |       |       |
| check   | Private              | TodoDetails |      |       |       |
| button  | OK                   | TodoDetails |      |       |       |
| button  | Cancel               | TodoDetails |      |       |       |
| button  | Delete...            | TodoDetails |      |       |       |
| button  | Note                 | TodoDetails |      |       |       |

### To Do Preferences (Show...)

Palm reference: To Do List: To Do Preferences. Code: `showShowOptions`.

| Kind    | Item                   | Code            | Look | Works | Notes |
| ------- | ---------------------- | --------------- | ---- | ----- | ----- |
| dialog  | "To Do Preferences"    | showShowOptions |      |       |       |
| trigger | Sort by                | showShowOptions |      |       |       |
| check   | Show Completed Items   | showShowOptions |      |       |       |
| check   | Show Only Due Items    | showShowOptions |      |       |       |
| check   | Record Completion Date | showShowOptions |      |       |       |
| check   | Show Due Dates         | showShowOptions |      |       |       |
| check   | Show Priorities        | showShowOptions |      |       |       |
| check   | Show Categories        | showShowOptions |      |       |       |
| button  | OK                     | showShowOptions |      |       |       |
| button  | Cancel                 | showShowOptions |      |       |       |

### Delete To Do

Palm reference: To Do List: Delete To Do. Code: `MainActivity.deleteTodo`.

| Kind   | Item                    | Code       | Look | Works | Notes |
| ------ | ----------------------- | ---------- | ---- | ----- | ----- |
| alert  | "Delete To Do"          | deleteTodo |      |       |       |
| check  | Save archive copy on PC | palmAlert  |      |       |       |
| button | OK                      | palmAlert  |      |       |       |
| button | Cancel                  | palmAlert  |      |       |       |

### Purge (To Do)

Palm reference: To Do List: Purge. Code: `showPurgeTodos`.

| Kind   | Item                    | Code           | Look | Works | Notes |
| ------ | ----------------------- | -------------- | ---- | ----- | ----- |
| alert  | "Purge"                 | showPurgeTodos |      |       |       |
| check  | Save archive copy on PC | palmAlert      |      |       |       |
| button | OK                      | palmAlert      |      |       |       |
| button | Cancel                  | palmAlert      |      |       |       |

### To Do menus

Palm reference: To Do List: menus. Code: `MainActivity.menus`.

| Kind | Item                        | Code  | Look | Works | Notes |
| ---- | --------------------------- | ----- | ---- | ----- | ----- |
| menu | Record: New Item            | menus |      |       |       |
| menu | Record: Delete Item...      | menus |      |       |       |
| menu | Record: Attach Note         | menus |      |       |       |
| menu | Record: Delete Note...      | menus |      |       |       |
| menu | Record: Purge...            | menus |      |       |       |
| menu | Record: Export Backup...    | menus |      |       |       |
| menu | Record: Import Backup...    | menus |      |       |       |
| menu | Options: Font...            | menus |      |       |       |
| menu | Options: Edit Categories... | menus |      |       |       |
| menu | Options: Sync...            | menus |      |       |       |
| menu | Options: ShortCuts...       | menus |      |       |       |
| menu | Options: Security...        | menus |      |       |       |
| menu | Options: About To Do List   | menus |      |       |       |

## Memo Pad

### Memo List

Palm reference: Memo Pad: Memo List. Code: `buildMemoList`.

| Kind    | Item                                                    | Code          | Look | Works | Notes |
| ------- | ------------------------------------------------------- | ------------- | ---- | ----- | ----- |
| screen  | Title tab "Memo"                                        | tab           |      |       |       |
| trigger | Category                                                | buildMemoList |      |       |       |
| button  | Memo row: opens the memo                                | buildMemoList |      |       |       |
| button  | Memo row: touch & hold, then drag to move (manual sort) | buildMemoList |      |       |       |
| icon    | Gray bar of a masked record: Private Record             | revealAsk     |      |       |       |
| icon    | Scroll bar                                              | withScrollBar |      |       |       |
| button  | New                                                     | buildMemoList |      |       |       |
| icon    | Find                                                    | render        |      |       |       |

### Memo view

Palm reference: Memo Pad: Memo View. Code: `buildMemoEdit`.

| Kind    | Item                    | Code          | Look | Works | Notes |
| ------- | ----------------------- | ------------- | ---- | ----- | ----- |
| screen  | Title tab "Memo N of M" | buildMemoEdit |      |       |       |
| trigger | Category                | buildMemoEdit |      |       |       |
| field   | Lined page              | linedPage     |      |       |       |
| icon    | Scroll bar              | withScrollBar |      |       |       |
| button  | Done                    | buildMemoEdit |      |       |       |
| button  | Details                 | buildMemoEdit |      |       |       |
| icon    | Find                    | render        |      |       |       |

### Memo Details

Palm reference: Memo Pad: Memo Details. Code: `showMemoDetails`.

| Kind    | Item           | Code            | Look | Works | Notes |
| ------- | -------------- | --------------- | ---- | ----- | ----- |
| dialog  | "Memo Details" | showMemoDetails |      |       |       |
| trigger | Category       | showMemoDetails |      |       |       |
| check   | Private        | showMemoDetails |      |       |       |
| button  | OK             | showMemoDetails |      |       |       |
| button  | Cancel         | showMemoDetails |      |       |       |
| button  | Delete...      | showMemoDetails |      |       |       |

### Delete Memo

Palm reference: Memo Pad: Delete Memo. Code: `deleteMemoAsk`.

| Kind   | Item                    | Code          | Look | Works | Notes |
| ------ | ----------------------- | ------------- | ---- | ----- | ----- |
| alert  | "Delete Memo"           | deleteMemoAsk |      |       |       |
| check  | Save archive copy on PC | palmAlert     |      |       |       |
| button | OK                      | palmAlert     |      |       |       |
| button | Cancel                  | palmAlert     |      |       |       |

### Memo Preferences

Palm reference: Memo Pad: Preferences. Code: `showMemoPreferences`.

| Kind    | Item               | Code                | Look | Works | Notes |
| ------- | ------------------ | ------------------- | ---- | ----- | ----- |
| dialog  | "Memo Preferences" | showMemoPreferences |      |       |       |
| trigger | Sort by            | showMemoPreferences |      |       |       |
| button  | OK                 | showMemoPreferences |      |       |       |
| button  | Cancel             | showMemoPreferences |      |       |       |

### Memo Pad menus

Palm reference: Memo Pad: menus. Code: `MainActivity.menus`.

| Kind  | Item                        | Code          | Look | Works | Notes |
| ----- | --------------------------- | ------------- | ---- | ----- | ----- |
| menu  | Record: New Memo            | menus         |      |       |       |
| menu  | Record: Delete Memo...      | menus         |      |       |       |
| menu  | Record: Export Backup...    | menus         |      |       |       |
| menu  | Record: Import Backup...    | menus         |      |       |       |
| menu  | Options: Font...            | menus         |      |       |       |
| menu  | Options: Preferences...     | menus         |      |       |       |
| menu  | Options: Edit Categories... | menus         |      |       |       |
| menu  | Options: Sync...            | menus         |      |       |       |
| menu  | Options: ShortCuts...       | menus         |      |       |       |
| menu  | Options: Security...        | menus         |      |       |       |
| menu  | Options: About Memo Pad     | menus         |      |       |       |
| alert | "About Memo Pad"            | showMemoAbout |      |       |       |

## Address Book

### Address List

Palm reference: Palm form 1000. Code: `buildAddressList`.

| Kind    | Item                                        | Code             | Look | Works | Notes                                                                               |
| ------- | ------------------------------------------- | ---------------- | ---- | ----- | ----------------------------------------------------------------------------------- |
| screen  | Title tab "Address"                         | tab              |      |       |                                                                                     |
| trigger | Category                                    | buildAddressList |      |       |                                                                                     |
| button  | Name row: opens Address View                | addressRow       |      |       |                                                                                     |
| icon    | Phone number & label letter                 | addressRow       |      |       |                                                                                     |
| icon    | Note icon                                   | noteIcon         |      |       |                                                                                     |
| icon    | Gray bar of a masked record: Private Record | revealAsk        |      |       |                                                                                     |
| field   | Look Up                                     | buildAddressList |      |       | Solid gray Android underline (palm-ui field); the Palm field has a dotted underline |
| button  | New                                         | buildAddressList |      |       |                                                                                     |
| icon    | Find                                        | render           |      |       |                                                                                     |

### Address View

Palm reference: Address Book: Address View. Code: `buildAddressView`.

| Kind   | Item                        | Code             | Look | Works | Notes |
| ------ | --------------------------- | ---------------- | ---- | ----- | ----- |
| screen | Title tab "Address View"    | tab              |      |       |       |
| button | Phone number: call          | phoneSection     |      |       |       |
| button | Msg: text message           | phoneSection     |      |       |       |
| button | E-mail: write               | buildAddressView |      |       |       |
| button | Address: map                | buildAddressView |      |       |       |
| button | Value: touch & hold to copy | copyValue        |      |       |       |
| icon   | Note text                   | noteSection      |      |       |       |
| button | Done                        | buildAddressView |      |       |       |
| button | Edit                        | buildAddressView |      |       |       |
| button | New                         | buildAddressView |      |       |       |
| icon   | Find                        | render           |      |       |       |

### Address Edit

Palm reference: Address Book: Address Edit. Code: `buildAddressEdit`.

| Kind    | Item                                                   | Code             | Look | Works | Notes                                                                               |
| ------- | ------------------------------------------------------ | ---------------- | ---- | ----- | ----------------------------------------------------------------------------------- |
| screen  | Title tab "Address Edit"                               | tab              |      |       |                                                                                     |
| trigger | Category                                               | buildAddressEdit |      |       |                                                                                     |
| field   | Name, Title & Company fields                           | addressField     |      |       | Solid gray Android underline (palm-ui field); the Palm field has a dotted underline |
| trigger | Phone label of each of the five phone fields           | buildAddressEdit |      |       |                                                                                     |
| field   | Address, City, State, Zip Code, Country, Custom 1 to 4 | addressField     |      |       | Solid gray Android underline (palm-ui field); the Palm field has a dotted underline |
| button  | Done                                                   | buildAddressEdit |      |       |                                                                                     |
| button  | Details...                                             | buildAddressEdit |      |       |                                                                                     |
| button  | Note                                                   | buildAddressEdit |      |       |                                                                                     |
| icon    | Find                                                   | render           |      |       |                                                                                     |

### Address Entry Details

Palm reference: Palm form 1200. Code: `showAddressDetails`.

| Kind    | Item                    | Code               | Look | Works | Notes |
| ------- | ----------------------- | ------------------ | ---- | ----- | ----- |
| dialog  | "Address Entry Details" | showAddressDetails |      |       |       |
| trigger | Show in List            | showAddressDetails |      |       |       |
| trigger | Category                | showAddressDetails |      |       |       |
| check   | Private                 | showAddressDetails |      |       |       |
| button  | OK                      | showAddressDetails |      |       |       |
| button  | Cancel                  | showAddressDetails |      |       |       |
| button  | Delete...               | showAddressDetails |      |       |       |
| button  | Note                    | showAddressDetails |      |       |       |

### Delete Address

Palm reference: Palm form 1600. Code: `deleteAddressAsk`.

| Kind   | Item                    | Code             | Look | Works | Notes |
| ------ | ----------------------- | ---------------- | ---- | ----- | ----- |
| alert  | "Delete Address"        | deleteAddressAsk |      |       |       |
| check  | Save archive copy on PC | palmAlert        |      |       |       |
| button | OK                      | palmAlert        |      |       |       |
| button | Cancel                  | palmAlert        |      |       |       |

### Address Book Preferences

Palm reference: Palm form 2000. Code: `showAddressPreferences`.

| Kind   | Item                                                | Code                   | Look | Works | Notes |
| ------ | --------------------------------------------------- | ---------------------- | ---- | ----- | ----- |
| dialog | "Address Book Preferences"                          | showAddressPreferences |      |       |       |
| check  | Remember last category                              | showAddressPreferences |      |       |       |
| push   | List By: Last Name, First Name / Company, Last Name | showAddressPreferences |      |       |       |
| button | OK                                                  | showAddressPreferences |      |       |       |
| button | Cancel                                              | showAddressPreferences |      |       |       |

### Rename Custom Fields

Palm reference: Palm form 1900. Code: `showRenameCustomFields`.

| Kind   | Item                   | Code                   | Look | Works | Notes                                                                               |
| ------ | ---------------------- | ---------------------- | ---- | ----- | ----------------------------------------------------------------------------------- |
| dialog | "Rename Custom Fields" | showRenameCustomFields |      |       |                                                                                     |
| field  | Four field names       | showRenameCustomFields |      |       | Solid gray Android underline (palm-ui field); the Palm field has a dotted underline |
| button | OK                     | showRenameCustomFields |      |       |                                                                                     |
| button | Cancel                 | showRenameCustomFields |      |       |                                                                                     |

### Phone Number Lookup

Palm reference: Palm form 2100, manual page 72. Code: `showPhoneLookup`.

| Kind   | Item                   | Code            | Look | Works | Notes                                                                               |
| ------ | ---------------------- | --------------- | ---- | ----- | ----------------------------------------------------------------------------------- |
| dialog | "Phone Number Lookup:" | showPhoneLookup |      |       |                                                                                     |
| button | List row               | showPhoneLookup |      |       |                                                                                     |
| field  | Look Up                | showPhoneLookup |      |       | Solid gray Android underline (palm-ui field); the Palm field has a dotted underline |
| button | Add                    | showPhoneLookup |      |       |                                                                                     |
| button | Cancel                 | showPhoneLookup |      |       |                                                                                     |

### Address Book sharing

Palm reference: Address Book: Beam (replaced). Code: `AddressShare.kt`.

| Kind  | Item                   | Code                  | Look | Works | Notes |
| ----- | ---------------------- | --------------------- | ---- | ----- | ----- |
| alert | "Select Business Card" | selectBusinessCardAsk |      |       |       |
| alert | "Export Addresses"     | exportVCard           |      |       |       |
| alert | "Import" (vCard)       | importVCardText       |      |       |       |
| alert | "About Address Book"   | showAddressAbout      |      |       |       |

### Address Book menus

Palm reference: Address Book: menus. Code: `MainActivity.menus`.

| Kind | Item                                       | Code  | Look | Works | Notes |
| ---- | ------------------------------------------ | ----- | ---- | ----- | ----- |
| menu | List Record: Share Category                | menus |      |       |       |
| menu | List Record: Share Business Card           | menus |      |       |       |
| menu | List Record: Import vCard...               | menus |      |       |       |
| menu | List Record: Export vCard...               | menus |      |       |       |
| menu | List Record: Export Backup...              | menus |      |       |       |
| menu | List Record: Import Backup...              | menus |      |       |       |
| menu | View/Edit Record: Delete Address...        | menus |      |       |       |
| menu | View/Edit Record: Attach Note              | menus |      |       |       |
| menu | View/Edit Record: Delete Note...           | menus |      |       |       |
| menu | View/Edit Record: Share Address            | menus |      |       |       |
| menu | View/Edit Record: Select Business Card...  | menus |      |       |       |
| menu | View/Edit Record: Share Business Card      | menus |      |       |       |
| menu | List Options: Font...                      | menus |      |       |       |
| menu | List Options: Preferences...               | menus |      |       |       |
| menu | List Options: Rename Custom Fields...      | menus |      |       |       |
| menu | List Options: Edit Categories...           | menus |      |       |       |
| menu | List Options: Sync...                      | menus |      |       |       |
| menu | List Options: ShortCuts...                 | menus |      |       |       |
| menu | List Options: Security...                  | menus |      |       |       |
| menu | List Options: About Address Book           | menus |      |       |       |
| menu | View/Edit Options: Font...                 | menus |      |       |       |
| menu | View/Edit Options: Rename Custom Fields... | menus |      |       |       |
| menu | View/Edit Options: About Address Book      | menus |      |       |       |

## Shared by the four apps

### Note view

Palm reference: Palm Note view. Code: `buildNote`.

| Kind   | Item                          | Code          | Look | Works | Notes |
| ------ | ----------------------------- | ------------- | ---- | ----- | ----- |
| screen | Title tab: record description | buildNote     |      |       |       |
| field  | Lined page                    | linedPage     |      |       |       |
| icon   | Scroll bar                    | withScrollBar |      |       |       |
| button | Done                          | buildNote     |      |       |       |
| button | Delete...                     | buildNote     |      |       |       |
| alert  | "Delete Note"                 | buildNote     |      |       |       |
| menu   | Edit menu                     | menus         |      |       |       |
| menu   | Options: Font...              | menus         |      |       |       |

### Edit menu

Palm reference: Palm Edit menu. Code: `MainActivity.menus`.

| Kind | Item         | Code  | Look | Works | Notes |
| ---- | ------------ | ----- | ---- | ----- | ----- |
| menu | Undo         | menus |      |       |       |
| menu | Cut          | menus |      |       |       |
| menu | Copy         | menus |      |       |       |
| menu | Paste        | menus |      |       |       |
| menu | Select All   | menus |      |       |       |
| menu | Phone Lookup | menus |      |       |       |
| menu | Keyboard     | menus |      |       |       |

### Menu bar

Palm reference: Palm menu bar. Code: `MenuBar`.

| Kind   | Item                                                  | Code       | Look | Works | Notes |
| ------ | ----------------------------------------------------- | ---------- | ---- | ----- | ----- |
| screen | Menu bar: opens from the title tab                    | MenuBar    |      |       |       |
| button | Command letters while open; Ctrl & letter at any time | commandKey |      |       |       |

### Select Font

Palm reference: Palm: Select Font. Code: `showFont`.

| Kind   | Item          | Code     | Look | Works | Notes |
| ------ | ------------- | -------- | ---- | ----- | ----- |
| dialog | "Select Font" | showFont |      |       |       |
| push   | A A A         | showFont |      |       |       |
| button | OK            | showFont |      |       |       |
| button | Cancel        | showFont |      |       |       |

### Change Security

Palm reference: Palm: Security. Code: `showSecurity`.

| Kind    | Item                                      | Code         | Look | Works | Notes |
| ------- | ----------------------------------------- | ------------ | ---- | ----- | ----- |
| dialog  | "Change Security"                         | showSecurity |      |       |       |
| trigger | Show, Mask or Hide Records                | showSecurity |      |       |       |
| button  | OK                                        | showSecurity |      |       |       |
| button  | Cancel                                    | showSecurity |      |       |       |
| alert   | "Private Record" (tap on a masked record) | revealAsk    |      |       |       |

### ShortCuts

Palm reference: Palm: ShortCuts. Code: `showShortCuts`.

| Kind   | Item             | Code          | Look | Works | Notes                                                                               |
| ------ | ---------------- | ------------- | ---- | ----- | ----------------------------------------------------------------------------------- |
| dialog | "ShortCuts"      | showShortCuts |      |       |                                                                                     |
| button | List row         | showShortCuts |      |       |                                                                                     |
| button | Done             | showShortCuts |      |       |                                                                                     |
| button | New              | showShortCuts |      |       |                                                                                     |
| dialog | "ShortCut Entry" | editShortCut  |      |       |                                                                                     |
| field  | ShortCut Name    | editShortCut  |      |       | Solid gray Android underline (palm-ui field); the Palm field has a dotted underline |
| field  | ShortCut Text    | editShortCut  |      |       | Solid gray Android underline (palm-ui field); the Palm field has a dotted underline |
| button | OK               | editShortCut  |      |       |                                                                                     |
| button | Cancel           | editShortCut  |      |       |                                                                                     |
| button | Delete           | editShortCut  |      |       |                                                                                     |

### Edit Categories

Palm reference: Palm: Edit Categories. Code: `editCategories`.

| Kind   | Item              | Code           | Look | Works | Notes                                                                               |
| ------ | ----------------- | -------------- | ---- | ----- | ----------------------------------------------------------------------------------- |
| dialog | "Edit Categories" | editCategories |      |       |                                                                                     |
| button | List row          | editCategories |      |       |                                                                                     |
| button | OK                | editCategories |      |       |                                                                                     |
| button | New               | editCategories |      |       |                                                                                     |
| button | Rename            | editCategories |      |       |                                                                                     |
| button | Delete            | editCategories |      |       |                                                                                     |
| alert  | "Delete Category" | editCategories |      |       |                                                                                     |
| field  | Name form: field  | editCategories |      |       | Solid gray Android underline (palm-ui field); the Palm field has a dotted underline |
| button | Name form: OK     | editCategories |      |       |                                                                                     |
| button | Name form: Cancel | editCategories |      |       |                                                                                     |
| alert  | Name in use       | editCategories |      |       |                                                                                     |

### Category pop-up

Palm reference: Palm category trigger. Code: `pickCategory`.

| Kind    | Item                                         | Code         | Look | Works | Notes |
| ------- | -------------------------------------------- | ------------ | ---- | ----- | ----- |
| trigger | Category list, with All & Edit Categories... | pickCategory |      |       |       |

### Find

Palm reference: Palm: Find. Code: `showFind`.

| Kind   | Item                         | Code            | Look | Works | Notes                                                                               |
| ------ | ---------------------------- | --------------- | ---- | ----- | ----------------------------------------------------------------------------------- |
| dialog | "Find"                       | showFind        |      |       |                                                                                     |
| field  | Find text                    | showFind        |      |       | Solid gray Android underline (palm-ui field); the Palm field has a dotted underline |
| button | OK                           | showFind        |      |       |                                                                                     |
| button | Cancel                       | showFind        |      |       |                                                                                     |
| dialog | "Find" results               | showFindResults |      |       |                                                                                     |
| button | Result row: opens the record | showFindResults |      |       |                                                                                     |
| button | Done                         | showFindResults |      |       |                                                                                     |

### Sync

Palm reference: palmsync. Code: `showSync`.

| Kind   | Item       | Code     | Look | Works | Notes                                                                               |
| ------ | ---------- | -------- | ---- | ----- | ----------------------------------------------------------------------------------- |
| dialog | "Sync"     | showSync |      |       |                                                                                     |
| field  | Server     | showSync |      |       | Solid gray Android underline (palm-ui field); the Palm field has a dotted underline |
| field  | Token      | showSync |      |       | Solid gray Android underline (palm-ui field); the Palm field has a dotted underline |
| field  | Passphrase | showSync |      |       | Solid gray Android underline (palm-ui field); the Palm field has a dotted underline |
| button | OK         | showSync |      |       |                                                                                     |
| button | Sync Now   | showSync |      |       |                                                                                     |
| button | Turn Off   | showSync |      |       |                                                                                     |
| button | Cancel     | showSync |      |       |                                                                                     |

### Import & Export

Palm reference: Android files. Code: `MainActivity.onActivityResult`.

| Kind  | Item                                             | Code             | Look | Works | Notes |
| ----- | ------------------------------------------------ | ---------------- | ---- | ----- | ----- |
| alert | "Import" (backup)                                | onActivityResult |      |       |       |
| alert | "Import" (iCalendar: count of simplified events) | importIcs        |      |       |       |
| alert | "Export"                                         | onActivityResult |      |       |       |

## Palm Launcher

### Applications screen

Palm reference: Palm III Applications. Code: `palm-launcher MainActivity`.

| Kind    | Item                                  | Code     | Look | Works | Notes |
| ------- | ------------------------------------- | -------- | ---- | ----- | ----- |
| screen  | Title tab: time (tap opens the menus) | topBar   |      |       |       |
| trigger | Category                              | topBar   |      |       |       |
| icon    | Battery gauge                         | topBar   |      |       |       |
| button  | App icon: tap launches                | showApps |      |       |       |
| button  | App icon: touch & hold, app menu      | appMenu  |      |       |       |
| button  | Dock slot: tap launches               | showDock |      |       |       |
| button  | Dock slot: touch & hold, dock menu    | dockMenu |      |       |       |

### Launcher menus

Palm reference: Palm III Applications menus. Code: `palm-launcher menus`.

| Kind | Item                                 | Code  | Look | Works | Notes |
| ---- | ------------------------------------ | ----- | ---- | ----- | ----- |
| menu | App: Category...                     | menus |      |       |       |
| menu | App: Info...                         | menus |      |       |       |
| menu | App: Delete...                       | menus |      |       |       |
| menu | Options: View as List / View as Grid | menus |      |       |       |
| menu | Options: Grid Columns...             | menus |      |       |       |
| menu | Options: Wallpaper                   | menus |      |       |       |
| menu | Options: Text Size...                | menus |      |       |       |
| menu | Options: Icon Size...                | menus |      |       |       |
| menu | Options: Change Wallpaper...         | menus |      |       |       |

### Launcher dialogs

Palm reference: Palm III Applications. Code: `palm-launcher Dialogs.kt`.

| Kind   | Item                              | Code           | Look | Works | Notes |
| ------ | --------------------------------- | -------------- | ---- | ----- | ----- |
| dialog | "Category"                        | pickCategory   |      |       |       |
| button | Category: Cancel                  | pickCategory   |      |       |       |
| dialog | Text Size, Icon Size              | pickSize       |      |       |       |
| button | Size: Cancel                      | pickSize       |      |       |       |
| dialog | "Grid Columns"                    | pickColumns    |      |       |       |
| button | Grid Columns: Cancel              | pickColumns    |      |       |       |
| dialog | App list (Category, Info, Delete) | pickApp        |      |       |       |
| button | App list: Cancel                  | pickApp        |      |       |       |
| dialog | "Edit Categories"                 | editCategories |      |       |       |


## Behaviors

Actions with no control of their own.

| Behavior                                                           | Where                                       | Code               | Look | Works | Notes |
| ------------------------------------------------------------------ | ------------------------------------------- | ------------------ | ---- | ----- | ----- |
| Swipe left or right: next or previous day, week, or month          | Date Book views                             | dispatchTouchEvent |      |       |       |
| Tap outside a line in edit: saves it and ends the edit             | Day, To Do                                  | dispatchTouchEvent |      |       |       |
| Type with no line selected: starts a new record                    | Day, To Do, Memo List, Address List         | dispatchKeyEvent   |      |       |       |
| Backspace in an empty line: ends the edit, deletes an empty record | Day, To Do, Memo view                       | BackEdit           |      |       |       |
| Back: Done in Memo view, Note view, Address View, Address Edit     | all apps                                    | onBackPressed      |      |       |       |
| Ctrl and a command letter: runs the menu command                   | all apps                                    | dispatchKeyEvent   |      |       |       |
| ^ and a ShortCut name, or Ctrl+Space: expands the ShortCut         | all text fields                             | palmShortCuts      |      |       |       |
| Keyboard stays open on a tall screen                               | all apps                                    | keepKeyboard       |      |       |       |
| Status bar hidden; swipe down shows it for a moment                | every window                                | hideStatusBar      |      |       |       |
| Alarm notification: Snooze and OK                                  | Date Book                                   | AlarmReceiver      |      |       |       |
| Sync on open, 1 s after a change, and hourly                       | all apps                                    | SyncEngine         |      |       |       |
| Font option changes every screen and dialog                        | all apps                                    | render             |      |       |       |
| Masked private record: gray bar; Hide removes it                   | every record list                           | isMasked           |      |       |       |
| Empty list message                                                 | Day, Agenda, To Do, Memo List, Address List | build*             |      |       |       |

## Parity

A record feature belongs in every view that shows its records. Check a whole row when one cell changes. `-` means the feature does not apply to that view.

| Feature                                    | Day | Week | Month | Agenda | To Do | Memo List | Memo view | Address List | Address View | Address Edit | Helper                                     |
| ------------------------------------------ | --- | ---- | ----- | ------ | ----- | --------- | --------- | ------------ | ------------ | ------------ | ------------------------------------------ |
| Note icon, tap opens the note              | yes | -    | -     | yes    | yes   | -         | -         | yes          | -            | -            | noteIcon                                   |
| Note view (Done, Delete...)                | yes | -    | -     | yes    | yes   | -         | -         | yes          | yes          | yes          | openNoteView                               |
| Private masking (gray bar, Private Record) | yes | yes  | -     | yes    | yes   | yes       | -         | yes          | yes          | -            | isMasked, revealAsk                        |
| Category trigger                           | -   | -    | -     | yes    | yes   | yes       | yes       | yes          | -            | yes          | pickCategory                               |
| Lined page with Palm scroll bar            | -   | -    | -     | -      | -     | yes       | yes       | -            | -            | -            | linedPage, withScrollBar                   |
| Dotted line under each text line           | yes | -    | -     | -      | yes   | -         | yes       | -            | -            | no           | LinedBackground; Address Edit uses field() |
| Delete with Save archive copy on PC        | yes | yes  | yes   | yes    | yes   | -         | yes       | -            | yes          | yes          | palmAlert                                  |
| Find icon                                  | yes | yes  | yes   | yes    | yes   | yes       | yes       | yes          | yes          | yes          | render                                     |
| Title tab opens the menus                  | yes | yes  | yes   | yes    | yes   | yes       | yes       | yes          | yes          | yes          | tab                                        |

## Rules found from faults

| Date       | Fault                                                                                                                                          | Rule                                                                                                                                                  |
| ---------- | ---------------------------------------------------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------- |
| 2026-10-30 | The note page was a full-screen dialog: a gray field underline across the page, and Done did not work. Notes had an icon only in the Day view. | A full-page text editor is a screen of the activity built with linedPage, never a dialog. A record feature is checked across its whole row in Parity. |
| 2026-10-30 | Found while building this file: field() in palm-ui keeps the Android underline, solid gray, in Address Edit and every dialog field.            | Text fields draw the Palm dotted underline. Open: see the field rows.                                                                                 |
