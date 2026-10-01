package org.vovchenko.webdavsync.data.local

import androidx.room.TypeConverter
import org.vovchenko.webdavsync.data.local.push.PushEndpointState
import org.vovchenko.webdavsync.data.local.push.PushRegistrationState
import org.vovchenko.webdavsync.data.model.AuthScheme
import org.vovchenko.webdavsync.data.model.SyncEventType
import org.vovchenko.webdavsync.data.model.SyncMethod

class Converters {
    @TypeConverter
    fun fromSyncMethod(value: SyncMethod): String = value.name

    @TypeConverter
    fun toSyncMethod(value: String): SyncMethod = SyncMethod.valueOf(value)

    @TypeConverter
    fun fromAuthScheme(value: AuthScheme?): String? = value?.name

    @TypeConverter
    fun toAuthScheme(value: String?): AuthScheme? = value?.let { AuthScheme.valueOf(it) }

    @TypeConverter
    fun fromSyncEventType(value: SyncEventType): String = value.name

    @TypeConverter
    fun toSyncEventType(value: String): SyncEventType = SyncEventType.valueOf(value)

    @TypeConverter
    fun fromPushRegistrationState(value: PushRegistrationState): String = value.name

    @TypeConverter
    fun toPushRegistrationState(value: String): PushRegistrationState = PushRegistrationState.valueOf(value)

    @TypeConverter
    fun fromPushEndpointState(value: PushEndpointState): String = value.name

    @TypeConverter
    fun toPushEndpointState(value: String): PushEndpointState = PushEndpointState.valueOf(value)

    // Relative paths can't contain newlines, so it's a safe, simple delimiter.
    @TypeConverter
    fun fromStringList(value: List<String>): String = value.joinToString("\n")

    @TypeConverter
    fun toStringList(value: String): List<String> = if (value.isEmpty()) emptyList() else value.split("\n")
}
