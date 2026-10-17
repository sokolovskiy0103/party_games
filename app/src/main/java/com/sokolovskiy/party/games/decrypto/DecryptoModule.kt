package com.sokolovskiy.party.games.decrypto

import com.sokolovskiy.party.core.GameEnv
import com.sokolovskiy.party.core.GameModule
import com.sokolovskiy.party.core.GameSession
import org.json.JSONObject

class DecryptoModule : GameModule {
    override val id = "decrypto"
    override val title = "Шифр"
    override val subtitle = "Дві команди, по чотири таємні слова. Шифруй код підказками так, щоб свої зрозуміли, а суперники — не перехопили"
    override val minPlayers = Game.MIN_PER_TEAM * 2
    override val emoji = "🔐"

    /** Shared across sessions so keywords don't repeat when the game is reopened the same evening. */
    private var bank: WordBank? = null

    override fun create(env: GameEnv): GameSession {
        val b = bank ?: WordBank.fromJson(env.asset("decrypto-words.json")).also { bank = it }
        return Session(Game(b), env)
    }

    private class Session(private val game: Game, private val env: GameEnv) : GameSession {

        override fun onJoin(id: String, name: String) { game.add(id, name) }

        override fun onLeave(id: String) = game.remove(id)

        override fun handle(pid: String?, isHost: Boolean, msg: JSONObject, now: Long) {
            val target = if (msg.optString("target") == "opp") Game.Target.OPP else Game.Target.OWN
            when (msg.optString("t")) {
                "switch" -> pid?.let { game.switchTeam(it) }
                "clues" -> pid?.let { p ->
                    val a = msg.optJSONArray("clues") ?: return@let
                    game.submitClues(p, (0 until a.length()).map { a.optString(it) })
                }
                "digit" -> pid?.let { game.setDigit(it, target, msg.optInt("slot", -1), msg.optInt("d", -1)) }
                "lock" -> pid?.let { game.lock(it, target) }
            }
            if (isHost) when (msg.optString("t")) {
                "move" -> game.switchTeam(msg.optString("pid"))
                "shuffle" -> game.shuffleTeams()
                "spice" -> runCatching { Game.Spice.valueOf(msg.optString("v")) }.getOrNull()?.let { game.setSpice(it) }
                "start" -> game.startGame()
                "next" -> game.nextRound()
                "resolve" -> game.resolve()
                "abort" -> game.abort()
            }
        }

        override fun state(pid: String?, isHost: Boolean, now: Long): JSONObject {
            sync()
            return game.stateJson(pid, isHost, now)
        }

        /** Public view for the TV: no viewer, so no secret words and no codes until a round is revealed. */
        override fun board(now: Long): JSONObject {
            sync()
            return game.stateJson(null, false, now)
        }

        private fun sync() {
            for (p in env.room.players.values) game.players[p.id]?.connected = p.connected
        }
    }
}
