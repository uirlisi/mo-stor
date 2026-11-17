package io.github.uirlisi.mostor.data

import io.github.uirlisi.mostor.core.BreastSegment
import io.github.uirlisi.mostor.core.Child
import io.github.uirlisi.mostor.core.Event
import io.github.uirlisi.mostor.core.EventStatus
import io.github.uirlisi.mostor.core.Kind
import io.github.uirlisi.mostor.core.Side
import io.github.uirlisi.mostor.core.MedSchedule
import io.github.uirlisi.mostor.core.SupplementPreset

fun EventEntity.toModel() = Event(
    id = id,
    childId = childId,
    kind = Kind.of(kind),
    subtype = subtype,
    startAt = startAt,
    endAt = endAt,
    status = status?.let(EventStatus::of),
    amountMl = amountMl,
    supplementPresetId = supplementPresetId,
    supplementName = supplementName,
    supplementAmount = supplementAmount,
    supplementUnit = supplementUnit,
    note = note,
    tz = tz,
    createdAt = createdAt,
    updatedAt = updatedAt,
    deletedAt = deletedAt,
    hlc = hlc,
)

fun Event.toEntity() = EventEntity(
    id = id,
    childId = childId,
    kind = kind.code,
    subtype = subtype,
    startAt = startAt,
    endAt = endAt,
    status = status?.code,
    amountMl = amountMl,
    supplementPresetId = supplementPresetId,
    supplementName = supplementName,
    supplementAmount = supplementAmount,
    supplementUnit = supplementUnit,
    note = note,
    tz = tz,
    createdAt = createdAt,
    updatedAt = updatedAt,
    deletedAt = deletedAt,
    hlc = hlc,
)

fun SegmentEntity.toModel() = BreastSegment(
    id = id,
    eventId = eventId,
    side = Side.of(side),
    startAt = startAt,
    endAt = endAt,
    createdAt = createdAt,
    updatedAt = updatedAt,
    deletedAt = deletedAt,
    hlc = hlc,
)

fun BreastSegment.toEntity() = SegmentEntity(
    id = id,
    eventId = eventId,
    side = side.code,
    startAt = startAt,
    endAt = endAt,
    createdAt = createdAt,
    updatedAt = updatedAt,
    deletedAt = deletedAt,
    hlc = hlc,
)

fun PresetEntity.toModel() = SupplementPreset(
    id = id,
    childId = childId,
    name = name,
    amount = amount,
    unit = unit,
    sortKey = sortKey,
    schedule = MedSchedule.of(schedule),
    scheduleWeekday = scheduleWeekday,
    scheduleEveryDays = scheduleEveryDays,
    scheduleStart = scheduleStart,
    createdAt = createdAt,
    updatedAt = updatedAt,
    deletedAt = deletedAt,
    hlc = hlc,
)

fun SupplementPreset.toEntity() = PresetEntity(
    id = id,
    childId = childId,
    name = name,
    amount = amount,
    unit = unit,
    sortKey = sortKey,
    schedule = schedule.code,
    scheduleWeekday = scheduleWeekday,
    scheduleEveryDays = scheduleEveryDays,
    scheduleStart = scheduleStart,
    createdAt = createdAt,
    updatedAt = updatedAt,
    deletedAt = deletedAt,
    hlc = hlc,
)

fun ChildEntity.toModel() = Child(
    id = id,
    name = name,
    birthDate = birthDate,
    sex = sex,
    birthWeightG = birthWeightG,
    birthLengthMm = birthLengthMm,
    color = color,
    sortKey = sortKey,
    archivedAt = archivedAt,
    createdAt = createdAt,
    updatedAt = updatedAt,
    deletedAt = deletedAt,
    hlc = hlc,
)

fun Child.toEntity() = ChildEntity(
    id = id,
    name = name,
    birthDate = birthDate,
    sex = sex,
    birthWeightG = birthWeightG,
    birthLengthMm = birthLengthMm,
    color = color,
    sortKey = sortKey,
    archivedAt = archivedAt,
    createdAt = createdAt,
    updatedAt = updatedAt,
    deletedAt = deletedAt,
    hlc = hlc,
)
