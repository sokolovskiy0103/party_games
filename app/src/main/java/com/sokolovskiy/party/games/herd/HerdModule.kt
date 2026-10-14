package com.sokolovskiy.party.games.herd

import android.content.Context
import android.util.Log
import com.sokolovskiy.party.core.GameEnv
import com.sokolovskiy.party.core.GameModule
import com.sokolovskiy.party.core.GameSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * «Стадо» (Herd Mentality). The optional on-device judge lives at module level, so the model
 * stays loaded while the host switches games.
 */
class HerdModule(context: Context, onChange: () -> Unit) : GameModule {
    override val id = "herd"
    override val title = "Стадо"
    override val subtitle = "Відповідай так, як відповість більшість. Хто відбився — отримує рожеву корову"
    override val minPlayers = 2
    override val emoji = "🐄"

    private val judge = Judge(context, onChange).also { it.init() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var bank: QuestionBank? = null

    override fun hostStatus(): JSONObject = judge.statusJson()

    override fun handleHost(msg: JSONObject) {
        when (msg.optString("t")) {
            "autoGroup" -> judge.setEnabled(msg.optBoolean("on", false))
        }
    }

    override fun create(env: GameEnv): GameSession {
        val b = bank ?: QuestionBank.fromJson(env.asset("herd-questions.json")).also { bank = it }
        return Session(Game(b), env)
    }

    private inner class Session(private val game: Game, private val env: GameEnv) : GameSession {
        @Volatile private var judging = false
        private var judgeRun = 0

        override fun onJoin(id: String, name: String) { game.add(id, name) }

        override fun onLeave(id: String) = game.remove(id)

        override fun handle(pid: String?, isHost: Boolean, msg: JSONObject, now: Long) {
            var toJudge: List<Pair<String, String>>? = null
            when (msg.optString("t")) {
                "answer" -> pid?.let { game.submitAnswer(it, msg.optString("text")) }
            }
            if (isHost) when (msg.optString("t")) {
                "levels" -> {
                    val arr = msg.optJSONArray("levels") ?: JSONArray()
                    game.setLevels((0 until arr.length()).map { arr.getString(it) })
                }
                "anonymous" -> game.anonymous = msg.optBoolean("on", false)
                "target" -> game.target = msg.optInt("target", 8).coerceIn(3, 20)
                "start" -> game.start()
                "skip" -> if (game.phase == Game.Phase.QUESTION) game.nextQuestion()
                "custom" -> if (game.phase == Game.Phase.QUESTION || game.phase == Game.Phase.RESULTS) game.customQuestion(msg.optString("text"))
                "reveal" -> if (game.phase == Game.Phase.QUESTION) toJudge = game.closeAnswers()
                "regroup" -> if (game.phase == Game.Phase.JUDGING) {
                    toJudge = game.answers.entries.filter { it.key in game.players }.map { it.key to it.value }
                }
                "move" -> game.move(msg.optString("pid"), msg.optInt("to", -1))
                "confirm" -> if (!judging) game.confirm()
                "next" -> if (game.phase == Game.Phase.RESULTS) game.nextQuestion()
                "newGame" -> {
                    judgeRun++          // drop any judging still in flight
                    judging = false
                    game.newGame()
                }
            }
            toJudge?.let { runJudge(it) }
        }

        private fun runJudge(list: List<Pair<String, String>>) {
            val run = ++judgeRun
            judging = true
            scope.launch {
                val verdict = try {
                    judge.group(list.map { it.second })
                } catch (t: Throwable) {
                    Log.w("HerdModule", "judge crashed", t)
                    Judge.Verdict(FuzzyMatcher.group(list.map { it.second }), "fuzzy")
                }
                env.locked {
                    if (run == judgeRun) {
                        game.applyGroups(list, verdict.groups, verdict.source)
                        judging = false
                    }
                }
                env.changed()
            }
        }

        override fun state(pid: String?, isHost: Boolean, now: Long): JSONObject {
            for (p in env.room.players.values) game.players[p.id]?.connected = p.connected
            return game.stateJson(pid, isHost).put("judging", judging)
        }

        override fun close() {
            judgeRun++
            judging = false
        }
    }
}
