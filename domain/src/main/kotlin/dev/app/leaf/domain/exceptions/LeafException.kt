package dev.app.leaf.domain.exceptions

abstract class LeafException(message: String, cause: Exception? = null) : Exception(message, cause)