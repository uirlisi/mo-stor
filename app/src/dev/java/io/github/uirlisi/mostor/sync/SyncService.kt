package io.github.uirlisi.mostor.sync

import java.util.UUID

/**
 * The dev build's Bluetooth sync service, not prod's, so a dev app never wakes for the family's
 * prod sync and the other way round (DECISIONS, 2026-11-18, "The dev build's own Bluetooth service").
 */
internal object SyncService {
    val ID: UUID = UUID.fromString("d7e691f2-c959-4343-8f71-910d5e337863")
}
