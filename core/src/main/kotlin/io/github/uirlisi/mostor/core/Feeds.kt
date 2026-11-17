package io.github.uirlisi.mostor.core

/** Where "since the last feed" is measured from (a per-phone setting). */
enum class FeedAnchor(val code: String) {
    START("start"),
    END("end"),
    ;

    companion object {
        fun ofOrNull(code: String?): FeedAnchor? = entries.firstOrNull { it.code == code }
    }
}

/** "Since the last feed" and "last side" (SPEC §5.3). */
object Feeds {
    /** The most recent feed (breast or bottle, never a supplement) by start time. */
    fun lastFeed(events: List<Event>): Event? =
        events.filter { it.kind.isFeed && it.deletedAt == null }.maxWithOrNull(compareBy({ it.startAt }, { it.createdAt }))

    /**
     * The moment "since the last feed" counts from. From the start by default. From the end, a
     * feed still running gives null (it's happening now), and so does one whose end is unknown.
     * A bottle without an end counts from its start.
     */
    fun anchor(feed: Event, from: FeedAnchor): Long? = when (from) {
        FeedAnchor.START -> feed.startAt
        FeedAnchor.END -> when (feed.status) {
            EventStatus.RUNNING, EventStatus.END_UNKNOWN, EventStatus.SKIPPED -> null
            EventStatus.ENDED -> feed.endAt
            null -> feed.endAt ?: feed.startAt
        }
    }

    fun sinceLastFeed(events: List<Event>, now: Long, from: FeedAnchor): Long? =
        lastFeed(events)?.let { anchor(it, from) }?.let { (now - it).coerceAtLeast(0) }

    /** The side of the last segment of the most recent breast feed. */
    fun lastSide(lastBreastFeed: TimedEvent?): Side? = lastBreastFeed?.lastSide
}
