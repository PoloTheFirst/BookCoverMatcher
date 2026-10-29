package com.bookcovermatcher.data

import com.bookcovermatcher.core.catalog.ColumnMappingCodec
import com.bookcovermatcher.core.catalog.ColumnMappingProfile
import java.io.File

/**
 * The saved column-mapping profiles (`files/mappings/profiles.json`).
 *
 * Same shape as the app's other stores: one small JSON file, a temp file + rename on write so an
 * interrupted save cannot truncate the list, and a read that never throws. The profiles are user data,
 * so saving one keeps every other profile and replaces only the same-named one.
 */
class ColumnMappingStore(private val dir: File) {

    private val file: File get() = File(dir, "profiles.json")

    fun load(): List<ColumnMappingProfile> {
        val text = try {
            if (file.isFile) file.readText() else null
        } catch (e: Exception) {
            null
        } ?: return emptyList()
        return ColumnMappingCodec.decode(text)
    }

    /** Adds or replaces [profile] by name and returns the stored list. */
    fun save(profile: ColumnMappingProfile): List<ColumnMappingProfile> {
        val next = load().filterNot { it.name == profile.name } + profile
        write(next)
        return next
    }

    fun delete(name: String): List<ColumnMappingProfile> {
        val next = load().filterNot { it.name == name }
        write(next)
        return next
    }

    private fun write(profiles: List<ColumnMappingProfile>) {
        dir.mkdirs()
        val temp = File(dir, "profiles.json.tmp")
        temp.writeText(ColumnMappingCodec.encode(profiles))
        if (!temp.renameTo(file)) {
            file.delete()
            temp.renameTo(file)
        }
    }
}