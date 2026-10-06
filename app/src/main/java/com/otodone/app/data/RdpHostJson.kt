package com.otodone.app.data

import org.json.JSONArray
import org.json.JSONObject

/** 用系统自带的 org.json 做持久化编解码，不额外引序列化库。 */
internal object RdpHostJson {

    fun encode(hosts: List<RdpHost>): String {
        val array = JSONArray()
        hosts.forEach { host ->
            array.put(
                JSONObject()
                    .put("id", host.id)
                    .put("name", host.name)
                    .put("address", host.address)
                    .put("port", host.port)
                    .put("username", host.username)
                    .put("domain", host.domain)
                    .put("password", host.password)
                    .put("lastConnectedAt", host.lastConnectedAt)
            )
        }
        return array.toString()
    }

    fun decode(raw: String?): List<RdpHost> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList(array.length()) {
                for (index in 0 until array.length()) {
                    val json = array.optJSONObject(index) ?: continue
                    val address = json.optString("address")
                    if (address.isBlank()) continue
                    add(
                        RdpHost(
                            id = json.optString("id", address),
                            name = json.optString("name"),
                            address = address,
                            port = json.optInt("port", RdpHost.DEFAULT_PORT)
                                .coerceIn(1, 65535),
                            username = json.optString("username"),
                            domain = json.optString("domain"),
                            password = json.optString("password"),
                            lastConnectedAt = json.optLong("lastConnectedAt", 0L),
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }
}
