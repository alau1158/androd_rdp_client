package com.dexrdp.data

import org.json.JSONObject
import java.util.UUID

enum class Resolution(val label: String, val width: Int, val height: Int) {
    AUTO("Auto (match DeX display)", 0, 0),
    UHD_4K("3840 x 2160 (4K UHD)", 3840, 2160),
    QHD("2560 x 1440 (QHD)", 2560, 1440),
    FHD("1920 x 1080 (Full HD)", 1920, 1080),
    HD("1280 x 720 (HD)", 1280, 720);

    fun label(): String = label

    fun sizeOr(fallbackWidth: Int, fallbackHeight: Int): Pair<Int, Int> =
        if (width == 0) fallbackWidth to fallbackHeight else width to height

    companion object {
        fun fromName(name: String?): Resolution =
            entries.firstOrNull { it.name == name } ?: AUTO
    }
}

enum class Transport(val label: String) {
    TCP("TCP only"),
    UDP("TCP + UDP (preferred)");

    fun label(): String = label

    companion object {
        fun fromName(name: String?): Transport =
            entries.firstOrNull { it.name == name } ?: UDP
    }
}

enum class Security(val label: String, val argument: String) {
    AUTO("Auto (recommended)", ""),
    NLA("NLA", "nla"),
    TLS("TLS", "tls"),
    RDP("RDP", "rdp");

    fun label(): String = label

    companion object {
        fun fromName(name: String?): Security =
            entries.firstOrNull { it.name == name } ?: AUTO
    }
}

data class ConnectionProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val host: String = "",
    val port: Int = 3389,
    val username: String = "",
    val domain: String = "",
    val password: String = "",
    val resolution: Resolution = Resolution.AUTO,
    val colorDepth: Int = 32,
    val transport: Transport = Transport.UDP,
    val security: Security = Security.AUTO,
    val audio: Boolean = true,
    val microphone: Boolean = false,
    val clipboard: Boolean = true,
    val h264: Boolean = true,
    val remoteFx: Boolean = false,
    val themes: Boolean = false
) {
    val displayName: String
        get() = if (name.isNotBlank()) name else if (host.isNotBlank()) host else "New connection"

    val endpoint: String
        get() = "$host:$port"

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("host", host)
        put("port", port)
        put("username", username)
        put("domain", domain)
        put("password", password)
        put("resolution", resolution.name)
        put("colorDepth", colorDepth)
        put("transport", transport.name)
        put("security", security.name)
        put("audio", audio)
        put("microphone", microphone)
        put("clipboard", clipboard)
        put("h264", h264)
        put("remoteFx", remoteFx)
        put("themes", themes)
    }

    companion object {
        fun fromJson(json: JSONObject): ConnectionProfile = ConnectionProfile(
            id = json.optString("id", UUID.randomUUID().toString()),
            name = json.optString("name", ""),
            host = json.optString("host", ""),
            port = json.optInt("port", 3389),
            username = json.optString("username", ""),
            domain = json.optString("domain", ""),
            password = json.optString("password", ""),
            resolution = Resolution.fromName(json.optString("resolution")),
            colorDepth = json.optInt("colorDepth", 32),
            transport = Transport.fromName(json.optString("transport")),
            security = Security.fromName(json.optString("security")),
            audio = json.optBoolean("audio", true),
            microphone = json.optBoolean("microphone", false),
            clipboard = json.optBoolean("clipboard", true),
            h264 = json.optBoolean("h264", true),
            remoteFx = json.optBoolean("remoteFx", false),
            themes = json.optBoolean("themes", false)
        )
    }
}