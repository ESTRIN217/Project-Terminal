package com.estrin217.terminal

import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import com.estrin217.terminal.core.TerminalConfig
import com.estrin217.terminal.core.logger.DebugLogger
import java.io.File
import java.io.FileNotFoundException

class RootfsDocumentsProvider : DocumentsProvider() {

    private val defaultProjection = arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
        DocumentsContract.Document.COLUMN_SIZE,
        DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        DocumentsContract.Document.COLUMN_FLAGS
    )

    override fun onCreate(): Boolean {
        DebugLogger.i("RootfsDocumentsProvider", "DocumentsProvider created")
        return true
    }

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val cols = projection ?: defaultProjection
        val cursor = MatrixCursor(cols)

        val rootfsDir = TerminalConfig.getRootfsDir(context!!)
        if (!rootfsDir.exists()) return cursor

        val row = cursor.newRow().apply {
            add(DocumentsContract.Root.COLUMN_ROOT_ID, ROOT_ID)
            add(DocumentsContract.Root.COLUMN_DOCUMENT_ID, ROOT_ID)
            add(DocumentsContract.Root.COLUMN_TITLE, "Linux Rootfs")
            add(DocumentsContract.Root.COLUMN_SUMMARY, "Rootfs del contenedor Linux")
            add(DocumentsContract.Root.COLUMN_MIME_TYPES, "*/*")
            add(DocumentsContract.Root.COLUMN_AVAILABLE_QUOTAS, DocumentsContract.Root.QUOTA_NOT_RESOLVED)
            add(DocumentsContract.Root.COLUMN_FLAGS, DocumentsContract.Root.FLAG_SUPPORTS_IS_CHILD or
                    DocumentsContract.Root.FLAG_LOCAL_ONLY)
            add(DocumentsContract.Root.COLUMN_ICON, android.R.drawable.ic_menu_folder)
        }
        DebugLogger.i("RootfsDocumentsProvider", "queryRoots: returning 1 root")
        return cursor
    }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        val cols = projection ?: defaultProjection
        val cursor = MatrixCursor(cols)
        val parentFile = getFileForDocumentId(parentDocumentId)

        if (!parentFile.exists() || !parentFile.isDirectory) {
            DebugLogger.w("RootfsDocumentsProvider", "queryChildDocuments: $parentDocumentId not found or not a directory")
            return cursor
        }

        val children = parentFile.listFiles() ?: return cursor
        for (child in children.sortedWith(compareBy({ !it.isDirectory }, { it.name }))) {
            includeFile(cursor, child, parentDocumentId)
        }
        return cursor
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        val cols = projection ?: defaultProjection
        val cursor = MatrixCursor(cols)
        val file = getFileForDocumentId(documentId)

        if (!file.exists()) {
            DebugLogger.w("RootfsDocumentsProvider", "queryDocument: $documentId not found")
            return cursor
        }
        includeFile(cursor, file, documentId)
        return cursor
    }

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?
    ): ParcelFileDescriptor {
        val file = getFileForDocumentId(documentId)
        if (!file.exists()) {
            throw FileNotFoundException("File not found: $documentId")
        }

        val accessMode = when {
            mode.contains("w") -> ParcelFileDescriptor.MODE_READ_WRITE
            else -> ParcelFileDescriptor.MODE_READ_ONLY
        }
        DebugLogger.i("RootfsDocumentsProvider", "openDocument: $documentId (mode=$mode)")
        return ParcelFileDescriptor.open(file, accessMode)
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean {
        return documentId.startsWith(parentDocumentId)
    }

    override fun getDocumentType(documentId: String): String {
        val file = getFileForDocumentId(documentId)
        if (file.isDirectory) return DocumentsContract.Document.MIME_TYPE_DIR
        val name = file.name
        return when {
            name.endsWith(".txt") || name.endsWith(".md") -> "text/plain"
            name.endsWith(".sh") -> "text/x-shellscript"
            name.endsWith(".so") -> "application/x-sharedlib"
            name.endsWith(".tar") || name.endsWith(".tar.xz") || name.endsWith(".tar.gz") -> "application/x-tar"
            else -> "application/octet-stream"
        }
    }

    override fun removeDocument(documentId: String, parentDocumentId: String) {
        val file = getFileForDocumentId(documentId)
        if (file.exists()) {
            if (file.isDirectory) {
                file.deleteRecursively()
            } else {
                file.delete()
            }
            DebugLogger.i("RootfsDocumentsProvider", "removeDocument: $documentId deleted")
        }
    }

    private fun getFileForDocumentId(documentId: String): File {
        val rootfsDir = TerminalConfig.getRootfsDir(context!!)
        return if (documentId == ROOT_ID) {
            rootfsDir
        } else {
            require(documentId.startsWith(ROOT_ID_PREFIX)) { "Invalid document ID: $documentId" }
            val relativePath = documentId.removePrefix(ROOT_ID_PREFIX)
            File(rootfsDir, relativePath)
        }
    }

    private fun encodeDocumentId(parentId: String, fileName: String): String {
        return if (parentId == ROOT_ID) {
            "$ROOT_ID_PREFIX$fileName"
        } else {
            "$parentId/$fileName"
        }
    }

    private fun includeFile(cursor: MatrixCursor, file: File, parentId: String) {
        val docId = encodeDocumentId(parentId, file.name)
        val mimeType = if (file.isDirectory) {
            DocumentsContract.Document.MIME_TYPE_DIR
        } else {
            getDocumentType(docId)
        }
        val flags = buildFlags(file)

        cursor.newRow().apply {
            add(DocumentsContract.Document.COLUMN_DOCUMENT_ID, docId)
            add(DocumentsContract.Document.COLUMN_DISPLAY_NAME, file.name)
            add(DocumentsContract.Document.COLUMN_MIME_TYPE, mimeType)
            add(DocumentsContract.Document.COLUMN_SIZE, file.length())
            add(DocumentsContract.Document.COLUMN_LAST_MODIFIED, file.lastModified())
            add(DocumentsContract.Document.COLUMN_FLAGS, flags)
        }
    }

    private fun buildFlags(file: File): Int {
        var flags = 0
        if (file.isDirectory) {
            flags = flags or DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE or
                    DocumentsContract.Document.FLAG_SUPPORTS_DELETE
        } else {
            flags = flags or DocumentsContract.Document.FLAG_SUPPORTS_WRITE or
                    DocumentsContract.Document.FLAG_SUPPORTS_DELETE or
                    DocumentsContract.Document.FLAG_SUPPORTS_READ
        }
        return flags
    }

    companion object {
        private const val ROOT_ID = "rootfs_root"
        private const val ROOT_ID_PREFIX = "rootfs/"
    }
}
