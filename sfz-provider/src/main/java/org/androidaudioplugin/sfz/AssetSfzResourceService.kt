package org.androidaudioplugin.sfz

import android.app.Service
import android.content.Intent
import android.os.Bundle
import java.util.concurrent.atomic.AtomicBoolean

/** Public immutable APK instrument packs. APK assets must be uncompressed for openFd.
 * The supplied asset root is the complete sharing boundary (including shared samples).
 * Increment revision whenever any resource bytes or names change. */
abstract class AssetSfzResourceService : Service() {
    data class Instrument(val id: String, val label: String, val revision: String,
                          val assetRoot: String, val entry: String)
    protected abstract val instruments: List<Instrument>
    private val binder = object : ISfzResourceService.Stub() {
        override fun getProtocolVersion() = 1
        override fun listInstruments(offset: Int, limit: Int): Array<Bundle> {
            require(offset >= 0 && limit in 1..128)
            return instruments.drop(offset).take(limit).map { i -> Bundle().apply {
                putString("id", i.id); putString("label", i.label); putString("revision", i.revision)
            } }.toTypedArray()
        }
        override fun openInstrument(instrumentId: String, revision: String): ISfzResourceSession {
            val instrument = instruments.singleOrNull { it.id == instrumentId && it.revision == revision }
                ?: throw IllegalArgumentException("Instrument revision unavailable")
            val names = mutableListOf<String>()
            fun walk(path: String, depth: Int) {
                require(depth <= 32 && names.size < 65536)
                val full = "${instrument.assetRoot}/$path".trimEnd('/')
                val children = assets.list(full) ?: emptyArray()
                if (children.isEmpty()) {
                    require(path.isNotEmpty() && path.length <= 4096)
                    names += path
                } else children.sorted().forEach { walk(if (path.isEmpty()) it else "$path/$it", depth + 1) }
            }
            walk("", 0)
            require(instrument.entry in names)
            val allowed = names.toHashSet()
            val closed = AtomicBoolean(false)
            return object : ISfzResourceSession.Stub() {
                override fun getEntryPath(): String { check(!closed.get()); return instrument.entry }
                override fun listResources(offset: Int, limit: Int): Array<String> {
                    check(!closed.get()); require(offset >= 0 && limit in 1..128)
                    return names.drop(offset).take(limit).toTypedArray()
                }
                override fun openResource(name: String): android.content.res.AssetFileDescriptor {
                    check(!closed.get()); require(name in allowed)
                    return assets.openFd("${instrument.assetRoot}/$name")
                }
                override fun close() { closed.set(true) }
            }
        }
    }
    override fun onBind(intent: Intent?) = binder
}
