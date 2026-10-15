package android.content.res

import java.io.File
import java.io.InputStream

class AssetManager(private val root: File) {
    fun open(path: String): InputStream = File(root, path).inputStream()
}
