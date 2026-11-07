package com.azurpilot.ghio

/** JVM 夹具保留生产 IO 调度语义。 / Preserves production IO dispatch semantics on JVM. */
object AppDispatchers { val IO = kotlinx.coroutines.Dispatchers.IO }
