package com.aaria.app.queue

import java.util.concurrent.ConcurrentLinkedQueue

class MessageQueue {

    private val queue = ConcurrentLinkedQueue<MessageObject>()

    var onMessageAdded: ((MessageObject) -> Unit)? = null

    fun add(message: MessageObject) {
        queue.add(message)
        onMessageAdded?.invoke(message)
    }

    fun peek(): MessageObject? = queue.peek()

    fun poll(): MessageObject? = queue.poll()

    fun pendingSenders(): List<String> = queue.map { it.sender }.distinct()

    fun findBySender(sender: String): MessageObject? =
        queue.firstOrNull { it.sender.equals(sender, ignoreCase = true) }

    fun size(): Int = queue.size

    fun isEmpty(): Boolean = queue.isEmpty()

    fun clear() = queue.clear()
}
