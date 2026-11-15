package com.voicenote.app

/**
 * Hardcoded geofence targets. Coordinates are approximate — the 400-yard
 * radius (~366 m) is forgiving enough that ±100 m won't matter, but if a
 * geofence doesn't fire when you arrive at a store, long-press its pin in
 * Google Maps and paste the coords that appear at the top over the values
 * here.
 *
 * The `id` doubles as the geofence request id and as the Prefs cooldown
 * key, so keep it stable across edits.
 */
data class Store(
    val id: String,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val radiusMeters: Float = 366f,   // ~400 yards
)

object Stores {
    val ALL: List<Store> = listOf(
        // ---- Kailua ------------------------------------------------------
        // Foodland Kailua — 108 Hekili St, Kailua, HI 96734
        Store("foodland_kailua",      "Foodland Kailua",           21.3935, -157.7413),
        // Whole Foods Kailua — 629 Kailua Rd, Kailua, HI 96734
        Store("whole_foods_kailua",   "Whole Foods Kailua",        21.3925, -157.7392),
        // Safeway Kailua — 200 Hamakua Dr, Kailua, HI 96734
        Store("safeway_kailua",       "Safeway Kailua",            21.3966, -157.7404),
        // Down to Earth Kailua — Lau Hala Shops, 573 Kailua Rd, Kailua, HI 96734
        Store("down_to_earth_kailua", "Down to Earth Kailua",      21.3922, -157.7388),
        // Target Kailua — 345 Hahani St, Kailua, HI 96734 (has full grocery section)
        Store("target_kailua",        "Target Kailua",             21.3945, -157.7408),

        // ---- Kaneohe -----------------------------------------------------
        // Foodland Kaneohe (Windward City Shopping Center) — 45-480 Kaneohe Bay Dr, Kaneohe, HI 96744
        Store("foodland_kaneohe",     "Foodland Kaneohe",          21.4108, -157.7993),
        // Times Supermarket Kaneohe — 45-934 Kamehameha Hwy, Kaneohe, HI 96744
        Store("times_kaneohe",        "Times Supermarket Kaneohe", 21.4103, -157.7996),
        // Times Supermarket Koolau — 47-388 Hui Iwa St, Kaneohe, HI 96744 (north Kaneohe)
        Store("times_koolau",         "Times Supermarket Koolau",  21.4337, -157.8054),
    )

    const val COOLDOWN_MS: Long = 30 * 60 * 1000L
}
