package io.github.uirlisi.mostor.sync

import java.util.UUID

/**
 * The prod build's Bluetooth sync service (random, fixed for good: the family's phones find each
 * other by it). The dev build has its own, so the two apps never wake each other (DECISIONS,
 * 2026-11-18, "The dev build's own Bluetooth service").
 */
internal object SyncService {
    val ID: UUID = UUID.fromString("12ca01f1-1d10-494e-8e7f-2b2e6240d3ff")
}
