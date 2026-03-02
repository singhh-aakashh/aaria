package com.aaria.app.queue

data class MessageObject(
    val id: String,
    val sender: String,
    val text: String,
    val isGroup: Boolean,
    val groupName: String?,
    val senderKey: String,
    val timestamp: Long,
    val replyAvailable: Boolean
)
