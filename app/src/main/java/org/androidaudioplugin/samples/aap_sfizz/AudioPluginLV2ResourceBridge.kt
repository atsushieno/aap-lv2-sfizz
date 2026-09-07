package org.androidaudioplugin.samples.aap_sfizz

import android.content.res.AssetFileDescriptor

/** App-owned bridge between an AAP-hosted sfizz instance and SFZ resource providers. */
object AudioPluginLV2ResourceBridge {
    interface Provider {
        fun open(identity: String): Snapshot
    }

    class Snapshot(
        @JvmField val entry: String,
        @JvmField val names: Array<String>,
        @JvmField val descriptors: Array<AssetFileDescriptor>,
    ) : AutoCloseable {
        override fun close() {
            descriptors.forEach { descriptor ->
                try {
                    descriptor.close()
                } catch (_: Exception) {
                }
            }
        }
    }

    init {
        System.loadLibrary("aap-lv2-sfizz-resources")
    }

    external fun initialize(provider: Provider)
    external fun load(nativeService: Long, instanceId: Int, identity: String): Boolean
}
