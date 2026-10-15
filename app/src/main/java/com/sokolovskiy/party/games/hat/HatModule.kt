package com.sokolovskiy.party.games.hat

import com.sokolovskiy.party.core.GameEnv
import com.sokolovskiy.party.core.GameModule
import com.sokolovskiy.party.core.GameSession
import org.json.JSONArray
import org.json.JSONObject

class HatModule : GameModule {
    override val id = "hat"
    override val title = "Шляпа"
    override val subtitle = "Пояснюй персонажів із шляпи: словами, одним словом, а потім без слів"
    override val minPlayers = Game.MIN_PLAYERS
    override val emoji = "🎩"

    private var bank: List<String>? = null

    override fun create(env: GameEnv): GameSession {
        val b = bank ?: parse(env.asset("hat-characters.json")).also { bank = it }
        return Session(Game(b), env)
    }

    private fun parse(text: String): List<String> {
        val arr = JSONArray(text)
        return (0 until arr.length()).map { arr.getString(it).trim() }.filter { it.isNotEmpty() }.distinct()
    }

    private class Session(private val game: Game, private val env: GameEnv) : GameSession {

        override fun onJoin(id: String, name: String) { game.add(id, name) }

        override fun onLeave(id: String) = game.remove(id)

        override fun handle(pid: String?, isHost: Boolean, msg: JSONObject, now: Long) {
            // The explainer runs their own turn; the host can always step in.
            val canRun = isHost || (pid != null && pid == game.explainer)
            when (msg.optString("t")) {
                "add" -> pid?.let { game.addCard(it, msg.optString("name")) }
                "remove" -> pid?.let { game.removeCard(it, msg.optInt("id")) }
                "autofill" -> pid?.let { game.autofill(it) }
                "begin" -> if (canRun) game.beginTurn(now)
                "got" -> if (canRun) game.got(now)
                "skip" -> if (canRun) game.skip(now)
                "undo" -> if (canRun) game.undo(now)
                "pic" -> game.setPic(pid, isHost, msg.optInt("id"), msg.optString("url"))
                "last" -> if (canRun) game.lastCard(msg.optBoolean("yes"))
            }
            if (isHost) when (msg.optString("t")) {
                "settings" -> {
                    if (msg.has("perPlayer")) game.perPlayer = msg.optInt("perPlayer")
                    if (msg.has("seconds")) game.turnSeconds = msg.optInt("seconds")
                    if (msg.has("images")) game.images = msg.optBoolean("images")
                    if (msg.has("auto")) game.autoPool = msg.optBoolean("auto")
                    if (msg.has("skips")) game.skipsAllowed = msg.optBoolean("skips")
                }
                "collect" -> game.startCollect()
                "autoAll" -> game.autofillAll()
                "toTeams" -> game.finishCollect()
                "teams" -> game.setTeamCount(msg.optInt("n"))
                "shuffle" -> game.reshuffle()
                "move" -> game.move(msg.optString("pid"))
                "start" -> game.startGame()
                "endTurn" -> game.endTurn(now)
                "nextRound" -> game.nextRound()
                "finish" -> game.finishEarly(now)
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
