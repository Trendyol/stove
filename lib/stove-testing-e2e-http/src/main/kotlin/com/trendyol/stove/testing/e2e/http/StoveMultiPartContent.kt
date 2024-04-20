@file:Suppress("ArrayInDataClass")

package com.trendyol.stove.testing.e2e.http

/**
 * Represents a multi-part content for a HTTP request.
 */
sealed class StoveMultiPartContent {
    /**
     * Represents a text content for a multi-part request.
     */
    data class Text(val param: String, val value: String) : StoveMultiPartContent()

    /**
     * Represents a file content for a multi-part request.
     */
    data class File(
        val param: String,
        val fileName: String,
        val content: ByteArray,
        val contentType: String
    ) : StoveMultiPartContent()

    /**
     * Represents a binary content for a multi-part request.
     */
    data class Binary(val param: String, val content: ByteArray) : StoveMultiPartContent()
}
