# Hardware check: command probe (Phase 12, P12-A-05)

Run on the Dell XPS 16 (DA16260) laptop in a normal, non-admin Windows PowerShell 5.1 (5.1.26100), 2026-11-10.
Personal identifiers are written as `<redacted>`. These results decide how `HardwareCheck` parses each section.

## Summary

| Section | Command | Non-admin | Runtime | Output shape |
|---|---|---|---|---|
| Computer | `Get-CimInstance Win32_ComputerSystem \| Select-Object Manufacturer,Model,SystemType \| ConvertTo-Json -Compress` | works | ~0.2 s | one JSON object |
| BIOS | `Get-CimInstance Win32_BIOS \| Select-Object Manufacturer,SMBIOSBIOSVersion,ReleaseDate \| ConvertTo-Json -Compress` | works | ~0.02 s | one JSON object, `ReleaseDate` as `/Date(ms)/` |
| Battery | `powercfg /batteryreport /xml /output <temp>\battery.xml` | works, exit code 0 | ~0.6 s | XML file, namespace below |
| Disks | `Get-PhysicalDisk \| Select-Object FriendlyName,MediaType,HealthStatus,OperationalStatus,Size \| ConvertTo-Json -Compress` | works | 1.3 s (6.8 s on the first call) | JSON array (2 disks here), a plain object when only one disk |
| Disk wear counters | `Get-PhysicalDisk \| Get-StorageReliabilityCounter` | NEEDS ADMIN ("Access to a CIM resource was not available", CIM result 2) | 0.2 s | not available: do not use |
| Event log | `Get-WinEvent -FilterHashtable @{LogName='System';Level=1,2;StartTime=<7 days ago>}` | works | 0.3 s (160 events) | objects; see below |

Everything works without admin except disk reliability counters, so the check reads disk health from `HealthStatus` and
`OperationalStatus` only.

## Win32_ComputerSystem and Win32_BIOS

Use two separate calls, each piped to `Select-Object` with only the needed fields (never the whole object: it carries the serial
number and other identifiers).

```
{"Manufacturer":"Dell Inc.","Model":"XPS 16 DA16260","SystemType":"x64-based PC"}
{"Manufacturer":"Dell Inc.","SMBIOSBIOSVersion":"1.11.0","ReleaseDate":"\/Date(1785801600000)\/"}
```

- Both come back as one object, not an array. `ConvertTo-Json` in PowerShell 5.1 writes a single object as `{...}` and several as
  `[...]`, so the parser must accept an object or an array.
- `ReleaseDate` (and every .NET date serialised by 5.1) is `"\/Date(<epoch ms>)\/"`. The JSON text contains the escaped slashes
  `\/`; after JSON decoding it is `/Date(1785801600000)/`. Parse the digits between `(` and `)` (they can be followed by an offset
  such as `+0300`; ignore it, the number is UTC epoch ms).
- `SerialNumber` exists on Win32_BIOS (`<redacted>`). Do not select it.

## Battery: `powercfg /batteryreport /xml`

Command: `powercfg /batteryreport /xml /output "<user temp>\battery.xml"`. Prints `Battery life report saved to file path ...`, exit
code 0, no admin needed. Delete the file afterwards.

- Root element `BatteryReport`, default namespace `http://schemas.microsoft.com/battery/2012` (every element is in it, so the parser
  must be namespace-aware or match on local names).
- Root children, in order: `ReportInformation`, `SystemInformation`, `Batteries`, `RuntimeEstimates`, `RecentUsage`, `History`,
  `EnergyDrains`.
- Under `Batteries`, one `Battery` element per battery (a laptop with no battery has an empty or missing `Batteries`). Its children:
  `Id`, `Manufacturer`, `SerialNumber`, `ManufactureDate`, `Chemistry`, `LongTerm`, `RelativeCapacity`, `DesignCapacity`,
  `FullChargeCapacity`, `CycleCount`.

```
<Battery><Id>DELL <redacted></Id><Manufacturer>BYD</Manufacturer><SerialNumber><redacted></SerialNumber>
<ManufactureDate></ManufactureDate><Chemistry>LION</Chemistry><LongTerm>1</LongTerm><RelativeCapacity>0</RelativeCapacity>
<DesignCapacity>68607</DesignCapacity><FullChargeCapacity>66274</FullChargeCapacity><CycleCount>79</CycleCount></Battery>
```

- `DesignCapacity`, `FullChargeCapacity`: integers in mWh (68607 and 66274 here, so health = 66274 / 68607 = 96.6%).
- `CycleCount`: integer (79 here). Can be 0 on batteries that do not report it.
- Do not read or store `Id`, `SerialNumber`. `ManufactureDate` is empty here.
- `RecentUsage` holds 147 `UsageEntry` elements (attributes, not child elements); the check does not need them.

## Disks: Get-PhysicalDisk

```
[{"FriendlyName":"Generic Mass-Storage","MediaType":"Unspecified","HealthStatus":"Healthy","OperationalStatus":"OK","Size":3965190144},
 {"FriendlyName":"BG7 KIOXIA 1024GB","MediaType":"SSD","HealthStatus":"Healthy","OperationalStatus":"OK","Size":1024209543168}]
```

- Two disks here (the first is a USB stick), so a JSON array. With one disk PowerShell 5.1 prints a single object (confirmed by
  filtering to one disk). Parse both, or wrap with `@(...)` in the command: `ConvertTo-Json -Compress -InputObject @(...)` always writes an array.
- `HealthStatus` and `OperationalStatus` come back as strings (Healthy / Warning / Unhealthy, OK / ...). `Size` is bytes (integer).
  `MediaType` is `SSD`, `HDD` or `Unspecified`.
- Wear, temperature and error counters (`Get-StorageReliabilityCounter`) need admin: not collected.

## Windows errors: Get-WinEvent System, Level 1 and 2, last 7 days

Command: `Get-WinEvent -FilterHashtable @{LogName='System';Level=1,2;StartTime=(Get-Date).AddDays(-7)} -ErrorAction SilentlyContinue`
then `Select-Object TimeCreated,Id,ProviderName,Level,LevelDisplayName,Message`. 160 events took 0.3 s.

```
{"TimeCreated":"\/Date(1794297082983)\/","Id":24641,"ProviderName":"Microsoft-Windows-BitLocker-Driver","Level":2,"LevelDisplayName":"Error","Message":"An unexpected error was encountered attempting to retrieve the BitLocker volume master key during restart."}
```

- `TimeCreated` uses the same `/Date(ms)/` form. `Level`: 1 Critical, 2 Error. `Id` integer, `ProviderName` string, `Message` string
  (can be long and multi-line; truncate it when storing).
- One event: `ConvertTo-Json` gives an object, not an array (confirmed with `-MaxEvents 1`); wrap with `@(...)` or accept both.
- No matching events: `Get-WinEvent` writes a non-terminating error `NoMatchingEventsFound` ("No events were found that match the
  specified selection criteria.") and returns nothing. With `-ErrorAction SilentlyContinue` the output is empty (not `[]` and not
  `null`), and with `-ErrorAction Stop` it is a terminating error with that same FullyQualifiedErrorId. An empty stdout with exit
  code 0 therefore means "no events", not "failed": treat it as an empty list. To tell "no events" from "denied", the denied case
  (for example `Get-WinEvent -LogName Security`) throws `System.UnauthorizedAccessException` ("Attempted to perform an unauthorized
  operation"), so use `-ErrorAction Stop` inside try/catch and compare the FullyQualifiedErrorId.
- Summarise by count and top providers/ids rather than storing all 160 messages.

## Text encoding (Hebrew and other non-ASCII)

When a Java `ProcessBuilder` pipe reads PowerShell's stdout, PowerShell uses the console code page. Test with the word
"shalom" in Hebrew letters built from code points:

| Setup | Bytes for the Hebrew word | Result |
|---|---|---|
| default | `3F 3F 3F 3F` | lossy: every letter became `?` |
| `[Console]::OutputEncoding=[Text.Encoding]::UTF8;` first | `D7 A9 D7 9C D7 95 D7 9D` | correct UTF-8, no BOM |

So every PowerShell command must start with `[Console]::OutputEncoding=[Text.Encoding]::UTF8;` and the Kotlin side must decode
stdout as UTF-8. The battery XML is a file written by powercfg (read it as UTF-8 / let the XML parser detect it), so it does not go
through the console.

## Calling convention for the runner

- Executable `powershell.exe` (full path under `%SystemRoot%\System32\WindowsPowerShell\v1.0`), arguments
  `-NoProfile -NonInteractive -ExecutionPolicy Bypass -Command <one fixed command string>`. `powercfg.exe` is called directly.
- Windows PowerShell 5.1: no `&&`, no `?.`; `ConvertTo-Json` of one item is an object, of many an array, dates are `/Date(ms)/`.
- Timeouts: every command above finished in under 7 s; the 30 s limit in the contract is generous.
