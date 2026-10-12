/*
 * SCAFFOLD, not compiled. Allows cleartext HTTP to 127.0.0.1 so the player can fetch from the
 * loopback server. API names below follow ReVanced/Morphe conventions seen in the spoof patch
 * (copyResources / ResourceGroup / resourcePatch) plus a ReVanced-style `document(...)` call
 * for the manifest. VERIFY all of it against the repo.
 *
 * IMPORTANT: if YTM's manifest already declares android:networkSecurityConfig, do NOT overwrite
 * it. Instead merge this into the existing XML file:
 *
 *   <domain-config cleartextTrafficPermitted="true">
 *       <domain includeSubdomains="false">127.0.0.1</domain>
 *   </domain-config>
 */
package app.morphe.patches.music.misc.localaudio

import app.morphe.patcher.patch.resourcePatch
import app.morphe.util.ResourceGroup
import app.morphe.util.copyResources
import org.w3c.dom.Element

internal val localAudioResourcePatch = resourcePatch {
    execute {
        // Copies patches/src/main/resources/localaudio/xml/<file> to res/xml/<file>.
        copyResources(
            "localaudio",
            ResourceGroup("xml", "morphe_local_audio_network_security_config.xml")
        )

        // VERIFY: manifest editing API in Morphe's resourcePatch.
        document("AndroidManifest.xml").use { document ->
            val application = document.getElementsByTagName("application").item(0) as Element
            application.setAttribute(
                "android:networkSecurityConfig",
                "@xml/morphe_local_audio_network_security_config"
            )
        }
    }
}
