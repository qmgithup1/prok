package com.systemsgo.hex.snmp.protocol

/** SNMP protocol version, wire values per RFC 3416 §3 ("version-1" enum field, INTEGER). */
enum class SnmpVersion(val wireValue: Int, val label: String) {
    V1(0, "SNMPv1"),
    V2C(1, "SNMPv2c"),
    V3(3, "SNMPv3"),
}

/** USM auth protocol (RFC 3414 §6, RFC 7860 for the SHA-2 family). */
enum class SnmpAuthProtocol(val label: String, val digestBytes: Int, val hmacAlgo: String, val digestAlgo: String) {
    NONE("None", 0, "", ""),
    MD5("MD5", 12, "HmacMD5", "MD5"),
    SHA1("SHA-1", 12, "HmacSHA1", "SHA-1"),
    SHA224("SHA-224", 16, "HmacSHA224", "SHA-224"),
    SHA256("SHA-256", 24, "HmacSHA256", "SHA-256"),
    SHA384("SHA-384", 32, "HmacSHA384", "SHA-384"),
    SHA512("SHA-512", 48, "HmacSHA512", "SHA-512"),
}

/** USM privacy protocol (RFC 3414 §8 for DES, RFC 3826 for AES-128; AES-192/256 are the common Cisco/Net-SNMP "3DES-style key extension" vendor convention, not an IETF standard, but widely interoperable). */
enum class SnmpPrivProtocol(val label: String, val keyBytes: Int) {
    NONE("None", 0),
    DES("DES", 16),
    AES128("AES-128", 16),
    AES192("AES-192", 32),
    AES256("AES-256", 32),
}

enum class SnmpSecurityLevel(val label: String) {
    NO_AUTH_NO_PRIV("noAuthNoPriv"),
    AUTH_NO_PRIV("authNoPriv"),
    AUTH_PRIV("authPriv"),
}

/**
 * Everything [SnmpClient] needs to authenticate a session, for any of the
 * three SNMP versions. v1/v2c only differ in the version number they put on
 * the wire and in v2c's extra PDU types (GetBulk, Inform) — both otherwise
 * share plaintext-community semantics, hence one [Community] case for both.
 */
sealed class SnmpCredentials {
    abstract val version: SnmpVersion

    data class Community(
        override val version: SnmpVersion, // V1 or V2C
        val community: String,
    ) : SnmpCredentials()

    data class Usm(
        val username: String,
        val securityLevel: SnmpSecurityLevel,
        val authProtocol: SnmpAuthProtocol = SnmpAuthProtocol.NONE,
        val authPassphrase: String = "",
        val privProtocol: SnmpPrivProtocol = SnmpPrivProtocol.NONE,
        val privPassphrase: String = "",
        val contextName: String = "",
        /** Discovered lazily by [SnmpClient] on first request if left null — see RFC 3414 §4 engine discovery. */
        val contextEngineId: ByteArray? = null,
    ) : SnmpCredentials() {
        override val version get() = SnmpVersion.V3

        override fun equals(other: Any?) = other is Usm && username == other.username &&
            securityLevel == other.securityLevel && authProtocol == other.authProtocol &&
            authPassphrase == other.authPassphrase && privProtocol == other.privProtocol &&
            privPassphrase == other.privPassphrase && contextName == other.contextName
        override fun hashCode() = listOf(username, securityLevel, authProtocol, authPassphrase, privProtocol, privPassphrase, contextName).hashCode()
    }
}

/** A typed SNMP value — the SMI base types (RFC 2578 §7) plus the varbind exception values a GetNext/GetBulk/response can carry (RFC 3416 §2.2). */
sealed class SnmpValue {
    data class IntegerVal(val value: Long) : SnmpValue()
    data class OctetStringVal(val bytes: ByteArray) : SnmpValue() {
        /** Best-effort text view — most OCTET STRING objects (sysDescr, sysName, ifDescr, …) are printable; binary ones (e.g. physAddress) should read [bytes] and hex-format themselves. */
        fun asText(): String = String(bytes, Charsets.UTF_8)
        fun asHex(): String = bytes.joinToString(" ") { "%02x".format(it) }
        override fun equals(other: Any?) = other is OctetStringVal && bytes.contentEquals(other.bytes)
        override fun hashCode() = bytes.contentHashCode()
    }
    object NullVal : SnmpValue()
    data class ObjectIdVal(val oid: Oid) : SnmpValue()
    data class IpAddressVal(val bytes: ByteArray) : SnmpValue() {
        fun asText(): String = bytes.joinToString(".") { (it.toInt() and 0xFF).toString() }
        override fun equals(other: Any?) = other is IpAddressVal && bytes.contentEquals(other.bytes)
        override fun hashCode() = bytes.contentHashCode()
    }
    data class Counter32Val(val value: Long) : SnmpValue()
    data class Gauge32Val(val value: Long) : SnmpValue()
    data class TimeTicksVal(val centiseconds: Long) : SnmpValue() {
        /** e.g. "3 days, 04:12:07.42" — the conventional sysUpTime rendering. */
        fun formatted(): String {
            val totalCs = centiseconds
            val cs = totalCs % 100
            val totalSec = totalCs / 100
            val s = totalSec % 60
            val totalMin = totalSec / 60
            val m = totalMin % 60
            val totalHour = totalMin / 60
            val h = totalHour % 24
            val d = totalHour / 24
            val hms = "%02d:%02d:%02d.%02d".format(h, m, s, cs)
            return if (d > 0) "$d day${if (d == 1L) "" else "s"}, $hms" else hms
        }
    }
    data class OpaqueVal(val bytes: ByteArray) : SnmpValue() {
        override fun equals(other: Any?) = other is OpaqueVal && bytes.contentEquals(other.bytes)
        override fun hashCode() = bytes.contentHashCode()
    }
    data class Counter64Val(val bits: Long) : SnmpValue() {
        fun asDecimalString(): String = bits.toUnsignedDecimalString()
    }
    object NoSuchObject : SnmpValue()
    object NoSuchInstance : SnmpValue()
    object EndOfMibView : SnmpValue()

    /** True for the three RFC 3416 §2.2 exception pseudo-values — a walk should stop, not display these as data. */
    val isException: Boolean get() = this === NoSuchObject || this === NoSuchInstance || this === EndOfMibView

    fun toBer(writer: BerWriter) {
        when (this) {
            is IntegerVal -> writer.writeTlv(Ber.INTEGER, berEncodeSignedInt(value))
            is OctetStringVal -> writer.writeTlv(Ber.OCTET_STRING, bytes)
            is NullVal -> writer.writeTlv(Ber.NULL, ByteArray(0))
            is ObjectIdVal -> writer.writeTlv(Ber.OBJECT_IDENTIFIER, berEncodeOid(oid))
            is IpAddressVal -> writer.writeTlv(Ber.IP_ADDRESS, bytes)
            is Counter32Val -> writer.writeTlv(Ber.COUNTER32, berEncodeUnsigned(value, 4))
            is Gauge32Val -> writer.writeTlv(Ber.GAUGE32, berEncodeUnsigned(value, 4))
            is TimeTicksVal -> writer.writeTlv(Ber.TIME_TICKS, berEncodeUnsigned(centiseconds, 4))
            is OpaqueVal -> writer.writeTlv(Ber.OPAQUE, bytes)
            is Counter64Val -> writer.writeTlv(Ber.COUNTER64, berEncodeUnsigned(bits, 8))
            NoSuchObject -> writer.writeTlv(Ber.NO_SUCH_OBJECT, ByteArray(0))
            NoSuchInstance -> writer.writeTlv(Ber.NO_SUCH_INSTANCE, ByteArray(0))
            EndOfMibView -> writer.writeTlv(Ber.END_OF_MIB_VIEW, ByteArray(0))
        }
    }

    companion object {
        fun fromBer(node: BerNode): SnmpValue = when (node.tag) {
            Ber.INTEGER -> IntegerVal(berDecodeSignedInt(node.content))
            Ber.OCTET_STRING -> OctetStringVal(node.content)
            Ber.NULL -> NullVal
            Ber.OBJECT_IDENTIFIER -> ObjectIdVal(berDecodeOid(node.content))
            Ber.IP_ADDRESS -> IpAddressVal(node.content)
            Ber.COUNTER32 -> Counter32Val(berDecodeUnsigned(node.content))
            Ber.GAUGE32 -> Gauge32Val(berDecodeUnsigned(node.content))
            Ber.TIME_TICKS -> TimeTicksVal(berDecodeUnsigned(node.content))
            Ber.OPAQUE -> OpaqueVal(node.content)
            Ber.COUNTER64 -> Counter64Val(berDecodeUnsigned(node.content))
            Ber.NO_SUCH_OBJECT -> NoSuchObject
            Ber.NO_SUCH_INSTANCE -> NoSuchInstance
            Ber.END_OF_MIB_VIEW -> EndOfMibView
            else -> throw SnmpException("Unknown SNMP value tag 0x${node.tag.toString(16)}")
        }
    }
}

data class VarBind(val oid: Oid, val value: SnmpValue) {
    companion object {
        /** A "please fill in" varbind for outgoing GET/GetNext/GetBulk requests, per RFC 3416 §3 (value is always NULL on the way out). */
        fun request(oid: Oid) = VarBind(oid, SnmpValue.NullVal)
    }
}

/** error-status codes (RFC 3416 §3, Table 1 for the v2c/v3-specific ones beyond the original 6). */
enum class SnmpErrorStatus(val code: Int, val label: String) {
    NO_ERROR(0, "noError"),
    TOO_BIG(1, "tooBig"),
    NO_SUCH_NAME(2, "noSuchName"),
    BAD_VALUE(3, "badValue"),
    READ_ONLY(4, "readOnly"),
    GEN_ERR(5, "genErr"),
    NO_ACCESS(6, "noAccess"),
    WRONG_TYPE(7, "wrongType"),
    WRONG_LENGTH(8, "wrongLength"),
    WRONG_ENCODING(9, "wrongEncoding"),
    WRONG_VALUE(10, "wrongValue"),
    NO_CREATION(11, "noCreation"),
    INCONSISTENT_VALUE(12, "inconsistentValue"),
    RESOURCE_UNAVAILABLE(13, "resourceUnavailable"),
    COMMIT_FAILED(14, "commitFailed"),
    UNDO_FAILED(15, "undoFailed"),
    AUTHORIZATION_ERROR(16, "authorizationError"),
    NOT_WRITABLE(17, "notWritable"),
    INCONSISTENT_NAME(18, "inconsistentName");

    companion object {
        fun fromCode(code: Int): SnmpErrorStatus = entries.find { it.code == code } ?: GEN_ERR
    }
}

/** A fully-decoded response PDU. */
data class SnmpResponse(
    val requestId: Int,
    val errorStatus: SnmpErrorStatus,
    val errorIndex: Int,
    val varBinds: List<VarBind>,
)

class SnmpTimeoutException(host: String, port: Int) : SnmpException("No response from $host:$port (timed out)")
class SnmpAuthenticationException(message: String) : SnmpException(message)
class SnmpErrorStatusException(val status: SnmpErrorStatus, val failingOid: Oid?) :
    SnmpException("Agent returned ${status.label}" + (failingOid?.let { " on $it" } ?: ""))
