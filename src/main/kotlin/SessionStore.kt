package com.example

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class Msg(val role: String, val content: String)

data class Session(
    val id: String,
    val assignmentId: String,
    val joinKey: String,
    val topic: String,
    val vocab: List<String>,
    val level: String? = null,
    val messages: MutableList<Msg> = mutableListOf()
)

object SessionStore {
    private val sessions = ConcurrentHashMap<String, Session>()

    fun createFromAssignment(a: Assignment): Session {
        val id = UUID.randomUUID().toString()
        val s = Session(
            id = id,
            assignmentId = a.id,
            joinKey = a.joinKey,
            topic = a.topic,
            vocab = a.vocab,
            level = a.level
        )
        sessions[id] = s
        return s
    }

    fun get(id: String): Session? = sessions[id]

    fun delete(id: String): Boolean = sessions.remove(id) != null

    fun list(limit: Int, offset: Int): List<Session> =
        sessions.values
            .asSequence()
            .drop(offset)
            .take(limit)
            .toList()
}
