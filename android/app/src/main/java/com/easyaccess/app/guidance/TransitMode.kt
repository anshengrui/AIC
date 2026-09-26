package com.easyaccess.app.guidance

enum class TransitMode(
    val id: String,
    val displayName: String,
) {
    BUS("bus", "公交车"),
    SUBWAY("subway", "地铁");

    companion object {
        fun fromId(id: String?): TransitMode? = entries.firstOrNull { it.id == id }
    }
}
