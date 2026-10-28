package dev.spike.glucobridge.libreview

import android.content.SharedPreferences
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * LibreView's app-facing API, as the Libre 3 Android app uses it (SPEC §3; Juggluco's Libreview.java).
 * Blocking — call from Dispatchers.IO. Config and token are cached in [prefs]; the token is a secret.
 */
class LibreViewClient(private val prefs: SharedPreferences) {
    class Failure(message: String, val permanent: Boolean) : Exception(message)

    /** The account is bound to another device (e.g. Juggluco). Only SetDevice:true gets past it. */
    class OtherDevice(reason: String) : Exception(reason)

    private val assets = "https://fsll3.freestyleserver.com/Payloads/Mobile/FSLibre3/Android/Assets/3.3.0/DE.json"

    val deviceId: String
        get() = prefs.getString("deviceId", null) ?: java.util.UUID.randomUUID().toString().also {
            prefs.edit().putString("deviceId", it).apply()
        }

    private fun base(): String {
        prefs.getString("newYuUrl", null)?.let { return it }
        val cfgUrl = Json.parseToJsonElement(get(assets)).jsonObject["Configuration"]!!.jsonPrimitive.content
        val cfg = Json.parseToJsonElement(get(cfgUrl)).jsonObject
        val url = cfg["newYuUrl"]!!.jsonPrimitive.content
        prefs.edit().putString("newYuUrl", url).putString("newYuApiKey", cfg["newYuApiKey"]!!.jsonPrimitive.content).apply()
        return url
    }

    /** Logs in; stores the token and the person fields needed for sensor registration. */
    fun auth(email: String, password: String, setDevice: Boolean) {
        val body = buildJsonObject {
            put("Culture", LibreViewPayload.CULTURE); put("DeviceId", deviceId); put("Password", password)
            put("SetDevice", setDevice); put("UserName", email); put("Domain", "Libreview"); put("GatewayType", LibreViewPayload.GATEWAY)
        }.toString()
        val o = call("POST", "/api/nisperson/getauthentication", body, token = "")
        val status = o["status"]?.jsonPrimitive?.intOrNull
        val reason = o["reason"]?.jsonPrimitive?.content.orEmpty()
        if (status == 20 && "wrongDeviceForUser" in reason) throw OtherDevice(reason)
        if (status != 0) throw Failure("auth status $status $reason", permanent = true)
        val r = o["result"]!!.jsonObject
        prefs.edit()
            .putString("token", r["UserToken"]!!.jsonPrimitive.content)
            .putString("person", buildJsonObject {
                for (k in listOf("DateOfBirth", "FirstName", "LastName", "GuardianFirstName", "GuardianLastName"))
                    put(k, r[k]?.jsonPrimitive?.content.orEmpty())
            }.toString())
            .apply()
    }

    val token get() = prefs.getString("token", null)

    /** Returns LibreView's `status` (0 = accepted) and reason. */
    fun postMeasurements(body: String): Pair<Int, String> {
        val o = call("POST", "/api/measurements", body, token.orEmpty())
        return (o["status"]?.jsonPrimitive?.int ?: -1) to o["reason"]?.jsonPrimitive?.content.orEmpty()
    }

    /** Juggluco's `getlibre3puttext`: tells the account which sensor is active. */
    fun putSensor(serial9: String): Pair<Int, String> {
        val person = Json.parseToJsonElement(prefs.getString("person", "{}")!!).jsonObject
        val body = buildJsonObject {
            put("DateOfBirth", person["DateOfBirth"] ?: JsonPrimitive(""))
            put("DomainData", "{\"activeSensor\":\"$serial9\"}")
            for (k in listOf("FirstName", "GuardianFirstName", "GuardianLastName", "LastName")) put(k, person[k] ?: JsonPrimitive(""))
            put("UserToken", token.orEmpty()); put("Domain", "Libreview"); put("GatewayType", LibreViewPayload.GATEWAY)
        }.toString()
        val o = call("PUT", "/api/nisperson", body, token.orEmpty())
        return (o["status"]?.jsonPrimitive?.int ?: -1) to o["reason"]?.jsonPrimitive?.content.orEmpty()
    }

    private fun call(method: String, path: String, body: String, token: String): JsonObject {
        val url = base() + path
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 10_000
            readTimeout = 60_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Platform", "Android")
            setRequestProperty("Version", "3.3.0")
            setRequestProperty("Abbott-ADC-App-Platform", "Android/16/FSL3/3.3.0.9092")
            setRequestProperty("Accept-Language", LibreViewPayload.CULTURE)
            setRequestProperty("x-api-key", prefs.getString("newYuApiKey", "")!!)
            setRequestProperty("x-newyu-token", token)
        }
        c.outputStream.use { it.write(body.toByteArray()) }
        val code = c.responseCode
        val text = (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (code != 200) throw Failure("$method $path → HTTP $code", permanent = code !in listOf(408, 425, 429) && code < 500)
        return Json.parseToJsonElement(text).jsonObject
    }

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        if (c.responseCode != 200) throw Failure("GET $url → HTTP ${c.responseCode}", permanent = false)
        return c.inputStream.bufferedReader().use { it.readText() }
    }
}
