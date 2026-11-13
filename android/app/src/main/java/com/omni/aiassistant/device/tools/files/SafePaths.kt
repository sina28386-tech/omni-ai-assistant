package com.omni.aiassistant.device.tools.files

import android.content.Context
import android.os.Environment
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.IOException

/** Thrown for every path that fails the storage sandbox rules. */
internal class UnsafePathException(message: String) : Exception(message)

/** A path after canonicalisation plus whether the original entry was a symlink. */
internal data class SafeTarget(val file: File, val symlink: Boolean)

/**
 * The only place that turns a model-supplied string into a [File].
 *
 * Rules (defence in depth, canonicalisation is the final word):
 * - empty, NUL, longer than 400 chars, or any `..` segment is rejected;
 * - relatives are resolved against shared storage;
 * - the canonical result must live below shared storage or one of Omni's
 *   private folders (files, cache, external files);
 * - symlinks are detected on the *original* entry before canonicalisation.
 */
@Suppress("DEPRECATION")
internal class SafePaths(context: Context) {

    val externalRoot: File =
        canonicalOf(Environment.getExternalStorageDirectory()) ?: File(FALLBACK_ROOT)

    private val appRoots: List<File> = listOfNotNull(
        context.filesDir,
        context.cacheDir,
        context.getExternalFilesDir(null),
    ).mapNotNull(::canonicalOf)

    fun resolve(raw: String?): SafeTarget {
        val input = raw?.trim().orEmpty()
        if (input.isEmpty()) throw UnsafePathException("A file path is required")
        if (input.length > MAX_PATH) throw UnsafePathException("That path is too long")
        if (input.indexOf('\u0000') >= 0) {
            throw UnsafePathException("That path contains an invalid character")
        }
        if (input.split('/', '\\').any { it == ".." }) {
            throw UnsafePathException("Parent-folder references are not allowed in a path")
        }

        val candidate = if (input.startsWith("/")) {
            File(input)
        } else {
            File(externalRoot, input)
        }
        val canonical = canonicalOf(candidate)
            ?: throw UnsafePathException("That path could not be resolved")
        if (canonical.path.length > MAX_PATH) throw UnsafePathException("That path is too long")
        if (!isAllowed(canonical)) {
            throw UnsafePathException("Only shared storage and Omni's own folders are accessible")
        }
        return SafeTarget(canonical, isSymbolicLink(candidate.absoluteFile))
    }

    fun resolveOrNull(raw: String?): File? = try {
        raw?.let { resolve(it).file }
    } catch (rejected: UnsafePathException) {
        null
    } catch (denied: SecurityException) {
        null
    }

    fun isAppPrivate(file: File): Boolean = appRoots.any { isInside(file, it) }

    fun isAllowed(file: File): Boolean = isAppPrivate(file) || isInside(file, externalRoot)

    fun isStorageRoot(file: File): Boolean =
        file.path == externalRoot.path || appRoots.any { file.path == it.path }

    /**
     * Creates the destination's parent directories (move/folder creation only)
     * and re-canonicalises the result so a symlinked ancestor cannot escape.
     */
    fun prepareDestination(file: File): File {
        val parent = file.parentFile
            ?: throw UnsafePathException("Destination folder is not allowed")
        val canonicalParent = canonicalOf(parent)
            ?: throw UnsafePathException("Destination folder could not be resolved")
        if (!isAllowed(canonicalParent)) {
            throw UnsafePathException("Destination folder is outside the allowed storage")
        }
        if (!canonicalParent.exists()) {
            val created = try {
                canonicalParent.mkdirs()
            } catch (error: SecurityException) {
                throw error
            } catch (error: Exception) {
                false
            }
            if (!created && !canonicalParent.isDirectory) {
                throw UnsafePathException("Could not create the destination folder")
            }
        }
        if (!canonicalParent.isDirectory) {
            throw UnsafePathException("Destination parent is not a folder")
        }
        val canonical = canonicalOf(file)
            ?: throw UnsafePathException("Destination could not be resolved")
        if (!isAllowed(canonical)) {
            throw UnsafePathException("Destination is outside the allowed storage")
        }
        return canonical
    }

    /** Copy must not create folders: the parent has to exist already. */
    fun requireExistingParent(file: File): File {
        val parent = file.parentFile
            ?: throw UnsafePathException("Destination folder is not allowed")
        val canonicalParent = canonicalOf(parent)
            ?: throw UnsafePathException("Destination folder could not be resolved")
        if (!isAllowed(canonicalParent)) {
            throw UnsafePathException("Destination folder is outside the allowed storage")
        }
        if (!canonicalParent.isDirectory) {
            throw UnsafePathException("Destination folder does not exist")
        }
        val canonical = canonicalOf(file)
            ?: throw UnsafePathException("Destination could not be resolved")
        if (!isAllowed(canonical)) {
            throw UnsafePathException("Destination is outside the allowed storage")
        }
        return canonical
    }

    private fun isInside(file: File, root: File): Boolean =
        file.path == root.path || file.path.startsWith(root.path + File.separator)

    private fun canonicalOf(file: File): File? = try {
        file.canonicalFile
    } catch (error: IOException) {
        null
    }

    companion object {
        const val MAX_PATH = 400
        private const val FALLBACK_ROOT = "/storage/emulated/0"
    }
}

/**
 * `File.isSymlink` is API 26+, so use lstat (API 21+) instead of adding a
 * compatibility branch. A missing entry is simply "not a symlink".
 */
internal fun isSymbolicLink(file: File): Boolean = try {
    OsConstants.S_ISLNK(Os.lstat(file.absolutePath).st_mode)
} catch (error: ErrnoException) {
    false
} catch (error: Exception) {
    false
}
