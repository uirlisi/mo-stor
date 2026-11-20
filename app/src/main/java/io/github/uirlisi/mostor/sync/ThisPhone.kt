package io.github.uirlisi.mostor.sync

import android.content.Context
import android.os.Build
import android.provider.Settings
import io.github.uirlisi.mostor.data.Repository

/** This phone as pairing and sync introduce it to the other phone: its device id and name. */
internal class ThisPhone(
    private val context: Context,
    private val repository: () -> Repository,
    private val households: HouseholdHost?,
) {
    suspend fun me(repo: Repository = repository()): Me {
        val id = repo.sync.deviceId()
        return Me(id, deviceName(), households?.deviceIds().orEmpty() - id)
    }

    /** This phone, with the household's list made and its own name in it current. */
    suspend fun ready(): Me = me().also { repository().sync.ensureRoster(it.name) }

    fun deviceName(): String =
        Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)?.takeIf { it.isNotBlank() } ?: Build.MODEL
}
