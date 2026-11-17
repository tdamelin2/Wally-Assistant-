package dev.tim.assistant.server.chat.ladder

/** The 20 intent names of the brain ladder (SPEC section 5). [wire] is the snake_case name used in files. */
enum class IntentName(val wire: String) {
    CREATE_TASK("create_task"),
    ADD_SPENDING("add_spending"),
    ADD_MEAL("add_meal"),
    ADD_CHECKIN("add_checkin"),
    ADD_JOURNAL("add_journal"),
    ADD_DREAM("add_dream"),
    MARK_TASK_DONE("mark_task_done"),
    MOVE_TASK("move_task"),
    MOVE_EXAM("move_exam"),
    FIND("find"),
    ASK_TODAY("ask_today"),
    ASK_TASKS("ask_tasks"),
    ASK_EVENTS("ask_events"),
    ASK_SPENDING_WEEK("ask_spending_week"),
    ASK_SPENDING("ask_spending"),
    ASK_EXAM("ask_exam"),
    ASK_HEALTH("ask_health"),
    ASK_MEALS("ask_meals"),
    ASK_CHECKIN("ask_checkin"),
    NONE("none");

    /** True for the read-only intents (the only ones a question may match). */
    val isAsk: Boolean get() = wire.startsWith("ask_")

    /** True for the four intents that write a log entry for "today". */
    val isLog: Boolean get() = this == ADD_SPENDING || this == ADD_MEAL || this == ADD_CHECKIN || this == ADD_JOURNAL

    companion object {
        fun fromWire(name: String): IntentName? = entries.firstOrNull { it.wire == name }
    }
}

sealed interface RouteResult {
    data class Match(val intent: IntentName, val slots: Map<String, String>) : RouteResult
    data object NoMatch : RouteResult
}
