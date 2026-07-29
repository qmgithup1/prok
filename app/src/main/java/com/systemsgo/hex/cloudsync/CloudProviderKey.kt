package com.systemsgo.hex.cloudsync

import dagger.MapKey

/**
 * CLOUD-SYNC FEATURE (Part 2/3).
 *
 * Hilt/Dagger `@MapKey` used to multibind each [CloudSyncProvider]
 * implementation into a `Map<CloudProvider, CloudSyncProvider>` — see
 * `di/AppModule.kt`'s cloud-sync bindings. A dedicated enum-valued key
 * (rather than `@StringKey(provider.storageKey)`) keeps the map's key type
 * as the actual [CloudProvider] enum everywhere it's consumed
 * ([CloudSyncManager], and eventually Part 3's Settings UI), so callers
 * never need to round-trip through `storageKey`/`fromStorageKeyOrNull` just
 * to look a provider up.
 */
@MapKey
annotation class CloudProviderKey(val value: CloudProvider)
