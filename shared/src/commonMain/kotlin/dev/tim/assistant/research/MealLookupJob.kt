package dev.tim.assistant.research

const val MEAL_LOOKUP_KIND = "MEAL_LOOKUP"
const val MEAL_LOOKUP_MAX_FOODS = 10
const val MEAL_LOOKUP_MIN_GRAMS = 1.0
const val MEAL_LOOKUP_MAX_GRAMS = 2000.0

/** The whole attempt, all Codex calls together, stays under the 30-minute stale-job reset. */
const val MEAL_LOOKUP_DEADLINE_MS = 25L * 60 * 1000
