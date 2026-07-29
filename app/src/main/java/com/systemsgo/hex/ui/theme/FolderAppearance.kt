package com.systemsgo.hex.ui.theme

// FOLDER-APPEARANCE FEATURE: kept here (a plain UI-layer file) rather than
// on ConnectionFolder/FolderColor/FolderIcon themselves, so the data model
// stays free of any Compose/Material import — this is the one place a
// FolderIcon enum name becomes an actual glyph and a FolderColor enum name
// becomes an actual Compose Color. Shared by HomeScreen.kt's FolderPill and
// Components.kt's NewFolderDialog/RenameFolderDialog swatch+glyph pickers,
// so both draw from exactly the same palette/icon set.

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Label
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.Work
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.systemsgo.hex.data.model.ConnectionFolder
import com.systemsgo.hex.data.model.FolderColor
import com.systemsgo.hex.data.model.FolderIcon

val FolderIconMap: Map<FolderIcon, ImageVector> = mapOf(
    FolderIcon.FOLDER to Icons.Outlined.Folder,
    FolderIcon.WORK   to Icons.Outlined.Work,
    FolderIcon.HOME   to Icons.Outlined.Home,
    FolderIcon.SERVER to Icons.Outlined.Dns,
    FolderIcon.CLOUD  to Icons.Outlined.Cloud,
    FolderIcon.STAR   to Icons.Outlined.Star,
    FolderIcon.LOCK   to Icons.Outlined.Lock,
    FolderIcon.LABEL  to Icons.Outlined.Label,
)

/** Resolves this folder's chosen icon (or the plain folder glyph if unset/unrecognized). */
fun ConnectionFolder.resolvedIcon(): ImageVector =
    FolderIcon.fromName(icon)?.let { FolderIconMap[it] } ?: Icons.Outlined.Folder

/** Resolves this folder's chosen swatch (or null == "use the default accent treatment" if unset/unrecognized). */
fun ConnectionFolder.resolvedColor(): Color? =
    FolderColor.fromName(color)?.let { Color(it.argb) }
