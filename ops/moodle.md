# Moodle connection (Phase 9 probe)

Probed 2026-11-09 from the XPS with PowerShell. Result: all three calls and a file download worked. All secrets are redacted below; nothing secret is stored in this file.

## Address
- Base URL: `https://moodle.example.edu`
- Web service endpoint: `POST https://moodle.example.edu/webservice/rest/server.php` with form fields `wstoken`, `wsfunction`, `moodlewsrestformat=json` plus the function's own fields.
- Site: sitename `Moodle2027`, release `4.5.15 (Build: 20261005)`, version `2024100715`, 443 functions available to the token (includes the three used here).

## How the link was captured
Tim opened the launch URL (`admin/tool/mobile/launch.php`) in Chrome, then in DevTools > Network tab (with "Preserve log" on) clicked the `launch.php` request > Response Headers > `location`. That header holds the `moodlemobile://token=...` link (the browser cannot open it, so the address bar never shows it). He saved it in `logs/moodle-link.txt` (not committed).

## Link format
- Scheme actually returned: `moodlemobile://` (NOT `wally://`). The server code must accept both `moodlemobile://token=` and `wally://token=`.
- Shape: `<scheme>://token=<base64>`. The base64 text (may be URL-escaped, may lack `=` padding) decodes to UTF-8 text with three parts joined by `:::`:
  1. a first part that is not a URL (so it is NOT the site URL; in this link it has no host; treat as a site/signature value and ignore it)
  2. the web-service token (full-access; secret)
  3. the privatetoken (secret)
- The base URL must therefore come from config, not from the link.
- Decode steps used: strip `^[^:]+://token=`, URL-unescape, pad with `=` to a multiple of 4, base64 decode, split on `:::`, take part 2 as the token.

## Calls
All three succeeded (HTTP 200, no `exception` field). Errors from Moodle come back as a JSON object with `exception`, `errorcode`, `message`; success for the list calls is a JSON array.

### 1. core_webservice_get_site_info (no extra fields)
Top-level keys: sitename, username, firstname, lastname, fullname, lang, userid, siteurl, userpictureurl, functions, downloadfiles, uploadfiles, release, version, mobilecssurl, advancedfeatures, usercanmanageownfiles, userquota, usermaxuploadfilesize, userhomepage, userprivateaccesskey, siteid, sitecalendartype, usercalendartype, userissiteadmin, theme, limitconcurrentlogins, policyagreed.
Excerpt (redacted):
```
{"sitename":"Moodle2027","username":"[REDACTED]","fullname":"[REDACTED]","userid":<int>,
 "release":"4.5.15 (Build: 20261005)","version":"2024100715","downloadfiles":1,
 "userprivateaccesskey":"[REDACTED]","functions":[{"name":"core_course_get_contents",...},...]}
```
`userid` is used for call 2. `userprivateaccesskey` is secret.

### 2. core_enrol_get_users_courses (`userid=<from site info>`)
11 courses returned. Keys per course: id, shortname, fullname, displayname, enrolledusercount, idnumber, visible, summary, summaryformat, format, courseimage, showgrades, lang, enablecompletion, completionhascriteria, completionusertracked, category, progress, completed, startdate, enddate, marker, lastaccess, isfavourite, hidden, overviewfiles, showactivitydates, showcompletionconditions, timemodified.
Excerpt (redacted to ids/short names):
```
[{"id":1000001,"shortname":"Course A-0001-2027/1 - 1000001","visible":1,"startdate":0,"enddate":0,"hidden":false,...},
 {"id":1000002,"shortname":"Course B-0002-2027/0 - 1000002","visible":1,"startdate":1789542300,"enddate":0,...}, ...]
```
Observation: all 11 courses had `visible=1` and `enddate=0`; `startdate` was 0 for most. So `enddate` cannot be used to tell current from past courses here (treat `enddate=0` as "no end"); use `visible` (and `hidden`) instead.

### 3. core_course_get_contents (`courseid=1000001`)
4 sections. Section keys: id, name, visible, summary, summaryformat, section, hiddenbynumsections, uservisible, component, itemid, modules. Each module has `modname` and a `contents` list. 42 file entries (`type=file`) were found in this course.
File entry keys: type, filename, filepath, filesize, fileurl, timecreated, timemodified, sortorder, mimetype, isexternalfile, userid, author, license.
Excerpt (redacted; `fileurl` token query is not part of the returned URL):
```
{"type":"file","filename":"Unit 1 slides.pdf","mimetype":"application/pdf",
 "filesize":308216,"timemodified":1791356889,
 "fileurl":"https://moodle.example.edu/webservice/pluginfile.php/100000/mod_folder/content/6/Unit%201%20slides.pdf"}
```
Notes: the files seen were inside `folder` modules (`modname=folder`); other module types (`resource`) must be scanned too. Filenames can be Hebrew (percent-encoded in `fileurl`, UTF-8 in `filename`) and can have a trailing space before the extension. Fields used by the plan: `fileurl`, `filename`, `mimetype`, `filesize`, `timemodified`, plus course `visible` and `enddate`.

## File download
- Method: GET the `fileurl` with `?token=<token>` appended (`&token=` if the URL already has a query). Used `Invoke-WebRequest -OutFile`.
- File: `Unit 1 slides.pdf` (application/pdf)
- Declared `filesize`: 308216 bytes; downloaded size: 308216 bytes (match).
- Saved as `logs/moodle-probe/probe-download.bin` (not committed).

## Differences from SPEC
- SPEC and the Tim flow say `wally://token=...`; the link actually returned uses `moodlemobile://token=...`. Accept both schemes.
- The decoded link has three `:::`-separated parts and the first is not the site URL; take the token from part 2 and the base URL from config.
- `enddate` is 0 for every course, so it cannot filter current courses; filter on `visible`/`hidden`.
- List calls return a bare JSON array on success, so check for an error object only when the response is not an array.
- Files appear under `folder` modules as well as `resource` modules.
- No other differences found; the three SPEC calls all work with this token.

## Setup on the HP (what Tim does, one step at a time)
The server reads the Moodle address from its config file. Add a `moodle` block; the `baseUrl` is the public address of the school site (it may contain a path, a trailing `/` is ignored). It is not a secret and the apps use it to build the login link.

1. Open the config file on the HP:
   ```
   ssh -t hp sudo nano /etc/assistant/config.json
   ```
2. Add this block next to the existing `google` and `ollama` blocks (do not forget the comma on the line before it):
   ```
   "moodle": {
     "baseUrl": "https://moodle.example.edu"
   }
   ```
   The whole file then has this shape (secrets are only examples, keep your real ones):
   ```
   {
     "bindAddress": "100.x.y.z",
     "port": 8080,
     "dbPath": "/var/lib/assistant/assistant.db",
     "tokens": { "phone": "...", "laptop": "..." },
     "google": { "clientId": "...", "clientSecret": "..." },
     "ollama": { "url": "http://127.0.0.1:11434", "briefingModel": "qwen3:1.7b" },
     "moodle": { "baseUrl": "https://moodle.example.edu" }
   }
   ```
3. Check the file is valid. This prints nothing when it is correct; otherwise it prints only a line and column number, so go back and fix that spot:
   ```
   ssh -t hp "sudo python3 -m json.tool /etc/assistant/config.json > /dev/null"
   ```
4. Restart the server:
   ```
   ssh -t hp sudo systemctl restart assistant
   ```
5. Deploy the new server before installing the new apps (`powershell -ExecutionPolicy Bypass -File ops\deploy-server.ps1`). Without the `moodle` block everything else keeps working: uploads, reading text and study sets work, and only "Paste Moodle link" and "Check now" answer "Moodle not set up on the server".

## Connecting Moodle (every few weeks or when the school logs you out) - do it on the laptop in Chrome
A phone or laptop browser throws the `moodlemobile://` link away without showing it, so you capture it from the browser's network log:
1. In Chrome on the laptop press F12, open the **Network** tab and tick **Preserve log**.
2. In the Wally Settings tab press "Open Moodle login" (or open `https://moodle.example.edu/admin/tool/mobile/launch.php?service=moodle_mobile_app&passport=12345&urlscheme=moodlemobile` yourself) and log in to the school.
3. In the Network list click the row named `launch.php`, open **Headers**, scroll to **Response Headers** and copy the value of `location`. It starts with `moodlemobile://token=`.
4. Paste it in Settings > Moodle > "Paste Moodle link" and press Connect. It shows how many courses were found.
5. The link is a full-access key to your school account. Do not paste it in chat or save it in a file in the project.
Pasting works on the phone too, but reconnect from the laptop because the capture needs F12.

The token is stored on the HP in `/var/lib/assistant/moodle-token.json` (readable only by the server user). It is never in the config file, the repo, the logs or the backup.

## Which courses are checked
All your courses appear in the Study tab, but a course is checked for files only when you switch it on ("Check files"). Moodle's end dates are all 0 here, so Wally cannot tell old courses from current ones. Switching a course on starts a check by itself; switching it off keeps the files already listed. The server checks once a day after 06:00 (Jerusalem time) and when you press "Check now". Only PDF and PPTX files up to 50 MB are used.

## If Moodle stops working
- Study tab shows "Reconnect Moodle": the school rejected the saved login. Connect again as above.
- After a restore from backup the login file is gone: connect again. Moodle files come back by themselves on the next check; hand-uploaded files must be uploaded again (the backup holds the database only).

## Differences from SPEC (Phase 9 decisions that replace the probe's open points)
- The launch URL uses `urlscheme=moodlemobile`.
- The `enddate` rule is replaced by the per-course switch described above.
