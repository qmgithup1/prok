package com.systemsgo.hex.data.repository

import com.systemsgo.hex.data.db.ConnectionFolderDao
import com.systemsgo.hex.data.model.ConnectionFolder
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ConnectionFolderRepository @Inject constructor(
    private val dao: ConnectionFolderDao
) {
    fun getAllFolders(): Flow<List<ConnectionFolder>> = dao.getAllFolders()

    // FOLDER-APPEARANCE FEATURE: color/icon are optional at creation time
    // (both default to "" == unset, same neutral look every folder had
    // before this feature existed) so NewFolderDialog can offer them
    // without forcing a choice.
    suspend fun createFolder(name: String, color: String = "", icon: String = ""): ConnectionFolder {
        val folder = ConnectionFolder(name = name.trim(), color = color, icon = icon)
        dao.insertFolder(folder)
        return folder
    }

    suspend fun renameFolder(folder: ConnectionFolder, newName: String) =
        dao.updateFolder(folder.copy(name = newName.trim()))

    // FOLDER-APPEARANCE FEATURE: separate from renameFolder so the rename
    // dialog's "Save" can update name + color + icon together in one write
    // instead of three round-trips.
    suspend fun updateAppearance(folder: ConnectionFolder, newName: String, color: String, icon: String) =
        dao.updateFolder(folder.copy(name = newName.trim(), color = color, icon = icon))

    // Deletes the folder itself, but never the connections in it — they're
    // un-filed (folderId -> null) first so they simply fall back to the
    // "All" view instead of being silently lost.
    suspend fun deleteFolder(folder: ConnectionFolder) {
        dao.clearFolderFromProfiles(folder.id)
        dao.deleteFolder(folder)
    }

    suspend fun reorderFolders(folders: List<ConnectionFolder>) {
        folders.forEachIndexed { index, folder ->
            if (folder.sortOrder != index) {
                dao.updateFolder(folder.copy(sortOrder = index))
            }
        }
    }
}
