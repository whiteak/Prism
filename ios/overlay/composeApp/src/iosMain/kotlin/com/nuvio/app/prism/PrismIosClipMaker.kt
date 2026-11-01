@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package com.nuvio.app.prism

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import platform.AVFoundation.*
import platform.CoreMedia.CMTimeMake
import platform.CoreMedia.CMTimeRangeMake
import platform.Foundation.NSError
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileSize
import platform.Foundation.NSNumber
import platform.Foundation.NSURL
import platform.Foundation.setValue
import platform.UIKit.UINavigationControllerDelegateProtocol
import platform.UIKit.UIVideoEditorController
import platform.UIKit.UIVideoEditorControllerDelegateProtocol
import platform.darwin.NSObject
import kotlin.coroutines.resume

// Prism's clip maker on the iPhone: pick a video (Photos or Files), trim it in Apple's own video trimmer, then save
// the part as a silent clip (H.265 where the phone can, else H.264) or just its sound (.m4a).
internal object PrismIosClipMaker {
    // The trimmer only holds its delegate weakly.
    private var delegate: NSObject? = null

    suspend fun make(sound: Boolean): PrismMadeClip? {
        val picked = PrismIos.pickRefs(PrismFileKind.Video, multiple = false).firstOrNull() ?: return null
        try {
            val trimmed = trim(picked.ref) ?: return null
            try {
                val base = picked.name.substringAfter('-', picked.name).substringBeforeLast('.').ifBlank { "clip" }
                val name = if (sound) "$base sound.m4a" else "$base clip.mp4"
                val out = "${PrismIos.pickedDirPath}/${PrismStore.newId()}-$name"
                val ok = withContext(Dispatchers.Default) { export(trimmed, out, sound) }
                if (!ok) return null
                return PrismMadeClip(PrismFolderFile(name, sizeOf(out), out), originalDeleted = null)
            } finally {
                PrismIos.delete(trimmed)
            }
        } finally {
            PrismIos.delete(picked.ref)
        }
    }

    private fun sizeOf(path: String): Long =
        (NSFileManager.defaultManager.attributesOfItemAtPath(path, error = null)?.get(NSFileSize) as? NSNumber)?.longLongValue ?: 0L

    /** Apple's trimmer on [path]; the trimmed copy's path, or null if cancelled. */
    private suspend fun trim(path: String): String? = withContext(Dispatchers.Main) {
        if (!UIVideoEditorController.canEditVideoAtPath(path)) return@withContext path.let { copy ->
            // Can't trim this one: use the whole video.
            val to = "${PrismIos.pickedDirPath}/${PrismStore.newId()}-whole.${path.substringAfterLast('.', "mov")}"
            if (NSFileManager.defaultManager.copyItemAtPath(copy, toPath = to, error = null)) to else null
        }
        suspendCancellableCoroutine { cont ->
            val editor = UIVideoEditorController()
            editor.videoPath = path
            editor.videoMaximumDuration = 3600.0
            // UIImagePickerControllerQualityTypeHigh (0); the default is medium.
            editor.setValue(NSNumber.numberWithInt(0), forKey = "videoQuality")
            val d = object : NSObject(), UIVideoEditorControllerDelegateProtocol, UINavigationControllerDelegateProtocol {
                fun finish(result: String?) {
                    delegate = null
                    editor.dismissViewControllerAnimated(true, completion = null)
                    if (cont.isActive) cont.resume(result)
                }

                // iOS sometimes calls this twice; only the first counts.
                override fun videoEditorController(editor: UIVideoEditorController, didSaveEditedVideoToPath: String) {
                    if (!cont.isActive) return
                    val to = "${PrismIos.pickedDirPath}/${PrismStore.newId()}-trimmed.${didSaveEditedVideoToPath.substringAfterLast('.', "mov")}"
                    finish(if (NSFileManager.defaultManager.moveItemAtPath(didSaveEditedVideoToPath, toPath = to, error = null)) to else didSaveEditedVideoToPath)
                }

                override fun videoEditorController(editor: UIVideoEditorController, didFailWithError: NSError) = finish(null)

                override fun videoEditorControllerDidCancel(editor: UIVideoEditorController) = finish(null)
            }
            delegate = d
            editor.delegate = d
            if (!PrismIos.presentController(editor)) {
                delegate = null
                if (cont.isActive) cont.resume(null)
            }
        }
    }

    private suspend fun export(from: String, to: String, sound: Boolean): Boolean {
        val asset = AVURLAsset(uRL = NSURL.fileURLWithPath(from), options = null)
        if (sound) return exportWith(asset, "AVAssetExportPresetAppleM4A", "com.apple.m4a-audio", to)
        // Video only: the sound is left out.
        val track = asset.tracksWithMediaType("vide").firstOrNull() as? AVAssetTrack ?: return false
        val composition = AVMutableComposition()
        val videoTrack = composition.addMutableTrackWithMediaType("vide", preferredTrackID = 0) ?: return false
        if (!videoTrack.insertTimeRange(CMTimeRangeMake(CMTimeMake(0, 1), asset.duration), ofTrack = track, atTime = CMTimeMake(0, 1), error = null)) return false
        videoTrack.preferredTransform = track.preferredTransform
        return exportWith(composition, "AVAssetExportPresetHEVCHighestQuality", "public.mpeg-4", to) ||
            exportWith(composition, "AVAssetExportPresetHighestQuality", "public.mpeg-4", to)
    }

    private suspend fun exportWith(asset: AVAsset, preset: String, type: String, to: String): Boolean {
        PrismIos.delete(to)
        val session = AVAssetExportSession.exportSessionWithAsset(asset, presetName = preset) ?: return false
        if (type !in session.supportedFileTypes.filterIsInstance<String>()) return false
        session.outputURL = NSURL.fileURLWithPath(to)
        session.outputFileType = type
        session.shouldOptimizeForNetworkUse = true
        return suspendCancellableCoroutine { cont ->
            session.exportAsynchronouslyWithCompletionHandler {
                if (cont.isActive) cont.resume(session.status == AVAssetExportSessionStatusCompleted)
            }
        }
    }
}
