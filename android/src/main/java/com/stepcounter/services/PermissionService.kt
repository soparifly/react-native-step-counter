package com.stepcounter.services

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Build.VERSION_CODES
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import android.util.SparseArray
import androidx.core.app.NotificationManagerCompat
import com.facebook.react.bridge.*
import com.facebook.react.modules.core.PermissionAwareActivity
import com.facebook.react.modules.core.PermissionListener
import com.stepcounter.models.PermissionsResponse
import com.stepcounter.models.PermissionsResponse.PermissionStatus.*

class PermissionService(reactContext: ReactApplicationContext?) : PermissionListener {
    private val mCallbacks = SparseArray<Callback>()
    private val activity = Activity()
    private var applicationContext = reactContext ?: activity.applicationContext
    private var mSharedPrefs = applicationContext.getSharedPreferences(
        SETTING_NAME,
        Context.MODE_PRIVATE,
    )
    private var mRequestCode = 0
    private var permissions: Array<String> = emptyArray()
    private val permissionAwareActivity: PermissionAwareActivity
        get() {
            check(activity is PermissionAwareActivity) {
                ("Tried to use permissions API but the host Activity doesn't" + " implement PermissionAwareActivity.")
            }
            return activity
        }

    fun requestPermission(permission: String, promise: Promise) {
        if (permissionNotExists(permission)) {
            promise.resolve(UNAVAILABLE)
            return
        }
        if (Build.VERSION.SDK_INT < VERSION_CODES.M) {
            promise.resolve(
                if (applicationContext.checkPermission(
                        permission,
                        Process.myPid(),
                        Process.myUid(),
                    ) == PackageManager.PERMISSION_GRANTED
                ) {
                    GRANTED
                } else {
                    BLOCKED
                },
            )
            return
        }
        if (applicationContext.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) {
            promise.resolve(GRANTED)
            return
        }
        if (!this.isNeedRequestPermission(permission, promise)) {
            return
        }
        try {
            val rationaleStatuses = BooleanArray(1)
            rationaleStatuses[0] =
                permissionAwareActivity.shouldShowRequestPermissionRationale(permission)
            mCallbacks.put(
                mRequestCode,
                Callback { args: Array<Any> ->
                    val results = args[0] as IntArray
                    if (results.isNotEmpty() && results[0] == PackageManager.PERMISSION_GRANTED) {
                        promise.resolve(GRANTED)
                    } else {
                        val perActivity = args[1] as PermissionAwareActivity
                        val boolArray = args[2] as BooleanArray
                        if (perActivity.shouldShowRequestPermissionRationale(permission)) {
                            promise.resolve(DENIED)
                        } else if (boolArray[0]) {
                            promise.resolve(BLOCKED)
                            mSharedPrefs.edit().putBoolean(permission, true).apply()
                        }
                    }
                },
            )
            permissionAwareActivity.requestPermissions(arrayOf(permission), mRequestCode, this)
            mRequestCode++
        } catch (e: IllegalStateException) {
            promise.reject(ERROR_INVALID_ACTIVITY, e)
        }
    }

    private fun isNeedRequestPermission(
        permission: String,
        promise: Promise,
    ): Boolean {
        val notBlocked = mSharedPrefs.getBoolean(permission, false)
        if (!notBlocked) {
            // not supporting reset the permission with "Ask me every time"
            promise.resolve(BLOCKED)
            return false
        }
        val result = if (activity.applicationContext.checkPermission(
                permission,
                Process.myPid(),
                Process.myUid(),
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            GRANTED
        } else {
            DENIED
        }
        return if (result == GRANTED) {
            promise.resolve(result)
            true
        } else {
            promise.resolve(DENIED)
            false
        }
    }

    fun openSettings(promise: Promise) {
        try {
            val intent = Intent()
            val packageName = applicationContext.packageName
            intent.action = Settings.ACTION_APPLICATION_DETAILS_SETTINGS
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            intent.data = Uri.fromParts("package", packageName, null)
            applicationContext.startActivity(intent)
            promise.resolve(true)
        } catch (e: Exception) {
            promise.reject(ERROR_INVALID_ACTIVITY, e)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ): Boolean {
        return try {
            val mCallback = mCallbacks[requestCode]
            mCallback.invoke(grantResults, permissionAwareActivity)
            mCallbacks.remove(requestCode)
            mCallbacks.size() == 0
        } catch (_: Exception) {
            false
        }
    }

    fun checkPermission(permission: String?): String {
        if ((permission == null) || permissionNotExists(permission)) {
            return UNAVAILABLE.status
        }
        return if (applicationContext.checkPermission(
                permission,
                Process.myPid(),
                Process.myUid(),
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            GRANTED.status
        } else {
            DENIED.status
        }
    }

    fun shouldShowRequestPermissionRationale(permission: String?, promise: Promise) {
        if (permission == null || Build.VERSION.SDK_INT < VERSION_CODES.M) {
            promise.resolve(false)
            return
        }
        try {
            promise.resolve(
                permissionAwareActivity.shouldShowRequestPermissionRationale(permission),
            )
        } catch (e: IllegalStateException) {
            promise.reject(ERROR_INVALID_ACTIVITY, e)
        }
    }

    fun checkMultiplePermissions(permissionArr: Array<String>): WritableMap {
        val output: WritableMap = WritableNativeMap()
        for (permission in permissionArr.iterator()) {
            if (permissionNotExists(permission)) {
                output.putString(permission, UNAVAILABLE.status)
            } else if (Build.VERSION.SDK_INT < VERSION_CODES.M) {
                output.putString(
                    permission,
                    if (applicationContext.checkPermission(
                            permission,
                            Process.myPid(),
                            Process.myUid(),
                        ) == PackageManager.PERMISSION_GRANTED
                    ) {
                        GRANTED.status
                    } else {
                        BLOCKED.status
                    },
                )
            } else if (applicationContext.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) {
                output.putString(permission, GRANTED.status)
            } else {
                output.putString(permission, DENIED.status)
            }
        }
        return output
    }

    fun requestMultiplePermissions(permissionArr: Array<String>, promise: Promise?) {
        permissions = permissionArr
        val output: WritableMap = WritableNativeMap()
        val permissionsToCheck = ArrayList<String>()
        var checkedPermissionsCount = 0
        for (permission in permissionArr.iterator()) {
            if (permission.isNotBlank()) {
                if (permissionNotExists(permission)) {
                    output.putString(permission, UNAVAILABLE.status)
                    checkedPermissionsCount++
                } else if (Build.VERSION.SDK_INT < VERSION_CODES.M) {
                    output.putString(
                        permission,
                        if (applicationContext.checkPermission(
                                permission,
                                Process.myPid(),
                                Process.myUid(),
                            ) == PackageManager.PERMISSION_GRANTED
                        ) {
                            GRANTED.status
                        } else {
                            BLOCKED.status
                        },
                    )
                    checkedPermissionsCount++
                } else if (applicationContext.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) {
                    output.putString(permission, GRANTED.status)
                    checkedPermissionsCount++
                } else {
                    permissionsToCheck.add(permission)
                }
            }
        }
        if (permissionArr.size == checkedPermissionsCount) {
            promise?.resolve(output)
            return
        }
        try {
            val activity = permissionAwareActivity
            mCallbacks.put(
                mRequestCode,
                Callback { args: Array<Any> ->
                    val results = args[0] as IntArray
                    val perActivity = args[1] as PermissionAwareActivity
                    for (j in permissionsToCheck.indices) {
                        val permission = permissionsToCheck[j]
                        if (results.isNotEmpty() && results[j] == PackageManager.PERMISSION_GRANTED) {
                            output.putString(permission, GRANTED.status)
                        } else {
                            if (perActivity.shouldShowRequestPermissionRationale(permission)) {
                                output.putString(permission, DENIED.status)
                            } else {
                                output.putString(permission, BLOCKED.status)
                            }
                        }
                    }
                    promise?.resolve(output)
                },
            )
            activity.requestPermissions(permissionsToCheck.toTypedArray(), mRequestCode, this)
            mRequestCode++
        } catch (e: IllegalStateException) {
            promise?.reject(ERROR_INVALID_ACTIVITY, e)
        }
    }

    private fun permissionNotExists(permission: String): Boolean {
        return if (permission.isNotBlank()) {
            val fieldName = permission.removePrefix("android.permission.")
            try {
                permission::class.java.getField(fieldName)
                return true
            } catch (_: Exception) {
                return false
            }
        } else {
            false
        }
    }

    fun checkNotifications(promise: Promise) {
        val output = Arguments.createMap()
        val settings = Arguments.createMap()
        val enabled = NotificationManagerCompat.from(applicationContext).areNotificationsEnabled()
        output.putString("status", if (enabled) GRANTED.status else BLOCKED.status)
        output.putMap("settings", settings)
        promise.resolve(output)
    }

    fun parseAndroidPermissions(permissionsResponse: Map<String, PermissionsResponse>): Bundle {
        return Bundle().apply {
            /*
                combined status is equal:
                granted when all needed permissions have been granted
                denied when all needed permissions have been denied
                undetermined if exist permission with undetermined status
            */
            val permissionsStatus = when {
                permissions.all { permissionsResponse.getValue(it).status == GRANTED.status } -> GRANTED
                permissions.all { permissionsResponse.getValue(it).status == DENIED.status } -> DENIED
                else -> UNDETERMINED
            }

            putString(PermissionsResponse.STATUS_KEY, permissionsStatus.status)
            putString(PermissionsResponse.EXPIRES_KEY, PermissionsResponse.PERMISSION_EXPIRES_NEVER)
            putBoolean(
                PermissionsResponse.CAN_ASK_AGAIN_KEY,
                permissions.all { permissionsResponse.getValue(it).canAskAgain },
            )
            putBoolean(PermissionsResponse.GRANTED_KEY, permissionsStatus == GRANTED)
        }
    }

    companion object {
        @Suppress("SpellCheckingInspection")
        private const val SETTING_NAME = "@RNSNPermissions:NonRequestables"
        private const val ERROR_INVALID_ACTIVITY = "E_INVALID_ACTIVITY"
    }
}