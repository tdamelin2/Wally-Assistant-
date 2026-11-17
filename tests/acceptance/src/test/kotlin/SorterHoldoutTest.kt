import dev.tim.assistant.server.chat.ladder.Embedder
import dev.tim.assistant.server.chat.ladder.SeedStore
import dev.tim.assistant.server.chat.ladder.Sorter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue

/**
 * PLAN 18.7 step 4: the same files with the real embedder and the real seeds. Skipped unless WALLY_EMBED_URL is set (the base
 * address of an Ollama that has embeddinggemma). Fails on any wrong write, card or data answer, and when fewer than 45 of the 60
 * command lines are handled by code (a correct pass-up is not counted).
 */
class SorterHoldoutTest {
    @Test
    fun test_AC4_sorter_holdout_handled_at_least_45_of_60_and_wrong_0() = runBlocking<Unit> {
        val url = System.getenv("WALLY_EMBED_URL")
        assumeTrue("WALLY_EMBED_URL not set", !url.isNullOrBlank())
        val embedder = Embedder(url!!, "embeddinggemma")
        try {
            val store = SeedStore(embedder, CoroutineScope(Dispatchers.Default + Job()))
            store.startSeeding()
            var waited = 0
            while (!store.ready && waited < 600) { delay(500); waited++ }
            assertTrue(store.ready, "seeds did not finish (is the embedding model reachable?)")
            val sorter = Sorter(store, embedder)
            val verdicts = runHoldout(listOf("holdout-commands.jsonl", "holdout-guards.jsonl"), askWithoutWriteIsNotHandled = true) { HoldoutRig(sorter = sorter) }
            val commands = verdicts.filter { it.line.id.startsWith("h") }
            val handled = commands.count { it.outcome == Outcome.HANDLED && it.line.expect != "pass_up" }
            val wrong = verdicts.filter { it.outcome == Outcome.WRONG }
            println("handled by code: $handled/${commands.size}, wrong: ${wrong.size}")
            println("not handled by code (ids and intents only): " + commands.filter { it.outcome == Outcome.NOT_HANDLED }.joinToString { "${it.line.id}:${it.line.expect}" })
            assertEquals(emptyList(), wrong.map { describe(it) }, "wrong action")
            assertTrue(handled >= 45, "handled by code: $handled/60, needs at least 45")
        } finally {
            embedder.close()
        }
    }
}
