//! C ABI shim exposing IronRDP's RDP-UDP (multitransport) transport.
//!
//! FreeRDP owns the TCP/RDP session; this library performs only the UDP
//! sideband: the RDPEUDP2 handshake, TLS, and the RDPEMT tunnel, then exposes
//! the resulting reliable byte stream over a small C API. Logging is routed to
//! Android logcat via the `log` crate so it lands in the same place as the rest
//! of the app's diagnostics.

use std::net::SocketAddr;
use std::panic::{AssertUnwindSafe, catch_unwind};
use std::sync::Once;

use ironrdp_pdu::rdp::headers::{BasicSecurityHeader, BasicSecurityHeaderFlags};
use ironrdp_pdu::rdp::multitransport::{MultitransportRequestPdu, RequestedProtocol};
use ironrdp_rdpeudp::ConnectionConfig;
use ironrdp_rdpeudp_tokio::transport::{UdpTlsConfig, UdpTransport};
use ironrdp_rdpeudp_tokio::MultitransportBootstrap;

struct UdpHandle {
    runtime: tokio::runtime::Runtime,
    transport: UdpTransport,
    response: Vec<u8>,
}

static INIT_LOGGING: Once = Once::new();

fn init_crypto() {
    INIT_LOGGING.call_once(|| {
        // rustls needs a process-default CryptoProvider; with the `ring`
        // feature the provider is available but not installed automatically.
        let _ = tokio_rustls::rustls::crypto::ring::default_provider().install_default();
    });
}

fn set_err(err: *mut std::os::raw::c_char, err_len: usize, message: &str) {
    if err.is_null() || err_len == 0 {
        return;
    }
    let bytes = message.as_bytes();
    let n = bytes.len().min(err_len - 1);
    unsafe {
        std::ptr::copy_nonoverlapping(bytes.as_ptr(), err as *mut u8, n);
        *((err as *mut u8).add(n)) = 0;
    }
}

fn cstr(ptr: *const std::os::raw::c_char) -> Option<String> {
    if ptr.is_null() {
        return None;
    }
    unsafe { std::ffi::CStr::from_ptr(ptr) }
        .to_str()
        .ok()
        .map(|s| s.to_owned())
}

fn slice<'a>(ptr: *const u8, len: usize) -> Option<&'a [u8]> {
    if ptr.is_null() || len == 0 {
        return None;
    }
    Some(unsafe { std::slice::from_raw_parts(ptr, len) })
}

/// Establish the UDP sideband from the fields of the Initiate Multitransport
/// Request PDU that FreeRDP already parsed.
///
/// `cookie` must point at 16 bytes. `server_addr` is `ip:port` for the UDP
/// peer. Returns an opaque handle, or null on failure with `err` filled in.
#[unsafe(no_mangle)]
pub extern "C" fn dexrdp_udp_connect_fields(
    request_id: u32,
    requested_protocol: u16,
    cookie: *const u8,
    server_addr: *const std::os::raw::c_char,
    server_name: *const std::os::raw::c_char,
    err: *mut std::os::raw::c_char,
    err_len: usize,
) -> *mut std::os::raw::c_void {
    init_crypto();

    let result = catch_unwind(AssertUnwindSafe(|| {
        let cookie_slice =
            slice(cookie, 16).ok_or_else(|| "missing security cookie".to_owned())?;
        let mut security_cookie = [0u8; 16];
        security_cookie.copy_from_slice(cookie_slice);

        let protocol = match requested_protocol {
            0x0001 => RequestedProtocol::UdpFecR,
            0x0002 => RequestedProtocol::UdpFecL,
            other => return Err(format!("unsupported requested protocol 0x{other:04X}")),
        };

        let request = MultitransportRequestPdu {
            security_header: BasicSecurityHeader {
                flags: BasicSecurityHeaderFlags::TRANSPORT_REQ,
            },
            request_id,
            requested_protocol: protocol,
            security_cookie,
        };

        let addr_str = cstr(server_addr).ok_or_else(|| "missing server address".to_owned())?;
        let name = cstr(server_name).unwrap_or_else(|| {
            addr_str
                .rsplit_once(':')
                .map(|(host, _)| host.to_owned())
                .unwrap_or_else(|| addr_str.clone())
        });
        let addr: SocketAddr = addr_str
            .parse()
            .map_err(|e| format!("bad server address '{addr_str}': {e}"))?;

        let mut bootstrap = MultitransportBootstrap::new(request);

        let runtime = tokio::runtime::Builder::new_multi_thread()
            .enable_all()
            .worker_threads(2)
            .build()
            .map_err(|e| format!("tokio runtime: {e}"))?;

        let tls = UdpTlsConfig::new(name.clone());
        runtime
            .block_on(bootstrap.connect(addr, name, ConnectionConfig::default(), tls))
            .map_err(|e| format!("udp connect: {e}"))?;

        let transport = bootstrap
            .take_transport()
            .ok_or_else(|| "transport missing after successful connect".to_owned())?;

        let response = bootstrap
            .response_pdu()
            .ok_or_else(|| "no multitransport response produced".to_owned())?;

        let handle = Box::new(UdpHandle {
            runtime,
            transport,
            response,
        });
        Ok::<*mut std::os::raw::c_void, String>(Box::into_raw(handle) as *mut std::os::raw::c_void)
    }));

    match result {
        Ok(Ok(ptr)) => ptr,
        Ok(Err(message)) => {
            set_err(err, err_len, &message);
            std::ptr::null_mut()
        }
        Err(_) => {
            set_err(err, err_len, "panic during udp connect");
            std::ptr::null_mut()
        }
    }
}

/// Copy the multitransport response PDU (to be sent over the TCP connection).
/// Returns the number of bytes written, or -1.
#[unsafe(no_mangle)]
pub extern "C" fn dexrdp_udp_response(
    handle: *mut std::os::raw::c_void,
    out: *mut u8,
    out_len: usize,
) -> i32 {
    if handle.is_null() || out.is_null() {
        return -1;
    }
    let h = unsafe { &*(handle as *const UdpHandle) };
    if h.response.len() > out_len {
        return -1;
    }
    unsafe {
        std::ptr::copy_nonoverlapping(h.response.as_ptr(), out, h.response.len());
    }
    h.response.len() as i32
}

/// Send tunneled data over the reliable UDP stream. Returns bytes sent or -1.
#[unsafe(no_mangle)]
pub extern "C" fn dexrdp_udp_send(
    handle: *mut std::os::raw::c_void,
    data: *const u8,
    len: usize,
) -> i32 {
    if handle.is_null() {
        return -1;
    }
    let h = unsafe { &mut *(handle as *mut UdpHandle) };
    let Some(payload) = slice(data, len) else {
        return -1;
    };
    let owned = payload.to_vec();

    match catch_unwind(AssertUnwindSafe(|| h.runtime.block_on(h.transport.send(owned)))) {
        Ok(Ok(())) => len as i32,
        _ => -1,
    }
}

/// Receive tunneled data. Returns bytes received, 0 on timeout/no data, or -1.
#[unsafe(no_mangle)]
pub extern "C" fn dexrdp_udp_recv(
    handle: *mut std::os::raw::c_void,
    buf: *mut u8,
    len: usize,
    timeout_ms: i32,
) -> i32 {
    if handle.is_null() || buf.is_null() || len == 0 {
        return -1;
    }
    let h = unsafe { &mut *(handle as *mut UdpHandle) };
    let wait = std::time::Duration::from_millis(timeout_ms.max(0) as u64);

    let result = catch_unwind(AssertUnwindSafe(|| {
        h.runtime.block_on(async {
            match tokio::time::timeout(wait, h.transport.recv()).await {
                Ok(Some(data)) => Some(data),
                _ => None,
            }
        })
    }));

    match result {
        Ok(Some(data)) => {
            let n = data.len().min(len);
            unsafe {
                std::ptr::copy_nonoverlapping(data.as_ptr(), buf, n);
            }
            n as i32
        }
        Ok(None) => 0,
        Err(_) => -1,
    }
}

/// Close and free the transport.
#[unsafe(no_mangle)]
pub extern "C" fn dexrdp_udp_close(handle: *mut std::os::raw::c_void) {
    if handle.is_null() {
        return;
    }
    let h = unsafe { Box::from_raw(handle as *mut UdpHandle) };
    let _ = catch_unwind(AssertUnwindSafe(|| {
        h.runtime.block_on(h.transport.shutdown());
    }));
}

/// Version string, useful for confirming the library loaded.
#[unsafe(no_mangle)]
pub extern "C" fn dexrdp_udp_version() -> *const std::os::raw::c_char {
    concat!("dexrdp-udp 0.1 (", env!("CARGO_PKG_VERSION"), ")\0").as_ptr()
        as *const std::os::raw::c_char
}
