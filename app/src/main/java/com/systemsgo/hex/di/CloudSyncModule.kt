package com.systemsgo.hex.di

import com.systemsgo.hex.cloudsync.CloudProvider
import com.systemsgo.hex.cloudsync.CloudProviderKey
import com.systemsgo.hex.cloudsync.CloudSyncProvider
import com.systemsgo.hex.cloudsync.DropboxSyncProvider
import com.systemsgo.hex.cloudsync.GoogleDriveSyncProvider
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoMap

/**
 * CLOUD-SYNC FEATURE (Part 2/3).
 *
 * Multibinds each concrete [CloudSyncProvider] implementation into a
 * `Map<CloudProvider, CloudSyncProvider>`, keyed by [CloudProviderKey]. This
 * is how [com.systemsgo.hex.cloudsync.CloudSyncManager] resolves "the
 * currently linked provider" (read as a [CloudProvider] out of
 * [com.systemsgo.hex.data.repository.CloudSyncPreferences]) to the right
 * `CloudSyncProvider` instance at call time without an `if`/`when` on
 * `CloudProvider` living inside `CloudSyncManager` itself — adding a third
 * provider later (see [CloudProvider]'s own doc comment) means adding one
 * more `@Binds` here, nothing else.
 *
 * A separate `abstract class` module from the existing `AppModule` (an
 * `object` using `@Provides`) because Dagger's `@Binds` — the
 * "this interface, backed by that implementation" style used here, cheaper
 * than `@Provides` since it generates no factory method body — requires an
 * abstract function on an abstract class/interface module, which `object`
 * modules can't declare.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class CloudSyncModule {

    @Binds
    @IntoMap
    @CloudProviderKey(CloudProvider.GOOGLE_DRIVE)
    abstract fun bindGoogleDriveSyncProvider(impl: GoogleDriveSyncProvider): CloudSyncProvider

    @Binds
    @IntoMap
    @CloudProviderKey(CloudProvider.DROPBOX)
    abstract fun bindDropboxSyncProvider(impl: DropboxSyncProvider): CloudSyncProvider
}
