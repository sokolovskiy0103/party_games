package com.sokolovskiy.party.games.detective

import com.sokolovskiy.party.core.GameEnv
import com.sokolovskiy.party.core.GameModule
import com.sokolovskiy.party.core.GameSession
import org.json.JSONObject

class DetectiveModule : GameModule {
    override val id = "detective"
    override val title = "Слідство"
    override val subtitle = "Кооперативне розслідування: складіть свідчення й знайдіть убивцю"
    override val minPlayers = Game.MIN_PLAYERS
    override val emoji = "🔍"

    /** Shared across sessions so a case does not repeat when the game is reopened the same evening. */
    private var bank: CaseBank? = null

    override fun create(env: GameEnv): GameSession {
        val b = bank ?: CaseBank.fromJson(env.asset("detective-cases.json")).also { bank = it }
        return Session(Game(b), env)
    }

    private class Session(private val game: Game, private val env: GameEnv) : GameSession {

        override fun onJoin(id: String, name: String) { game.add(id, name) }

        override fun onLeave(id: String) = game.remove(id)

        override fun handle(pid: String?, isHost: Boolean, msg: JSONObject, now: Long) {
            val inRound = pid != null && pid in game.inRound
            when (msg.optString("t")) {
                "ready" -> if (pid != null) {
                    game.markReady(pid)
                    if (game.allReady()) game.begin(now)
                }
                "share" -> if (pid != null) game.share(pid, msg.optInt("i", -1))
                "mark" -> if (pid != null) game.toggleMark(msg.optInt("i", -1))
                "vote" -> if (pid != null) game.castVote(pid, msg.optInt("i", -1), now)
                "callVote" -> if (isHost || inRound) game.callVote(now)
            }
            if (isHost) when (msg.optString("t")) {
                "start", "next" -> game.startCase()
                "begin" -> game.begin(now)
                "closeVote" -> game.closeVote(now)
                "seconds" -> game.investigationSeconds = msg.optInt("seconds", Game.DEFAULT_SECONDS)
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
