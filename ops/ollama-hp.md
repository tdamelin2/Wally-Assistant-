# Ollama on the HP server (the morning briefing and meal estimates)

What this does for you: Ollama is the program that runs a small AI model. We install it on the home server (the HP) so that every morning at 7:00 the HP writes a short summary of your day, and every 15 minutes it guesses the calories of up to 5 of your meals. The model is small (`qwen3:1.7b`, about 1.4 GB) because the HP only has 4 GB of memory.

You do this once. It takes about 30 minutes, most of it waiting for downloads. Do the steps in order. Each command goes in its own box: copy one box, press Enter, wait for it to finish, then copy the next.

Word used here: an "ssh window" is a terminal window on your laptop that is connected to the HP. Anything you type there runs on the HP. Every step says where to type.

Before you start: the new server version (this phase) must be on the HP, because the "write a briefing now" test in step 9 needs it. Step 7 does that.

## 1. Open two ssh windows

Open a first terminal window on your laptop (PowerShell) and connect to the HP:

```
ssh hp
```

Leave it open. Open a second PowerShell window and connect again the same way. Keep it next to the first one. Call them window 1 and window 2.

```
ssh hp
```

## 2. Download the Ollama installer (window 1)

This only downloads a small text file, it does not install anything yet.

```
curl -fsSL https://ollama.com/install.sh -o /tmp/ollama-install.sh
```

Check whether the script understands a version number:

```
grep -n OLLAMA_VERSION /tmp/ollama-install.sh
```

If this printed some lines, use step 3A. If it printed nothing, use step 3B.

If you want to read the script first, run `less /tmp/ollama-install.sh` (press `q` to leave). This is optional.

## 3A. Install the pinned version 0.40.14 (window 1)

Use this only if step 2 printed lines.

```
OLLAMA_VERSION=0.40.14 sh /tmp/ollama-install.sh
```

## 3B. Install the current version (window 1)

Use this only if step 2 printed nothing.

```
sh /tmp/ollama-install.sh
```

If the installer stops and says `zstd` is missing, install it and then run the install box again:

```
sudo apt-get install -y zstd
```

## 4. Record the version (window 1)

```
ollama --version
```

Write what it printed in the Results section at the bottom of this file. Ollama listens only on the HP itself (`127.0.0.1:11434`). Leave that as it is.

## 5. Download the model (window 1)

This downloads about 1.4 GB.

```
ollama pull qwen3:1.7b
```

## 6. Measure memory while the model answers

In window 2, start a memory watcher. It prints a new reading every 2 seconds. Leave it running.

```
free -h -s 2
```

In window 1, ask the model a short question:

```
curl http://127.0.0.1:11434/api/chat -d '{"model":"qwen3:1.7b","think":false,"stream":false,"options":{"num_ctx":4096},"messages":[{"role":"user","content":"Say hello in 5 words"}]}'
```

While it runs, watch the `available` column in window 2 (the number at the end of the `Mem:` line). Note the LOWEST number you see. Then press Ctrl+C in window 2 to stop the watcher.

From the reply in window 1, find the two numbers `eval_count` and `eval_duration`. Speed in tokens per second = `eval_count` divided by (`eval_duration` divided by 1,000,000,000). Write the lowest available memory and the tokens per second in the Results section.

If the lowest available memory was below 300 MB, do the fallback in step 11 now, then come back to step 7.

## 7. Put the new server on the HP (your laptop, not an ssh window)

In a normal PowerShell window on your laptop, in the project folder:

```
powershell -ExecutionPolicy Bypass -File ops\deploy-server.ps1
```

Wait for it to say it finished. The server restarts by itself.

## 8. Tell the server about Ollama (window 1)

Open the config file:

```
sudo nano /etc/assistant/config.json
```

Look near the end of the file for a line that says `"ollama": {`.

If it is there, check that it matches the block below. If it is not there, add the block after the `"google"` block (do not forget the comma after the closing `}` of the block before it).

The end of the file must look like this (your own `tokens` and `google` parts stay as they are):

```
  "google": {
    ...your existing lines...
  },
  "ollama": {
    "url": "http://127.0.0.1:11434",
    "briefingModel": "qwen3:1.7b"
  }
}
```

Check these three things:
- There is a comma after the `}` that closes the block before `"ollama"`.
- There is NO comma after the `}` that closes the `"ollama"` block.
- The very last line of the file is a single `}`.

Save with Ctrl+O, Enter, then leave with Ctrl+X.

Check that the file is still valid. If this prints the file back, it is valid. If it prints an error with a line number, open the file again and fix that line:

```
python3 -m json.tool /etc/assistant/config.json
```

Only when the check printed the file back, restart the server:

```
sudo systemctl restart assistant
```

Check that it is running:

```
systemctl status assistant --no-pager
```

It should say `active (running)`. Then check the server still answers (replace `<laptop token>` with the laptop token, the same text the laptop app has in Settings):

```
curl -s -H "Authorization: Bearer <laptop token>" http://xxxxxxxx:8080/v1/health
```

## 9. Write a briefing now (laptop, to prove the whole chain works)

Run this in a normal PowerShell window on your laptop (replace `<laptop token>`):

```
curl.exe -X POST -H "Authorization: Bearer <laptop token>" http://xxxxxxxx:8080/v1/admin/briefing
```

Tip: type the token once as a variable, then use it, so a typo cannot hide in the long command. A typo gives HTTP 401 with no output at all. First run `$t = "<laptop token>"`, then use `-H "Authorization: Bearer $t"` in the command above instead of the long form.

It can take 1 to 3 minutes the first time, because the model has to load. Keep window 2 running `free -h -s 2` meanwhile if you can, and note the lowest `available` number again.

It must print a line starting with `{"localDate":"` and containing `"text":"..."`. Copy that text into the Results section. If it says `Briefing unavailable today`, the model call failed: look at the server log with `journalctl -u assistant -n 30 --no-pager` in window 1 and tell Claude what it says.

If it says `{"error":"briefing already running"}`, wait one minute and run it again.

## 10. Check the calorie guess (window 1)

This asks the model for calories as structured data, the way the server does.

```
curl http://127.0.0.1:11434/api/chat -d '{"model":"qwen3:1.7b","think":false,"stream":false,"format":{"type":"object","properties":{"kcal":{"type":"integer"}},"required":["kcal"]},"messages":[{"role":"user","content":"Meal: two eggs and toast"}]}'
```

In the reply, `message.content` must be a small JSON piece like `{"kcal":300}`. Copy it into the Results section.

## 11. Fallback only if memory was too low

Only do this if available memory dropped below 300 MB in step 6 or step 9. The smaller model `gemma3:1b` is about 806 MB.

```
ollama pull gemma3:1b
```

Open the config and change `"briefingModel": "qwen3:1.7b"` to `"briefingModel": "gemma3:1b"`:

```
sudo nano /etc/assistant/config.json
```

```
python3 -m json.tool /etc/assistant/config.json
```

```
sudo systemctl restart assistant
```

Then repeat step 9 (and step 10 with `gemma3:1b` in place of `qwen3:1.7b`) and write the new numbers in the Results section.

## 12. The Ollama settings file (Phase 18 and later)

The HP keeps its Ollama settings in one small file. It should hold these three lines (this is the full block; the first two came from Phase 17, the third lets the chat model and the small "sorter" model sit in memory together):

```
[Service]
Environment="OLLAMA_KEEP_ALIVE=30m"
Environment="OLLAMA_CONTEXT_LENGTH=8192"
Environment="OLLAMA_MAX_LOADED_MODELS=2"
```

The one-box command that writes it (type it in an ssh window on the HP, then run `sudo systemctl daemon-reload` and `sudo systemctl restart ollama`):

```
printf '[Service]\nEnvironment="OLLAMA_KEEP_ALIVE=30m"\nEnvironment="OLLAMA_CONTEXT_LENGTH=8192"\nEnvironment="OLLAMA_MAX_LOADED_MODELS=2"\n' | sudo tee /etc/systemd/system/ollama.service.d/override.conf
```

The step-by-step version with the measurement is in `ops/hp-brain-check.md`.

## Results (fill in as you go; Claude copies the numbers in at the end)

| What | Value |
|------|-------|
| Ollama version (`ollama --version`) | 0.40.14 (CPU only, no GPU) |
| Model used (`qwen3:1.7b` or `gemma3:1b`) | qwen3:1.7b |
| Lowest available memory during the hello test (step 6) | 1.4Gi (`free -h` right after, model loaded, assistant running; total 3.8Gi, used 2.4Gi) |
| Tokens per second (`eval_count` / (`eval_duration` / 1,000,000,000)) | 13.8 (eval_count 5 / 0.3629 s) |
| Lowest available memory during the briefing (step 9) | not measured (1.4Gi with model loaded) |
| Briefing text returned by the admin call (step 9) | `{"localDate":"2026-11-09","text":"Events today: Research Methods and Statistics, Fundamentals of Photography and Editing, Research Methods and Statistics, Introduction to Human-Computer Interaction, Exam Session 3 Final Exam, Introduction to Communication Research. Observation: The exam session is scheduled for the afternoon."}` |
| `message.content` of the calorie test (step 10) | `{"kcal": 1600000000000000}`. Note: this bare test has no system prompt, so the number is nonsense; the real server sends a system prompt and rejects any kcal above 5000. |
| Date and time of the test | 2026-11-09 |
