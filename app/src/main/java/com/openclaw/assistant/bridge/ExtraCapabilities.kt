package com.openclaw.assistant.bridge

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.wifi.WifiManager
import androidx.core.content.ContextCompat
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Capabilities beyond the minimal trio in [DefaultCapabilities]. Each one
 * advertises itself via [isAvailable] so the manifest only shows what the
 * device can actually run; missing runtime permissions are surfaced via
 * `unsupported_capability` rather than fake failures.
 */
object WifiStatusCapability : BridgeCapability {
    override val name = "wifi.status"
    override val description = "Read Wi-Fi enabled state"
    override val group = "device"
    override val riskLevel = RiskLevel.LOW
    override suspend fun execute(context: Context, arguments: JsonObject): JsonObject {
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        return buildJsonObject {
            put("wifiEnabled", wm?.isWifiEnabled == true)
        }
    }
}

object LocationGetCapability : BridgeCapability {
    override val name = "location.get"
    override val description = "Best-effort last known coarse location"
    override val group = "device"
    override val riskLevel = RiskLevel.MEDIUM
    override val requiresPermissions = listOf(Manifest.permission.ACCESS_COARSE_LOCATION)
    override fun isAvailable(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    // Location permissions are not declared in the CTB manifest (Spec 001 §C):
    // isAvailable() is always false and the SecurityException path is dead.
    @android.annotation.SuppressLint("MissingPermission")
    override suspend fun execute(context: Context, arguments: JsonObject): JsonObject {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        val loc = try {
            lm?.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                ?: lm?.getLastKnownLocation(LocationManager.GPS_PROVIDER)
        } catch (_: SecurityException) { null }
        return buildJsonObject {
            if (loc == null) put("available", false)
            else {
                put("available", true)
                put("latitude", loc.latitude); put("longitude", loc.longitude)
                put("accuracyMeters", loc.accuracy.toDouble()); put("provider", loc.provider ?: "")
            }
        }
    }
}

object AppsLaunchCapability : BridgeCapability {
    override val name = "apps.launch"
    override val description = "Launch an installed app by package name"
    override val group = "apps"
    override val riskLevel = RiskLevel.MEDIUM
    override val inputSchema = buildJsonObject {
        put("type", "object")
        put("required", buildJsonArray { add(JsonPrimitive("packageName")) })
    }
    override suspend fun execute(context: Context, arguments: JsonObject): JsonObject {
        val pkg = (arguments["packageName"] as? kotlinx.serialization.json.JsonPrimitive)?.content
            ?: return buildJsonObject { put("launched", false); put("reason", "missing packageName") }
        if (MobileBridgeConfig.getInstance(context).isPackageBlocked(pkg)) {
            return buildJsonObject { put("launched", false); put("reason", "package is in user blocklist") }
        }
        val intent = context.packageManager.getLaunchIntentForPackage(pkg)
        if (intent == null) return buildJsonObject { put("launched", false); put("reason", "not installed") }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return buildJsonObject { put("launched", true); put("packageName", pkg) }
    }
}

/** Replaces [DefaultCapabilities] when ExtraCapabilities are wired in. */
object AllCapabilities {
    val all: List<BridgeCapability> = DefaultCapabilities.all + listOf(
        WifiStatusCapability,
        LocationGetCapability,
        AppsLaunchCapability,
    )
}
