/*
   Physical Keyboard Router

   This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
   If a copy of the MPL was not distributed with this file, You can obtain one at
   http://mozilla.org/MPL/2.0/.
 */

package com.freerdp.freerdpcore.presentation;

import android.view.KeyEvent;

/**
 * Bridge that lets a session activity outside this library receive hardware key
 * events through {@link KeyboardAccessibilityService}.
 *
 * The service natively forwards only to the FreeRDP {@link SessionActivity}. The
 * bundled IronRDP engine activity lives in the app module, which depends on this
 * library (so this library cannot reference it directly). The activity registers
 * itself here while it is resumed; the service forwards to it first.
 */
public final class PhysicalKeyboardRouter
{
	public interface Target
	{
		boolean handleKeyEvent(KeyEvent event);
	}

	public static volatile Target target;

	private PhysicalKeyboardRouter()
	{
	}
}
