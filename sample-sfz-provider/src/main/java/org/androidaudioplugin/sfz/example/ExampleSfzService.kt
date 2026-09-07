package org.androidaudioplugin.sfz.example
import org.androidaudioplugin.sfz.AssetSfzResourceService
class ExampleSfzService : AssetSfzResourceService() {
    override val instruments = listOf(Instrument("sine", "External sine (includes + sample)", "1", "pack", "instruments/main.sfz"))
}
