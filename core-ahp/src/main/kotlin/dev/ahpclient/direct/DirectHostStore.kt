package dev.ahpclient.direct

import dev.ahpclient.platform.IOException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * The direct hosts the person added: their ids, names and addresses. None of it is secret (the token is in the
 * [dev.ahpclient.security.DirectHostVault]), so it lives in ordinary preferences.
 */
interface DirectHostStore {
    /** @throws IOException when the saved hosts cannot be read. */
    suspend fun load(): List<DirectHost>

    /** @throws IOException when they cannot be saved. */
    suspend fun save(hosts: List<DirectHost>)
}

/** Keeps the hosts for this process only. The default when nothing persists them. */
class InMemoryDirectHostStore(private var hosts: List<DirectHost> = emptyList()) : DirectHostStore {
    override suspend fun load(): List<DirectHost> = hosts

    override suspend fun save(hosts: List<DirectHost>) {
        this.hosts = hosts
    }
}

/**
 * The saved form of the host list: JSON, read leniently. An entry that is damaged, has an address the rules now refuse, or repeats an
 * id is dropped, not an error, so a bad entry never hides the good ones; the list is cut to [MAX_HOSTS].
 */
object DirectHostCodec {
    const val MAX_HOSTS = 20
    const val MAX_NAME = 60

    fun encode(hosts: List<DirectHost>): String {
        val array = JsonArray(hosts.take(MAX_HOSTS).map { host ->
            buildJsonObject {
                put("id", host.id)
                put("name", host.name)
                put("url", host.url)
            }
        })
        return buildJsonObject { put("v", 1); put("hosts", array) }.toString()
    }

    fun decode(text: String?): List<DirectHost> {
        if (text.isNullOrBlank()) return emptyList()
        val array = try {
            ((Json.parseToJsonElement(text) as? JsonObject)?.get("hosts") as? JsonArray) ?: return emptyList()
        } catch (_: IllegalArgumentException) {
            return emptyList()
        }
        val seen = HashSet<String>()
        val out = ArrayList<DirectHost>()
        for (element in array) {
            val o = element as? JsonObject ?: continue
            val id = o.string("id")?.takeIf { DirectHost.isDirectId(it) && it.length in 8..80 } ?: continue
            val name = o.string("name")?.let(::cleanName)?.takeIf { it.isNotEmpty() } ?: continue
            val url = (HostAddress.check(o.string("url") ?: continue) as? HostAddressCheck.Ok)?.url ?: continue
            if (seen.add(id)) out += DirectHost(id, name, url)
            if (out.size == MAX_HOSTS) break
        }
        return out
    }

    /** A name is one line of at most [MAX_NAME] characters with no control characters. */
    fun cleanName(raw: String): String = raw.filter { !it.isISOControl() }.trim().take(MAX_NAME)

    private fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
}
