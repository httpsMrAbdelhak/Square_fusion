// Ported from BitChord (GPL-3.0), data/sources/SourceKind.kt and
// SourceRegistry.kt, reduced to the addon and JioSaavn kinds. The MODULE kind
// (third-party JavaScript run in QuickJS) is deliberately not ported.
package dev.lelonio.square.fusion.sources

import dev.lelonio.square.fusion.sources.addon.AddonSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.util.UUID

enum class SourceKind {
    /** An addon server: plain HTTP + JSON, nothing executed here. */
    ADDON,

    /** JioSaavn through its unofficial web API. Off unless added by the user. */
    JIOSAAVN,
}

/**
 * One configured source, as stored. [allowDownloads] and [requiresLosslessOutput]
 * are copied from the addon's manifest each time it is read, so the policy is
 * known even while the addon is unreachable.
 */
@Serializable
data class SourceConfig(
    val id: String,
    val kind: SourceKind,
    val baseUrl: String = "",
    val label: String = "",
    val enabled: Boolean = true,
    val allowDownloads: Boolean = true,
    val requiresLosslessOutput: Boolean = false,
) {
    val displayName: String
        get() = label.ifBlank {
            when (kind) {
                SourceKind.JIOSAAVN -> "JioSaavn"
                SourceKind.ADDON -> baseUrl.substringAfter("://").substringBefore('/').ifBlank { "Addon" }
            }
        }
}

/** Where the list of sources is kept. SharedPreferences in the app, memory in tests. */
interface SourceStorage {
    fun read(): String?
    fun write(value: String)
}

class MemorySourceStorage(private var value: String? = null) : SourceStorage {
    override fun read() = value
    override fun write(value: String) { this.value = value }
}

/**
 * The configured sources, in the order they are asked.
 *
 * Order is the user's: the resolver walks the enabled ones top to bottom and
 * takes the first that answers. An empty registry, which is the default, means
 * every track plays from YouTube exactly as in Square.
 */
class SourceRegistry(private val storage: SourceStorage) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val serializer = ListSerializer(SourceConfig.serializer())

    private val _configs = MutableStateFlow(load())
    val configs: StateFlow<List<SourceConfig>> = _configs.asStateFlow()

    private val built = mutableMapOf<String, Pair<SourceConfig, MusicSource>>()

    private fun load(): List<SourceConfig> = storage.read()
        ?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() }
        .orEmpty()

    private fun save(next: List<SourceConfig>) {
        _configs.value = next
        storage.write(json.encodeToString(serializer, next))
    }

    fun config(id: String): SourceConfig? = _configs.value.firstOrNull { it.id == id }

    @Synchronized
    fun add(kind: SourceKind, baseUrl: String = "", label: String = ""): SourceConfig {
        val config = SourceConfig(
            id = UUID.randomUUID().toString(),
            kind = kind,
            baseUrl = baseUrl.trim(),
            label = label.trim(),
        )
        save(_configs.value + config)
        return config
    }

    @Synchronized
    fun remove(id: String) {
        built.remove(id)?.second?.let { (it as? AddonSource)?.release() }
        save(_configs.value.filterNot { it.id == id })
    }

    @Synchronized
    fun setEnabled(id: String, enabled: Boolean) =
        save(_configs.value.map { if (it.id == id) it.copy(enabled = enabled) else it })

    /** Moves the source at [id] by [delta] places, clamped to the list. */
    @Synchronized
    fun move(id: String, delta: Int) {
        val list = _configs.value.toMutableList()
        val from = list.indexOfFirst { it.id == id }
        if (from < 0) return
        val to = (from + delta).coerceIn(0, list.lastIndex)
        if (to == from) return
        list.add(to, list.removeAt(from))
        save(list)
    }

    @Synchronized
    fun updatePolicy(id: String, allowDownloads: Boolean, requiresLosslessOutput: Boolean) {
        val current = config(id) ?: return
        if (current.allowDownloads == allowDownloads && current.requiresLosslessOutput == requiresLosslessOutput) return
        save(
            _configs.value.map {
                if (it.id == id) it.copy(allowDownloads = allowDownloads, requiresLosslessOutput = requiresLosslessOutput) else it
            },
        )
    }

    /** The enabled sources, in order, built once and reused while their config is unchanged. */
    @Synchronized
    fun enabledSources(factory: (SourceConfig) -> MusicSource = ::defaultFactory): List<MusicSource> =
        _configs.value.filter { it.enabled }.map { sourceFor(it, factory) }

    @Synchronized
    fun sourceFor(config: SourceConfig, factory: (SourceConfig) -> MusicSource = ::defaultFactory): MusicSource {
        val held = built[config.id]
        // Policy fields change under the source's feet without making it a
        // different source; only the address or the kind does.
        if (held != null && held.first.baseUrl == config.baseUrl && held.first.kind == config.kind) return held.second
        held?.second?.let { (it as? AddonSource)?.release() }
        val source = factory(config)
        built[config.id] = config to source
        return source
    }

    fun hasEnabledSources(): Boolean = _configs.value.any { it.enabled }

    private fun defaultFactory(config: SourceConfig): MusicSource = when (config.kind) {
        SourceKind.ADDON -> AddonSource(config, this)
        SourceKind.JIOSAAVN -> dev.lelonio.square.fusion.jiosaavn.JioSaavnSource(config)
    }
}
