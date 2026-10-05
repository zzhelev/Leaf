package dev.app.leaf.data.repositories

import dev.app.leaf.domain.models.NotificationData
import dev.app.leaf.domain.repositories.NotificationsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

class InMemoryNotificationsRepository @Inject constructor() : NotificationsRepository {
    private val _notifications = MutableStateFlow<List<NotificationData>>(emptyList())

    override val notifications: StateFlow<List<NotificationData>> = _notifications

    override suspend fun emitNotification(notificationData: NotificationData) {
        _notifications.value += notificationData
    }
}