package io.github.uirlisi.mostor.core

/** Event kinds (SPEC §4.2). [code] is what the database stores. */
enum class Kind(val code: String, val timed: Boolean) {
    BREAST("breast", true),
    BOTTLE("bottle", false),
    SUPPLEMENT("supplement", false),
    SLEEP("sleep", true),
    DIAPER("diaper", false),
    PUMP("pump", false),
    NOTE("note", false),
    ;

    /** Feeds are breast and bottle events. Supplements never count as feeds (SPEC §5.3). */
    val isFeed: Boolean get() = this == BREAST || this == BOTTLE

    companion object {
        fun of(code: String): Kind = entries.firstOrNull { it.code == code }
            ?: throw IllegalArgumentException("unknown kind '$code'")
    }
}

/**
 * Status of a timed event. Point events have none, except a med (supplement) dose that was
 * deliberately skipped: [SKIPPED] (SPEC §5.10). A skip covers its scheduled day but is never given.
 */
enum class EventStatus(val code: String) {
    RUNNING("running"),
    ENDED("ended"),
    END_UNKNOWN("end_unknown"),
    SKIPPED("skipped"),
    ;

    companion object {
        fun of(code: String): EventStatus = entries.firstOrNull { it.code == code }
            ?: throw IllegalArgumentException("unknown status '$code'")
    }
}

enum class Side(val code: String) {
    LEFT("L"),
    RIGHT("R"),
    ;

    val other: Side get() = if (this == LEFT) RIGHT else LEFT

    companion object {
        fun of(code: String): Side = entries.firstOrNull { it.code == code }
            ?: throw IllegalArgumentException("unknown side '$code'")
    }
}

/** Diaper subtypes. */
enum class Diaper(val code: String) {
    WET("wet"),
    POOP("poop"),
    MIXED("mixed"),
    DRY("dry"),
    ;

    companion object {
        fun ofOrNull(code: String?): Diaper? = entries.firstOrNull { it.code == code }
    }
}

/** Bottle subtypes. */
enum class BottleContent(val code: String) {
    BREAST_MILK("breast_milk"),
    FORMULA("formula"),
    ;

    companion object {
        fun ofOrNull(code: String?): BottleContent? = entries.firstOrNull { it.code == code }
    }
}

/** Common columns of every synced row (SPEC §4.1). */
interface Synced {
    val id: String
    val createdAt: Long
    val updatedAt: Long
    val deletedAt: Long?
    val hlc: String

    /**
     * Where this version sorts when two versions of the row meet ([Merge.plan]): its stamp, except for
     * an automatic change ([Event.autoBase]), which sorts just after the version it was made from.
     */
    val mergeRank: String get() = hlc
}

data class Child(
    override val id: String,
    val name: String? = null,
    val birthDate: String? = null,
    val sex: String? = null,
    val birthWeightG: Int? = null,
    val birthLengthMm: Int? = null,
    val color: Int? = null,
    val sortKey: Int = 0,
    val archivedAt: Long? = null,
    override val createdAt: Long,
    override val updatedAt: Long,
    override val deletedAt: Long? = null,
    override val hlc: String,
) : Synced

data class Event(
    override val id: String,
    val childId: String,
    val kind: Kind,
    val subtype: String? = null,
    val startAt: Long,
    val endAt: Long? = null,
    val status: EventStatus? = null,
    val amountMl: Double? = null,
    val supplementPresetId: String? = null,
    val supplementName: String? = null,
    val supplementAmount: Double? = null,
    val supplementUnit: String? = null,
    val note: String? = null,
    val tz: String,
    override val createdAt: Long,
    override val updatedAt: Long,
    override val deletedAt: Long? = null,
    override val hlc: String,
    /**
     * Set only on a change no one made: a timer given up as "end unknown", or a forgotten pause ended at
     * the pause (SPEC §5.5). It holds the stamp of the version the phone changed automatically. When the
     * versions meet, any version newer than that one wins, though its own stamp may be older: a phone
     * out of range, which never saw a stop made elsewhere, mustn't overwrite it (SPEC §9.5). Any later
     * change by a person clears it.
     */
    val autoBase: String? = null,
) : Synced {
    val isLive: Boolean get() = deletedAt == null

    override val mergeRank: String get() = autoBase ?: hlc
}

data class BreastSegment(
    override val id: String,
    val eventId: String,
    val side: Side,
    val startAt: Long,
    val endAt: Long? = null,
    override val createdAt: Long,
    override val updatedAt: Long,
    override val deletedAt: Long? = null,
    override val hlc: String,
) : Synced

/** How often a med is given (SPEC §5.10). Only displayed: the app never enforces or suggests one. */
enum class MedSchedule(val code: String) {
    DAILY("daily"),
    WEEKLY("weekly"),
    EVERY_N_DAYS("every_n_days"),
    AS_NEEDED("as_needed"),
    ;

    companion object {
        fun of(code: String?): MedSchedule = entries.firstOrNull { it.code == code } ?: AS_NEEDED
    }
}

/**
 * A med (called a supplement in code and storage). Each belongs to one child (schema version 2).
 * [scheduleWeekday] is 1 = Monday … 7 = Sunday (weekly only); [scheduleEveryDays] is N ≥ 2 (every N
 * days only); [scheduleStart] is the local date (yyyy-MM-dd) the schedule counts from.
 */
data class SupplementPreset(
    override val id: String,
    val childId: String,
    val name: String,
    val amount: Double? = null,
    val unit: String? = null,
    val sortKey: Int = 0,
    val schedule: MedSchedule = MedSchedule.AS_NEEDED,
    val scheduleWeekday: Int? = null,
    val scheduleEveryDays: Int? = null,
    val scheduleStart: String? = null,
    override val createdAt: Long,
    override val updatedAt: Long,
    override val deletedAt: Long? = null,
    override val hlc: String,
) : Synced

/** A timed event with its live breast segments in time order (empty for sleep). */
class TimedEvent private constructor(val event: Event, val segments: List<BreastSegment>) {
    /** The segment still open, if the event is a breast feed that hasn't ended. */
    val openSegment: BreastSegment? get() = segments.lastOrNull()?.takeIf { it.endAt == null }

    /** The side of the last segment ("which side now" while running, "last side" once ended). */
    val lastSide: Side? get() = segments.lastOrNull()?.side

    /** A running breast feed with every segment closed: paused (SPEC §5.4). */
    val isPaused: Boolean
        get() = event.kind == Kind.BREAST && event.status == EventStatus.RUNNING && event.deletedAt == null &&
            segments.isNotEmpty() && openSegment == null

    /** When a paused feed was paused: the end of its last segment. */
    val pausedAt: Long? get() = if (isPaused) segments.last().endAt else null

    override fun equals(other: Any?) = other is TimedEvent && other.event == event && other.segments == segments
    override fun hashCode() = 31 * event.hashCode() + segments.hashCode()
    override fun toString() = "TimedEvent($event, $segments)"

    companion object {
        /** Keeps [event]'s live segments, sorted by start. */
        fun of(event: Event, segments: List<BreastSegment> = emptyList()): TimedEvent {
            require(event.kind.timed) { "${event.kind} is not timed" }
            val own = segments.filter { it.eventId == event.id && it.deletedAt == null }
                .sortedWith(compareBy({ it.startAt }, { it.createdAt }))
            return TimedEvent(event, own)
        }
    }
}
