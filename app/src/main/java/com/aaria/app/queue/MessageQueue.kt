package com.aaria.app.queue

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

class MessageQueue {

    private val queue = ConcurrentLinkedQueue<MessageObject>()

    // Tracks notification IDs with the timestamp they were added.
    // Suppresses duplicate posts of the exact same notification within a 5-second
    // window (WhatsApp sometimes fires onNotificationPosted twice quickly for the
    // same sbn.key). After 5 seconds the entry is evicted so future messages from
    // the same conversation are always accepted.
    private val recentIds = ConcurrentHashMap<String, Long>()

    // Multiple listeners keyed by tag — service and activity can both subscribe
    // without overwriting each other.
    private val listeners = ConcurrentHashMap<String, (MessageObject) -> Unit>()

    fun addListener(tag: String, listener: (MessageObject) -> Unit) {
        listeners[tag] = listener
    }

    fun removeListener(tag: String) {
        listeners.remove(tag)
    }

    fun add(message: MessageObject) {
        val now = System.currentTimeMillis()
        val last = recentIds[message.id]
        if (last != null && now - last < 5_000L) return  // same notification re-posted within 5s
        recentIds[message.id] = now
        queue.add(message)
        listeners.values.forEach { it.invoke(message) }
    }

    fun peek(): MessageObject? = queue.peek()

    fun poll(): MessageObject? = queue.poll()

    fun pendingSenders(): List<String> = queue.map { it.sender }.distinct()

    fun findBySender(sender: String): MessageObject? =
        queue.firstOrNull { it.sender.equals(sender, ignoreCase = true) }

    fun size(): Int = queue.size

    fun isEmpty(): Boolean = queue.isEmpty()

    fun clear() {
        queue.clear()
        recentIds.clear()
    }
}
