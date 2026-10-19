package org.readera.openreadera.sync

internal fun annotationRemoteWins(
    localUpdated: Long, localCreated: Long, localDeleted: Long,
    remoteUpdated: Long, remoteCreated: Long, remoteDeleted: Long
): Boolean {
    // There is no restore action: deletion must also beat edits from an offline device.
    if (localDeleted > 0L && remoteDeleted == 0L) return false
    if (remoteDeleted > 0L && localDeleted == 0L) return true
    val localVersion = maxOf(localUpdated, localCreated, localDeleted)
    val remoteVersion = maxOf(remoteUpdated, remoteCreated, remoteDeleted)
    return remoteVersion > localVersion ||
        (remoteVersion == localVersion && remoteDeleted > localDeleted)
}
