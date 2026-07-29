package com.systemsgo.hex.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * A user-defined folder (category) used to group saved connections in the
 * connection list. A connection belongs to at most one folder — see
 * [RdpProfile.folderId]. Connections with folderId == null are "unfiled"
 * and show up under the implicit "All" / "Unfiled" view.
 */
@Entity(tableName = "connection_folders")
data class ConnectionFolder(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val createdAt: Long = System.currentTimeMillis(),
    // Lets folders be reordered in the UI the same way profiles already are
    // (RdpProfile.sortOrder); defaults to creation order.
    val sortOrder: Int = 0,
    // FOLDER-APPEARANCE FEATURE: purely cosmetic, zero effect on filtering
    // or connection logic — lets folders be told apart at a glance once a
    // user has more than a couple of them.
    //
    // Stored as one of [FolderColor]'s enum names rather than a raw ARGB
    // int so the palette can be re-themed centrally (light/dark, future
    // themes) without a data migration — see FolderColor.toColor(). Empty
    // string == "no color chosen" -> falls back to the neutral default the
    // pill already used before this feature existed.
    val color: String = "",
    // One of [FolderIcon]'s enum names. Empty string == "no icon chosen"
    // -> falls back to the plain folder glyph already used before this
    // feature existed (Icons.Outlined.Folder).
    val icon: String = "",
)

/**
 * Fixed palette a folder's [ConnectionFolder.color] can be set to. A closed
 * enum (rather than a free-form hex string) keeps every folder color
 * legible against both NebulaSurface (unselected pill) and its own
 * selected-state tint, and keeps the color picker UI a simple fixed swatch
 * row instead of a full color wheel.
 */
enum class FolderColor(val argb: Long) {
    CYAN(0xFF29D9E8),
    BLUE(0xFF4A7CFF),
    VIOLET(0xFF9B6BFF),
    PINK(0xFFFF6BB3),
    RED(0xFFFF5C5C),
    ORANGE(0xFFFF9D42),
    YELLOW(0xFFF2D544),
    GREEN(0xFF4ADE80);

    companion object {
        fun fromName(name: String): FolderColor? = entries.firstOrNull { it.name == name }
    }
}

/**
 * Fixed set of icons a folder's [ConnectionFolder.icon] can be set to —
 * intentionally small and generic (work, home, server, etc.) rather than
 * exhaustive, mirroring [FolderColor]'s closed-palette reasoning. Mapped to
 * an actual Material icon in the UI layer (see FolderIconMap in
 * HomeScreen.kt) so this model file stays free of any Compose/UI import.
 */
enum class FolderIcon {
    FOLDER,
    WORK,
    HOME,
    SERVER,
    CLOUD,
    STAR,
    LOCK,
    LABEL;

    companion object {
        fun fromName(name: String): FolderIcon? = entries.firstOrNull { it.name == name }
    }
}
