#!/usr/bin/env python3
"""HP measurement gate for Phase 18 (PLAN Task 18.1). Python 3, standard library only.

Run on the HP:   python3 /tmp/hp-brain-check.py
Dry run on the laptop Ollama:   python hp-brain-check.py --url http://xxxxxxxx:11434

Options:
  --url <base>         Ollama base URL (default http://127.0.0.1:11434)
  --system <file>      replace the built-in system text of Part 3 (step 6a, real prompt)
  --part 1|2|3|all     which parts to run (default all)
  --chat-model <name>  model for Part 3 (default llama3.2:3b)
  --embed-model <name> model for Parts 1 and 2 (default embeddinggemma)

Part 1: embed timing. Part 2: nearest-seed ranking with and without the prefix.
Part 3: last resort at the real request size (10 calls, different data each time).
It always exits 0; a missed mark is printed as MISS and recorded in the report.
"""
import argparse
import json
import math
import re
import statistics
import sys
import time
import urllib.request
import random

PREFIX = "task: classification | query: "
NUM_PREDICT = 300  # same cap as HP_NUM_PREDICT in HpLastResort.kt

# 8 intents x 3 seed sentences (24 seeds)
SEEDS = {
    "create_task": [
        "add a task to buy printer ink",
        "remind me to email the professor",
        "new task: finish the lab report by friday",
    ],
    "add_spending": [
        "I spent 35 on lunch",
        "paid 120 shekels for a taxi",
        "bought a book for 60",
    ],
    "add_meal": [
        "I had pasta for dinner",
        "ate two eggs and toast this morning",
        "snack: an apple and some nuts",
    ],
    "add_checkin": [
        "check in: mood 4, energy 3",
        "I slept badly and feel stressed today",
        "feeling pretty good, energy is high",
    ],
    "add_journal": [
        "journal: today was a long but good day",
        "write in my diary that I finally talked to my sister",
        "note to self about how the meeting went",
    ],
    "mark_task_done": [
        "mark the printer ink task as done",
        "I finished the lab report",
        "tick off emailing the professor",
    ],
    "ask_today": [
        "what is on my schedule today",
        "what do I have to do today",
        "show me today's plan",
    ],
    "none": [
        "hello there",
        "thanks a lot",
        "tell me a joke about cats",
    ],
}

# (sentence, expected intent): 10 English probes, 10 Hebrew probes with the same meanings
PROBES_EN = [
    ("please put a new item on my to-do list for calling the dentist", "create_task"),
    ("I need to remember submitting the scholarship form next week", "create_task"),
    ("the cafe charged me 28 shekels just now", "add_spending"),
    ("got new headphones and it cost 250", "add_spending"),
    ("for breakfast I ate yogurt with granola", "add_meal"),
    ("rating today: mood 2, energy 2, stress 5, sleep 3", "add_checkin"),
    ("dear diary, my exam went better than I feared", "add_journal"),
    ("the dentist call is complete now", "mark_task_done"),
    ("so what is happening this afternoon and evening", "ask_today"),
    ("good morning, how are you doing", "none"),
]
PROBES_HE = [
    ("בבקשה תוסיף למשימות שלי להתקשר לרופא שיניים", "create_task"),
    ("אני צריך לזכור להגיש את טופס המלגה בשבוע הבא", "create_task"),
    ("בית הקפה חייב אותי ב-28 שקלים עכשיו", "add_spending"),
    ("קניתי אוזניות חדשות והן עלו 250", "add_spending"),
    ("לארוחת בוקר אכלתי יוגורט עם גרנולה", "add_meal"),
    ("דירוג להיום: מצב רוח 2, אנרגיה 2, לחץ 5, שינה 3", "add_checkin"),
    ("יומן יקר, המבחן שלי עבר טוב יותר ממה שפחדתי", "add_journal"),
    ("השיחה לרופא השיניים הסתיימה", "mark_task_done"),
    ("אז מה קורה היום אחר הצהריים ובערב", "ask_today"),
    ("בוקר טוב, מה שלומך", "none"),
]

# 10 last-resort messages: (text, kind, expected action, required arg names)
LAST_RESORT = [
    ("I paid 42 shekels for a haircut", "action", "add_spending", ["amount", "category"]),
    ("I had a chicken salad for lunch around one", "action", "add_meal", ["type", "text"]),
    ("check-in: mood 3, energy 4, sleep 4, stress 2", "action", "add_checkin",
     ["mood", "energy", "sleepQuality", "stress"]),
    ("journal entry: I felt proud after presenting my project", "action", "add_journal", ["text"]),
    ("change the price of the haircut to 45", "action", "update_spending", []),
    ("move my statistics exam to next thursday", "action", "update_exam", []),
    ("rename the dentist task to call the clinic", "action", "update_task", []),
    ("I feel overwhelmed with studying, any advice", "answer", None, []),
    ("what do you think of studying in the morning versus at night", "answer", None, []),
    ("thank you, that helped a lot", "answer", None, []),
]

SCHEMA = json.loads(
    '{"type":"object","properties":{"kind":{"type":"string","enum":["answer","action","needs_laptop"]},'
    '"reply":{"type":"string"},"action":{"type":"string","enum":["create_task","add_spending","add_meal",'
    '"add_checkin","add_journal","find","update_task","update_exam","update_meal","update_spending",'
    '"update_checkin","update_journal","update_goal","update_doc"]},"target":{"type":"string"},'
    '"args":{"type":"object","additionalProperties":{"type":"string"}}},"required":["kind"]}'
)
KINDS = ("answer", "action", "needs_laptop")

VOICE = (
    "You are Wally, Tim's private assistant. You live on his home server, so you are there even when his laptop "
    "is off. Warm and short: one to four sentences. Answer in the language Tim writes in (English or Hebrew). "
    "Use the real numbers and names from his data. Never invent data. If you do not know, say so. "
    "When you are unsure what he means, ask one short question. Never show ids, codes or tool names to Tim. Talk about things by their names. Be kind and calm, never preachy, and keep to what Tim asked. Short plain words, no lists or emojis. Be fair."
)
INSTRUCTIONS = (
    "Reply with one JSON object and nothing else. Fields: kind, reply, action, target, args. "
    "kind is action whenever Tim says something he did, ate, spent or felt for the check-in, or asks you to "
    "write down, add, change, move or rename something. Set action to one allowed name, reply to a short "
    "confirmation, and put values in args as text. Naming an action while using kind answer is wrong. "
    "kind is answer only for talk, advice, thanks, or questions the data answers. "
    "kind is needs_laptop only when Tim asks for research or a summary of a document. When there is nothing to save, use answer. "
    "create_task needs a title. add_spending needs amount (number) and "
    "category (Food, Transport, Shopping, Bills, Fun, Health or Other). add_meal (food he ate) needs type (MEAL or SNACK) and "
    "text. add_checkin (only when he gives numbers) needs mood, energy, sleepQuality and stress, each 1 to 5. add_journal needs the text. "
    "find needs query and kind (course, study_doc, goal, task, doc, event, exam or mail). "
    "update_ actions change fields: title, due, done, date, amount, category, type, text, progress. For every update_ action, target is the name of the existing item as Tim says it, never an id, and "
    "args holds the changed fields. Do not ask first. Save it, then tell him so. Dates are yyyy-MM-dd, times as HH:mm. "
    'Example: {"kind":"action","reply":"Saved.","action":"add_journal","args":{"text":"a quiet walk"}}'
)
FILLER =" Keep it simple and kind."

NOUNS = ["library", "bicycle", "thesis", "garden", "bakery", "orchestra", "tutor", "lecture", "invoice", "recipe",
         "campus", "notebook", "podcast", "laundry", "workshop", "museum", "postcard", "scholarship", "marathon",
         "seminar", "kitchen", "balcony", "schedule", "project", "roommate", "pharmacy", "concert", "dataset"]
VERBS = ["planned", "postponed", "finished", "started", "reviewed", "cancelled", "booked", "revised", "forgot",
         "enjoyed", "skipped", "organised", "shared", "printed", "ordered", "repaired"]
ADJ = ["quiet", "busy", "late", "early", "difficult", "pleasant", "tiring", "useful", "boring", "exciting"]
DAYS = ["Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday"]


def fit(text, n, filler=FILLER):
    """Exactly n characters: cut when too long, pad with filler when too short."""
    while len(text) < n:
        text += filler
    return text[:n]


def sentence(rng):
    return "%s the %s %s %s %s." % (
        rng.choice(["Tim", "Today I", "Last week I", "Yesterday I", "We"]),
        rng.choice(ADJ), rng.choice(NOUNS), rng.choice(VERBS), rng.choice(DAYS))


def paragraph(rng, n):
    s = ""
    while len(s) < n:
        s += sentence(rng) + " "
    return s[:n]


def data_block(rng, n):
    lines = []
    total = 0
    k = 0
    while total < n:
        k += 1
        line = "%s #%d: %s %s, %s, due %s." % (
            rng.choice(["Task", "Exam", "Meal", "Spending", "Event"]), rng.randint(100, 999),
            rng.choice(ADJ), rng.choice(NOUNS), rng.choice(DAYS), "2026-%02d-%02d" % (rng.randint(1, 12), rng.randint(1, 28)))
        lines.append(line)
        total += len(line) + 1
    return "\n".join(lines)[:n]


def history(rng, n):
    s = ""
    who = 0
    while len(s) < n:
        s += ("Tim: " if who == 0 else "Wally: ") + sentence(rng) + "\n"
        who = 1 - who
    return s[:n]


def builtin_system():
    voice = fit(VOICE, 550)
    instr = fit(INSTRUCTIONS, 1399)
    return voice + "\n" + instr


# ---------------------------------------------------------------- http
def post(base, path, body, timeout):
    req = urllib.request.Request(base.rstrip("/") + path, data=json.dumps(body).encode("utf-8"),
                                 headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return json.loads(r.read().decode("utf-8"))


def embed(base, model, text, prefix, timeout=300):
    t0 = time.time()
    try:
        r = post(base, "/api/embed", {"model": model, "input": (PREFIX if prefix else "") + text, "keep_alive": -1}, timeout)
        v = r["embeddings"][0]
        return v, int((time.time() - t0) * 1000)
    except Exception as e:  # noqa: BLE001 - report every failure and go on
        print("  embed failed: %s" % e)
        return None, int((time.time() - t0) * 1000)


def cosine(a, b):
    dot = sum(x * y for x, y in zip(a, b))
    na = math.sqrt(sum(x * x for x in a))
    nb = math.sqrt(sum(y * y for y in b))
    return dot / (na * nb) if na and nb else 0.0


def mem_available_mb():
    try:
        with open("/proc/meminfo") as f:
            for line in f:
                if line.startswith("MemAvailable:"):
                    return int(line.split()[1]) // 1024
    except Exception:  # noqa: BLE001
        pass
    return None


def words(s):
    return re.findall(r"\w+", s.lower())


def shares_run(a, b, n=4):
    wa, wb = words(a), words(b)
    grams = set(tuple(wa[i:i + n]) for i in range(len(wa) - n + 1))
    return any(tuple(wb[i:i + n]) in grams for i in range(len(wb) - n + 1))


def self_check():
    seeds = [s for v in SEEDS.values() for s in v]
    bad = []
    for text, _ in PROBES_EN + PROBES_HE:
        if any(shares_run(text, s) for s in seeds):
            bad.append(text)
    for text, _, _, _ in LAST_RESORT:
        if any(shares_run(text, s) for s in seeds):
            bad.append(text)
    assert sum(len(v) for v in SEEDS.values()) == 24 and len(SEEDS) == 8
    assert len(PROBES_EN) == 10 and len(PROBES_HE) == 10 and len(LAST_RESORT) == 10
    if bad:
        print("WARNING: %d probe(s) share a 4-word run with a seed" % len(bad))


# ---------------------------------------------------------------- parts
def part1(a, res):
    print("\n=== Part 1: embed timing (%s, prefixed, 20 probes) ===" % a.embed_model)
    times = []
    length = None
    for i, (text, _) in enumerate(PROBES_EN + PROBES_HE, 1):
        v, ms = embed(a.url, a.embed_model, text, True)
        if v is not None:
            length = len(v)
        times.append(ms)
        print("  call %2d: %5d ms%s" % (i, ms, "  (cold)" if i == 1 else ""))
    warm = times[1:]
    res["embed_cold_ms"] = times[0]
    res["embed_warm_median_ms"] = statistics.median(warm) if warm else None
    res["vector_length"] = length
    print("  warm median (calls 2-20): %s ms, vector length %s" % (res["embed_warm_median_ms"], length))


def ranking(a, prefix):
    seeds = [(intent, s) for intent, v in SEEDS.items() for s in v]
    seed_vecs = []
    for intent, s in seeds:
        v, _ = embed(a.url, a.embed_model, s, prefix)
        seed_vecs.append((intent, v))
    out = {}
    for lang, probes in (("English", PROBES_EN), ("Hebrew", PROBES_HE)):
        right = 0
        for text, want in probes:
            v, _ = embed(a.url, a.embed_model, text, prefix)
            best, best_intent = -2.0, "?"
            if v is not None:
                for intent, sv in seed_vecs:
                    if sv is None:
                        continue
                    c = cosine(v, sv)
                    if c > best:
                        best, best_intent = c, intent
            ok = best_intent == want
            right += ok
            print("  [%s] %-14s -> %-14s %.3f %s" % (lang[:2], want, best_intent, best, "ok" if ok else "WRONG"))
        out[lang] = right
        print("  %s right: %d/10" % (lang, right))
    return out


def part2(a, res):
    print("\n=== Part 2: ranking (24 seeds, 20 probes), run twice ===")
    print("--- with prefix ---")
    pre = ranking(a, True)
    print("--- without prefix ---")
    nop = ranking(a, False)
    res["rank_prefix"] = pre
    res["rank_noprefix"] = nop
    use_prefix = pre["English"] >= nop["English"]
    res["prefix_used"] = use_prefix
    chosen = pre if use_prefix else nop
    res["embed_prefix"] = PREFIX if use_prefix else ""
    res["english_right"] = chosen["English"]
    res["hebrew_right"] = chosen["Hebrew"]


def part3(a, res):
    print("\n=== Part 3: last resort, %s, 10 calls, num_ctx 8192, temperature 0, num_predict %d ===" % (a.chat_model, NUM_PREDICT))
    if a.system:
        with open(a.system, encoding="utf-8") as f:
            system = f.read()
        print("  system text from %s (%d chars)" % (a.system, len(system)))
    else:
        system = builtin_system()
        print("  built-in system text (%d chars)" % len(system))
    secs = []
    valid = 0
    conv_ok = 0
    adds_ok = 0
    for i, (msg, want_kind, want_action, want_args) in enumerate(LAST_RESORT):
        rng = random.Random(1000 + i)
        notes = "\n".join("Note: " + paragraph(rng, 194) for _ in range(3))
        user = "%s\n\n%s\n\n%s\nTime now: %02d:%02d\n\n%s" % (
            notes, data_block(rng, 1200), history(rng, 800), rng.randint(6, 22), rng.randint(0, 59), msg)
        body = {"model": a.chat_model, "stream": False, "think": False, "format": SCHEMA,
                "options": {"num_ctx": 8192, "temperature": 0, "num_predict": NUM_PREDICT},
                "messages": [{"role": "system", "content": system}, {"role": "user", "content": user}]}
        t0 = time.time()
        raw = ""
        try:
            raw = post(a.url, "/api/chat", body, 900)["message"]["content"]
        except Exception as e:  # noqa: BLE001
            print("  call %d failed: %s" % (i + 1, e))
        s = time.time() - t0
        secs.append(s)
        obj = None
        try:
            obj = json.loads(raw)
        except Exception:  # noqa: BLE001
            pass
        good = isinstance(obj, dict) and obj.get("kind") in KINDS
        valid += good
        note = ""
        if good and i < 4:
            args = obj.get("args") if isinstance(obj.get("args"), dict) else {}
            present = all(str(args.get(k, "")).strip() for k in want_args)
            ok = obj.get("kind") == "action" and obj.get("action") == want_action and present
            adds_ok += ok
            note = "add %s" % ("ok" if ok else "WRONG (kind=%s action=%s args=%s)" % (obj.get("kind"), obj.get("action"), sorted(args)))
        elif good and i >= 7:
            ok = obj.get("kind") == "answer" and bool(str(obj.get("reply", "")).strip())
            conv_ok += ok
            note = "conversation %s" % ("ok" if ok else "WRONG (kind=%s)" % obj.get("kind"))
        elif good:
            note = "edit: kind=%s action=%s (recorded only)" % (obj.get("kind"), obj.get("action"))
        else:
            note = "NOT VALID JSON/kind"
        print("  call %2d: %6.1f s%s  %s" % (i + 1, s, "  (cold)" if i == 0 else "", note))
    res["lr_cold_s"] = secs[0]
    res["lr_median_s"] = statistics.median(secs[1:])
    res["lr_valid"] = valid
    res["lr_conv"] = conv_ok
    res["lr_adds"] = adds_ok
    print("  cold %.1f s, median of calls 2-10 %.1f s, valid %d/10, conversation %d/3, adds %d/4"
          % (res["lr_cold_s"], res["lr_median_s"], valid, conv_ok, adds_ok))


def mark(name, value, ok):
    print("  %-4s %s: %s" % ("PASS" if ok else "MISS", name, value))


def summary(a, res):
    print("\n=== SUMMARY (copy everything printed and paste it to Claude) ===")
    print("url: %s | chat model: %s | embed model: %s | system file: %s" % (a.url, a.chat_model, a.embed_model, a.system or "built-in"))
    if "embed_warm_median_ms" in res:
        print("embed warm median ms: %s | embed cold ms: %s | vector length: %s"
              % (res["embed_warm_median_ms"], res["embed_cold_ms"], res["vector_length"]))
    if "rank_prefix" in res:
        p, n = res["rank_prefix"], res["rank_noprefix"]
        print("ranking with prefix:    English %d/10, Hebrew %d/10" % (p["English"], p["Hebrew"]))
        print("ranking without prefix: English %d/10, Hebrew %d/10" % (n["English"], n["Hebrew"]))
        print("EMBED_PREFIX = %s" % json.dumps(res["embed_prefix"]))
        print("SORTER_HEBREW = %s (Hebrew %d/10 on the chosen variant)" % ("true" if res["hebrew_right"] >= 6 else "false", res["hebrew_right"]))
    if "lr_median_s" in res:
        print("last resort: cold %.1f s | median calls 2-10 %.1f s | valid JSON %d/10 | conversation %d/3 | adds %d/4"
              % (res["lr_cold_s"], res["lr_median_s"], res["lr_valid"], res["lr_conv"], res["lr_adds"]))
    ram = mem_available_mb()
    res["ram_mb"] = ram
    print("available RAM now (MemAvailable): %s" % ("%d MB" % ram if ram is not None else "not readable here"))
    print("\nMarks:")
    if "embed_warm_median_ms" in res:
        m = res["embed_warm_median_ms"]
        mark("embed warm median <= 500 ms", m, m is not None and m <= 500)
    if "english_right" in res:
        mark("English ranking >= 8/10", "%d/10" % res["english_right"], res["english_right"] >= 8)
    if "lr_median_s" in res:
        mark("last-resort median <= 60 s", "%.1f s" % res["lr_median_s"], res["lr_median_s"] <= 60)
        mark("valid JSON 10/10", "%d/10" % res["lr_valid"], res["lr_valid"] == 10)
        mark("conversation answer with text 3/3", "%d/3" % res["lr_conv"], res["lr_conv"] == 3)
        mark("adds right action and arguments >= 3/4", "%d/4" % res["lr_adds"], res["lr_adds"] >= 3)
    if ram is not None:
        mark("available RAM >= 500 MB", "%d MB" % ram, ram >= 500)
    else:
        print("  n/a  available RAM >= 500 MB: read `free -h` with both models loaded")
    print("Run `ssh hp free -h` now while both models are loaded and paste it too.")


def main():
    ap = argparse.ArgumentParser(description="Phase 18 HP measurement gate")
    ap.add_argument("--url", default="http://127.0.0.1:11434")
    ap.add_argument("--system", default=None)
    ap.add_argument("--part", default="all", choices=["1", "2", "3", "all"])
    ap.add_argument("--chat-model", default="llama3.2:3b")
    ap.add_argument("--embed-model", default="embeddinggemma")
    a = ap.parse_args()
    self_check()
    res = {}
    if a.part in ("1", "all"):
        part1(a, res)
    if a.part in ("2", "all"):
        part2(a, res)
    if a.part in ("3", "all"):
        part3(a, res)
    summary(a, res)
    return 0


if __name__ == "__main__":
    sys.exit(main())
