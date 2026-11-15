package com.voicenote.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices

/**
 * Registers the hardcoded stores in Stores.ALL as ENTER-only geofences
 * with Play Services. Idempotent — call whenever permissions land or
 * after boot. Silently no-ops if FINE_LOCATION isn't granted.
 */
object GeofenceRegistrar {

    private const val TAG = "GeofenceRegistrar"

    fun register(context: Context) {
        if (Stores.ALL.isEmpty()) return
        if (!hasFineLocation(context)) {
            Log.i(TAG, "ACCESS_FINE_LOCATION not granted; skipping geofence register")
            return
        }

        val geofences = Stores.ALL.map { store ->
            Geofence.Builder()
                .setRequestId(store.id)
                .setCircularRegion(store.latitude, store.longitude, store.radiusMeters)
                .setExpirationDuration(Geofence.NEVER_EXPIRE)
                .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER)
                .build()
        }

        val request = GeofencingRequest.Builder()
            .setInitialTrigger(0) // don't fire immediately if we're already inside a fence
            .addGeofences(geofences)
            .build()

        registerInternal(context, request)
    }

    fun unregister(context: Context) {
        LocationServices.getGeofencingClient(context)
            .removeGeofences(pendingIntent(context))
            .addOnSuccessListener { Log.i(TAG, "Geofences removed") }
            .addOnFailureListener { Log.w(TAG, "Failed to remove geofences: ${it.message}") }
    }

    @SuppressLint("MissingPermission") // gated by hasFineLocation
    private fun registerInternal(context: Context, request: GeofencingRequest) {
        LocationServices.getGeofencingClient(context)
            .addGeofences(request, pendingIntent(context))
            .addOnSuccessListener {
                Log.i(TAG, "Registered ${Stores.ALL.size} geofence(s)")
            }
            .addOnFailureListener {
                Log.w(TAG, "Failed to register geofences: ${it.message}")
            }
    }

    private fun pendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, GeofenceReceiver::class.java)
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        return PendingIntent.getBroadcast(context, 0, intent, flags)
    }

    private fun hasFineLocation(context: Context): Boolean =
        ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
}
