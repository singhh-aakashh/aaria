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

        // Dedup 1: exact same sbn.key re-posted within 5s (WhatsApp double-fires onNotificationPosted)
        val last = recentIds[message.id]
        if (last != null && now - last < 5_000L) return
        recentIds[message.id] = now

        // Dedup 2: same sender + same text within 3s (WhatsApp posts two different sbn.key
        // notifications for the same message — one individual, one summary update)
        val contentKey = "${message.senderKey}|${message.text}"
        val lastContent = recentIds[contentKey]
        if (lastContent != null && now - lastContent < 3_000L) return
        recentIds[contentKey] = now

        queue.add(message)
        listeners.values.forEach { it.invoke(message) }
    }

    fun peek(): MessageObject? = queue.peek()

    fun poll(): MessageObject? = queue.poll()

    /** Distinct sender display names currently in the queue, in arrival order. */
    fun pendingSenders(): List<String> = queue.map { it.sender }.distinct()

    /** Distinct senderKeys currently in the queue, in arrival order. */
    fun pendingSenderKeys(): List<String> = queue.map { it.senderKey }.distinct()

    fun findBySender(sender: String): MessageObject? =
        queue.firstOrNull { it.sender.equals(sender, ignoreCase = true) }

    fun findBySenderKey(senderKey: String): MessageObject? =
        queue.firstOrNull { it.senderKey == senderKey }

    /** All messages from a given senderKey, in arrival order. */
    fun allBySenderKey(senderKey: String): List<MessageObject> =
        queue.filter { it.senderKey == senderKey }

    /**
     * Remove and return all messages from the given senderKey.
     * Used when the user selects a conversation to read/reply to.
     */
    fun removeAllBySenderKey(senderKey: String): List<MessageObject> {
        val removed = mutableListOf<MessageObject>()
        val iter = queue.iterator()
        while (iter.hasNext()) {
            val msg = iter.next()
            if (msg.senderKey == senderKey) {
                iter.remove()
                removed.add(msg)
            }
        }
        return removed
    }

    fun size(): Int = queue.size

    fun isEmpty(): Boolean = queue.isEmpty()

    fun clear() {
        queue.clear()
        recentIds.clear()
    }
}
