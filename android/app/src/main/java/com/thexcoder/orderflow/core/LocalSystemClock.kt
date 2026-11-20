package com.thexcoder.orderflow.core

import java.time.Clock
import java.time.Instant
import java.time.ZoneId

/** Unlike Clock.systemDefaultZone(), observes a device timezone change immediately. */
object LocalSystemClock : Clock() {
    override fun getZone(): ZoneId = ZoneId.systemDefault()
    override fun instant(): Instant = Instant.now()
    override fun withZone(zone: ZoneId): Clock = Clock.system(zone)
}
