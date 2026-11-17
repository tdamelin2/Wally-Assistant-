# Codex CLI probe (Phase 13, P13-B-01)

Date: 2026-11-10, run on the XPS laptop. Only harmless probes and the 5 fixed NUTRITION questions were used (no personal data in any prompt). No credential or auth file was read, printed or copied; CODEX_HOME and Codex settings were not changed.

## 1. Executable and version
- `Get-Command codex -All` finds three entries, all npm shims: `C:\Users\user\AppData\Roaming\npm\codex.ps1` (ExternalScript), `...\npm\codex.cmd` (Application, a batch file that starts node on `codex.js`) and `...\npm\codex` (extensionless sh shim).
- The real native executable (start this one, never the `.cmd`, so no cmd.exe quoting is involved):
  `C:\Users\user\AppData\Roaming\npm\node_modules\@openai\codex\node_modules\@openai\codex-win32-x64\vendor\x86_64-pc-windows-msvc\bin\codex.exe`
  Resolve it at run time from the `codex.cmd` directory (`<npm dir>\node_modules\@openai\codex\node_modules\@openai\codex-win32-x64\vendor\x86_64-pc-windows-msvc\bin\codex.exe`); fall back to `codex` on PATH only if that file is missing (then it is a shim and "Codex is not set up on the server" applies if it cannot start).
- `codex --version`: **codex-cli 0.167.0** (the Plan assumed 0.166.0; the flags below were confirmed on 0.167.0).
- `codex login status`: "Logged in using ChatGPT".

## 2. `codex exec --help`: SPEC flags
| SPEC flag | Exists in 0.167.0 | Note |
|---|---|---|
| `--skip-git-repo-check` | yes | needed, the temp dir is not a git repo |
| `--ephemeral` | yes | no session files persisted |
| `-s read-only` / `--sandbox` | yes | values: read-only, workspace-write, danger-full-access |
| `-c web_search="live"` | yes | `-c key=value`, value parsed as TOML; works |
| `-C <DIR>` / `--cd` | yes | |
| `--output-schema <FILE>` | yes | |
| `-o <FILE>` / `--output-last-message` | yes | |
| prompt from stdin | yes | `[PROMPT]` omitted or `-` reads stdin. If both a prompt argument and piped stdin exist, stdin is appended as a `<stdin>` block, so pass only `-` (and no prompt argument) and write the question to stdin |

Also present and useful: `--ignore-user-config`, `--ignore-rules`, `--disable <FEATURE>`, `--strict-config`, `--json` (JSONL events on stdout), `-m`, `--color`.
Schema note: the output schema works with `type: object`, all properties listed in `required` and `additionalProperties: false` (the shape the model API accepts for structured output). Use that shape for all four category schemas.

## 3. Isolation in an empty -C dir
Without isolation the user config at `$CODEX_HOME/config.toml` is loaded. On this laptop it holds many `[projects...]` entries, `[plugins...]` (Google Calendar, Slack, documents, browser, computer-use and others), `[features]` and `[mcp_servers.*]`. `codex mcp list` (reads the same config) shows these MCP servers: `codex_app` (disabled), `cua_repl`, `node_repl`, `telegram` (all enabled). So by default a research run could start browser/computer-use and Telegram tools. `~/.codex` also has a `memories` folder and `rules` (the `memories` feature is off by default; `rules` holds execpolicy files). No `AGENTS.md` exists in `~/.codex`; an empty -C dir has no project AGENTS.md or `.codex` either.

Supported flags that turn these off:
- `--ignore-user-config`: does not load `$CODEX_HOME/config.toml` (so no plugins, MCP servers, projects, features from it); **auth still uses CODEX_HOME**, confirmed: runs with it succeeded while logged in.
- `--ignore-rules`: does not load user or project execpolicy `.rules`.
- `-C <empty temp dir>` removes project-level config/instructions.
- `-s read-only` plus `--ephemeral` as in the SPEC.

Evidence run: with the final command plus `--json`, the event list for one NUTRITION question was only `thread.started`, `turn.started`, 2 `agent_message`, 1 `web_search` (started and completed) and `turn.completed`: no MCP tool, shell or plugin calls.

**Recommended final command (isolation decision for the manager):**
```
<codex.exe> exec --skip-git-repo-check --ephemeral -s read-only -c web_search="live" --ignore-user-config --ignore-rules -C <new empty temp dir> --output-schema <schema.json> -o <out.json> -
```
with the question written to stdin. This adds only `--ignore-user-config --ignore-rules` and the trailing `-` to the SPEC command. Environment variables are inherited (CODEX_HOME stays the default so login works).

## 4. Five NUTRITION runs (final command above, empty temp dir each, stdin prompt = the SPEC template)
Template: "Nutrition facts per 100 g of {item}. Return kcal, proteinG, carbsG, fatG and source URLs." Schema: `{"kcal","proteinG","carbsG","fatG": number, "sources": [string]}`, all required, no extra properties.

| Food | Wall time | Exit | Schema-valid | Output |
|---|---|---|---|---|
| hummus | 16.8 s | 0 | yes | `{"kcal":166,"proteinG":7.9,"carbsG":14.29,"fatG":9.6,"sources":["https://pmc.ncbi.nlm.nih.gov/articles/PMC5188421/","https://www.cooks.com/rec/nutrition?nwal=16158&nwgt=100"]}` |
| banana | 16.2 s | 0 | yes | `{"kcal":89,"proteinG":1.09,"carbsG":22.84,"fatG":0.33,"sources":["https://fdc.nal.usda.gov/food-details/173944/nutrients","https://whatyoueat.io/foods/173944-bananas"]}` |
| white rice | 16.3 s | 0 | yes | `{"kcal":130,"proteinG":2.69,"carbsG":28.17,"fatG":0.28,"sources":["https://fdc.nal.usda.gov/food-details/169757/nutrients","https://www.ars.usda.gov/ARSUserFiles/80400535/Data/SR/SR28/reports/sr28fg20.pdf"]}` |
| chicken breast | 17.3 s | 0 | yes | `{"kcal":165,"proteinG":31.02,"carbsG":0,"fatG":3.57,"sources":["https://fdc.nal.usda.gov/food-details/171477/nutrients","https://www.cooks.com/rec/nutrition?nwal=05064"]}` |
| olive oil | 16.0 s | 0 | yes | `{"kcal":884,"proteinG":0,"carbsG":0,"fatG":100,"sources":["https://whatyoueat.io/foods/171413-olive-oil","https://megrami.com/en/foods/olive-oil"]}` |

Result: 5 of 5 schema-valid (gate needs 4 of 5). An earlier hummus run with `--json` (18.4 s, exit 0) was also valid. Typical wall time about 16-18 s. The values match known USDA numbers. stdout carries the progress text; the answer is read only from the `-o` file. stderr was empty in these runs.

## 5. For Tim (data controls)
Before research is used automatically, in ChatGPT go to Settings > Data controls and turn **off** "Improve the model for everyone". The manager confirms with Tim; not yet confirmed here.

## 6. Error texts
- Usage/rate limit: unknown, not seen (no limit was hit and `--help` gives no text). The Plan's detection stays: case-insensitive "usage limit" or "rate limit" in stdout, stderr or the output file.
- Not logged in: unknown text (could not be provoked without touching auth). `codex login status` prints "Logged in using ChatGPT" when logged in; treat a failed start, or a nonzero exit whose output mentions login/auth, as "Codex is not set up on the server". The implementation should also treat a missing executable the same way.

## 7. Low-effort run through the production code (P13-B-04, 2026-11-10)
The final command now also has `-c model_reasoning_effort="low"` (Tim, 12:10). One real NUTRITION run (hummus) went through `CodexResearch` + `ProcessCodexProcess` (ProcessBuilder argument vector, question on stdin, empty temp cwd, `--ignore-user-config --ignore-rules`): the flag is accepted together with `--ignore-user-config`, exit 0, schema-valid, **17.1 s** (about the same as the earlier runs). Answer: `Per 100 g: 166 kcal, protein 7.9 g, carbs 14.29 g, fat 9.6 g`, 2 source URLs. The embedded quotes in `web_search="live"` and `model_reasoning_effort="low"` survive Java's Windows argument quoting. Re-run with `$env:WALLY_CODEX_PROBE='1'; .\gradlew :shared:jvmTest --tests "*CodexRealRunTest*"` (writes shared/build/codex-probe.txt).