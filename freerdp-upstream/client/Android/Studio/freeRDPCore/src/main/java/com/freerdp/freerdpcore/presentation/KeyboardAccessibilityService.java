/*
   Physical Keyboard Accessibility Service

   Copyright 2026 Ibrahim Sevinc <ibrahim.sevinc.mail@gmail.com>

   This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
   If a copy of the MPL was not distributed with this file, You can obtain one at
   http://mozilla.org/MPL/2.0/.
 */

package com.freerdp.freerdpcore.presentation;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

public class KeyboardAccessibilityService extends AccessibilityService
{
	/*
	 * Auto-repeat. Android emits repeated ACTION_DOWN events with a non-zero
	 * repeat count for a held key, but those repeats do not reliably reach the
	 * session through the accessibility key-filtering pipeline. Synthesising the
	 * repeat here — the single point every key passes through — guarantees that
	 * holding Backspace, an arrow key, etc. repeats on the remote, for both the
	 * FreeRDP session and the IronRDP engine.
	 */
	private static final long REPEAT_DELAY_MS = 700;
	private static final long REPEAT_INTERVAL_MS = 55;

	private final Handler repeatHandler = new Handler(Looper.getMainLooper());
	private KeyEvent repeatingEvent;
	private boolean repeatingToTarget;

	private final Runnable repeatRunnable = new Runnable() {
		@Override public void run()
		{
			final KeyEvent event = repeatingEvent;
			if (event == null)
				return;

			final KeyEvent repeat =
			    KeyEvent.changeTimeRepeat(event, SystemClock.uptimeMillis(), event.getRepeatCount() + 1);
			if (!dispatch(repeat, repeatingToTarget))
			{
				stopRepeat();
				return;
			}
			repeatingEvent = repeat;
			repeatHandler.postDelayed(this, REPEAT_INTERVAL_MS);
		}
	};

	@Override public boolean onKeyEvent(KeyEvent event)
	{
		InputDevice device = event.getDevice();
		if (device != null &&
		    (device.getSources() & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD)
			return super.onKeyEvent(event);

		switch (event.getKeyCode())
		{
			case KeyEvent.KEYCODE_VOLUME_UP:
			case KeyEvent.KEYCODE_VOLUME_DOWN:
			case KeyEvent.KEYCODE_POWER:
				return super.onKeyEvent(event);
		}

		/* The IronRDP engine activity registers itself here while resumed. */
		final PhysicalKeyboardRouter.Target target = PhysicalKeyboardRouter.target;
		final boolean toTarget = target != null;
		if (!toTarget && SessionActivity.activeSession == null)
			return super.onKeyEvent(event);

		/* Android's own repeat events for the key we are synthesising are
		   dropped here rather than forwarded (and possibly duplicated). */
		if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() > 0)
		{
			if (repeatingEvent != null && repeatingEvent.getKeyCode() == event.getKeyCode())
				return true;
		}

		/*
		 * Stop on the key-up regardless of whether the session "handles" it. The
		 * FreeRDP KeyboardMapper reports non-Meta key-ups as unhandled, so keying
		 * the stop off dispatch()'s result let the repeat outlive the key and
		 * inject extra characters after release.
		 */
		if (event.getAction() == KeyEvent.ACTION_UP)
		{
			if (repeatingEvent != null && repeatingEvent.getKeyCode() == event.getKeyCode())
				stopRepeat();
		}

		if (!dispatch(event, toTarget))
			return false;

		if (event.getAction() == KeyEvent.ACTION_DOWN && isRepeatable(event.getKeyCode()))
			startRepeat(event, toTarget);

		return true;
	}

	private boolean dispatch(KeyEvent event, boolean toTarget)
	{
		if (toTarget)
		{
			PhysicalKeyboardRouter.Target target = PhysicalKeyboardRouter.target;
			return target != null && target.handleKeyEvent(event);
		}

		SessionActivity session = SessionActivity.activeSession;
		if (session == null)
			return false;

		/* Some tablets map the physical ESC key to KEYCODE_BACK (scancode 1).*/
		if (event.getScanCode() == 1 && event.getKeyCode() != KeyEvent.KEYCODE_ESCAPE)
		{
			event =
			    new KeyEvent(event.getDownTime(), event.getEventTime(), event.getAction(),
			                 KeyEvent.KEYCODE_ESCAPE, event.getRepeatCount(), event.getMetaState());
		}

		return session.handleKeyEvent(event);
	}

	private void startRepeat(KeyEvent event, boolean toTarget)
	{
		stopRepeat();
		repeatingEvent = event;
		repeatingToTarget = toTarget;
		repeatHandler.postDelayed(repeatRunnable, REPEAT_DELAY_MS);
	}

	private void stopRepeat()
	{
		repeatHandler.removeCallbacks(repeatRunnable);
		repeatingEvent = null;
	}

	/* Modifier and lock keys do not auto-repeat. */
	private static boolean isRepeatable(int keyCode)
	{
		switch (keyCode)
		{
			case KeyEvent.KEYCODE_SHIFT_LEFT:
			case KeyEvent.KEYCODE_SHIFT_RIGHT:
			case KeyEvent.KEYCODE_CTRL_LEFT:
			case KeyEvent.KEYCODE_CTRL_RIGHT:
			case KeyEvent.KEYCODE_ALT_LEFT:
			case KeyEvent.KEYCODE_ALT_RIGHT:
			case KeyEvent.KEYCODE_META_LEFT:
			case KeyEvent.KEYCODE_META_RIGHT:
			case KeyEvent.KEYCODE_CAPS_LOCK:
			case KeyEvent.KEYCODE_NUM_LOCK:
			case KeyEvent.KEYCODE_SCROLL_LOCK:
			case KeyEvent.KEYCODE_FUNCTION:
				return false;
			default:
				return true;
		}
	}

	@Override public void onServiceConnected()
	{
		AccessibilityServiceInfo info = new AccessibilityServiceInfo();
		info.packageNames = new String[] { getApplicationContext().getPackageName() };
		info.eventTypes = AccessibilityEvent.TYPES_ALL_MASK;
		info.notificationTimeout = 100;
		info.flags = AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS;
		info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
		setServiceInfo(info);
	}

	@Override public void onAccessibilityEvent(AccessibilityEvent event)
	{
	}

	@Override public void onInterrupt()
	{
		stopRepeat();
	}
}
