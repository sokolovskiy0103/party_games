package com.sokolovskiy.party.games.speech

import com.sokolovskiy.party.core.GameEnv
import com.sokolovskiy.party.core.GameModule
import com.sokolovskiy.party.core.GameSession
import org.json.JSONArray
import org.json.JSONObject

class SpeechModule : GameModule {
    override val id = "speech"
    override val title = "Три слова"
    override val subtitle = "Говори на тему й непомітно встав три таємні слова — решта намагається їх вловити"
    override val minPlayers = Game.MIN_PLAYERS
    override val emoji = "🎤"

    /** Shared across sessions so topics and words don't repeat when the game is reopened the same evening. */
    private var topics: Deck<String>? = null
    private var adultTopics: Deck<String>? = null
    private var words: Deck<String>? = null

    override fun create(env: GameEnv): GameSession {
        val t = topics ?: JSONObject(env.asset("speech-topics.json")).let { o ->
            adultTopics = Deck(strings(o.getJSONArray("adult")))
            Deck(strings(o.getJSONArray("clean"))).also { topics = it }
        }
        val w = words ?: Deck(
            listOf("words-basic.json", "words-medium.json").flatMap { f ->
                val a = JSONArray(env.asset(f))
                (0 until a.length()).map { a.getJSONObject(it).getString("w").trim() }
            }.filter { it.isNotEmpty() && ' ' !in it }
        ).also { words = it }
        return Session(Game(t, w, adultTopics = adultTopics), env)
    }

    private fun strings(a: JSONArray) = (0 until a.length()).map { a.getString(it).trim() }.filter { it.isNotEmpty() }

    private class Session(private val game: Game, private val env: GameEnv) : GameSession {

        override fun onJoin(id: String, name: String) { game.add(id, name) }

        override fun onLeave(id: String) = game.remove(id)

        private fun syncOnline() {
            for (p in env.room.players.values) game.players[p.id]?.connected = p.connected
        }

        override fun handle(pid: String?, isHost: Boolean, msg: JSONObject, now: Long) {
            syncOnline()
            when (msg.optString("t")) {
                "guess" -> pid?.let { p ->
                    val a = msg.optJSONArray("g") ?: JSONArray()
                    game.guess(p, (0 until a.length()).map { a.optString(it) }, msg.optBoolean("done"))
                }
                "swapTopic" -> game.swapTopic(pid, isHost)
                "swapWords" -> game.swapWords(pid, isHost)
                "begin" -> game.beginSpeech(pid, isHost, now)
                "end" -> game.endSpeech(pid, isHost, now)
                "used" -> game.setUsed(pid, isHost, msg.optInt("i", -1), msg.optBoolean("v"))
                "hit" -> game.setHit(pid, isHost, msg.optString("pid"), msg.optInt("i", -1), msg.optBoolean("v"))
                "next" -> game.next(pid, isHost)
                "mic" -> game.micOn(pid)
                "heard" -> game.heard(pid, msg.optString("text"))
            }
            if (isHost) when (msg.optString("t")) {
                "speech" -> game.setSpeech(msg.optInt("v", 0))
                "cycles" -> game.setCycles(msg.optInt("v", 0))
                "adult" -> game.setAdult(msg.optBoolean("v"))
                "start" -> game.startGame()
                "skip" -> game.skip(true)
                "review" -> game.forceReview(true)
                "abort" -> game.abort()
                "newGame" -> game.newGame()
            }
        }

        override fun state(pid: String?, isHost: Boolean, now: Long): JSONObject {
            syncOnline()
            return game.stateJson(pid, isHost, now)
        }

        override fun tick(now: Long): Boolean {
            syncOnline()
            return game.tick(now) or game.checkDone()
        }
    }
}
