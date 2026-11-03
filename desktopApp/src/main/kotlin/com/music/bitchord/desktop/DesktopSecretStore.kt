package com.music.bitchord.desktop

import com.music.bitchord.desktop.secrets.SecretServiceApi
import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.types.Variant

/** The platform's own password store, for the credentials this app holds. */
internal object DesktopSecretStore {

    private fun macAccount(attributes: Map<String, String>): String = attributes.toSortedMap().entries
        .joinToString("&") { (key, value) -> "${key.length}:$key${value.length}:$value" }

    /** Plain-text transport. */
    private const val PLAIN = "plain"

    private const val DEFAULT_COLLECTION = "/org/freedesktop/secrets/aliases/default"

    /** Runs [block] with an open Secret Service session, or returns null. */
    private fun <T> withService(
        block: (DBusConnection, SecretServiceApi.Service, DBusPath) -> T?,
    ): T? = runCatching {
        DBusConnectionBuilder.forSessionBus().withShared(false).build().use { connection ->
            val service = connection.getRemoteObject(
                SecretServiceApi.BUS_NAME,
                SecretServiceApi.SERVICE_PATH,
                SecretServiceApi.Service::class.java,
            )
            val session = service.OpenSession(PLAIN, Variant("")).session
            block(connection, service, session)
        }
    }.getOrElse {
        DesktopTrackLog.log("secret store unavailable: ${it.message}")
        null
    }

    /** The first secret stored under [attributes], as bytes. */
    fun lookup(attributes: Map<String, String>): ByteArray? = if (DesktopPlatform.isMac) {
        runCatching { DesktopMacKeychain.read(macAccount(attributes))?.toByteArray(Charsets.UTF_8) }
            .onFailure { DesktopTrackLog.log("Login Keychain session unavailable; unlock the Keychain to reconnect") }.getOrNull()
    } else withService { _, service, session ->
        val found = service.SearchItems(attributes)
        val items = buildList {
            addAll(found.unlocked)
            if (found.locked.isNotEmpty()) {
                runCatching { service.Unlock(found.locked) }.getOrNull()?.let { addAll(it.unlocked) }
            }
        }
        if (items.isEmpty()) return@withService null
        service.GetSecrets(items, session).values.firstOrNull()?.value
    }

    /** Stores [secret] under [attributes], replacing whatever was there. */
    fun store(label: String, attributes: Map<String, String>, secret: ByteArray): Boolean =
        if (DesktopPlatform.isMac) {
            DesktopMacKeychain.write(macAccount(attributes), secret.toString(Charsets.UTF_8))
            true
        } else withService { connection, service, session ->
            val collection = runCatching { service.ReadAlias("default")?.path }
                .getOrNull()
                ?.takeIf { it.isNotBlank() && it != "/" }
                ?: DEFAULT_COLLECTION
            val properties = mapOf<String, Variant<*>>(
                "org.freedesktop.Secret.Item.Label" to Variant(label),
                "org.freedesktop.Secret.Item.Attributes" to Variant(attributes, "a{ss}"),
            )
            val item = connection.getRemoteObject(
                SecretServiceApi.BUS_NAME,
                collection,
                SecretServiceApi.Collection::class.java,
            )
            item.CreateItem(
                properties,
                SecretServiceApi.Secret(session, ByteArray(0), secret, "text/plain; charset=utf8"),
                true,
            )
            true
        } ?: false

    /** Forgets everything stored under [attributes]. */
    fun remove(attributes: Map<String, String>): Boolean = if (DesktopPlatform.isMac) {
        DesktopMacKeychain.remove(macAccount(attributes))
        true
    } else withService { connection, service, _ ->
        val found = service.SearchItems(attributes)
        (found.unlocked + found.locked).forEach { path ->
            runCatching {
                connection.getRemoteObject(
                    SecretServiceApi.BUS_NAME,
                    path.path,
                    SecretServiceApi.Item::class.java,
                ).Delete()
            }
        }
        true
    } ?: false

    /** Whether a store is actually reachable, for the settings screen to say so. */
    fun isAvailable(): Boolean = if (DesktopPlatform.isMac) runCatching { DesktopMacKeychain.read("availability-probe"); true }.getOrDefault(false) else withService { _, _, _ -> true } == true
}
