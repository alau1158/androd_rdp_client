package com.freerdp.freerdpcore.utils;

import android.app.Activity;
import android.content.ComponentName;
import android.os.Build;
import android.util.Log;
import android.view.WindowManager;

import java.lang.reflect.Method;

/**
 * Lets an activity capture hardware keys that the system would otherwise consume as
 * global shortcuts -- most importantly the Windows/Super (Meta) key, which opens the
 * DeX app drawer or the Android launcher.
 *
 * <p>Two mechanisms are used, because the platform support arrived in stages:
 * <ul>
 *   <li>Android 16.1 (API 36.1) and later: {@code WindowManager.LayoutParams
 *       .setKeyboardCaptureEnabled(true)}. Requires {@code android.permission
 *       .CAPTURE_KEYBOARD}, which is a normal (install-time) permission.</li>
 *   <li>Samsung devices that have not picked up the platform API: the hidden
 *       {@code SemWindowManager.requestMetaKeyEvent(ComponentName, boolean)} call,
 *       which asks DeX to pass Meta events to the given component instead of
 *       handling them itself.</li>
 * </ul>
 *
 * <p>Capture is tied to the focused window, so it is enabled in
 * {@code onResume()} and disabled again in {@code onPause()}.
 */
public final class KeyboardCapture
{
	private static final String TAG = "DexRdpKeyboardCapture";

	private KeyboardCapture() {}

	public static void setEnabled(Activity activity, boolean enabled)
	{
		if (activity == null)
			return;
		setNativeKeyboardCapture(activity, enabled);
		setSamsungMetaKeyCapture(activity, enabled);
	}

	private static void setNativeKeyboardCapture(Activity activity, boolean enabled)
	{
		// SDK_INT_FULL (API 36.1) is only present on Android 16+. Short-circuit before
		// touching it so older devices never resolve the field.
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.BAKLAVA)
			return;
		if (Build.VERSION.SDK_INT_FULL < Build.VERSION_CODES_FULL.BAKLAVA_1)
			return;

		try
		{
			final WindowManager.LayoutParams params = activity.getWindow().getAttributes();
			if (params.hasKeyboardCapture() == enabled)
				return;
			params.setKeyboardCaptureEnabled(enabled);
			activity.getWindow().setAttributes(params);
			Log.d(TAG, "native keyboard capture -> " + enabled);
		}
		catch (Throwable t)
		{
			// Platform API present but disabled (feature flag off, or permission not
			// granted). The Samsung path below may still work.
			Log.d(TAG, "native keyboard capture unavailable: " + t);
		}
	}

	private static void setSamsungMetaKeyCapture(Activity activity, boolean enabled)
	{
		try
		{
			final Class<?> semWindowManager =
			    Class.forName("com.samsung.android.view.SemWindowManager");
			final Method getInstance = semWindowManager.getMethod("getInstance");
			final Object manager = getInstance.invoke(null);
			if (manager == null)
				return;

			final Method requestMetaKeyEvent = semWindowManager.getDeclaredMethod(
			    "requestMetaKeyEvent", ComponentName.class, boolean.class);
			requestMetaKeyEvent.invoke(manager, activity.getComponentName(), enabled);
			Log.d(TAG, "SemWindowManager.requestMetaKeyEvent -> " + enabled);
		}
		catch (Throwable t)
		{
			// Not a Samsung device, or the API changed. Nothing else to do.
			Log.d(TAG, "SemWindowManager.requestMetaKeyEvent unavailable: " + t);
		}
	}
}
