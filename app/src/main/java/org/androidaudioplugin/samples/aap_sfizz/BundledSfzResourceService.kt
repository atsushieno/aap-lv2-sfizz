package org.androidaudioplugin.samples.aap_sfizz

import org.androidaudioplugin.sfz.AssetSfzResourceService

/** Publishes the piano shipped with AAP Sfizz through the same contract as add-on packs. */
class BundledSfzResourceService : AssetSfzResourceService() {
    override val instruments = listOf(
        Instrument(
            id = "splendid-grand-piano",
            label = "Splendid Grand Piano (bundled)",
            revision = "1",
            assetRoot = "lv2/sfizz.lv2/Contents/Resources/SplendidGrandPiano",
            entry = "Splendid Grand Piano.sfz",
        )
    )
}
