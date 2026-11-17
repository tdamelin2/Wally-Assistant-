# Research — Wally brain ladder (Phases 18–19)

Looked up 2026-11-12. "Checked" = read in a source or measured. "Not confirmed" = must be settled by the named task.

## 1. Measured on Tim's own hardware (checked)
| Fact | Value | Source |
|------|-------|--------|
| HP: 2 CPU cores, 8 GB RAM, no GPU | — | `ops/server-facts.md` |
| HP reading speed, `llama3.2:3b` | about 22 tokens/s | `handoffs/phase-17.md` Plan note 2026-11-11 23:55 |
| HP writing speed, `llama3.2:3b` | about 9.9 tokens/s | same |
| HP `qwen3:4b` on the lite request | 115–765 s per answer | `handoffs/phase-17.md` Plan note 2026-11-12 16:20 |
| HP lite eval, `llama3.2:3b`, final | 11/14, cold 62.6 s, warm 5.2 s (same context every call) | `handoffs/phase-17.md` Plan note "HP lite eval pass" |
| Phase 17 lite prompt contains near-copies of eval prompts 9, 12, 14, 15 | — | `server/src/main/resources/wally-persona-lite.md` lines 10–14 vs `server/src/test/resources/model-eval.jsonl` |
| Phase 17 lite system prompt holds the clock time before the data, so Ollama's prefix cache stops after the persona in real chat | — | `LiteContext.kt` line 29, `ChatRunner.kt` `buildLitePrompt` |
| Samsung S25, PocketPal AI (llama.cpp), Gemma 3 4B Q4 | 16.6–19.6 tokens/s, first word 0.3–0.8 s, "35 Shekels Food" correct | Tim's screenshots 2026-11-12 18:01 |
| Samsung S25, same app, Llama 3.2 3B Q4 | 12.8–23.3 tokens/s, first word 0.3–2.5 s, answered "70 Food" (wrong) | Tim's screenshots 2026-11-12 17:50 |

## 2. Phone library
| Fact | Status | Source |
|------|--------|--------|
| LiteRT-LM Android artifact `com.google.ai.edge.litertlm:litertlm-android`, newest version 0.19.1 on Google Maven (a third-party listing still showed 0.19.0) | Checked by the independent reviewer on Google Maven; Task 19.1 confirms it resolves in Gradle | https://maven.google.com/web/index.html#com.google.ai.edge.litertlm:litertlm-android |
| Kotlin API: create an engine, create a conversation, send messages blocking or streaming | Checked | https://developers.google.com/edge/litert-lm/api_overview |
| Kotlin JSON-schema and regex constrained output: `ConversationConfig(enableResponseFormat = true)`, `ResponseFormat.json(...)`, from v0.16.0 | Checked in the issue thread; exact signatures confirmed in Task 19.4 | https://github.com/google-ai-edge/LiteRT-LM/issues/1662 |
| Supported models listed: Gemma3-1B, Gemma-3n-E2B, Gemma-3n-E4B, Gemma4-E4B. No Gemma 3 4B file | Checked | https://developers.google.com/edge/litert-lm/overview |
| Gemma 4 E4B LiteRT-LM file, about 3.65 GB | Checked (size from a third-party article; Task 19.1 records the real size) | https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm |
| Gemma 4 E4B decode speed on a Galaxy S26 Ultra, CPU: 17.7 tokens/s | Checked on Google's model page; the S25 is not listed | https://ai.google.dev/edge/litert-lm/models/gemma-4 |
| Speed of Gemma 4 E4B on Tim's S25 | Not confirmed → Task 19.1 | — |
| LiteRT-LM minimum Android version works with Wally's minSdk 26 | Not confirmed → Task 19.1 (build) | — |
| The library makes no network calls of its own | Not confirmed → Task 19.8 privacy check | — |

## 3. Sorter model on the HP
| Fact | Status | Source |
|------|--------|--------|
| EmbeddingGemma in Ollama: 622 MB download, 768 dimensions, 100+ languages claimed, needs a task prefix on the text | Size checked on the Ollama library page; the rest from third-party summaries of the model card | https://ollama.com/library/embeddinggemma , https://www.morphllm.com/ollama-embedding-models |
| Ollama embeddings endpoint `POST /api/embed` | Checked | same sources |
| `ollama pull embeddinggemma` works on the HP's Ollama 0.40.14 | Not confirmed → Task 18.1 | — |
| Hebrew quality of EmbeddingGemma | Not confirmed (no published Hebrew test found) → Task 18.1 | — |
| Embedding time for one short sentence on the 2-core HP | Not confirmed → Task 18.1 | — |
| Fallback if the gate fails: `bge-m3` (1.2 GB in Ollama, multilingual) | Checked that it exists; used only if Tim chooses it after a failed gate | https://www.morphllm.com/ollama-embedding-models |

## 4. Rejected options
| Option | Why not |
|--------|---------|
| Community llama.cpp Kotlin libraries (llama-kotlin, Llamatik, llama-android AAR) | Run the tested Gemma 3 4B file, but each is maintained by one or two people and their published versions disagree between sources (https://github.com/ThalzeronLabs/llama-kotlin , https://github.com/ferranpons/llamatik) |
| A stronger Ollama model on the HP | Measured: the 2-core CPU is the limit (§1) |
| Codex (cloud) as the laptop-off chat brain | Sends personal data off Tim's devices; Overview Constraints forbid it |
| Training a language model from scratch | Needs data and compute far beyond this project |
