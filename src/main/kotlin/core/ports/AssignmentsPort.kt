package com.example.core.ports

import com.example.core.model.Assignment
import java.util.UUID

interface AssignmentsPort {
    fun insert(a: Assignment)
    fun getById(id: UUID): Assignment?
    fun getByJoinKey(joinKey: String): Assignment?
}
