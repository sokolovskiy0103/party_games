package com.sokolovskiy.party.games.draw

import com.sokolovskiy.party.core.GameEnv
import com.sokolovskiy.party.core.GameModule
import com.sokolovskiy.party.core.GameSession
import org.json.JSONObject

class DrawModule : GameModule {
    override val id = "draw"
    override val title = "Малювач"
    override val subtitle = "Намалюй дивну тему, вигадуй фальшиві назви до чужих малюнків і вгадуй справжню"
    override val minPlayers = Game.MIN_PLAYERS
    override val emoji = "🎨"

    /** Shared across sessions so prompts don't repeat when the game is reopened the same evening. */
    private var bank: PromptBank? = null

    override fun create(env: GameEnv): GameSession {
        val b = bank ?: PromptBank.fromJson(env.asset("draw-prompts.json")).also { bank = it }
        return Session(Game(b), env)
    }

    private class Session(private val game: Game, private val env: GameEnv) : GameSession {

        override fun onJoin(id: String, name: String) { game.add(id, name) }

        override fun onLeave(id: String) = game.remove(id, System.currentTimeMillis())

        override fun handle(pid: String?, isHost: Boolean, msg: JSONObject, now: Long) {
            when (msg.optString("t")) {
                "art" -> if (pid != null) Art.parse(msg.optJSONArray("art"))?.let { game.draft(pid, it) }
                "submit" -> if (pid != null) game.submit(pid, Art.parse(msg.optJSONArray("art")), now)
                "unsubmit" -> game.unsubmit(pid)
                "swap" -> game.swapPrompt(pid)
                "fake" -> game.writeFake(pid, msg.optString("text"), now)
                "vote" -> game.vote(pid, msg.optInt("i", -1), now)
                "next" -> game.next(pid, isHost, now)
            }
            if (isHost) when (msg.optString("t")) {
                "spice" -> runCatching { Game.Spice.valueOf(msg.optString("v")) }.getOrNull()?.let { game.setSpice(it) }
                "rounds" -> game.setRounds(msg.optInt("v", 0))
                "drawSeconds" -> game.setDrawSeconds(msg.optInt("v", 0))
                "start" -> game.startGame(now)
                "hurry" -> game.hurry(true, now)
                "abort" -> game.abort()
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
