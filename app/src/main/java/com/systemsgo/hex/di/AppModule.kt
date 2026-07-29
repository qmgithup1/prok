package com.systemsgo.hex.di

import android.content.Context
import androidx.room.Room
import com.systemsgo.hex.data.db.ConnectionLogDao
import com.systemsgo.hex.data.db.DatabaseEncryptionMigrator
import com.systemsgo.hex.data.db.DatabaseKeyProvider
import com.systemsgo.hex.data.db.DatabaseOpenRecovery
import com.systemsgo.hex.data.db.SystemsGoDatabase
import com.systemsgo.hex.data.db.RdpProfileDao
import com.systemsgo.hex.data.db.MIGRATION_1_2
import com.systemsgo.hex.data.db.MIGRATION_2_3
import com.systemsgo.hex.data.db.MIGRATION_3_4
import com.systemsgo.hex.data.db.MIGRATION_4_5
import com.systemsgo.hex.data.db.MIGRATION_5_6  // BUG-3 FIX
import com.systemsgo.hex.data.db.MIGRATION_6_7  // BUG-3 FIX (acceptSelfSignedCertificate)
import com.systemsgo.hex.data.db.MIGRATION_7_8  // CRIT-R1 FIX (SSH Tunnel re-encryption)
import com.systemsgo.hex.data.db.MIGRATION_8_9  // SSH agent forwarding column
import com.systemsgo.hex.data.db.MIGRATION_9_10 // Dynamic SOCKS5 proxy columns (ssh -D equivalent)
import com.systemsgo.hex.data.db.MIGRATION_10_11 // Microphone (audin) redirection column
import com.systemsgo.hex.data.db.MIGRATION_11_12 // Connection folders (categories)
import com.systemsgo.hex.data.db.MIGRATION_12_13 // Connection tags
import com.systemsgo.hex.data.db.MIGRATION_13_14 // Favorites
import com.systemsgo.hex.data.db.MIGRATION_14_15 // Wake & Connect (port/timeout/retry columns)
import com.systemsgo.hex.data.db.MIGRATION_15_16 // Multi-Monitor (preferredMonitorId column)
import com.systemsgo.hex.data.db.MIGRATION_16_17 // Printer redirection (enablePrinterRedirect column)
import com.systemsgo.hex.data.db.MIGRATION_17_18 // Webcam redirection (enableWebcamRedirect column)
import com.systemsgo.hex.data.db.MIGRATION_18_19 // RemoteApp-Windows feature (remoteAppDisplayMode column)
import com.systemsgo.hex.data.db.MIGRATION_19_20 // Smartcard-Redirect feature (enableSmartcardRedirect column)
import com.systemsgo.hex.data.db.MIGRATION_20_21 // Codec-Negotiation feature (codecPreference column)
import com.systemsgo.hex.data.db.MIGRATION_21_22 // Parallel-Redirect feature (enableParallelRedirect/parallelPortPath columns)
import com.systemsgo.hex.data.db.MIGRATION_22_23 // Serial-Redirect feature (enableSerialRedirect/serialPortPath columns)
import com.systemsgo.hex.data.db.MIGRATION_35_36 // Serial-over-Network feature (serialRedirectMode/serialNetworkHost/serialNetworkPort columns)
import com.systemsgo.hex.data.db.MIGRATION_36_37 // Web/HTTPS portal feature (webUrl/webTrustSelfSignedCertificate/webAutoFillHttpAuth columns)
import com.systemsgo.hex.data.db.MIGRATION_37_38 // Web-portal smart-autofill feature (webAutoFillLoginForm column)
import com.systemsgo.hex.data.db.MIGRATION_38_39 // PAC-support feature (pacUrl column)
import com.systemsgo.hex.data.db.MIGRATION_39_40 // RDP-over-WebSocket feature (transportMode/webSocketConfig columns)
import com.systemsgo.hex.data.db.MIGRATION_40_41 // Redfish/IPMI feature (ipmiPrivilegeLevel column + REDFISH/IPMI protocol types)
import com.systemsgo.hex.data.db.MIGRATION_41_42 // AMT/vPro feature (amtUseTls column + ProtocolType.AMT)
import com.systemsgo.hex.data.db.MIGRATION_42_43 // Rlogin feature (rloginRemoteUsername/rloginTerminalType columns)
import com.systemsgo.hex.data.db.MIGRATION_43_44 // Serial Console feature (serialConsole* columns)
import com.systemsgo.hex.data.db.MIGRATION_44_45 // Pin Connection feature (isPinned/pinnedOrder columns)
import com.systemsgo.hex.data.db.MIGRATION_45_46 // RESTCONF feature (restconf* columns + ProtocolType.RESTCONF)
import com.systemsgo.hex.data.db.MIGRATION_46_47 // RESTCONF API Explorer (saved requests/collections/history tables)
import com.systemsgo.hex.data.db.MIGRATION_47_48 // RESTCONF Environment Variables (restconf_environments table)
import com.systemsgo.hex.data.db.MIGRATION_48_49 // SNMP feature (snmp* columns)
import com.systemsgo.hex.data.db.MIGRATION_49_50 // NETCONF feature (netconf* columns + ProtocolType.NETCONF)
import com.systemsgo.hex.data.db.MIGRATION_50_51 // NETCONF Call Home feature (RFC 8071, netconfCallHome* columns)
import com.systemsgo.hex.data.db.MIGRATION_51_52 // NETCONF Call Home over TLS (RFC 8071 netconf-ch-tls, netconfCallHomeTransport/netconfCallHomeTlsClientCertificatePem columns)
import com.systemsgo.hex.data.db.MIGRATION_52_53 // Guacamole feature (guac* columns)
import com.systemsgo.hex.data.db.MIGRATION_53_54 // Serial Console hardware flow control (serialConsoleHardwareFlowControl column)
import com.systemsgo.hex.data.db.MIGRATION_54_55 // IPMI Kg/two-key login support (ipmiKgKey column)
import com.systemsgo.hex.data.db.MIGRATION_55_56 // Mosh feature (mosh* columns)
import com.systemsgo.hex.data.db.MIGRATION_56_57 // Proxmox API feature (proxmox* columns)
import com.systemsgo.hex.data.db.MIGRATION_57_58 // Modbus TCP feature (modbus* columns)
import com.systemsgo.hex.data.db.MIGRATION_58_59 // VirtualBox VRDE feature (vrde* columns)
import com.systemsgo.hex.data.db.MIGRATION_59_60 // VMware vSphere API feature (vsphere* columns)
import com.systemsgo.hex.data.db.MIGRATION_60_61 // RTSP feature (rtsp* columns — BUGFIX: previously-missing migration for a fully-shipped protocol)
import com.systemsgo.hex.data.db.MIGRATION_61_62 // FTP/FTPS/WebDAV/SMB/NFS-standalone feature (ftpSecurity/ftpPassiveMode/smbShare/smbDomain/webdavBaseUrl/nfsExportPath/nfsUid/nfsGid/nfsMountdPort columns)
import com.systemsgo.hex.data.db.MIGRATION_62_63 // AMT/vPro CIRA setup UI (ciraEnabled/ciraRelayHost/ciraRelayPort/ciraRelayUsername/ciraRelayPassword/ciraDeviceId columns)
import com.systemsgo.hex.data.db.MIGRATION_63_64 // AMT/vPro CIRA relay wss:// support, Phase 6 Part 3 (ciraRelayUseTls column)
import com.systemsgo.hex.data.db.MIGRATION_23_24 // Outbound-Proxy feature (proxyEnabled/proxyType/proxyHost/proxyPort/proxyUsername/proxyPassword columns)
import com.systemsgo.hex.data.db.MIGRATION_24_25 // Telnet feature (telnetUseTls column)
import com.systemsgo.hex.data.db.MIGRATION_25_26 // X11 Forwarding feature (x11ForwardingEnabled/x11DisplayHost/x11DisplayNumber/x11AuthCookie columns)
import com.systemsgo.hex.data.db.MIGRATION_26_27 // SSH Port Forwarding feature (sshPortForwards column)
import com.systemsgo.hex.data.db.MIGRATION_27_28 // Folder Appearance feature (connection_folders.color/icon columns)
import com.systemsgo.hex.data.db.MIGRATION_28_29 // UltraVNC Repeater feature (vncRepeaterEnabled/vncRepeaterId columns)
import com.systemsgo.hex.data.db.MIGRATION_29_30 // Entra ID Gateway auth feature (gatewayAuthMode/entraLinkedUpn columns)
import com.systemsgo.hex.data.db.MIGRATION_30_31 // Entra ID Gateway auth feature (per-profile gatewayScopeUri column)
import com.systemsgo.hex.data.db.MIGRATION_31_32 // UltraVNC Repeater feature (vncRepeaterMode column — Mode I/II)
import com.systemsgo.hex.data.db.MIGRATION_32_33 // Listen-mode / reverse VNC feature (vncListenModeEnabled/vncListenPort columns)
import com.systemsgo.hex.data.db.MIGRATION_33_34 // RD Web Feed feature (web_feed_subscriptions table + webFeedSubscriptionId/webFeedAlias columns)
import com.systemsgo.hex.data.db.MIGRATION_34_35 // SSH ProxyJump chain feature (sshTunnelHops column)
import com.systemsgo.hex.data.db.WebFeedSubscriptionDao
import com.systemsgo.hex.data.db.ConnectionFolderDao
import com.systemsgo.hex.session.SessionTabManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
// 🔴 CRITICAL FIX (build break, 2nd round): net.zetetic.database.sqlcipher.SupportFactory
// does NOT exist in the new `net.zetetic:sqlcipher-android` artifact. The equivalent
// Room/androidx.sqlite glue class in this artifact is called SupportOpenHelperFactory.
// (SupportFactory only exists in the deprecated net.sqlcipher.database package.)
// See: https://github.com/sqlcipher/sqlcipher-android (README "Room API via the
// SupportOpenHelperFactory").
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): SystemsGoDatabase {
        // HIGH-3 FIX: Encrypt the Room database with SQLCipher.
        //
        // Step 1: retrieve or generate the 32-byte AES passphrase.
        //         The key is stored encrypted in SharedPreferences, wrapped by
        //         an AES-256-GCM key held inside the Android Keystore.
        val passphrase = DatabaseKeyProvider.getOrCreate(context)

        // Step 2: if the existing database file is plain SQLite (first boot after
        //         this security update), encrypt it in-place before Room opens it.
        //         DatabaseEncryptionMigrator only zeroes `passphrase` if that
        //         plaintext->encrypted migration actually runs; in the normal case
        //         (already-encrypted DB, or fresh install) it returns immediately
        //         and leaves `passphrase` untouched for step 3 below.
        DatabaseEncryptionMigrator.migrate(context, passphrase)

        // Step 3: build Room with SQLCipher's SupportOpenHelperFactory so all I/O goes
        //         through AES-256-CBC (SQLCipher 4 default), then eagerly open it via
        //         DatabaseOpenRecovery. If the on-disk database was encrypted with an
        //         old Keystore-backed key that is now lost (so `passphrase` above is a
        //         freshly-generated replacement), SQLCipher cannot decrypt the existing
        //         file — DatabaseOpenRecovery detects exactly that failure signature,
        //         deletes the unreadable database (+ -wal/-shm), and recreates it with
        //         the current key so the app starts normally instead of crashing.
        // buildRoom() may be invoked more than once by DatabaseOpenRecovery (original
        // attempt + one retry after recovery), so it takes its own defensive copy of
        // the passphrase each time rather than consuming a shared one.
        fun buildRoom(): SystemsGoDatabase {
            val passphraseForRoom = passphrase.copyOf()
            // 🔴 FIX: SupportOpenHelperFactory(byte[]) is the real constructor in this
            // artifact (single-arg overload delegates to (password, hook=null, enableWriteAheadLogging=false)).
            val roomDb = Room.databaseBuilder(
                context,
                SystemsGoDatabase::class.java,
                SystemsGoDatabase.DATABASE_NAME
            )
                .openHelperFactory(SupportOpenHelperFactory(passphraseForRoom))
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16, MIGRATION_16_17, MIGRATION_17_18, MIGRATION_18_19, MIGRATION_19_20, MIGRATION_20_21, MIGRATION_21_22, MIGRATION_22_23, MIGRATION_23_24, MIGRATION_24_25, MIGRATION_25_26, MIGRATION_26_27, MIGRATION_27_28, MIGRATION_28_29, MIGRATION_29_30, MIGRATION_30_31, MIGRATION_31_32, MIGRATION_32_33, MIGRATION_33_34, MIGRATION_34_35, MIGRATION_35_36, MIGRATION_36_37, MIGRATION_37_38, MIGRATION_38_39, MIGRATION_39_40, MIGRATION_40_41, MIGRATION_41_42, MIGRATION_42_43, MIGRATION_43_44, MIGRATION_44_45, MIGRATION_45_46, MIGRATION_46_47, MIGRATION_47_48, MIGRATION_48_49, MIGRATION_49_50, MIGRATION_50_51, MIGRATION_51_52, MIGRATION_52_53, MIGRATION_53_54, MIGRATION_54_55, MIGRATION_55_56, MIGRATION_56_57, MIGRATION_57_58, MIGRATION_58_59, MIGRATION_59_60, MIGRATION_60_61, MIGRATION_61_62, MIGRATION_62_63, MIGRATION_63_64)
                .fallbackToDestructiveMigrationOnDowngrade(dropAllTables = true)
                .build()
            // CRITICAL-1 FIX: zero the Room copy now that SupportOpenHelperFactory has
            // consumed it. SupportOpenHelperFactory copies the key internally on
            // construction, so zeroing here is safe.
            passphraseForRoom.fill(0)
            return roomDb
        }

        val db = DatabaseOpenRecovery.openWithRecovery(context, ::buildRoom)

        // The shared passphrase has now been consumed by every buildRoom() call
        // DatabaseOpenRecovery made (each took its own copy); zero it here too.
        passphrase.fill(0)

        return db
    }

    @Provides
    @Singleton
    fun provideRdpProfileDao(db: SystemsGoDatabase): RdpProfileDao = db.rdpProfileDao()

    @Provides
    @Singleton
    fun provideConnectionLogDao(db: SystemsGoDatabase): ConnectionLogDao = db.connectionLogDao()

    @Provides
    @Singleton
    fun provideConnectionFolderDao(db: SystemsGoDatabase): ConnectionFolderDao = db.connectionFolderDao()

    @Provides
    @Singleton
    fun provideWebFeedSubscriptionDao(db: SystemsGoDatabase): WebFeedSubscriptionDao = db.webFeedSubscriptionDao()

    // RESTCONF FEATURE (Part 3/4): backs RestconfExplorerRepository (Saved Requests/Collections/History).
    @Provides
    @Singleton
    fun provideRestconfExplorerDao(db: SystemsGoDatabase): com.systemsgo.hex.data.db.RestconfExplorerDao = db.restconfExplorerDao()

    @Provides
    @Singleton
    fun provideSessionTabManager(): SessionTabManager = SessionTabManager()
}
