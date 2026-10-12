/*
 * SCAFFOLD. Modeled on app/morphe/patches/shared/misc/spoof/SpoofVideoStreamsPatch.kt.
 * Not compiled or tested. Items marked VERIFY depend on the exact YTM build and Morphe's
 * current patcher API; check them against the repo and your decompiled APK.
 */
package app.morphe.patches.music.misc.localaudio

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
// These two fingerprints live in the same package as the spoof patch (the spoof file uses them
// without importing). VERIFY they are not `private` and that they match your target version.
import app.morphe.patches.shared.misc.spoof.BuildMediaDataSourceFingerprint
import app.morphe.patches.shared.misc.spoof.CreateStreamingDataFingerprint
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/music/localaudio/LocalAudioPatch;"

/**
 * Name of the String field in the "video details" class that holds the video ID.
 * The spoof patch hardcodes `c` for the builds it targets. VERIFY on 9.20.53 in jadx.
 */
private const val VIDEO_ID_FIELD = "c"

@Suppress("unused")
val localAudioPatch = bytecodePatch(
    name = "Local audio playback",
    description = "Plays a local audio file named '... [VIDEOID].m4a/.webm' instead of " +
        "streaming when one exists in the app's external 'local' directory."
) {
    // VERIFY: use whatever Morphe's current compatibility declaration looks like. Compare with
    // another music patch in the repo (e.g. the Downloads patch).
    compatibleWith("com.google.android.apps.youtube.music")

    // VERIFY / TODO: add the shared extension patch your other music patches depend on so the
    // extension module gets bundled, and make sure SABR is disabled (see README). Example:
    // dependsOn(sharedExtensionPatch, localAudioResourcePatch)
    dependsOn(localAudioResourcePatch)

    execute {
        // region Hook A: record the current video ID when the player response is parsed.

        CreateStreamingDataFingerprint.let {
            it.method.apply {
                val videoDetailsMatch = it.instructionMatches[5]
                val videoDetailsIndex = videoDetailsMatch.index
                val videoDetailsRegister =
                    getInstruction<TwoRegisterInstruction>(videoDetailsIndex).registerA
                val videoDetailsClass =
                    videoDetailsMatch.instruction.getReference<FieldReference>()!!.type

                // Helper method avoids register pressure in the (large) original method.
                val helperMethod = ImmutableMethod(
                    it.classDef.type,
                    "patch_localAudioSetVideoId",
                    listOf(ImmutableMethodParameter(videoDetailsClass, null, null)),
                    "V",
                    AccessFlags.PRIVATE.value or AccessFlags.FINAL.value,
                    null,
                    null,
                    MutableMethodImplementation(3), // v0, p0, p1
                ).toMutable().apply {
                    addInstructionsWithLabels(
                        0,
                        """
                            iget-object v0, p1, $videoDetailsClass->$VIDEO_ID_FIELD:Ljava/lang/String;
                            if-eqz v0, :skip
                            invoke-static { v0 }, $EXTENSION_CLASS->setCurrentVideoId(Ljava/lang/String;)V
                            :skip
                            return-void
                        """
                    )
                }

                it.classDef.methods.add(helperMethod)
                addInstruction(
                    videoDetailsIndex + 1,
                    "invoke-direct { p0, v$videoDetailsRegister }, $helperMethod"
                )
            }
        }

        // endregion

        // region Hook B: swap the stream Uri for the loopback URL when a local file applies.

        BuildMediaDataSourceFingerprint.let {
            // Same indices the spoof patch uses: match 0 reads the Uri field.
            val urlField = it.instructionMatches[0].getFieldAccessed()
            val index = it.instructionMatches.last().index

            it.method.addInstructions(
                index,
                """
                    move-object v0, p0 # method has >15 registers: copy p0 to a low register.
                    iget-object v1, v0, $urlField
                    invoke-static { v1 }, $EXTENSION_CLASS->redirectToLocal(Landroid/net/Uri;)Landroid/net/Uri;
                    move-result-object v1
                    iput-object v1, v0, $urlField
                """
            )
        }

        // endregion
    }
}
