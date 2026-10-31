package dev.idadroid.files

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import java.io.File
import java.util.Locale

object RootfsFileSharing {
    fun contentUri(context: Context, file: File) = FileProvider.getUriForFile(
        context,
        "${context.packageName}.rootfs-file-provider",
        file
    )

    fun openFile(context: Context, file: File) {
        val mimeType = mimeTypeFor(file.name)
        val intent = viewIntent(context, file, mimeType)
            .takeIf { it.resolveActivity(context.packageManager) != null }
            ?: viewIntent(context, file, "*/*")
                .takeIf { it.resolveActivity(context.packageManager) != null }
            ?: throw ActivityNotFoundException("没有可打开该文件的应用")

        val chooser = Intent.createChooser(intent, "打开 ${file.name}")
        chooser.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        context.startActivity(chooser)
    }

    fun mimeTypeFor(fileName: String): String {
        val extension = fileName.substringAfterLast('.', missingDelimiterValue = "")
            .lowercase(Locale.ROOT)
            .takeIf { it.isNotBlank() }
        return extension?.let { MimeTypeMap.getSingleton().getMimeTypeFromExtension(it) }
            ?: "application/octet-stream"
    }

    private fun viewIntent(context: Context, file: File, mimeType: String): Intent {
        val uri = contentUri(context, file)
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType)
            putExtra(Intent.EXTRA_TITLE, file.name)
            clipData = ClipData.newUri(context.contentResolver, file.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
    }
}
