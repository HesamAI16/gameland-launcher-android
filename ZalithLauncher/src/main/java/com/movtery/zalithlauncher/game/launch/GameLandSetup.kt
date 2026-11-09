/*
 * Zalith Launcher 2
 * Copyright (C) 2025-2026 MovTery <movtery228@qq.com> and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/gpl-3.0.txt>.
 */

package com.movtery.zalithlauncher.game.launch

import com.google.gson.JsonParser
import com.movtery.zalithlauncher.game.version.installed.Version
import com.movtery.zalithlauncher.game.version.multiplayer.AllServers
import com.movtery.zalithlauncher.game.version.multiplayer.ServerData
import com.movtery.zalithlauncher.utils.logging.Logger
import com.movtery.zalithlauncher.utils.string.isBiggerOrEqualTo
import com.movtery.zalithlauncher.utils.string.isLowerTo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

object GameLandSetup {
    private const val TAG = "GameLandSetup"
    private const val BASE = "https://gamelandmc.com/launcher/mods/"
    private const val PACK_NAME = "GameLand-UI.zip"
    const val SERVER_IP = "play.gamelandmc.com"
    private const val SERVER_NAME = "§a§lGAME§f§lLAND"

    private val FORMATS = listOf(
        "1.8" to 1, "1.9" to 2, "1.11" to 3, "1.13" to 4, "1.15" to 5, "1.16.2" to 6,
        "1.17" to 7, "1.18" to 8, "1.19" to 9, "1.19.3" to 12, "1.19.4" to 13, "1.20" to 15
    )

    suspend fun pinServer(version: Version) = withContext(Dispatchers.IO) {
        runCatching {
            val dir = version.getGameDir()
            dir.mkdirs()
            val all = AllServers()
            all.loadServers(File(dir, "servers.dat"))
            val existing = all.serverList.toList()
            val current = existing.firstOrNull { it.originIp.equals(SERVER_IP, true) }
            if (current != null && existing.first() === current && current.name == SERVER_NAME) return@runCatching
            existing.forEach { all.removeServer(it) }
            val pinned = current?.also { it.name = SERVER_NAME } ?: ServerData(SERVER_NAME, SERVER_IP)
            all.addServer(pinned)
            existing.filter { it !== current }.forEach { all.addServer(it) }
            all.save(dir)
        }.onFailure { Logger.warning(TAG, "Unable to pin the GameLand server", it) }
    }

    suspend fun preparePack(version: Version, mcVersion: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            if (mcVersion.isLowerTo("1.8")) return@runCatching null
            val modern = mcVersion.isBiggerOrEqualTo("1.20")
            val newFormat = mcVersion.isBiggerOrEqualTo("1.20.2")
            val dir = File(version.getGameDir(), "resourcepacks").also { it.mkdirs() }
            val target = File(dir, PACK_NAME)
            val mark = File(dir, "$PACK_NAME.json")
            val index = runCatching {
                JsonParser.parseString(fetch(BASE + "index.json")).asJsonObject
            }.getOrNull()
            val entry = index?.getAsJsonObject(if (modern) "_pack" else "_packLegacy")
            val fmt = if (newFormat) 0 else packFormat(mcVersion)
            if (entry != null) {
                val file = entry.get("file").asString
                val sha1 = entry.get("sha1")?.asString ?: file
                val tag = "$sha1|$fmt"
                val upToDate = target.exists() && mark.exists() && mark.readText() == tag
                if (!upToDate) {
                    val raw = File(dir, "$PACK_NAME.part")
                    download(BASE + file, raw)
                    if (entry.get("sha1") != null && sha1Of(raw) != sha1) {
                        raw.delete()
                        error("sha1 mismatch")
                    }
                    if (fmt == 0) {
                        raw.copyTo(target, true)
                    } else {
                        rewriteMcmeta(raw, target, fmt)
                    }
                    raw.delete()
                    mark.writeText(tag)
                }
            }
            if (!target.exists()) return@runCatching null
            if (mcVersion.isBiggerOrEqualTo("1.13")) "file/$PACK_NAME" else PACK_NAME
        }.onFailure { Logger.warning(TAG, "Unable to prepare the GameLand UI pack", it) }.getOrNull()
    }

    fun applyPackOption(entry: String?) {
        if (entry == null) return
        val bare = PACK_NAME
        val list = MCOptions.getAsList("resourcePacks")
            .map { it.trim().removeSurrounding("\"") }
            .filter { it.isNotBlank() && it != bare && it != "file/$bare" }
            .toMutableList()
        if (entry.startsWith("file/") && !list.contains("vanilla")) list.add(0, "vanilla")
        list.add(entry)
        MCOptions.set("resourcePacks", list)
    }

    private fun packFormat(mcVersion: String): Int {
        var out = 1
        FORMATS.forEach { (v, f) -> if (mcVersion.isBiggerOrEqualTo(v)) out = f }
        return out
    }

    private fun rewriteMcmeta(src: File, dest: File, format: Int) {
        val tmp = File(dest.parentFile, dest.name + ".tmp")
        ZipFile(src).use { zip ->
            ZipOutputStream(tmp.outputStream().buffered()).use { out ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val e = entries.nextElement()
                    out.putNextEntry(ZipEntry(e.name))
                    if (e.name == "pack.mcmeta") {
                        out.write("{\"pack\":{\"pack_format\":$format,\"description\":\"GameLand UI\"}}".toByteArray())
                    } else if (!e.isDirectory) {
                        zip.getInputStream(e).use { it.copyTo(out) }
                    }
                    out.closeEntry()
                }
            }
        }
        tmp.copyTo(dest, true)
        tmp.delete()
    }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 60000
            setRequestProperty("User-Agent", "GameLandLauncher-Android")
        }

    private fun fetch(url: String): String {
        val c = open(url)
        try {
            if (c.responseCode != 200) error("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().readText()
        } finally {
            c.disconnect()
        }
    }

    private fun download(url: String, dest: File) {
        val c = open(url)
        try {
            if (c.responseCode != 200) error("HTTP ${c.responseCode}")
            c.inputStream.use { input -> dest.outputStream().use { input.copyTo(it) } }
        } finally {
            c.disconnect()
        }
    }

    private fun sha1Of(file: File): String {
        val md = MessageDigest.getInstance("SHA-1")
        file.inputStream().use { s ->
            val buf = ByteArray(65536)
            while (true) {
                val n = s.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
