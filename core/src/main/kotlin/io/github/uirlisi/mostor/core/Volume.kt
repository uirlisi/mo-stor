package io.github.uirlisi.mostor.core

import kotlin.math.roundToLong

/** Volumes are stored in millilitres and shown in ml or US fluid ounces (SPEC §4.1, §6.6). */
enum class VolumeUnit(val code: String) {
    ML("ml"),
    FL_OZ("fl_oz"),
    ;

    /** One stepper step in this unit. */
    val step: Double get() = if (this == ML) 10.0 else 0.5

    /** Where a stepper starts when nothing has been recorded yet. */
    val firstAmount: Double get() = if (this == ML) 60.0 else 2.0

    companion object {
        fun ofOrNull(code: String?): VolumeUnit? = entries.firstOrNull { it.code == code }

        /** The default for a phone's region: fluid ounces where they are the everyday unit. */
        fun defaultFor(countryCode: String?): VolumeUnit =
            if (countryCode?.uppercase() in setOf("US", "LR", "MM")) FL_OZ else ML
    }
}

object Volume {
    const val ML_PER_US_FL_OZ = 29.5735295625

    fun toMl(amount: Double, unit: VolumeUnit): Double = if (unit == VolumeUnit.ML) amount else amount * ML_PER_US_FL_OZ

    fun fromMl(ml: Double, unit: VolumeUnit): Double = if (unit == VolumeUnit.ML) ml else ml / ML_PER_US_FL_OZ

    /**
     * [ml] shown in [unit] and snapped to the stepper's grid (whole 10 ml, or half ounces), so a
     * stepper that starts at "the last amount" lands on round values.
     */
    fun snapped(ml: Double, unit: VolumeUnit): Double {
        val v = fromMl(ml, unit)
        return (v / unit.step).roundToLong() * unit.step
    }
}
