package com.dexrdp.data

import android.content.Context
import org.json.JSONArray

class ProfileStore(context: Context) {

    private val preferences = context.applicationContext
        .getSharedPreferences("dex_rdp_profiles", Context.MODE_PRIVATE)

    fun load(): MutableList<ConnectionProfile> {
        val raw = preferences.getString(KEY_PROFILES, null) ?: return mutableListOf()
        return try {
            val array = JSONArray(raw)
            MutableList(array.length()) { index ->
                ConnectionProfile.fromJson(array.getJSONObject(index))
            }
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    fun save(profiles: List<ConnectionProfile>) {
        val array = JSONArray()
        profiles.forEach { array.put(it.toJson()) }
        preferences.edit().putString(KEY_PROFILES, array.toString()).apply()
    }

    companion object {
        private const val KEY_PROFILES = "profiles"
    }
}