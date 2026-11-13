package com.omni.aiassistant.core.util

import java.util.UUID

object Ids {
    fun newId(): String = UUID.randomUUID().toString()
}
