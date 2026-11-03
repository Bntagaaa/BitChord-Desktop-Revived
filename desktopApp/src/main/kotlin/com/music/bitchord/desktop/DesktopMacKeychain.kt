package com.music.bitchord.desktop

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference

/** Login Keychain generic passwords. Secrets never enter command arguments or logs. */
internal object DesktopMacKeychain {
    private val preferenceKeys = setOf("spotify_spdc_token", "discord_token", "lastfm_session_key",
        "listenbrainz_token", "listen_together_token")
    fun ownsPreference(key: String): Boolean = key in preferenceKeys

    private interface Security : Library {
        fun SecKeychainFindGenericPassword(keychain: Pointer?, serviceLength: Int, service: ByteArray,
            accountLength: Int, account: ByteArray, length: IntByReference?, data: PointerByReference?, item: PointerByReference?): Int
        fun SecKeychainAddGenericPassword(keychain: Pointer?, serviceLength: Int, service: ByteArray,
            accountLength: Int, account: ByteArray, length: Int, data: ByteArray, item: PointerByReference?): Int
        fun SecKeychainItemModifyAttributesAndData(item: Pointer, attributes: Pointer?, length: Int, data: ByteArray): Int
        fun SecKeychainItemDelete(item: Pointer): Int
        fun SecKeychainItemFreeContent(attributes: Pointer?, data: Pointer): Int
    }
    private interface CoreFoundation : Library { fun CFRelease(value: Pointer) }
    private val security by lazy { Native.load("/System/Library/Frameworks/Security.framework/Security", Security::class.java) }
    private val cf by lazy { Native.load("/System/Library/Frameworks/CoreFoundation.framework/CoreFoundation", CoreFoundation::class.java) }
    private val service = "com.music.bitchord.desktop".toByteArray(Charsets.UTF_8)

    @Synchronized fun read(key: String): String? {
        check(DesktopPlatform.isMac)
        val account = key.toByteArray(Charsets.UTF_8)
        val length = IntByReference()
        val data = PointerByReference()
        val status = security.SecKeychainFindGenericPassword(null, service.size, service, account.size, account, length, data, null)
        if (status == -25300) return null // errSecItemNotFound; all other errors must preserve existing data.
        check(status == 0) { "Login Keychain read failed (status $status). Unlock your login Keychain." }
        return try { data.value.getByteArray(0, length.value).toString(Charsets.UTF_8) }
        finally { security.SecKeychainItemFreeContent(null, data.value) }
    }

    @Synchronized fun remove(key: String) {
        val account = key.toByteArray(Charsets.UTF_8)
        val item = PointerByReference()
        val status = security.SecKeychainFindGenericPassword(null, service.size, service, account.size, account, null, null, item)
        if (status == -25300) return
        check(status == 0) { "Login Keychain lookup failed (status $status)" }
        try { check(security.SecKeychainItemDelete(item.value) == 0) }
        finally { cf.CFRelease(item.value) }
    }

    @Synchronized fun write(key: String, value: String) {
        check(DesktopPlatform.isMac)
        val account = key.toByteArray(Charsets.UTF_8)
        val bytes = value.toByteArray(Charsets.UTF_8)
        val status = security.SecKeychainAddGenericPassword(null, service.size, service, account.size, account, bytes.size, bytes, null)
        if (status != -25299) { // errSecDuplicateItem
            check(status == 0) { "Login Keychain save failed (status $status). Credentials were not saved." }
            return
        }
        val item = PointerByReference()
        check(security.SecKeychainFindGenericPassword(null, service.size, service, account.size, account, null, null, item) == 0)
        try {
            check(security.SecKeychainItemModifyAttributesAndData(item.value, null, bytes.size, bytes) == 0) { "Login Keychain update failed" }
        } finally { cf.CFRelease(item.value) }
    }
}
