# Ollama on the XPS laptop (the Chat tab)

What this does for you: the Chat tab asks questions about your own data. The AI that answers runs on your XPS laptop (it is much stronger than the little server). This guide installs Ollama (the program that runs the AI) on the XPS, downloads the model `qwen3.5:9b` (about 6 GB), and opens one door in the firewall so your phone and laptop can reach it over Tailscale only. When the XPS is off, the Chat tab says "XPS is offline" within 5 seconds.

You do this once on the XPS. It takes about 30 minutes, mostly waiting for the download. Each command goes in its own box: copy one box, press Enter, wait for it to finish, then copy the next.

The XPS Tailscale address used below is `xxxxxxxx` (from `ops/server-facts.md`). If Tailscale shows a different address for the XPS, use that one everywhere.

## 1. Install Ollama 0.40.14

1. Open https://github.com/ollama/ollama/releases/tag/v0.40.14 in the browser.
2. Under Assets, download `OllamaSetup.exe` (do not use ollama.com/download: it always gives the newest version, not this one).
3. Run `OllamaSetup.exe` and finish the installer.

Ollama updates itself later, so do not assume the version. Open a normal PowerShell window and run:

```
ollama --version
```

Write what it printed in the Results section at the bottom.

## 2. Download the model

This downloads about 6 GB.

```
ollama pull qwen3.5:9b
```

## 3. Make Ollama listen on the Tailscale address

Ollama must listen on the XPS Tailscale address (not on the home Wi-Fi address). Set this setting once (a user environment variable, a named setting Windows keeps for you):

```
[Environment]::SetEnvironmentVariable("OLLAMA_HOST", "xxxxxxxx:11434", "User")
```

Try to use the graphics card (GPU), which is much faster. These two settings turn it on:

```
[Environment]::SetEnvironmentVariable("OLLAMA_VULKAN", "1", "User")
```

```
[Environment]::SetEnvironmentVariable("OLLAMA_IGPU_ENABLE", "1", "User")
```

Now restart Ollama so it reads the settings: find the Ollama icon in the tray (bottom right, you may need the small arrow), right-click it, choose Quit. Then start Ollama again from the Start menu. Then close and reopen PowerShell, so it also knows the settings.

## 4. Open the firewall for Tailscale only

Windows blocks incoming connections by default. This opens port 11434 (the door Ollama uses) for the Tailscale network only, not for your home Wi-Fi.

Open PowerShell as administrator: Start menu, type `PowerShell`, right-click "Windows PowerShell", choose "Run as administrator". Then run:

```
New-NetFirewallRule -DisplayName "Ollama Tailscale" -Direction Inbound -Protocol TCP -LocalPort 11434 -InterfaceAlias Tailscale -Action Allow
```

## 4b. Allow your devices in Tailscale's access rules

Tailscale has its own list of who may talk to whom. Without a rule for the XPS, Tailscale silently drops every connection to the laptop, and nothing tells you why (McAfee may claim to be the firewall, but it was not the cause). Keep the existing rule for the HP and add one for the XPS.

1. Open https://login.tailscale.com/admin/acls and sign in.
2. Add a rule: Source `autogroup:member`, Destination `xxxxxxxx`, Port `tcp:11434`. In the JSON form it looks like this:

```
{"src": ["autogroup:member"], "dst": ["xxxxxxxx"], "ip": ["tcp:11434"]}
```

3. Save.

## 5. Test the model and see if the GPU is used

In a normal PowerShell window (the new one from step 3):

```
ollama run qwen3.5:9b "hello" --verbose
```

When it answers, look for the line `eval rate:` in the statistics (tokens per second). Write that number in the Results section. Then, right away, run:

```
ollama ps
```

In the `PROCESSOR` column it says `GPU`, `CPU`, or a mix such as `45%/55% CPU/GPU`. Write that in the Results section too.

## 6. If the GPU does not work or is unstable

Only do this if the test in step 5 crashed, froze the laptop, or gave an error. Remove the two GPU settings, then redo the restart of Ollama from step 3 and the test from step 5. The answers will be slower but safe. Record "CPU" and the new `eval rate` in the Results section.

```
[Environment]::SetEnvironmentVariable("OLLAMA_VULKAN", $null, "User")
```

```
[Environment]::SetEnvironmentVariable("OLLAMA_IGPU_ENABLE", $null, "User")
```

## 7. The address in both apps

Nothing to do: the laptop app and the phone app fill in the field `XPS Ollama URL` automatically with:

```
http://xxxxxxxx:11434
```

You can see it in Settings. Only change it if the laptop's Tailscale address changes.

## 8. Check who can reach it (these are the acceptance checks)

1. Phone, on Tailscale (Wi-Fi or mobile data, Tailscale app turned on): open `http://xxxxxxxx:11434/` in the phone browser. It must say `Ollama is running`.
2. Phone or another device on your home Wi-Fi but with Tailscale turned OFF: open `http://<the XPS home Wi-Fi address>:11434/`. It must NOT answer (the page does not load). To find the XPS home Wi-Fi address, run this on the XPS and look for `IPv4 Address` under Wi-Fi:

```
ipconfig
```

Write both results in the Results section.

## Good to know

- Other programs on the XPS that used Ollama at localhost must now use http://xxxxxxxx:11434.

- If the XPS starts Ollama at Windows sign-in before Tailscale has its address, Ollama cannot listen yet and the Chat tab says "XPS is offline". Fix: right-click the Ollama tray icon, choose Quit, and start Ollama again from the Start menu.
- When the XPS is off, asleep or Ollama is not running, the Chat tab says "XPS is offline - turn it on to chat". Nothing is lost.

## Results (fill in as you go; Claude copies the numbers in at the end)

| What | Value |
|------|-------|
| Ollama version (`ollama --version`) | 0.40.15 (already installed and self-updated; installer skipped) |
| GPU or CPU (`ollama ps` PROCESSOR column) | 100% GPU |
| Speed (`eval rate`, tokens per second) | 17.44 |
| GPU settings kept or removed | kept |
| Phone over Tailscale: `http://xxxxxxxx:11434/` says "Ollama is running"? | yes |
| XPS home Wi-Fi address does NOT answer? | yes |
| Date of the test | 2026-11-09 |
