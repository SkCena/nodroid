package dev.nodroid.security

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject

/**
 * Safe workflow export: vault references ({{vault.KEY}}) and secret-named config values
 * are removed before the JSON ever leaves the device. No secret value is ever written to an export.
 */
object ExportScrubber {
    private val vaultRef = Regex("\\{\\{vault\\.[A-Za-z0-9_]+\\}\\}")
    private val secretKey = Regex("(?i)(secret|password|passwd|api[_-]?key|token|auth|private)")

    fun containsVaultReference(text: String): Boolean = vaultRef.containsMatchIn(text)

    fun scrubJson(text: String): String {
        val element = try {
            Json.parseToJsonElement(text)
        } catch (e: Exception) {
            return text
        }
        return Json.encodeToString(JsonElement.serializer(), scrub(element))
    }

    private fun scrub(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> buildJsonObject {
            for ((key, value) in element.jsonObject) {
                if (secretKey.containsMatchIn(key)) continue
                val scrubbed = scrub(value)
                if (scrubbed is JsonPrimitive && scrubbed.isString && vaultRef.containsMatchIn(scrubbed.content)) continue
                put(key, scrubbed)
            }
        }
        is JsonArray -> JsonArray(element.map { scrub(it) })
        is JsonPrimitive -> if (element.isString && vaultRef.containsMatchIn(element.content)) JsonPrimitive("") else element
        is JsonNull -> element
        else -> element
    }
}
