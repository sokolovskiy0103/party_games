package com.sokolovskiy.party.update

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReleaseTest {

    private fun json(tag: String = "v1.0.12", vararg assets: Pair<String, String>, body: String = "") =
        JSONObject()
            .put("tag_name", tag)
            .put("body", body)
            .put("assets", JSONArray().apply {
                assets.forEach { (name, url) -> put(JSONObject().put("name", name).put("browser_download_url", url).put("size", 1234)) }
            })

    @Test fun tagNumber() {
        assertEquals(12, Release.tagNumber("v1.0.12"))
        assertEquals(7, Release.tagNumber(" v1.0.7 "))
        assertEquals(3, Release.tagNumber("3"))
        assertNull(Release.tagNumber("latest"))
    }

    @Test fun parsePicksTheApk() {
        val r = Release.parse(json("v1.0.12",
            "notes.txt" to "https://x/notes.txt",
            "PartyGames.apk" to "https://github.com/a/b/releases/download/v1.0.12/PartyGames.apk"))!!
        assertEquals(12, r.number)
        assertEquals("1.0.12", r.name)
        assertEquals(1234L, r.size)
        assertEquals("https://github.com/a/b/releases/download/v1.0.12/PartyGames.apk", r.apkUrl)
    }

    @Test fun parseRejectsMissingOrInsecureApk() {
        assertNull(Release.parse(json("v1.0.12", "other.apk" to "https://x")))
        assertNull(Release.parse(json("v1.0.12", "PartyGames.apk" to "http://x")))
        assertNull(Release.parse(json("nightly", "PartyGames.apk" to "https://x")))
        assertNull(Release.parse(json("v1.0.12", "PartyGames.apk" to "https://x").put("prerelease", true)))
    }

    @Test fun notesKeepOnlyTheCommitMessage() {
        val body = "Add self-update\n\nChecks GitHub on launch.\n\nCo-Authored-By: Claude <x@y>\nClaude-Session: https://s\n\n---\nЗбірка з коміту abc.\nЗавантаж PartyGames.apk…"
        assertEquals("Add self-update\n\nChecks GitHub on launch.", Release.cleanNotes(body))
        // older releases: only boilerplate
        assertEquals("", Release.cleanNotes("Збірка з коміту abc.\nЗавантаж PartyGames.apk на телефон хоста й встанови поверх попередньої версії.\n"))
    }
}
