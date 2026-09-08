package org.androidaudioplugin.samples.aap_sfizz

import android.content.*
import android.os.*
import android.net.Uri
import android.content.res.AssetFileDescriptor
import org.androidaudioplugin.sfz.ISfzResourceService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

object SfzResourceClient : AudioPluginLV2ResourceBridge.Provider {
    private lateinit var context: Context
    const val ACTION = "org.androidaudioplugin.SfzResourceService.V1"
    data class Choice(val label: String, val identity: String)
    fun initialize(ctx: Context) {
        context = ctx.applicationContext
        AudioPluginLV2ResourceBridge.initialize(this)
    }
    private fun <T> connected(component: ComponentName, block: (ISfzResourceService) -> T): T {
        check(Looper.myLooper() != Looper.getMainLooper()) { "Resource IPC must run off the UI/audio thread" }
        val ready = CountDownLatch(1)
        var remote: ISfzResourceService? = null
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                remote = ISfzResourceService.Stub.asInterface(binder); ready.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName) { remote = null }
            override fun onNullBinding(name: ComponentName) { ready.countDown() }
            override fun onBindingDied(name: ComponentName) { ready.countDown() }
        }
        check(context.bindService(Intent(ACTION).setComponent(component), connection, Context.BIND_AUTO_CREATE)) {
            "Cannot bind SFZ provider"
        }
        try {
            check(ready.await(10, TimeUnit.SECONDS)) { "SFZ provider connection timed out" }
            val service = checkNotNull(remote) { "SFZ provider unavailable" }
            check(service.protocolVersion == 1) { "Unsupported SFZ protocol" }
            return block(service)
        } finally { context.unbindService(connection) }
    }
    fun discover(): List<Choice> {
        val result = mutableListOf<Choice>()
        for (match in context.packageManager.queryIntentServices(Intent(ACTION), 0)) {
            val component = ComponentName(match.serviceInfo.packageName, match.serviceInfo.name)
            connected(component) { service ->
                var offset = 0
                while (offset < 4096) {
                    val page = service.listInstruments(offset, 128)
                    require(page.size <= 128)
                    for (item in page) {
                        val id = requireNotNull(item.getString("id"))
                        val revision = requireNotNull(item.getString("revision"))
                        require(id.isNotEmpty() && revision.isNotEmpty())
                        val identity = Uri.Builder().scheme("aap-sfz").authority(component.packageName)
                            .appendPath(component.className).appendPath(id).appendQueryParameter("revision", revision).build().toString()
                        require(identity.length <= 4096)
                        result += Choice(item.getString("label") ?: id, identity)
                    }
                    offset += page.size
                    if (page.size < 128) break
                }
            }
        }
        return result + SfzFolders.discover(context)
    }
    override fun open(identity: String): AudioPluginLV2ResourceBridge.Snapshot {
        val uri = Uri.parse(identity)
        if (uri.scheme == "aap-sfz-folder") return SfzFolders.open(context, uri)
        require(identity.length <= 4096 && uri.scheme == "aap-sfz" && uri.pathSegments.size == 2 && uri.fragment == null)
        val component = ComponentName(requireNotNull(uri.host), uri.pathSegments[0])
        val revision = requireNotNull(uri.getQueryParameter("revision"))
        return connected(component) { service ->
            val session = service.openInstrument(uri.pathSegments[1], revision)
            val names = mutableListOf<String>()
            val descriptors = mutableListOf<AssetFileDescriptor>()
            try {
                val entry = session.entryPath
                while (true) {
                    val page = session.listResources(names.size, 128)
                    require(page.size <= 128 && names.size + page.size <= 65536) { "Pack exceeds 65536 resources" }
                    names.addAll(page)
                    if (page.size < 128) break
                }
                require(entry in names && names.toSet().size == names.size)
                names.forEach { descriptors += session.openResource(it) }
                AudioPluginLV2ResourceBridge.Snapshot(entry, names.toTypedArray(), descriptors.toTypedArray())
            } catch (e: Exception) {
                android.util.Log.e("AAP.SFZ", "Opening resource pack $identity failed", e)
                descriptors.forEach { try { it.close() } catch (_: Exception) {} }
                throw e
            } finally { try { session.close() } catch (_: Exception) {} }
        }
    }
}
