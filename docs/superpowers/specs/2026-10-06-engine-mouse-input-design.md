# Engine mouse input: full parity + back/forward side buttons

Date: 2026-10-06
Status: implemented

## Problem

The IronRDP engine ("Connect (UDP engine)") had minimal mouse support: only
left-click and move. Right-click, middle-click, scroll, and the mouse's
back/forward side buttons did nothing. The side buttons were worse than
nothing — Android turned them into a system Back, which closed the session.

The FreeRDP engine ("Connect") already handled left/right/middle and scroll
correctly, so this work brings the engine to parity and adds the side buttons
to both the motion-event and key-event delivery paths.

## Scope

In scope: right-click, middle-click, back/forward side buttons, vertical and
horizontal scroll, hover/drag, and correct view→desktop coordinate mapping.

Out of scope: the FreeRDP engine's side buttons (its `SessionView` silently
consumes unknown buttons; wiring those would need a new extended-mouse JNI
call). Exiting the session stays as it is — the user closes the DeX window or
disconnects from Windows, so no exit control is added.

## Design

### Android event → RDP mapping

| Android | RDP |
|---|---|
| `ACTION_DOWN` / `ACTION_UP` | `LEFT_BUTTON` (+`DOWN` on press) |
| `ACTION_MOVE` | `MOVE` \| currently-held buttons (`buttonState`) |
| `ACTION_HOVER_MOVE` | `MOVE` |
| `ACTION_BUTTON_PRESS/RELEASE` `BUTTON_SECONDARY` | `RIGHT_BUTTON` (+`DOWN`) |
| `BUTTON_TERTIARY` | `MIDDLE_BUTTON` (+`DOWN`) |
| `BUTTON_BACK` / `BUTTON_FORWARD` | X1 / X2 (`MouseXPdu`) |
| `KEYCODE_BACK` / `KEYCODE_FORWARD` | X1 / X2 (`MouseXPdu`) |
| `ACTION_SCROLL` `AXIS_VSCROLL` | `VERTICAL_WHEEL`, units ±`WHEEL_DELTA` |
| `ACTION_SCROLL` `AXIS_HSCROLL` | `HORIZONTAL_WHEEL`, units ±`WHEEL_DELTA` |

Wheel sign matches the FreeRDP engine: `vScroll > 0` → negative units;
`hScroll > 0` → positive units. One notch is 120 rotation units.

Side buttons are handled on both paths because Android surfaces them
inconsistently: as `MotionEvent.BUTTON_BACK/FORWARD` or as a
`KEYCODE_BACK/FORWARD` key event. Key events are intercepted in `onKeyDown`,
`onKeyUp`, and `handleKeyEvent` (the accessibility-service route). `KEYCODE_BACK`
is intercepted unconditionally while a session is active, because the user does
not use Back to exit and this guarantees the mouse button is captured.

### Coordinates

`RemoteView` stretches the framebuffer to fill the view, so view pixels map
linearly to desktop pixels: `x * desktopWidth / view.width` (clamped). The last
known position is retained for X-button key events, which carry no coordinates.

### Native surface

`libdexrdp_client` gains two JNI calls:

- `nativeSendMouse(handle, x, y, flags, wheelUnits)` → `MousePdu`
  (IronRDP derives the `WHEEL_NEGATIVE` bit from the signed units).
- `nativeSendMouseEx(handle, x, y, xflags)` → `FastPathInputEvent::MouseEventEx(MouseXPdu)`.

`MouseFlags.kt` holds the `PTRFLAGS_*` / `PTRXFLAGS_*` constants, mirroring the
FreeRDP engine's `Mouse.java`.

## Testing

Manual on-device: right-click context menu, back/forward in a remote browser or
Explorer, middle-click, vertical and horizontal scroll, drag. Rust `cargo check`
and the existing `MousePdu` wheel round-trip tests cover encoding.
