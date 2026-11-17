# HP brain check (Phase 18, Task 18.1)

What this does for you: before we build Wally's new "brain", we measure whether the home server (the HP) is fast and accurate enough. The test checks two small AI models on the HP: one that sorts your sentences by meaning (`embeddinggemma`) and the chat model (`llama3.2:3b`). Nothing here changes your data. If the numbers are too low, we stop and you decide what to do.

You do it once, in order. Copy one box, press Enter, wait for it to finish, then copy the next. "Laptop" means a normal PowerShell window in the project folder. "ssh window" means a window where you first ran `ssh hp`; everything typed there runs on the HP.

Words used here: a "model" is the AI program Ollama runs; "RAM" is the HP's working memory.

## 1. Check what is loaded (laptop)

Nothing should be loaded now.

```
ssh hp ollama ps
```

If a model is listed, stop it (replace `<name>` with the name in the list), then run the box above again:

```
ssh hp ollama stop <name>
```

## 2. Check the chat model is there (laptop)

The list must show `llama3.2:3b`.

```
ssh hp ollama list
```

## 3. Download the sorting model (laptop)

About 622 MB.

```
ssh hp ollama pull embeddinggemma
```

## 4. Update the Ollama settings (ssh window)

This keeps both models ready in memory. It rewrites one small settings file with three lines.

```
ssh hp
```

```
sudo mkdir -p /etc/systemd/system/ollama.service.d
```

```
printf '[Service]\nEnvironment="OLLAMA_KEEP_ALIVE=30m"\nEnvironment="OLLAMA_CONTEXT_LENGTH=8192"\nEnvironment="OLLAMA_MAX_LOADED_MODELS=2"\n' | sudo tee /etc/systemd/system/ollama.service.d/override.conf
```

It should print the same three lines back (after `[Service]`).

```
sudo systemctl daemon-reload
```

```
sudo systemctl restart ollama
```

```
exit
```

## 5. Copy the test to the HP (laptop)

```
scp ops\hp-brain-check.py hp:/tmp/
```

## 6. Run the test (laptop)

It takes several minutes: the chat part makes 10 calls and the first one is slow because the model has to load. Wait until it prints the SUMMARY and the Marks.

```
ssh hp python3 /tmp/hp-brain-check.py
```

## 7. Check the memory right after (laptop)

Do this straight after step 6, while both models are still loaded.

```
ssh hp free -h
```

## 8. Send the results to Claude

Copy everything the two commands printed (step 6 and step 7) and paste it into the chat with Claude. Do not change anything. A line starting with `MISS` is fine to report: it only means we have to decide something together.

## Rerun with the real prompt (only when Claude asks, after the build)

Only do this when Claude asks you to, after the new chat code is built. Claude will have made one text file from the real instructions (`ops\hp-brain-check-system.txt`). It tests the chat model again with that real text; the same marks apply.

```
scp ops\hp-brain-check-system.txt hp:/tmp/
```

```
ssh hp python3 /tmp/hp-brain-check.py --part 3 --system /tmp/hp-brain-check-system.txt
```

```
ssh hp free -h
```

Paste everything printed to Claude, as in step 8.

## The settings block (for reference)

This is what the settings file holds after step 4:

```
[Service]
Environment="OLLAMA_KEEP_ALIVE=30m"
Environment="OLLAMA_CONTEXT_LENGTH=8192"
Environment="OLLAMA_MAX_LOADED_MODELS=2"
```
