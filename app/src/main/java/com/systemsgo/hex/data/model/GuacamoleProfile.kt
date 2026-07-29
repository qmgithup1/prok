package com.systemsgo.hex.data.model

/**
 * GUACAMOLE-PROTOCOL FEATURE (Part 1/N).
 *
 * Everything needed to reach a Guacamole server and log in — the
 * [com.systemsgo.hex.guacamole.GuacamoleAuthClient] side of a Guacamole
 * profile. Deliberately NOT yet a field group bolted onto [RdpProfile]
 * (unlike REDFISH/IPMI/AMT, which reuse RdpProfile's Room entity for their
 * host/port/username/password columns): every other protocol's fields
 * landed in RdpProfile as part of the same change that also added its
 * [ProtocolType] entry and RemoteSessionFactory branch, and Guacamole
 * doesn't have either yet (see this feature's Part 1/N doc note in
 * RemoteSessionFactory-adjacent files for why). Part 2/N is where this
 * either becomes real `@ColumnInfo` fields on [RdpProfile] with a Room
 * migration (matching precedent) or — given how different Guacamole's
 * shape is (a server URL + data source + a *picked* remote connection
 * identifier, vs. every other protocol's direct host/port) — its own
 * `@Entity`; that call is easier to make once Part 2/N's connection-picker
 * UI shows exactly what a saved profile needs to remember.
 *
 * Field-by-field mapping to reg.txt:
 * - [serverUrl]/[username]/[password]: AUTHENTICATION → Username/Password.
 * - [dataSource]: which Guacamole authentication backend
 *   (mysql/postgresql/ldap/...) owns the account — Guacamole servers with
 *   more than one configured backend require this on every REST call; see
 *   [com.systemsgo.hex.guacamole.GuacamoleAuthResult.availableDataSources].
 * - [connectionIdentifier]/[connectionName]: CONNECTION MANAGEMENT → the
 *   specific connection this profile launches, picked from
 *   [com.systemsgo.hex.guacamole.GuacamoleRepository.listConnections] in
 *   the Part 2/N "Add Connection" flow. [connectionProtocol] is carried
 *   along purely for UI display (icon/label) — reg.txt's SUPPORTED
 *   PROTOCOLS section is explicit that the app must not hardcode behavior
 *   per inner protocol, so this app treats it as opaque metadata, never as
 *   a branch condition.
 * - [acceptSelfSignedCertificate]: SECURITY → self-signed handling, same
 *   flag shape as every other protocol's profile field.
 */
data class GuacamoleProfile(
    val serverUrl: String, // e.g. "https://guac.example.com/guacamole" — see GuacamoleServerConfig.baseUrl's doc
    val username: String,
    val password: String,
    val dataSource: String? = null, // null = use the server's default data source from the login response
    val connectionIdentifier: String = "",
    val connectionName: String = "",
    val connectionProtocol: String? = null, // display-only, e.g. "rdp" / "vnc" / "ssh" — see class doc
    val acceptSelfSignedCertificate: Boolean = false,
)
