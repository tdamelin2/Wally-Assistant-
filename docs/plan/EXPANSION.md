# Future Wally apps — how they would plug in

Status: a note, not a plan (D59). Nothing here is built now. When a new app is actually
wanted, plan it with `deep-plan` as its own phase.

## What already makes this easy
- **One server, one sync.** Every entry type is a table with the same five columns
  (`id`, `updatedAt`, `deleted`, `serverSeq`, `syncState`). Push and pull work on any
  synced table, so a new kind of data needs no new endpoint.
- **Per-device tokens.** A new app or device gets its own token in
  `/etc/assistant/config.json`; nothing else changes on the server.
- **Database upgrades.** New tables arrive through SQLDelight migration files (D56), so
  existing data is kept.
- **Shared code.** Schema, sync engine, theme and components live in `shared`, which any
  new screen can use.

## Two ways a future app could arrive
1. **A new tab inside Wally (simplest; try this first).** For example "Dream log":
   a `dream` table + migration, validation on the server, a form and a list. Same as
   how the diet log was added in Phase 2.
2. **A separate app (only if a tab really doesn't fit).** For example "Wally Calendar"
   as its own Android/desktop app: a new app module that depends on `shared`, its own
   device token, the same server. Its tables would sync to the main Wally app too.

## Examples
| App | Probably | New data |
|-----|----------|----------|
| Wally Dream log | Tab | `dream` (date, text, mood) |
| Wally Study | Tab | `study_session` (subject, minutes, notes) |
| Wally Calendar | Tab first; separate app only if it needs writing to Google (D24 would have to change) | already has `cal_event` |
| Wally Newspaper | Separate decision: needs outside news sources, which nothing here reads today | unknown |

## Rules to keep when adding one
- Same sync rules: last edit wins, soft deletes, deterministic ids where one-per-key.
- New tables only through migrations; never edit an old migration.
- Uses the adaptive layout (Phase 5, D60): a new tab in the sidebar and bottom bar.
