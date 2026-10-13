package com.cleo.cleos.data

/** Stable preference keys; old installs retain their existing gallery. */
enum class PhotoLibrary(val title: String) {
    Openverse("Openverse"), WordPress("WordPress 照片库");

    companion object {
        fun decode(value: String?) = entries.firstOrNull { it.name == value } ?: Openverse
    }
}
