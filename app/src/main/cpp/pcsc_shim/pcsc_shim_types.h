/* SMARTCARD-REDIRECT FEATURE
 *
 * Self-contained re-declaration of the subset of the PC/SC Workgroup's
 * WinSCard API (as implemented by PCSC-lite's winscard.h/pcsclite.h on
 * Linux, and the vendor-neutral PC/SC spec these are both based on) that
 * pcsc_shim.c needs to export. This is a public, vendor-neutral ABI/API
 * standard — the same one every PC/SC client library on every OS
 * implements — not anything specific to PCSC-lite's source; re-declaring it
 * here just means this shim doesn't need PCSC-lite's actual headers
 * available at compile time (this file IS effectively those headers'
 * public surface, trimmed to what this shim uses).
 *
 * Values below (constants, struct layouts) are fixed by the PC/SC spec and
 * mirrored verbatim by every conforming implementation — pcsc-lite.
 * apdu.fr's headers, Windows' winscard.h, macOS's PCSC framework, etc. all
 * agree on these, so this header only needs to match the *spec*, not any
 * particular vendor's header file.
 */
#ifndef SYSTEMSGO_PCSC_SHIM_TYPES_H
#define SYSTEMSGO_PCSC_SHIM_TYPES_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef uint32_t DWORD;
typedef int32_t  LONG;
typedef uint8_t  BYTE;
typedef uint16_t WORD;
typedef DWORD*   LPDWORD;
typedef BYTE*    LPBYTE;
typedef const BYTE* LPCBYTE;
typedef char*    LPSTR;
typedef const char* LPCSTR;
typedef void*    LPVOID;
typedef const void* LPCVOID;
typedef unsigned long ULONG_PTR;
typedef ULONG_PTR SCARDCONTEXT;
typedef ULONG_PTR SCARDHANDLE;
typedef SCARDCONTEXT* LPSCARDCONTEXT;
typedef SCARDHANDLE*  LPSCARDHANDLE;

/* PC/SC spec constant — MAX_ATR_SIZE is fixed at 33 bytes by every
 * conforming implementation (ISO/IEC 7816-3 ATR is at most 33 bytes). */
#define SYSTEMSGO_MAX_ATR_SIZE 33
#define SYSTEMSGO_MAX_READERNAME 128
#define SYSTEMSGO_MAX_BUFFER_SIZE 264

/* SCARD_S_SUCCESS / error codes — fixed 32-bit values per the PC/SC spec
 * (same numeric values across pcsc-lite, Windows, and every other
 * implementation, since callers like FreeRDP's WinPR check them by value,
 * not by re-including a particular vendor header). Only the subset this
 * shim actually returns is declared. */
#define SCARD_S_SUCCESS                 ((LONG)0x00000000)
#define SCARD_E_INVALID_HANDLE          ((LONG)0x80100003)
#define SCARD_E_INVALID_PARAMETER       ((LONG)0x80100004)
#define SCARD_E_NO_MEMORY               ((LONG)0x80100006)
#define SCARD_E_INSUFFICIENT_BUFFER     ((LONG)0x80100008)
#define SCARD_E_UNKNOWN_READER          ((LONG)0x80100009)
#define SCARD_E_TIMEOUT                 ((LONG)0x8010000A)
#define SCARD_E_NO_SMARTCARD            ((LONG)0x8010000C)
#define SCARD_E_NOT_TRANSACTED          ((LONG)0x80100016)
#define SCARD_E_READER_UNAVAILABLE      ((LONG)0x80100017)
#define SCARD_E_NO_READERS_AVAILABLE    ((LONG)0x8010002E)
#define SCARD_E_UNSUPPORTED_FEATURE     ((LONG)0x8010001F)
#define SCARD_W_REMOVED_CARD            ((LONG)0x80100069)
#define SCARD_W_UNRESPONSIVE_CARD       ((LONG)0x80100066)
#define SCARD_W_UNPOWERED_CARD          ((LONG)0x80100067)

#define SCARD_SCOPE_USER     0
#define SCARD_SCOPE_TERMINAL 1
#define SCARD_SCOPE_SYSTEM   2

#define SCARD_SHARE_EXCLUSIVE 1
#define SCARD_SHARE_SHARED    2
#define SCARD_SHARE_DIRECT    3

#define SCARD_LEAVE_CARD   0
#define SCARD_RESET_CARD   1
#define SCARD_UNPOWER_CARD 2
#define SCARD_EJECT_CARD   3

#define SCARD_PROTOCOL_UNDEFINED 0x00000000
#define SCARD_PROTOCOL_T0        0x00000001
#define SCARD_PROTOCOL_T1        0x00000002
#define SCARD_PROTOCOL_RAW       0x00010000

#define SCARD_UNKNOWN    0
#define SCARD_ABSENT     1
#define SCARD_PRESENT    2
#define SCARD_SWALLOWED  3
#define SCARD_POWERED    4
#define SCARD_NEGOTIABLE 5
#define SCARD_SPECIFIC   6

#define SCARD_STATE_UNAWARE     0x00000000
#define SCARD_STATE_IGNORE      0x00000001
#define SCARD_STATE_CHANGED     0x00000002
#define SCARD_STATE_UNKNOWN     0x00000004
#define SCARD_STATE_UNAVAILABLE 0x00000008
#define SCARD_STATE_EMPTY       0x00000010
#define SCARD_STATE_PRESENT     0x00000020

/* pcsc-lite's autoallocate extension: pass this as *pcchReaders/*pcbAtrLen
 * and the library mallocs the buffer itself, handing back a pointer that
 * must later go through SCardFreeMemory. WinPR/FreeRDP's smartcard client
 * commonly uses this for SCardListReaders. */
#define SYSTEMSGO_SCARD_AUTOALLOCATE ((DWORD)0xFFFFFFFF)

typedef struct {
    DWORD dwProtocol;
    DWORD cbPciLength;
} SCARD_IO_REQUEST;

/* Field order/sizes fixed by the PC/SC spec — every implementation's
 * SCARD_READERSTATE_A agrees on this layout. */
typedef struct {
    const char* szReader;
    void*       pvUserData;
    DWORD       dwCurrentState;
    DWORD       dwEventState;
    DWORD       cbAtr;
    unsigned char rgbAtr[SYSTEMSGO_MAX_ATR_SIZE];
} SCARD_READERSTATE_A;

#ifdef __cplusplus
}
#endif

#endif /* SYSTEMSGO_PCSC_SHIM_TYPES_H */
