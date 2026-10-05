package dev.app.leaf.domain.repositories

import dev.app.leaf.domain.models.NotificationData
import kotlinx.coroutines.flow.StateFlow

interface NotificationsRepository {
    val notifications: StateFlow<List<NotificationData>>

    suspend fun emitNotification(notificationData: NotificationData)
}