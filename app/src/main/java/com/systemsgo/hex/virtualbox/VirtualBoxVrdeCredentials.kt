package com.systemsgo.hex.virtualbox

import com.systemsgo.hex.data.model.RdpProfile
import com.systemsgo.hex.data.model.VrdeAuthType

/**
 * VIRTUALBOX-VRDE FEATURE (Part 1/N).
 *
 * Unlike every other "management API" protocol added alongside this one
 * (PROXMOX, VMWARE_VSPHERE — see [com.systemsgo.hex.vsphere.VSphereCredentials]
 * for that one's equivalent), VirtualBox's Remote Display Extension has no
 * management API of its own to model here at all: VRDE *is* an RDP server
 * (VirtualBox embeds a cut-down RDP stack directly), so a
 * [ProtocolType.VIRTUALBOX_VRDE][com.systemsgo.hex.data.model.ProtocolType]
 * profile is consumed exactly like a plain RDP profile —
 * [com.systemsgo.hex.remote.RemoteSessionFactory.create] dispatches it
 * straight through `RdpRemoteAdapter`/FreeRDP, no separate credentials type,
 * no separate wire protocol. There is deliberately no `VirtualBoxVrdeConfig`
 * data class the way [com.systemsgo.hex.proxmox.protocol.ProxmoxConnectionConfig]
 * or [com.systemsgo.hex.vsphere.protocol.VSphereConnectionConfig] exist for
 * their REST APIs — there is nothing beyond RdpCredentials for it to hold.
 *
 * The one real behavioral difference from plain RDP — forcing NLA off,
 * since stock VirtualBox VRDE builds don't implement NLA/CredSSP — lives in
 * `RemoteSessionFactory`'s shared `ProtocolType.RDP, ProtocolType.VIRTUALBOX_VRDE ->`
 * branch, not here.
 *
 * What *does* live here: [RdpProfile.vrdeAuthType] is a purely descriptive
 * field today (see that column's own doc comment and [VrdeAuthType]'s) — it
 * exists so the connection editor can remind the user which VRDE auth mode
 * the host VM expects (`VBoxManage modifyvm <vm> --vrdeauthtype ...`), but
 * nothing in the client actually branches on it yet, because VRDE's auth
 * method is negotiated server-side during the RDP handshake, not chosen by
 * the client. [effectiveAuthTypeHint] exists only so a future settings/help
 * screen has one canonical place to read a human-readable version of it
 * from, rather than re-deriving the mapping inline.
 */
fun RdpProfile.effectiveAuthTypeHint(): String {
    val authType = runCatching { VrdeAuthType.valueOf(vrdeAuthType) }.getOrDefault(VrdeAuthType.NULL_AUTH)
    return when (authType) {
        VrdeAuthType.NULL_AUTH -> "VRDE Auth: Null — VM accepts any (or no) credentials at the RDP layer; guest OS still shows its own login."
        VrdeAuthType.EXTERNAL -> "VRDE Auth: External — host validates credentials via a VRDEAuthLibrary module (e.g. AD/LDAP) before the session is allowed."
        VrdeAuthType.GUEST -> "VRDE Auth: Guest — credentials are passed through to the guest OS's own login screen."
    }
}
