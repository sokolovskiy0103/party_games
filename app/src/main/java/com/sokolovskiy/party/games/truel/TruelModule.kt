package com.sokolovskiy.party.games.truel

import com.sokolovskiy.party.core.GameEnv
import com.sokolovskiy.party.core.GameModule
import com.sokolovskiy.party.core.GameSession
import org.json.JSONObject

class TruelModule : GameModule {
    override val id = "truel"
    override val title = "Труель"
    override val subtitle = "Перестрілка на трьох: усі таємно й одночасно стріляють, ухиляються чи перезаряджаються. Карти-трюки, домовленості й зради"
    override val minPlayers = Game.MIN_PLAYERS
    override val emoji = "🤠"

    override fun create(env: GameEnv): GameSession = Session(Game(), env)

    private class Session(private val game: Game, private val env: GameEnv) : GameSession {

        override fun onJoin(id: String, name: String) { game.add(id, name) }

        override fun onLeave(id: String) = game.remove(id)

        override fun handle(pid: String?, isHost: Boolean, msg: JSONObject, now: Long) {
            if (pid != null) when (msg.optString("t")) {
                "move" -> {
                    val act = runCatching { Game.Act.valueOf(msg.optString("act")) }.getOrNull() ?: return
                    val card = msg.optString("card").takeIf { it.isNotEmpty() }
                        ?.let { runCatching { Game.Card.valueOf(it) }.getOrNull() ?: return }
                    game.move(pid, Game.Move(act, msg.optString("target").ifEmpty { null }, card,
                        msg.optString("cardTarget").ifEmpty { null }))
                }
                "ready" -> game.setReady(pid)
            }
            if (isHost) when (msg.optString("t")) {
                "target" -> game.setTarget(msg.optInt("v"))
                "seconds" -> game.setSeconds(msg.optInt("v", -1))
                "cards" -> game.setCards(msg.optBoolean("v"))
                "start" -> game.startGame()
                "resolve" -> game.forceResolve(true)
                "next" -> game.next(pid, true)
                "abort" -> game.abort()
                "newGame" -> game.newGame()
            }
        }

        override fun tick(now: Long) = game.tick(now)

        /** The TV: what a viewer with no seat sees (no hands, moves only once revealed); the room screen in the lobby. */
        override fun board(now: Long): JSONObject? = game.boardJson(now)

        override fun state(pid: String?, isHost: Boolean, now: Long): JSONObject {
            for (p in env.room.players.values) game.players[p.id]?.connected = p.connected
            return game.stateJson(pid, isHost, now)
        }
    }
}
