package com.sokolovskiy.party.games.insider

import com.sokolovskiy.party.core.GameEnv
import com.sokolovskiy.party.core.GameModule
import com.sokolovskiy.party.core.GameSession
import org.json.JSONObject

class InsiderModule : GameModule {
    override val id = "insider"
    override val title = "Інсайдер"
    override val subtitle = "Вгадайте слово питаннями «так/ні» — і знайдіть того, хто знав його від початку"
    override val minPlayers = Game.MIN_PLAYERS
    override val emoji = "🕵️"

    /** Shared across sessions so words don't repeat when the game is reopened the same evening. */
    private var bank: WordBank? = null

    override fun create(env: GameEnv): GameSession {
        val b = bank ?: WordBank.fromJson(env.asset("insider-words.json")).also { bank = it }
        return Session(Game(b), env)
    }

    private class Session(private val game: Game, private val env: GameEnv) : GameSession {

        override fun onJoin(id: String, name: String) { game.add(id, name) }

        override fun onLeave(id: String) = game.remove(id)

        override fun handle(pid: String?, isHost: Boolean, msg: JSONObject, now: Long) {
            // The Master runs the round from their own phone; the host can always step in.
            val canRun = isHost || (pid != null && pid == game.masterId)
            when (msg.optString("t")) {
                "ready" -> pid?.let { game.markReady(it) }
                "vote1" -> pid?.let { game.castVote1(it, msg.optBoolean("yes")) }
                "vote2" -> pid?.let { game.castVote2(it, msg.optString("target")) }
                "swapWord" -> if (canRun) game.swapWord()
                "begin" -> if (canRun) game.beginQuestions(now)
                "guessed" -> if (canRun) game.guessed(msg.optString("pid"), now)
                "endDiscussion" -> if (canRun) game.endDiscussion()
            }
            if (isHost) when (msg.optString("t")) {
                "start", "next" -> game.startRound()
                "seconds" -> game.questionSeconds = msg.optInt("seconds", 300)
                "closeVote1" -> game.closeVote1()
                "closeVote2" -> game.closeVote2()
                "abort" -> game.abortRound()
                "newGame" -> game.newGame()
            }
        }

        override fun state(pid: String?, isHost: Boolean, now: Long): JSONObject {
            for (p in env.room.players.values) game.players[p.id]?.connected = p.connected
            return game.stateJson(pid, isHost, now)
        }

        override fun tick(now: Long) = game.tick(now)
    }
}
