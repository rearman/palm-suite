# Android UI inventory

Every screen, dialog, control, menu command, and gesture of the Android apps (Date Book, To Do List, Memo Pad, Address Book, and the launcher), for checking each against the Palm original. A check from memory does not count: compare with the Palm IIIxe handbook, the Franklin Planner edition for Palm OS 3.5 (manualslib 754038; its page numbers are given here), the Palm OS 3.5 resources, or a Palm OS emulator. The handbook's screen pictures are 160 by 160 px images: extract them with `pdfimages -png` and turn them upright, and copy a drawing from their pixels.

- **Look**: the item matches the Palm reference in position, size, font, and wording. **Works**: tapping or using it does what the Palm does, and what the README says.
- Each cell holds the date of the check and its method: `M` the code read against the handbook's text and screen pictures (wording, controls, and described behavior; position, size, and the running app need `S` or `D`), `S` a screenshot of the app compared with the reference, `D` tried on a device, `T` a test covers it. Example: `2026-10-30 D`. An empty cell is unchecked, or did not match: Notes says which.
- **Notes** holds a found difference until it is fixed (`Open:`, awaiting a decision), or the reason a difference is kept (`Kept:`).
- A new screen, dialog, control, or menu command gets its row in the same change. `python3 tools/ui_inventory.py --check` lists the controls in the code that have no row here.
- When a fault is found in one place, add its class to **Rules found from faults** and check the whole parity table for it.

## Date Book

### Day view

Palm reference: Handbook p.83 to 86. Code: `MainActivity.buildDay`.

| Kind   | Item                                                           | Code        | Look         | Works | Notes |
| ------ | -------------------------------------------------------------- | ----------- | ------------ | ----- | ----- |
| screen | Title tab: date (tap opens the menus)                          | tab         | 2026-10-30 M |       |       |
| button | Week arrows ◀ ▶                                                | buildDay    | 2026-10-30 M |       |       |
| button | Day buttons S M T W T F S                                      | buildDay    | 2026-10-30 M |       |       |
| field  | Event line: tap & type a description                           | rowEdit     | 2026-10-30 M |       |       |
| icon   | Untimed event mark: the 7 px diamond                           | untimedMark | 2026-10-31 M |       |       |
| button | Time of a line: Set Time, or new event                         | timeTapped  | 2026-10-30 M |       |       |
| icon   | Note icon                                                      | noteIcon    | 2026-10-31 M |       |       |
| icon   | Alarm icon: Event Details                                      | buildDay    |              |       |       |
| icon   | Repeat icon: Event Details                                     | buildDay    |              |       |       |
| icon   | Masked record: 50% gray bar with the lock icon; a tap shows it | maskedBar   | 2026-10-31 M |       |       |
| icon   | View icons: Day, Week, Month, Agenda                           | viewIcons   |              |       |       |
| button | New                                                            | buildDay    | 2026-10-30 M |       |       |
| button | Details                                                        | buildDay    | 2026-10-30 M |       |       |
| alert  | Select Event (Details with no event)                           | buildDay    |              |       |       |
| button | Go to: Go to Date                                              | buildDay    | 2026-10-31 M |       |       |
| icon   | Find                                                           | render      |              |       |       |

### Week view

Palm reference: Handbook p.91 to 92. Code: `MainActivity.buildWeek`.

| Kind   | Item                                                                   | Code      | Look         | Works | Notes |
| ------ | ---------------------------------------------------------------------- | --------- | ------------ | ----- | ----- |
| screen | Title tab: month & year                                                | tab       |              |       |       |
| button | Week arrows ◀ ▶                                                        | buildWeek | 2026-10-30 M |       |       |
| button | Day heading: opens the Day view                                        | buildWeek | 2026-10-30 M |       |       |
| icon   | Event bar: tap shows the description, second tap opens the day         | WeekGrid  | 2026-10-30 M |       |       |
| icon   | Event bar: drag to another time or day moves it, in 15 minute steps    | WeekGrid  | 2026-10-31 M |       |       |
| icon   | Blank time: opens that day with the hour line selected for a new event | WeekGrid  | 2026-10-31 M |       |       |
| icon   | View icons                                                             | viewIcons |              |       |       |
| button | Go to                                                                  | buildWeek | 2026-10-31 M |       |       |

### Month view

Palm reference: Handbook p.93. Code: `MainActivity.buildMonth`.

| Kind   | Item                                                            | Code       | Look         | Works | Notes |
| ------ | --------------------------------------------------------------- | ---------- | ------------ | ----- | ----- |
| screen | Title tab: month & year ("Jul 2002")                            | tab        | 2026-10-31 M |       |       |
| button | Month arrows ◀ ▶                                                | buildMonth | 2026-10-30 M |       |       |
| icon   | Day cell: marks for timed, untimed (+) & daily repeating events | MonthGrid  | 2026-10-30 M |       |       |
| button | Day cell: opens the Day view                                    | MonthGrid  | 2026-10-30 M |       |       |
| icon   | View icons                                                      | viewIcons  |              |       |       |
| button | Go to                                                           | buildMonth | 2026-10-31 M |       |       |

### Agenda view

Palm reference: Handbook p.93 to 94. Code: `MainActivity.buildAgenda`.

| Kind    | Item                                                     | Code         | Look         | Works | Notes |
| ------- | -------------------------------------------------------- | ------------ | ------------ | ----- | ----- |
| screen  | Title tab, day arrows ◀ ▶, date (tap opens the Day view) | buildAgenda  | 2026-10-30 M |       |       |
| button  | Appointment row: opens the Day view at the event         | buildAgenda  | 2026-10-30 M |       |       |
| icon    | Appointment note icon                                    | noteIcon     | 2026-10-31 M |       |       |
| trigger | To Do category                                           | buildAgenda  | 2026-10-30 M |       |       |
| check   | To Do check box                                          | completeTodo | 2026-10-30 M |       |       |
| button  | To Do row: opens the To Do List at the item              | openTodoApp  | 2026-10-30 M |       |       |
| icon    | To Do note icon                                          | noteIcon     | 2026-10-31 M |       |       |
| icon    | View icons                                               | viewIcons    |              |       |       |
| button  | Go to                                                    | buildAgenda  | 2026-10-31 M |       |       |

### Event Details

Palm reference: Handbook p.87, 89. Code: `EventDetails`.

| Kind    | Item                               | Code         | Look         | Works | Notes                                                                                                                                     |
| ------- | ---------------------------------- | ------------ | ------------ | ----- | ----------------------------------------------------------------------------------------------------------------------------------------- |
| dialog  | "Event Details"                    | EventDetails | 2026-10-30 M |       |                                                                                                                                           |
| trigger | Time                               | EventDetails | 2026-10-30 M |       |                                                                                                                                           |
| trigger | Date                               | EventDetails | 2026-10-30 M |       |                                                                                                                                           |
| check   | Alarm, with advance & unit trigger | EventDetails | 2026-10-30 M |       | Kept: the advance goes above 99 (a30bc14); the Palm allows 0 to 99. Kept: the advance & unit can be set with Alarm off (asked 2026-11-04) |
| trigger | Repeat: Change Repeat              | EventDetails | 2026-10-30 M |       |                                                                                                                                           |
| check   | Private                            | EventDetails | 2026-10-30 M |       |                                                                                                                                           |
| button  | OK                                 | EventDetails | 2026-10-30 M |       |                                                                                                                                           |
| button  | Cancel                             | EventDetails | 2026-10-30 M |       |                                                                                                                                           |
| button  | Delete...                          | EventDetails | 2026-10-30 M |       |                                                                                                                                           |
| button  | Note                               | EventDetails | 2026-10-30 M |       |                                                                                                                                           |

### Set Time

Palm reference: Handbook p.84, 86. Code: `TimeDialog`.

| Kind   | Item                                                | Code       | Look         | Works | Notes |
| ------ | --------------------------------------------------- | ---------- | ------------ | ----- | ----- |
| dialog | "Set Time"                                          | TimeDialog | 2026-10-30 M |       |       |
| field  | Start & end time boxes, hour & minute lists         | TimeDialog | 2026-10-30 M |       |       |
| button | No Time                                             | TimeDialog | 2026-10-30 M |       |       |
| button | All Day: the Start and End Times of the Preferences | TimeDialog | 2026-10-31 M |       |       |
| button | OK                                                  | TimeDialog | 2026-10-30 M |       |       |
| button | Cancel                                              | TimeDialog | 2026-10-30 M |       |       |

### Date dialog (Go to Date, Ending On, Due Date)

Palm reference: Handbook p.85. Code: `DateDialog`.

| Kind   | Item              | Code       | Look         | Works | Notes |
| ------ | ----------------- | ---------- | ------------ | ----- | ----- |
| dialog | Title from caller | DateDialog | 2026-10-30 M |       |       |
| button | Year arrows       | DateDialog | 2026-10-30 M |       |       |
| push   | Month buttons     | DateDialog | 2026-10-30 M |       |       |
| button | Day grid          | DateDialog | 2026-10-30 M |       |       |
| button | Cancel            | DateDialog | 2026-10-30 M |       |       |
| button | Today             | DateDialog | 2026-10-30 M |       |       |

### Change Repeat

Palm reference: Handbook p.89. Code: `RepeatDialog`.

| Kind    | Item                                                                              | Code          | Look         | Works | Notes |
| ------- | --------------------------------------------------------------------------------- | ------------- | ------------ | ----- | ----- |
| dialog  | "Change Repeat"                                                                   | RepeatDialog  | 2026-10-30 M |       |       |
| push    | None, Day, Week, Month, Year                                                      | RepeatDialog  | 2026-10-30 M |       |       |
| field   | Every                                                                             | buildArea     | 2026-10-30 M |       |       |
| trigger | End on: No End Date, Choose Date...                                               | buildArea     | 2026-10-30 M |       |       |
| push    | Repeat on: S M T W T F S (Week)                                                   | buildArea     |              |       |       |
| push    | Repeat by: Day, Date (Month)                                                      | buildArea     |              |       |       |
| icon    | Summary text in a framed box; with None, the instruction above it and "No Repeat" | updateSummary | 2026-10-31 M |       |       |
| button  | OK                                                                                | RepeatDialog  | 2026-10-30 M |       |       |
| button  | Cancel                                                                            | RepeatDialog  | 2026-10-30 M |       |       |

### Repeating record choice

Palm reference: Handbook p.90 (Repeating Event). Code: `MainActivity.repeatForm`.

| Kind   | Item              | Code       | Look         | Works | Notes |
| ------ | ----------------- | ---------- | ------------ | ----- | ----- |
| dialog | Title from caller | repeatForm | 2026-10-30 M |       |       |
| icon   | Confirmation icon | repeatForm | 2026-10-30 M |       |       |
| button | Current           | repeatForm | 2026-10-30 M |       |       |
| button | Future            | repeatForm | 2026-10-30 M |       |       |
| button | All               | repeatForm | 2026-10-30 M |       |       |
| button | Cancel            | repeatForm | 2026-10-30 M |       |       |

### Delete Event

Palm reference: Handbook p.64. Code: `MainActivity.deleteEvent`.

| Kind   | Item                    | Code        | Look         | Works | Notes |
| ------ | ----------------------- | ----------- | ------------ | ----- | ----- |
| alert  | "Delete Event"          | deleteEvent | 2026-10-30 M |       |       |
| check  | Save archive copy on PC | palmAlert   | 2026-10-30 M |       |       |
| button | OK                      | palmAlert   | 2026-10-30 M |       |       |
| button | Cancel                  | palmAlert   |              |       |       |

### Purge (events)

Palm reference: Handbook p.65. Code: `showPurgeEvents`.

| Kind    | Item                     | Code            | Look         | Works | Notes |
| ------- | ------------------------ | --------------- | ------------ | ----- | ----- |
| dialog  | "Purge"                  | showPurgeEvents | 2026-10-30 M |       |       |
| icon    | Confirmation icon        | showPurgeEvents |              |       |       |
| trigger | Delete events older than | showPurgeEvents | 2026-10-30 M |       |       |
| check   | Save archive copy on PC  | showPurgeEvents | 2026-10-30 M |       |       |
| button  | OK                       | showPurgeEvents | 2026-10-30 M |       |       |
| button  | Cancel                   | showPurgeEvents |              |       |       |

### Preferences

Palm reference: Handbook p.96. Code: `showPreferences`.

| Kind    | Item                       | Code            | Look         | Works | Notes                                                                               |
| ------- | -------------------------- | --------------- | ------------ | ----- | ----------------------------------------------------------------------------------- |
| dialog  | "Preferences"              | showPreferences | 2026-10-30 M |       |                                                                                     |
| trigger | Start Time                 | showPreferences |              |       | Kept: a pop-up list; the Palm has a time box with up and down arrows                |
| trigger | End Time                   | showPreferences |              |       | Kept: a pop-up list; the Palm has a time box with up and down arrows                |
| trigger | Alarm Preset: advance unit | showPreferences | 2026-10-30 M |       | Kept: the advance & unit can be set with Alarm Preset off (asked 2026-11-04)        |
| check   | Alarm Preset               | showPreferences | 2026-10-30 M |       | Kept: the advance & unit can be set with Alarm Preset off (asked 2026-11-04)        |
| button  | Alarm Sound: Change...     | showPreferences |              |       | Kept: opens the Android alarm sound settings; the Palm has an Alarm Sound pick list |
| trigger | Remind Me                  | showPreferences | 2026-10-30 M |       |                                                                                     |
| trigger | Play Every                 | showPreferences | 2026-10-30 M |       |                                                                                     |
| button  | OK                         | showPreferences | 2026-10-30 M |       |                                                                                     |
| button  | Cancel                     | showPreferences | 2026-10-30 M |       |                                                                                     |

### Display Options

Palm reference: Handbook p.95. Code: `showDisplayOptions`.

| Kind   | Item                      | Code               | Look         | Works | Notes |
| ------ | ------------------------- | ------------------ | ------------ | ----- | ----- |
| dialog | "Display Options"         | showDisplayOptions | 2026-10-30 M |       |       |
| check  | Show Time Bars            | showDisplayOptions | 2026-10-30 M |       |       |
| check  | Compress Day View         | showDisplayOptions | 2026-10-30 M |       |       |
| check  | Show Timed Events         | showDisplayOptions | 2026-10-31 M |       |       |
| check  | Show Untimed Events       | showDisplayOptions | 2026-10-31 M |       |       |
| check  | Show Daily Repeating Evts | showDisplayOptions | 2026-10-31 M |       |       |
| button | OK                        | showDisplayOptions | 2026-10-30 M |       |       |
| button | Cancel                    | showDisplayOptions | 2026-10-30 M |       |       |

### Date Book menus

Palm reference: Handbook p.94 to 95. Code: `MainActivity.menus`.

| Kind | Item                        | Code  | Look         | Works | Notes                                      |
| ---- | --------------------------- | ----- | ------------ | ----- | ------------------------------------------ |
| menu | Record: New Event           | menus | 2026-10-30 M |       |                                            |
| menu | Record: Delete Event...     | menus | 2026-10-30 M |       |                                            |
| menu | Record: Attach Note         | menus | 2026-10-30 M |       |                                            |
| menu | Record: Delete Note...      | menus | 2026-10-30 M |       |                                            |
| menu | Record: Purge...            | menus | 2026-10-30 M |       |                                            |
| menu | Record: Export Backup...    | menus |              |       | Kept: Export and Import replace Beam Event |
| menu | Record: Import Backup...    | menus |              |       |                                            |
| menu | Record: Export iCalendar... | menus |              |       |                                            |
| menu | Record: Import iCalendar... | menus |              |       |                                            |
| menu | Options: Font...            | menus | 2026-10-30 M |       |                                            |
| menu | Options: Preferences...     | menus | 2026-10-30 M |       |                                            |
| menu | Options: Display Options... | menus | 2026-10-30 M |       |                                            |
| menu | Options: Phone Lookup       | menus | 2026-10-31 M |       |                                            |
| menu | Options: Sync...            | menus |              |       |                                            |
| menu | Options: Security...        | menus | 2026-10-30 M |       |                                            |
| menu | Options: About Date Book    | menus | 2026-10-30 M |       |                                            |

## To Do List

### To Do List

Palm reference: Handbook p.102 to 105. Code: `MainActivity.buildTodo`.

| Kind    | Item                                                           | Code              | Look         | Works | Notes |
| ------- | -------------------------------------------------------------- | ----------------- | ------------ | ----- | ----- |
| screen  | Title tab "To Do"                                              | tab               | 2026-10-30 M |       |       |
| trigger | Category                                                       | buildTodo         | 2026-10-30 M |       |       |
| check   | Check box: complete                                            | completeTodo      | 2026-10-30 M |       |       |
| button  | Priority: tap to change                                        | todoPriorityLabel | 2026-10-30 M |       |       |
| field   | Item line: tap & type                                          | rowEdit           | 2026-10-30 M |       |       |
| icon    | Note icon                                                      | noteIcon          | 2026-10-31 M |       |       |
| button  | Due date: tap to change                                        | todoDueLabel      | 2026-10-30 M |       |       |
| button  | Category column: tap to change                                 | todoCategoryLabel |              |       |       |
| icon    | Masked record: 50% gray bar with the lock icon; a tap shows it | maskedBar         | 2026-10-31 M |       |       |
| button  | New                                                            | buildTodo         | 2026-10-30 M |       |       |
| button  | Details...                                                     | buildTodo         | 2026-10-30 M |       |       |
| alert   | Select Item (Details with no item)                             | buildTodo         |              |       |       |
| button  | Show...                                                        | buildTodo         | 2026-10-30 M |       |       |
| icon    | Find                                                           | render            |              |       |       |

### To Do Item Details

Palm reference: Handbook p.104. Code: `TodoDetails`.

| Kind    | Item                 | Code        | Look         | Works | Notes |
| ------- | -------------------- | ----------- | ------------ | ----- | ----- |
| dialog  | "To Do Item Details" | TodoDetails | 2026-10-30 M |       |       |
| push    | Priority 1 to 5      | TodoDetails | 2026-10-30 M |       |       |
| trigger | Category             | TodoDetails | 2026-10-30 M |       |       |
| trigger | Due Date             | TodoDetails | 2026-10-30 M |       |       |
| check   | Private              | TodoDetails | 2026-10-30 M |       |       |
| button  | OK                   | TodoDetails | 2026-10-30 M |       |       |
| button  | Cancel               | TodoDetails | 2026-10-30 M |       |       |
| button  | Delete...            | TodoDetails | 2026-10-30 M |       |       |
| button  | Note                 | TodoDetails | 2026-10-30 M |       |       |

### To Do Preferences (Show...)

Palm reference: Handbook p.105 to 106. Code: `showShowOptions`.

| Kind    | Item                   | Code            | Look         | Works | Notes |
| ------- | ---------------------- | --------------- | ------------ | ----- | ----- |
| dialog  | "To Do Preferences"    | showShowOptions | 2026-10-30 M |       |       |
| trigger | Sort by                | showShowOptions | 2026-10-30 M |       |       |
| check   | Show Completed Items   | showShowOptions | 2026-10-30 M |       |       |
| check   | Show Only Due Items    | showShowOptions | 2026-10-30 M |       |       |
| check   | Record Completion Date | showShowOptions | 2026-10-30 M |       |       |
| check   | Show Due Dates         | showShowOptions | 2026-10-30 M |       |       |
| check   | Show Priorities        | showShowOptions | 2026-10-30 M |       |       |
| check   | Show Categories        | showShowOptions | 2026-10-30 M |       |       |
| button  | OK                     | showShowOptions | 2026-10-30 M |       |       |
| button  | Cancel                 | showShowOptions | 2026-10-30 M |       |       |

### Delete To Do

Palm reference: Handbook p.64. Code: `MainActivity.deleteTodo`.

| Kind   | Item                    | Code       | Look         | Works | Notes |
| ------ | ----------------------- | ---------- | ------------ | ----- | ----- |
| alert  | "Delete To Do"          | deleteTodo |              |       |       |
| check  | Save archive copy on PC | palmAlert  | 2026-10-30 M |       |       |
| button | OK                      | palmAlert  | 2026-10-30 M |       |       |
| button | Cancel                  | palmAlert  |              |       |       |

### Purge (To Do)

Palm reference: Handbook p.65. Code: `showPurgeTodos`.

| Kind   | Item                    | Code           | Look         | Works | Notes |
| ------ | ----------------------- | -------------- | ------------ | ----- | ----- |
| alert  | "Purge"                 | showPurgeTodos |              |       |       |
| check  | Save archive copy on PC | palmAlert      | 2026-10-30 M |       |       |
| button | OK                      | palmAlert      | 2026-10-30 M |       |       |
| button | Cancel                  | palmAlert      |              |       |       |

### To Do menus

Palm reference: Handbook p.106. Code: `MainActivity.menus`.

| Kind | Item                      | Code  | Look         | Works | Notes                                                       |
| ---- | ------------------------- | ----- | ------------ | ----- | ----------------------------------------------------------- |
| menu | Record: Delete Item...    | menus | 2026-10-30 M |       |                                                             |
| menu | Record: Attach Note       | menus | 2026-10-30 M |       |                                                             |
| menu | Record: Delete Note...    | menus | 2026-10-30 M |       |                                                             |
| menu | Record: Purge...          | menus | 2026-10-30 M |       |                                                             |
| menu | Record: Export Backup...  | menus |              |       | Kept: Export and Import replace Beam Item and Beam Category |
| menu | Record: Import Backup...  | menus |              |       |                                                             |
| menu | Options: Font...          | menus | 2026-10-30 M |       |                                                             |
| menu | Options: Phone Lookup     | menus | 2026-10-31 M |       |                                                             |
| menu | Options: Sync...          | menus |              |       |                                                             |
| menu | Options: Security...      | menus | 2026-10-30 M |       |                                                             |
| menu | Options: About To Do List | menus | 2026-10-30 M |       |                                                             |

## Memo Pad

### Memo List

Palm reference: Handbook p.107 to 108. Code: `buildMemoList`.

| Kind    | Item                                                           | Code          | Look         | Works | Notes |
| ------- | -------------------------------------------------------------- | ------------- | ------------ | ----- | ----- |
| screen  | Title tab "Memo"                                               | tab           | 2026-10-30 M |       |       |
| trigger | Category                                                       | buildMemoList | 2026-10-30 M |       |       |
| button  | Memo row: opens the memo                                       | buildMemoList | 2026-10-30 M |       |       |
| button  | Memo row: touch & hold, then drag to move (manual sort)        | buildMemoList | 2026-10-30 M |       |       |
| icon    | Masked record: 50% gray bar with the lock icon; a tap shows it | maskedBar     | 2026-10-31 M |       |       |
| icon    | Scroll bar                                                     | withScrollBar |              |       |       |
| button  | New                                                            | buildMemoList | 2026-10-30 M |       |       |
| icon    | Find                                                           | render        |              |       |       |

### Memo view

Palm reference: Handbook p.82, 107 to 108. Code: `buildMemoEdit`.

| Kind    | Item                                 | Code          | Look         | Works | Notes |
| ------- | ------------------------------------ | ------------- | ------------ | ----- | ----- |
| screen  | Title tab "Memo N of M"              | buildMemoEdit | 2026-10-30 M |       |       |
| trigger | Category: a dotted box with no arrow | buildMemoEdit | 2026-10-31 M |       |       |
| field   | Lined page                           | linedPage     | 2026-10-30 M |       |       |
| icon    | Scroll bar                           | withScrollBar | 2026-10-30 M |       |       |
| button  | Done                                 | buildMemoEdit | 2026-10-30 M |       |       |
| button  | Details                              | buildMemoEdit | 2026-10-30 M |       |       |
| icon    | Find                                 | render        |              |       |       |

### Memo Details

Palm reference: Not in the handbook. Code: `showMemoDetails`.

| Kind    | Item           | Code            | Look | Works | Notes |
| ------- | -------------- | --------------- | ---- | ----- | ----- |
| dialog  | "Memo Details" | showMemoDetails |      |       |       |
| trigger | Category       | showMemoDetails |      |       |       |
| check   | Private        | showMemoDetails |      |       |       |
| button  | OK             | showMemoDetails |      |       |       |
| button  | Cancel         | showMemoDetails |      |       |       |
| button  | Delete...      | showMemoDetails |      |       |       |

### Delete Memo

Palm reference: Handbook p.64. Code: `deleteMemoAsk`.

| Kind   | Item                    | Code          | Look         | Works | Notes |
| ------ | ----------------------- | ------------- | ------------ | ----- | ----- |
| alert  | "Delete Memo"           | deleteMemoAsk |              |       |       |
| check  | Save archive copy on PC | palmAlert     | 2026-10-30 M |       |       |
| button | OK                      | palmAlert     | 2026-10-30 M |       |       |
| button | Cancel                  | palmAlert     |              |       |       |

### Memo Preferences

Palm reference: Handbook p.78. Code: `showMemoPreferences`.

| Kind    | Item               | Code                | Look         | Works | Notes |
| ------- | ------------------ | ------------------- | ------------ | ----- | ----- |
| dialog  | "Memo Preferences" | showMemoPreferences | 2026-10-30 M |       |       |
| trigger | Sort by            | showMemoPreferences | 2026-10-30 M |       |       |
| button  | OK                 | showMemoPreferences | 2026-10-30 M |       |       |
| button  | Cancel             | showMemoPreferences | 2026-10-30 M |       |       |

### Memo Pad menus

Palm reference: Handbook p.108 to 109. Code: `MainActivity.menus`.

| Kind  | Item                                                         | Code          | Look         | Works | Notes                                                       |
| ----- | ------------------------------------------------------------ | ------------- | ------------ | ----- | ----------------------------------------------------------- |
| menu  | Memo screen Record: New Memo                                 | menus         | 2026-10-31 M |       |                                                             |
| menu  | Record: Delete Memo...                                       | menus         | 2026-10-30 M |       |                                                             |
| menu  | Record: Export Backup...                                     | menus         |              |       | Kept: Export and Import replace Beam Memo and Beam Category |
| menu  | Record: Import Backup...                                     | menus         |              |       |                                                             |
| menu  | Options: Font...                                             | menus         | 2026-10-30 M |       |                                                             |
| menu  | Memo List Options: Preferences...                            | menus         | 2026-10-31 M |       |                                                             |
| menu  | Options: Sync...                                             | menus         |              |       |                                                             |
| menu  | Memo List Options: Security...                               | menus         | 2026-10-31 M |       |                                                             |
| menu  | Options: About Memo Pad                                      | menus         | 2026-10-30 M |       |                                                             |
| menu  | Memo screen Options: Go to Top of Page, Go to Bottom of Page | menus         | 2026-10-31 M |       |                                                             |
| menu  | Memo screen Options: Phone Lookup                            | menus         | 2026-10-31 M |       |                                                             |
| alert | "About Memo Pad"                                             | showMemoAbout |              |       |                                                             |

## Address Book

### Address List

Palm reference: Handbook p.71, 74, 97 (Palm form 1000). Code: `buildAddressList`.

| Kind    | Item                                                           | Code             | Look         | Works | Notes |
| ------- | -------------------------------------------------------------- | ---------------- | ------------ | ----- | ----- |
| screen  | Title tab "Address"                                            | tab              | 2026-10-30 M |       |       |
| trigger | Category                                                       | buildAddressList | 2026-10-30 M |       |       |
| button  | Name row: opens Address View                                   | addressRow       | 2026-10-30 M |       |       |
| icon    | Phone number & label letter                                    | addressRow       | 2026-10-30 M |       |       |
| icon    | Note icon                                                      | noteIcon         | 2026-10-31 M |       |       |
| icon    | Masked record: 50% gray bar with the lock icon; a tap shows it | maskedBar        | 2026-10-31 M |       |       |
| field   | Look Up                                                        | buildAddressList | 2026-10-30 M |       |       |
| button  | New                                                            | buildAddressList | 2026-10-30 M |       |       |
| icon    | Find                                                           | render           |              |       |       |

### Address View

Palm reference: No picture in the handbook. Code: `buildAddressView`.

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

Palm reference: Handbook p.97 to 99. Code: `buildAddressEdit`.

| Kind    | Item                                                                | Code             | Look         | Works | Notes |
| ------- | ------------------------------------------------------------------- | ---------------- | ------------ | ----- | ----- |
| screen  | Title tab "Address Edit"                                            | tab              | 2026-10-30 M |       |       |
| trigger | Category: a dotted box with no arrow                                | buildAddressEdit | 2026-10-31 M |       |       |
| field   | Name, Title & Company fields                                        | addressField     | 2026-10-30 M |       |       |
| trigger | Phone label of each of the five phone fields                        | buildAddressEdit | 2026-10-30 M |       |       |
| field   | Address, City, State, Zip Code, Country, Custom 1 to 4              | addressField     | 2026-10-30 M |       |       |
| field   | Auto-fill of Title, Company, City, and State from the other entries | autoFill         | 2026-10-31 M |       |       |
| button  | Done                                                                | buildAddressEdit | 2026-10-30 M |       |       |
| button  | Details...                                                          | buildAddressEdit | 2026-10-30 M |       |       |
| button  | Note                                                                | buildAddressEdit | 2026-10-30 M |       |       |
| icon    | Find                                                                | render           |              |       |       |

### Address Entry Details

Palm reference: Handbook p.70, 99 (Palm form 1200). Code: `showAddressDetails`.

| Kind    | Item                    | Code               | Look         | Works | Notes |
| ------- | ----------------------- | ------------------ | ------------ | ----- | ----- |
| dialog  | "Address Entry Details" | showAddressDetails | 2026-10-30 M |       |       |
| trigger | Show in List            | showAddressDetails | 2026-10-30 M |       |       |
| trigger | Category                | showAddressDetails | 2026-10-30 M |       |       |
| check   | Private                 | showAddressDetails | 2026-10-30 M |       |       |
| button  | OK                      | showAddressDetails | 2026-10-30 M |       |       |
| button  | Cancel                  | showAddressDetails | 2026-10-30 M |       |       |
| button  | Delete...               | showAddressDetails | 2026-10-30 M |       |       |
| button  | Note                    | showAddressDetails | 2026-10-30 M |       |       |

### Delete Address

Palm reference: Handbook p.64 (Palm form 1600). Code: `deleteAddressAsk`.

| Kind   | Item                    | Code             | Look         | Works | Notes |
| ------ | ----------------------- | ---------------- | ------------ | ----- | ----- |
| alert  | "Delete Address"        | deleteAddressAsk |              |       |       |
| check  | Save archive copy on PC | palmAlert        | 2026-10-30 M |       |       |
| button | OK                      | palmAlert        | 2026-10-30 M |       |       |
| button | Cancel                  | palmAlert        |              |       |       |

### Address Book Preferences

Palm reference: Handbook p.78, 101 (Palm form 2000). Code: `showAddressPreferences`.

| Kind   | Item                                                | Code                   | Look         | Works | Notes |
| ------ | --------------------------------------------------- | ---------------------- | ------------ | ----- | ----- |
| dialog | "Address Book Preferences"                          | showAddressPreferences | 2026-10-30 M |       |       |
| check  | Remember last category                              | showAddressPreferences | 2026-10-30 M |       |       |
| push   | List By: Last Name, First Name / Company, Last Name | showAddressPreferences | 2026-10-30 M |       |       |
| button | OK                                                  | showAddressPreferences | 2026-10-30 M |       |       |
| button | Cancel                                              | showAddressPreferences | 2026-10-30 M |       |       |

### Rename Custom Fields

Palm reference: Handbook p.101 (Palm form 1900). Code: `showRenameCustomFields`.

| Kind   | Item                   | Code                   | Look         | Works | Notes |
| ------ | ---------------------- | ---------------------- | ------------ | ----- | ----- |
| dialog | "Rename Custom Fields" | showRenameCustomFields | 2026-10-30 M |       |       |
| field  | Four field names       | showRenameCustomFields | 2026-10-30 M |       |       |
| button | OK                     | showRenameCustomFields | 2026-10-30 M |       |       |
| button | Cancel                 | showRenameCustomFields | 2026-10-30 M |       |       |

### Phone Number Lookup

Palm reference: Handbook p.75 (Palm form 2100). Code: `showPhoneLookup`.

| Kind   | Item                   | Code            | Look         | Works | Notes |
| ------ | ---------------------- | --------------- | ------------ | ----- | ----- |
| dialog | "Phone Number Lookup:" | showPhoneLookup | 2026-10-30 M |       |       |
| button | List row               | showPhoneLookup | 2026-10-30 M |       |       |
| field  | Look Up                | showPhoneLookup | 2026-10-30 M |       |       |
| button | Add                    | showPhoneLookup | 2026-10-30 M |       |       |
| button | Cancel                 | showPhoneLookup | 2026-10-30 M |       |       |

### Address Book sharing

Palm reference: Address Book: Beam (replaced). Code: `AddressShare.kt`.

| Kind  | Item                   | Code                  | Look | Works | Notes |
| ----- | ---------------------- | --------------------- | ---- | ----- | ----- |
| alert | "Select Business Card" | selectBusinessCardAsk |      |       |       |
| alert | "Export Addresses"     | exportVCard           |      |       |       |
| alert | "Import" (vCard)       | importVCardText       |      |       |       |
| alert | "About Address Book"   | showAddressAbout      |      |       |       |

### Address Book menus

Palm reference: Handbook p.100 to 101. Code: `MainActivity.menus`.

| Kind | Item                                         | Code                    | Look         | Works | Notes                             |
| ---- | -------------------------------------------- | ----------------------- | ------------ | ----- | --------------------------------- |
| menu | List Record: Share Category                  | menus                   |              |       | Kept: replaces Beam Category      |
| menu | List Record: Share Business Card             | menus                   |              |       | Kept: replaces Beam Business Card |
| menu | List Record: Import vCard...                 | menus                   |              |       |                                   |
| menu | List Record: Export vCard...                 | menus                   |              |       |                                   |
| menu | List Record: Export Backup...                | menus                   |              |       |                                   |
| menu | List Record: Import Backup...                | menus                   |              |       |                                   |
| menu | View/Edit Record: Delete Address...          | menus                   | 2026-10-30 M |       |                                   |
| menu | View/Edit Record: Attach Note                | menus                   | 2026-10-30 M |       |                                   |
| menu | View/Edit Record: Delete Note...             | menus                   | 2026-10-30 M |       |                                   |
| menu | View/Edit Record: Share Address              | menus                   |              |       | Kept: replaces Beam Address       |
| menu | View/Edit Record: Select Business Card...    | menus                   | 2026-10-30 M |       |                                   |
| menu | View/Edit Record: Share Business Card        | menus                   |              |       | Kept: replaces Beam Business Card |
| menu | List and View Record: Duplicate Address (/T) | menus, duplicateAddress | 2026-10-31 M |       |                                   |
| menu | List Options: Font...                        | menus                   | 2026-10-30 M |       |                                   |
| menu | List Options: Preferences...                 | menus                   | 2026-10-30 M |       |                                   |
| menu | List Options: Rename Custom Fields...        | menus                   | 2026-10-30 M |       |                                   |
| menu | List Options: Sync...                        | menus                   |              |       |                                   |
| menu | List Options: Security...                    | menus                   | 2026-10-30 M |       |                                   |
| menu | List Options: About Address Book             | menus                   | 2026-10-30 M |       |                                   |
| menu | View/Edit Options: Font...                   | menus                   | 2026-10-30 M |       |                                   |
| menu | View/Edit Options: Rename Custom Fields...   | menus                   | 2026-10-30 M |       |                                   |
| menu | View/Edit Options: About Address Book        | menus                   | 2026-10-30 M |       |                                   |

## Shared by the four apps

### Note view

Palm reference: Handbook p.80 to 81 (text only). Code: `buildNote`.

| Kind   | Item                          | Code          | Look         | Works | Notes |
| ------ | ----------------------------- | ------------- | ------------ | ----- | ----- |
| screen | Title tab: record description | buildNote     |              |       |       |
| field  | Lined page                    | linedPage     |              |       |       |
| icon   | Scroll bar                    | withScrollBar |              |       |       |
| button | Done                          | buildNote     | 2026-10-30 M |       |       |
| button | Delete...                     | buildNote     | 2026-10-30 M |       |       |
| alert  | "Delete Note"                 | buildNote     | 2026-10-30 M |       |       |
| menu   | Edit menu                     | menus         |              |       |       |
| menu   | Options: Font...              | menus         |              |       |       |

### Edit menu

Palm reference: Handbook p.63 to 64. Code: `MainActivity.menus`.

| Kind | Item                                                    | Code  | Look         | Works | Notes                          |
| ---- | ------------------------------------------------------- | ----- | ------------ | ----- | ------------------------------ |
| menu | Undo (no Edit menu on the Memo List or in Address View) | menus | 2026-10-31 M |       |                                |
| menu | Cut                                                     | menus | 2026-10-30 M |       |                                |
| menu | Copy                                                    | menus | 2026-10-30 M |       |                                |
| menu | Paste                                                   | menus | 2026-10-30 M |       |                                |
| menu | Select All                                              | menus | 2026-10-30 M |       |                                |
| menu | Keyboard                                                | menus | 2026-10-30 M |       |                                |
| menu | Graffiti Help (not in the apps)                         | menus |              |       | Kept: Graffiti is not included |

### Menu bar

Palm reference: Handbook p.24 to 25. Code: `MenuBar`.

| Kind   | Item                                                  | Code       | Look         | Works | Notes |
| ------ | ----------------------------------------------------- | ---------- | ------------ | ----- | ----- |
| screen | Menu bar: opens from the title tab                    | MenuBar    | 2026-10-30 M |       |       |
| button | Command letters while open; Ctrl & letter at any time | commandKey | 2026-10-30 M |       |       |

### Select Font

Palm reference: Handbook p.82. Code: `showFont`.

| Kind   | Item                                                                   | Code       | Look         | Works | Notes |
| ------ | ---------------------------------------------------------------------- | ---------- | ------------ | ----- | ----- |
| dialog | "Select Font"                                                          | showFont   | 2026-10-30 M |       |       |
| push   | A A A: small, bold, and large                                          | showFont   | 2026-10-31 M |       |       |
| button | OK                                                                     | showFont   | 2026-10-30 M |       |       |
| button | Cancel                                                                 | showFont   | 2026-10-30 M |       |       |
| icon   | Tips icon (i) at the right of the title of the Palm forms that have it | PalmDialog | 2026-10-31 M |       |       |
| dialog | "Tips"                                                                 | showTips   | 2026-10-31 M |       |       |
| button | Done                                                                   | showTips   | 2026-10-31 M |       |       |

### Change Security

Palm reference: Handbook p.78 to 80. Code: `showSecurity`.

| Kind    | Item                                                                              | Code            | Look         | Works | Notes |
| ------- | --------------------------------------------------------------------------------- | --------------- | ------------ | ----- | ----- |
| dialog  | "Change Security"                                                                 | showSecurity    |              |       |       |
| trigger | Show, Mask or Hide Records                                                        | showSecurity    | 2026-10-30 M |       |       |
| button  | OK                                                                                | showSecurity    |              |       |       |
| button  | Cancel                                                                            | showSecurity    |              |       |       |
| dialog  | "Show Private Records" (tap on a masked record, or Show Records, with a password) | askPassword     | 2026-10-31 M |       |       |
| button  | OK                                                                                | askPassword     | 2026-10-31 M |       |       |
| button  | Cancel                                                                            | askPassword     | 2026-10-31 M |       |       |
| alert   | "Invalid Password"                                                                | invalidPassword | 2026-10-31 M |       |       |
| trigger | Password: -Unassigned- or -Assigned-                                              | showSecurity    | 2026-10-31 M |       |       |
| dialog  | "Password": Enter the current password                                            | editPassword    | 2026-10-31 M |       |       |
| button  | OK: then a new password                                                           | editPassword    | 2026-10-31 M |       |       |
| button  | Cancel                                                                            | editPassword    | 2026-10-31 M |       |       |
| button  | Delete: removes the password                                                      | editPassword    | 2026-10-31 M |       |       |
| dialog  | "Password": Enter a password, then "Verify"                                       | newPassword     | 2026-10-31 M |       |       |
| button  | OK                                                                                | newPassword     | 2026-10-31 M |       |       |
| button  | Cancel                                                                            | newPassword     | 2026-10-31 M |       |       |
| alert   | "Invalid Password": the passwords do not match                                    | newPassword     | 2026-10-31 M |       |       |
| alert   | "Hide Records" and "Mask Records" confirmations, OK and Cancel                    | showSecurity    | 2026-10-31 M |       |       |

### Prefs: ShortCuts

Palm reference: Handbook p.196 to 197. Code: `PrefsApp.kt`.

| Kind   | Item                                                    | Code         | Look         | Works | Notes |
| ------ | ------------------------------------------------------- | ------------ | ------------ | ----- | ----- |
| screen | Prefs app: title "Prefs", the ShortCuts panel pick list | buildPrefs   | 2026-10-31 M |       |       |
| button | List row: selects the ShortCut                          | buildPrefs   | 2026-10-31 M |       |       |
| button | New                                                     | buildPrefs   | 2026-10-31 M |       |       |
| button | Edit...                                                 | buildPrefs   | 2026-10-31 M |       |       |
| button | Delete...                                               | buildPrefs   | 2026-10-31 M |       |       |
| alert  | "Delete ShortCut", Yes and No                           | buildPrefs   | 2026-10-31 M |       |       |
| dialog | "ShortCut Entry"                                        | editShortCut | 2026-10-30 M |       |       |
| field  | ShortCut Name                                           | editShortCut | 2026-10-30 M |       |       |
| field  | ShortCut Text                                           | editShortCut | 2026-10-30 M |       |       |
| button | OK                                                      | editShortCut | 2026-10-30 M |       |       |
| button | Cancel                                                  | editShortCut | 2026-10-30 M |       |       |

### Edit Categories

Palm reference: Handbook p.71 to 73. Code: `editCategories`.

| Kind   | Item              | Code           | Look         | Works | Notes                                            |
| ------ | ----------------- | -------------- | ------------ | ----- | ------------------------------------------------ |
| dialog | "Edit Categories" | editCategories | 2026-10-30 M |       |                                                  |
| button | List row          | editCategories | 2026-10-30 M |       |                                                  |
| button | OK                | editCategories | 2026-10-30 M |       |                                                  |
| button | New               | editCategories | 2026-10-30 M |       | Kept: no limit on categories; the Palm allows 15 |
| button | Rename            | editCategories | 2026-10-30 M |       |                                                  |
| button | Delete            | editCategories | 2026-10-30 M |       |                                                  |
| alert  | "Delete Category" | editCategories |              |       |                                                  |
| field  | Name form: field  | editCategories | 2026-10-30 M |       |                                                  |
| button | Name form: OK     | editCategories | 2026-10-30 M |       |                                                  |
| button | Name form: Cancel | editCategories | 2026-10-30 M |       |                                                  |
| alert  | Name in use       | editCategories |              |       |                                                  |

### Category pop-up

Palm reference: Handbook p.70. Code: `pickCategory`.

| Kind    | Item                                         | Code         | Look         | Works | Notes |
| ------- | -------------------------------------------- | ------------ | ------------ | ----- | ----- |
| trigger | Category list, with All & Edit Categories... | pickCategory | 2026-10-30 M |       |       |

### Find

Palm reference: Handbook p.74 to 75. Code: `showFind`.

| Kind   | Item                                                                                                          | Code            | Look         | Works | Notes                                                                      |
| ------ | ------------------------------------------------------------------------------------------------------------- | --------------- | ------------ | ----- | -------------------------------------------------------------------------- |
| dialog | "Find"                                                                                                        | showFind        | 2026-10-30 M |       |                                                                            |
| field  | Find text                                                                                                     | showFind        | 2026-10-30 M |       |                                                                            |
| button | OK                                                                                                            | showFind        | 2026-10-30 M |       |                                                                            |
| button | Cancel                                                                                                        | showFind        | 2026-10-30 M |       |                                                                            |
| dialog | "Find" results: "Datebook", "Addresses", "To Do Items", and "Memos", the current app first; no empty headings | showFindResults | 2026-10-31 M |       |                                                                            |
| button | Result row: opens the record                                                                                  | showFindResults | 2026-10-30 M |       |                                                                            |
| button | Cancel                                                                                                        | showFindResults | 2026-10-31 M |       |                                                                            |
| button | Find More: the next page of matches                                                                           | showFindResults | 2026-10-31 M |       |                                                                            |
| button | Stop (not in the app)                                                                                         | showFindResults |              |       | Kept: the search ends before the form shows, so there is no search to stop |

### Sync

Palm reference: palmsync. Code: `showSync`.

| Kind   | Item       | Code     | Look | Works | Notes |
| ------ | ---------- | -------- | ---- | ----- | ----- |
| dialog | "Sync"     | showSync |      |       |       |
| field  | Server     | showSync |      |       |       |
| field  | Token      | showSync |      |       |       |
| field  | Passphrase | showSync |      |       |       |
| button | OK         | showSync |      |       |       |
| button | Sync Now   | showSync |      |       |       |
| button | Turn Off   | showSync |      |       |       |
| button | Cancel     | showSync |      |       |       |

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

| Behavior                                                           | Where                                       | Code                | Look | Works        | Notes                                                                                                                                          |
| ------------------------------------------------------------------ | ------------------------------------------- | ------------------- | ---- | ------------ | ---------------------------------------------------------------------------------------------------------------------------------------------- |
| Swipe left or right: next or previous day, week, or month          | Date Book views                             | dispatchTouchEvent  |      |              |                                                                                                                                                |
| Tap outside a line in edit: saves it and ends the edit             | Day, To Do                                  | dispatchTouchEvent  |      |              |                                                                                                                                                |
| Type with no line selected: starts a new record                    | Day, To Do, Memo List, Address List         | dispatchKeyEvent    |      |              |                                                                                                                                                |
| Backspace in an empty line: ends the edit, deletes an empty record | Day, To Do, Memo view                       | BackEdit            |      |              |                                                                                                                                                |
| Back: Done in Memo view, Note view, Address View, Address Edit     | all apps                                    | onBackPressed       |      |              |                                                                                                                                                |
| Ctrl and a command letter: runs the menu command                   | all apps                                    | dispatchKeyEvent    |      |              |                                                                                                                                                |
| ^ and a ShortCut name, or Ctrl+Space: expands the ShortCut         | all text fields                             | palmShortCuts       |      |              |                                                                                                                                                |
| Keyboard stays open on a tall screen                               | all apps                                    | keepKeyboard        |      |              |                                                                                                                                                |
| Status bar hidden; swipe down shows it for a moment                | every window                                | hideStatusBar       |      |              |                                                                                                                                                |
| Alarm notification: Snooze and OK                                  | Date Book                                   | AlarmReceiver       |      |              |                                                                                                                                                |
| Alarm notification: OK or dismiss on phone or watch clears both    | Date Book, watch                            | Alarms.show         |      | 2026-11-09 D |                                                                                                                                                |
| Live Update: alarmed timed event in progress, counts to its end    | Date Book (Android 16)                      | refreshLive         |      | 2026-11-09 D |                                                                                                                                                |
| Agenda to the watch: after a change or sync, at boot and midnight  | Date Book                                   | WearBridge          |      | 2026-11-09 D |                                                                                                                                                |
| Watch Date Book tile: appointments that fit, by day                | watch                                       | AgendaTileService   |      |              |                                                                                                                                                |
| Watch To Do tile: first open To Dos; check box completes           | watch                                       | TodoTileService     |      |              |                                                                                                                                                |
| Watch tile tap: opens the watch screen (7 days and the To Dos)     | watch                                       | AgendaActivity      |      |              |                                                                                                                                                |
| To Do checked on the watch: completed on the phone                 | Date Book, watch                            | WearListener        |      |              |                                                                                                                                                |
| Sync on open, 1 s after a change, and hourly                       | all apps                                    | SyncEngine          |      |              |                                                                                                                                                |
| Font option changes every screen; dialogs keep the form font       | all apps                                    | render, Palm.views  |      |              |                                                                                                                                                |
| Masked private record: 50% gray bar with the lock; Hide removes it | every record list                           | isMasked, maskedBar |      |              |                                                                                                                                                |
| Empty list message                                                 | Day, Agenda, To Do, Memo List, Address List | build*              |      |              |                                                                                                                                                |

## Parity

A record feature belongs in every view that shows its records. Check a whole row when one cell changes. `-` means the feature does not apply to that view.

| Feature                                    | Day | Week | Month | Agenda | To Do | Memo List | Memo view | Address List | Address View | Address Edit | Helper                   |
| ------------------------------------------ | --- | ---- | ----- | ------ | ----- | --------- | --------- | ------------ | ------------ | ------------ | ------------------------ |
| Note icon, tap opens the note              | yes | -    | -     | yes    | yes   | -         | -         | yes          | -            | -            | noteIcon                 |
| Note view (Done, Delete...)                | yes | -    | -     | yes    | yes   | -         | -         | yes          | yes          | yes          | openNoteView             |
| Private masking (masked bar, tap shows it) | yes | yes  | -     | yes    | yes   | yes       | -         | yes          | yes          | -            | isMasked, maskedBar      |
| Category trigger                           | -   | -    | -     | yes    | yes   | yes       | yes       | yes          | -            | yes          | pickCategory             |
| Lined page with Palm scroll bar            | -   | -    | -     | -      | -     | yes       | yes       | -            | -            | -            | linedPage, withScrollBar |
| Dotted line under each text line           | yes | -    | -     | -      | yes   | -         | yes       | -            | -            | yes          | LinedBackground          |
| Delete with Save archive copy on PC        | yes | yes  | yes   | yes    | yes   | -         | yes       | -            | yes          | yes          | palmAlert                |
| Find icon                                  | yes | yes  | yes   | yes    | yes   | yes       | yes       | yes          | yes          | yes          | render                   |
| Title tab opens the menus                  | yes | yes  | yes   | yes    | yes   | yes       | yes       | yes          | yes          | yes          | tab                      |

## Rules found from faults

| Date       | Fault                                                                                                                                                                                            | Rule                                                                                                                                                                     |
| ---------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| 2026-10-30 | The new note icon was drawn from memory: a page folded at the top right with two text lines. The handbook's icon is a 7 by 9 page folded at the bottom right with no lines.                      | A Palm drawing is copied from the handbook's own pixels, never from memory. Fixed 2026-10-31, with the lock, the untimed diamond, and the Tips icon copied the same way. |
| 2026-10-30 | The note page was a full-screen dialog: a gray field underline across the page, and Done did not work. Notes had an icon only in the Day view.                                                   | A full-page text editor is a screen of the activity built with linedPage, never a dialog. A record feature is checked across its whole row in Parity.                    |
| 2026-10-30 | Found while building this file: field() in palm-ui keeps the Android underline, solid gray, in Address Edit and every dialog field.                                                              | Every text field draws the Palm dotted underline: field() takes LinedBackground, which moved into palm-ui. Fixed the same day.                                           |
| 2026-10-31 | The first check against the handbook used an older edition (archive.org, with no Agenda view). Its titles and title tap differ from the edition of the apps, so some of its findings were wrong. | Name the handbook edition and its source in the legend, and take each page number and picture from that edition.                                                         |
| 2026-11-04 | On a device, the Change Repeat push buttons and its Every and End on labels wrapped in their fixed widths, and a trailing gap squeezed the Note button of Event Details.                         | A label in a fixed width stays on one line, and a push cell grows to fit its label. A button row puts its gaps between the buttons. Fixed the same day.                  |
| 2026-11-04 | A pop-up list in a dialog opened below its trigger: the list window counted its x and y from the dialog corner. Menu drop-downs at the right wrapped their commands.                             | A pop-up list gives its place in its trigger window's coordinates. A menu drop-down moves left until it fits. Fixed the same day.                                        |
| 2026-11-04 | At the Bold and Large fonts, dialog labels and buttons overflowed their fixed Palm places, and the Sync button row was wider than the form.                                                      | Forms, menus, and lists keep the form font, as on the Palm; the Font option sets only the screens. A button row too wide narrows its buttons, then its gaps.             |
| 2026-11-04 | The Change Repeat summary box lost its right edge, and the Set Time minute list its last column: both end at form x 153, and the dialog body clipped at its padding, at x 152.                   | A dialog body does not clip at its padding, so a control placed out to the form edge draws whole. Fixed the same day.                                                    |
| 2026-11-09 | Date Book 1.16 crashed at every start on an Android 16 phone at API 36.0: the Live Update called setRequestPromotedOngoing, an API 36.1 method, behind an SDK_INT >= 36 check.                   | An API of a minor SDK (such as 36.1) sits behind a check of SDK_INT_FULL. Code that runs in onResume or at boot catches a rejected notification. Fixed in 1.17.          |
| 2026-11-11 | The watch widget icons were white glyphs; the host draws a widget's icon on a white disc above the card, so the dot showed blank.                                                                | A widget icon is a dark glyph (ic_agenda_widget, ic_todo_widget); the app icon stays white. Fixed the same day.                                                          |
| 2026-11-11 | The larger watch bubbles fit one Date Book entry and two To Dos, with black below: the card is 113 dp high and holds 97 dp of rows.                                                              | Size widget rows from the card's measured height: log heightDp and the padding, and screenshot the watch with adb screencap. Fixed the same day.                         |
