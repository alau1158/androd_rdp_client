package com.freerdp.freerdpcore.utils;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Persists the outcome of the last connection attempt so that the launcher
 * screen can display it after the session activity has already been closed.
 */
public class Diagnostics
{
	private static final String KEY_LAST_ERROR = "diagnostics.last_error";
	private static final String KEY_LAST_AT = "diagnostics.last_at";
	private static final String KEY_LAST_TARGET = "diagnostics.last_target";

	public static void recordConnectFailure(Context context, String detail)
	{
		recordConnectFailure(context, "", detail);
	}

	public static void recordConnectFailure(Context context, String target, String detail)
	{
		SharedPreferences preferences =
		    PreferenceManager.getDefaultSharedPreferences(context.getApplicationContext());

		preferences.edit()
		    .putString(KEY_LAST_ERROR, (detail == null || detail.isEmpty()) ? "Unknown error" : detail)
		    .putString(KEY_LAST_AT,
		               new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()))
		    .putString(KEY_LAST_TARGET, target == null ? "" : target)
		    .apply();
	}

	/** Updates only the reason, preserving the target recorded by the launcher. */
	public static void recordFailureReason(Context context, String detail)
	{
		SharedPreferences preferences =
		    PreferenceManager.getDefaultSharedPreferences(context.getApplicationContext());

		preferences.edit()
		    .putString(KEY_LAST_ERROR, (detail == null || detail.isEmpty()) ? "Unknown error" : detail)
		    .putString(KEY_LAST_AT,
		               new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()))
		    .apply();
	}

	public static String getLastError(Context context)
	{
		return PreferenceManager.getDefaultSharedPreferences(context.getApplicationContext())
		    .getString(KEY_LAST_ERROR, "");
	}

	public static String getLastErrorAt(Context context)
	{
		return PreferenceManager.getDefaultSharedPreferences(context.getApplicationContext())
		    .getString(KEY_LAST_AT, "");
	}

	public static String getLastTarget(Context context)
	{
		return PreferenceManager.getDefaultSharedPreferences(context.getApplicationContext())
		    .getString(KEY_LAST_TARGET, "");
	}

	public static void clear(Context context)
	{
		PreferenceManager.getDefaultSharedPreferences(context.getApplicationContext())
		    .edit()
		    .remove(KEY_LAST_ERROR)
		    .remove(KEY_LAST_AT)
		    .remove(KEY_LAST_TARGET)
		    .apply();
	}
}