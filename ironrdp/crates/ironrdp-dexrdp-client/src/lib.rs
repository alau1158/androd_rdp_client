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
use ironrdp_tls::CertificateValidation;
use jni::objects::{GlobalRef, JByteBuffer, JClass, JObject, JString, JValue};
use jni::sys::{jboolean, jint, jlong};
use jni::JNIEnv;
use smallvec::smallvec;
use std::sync::{Once, OnceLock};

static LOG_PATH: OnceLock<String> = OnceLock::new();
static INIT_LOG: Once = Once::new();

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
        let _ = tracing_subscriber::fmt()
            .with_ansi(false)
            .with_writer(FileAppender)
            .with_max_level(tracing::Level::DEBUG)
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
        let client = RdpClient::new(config, output_sender);
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
                    RdpOutputEvent::ConnectionFailure(err) => {
                        report_failure(&mut env, callback_for_thread.as_obj(), &format!("{err}"));
                    }
                    RdpOutputEvent::Terminated(_) => {
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
        // Source is 0x00RRGGBB. Android's ARGB_8888 bitmap stores pixels as
        // RGBA in memory (little-endian), so emit [R, G, B, 0xFF], which is the
        // u32 0xFF_BB_GG_RR: red and blue must be swapped relative to the source.
        let r = (px >> 16) & 0xFF;
        let g = (px >> 8) & 0xFF;
        let b = px & 0xFF;
        dst[i] = 0xFF00_0000 | (b << 16) | (g << 8) | r;
    }
    Ok(())
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
