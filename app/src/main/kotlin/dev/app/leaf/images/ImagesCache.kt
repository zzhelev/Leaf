package dev.app.leaf.images

interface ImagesCache {
    fun getCachedImage(urlSource: String): ByteArray?
    fun cacheImage(urlSource: String, image: ByteArray)
}