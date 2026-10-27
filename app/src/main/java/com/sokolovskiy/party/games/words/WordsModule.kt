package com.sokolovskiy.party.games.words

import com.sokolovskiy.party.core.GameEnv
import com.sokolovskiy.party.core.GameModule
import com.sokolovskiy.party.core.GameSession
import org.json.JSONArray
import org.json.JSONObject

class WordsModule : GameModule {
    override val id = "words"
    override val title = "Скажи інакше"
    override val subtitle = "Пояснюй слова команді на час: класика, табу, хлопці проти дівчат або дитячий режим з картинками"
    override val minPlayers = Game.MIN_PLAYERS
    override val emoji = "🗣️"

    private var banks: Map<String, List<Game.Word>>? = null

    override fun create(env: GameEnv): GameSession {
        val b = banks ?: Game.BANKS.associateWith { load(env, it) }.also { banks = it }
        return Session(Game(b), env)
    }

    /** `assets/data/words-<theme>.json`: [{"w": word, "t": [five forbidden words], "e": optional emoji picture}]. A missing bank is just empty. */
    private fun load(env: GameEnv, theme: String): List<Game.Word> {
        val text = try { env.asset("words-$theme.json") } catch (_: Exception) { return emptyList() }
        val arr = JSONArray(text)
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val w = o.optString("w").trim()
            val t = o.optJSONArray("t") ?: JSONArray()
            if (w.isEmpty()) null else Game.Word(w, (0 until t.length()).map { t.getString(it).trim() }.filter { it.isNotEmpty() }, o.optString("e").trim())
        }.distinctBy { it.w.lowercase() }
    }

    private class Session(private val game: Game, private val env: GameEnv) : GameSession {

        override fun onJoin(id: String, name: String) { game.add(id, name) }

        override fun onLeave(id: String) = game.remove(id)

        override fun handle(pid: String?, isHost: Boolean, msg: JSONObject, now: Long) {
            // The explainer runs their own turn; the host steps in when they are away.
            val canRun = isHost || (pid != null && pid == game.explainer)
            when (msg.optString("t")) {
                "join" -> pid?.let { game.join(it, msg.optInt("team", -1)) }
                "begin" -> if (canRun) game.beginTurn(now)
                "got" -> if (canRun) game.got()
                "skip" -> if (canRun) game.skip()
                "foul" -> if (pid == game.explainer || game.canWatch(pid)) game.foul()
                "last" -> if (canRun) game.lastWord(msg.optInt("by", -1))
                "mark" -> if (canRun) Game.Mark.entries.firstOrNull { it.name == msg.optString("m") }?.let { game.mark(msg.optInt("i", -1), it) }
                "lastBy" -> if (canRun) game.setLastBy(msg.optInt("by", -1))
                "confirm" -> if (canRun) game.confirm()
            }
            if (isHost) when (msg.optString("t")) {
                "settings" -> if (game.phase == Game.Phase.LOBBY) {
                    if (msg.has("seconds")) game.turnSeconds = msg.optInt("seconds")
                    if (msg.has("goal")) game.goal = msg.optInt("goal")
                    if (msg.has("skipPenalty")) game.skipPenalty = msg.optBoolean("skipPenalty")
                    if (msg.has("mode")) game.setMode(msg.optString("mode"))
                    if (msg.has("taboo")) { game.taboo = msg.optBoolean("taboo"); if (game.taboo) game.kids = false }
                    if (msg.has("lastForAll")) game.lastForAll = msg.optBoolean("lastForAll")
                    if (msg.has("battle")) { game.battle = msg.optBoolean("battle"); if (game.battle) game.kids = false }
                }
                "theme" -> game.toggleTheme(msg.optString("id"))
                "toTeams" -> game.toTeams()
                "teams" -> game.setTeamCount(msg.optInt("n"))
                "shuffle" -> game.reshuffle()
                "move" -> game.move(msg.optString("pid"))
                "start" -> game.startGame()
                "endTurn" -> game.endTurn()
                "finish" -> game.finishEarly()
                "newGame" -> game.newGame()
            }
        }

        override fun state(pid: String?, isHost: Boolean, now: Long): JSONObject {
            for (p in env.room.players.values) game.players[p.id]?.connected = p.connected
            return game.stateJson(pid, isHost, now)
        }

        // a viewer who is nobody sees exactly the public part: never the card
        override fun board(now: Long): JSONObject = state(null, false, now)

        override fun tick(now: Long) = game.tick(now)
    }
}
