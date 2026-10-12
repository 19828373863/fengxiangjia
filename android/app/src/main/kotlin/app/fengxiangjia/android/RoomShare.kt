package app.fengxiangjia.android

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** Shares the bitmap captured from the displayed room canvas; no second solver or renderer. */
suspend fun shareRoomImage(context: Context, bitmap: Bitmap, name: String) {
    val uri = withContext(Dispatchers.IO) {
        check(!bitmap.isRecycled && bitmap.width > 0 && bitmap.height > 0)
        val directory = File(context.cacheDir, "room-images")
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("无法创建图片缓存")
        val safeName = name.trim().replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").take(40).ifBlank { "房间摆法" }
        val file = File.createTempFile("风向家-$safeName-", ".png", directory)
        try {
            file.outputStream().use { output ->
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) throw IOException("图片编码失败")
            }
            FileProvider.getUriForFile(context, "${context.packageName}.room-images", file)
        } catch (error: Exception) {
            file.delete()
            throw error
        }
    }
    withContext(Dispatchers.Main) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "风向家 · $name")
            clipData = ClipData.newUri(context.contentResolver, "房间摆法", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(send, "导出房间图片").apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
    }
}
