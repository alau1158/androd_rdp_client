//! Placeholder to validate that the IronRDP client session stack cross-compiles
//! for Android, including CredSSP (sspi) and the RDP-UDP transport.
use ironrdp_connector::ClientConnector;
use ironrdp_rdpeudp_tokio::MultitransportBootstrap;
use ironrdp_session::ActiveStage;

#[unsafe(no_mangle)]
pub extern "C" fn dexrdp_client_probe() -> i32 {
    let _ = core::mem::size_of::<ClientConnector>();
    let _ = core::mem::size_of::<ActiveStage>();
    let _ = core::mem::size_of::<MultitransportBootstrap>();
    1
}
