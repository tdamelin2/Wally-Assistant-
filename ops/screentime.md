# Screen time (Wally 1.8.0)

Wally records which app you use on the phone and the laptop, so the Screentime tab can show productive and unproductive time.

## Phone
1. Open Settings, Screen time, and tap "Open usage access". Android opens its "Usage access" list.
2. Switch Wally on. Without this, Wally records nothing and says so.
3. Sessions shorter than 5 seconds are ignored. Wally re-reads the last 7 days at most, so a few days off is fine.

## Laptop
- Wally samples the active window every 5 seconds (app name, window title, idle time). Idle for 2 minutes or more ends the session.
- It keeps running in the system tray when you close the window. Settings has "Keep running in tray" and "Start with Windows". Use tray -> Quit to really stop it (the last session is saved first).
- "Start with Windows" only works in the installed app, not in a dev run.
- Only one Wally runs per database; opening it a second time brings the first window forward.

## No-admin check (2026-11-10, XPS laptop)
Ran the sampler as a normal user (no "Run as administrator") with `.\gradlew :desktopApp:probeSampler`, a temporary tool removed afterwards. It printed the foreground app (`windowsterminal.exe`), the window title and the idle time, so no admin rights are needed.
Windows may show nothing for some elevated (admin) windows: those count as "unknown" and end the session.

## Labels
Each app or window title gets a label: Productive, Unproductive or Neutral, kept as rules in the Screentime tab. New apps are labelled by the AI job (Accept to keep a suggestion).
