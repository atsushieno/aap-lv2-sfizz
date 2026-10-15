package org.androidaudioplugin.samples.aap_sfizz

import android.content.Context
import org.androidaudioplugin.soundpack.SoundpackIdentity
import org.androidaudioplugin.soundpack.SoundpackKind
import org.androidaudioplugin.soundpack.client.SoundpackClient

/** Resolves the SFZ selections sfizz keeps in its state (`commons-soundpack://…` identities)
 * through the soundpack client: installed pack apps, folders linked in this app, and the
 * Soundpack Manager when this app is connected to it.
 *
 * The native bridge calls [open] on a loading thread, or on the host's main thread when a
 * state is restored. It is plain ContentProvider IPC, so neither waits on a callback. */
object SoundpackResources : AudioPluginLV2ResourceBridge.Provider {
    private lateinit var context: Context

    val client: SoundpackClient by lazy { SoundpackClient(context, SoundpackKind.SFZ) }

    fun initialize(context: Context) {
        this.context = context.applicationContext
        AudioPluginLV2ResourceBridge.initialize(this)
    }

    override fun open(identity: String): AudioPluginLV2ResourceBridge.Snapshot {
        // Selections saved before the soundpack contract (aap-sfz://…) are not translated: they
        // fail here, and sfizz keeps them as an unavailable instrument.
        val parsed = requireNotNull(SoundpackIdentity.parse(identity)) { "Not a soundpack identity: $identity" }
        val session = client.open(parsed)
        val names = session.files.map { it.path }
        require(session.entryPath in names) { "${session.entryPath} is not a file of ${session.pack.id}" }
        // Every file is opened when sfizz asks for it and mapped by the native side, so even a
        // pack as large as VPO3 costs only the files the instrument uses.
        return AudioPluginLV2ResourceBridge.Snapshot(session.entryPath, names.toTypedArray(), emptyArray(),
            opener = { path -> session.openFile(path) })
    }
}
