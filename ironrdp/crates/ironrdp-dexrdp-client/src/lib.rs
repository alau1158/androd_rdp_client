//! Android RDP engine built on IronRDP's reusable client library.
//!
//! `RdpClient` already implements the whole connection sequence (TCP, TLS,
//! NLA/CredSSP) plus the RDP-UDP sideband (soft-sync + DVC tunnelling), so this
//! crate is the JNI boundary only: it drives the session and marshals frames and
//! input between IronRDP and Kotlin.
//!
//! Threading: the session and the output pump each own a dedicated thread with a
//! current-thread Tokio runtime. `RdpClient::run()` is awaited with `block_on`
//! rather than spawned, which avoids a higher-ranked `Send` limitation, and the
//! JNI environment is attached once per thread.

use ironrdp_client::config::{ConfigBuilder, Destination};
use ironrdp_client::output_channel::output_channel;
use ironrdp_client::rdp::{RdpClient, RdpInputEvent, RdpInputSender, RdpOutputEvent};
use ironrdp_pdu::input::fast_path::{FastPathInputEvent, KeyboardFlags};
use ironrdp_pdu::input::mouse::{MousePdu, PointerFlags};
use ironrdp_pdu::input::mouse_x::{MouseXPdu, PointerXFlags};
use ironrdp_pdu::rdp::capability_sets::MajorPlatformType;
use ironrdp_pdu::rdp::client_info::{OptionalSystemTime, TimezoneInfo};
use ironrdp_tls::CertificateValidation;
use jni::objects::{GlobalRef, JByteBuffer, JClass, JObject, JString, JValue};
use jni::sys::{jboolean, jint, jlong};
use jni::JNIEnv;
use smallvec::smallvec;
use std::sync::{Once, OnceLock};

static LOG_PATH: OnceLock<String> = OnceLock::new();
static INIT_LOG: Once = Once::new();

/// Default tracing filter. `info` overall, with the connection and RDP-UDP
/// transport crates at `debug` so the multitransport negotiation stays visible,
/// while the per-bitmap session and graphics decoders are quieted. Without this,
/// the fast-path DEBUG flood fills the in-app log tail (last 400 lines) before
/// the UDP bootstrap result can be read.
const DEFAULT_LOG_FILTER: &str = "info,\
dexrdp_client=debug,\
ironrdp_connector=debug,\
ironrdp_client=debug,\
ironrdp_rdpeudp=debug,\
ironrdp_rdpeudp_tokio=debug,\
ironrdp_rdpemt=debug,\
ironrdp_dvc=debug,\
ironrdp_session=debug,\
ironrdp_egfx=debug,\
ironrdp_graphics=warn,\
ironrdp_echo=warn";

/// Appends tracing output to the log file shared with the rest of the app.
struct FileAppender;

impl<'a> tracing_subscriber::fmt::MakeWriter<'a> for FileAppender {
    type Writer = std::fs::File;
    fn make_writer(&'a self) -> Self::Writer {
        let path = LOG_PATH.get().map(String::as_str).unwrap_or("/dev/null");
        std::fs::OpenOptions::new()
            .create(true)
            .append(true)
            .open(path)
            .or_else(|_| std::fs::File::open("/dev/null"))
            .unwrap_or_else(|_| unreachable!())
    }
}

extern "C" fn dexrdp_crash_handler(sig: libc::c_int) {
    use std::io::Write;
    if let Some(path) = LOG_PATH.get() {
        if let Ok(mut f) = std::fs::OpenOptions::new().create(true).append(true).open(path) {
            let _ = writeln!(f, "\n==== NATIVE CRASH signal {sig} ====");
            let bt = backtrace::Backtrace::new();
            let _ = writeln!(f, "{bt:?}");
            let _ = f.flush();
        }
    }
    unsafe {
        libc::signal(sig, libc::SIG_DFL);
        libc::raise(sig);
    }
}

fn install_crash_handlers() {
    unsafe {
        libc::signal(libc::SIGSEGV, dexrdp_crash_handler as usize);
        libc::signal(libc::SIGABRT, dexrdp_crash_handler as usize);
        libc::signal(libc::SIGBUS, dexrdp_crash_handler as usize);
        libc::signal(libc::SIGILL, dexrdp_crash_handler as usize);
    }
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_com_dexrdp_engine_NativeRdp_nativeSetLogPath(
    mut env: JNIEnv,
    _cls: JClass,
    path: JString,
) {
    let path = jstring_to_string(&mut env, &path);
    let _ = LOG_PATH.set(path);
    INIT_LOG.call_once(|| {
        let filter = tracing_subscriber::EnvFilter::try_from_default_env()
            .unwrap_or_else(|_| tracing_subscriber::EnvFilter::new(DEFAULT_LOG_FILTER));
        let _ = tracing_subscriber::fmt()
            .with_ansi(false)
            .with_writer(FileAppender)
            .with_env_filter(filter)
            .try_init();
        install_crash_handlers();
    });
}

struct Session {
    input: RdpInputSender,
}

fn jstring_to_string(env: &mut JNIEnv, s: &JString) -> String {
    env.get_string(s)
        .map(|v| v.to_string_lossy().into_owned())
        .unwrap_or_default()
}

/// Derive the client's current time-zone offset from the C library so the remote
/// Windows session is not left on UTC.
///
/// The connector otherwise sends `TimezoneInfo::default()` (bias 0). We advertise
/// the offset in effect *now*, with no DST transition table: Windows reapplies the
/// client time zone on every connect, so a reconnect after a DST change corrects it.
fn local_timezone_info() -> TimezoneInfo {
    let now = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_secs() as libc::time_t)
        .unwrap_or(0);

    let mut tm: libc::tm = unsafe { std::mem::zeroed() };
    if unsafe { libc::localtime_r(&now, &mut tm) }.is_null() {
        return TimezoneInfo::default();
    }

    // RDP `bias` is "UTC = local + bias" in minutes; tm_gmtoff is seconds east.
    let bias = -((tm.tm_gmtoff / 60) as i32);
    let name = if tm.tm_zone.is_null() {
        String::new()
    } else {
        unsafe { std::ffi::CStr::from_ptr(tm.tm_zone) }
            .to_string_lossy()
            .into_owned()
    };

    TimezoneInfo {
        bias,
        standard_name: name.clone(),
        standard_date: OptionalSystemTime(None),
        standard_bias: 0,
        daylight_name: name,
        daylight_date: OptionalSystemTime(None),
        daylight_bias: 0,
    }
}

fn report_failure(env: &mut JNIEnv, callback: &JObject, message: &str) {
    if let Ok(msg) = env.new_string(message) {
        let _ = env.call_method(
            callback,
            "onFailure",
            "(Ljava/lang/String;)V",
            &[JValue::Object(&msg)],
        );
    }
}

/// Start a session. `frame_buffer` is a direct ByteBuffer owned by Kotlin, sized
/// for `width * height` ARGB pixels; the engine writes frames into it.
/// Returns an opaque handle, or 0 on failure (after reporting via `callback`).
#[unsafe(no_mangle)]
pub extern "C" fn Java_com_dexrdp_engine_NativeRdp_nativeConnect(
    mut env: JNIEnv,
    _cls: JClass,
    host: JString,
    port: jint,
    username: JString,
    password: JString,
    domain: JString,
    width: jint,
    height: jint,
    frame_buffer: JByteBuffer,
    callback: JObject,
) -> jlong {
    let host = jstring_to_string(&mut env, &host);
    let username = jstring_to_string(&mut env, &username);
    let password = jstring_to_string(&mut env, &password);
    let domain = jstring_to_string(&mut env, &domain);

    tracing::info!("nativeConnect host={host} port={port} size={width}x{height} user={username}");

    let callback_ref: GlobalRef = match env.new_global_ref(&callback) {
        Ok(r) => r,
        Err(_) => return 0,
    };

    let outcome = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| -> Result<jlong, String> {
        let destination = Destination::new(format!("{host}:{port}"))
            .map_err(|e| format!("bad destination: {e}"))?;

        let mut builder = ConfigBuilder::new()
            .with_destination(destination)
            .with_username(username)
            .with_password(password)
            .with_desktop_width(width as u16)
            .with_desktop_height(height as u16)
            .with_tls(true)
            .with_credssp(true)
            .with_udp_transport(true)
            .with_client_build(1)
            .with_client_dir(r"C:\")
            .with_client_name("DeX RDP")
            .with_platform(MajorPlatformType::ANDROID)
            .with_pointer_software_rendering(true)
            // Advertise RNS_UD_CS_SUPPORT_DYNVC_GFX_PROTOCOL so Windows opens the
            // RDPGFX DVC. Without it the server keeps graphics on the legacy TCP
            // path and there is no graphics channel for RDP multitransport
            // Soft-Sync to move onto the UDP tunnel.
            .with_graphics_pipeline(true)
            .with_timezone_info(local_timezone_info())
            .with_certificate_validation(CertificateValidation::DangerouslyAcceptInvalidCertificate);

        if !domain.is_empty() {
            builder = builder.with_domain(domain);
        }

        let config = builder.build().map_err(|e| format!("config: {e}"))?;
        tracing::info!("engine config built ok");

        let frame_buffer = env
            .new_global_ref(frame_buffer)
            .map_err(|e| format!("frame buffer ref: {e}"))?;
        let vm = env.get_java_vm().map_err(|e| format!("java vm: {e}"))?;

        let (output_sender, mut output_receiver) = output_channel(64);
        // Deliver only the changed regions. The default full-`Image` mode repacks
        // and colour-converts the entire 4K framebuffer on every update (an 8.3M
        // pixel scan plus a fresh ~33 MB allocation per frame), which pins the
        // phone's CPU and triggers constant GC. Dirty regions keep the per-update
        // cost proportional to what actually changed.
        let client = RdpClient::new(config, output_sender).with_desktop_updates();
        let input = client.input_sender();

        // Session thread: connect + run (includes the UDP sideband).
        std::thread::spawn(move || {
            let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
                match tokio::runtime::Builder::new_current_thread().enable_all().build() {
                    Ok(rt) => rt.block_on(client.run()),
                    Err(e) => tracing::error!("session runtime build failed: {e}"),
                }
            }));
            if result.is_err() {
                tracing::error!("session thread panicked");
            }
            tracing::info!("session thread ended");
        });

        // Output pump thread: forwards frames and lifecycle events to Kotlin.
        let vm_for_frames = vm;
        let callback_for_thread = callback_ref.clone();
        std::thread::spawn(move || {
            let mut env = match vm_for_frames.attach_current_thread_permanently() {
                Ok(env) => env,
                Err(_) => return,
            };
            let pump = match tokio::runtime::Builder::new_current_thread()
                .enable_all()
                .build()
            {
                Ok(rt) => rt,
                Err(_) => return,
            };

            while let Some(event) = pump.block_on(output_receiver.recv()) {
                match event {
                    RdpOutputEvent::Connected => {
                        let _ = env.call_method(callback_for_thread.as_obj(), "onConnected", "()V", &[]);
                    }
                    RdpOutputEvent::Image {
                        buffer,
                        width,
                        height,
                    } => {
                        let _ = write_frame(&mut env, &frame_buffer, &buffer, width.get(), height.get());
                        let _ = env.call_method(
                            callback_for_thread.as_obj(),
                            "onFrame",
                            "(Ljava/nio/ByteBuffer;II)V",
                            &[
                                JValue::Object(frame_buffer.as_obj()),
                                JValue::Int(width.get() as i32),
                                JValue::Int(height.get() as i32),
                            ],
                        );
                    }
                    RdpOutputEvent::DesktopUpdate(update) => {
                        let (pixels, width, height, region) = update.into_parts();
                        let _ = write_frame_region(
                            &mut env,
                            &frame_buffer,
                            &pixels,
                            width.get(),
                            height.get(),
                            region.left,
                            region.top,
                            region.right,
                            region.bottom,
                        );
                        let _ = env.call_method(
                            callback_for_thread.as_obj(),
                            "onFrame",
                            "(Ljava/nio/ByteBuffer;II)V",
                            &[
                                JValue::Object(frame_buffer.as_obj()),
                                JValue::Int(width.get() as i32),
                                JValue::Int(height.get() as i32),
                            ],
                        );
                    }
                    RdpOutputEvent::ConnectionFailure(err) => {
                        tracing::error!(%err, "connection failure");
                        report_failure(&mut env, callback_for_thread.as_obj(), &format!("{err}"));
                    }
                    RdpOutputEvent::Terminated(result) => {
                        match &result {
                            Ok(reason) => tracing::warn!("session terminated gracefully: {reason:?}"),
                            Err(error) => tracing::warn!("session terminated with error: {error}"),
                        }
                        let _ = env.call_method(callback_for_thread.as_obj(), "onTerminated", "()V", &[]);
                        break;
                    }
                    _ => {}
                }
            }
        });

        Ok(Box::into_raw(Box::new(Session { input })) as jlong)
    }));

    match outcome {
        Ok(Ok(handle)) => handle,
        Ok(Err(message)) => {
            report_failure(&mut env, callback_ref.as_obj(), &message);
            0
        }
        Err(_) => {
            report_failure(&mut env, callback_ref.as_obj(), "panic in nativeConnect");
            0
        }
    }
}

fn write_frame(
    env: &mut JNIEnv,
    frame_buffer: &GlobalRef,
    pixels: &[u32],
    width: u16,
    height: u16,
) -> Result<(), ()> {
    let buf = unsafe { JByteBuffer::from_raw(frame_buffer.as_raw()) };
    let capacity = env.get_direct_buffer_capacity(&buf).map_err(|_| ())?;
    let addr = unsafe { env.get_direct_buffer_address(&buf) }.map_err(|_| ())?;
    if addr.is_null() {
        return Err(());
    }

    let expected = (width as usize).saturating_mul(height as usize);
    let count = pixels.len().min(expected).min(capacity / 4);
    let dst = unsafe { std::slice::from_raw_parts_mut(addr as *mut u32, count) };
    for (i, px) in pixels.iter().take(count).enumerate() {
        dst[i] = to_argb(px);
    }
    Ok(())
}

/// Blit one changed region into the Kotlin-owned framebuffer.
///
/// `pixels` is the tightly packed `0x00RRGGBB` region (row-major, `region_w *
/// region_h` entries) delivered by [`RdpOutputEvent::DesktopUpdate`]. The region
/// uses inclusive coordinates in the full framebuffer, so only those rows and
/// columns are touched; the rest of the direct buffer keeps the pixels written by
/// earlier updates. The caller still invokes `onFrame` with the full buffer, so
/// the Kotlin side stays unchanged.
#[expect(
    clippy::too_many_arguments,
    reason = "the region coordinates are plain scalars taken apart from the update"
)]
fn write_frame_region(
    env: &mut JNIEnv,
    frame_buffer: &GlobalRef,
    pixels: &[u32],
    fb_width: u16,
    fb_height: u16,
    left: u16,
    top: u16,
    right: u16,
    bottom: u16,
) -> Result<(), ()> {
    if right < left || bottom < top {
        return Err(());
    }

    let buf = unsafe { JByteBuffer::from_raw(frame_buffer.as_raw()) };
    let capacity = env.get_direct_buffer_capacity(&buf).map_err(|_| ())?;
    let addr = unsafe { env.get_direct_buffer_address(&buf) }.map_err(|_| ())?;
    if addr.is_null() {
        return Err(());
    }

    let fb_w = fb_width as usize;
    let fb_h = fb_height as usize;
    let left = left as usize;
    let top = top as usize;
    let region_w = (right as usize) + 1 - left;
    let region_h = (bottom as usize) + 1 - top;

    let cap_px = (capacity / 4).min(fb_w.saturating_mul(fb_h));
    if region_w == 0 || region_h == 0 || left + region_w > fb_w || top + region_h > fb_h {
        return Err(());
    }

    let dst = unsafe { std::slice::from_raw_parts_mut(addr as *mut u32, cap_px) };
    let needed = region_w.saturating_mul(region_h);
    if pixels.len() < needed {
        return Err(());
    }

    for y in 0..region_h {
        let dst_row = (top + y) * fb_w + left;
        if dst_row + region_w > cap_px {
            break;
        }
        let src_row = y * region_w;
        for x in 0..region_w {
            dst[dst_row + x] = to_argb(&pixels[src_row + x]);
        }
    }
    Ok(())
}

/// Convert an IronRDP `0x00RRGGBB` pixel to the ARGB_8888 memory layout Android
/// expects. Android stores RGBA in memory (little-endian), so emit
/// `[R, G, B, 0xFF]`, the u32 `0xFF_BB_GG_RR`: red and blue are swapped.
#[inline]
fn to_argb(px: &u32) -> u32 {
    let r = (px >> 16) & 0xFF;
    let g = (px >> 8) & 0xFF;
    let b = px & 0xFF;
    0xFF00_0000 | (b << 16) | (g << 8) | r
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_com_dexrdp_engine_NativeRdp_nativeSendKey(
    _env: JNIEnv,
    _cls: JClass,
    handle: jlong,
    scancode: jint,
    down: jboolean,
    extended: jboolean,
) {
    if handle == 0 {
        return;
    }
    let session = unsafe { &*(handle as *const Session) };
    let mut flags = if down != 0 {
        KeyboardFlags::empty()
    } else {
        KeyboardFlags::RELEASE
    };
    if extended != 0 {
        flags |= KeyboardFlags::EXTENDED;
    }
    let event = FastPathInputEvent::KeyboardEvent(flags, scancode as u8);
    let _ = session
        .input
        .try_send(RdpInputEvent::FastPath(smallvec![event]));
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_com_dexrdp_engine_NativeRdp_nativeSendMouse(
    _env: JNIEnv,
    _cls: JClass,
    handle: jlong,
    x: jint,
    y: jint,
    flags: jint,
    wheel_units: jint,
) {
    if handle == 0 {
        return;
    }
    let session = unsafe { &*(handle as *const Session) };
    let pdu = MousePdu {
        flags: PointerFlags::from_bits_truncate(flags as u16),
        number_of_wheel_rotation_units: wheel_units as i16,
        x_position: x as u16,
        y_position: y as u16,
    };
    let event = FastPathInputEvent::MouseEvent(pdu);
    let _ = session
        .input
        .try_send(RdpInputEvent::FastPath(smallvec![event]));
}

/// Extended mouse event, carrying the X1/X2 side buttons (back/forward).
#[unsafe(no_mangle)]
pub extern "C" fn Java_com_dexrdp_engine_NativeRdp_nativeSendMouseEx(
    _env: JNIEnv,
    _cls: JClass,
    handle: jlong,
    x: jint,
    y: jint,
    xflags: jint,
) {
    if handle == 0 {
        return;
    }
    let session = unsafe { &*(handle as *const Session) };
    let pdu = MouseXPdu {
        flags: PointerXFlags::from_bits_truncate(xflags as u16),
        x_position: x as u16,
        y_position: y as u16,
    };
    let event = FastPathInputEvent::MouseEventEx(pdu);
    let _ = session
        .input
        .try_send(RdpInputEvent::FastPath(smallvec![event]));
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_com_dexrdp_engine_NativeRdp_nativeDisconnect(
    _env: JNIEnv,
    _cls: JClass,
    handle: jlong,
) {
    if handle == 0 {
        return;
    }
    let session = unsafe { &*(handle as *const Session) };
    session.input.request_close();
}

#[unsafe(no_mangle)]
pub extern "C" fn Java_com_dexrdp_engine_NativeRdp_nativeFree(
    _env: JNIEnv,
    _cls: JClass,
    handle: jlong,
) {
    if handle == 0 {
        return;
    }
    let session = unsafe { Box::from_raw(handle as *mut Session) };
    drop(session);
}
