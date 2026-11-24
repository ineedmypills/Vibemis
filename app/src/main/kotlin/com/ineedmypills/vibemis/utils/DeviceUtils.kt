package com.ineedmypills.vibemis.utils

import android.Manifest.permission.ACCESS_WIFI_STATE
import android.Manifest.permission.CHANGE_WIFI_STATE
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.content.res.Resources
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import android.telephony.TelephonyManager
import android.text.TextUtils
import androidx.annotation.RequiresPermission
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketException

object DeviceUtils {
    @JvmStatic
    fun isDeviceRooted(): Boolean {
        val su = "su"
        val locations = arrayOf(
            "/system/bin/", "/system/xbin/", "/sbin/", "/system/sd/xbin/",
            "/system/bin/failsafe/", "/data/local/xbin/", "/data/local/bin/", "/data/local/",
            "/system/sbin/", "/usr/bin/", "/vendor/bin/"
        )
        return locations.any { File(it + su).exists() }
    }

    @JvmStatic
    fun isAdbEnabled(context: Context): Boolean =
        Settings.Secure.getInt(context.contentResolver, Settings.Global.ADB_ENABLED, 0) > 0

    @JvmStatic
    fun getSDKVersionName(): String = Build.VERSION.RELEASE

    @JvmStatic
    fun getSDKVersionCode(): Int = Build.VERSION.SDK_INT

    @JvmStatic
    @SuppressLint("HardwareIds")
    fun getAndroidID(context: Context): String {
        val id = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        if ("9774d56d682e549c" == id) return ""
        return id ?: ""
    }

    @JvmStatic
    @RequiresPermission(allOf = [ACCESS_WIFI_STATE, CHANGE_WIFI_STATE])
    fun getMacAddress(context: Context): String {
        val macAddress = getMacAddress(context, *emptyArray())
        if (!TextUtils.isEmpty(macAddress) || getWifiEnabled(context)) return macAddress
        setWifiEnabled(context, true)
        setWifiEnabled(context, false)
        return getMacAddress(context, *emptyArray())
    }

    private fun getWifiEnabled(context: Context): Boolean {
        @SuppressLint("WifiManagerLeak")
        val manager = context.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return false
        return manager.isWifiEnabled
    }

    @Suppress("DEPRECATION")
    @RequiresPermission(CHANGE_WIFI_STATE)
    private fun setWifiEnabled(context: Context, enabled: Boolean) {
        @SuppressLint("WifiManagerLeak")
        val manager = context.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
        if (enabled == manager.isWifiEnabled) return
        manager.isWifiEnabled = enabled
    }

    @JvmStatic
    @RequiresPermission(allOf = [ACCESS_WIFI_STATE])
    fun getMacAddress(context: Context, vararg excepts: String?): String {
        var macAddress = getMacAddressByNetworkInterface()
        if (isAddressNotInExcepts(macAddress, *excepts)) return macAddress
        macAddress = getMacAddressByInetAddress()
        if (isAddressNotInExcepts(macAddress, *excepts)) return macAddress
        macAddress = getMacAddressByWifiInfo(context)
        if (isAddressNotInExcepts(macAddress, *excepts)) return macAddress
        return ""
    }

    private fun isAddressNotInExcepts(address: String, vararg excepts: String?): Boolean {
        if (TextUtils.isEmpty(address) || "02:00:00:00:00:00" == address) return false
        if (excepts.isEmpty()) return true
        return excepts.none { it != null && it == address }
    }

    @Suppress("DEPRECATION")
    @RequiresPermission(ACCESS_WIFI_STATE)
    private fun getMacAddressByWifiInfo(context: Context): String {
        try {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val info = wifi?.connectionInfo
            if (info != null) {
                @SuppressLint("HardwareIds")
                val macAddress = info.macAddress
                if (!TextUtils.isEmpty(macAddress)) return macAddress
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return "02:00:00:00:00:00"
    }

    private fun getMacAddressByNetworkInterface(): String {
        try {
            val nis = NetworkInterface.getNetworkInterfaces()
            while (nis.hasMoreElements()) {
                val ni = nis.nextElement() ?: continue
                if (!ni.name.equals("wlan0", ignoreCase = true)) continue
                val macBytes = ni.hardwareAddress
                if (macBytes != null && macBytes.isNotEmpty()) {
                    return macBytes.joinToString(":") { "%02x".format(it) }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return "02:00:00:00:00:00"
    }

    private fun getMacAddressByInetAddress(): String {
        try {
            val inetAddress = getInetAddress()
            if (inetAddress != null) {
                val ni = NetworkInterface.getByInetAddress(inetAddress)
                val macBytes = ni?.hardwareAddress
                if (macBytes != null && macBytes.isNotEmpty()) {
                    return macBytes.joinToString(":") { "%02x".format(it) }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return "02:00:00:00:00:00"
    }

    private fun getInetAddress(): InetAddress? {
        try {
            val nis = NetworkInterface.getNetworkInterfaces()
            while (nis.hasMoreElements()) {
                val ni = nis.nextElement()
                if (!ni.isUp) continue
                val addresses = ni.inetAddresses
                while (addresses.hasMoreElements()) {
                    val inetAddress = addresses.nextElement()
                    if (!inetAddress.isLoopbackAddress) {
                        val hostAddress = inetAddress.hostAddress
                        if (hostAddress != null && hostAddress.indexOf(':') < 0) return inetAddress
                    }
                }
            }
        } catch (e: SocketException) {
            e.printStackTrace()
        }
        return null
    }

    @JvmStatic
    fun getManufacturer(): String = Build.MANUFACTURER

    @JvmStatic
    fun getModel(): String = Build.MODEL?.trim()?.replace("\\s*".toRegex(), "") ?: ""

    @JvmStatic
    fun getABIs(): Array<String> = Build.SUPPORTED_ABIS

    @JvmStatic
    fun isTablet(): Boolean =
        (Resources.getSystem().configuration.screenLayout and Configuration.SCREENLAYOUT_SIZE_MASK) >=
                Configuration.SCREENLAYOUT_SIZE_LARGE

    @JvmStatic
    fun isEmulator(context: Context): Boolean {
        val checkProperty = Build.FINGERPRINT.startsWith("generic") ||
                Build.FINGERPRINT.lowercase().contains("vbox") ||
                Build.FINGERPRINT.lowercase().contains("test-keys") ||
                Build.MODEL.contains("google_sdk") ||
                Build.MODEL.contains("Emulator") ||
                Build.MODEL.contains("Android SDK built for x86") ||
                Build.MANUFACTURER.contains("Genymotion") ||
                (Build.BRAND.startsWith("generic") && Build.DEVICE.startsWith("generic")) ||
                "google_sdk" == Build.PRODUCT
        if (checkProperty) return true

        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        val operatorName = tm?.networkOperatorName ?: ""
        if (operatorName.lowercase() == "android") return true

        val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:123456"))
        if (intent.resolveActivity(context.packageManager) == null) return true
        return isEmulatorByCpu()
    }

    private fun isEmulatorByCpu(): Boolean {
        val cpuInfo = readCpuInfo()
        return cpuInfo.contains("intel") || cpuInfo.contains("amd")
    }

    @JvmStatic
    fun readCpuInfo(): String {
        var result = ""
        try {
            val process = ProcessBuilder("/system/bin/cat", "/proc/cpuinfo").start()
            BufferedReader(InputStreamReader(process.inputStream, "utf-8")).use { reader ->
                val sb = StringBuilder()
                var readLine: String?
                while (reader.readLine().also { readLine = it } != null) {
                    sb.append(readLine)
                }
                result = sb.toString().lowercase()
            }
        } catch (_: IOException) {
        }
        return result
    }

    @JvmStatic
    fun isDevelopmentSettingsEnabled(context: Context): Boolean =
        Settings.Global.getInt(context.contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) > 0
}
