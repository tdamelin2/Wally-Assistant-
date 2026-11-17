# Health Connect check (PLAN Task 4.1)

What Garmin Connect really writes into Health Connect on Tim's phone. Wally keeps only records whose origin is `com.garmin.android.apps.connectmobile`.

- Date: 2026-11-09 (probe run three times at 10:18)
- Device: Samsung S25 (SM-S931B)
- Window: last 7 days up to 2026-11-09 10:18
- Permissions granted: yes (all four: sleep, steps, resting heart rate, exercise)
- Read failures: none
- Source log: `logs/phase-5-health-check.log` (record types and origin names only, no values)

## From com.garmin.android.apps.connectmobile

| Data | Seen from Garmin Connect? | Notes |
|---|---|---|
| Sleep | yes | 2 records per run |
| Steps | yes | Garmin records present |
| Resting heart rate | yes | 2 records per run |
| Exercise (workouts) | no (none seen; no Garmin workout recorded in the window; re-check with checklist line) | The read worked (count=0). Tim recorded no workout and did not wear the watch most of the week, so this is "no records in the window", not proof that Garmin does not write exercise. Update this row if a workout appears. |

## Origin strings seen exactly as logged

- `com.garmin.android.apps.connectmobile`
- `com.android.healthconnect.phone.jde348b705ae63bfc90ec9ae59c4fe07c` (the phone's own step counter; Wally ignores it)

## Notes

- A type marked "no" shows "no data" in Wally (decision D37).
- A denied permission or a failed read is not a "no": in that case Wally changes nothing and tries again at the next sync.
