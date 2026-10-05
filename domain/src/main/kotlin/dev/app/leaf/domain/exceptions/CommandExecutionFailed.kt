package dev.app.leaf.domain.exceptions

class CommandExecutionFailed(msg: String, cause: Exception) : LeafException(msg, cause) {
}