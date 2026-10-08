extern crate notify;

use std::collections::HashMap;
use std::ffi::CStr;
use std::fmt::Debug;
use std::io::Write;
use std::path::Path;
use std::sync::mpsc::{Receiver, RecvTimeoutError, channel};
use std::sync::{Arc, LockResult, RwLock, RwLockWriteGuard};
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use libssh_rs::{KnownHosts, PublicKeyHashType, SignAlgorithm, SshKey, SshOption, ssh_sign};

#[allow(unused_imports)]
use libssh_rs::AuthStatus;
use notify::event::{CreateKind, RemoveKind};
use notify::{
    Config, Error, ErrorKind, Event, EventKind, RecommendedWatcher, RecursiveMode, Watcher,
};

uniffi::setup_scaffolding!();

#[derive(uniffi::Object)]
struct FileWatcher {
    keep_watching: RwLock<bool>,
    watcher: RwLock<Option<WatcherHolder>>,
    receiver: RwLock<Option<ReceiverHolder>>,
}

struct WatcherHolder {
    watcher: Box<dyn Watcher>,
}

struct ReceiverHolder {
    receiver: Receiver<notify::Result<Event>>,
}

unsafe impl Send for WatcherHolder {}
unsafe impl Sync for WatcherHolder {}
unsafe impl Send for ReceiverHolder {}
unsafe impl Sync for ReceiverHolder {}

impl Drop for FileWatcher {
    fn drop(&mut self) {
        println!("File watcher dropped!");
    }
}

#[derive(uniffi::Record, Debug, Clone, Eq, PartialEq, Hash)]
pub struct FileChanged {
    path: String,
    file_type: FileType,
}

#[derive(uniffi::Enum, Debug, Clone, Eq, PartialEq, Hash)]
pub enum FileType {
    File,
    Directory,
}

#[uniffi::export]
impl FileWatcher {
    fn init(&self) -> i32 {
        println!("initializing file watcher");

        // Create a channel to receive the events.
        let (sender, receiver) = channel();

        // Create a watcher object, delivering debounced events.
        // The notification back-end is selected based on the platform.
        let config = Config::default();
        config.with_poll_interval(Duration::from_secs(3600));

        let watcher = RecommendedWatcher::new(sender, config);

        match watcher {
            Ok(watcher) => {
                let mut watcher_holder = self.watcher.write().unwrap();
                let mut receiver_holder = self.receiver.write().unwrap();

                *watcher_holder = Some(WatcherHolder {
                    watcher: Box::new(watcher),
                });
                *receiver_holder = Some(ReceiverHolder { receiver });
                0
            }
            Err(e) => {
                // TODO Hardcoded nums should be changed to an enum or sth similar once Kotars supports them
                let code = error_to_code(e.kind);
                code
            }
        }
    }

    fn watch(&self, notifier: Box<dyn WatchDirectoryNotifier>) {
        let receiver = self.receiver.read().unwrap();

        let receiver = match receiver.as_ref() {
            None => {
                println!("Receiver not initialized");
                return;
            }
            Some(receiver) => &receiver.receiver,
        };

        let mut paths_cached = HashMap::<FileChanged, Vec<EventKind>>::new();

        let mut last_update: u128 = 0;

        while notifier.should_keep_looping() {
            match receiver.recv_timeout(Duration::from_millis(WATCH_TIMEOUT)) {
                Ok(e) => {
                    if let Some(paths) = get_paths_from_event_result(&e) {
                        let paths_without_dirs: Vec<FileChangeEvent> = paths.into_iter().collect();

                        for path in paths_without_dirs.into_iter() {
                            let is_dir = is_directory_event(&path.event_kind);
                            let file_type = if is_dir {
                                FileType::Directory
                            } else {
                                FileType::File
                            };

                            let file_changed = FileChanged {
                                path: path.path,
                                file_type,
                            };

                            match paths_cached.get_mut(&file_changed) {
                                Some(v) => v.push(path.event_kind),
                                None => {
                                    paths_cached.insert(file_changed, vec![path.event_kind]);
                                }
                            }
                        }

                        let current_time = current_time_as_millis();

                        if last_update != 0
                            && current_time - last_update > MIN_TIME_IN_MS_BETWEEN_REFRESHES
                        {
                            process_paths_cached(&mut paths_cached, &notifier);
                            last_update = current_time_as_millis();
                        }
                    }
                }
                Err(e) => match e {
                    RecvTimeoutError::Timeout => {
                        process_paths_cached(&mut paths_cached, &notifier);
                        last_update = current_time_as_millis();
                    }
                    RecvTimeoutError::Disconnected => {
                        println!("Watch error: {:?}", e);
                    }
                },
            };
        }

        // // TODO If unwatch fails it's probably because we no longer have access to it. We probably don't care about it but double check in the future
        // let _ = watcher.unwatch(Path::new(path.as_str()));

        println!("Watch finishing...");
    }

    fn add_watch(&self, path: String, is_recursive: bool) -> i32 {
        let mut watcher_holder = self.watcher.write().unwrap();
        let watcher = match watcher_holder.as_mut() {
            None => {
                println!("Watcher not initialized");
                return 1; // TODO Provide better error
            }
            Some(watcher) => &mut watcher.watcher,
        };

        // Add a path to be watched. All files and directories at that path and
        // below will be monitored for changes.

        let recursive_mode = if is_recursive {
            RecursiveMode::Recursive
        } else {
            RecursiveMode::NonRecursive
        };

        let res = watcher.watch(Path::new(path.as_str()), recursive_mode);

        if let Err(e) = res {
            // TODO Hardcoded nums should be changed to an enum or sth similar once Kotars supports them
            error_to_code(e.kind)
        } else {
            0
        }
    }

    fn remove_watch(&self, path: String) -> i32 {
        println!("Removing watch: {path}");
        let mut watcher_holder = self.watcher.write().unwrap();
        let watcher = match watcher_holder.as_mut() {
            None => {
                println!("Watcher not initialized");
                return 1; // TODO Provide better error
            }
            Some(watcher) => &mut watcher.watcher,
        };

        // Add a path to be watched. All files and directories at that path and
        // below will be monitored for changes.
        let res = watcher.unwatch(Path::new(path.as_str()));

        if let Err(e) = res {
            // TODO Hardcoded nums should be changed to an enum or sth similar once Kotars supports them
            error_to_code(e.kind)
        } else {
            0
        }
    }
    #[uniffi::constructor]
    fn new() -> FileWatcher {
        FileWatcher {
            keep_watching: RwLock::from(true),
            watcher: RwLock::from(None),
            receiver: RwLock::from(None),
        }
    }

    fn stop_watching(&self) {
        println!("Keep watching set to false");
        *self.keep_watching.write().unwrap() = false
    }
}

fn remove_temporary_files(changes: &mut HashMap<FileChanged, Vec<EventKind>>) -> Vec<FileChanged> {
    let paths: Vec<FileChanged> = changes
        .iter()
        .filter_map(|(key, value)| {
            let index_created = value
                .iter()
                .position(|v| matches!(v, EventKind::Create(_)));

            let index_removed = value
                .iter()
                .position(|v| matches!(v, EventKind::Remove(_)));

            // If a file has been created and removed before passing it to kotlin,  filter it out,
            // we don't care about it as it's a temporary file.
            // If a file has been first removed and then created, then it shouldn't be marked as
            // temporary file.
            if let (Some(index_created), Some(index_removed)) = (index_created, index_removed)
                && index_created < index_removed
            {
                println!(
                    "Removing entry {} as it looks like a temporary file.",
                    key.path
                );
                None
            } else {
                Some(key.clone())
            }
        })
        .collect();

    paths
}

fn is_directory_event(kind: &EventKind) -> bool {
    match kind {
        EventKind::Create(CreateKind::Folder) | EventKind::Remove(RemoveKind::Folder) => true,
        _ => false,
    }
}

fn process_paths_cached(
    paths_cached: &mut HashMap<FileChanged, Vec<EventKind>>,
    notifier: &Box<dyn WatchDirectoryNotifier>,
) {
    let paths_to_send: Vec<FileChanged> = remove_temporary_files(paths_cached);
    paths_cached.clear();

    if !paths_to_send.is_empty() {
        println!(
            "Sending a total of {} paths cached to Kotlin side",
            paths_to_send.len()
        );
        notifier.detected_change(paths_to_send);
    }
}

fn current_time_as_millis() -> u128 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .expect("We need a TARDIS to fix this")
        .as_millis()
}

const MIN_TIME_IN_MS_BETWEEN_REFRESHES: u128 = 500;
const WATCH_TIMEOUT: u64 = 500;

fn error_to_code(error_kind: ErrorKind) -> i32 {
    match error_kind {
        ErrorKind::Generic(_) => 1,
        ErrorKind::Io(_) => 2,
        ErrorKind::PathNotFound => 3,
        ErrorKind::WatchNotFound => 4,
        ErrorKind::InvalidConfig(_) => 5,
        ErrorKind::MaxFilesWatch => 6,
    }
}

pub fn get_paths_from_event_result(
    event_result: &Result<Event, Error>,
) -> Option<Vec<FileChangeEvent>> {
    match event_result {
        Ok(event) => match event.kind {
            EventKind::Create(_) | EventKind::Modify(_) | EventKind::Remove(_) => {
                let events: Vec<FileChangeEvent> = get_event_paths(event);

                if events.is_empty() {
                    None
                } else {
                    Some(events)
                }
            }
            _ => None,
        },
        Err(err) => {
            println!("{:?}", err);
            None
        }
    }
}

fn get_event_paths(event: &Event) -> Vec<FileChangeEvent> {
    event
        .paths
        .clone()
        .into_iter()
        .filter_map(|path| {
            let path_str = path.into_os_string().into_string().ok()?;

            let file_change_event = FileChangeEvent {
                path: path_str,
                event_kind: event.kind,
            };

            Some(file_change_event)
        })
        .collect()
}

pub struct FileChangeEvent {
    pub path: String,
    pub event_kind: EventKind,
}

#[uniffi::export(callback_interface)]
pub trait WatchDirectoryNotifier: Send + Sync + Debug {
    fn should_keep_looping(&self) -> bool;
    fn detected_change(&self, paths: Vec<FileChanged>);
    fn on_error(&self, code: i32);
}

const ACCEPTED_SSH_TYPES: &str = "ssh-ed25519,ecdsa-sha2-nistp256,ecdsa-sha2-nistp384,ecdsa-sha2-nistp521,ssh-rsa,rsa-sha2-512,rsa-sha2-256,ssh-dss";

#[derive(uniffi::Object)]
pub struct Session {
    session_holder: Option<SessionHolder>,
}

pub struct SessionHolder {
    pub session: RwLock<libssh_rs::Session>,
}

#[uniffi::export]
impl Session {
    #[uniffi::constructor]
    pub fn new() -> Session {
        let session = libssh_rs::Session::new().unwrap();

        let session_holder = SessionHolder {
            session: RwLock::new(session),
        };

        Session {
            session_holder: Some(session_holder),
        }
    }

    /// Connects to `host`. `known_hosts_file` replaces the user's known_hosts file (`UserKnownHostsFile`), for tests.
    pub fn setup(
        &self,
        host: String,
        user: String,
        port: Option<i32>,
        known_hosts_file: Option<String>,
    ) -> String {
        let session_holder = self.session_holder.as_ref().unwrap();
        let session = match session_holder.session.write() {
            Ok(s) => s,
            Err(e) => {
                return format!("Something failed obtaining write session: {e:?}");
            }
        };

        if let Err(e) = session.set_option(SshOption::Hostname(host)) {
            let message = libssh_error_to_message(&e);
            return format!("SSH Hostname option failed: {message}");
        }

        if !user.is_empty() {
            if let Err(e) = session.set_option(SshOption::User(Some(user))) {
                let message = libssh_error_to_message(&e);
                return format!("SSH User option failed: {message}");
            }
        }

        if let Some(port) = port {
            if let Err(e) = session.set_option(SshOption::Port(port as u16)) {
                let message = libssh_error_to_message(&e);
                return format!("SSH Port option failed: {message}");
            }
        }

        if let Err(e) = session.set_option(SshOption::PublicKeyAcceptedTypes(
            ACCEPTED_SSH_TYPES.to_string(),
        )) {
            let message = libssh_error_to_message(&e);
            return format!("SSH Public keys option failed: {message}");
        }

        if let Err(e) = session.options_parse_config(None) {
            let message = libssh_error_to_message(&e);
            return format!("SSH Configuration parsing failed: {message}");
        }

        // After the config, which could set it too
        if let Some(file) = known_hosts_file {
            if let Err(e) = session.set_option(SshOption::KnownHosts(Some(file))) {
                let message = libssh_error_to_message(&e);
                return format!("SSH known hosts option failed: {message}");
            }
        }

        if let Err(e) = session.connect() {
            let message = libssh_error_to_message(&e);
            return format!("Server connection failed: {message}");
        }

        String::new()
    }

    /// Compares the server's host key with the known_hosts files. Called after `setup`, before authenticating.
    pub fn check_host_key(&self) -> HostKeyCheck {
        let failed = |fingerprint: String, error: String| HostKeyCheck {
            state: HostKeyState::Failed,
            fingerprint,
            error,
        };

        let session_holder = self.session_holder.as_ref().unwrap();
        let session = match session_holder.session.write() {
            Ok(s) => s,
            Err(e) => {
                return failed(String::new(), format!("Something failed obtaining write session: {e:?}"));
            }
        };

        let fingerprint = match server_key_fingerprint(&session) {
            Ok(fingerprint) => fingerprint,
            Err(error) => return failed(String::new(), error),
        };

        let state = match session.is_known_server() {
            Ok(KnownHosts::Ok) => HostKeyState::Known,
            Ok(KnownHosts::Unknown) | Ok(KnownHosts::NotFound) => HostKeyState::Unknown,
            Ok(KnownHosts::Changed) => HostKeyState::Changed,
            Ok(KnownHosts::Other) => HostKeyState::OtherType,
            Err(e) => return failed(fingerprint, libssh_error_to_message(&e)),
        };

        HostKeyCheck {
            state,
            fingerprint,
            error: String::new(),
        }
    }

    /// Adds the server's host key to the user's known_hosts file, as ssh does once the user trusts it.
    pub fn accept_host_key(&self) -> String {
        let session_holder = self.session_holder.as_ref().unwrap();
        let session = match session_holder.session.write() {
            Ok(s) => s,
            Err(e) => {
                return format!("Something failed obtaining write session: {e:?}");
            }
        };

        match session.update_known_hosts_file() {
            Ok(_) => String::new(),
            Err(e) => {
                let message = libssh_error_to_message(&e);
                format!("Could not add the host key to known_hosts: {message}")
            }
        }
    }

    pub fn public_key_auth(&self, password: String) -> i32 {
        //AuthStatus {
        println!("Public key auth");
        let session_holder = self.session_holder.as_ref().unwrap();
        let session = match session_holder.session.write() {
            Ok(s) => s,
            Err(e) => {
                println!("Something failed obtaining write session: {e:?}");
                return -1;
            }
        };

        let status = match session.userauth_public_key_auto(None, Some(&password)) {
            Ok(s) => s,
            Err(e) => {
                let message = libssh_error_to_message(&e);
                println!("Something failed when using public key auto auth: {message}");
                return -2;
            }
        };

        println!("Status is {status:?}");

        to_int(status) // TODO remove this cast
    }

    pub fn password_auth(&self, password: String) -> i32 {
        //AuthStatus {
        let session_holder = self.session_holder.as_ref().unwrap();
        let session = match session_holder.session.write() {
            Ok(s) => s,
            Err(e) => {
                println!("Something failed obtaining write session: {e:?}");
                return -1;
            }
        };

        let status = match session.userauth_password(None, Some(&password)) {
            Ok(s) => s,
            Err(e) => {
                let message = libssh_error_to_message(&e);
                println!("An error occurred when using user auth with password: {message}");
                return -2;
            }
        };
        to_int(status) // TODO remove this cast
    }

    pub fn disconnect(&self) {
        let session_holder = self.session_holder.as_ref().unwrap();
        match session_holder.session.write() {
            Ok(session) => session.disconnect(),
            Err(e) => println!("Session disconnection failed due to: {e:#?}"),
        };
    }
}

pub struct ChannelHolder {
    channel: RwLock<libssh_rs::Channel>,
}

unsafe impl Send for ChannelHolder {}
unsafe impl Sync for ChannelHolder {}

#[derive(uniffi::Object)]
pub struct Channel {
    channel: Option<ChannelHolder>,
}

#[uniffi::export]
impl Channel {
    #[uniffi::constructor]
    pub fn new(session: Arc<Session>) -> Channel {
        let session_holder = session.as_ref().session_holder.as_ref().unwrap();
        let session = session_holder.session.read().unwrap();
        let channel = session.new_channel().unwrap();

        let channel_holder = ChannelHolder {
            channel: RwLock::new(channel),
        };

        Channel {
            channel: Some(channel_holder),
        }
    }

    pub fn open_session(&self) -> String {
        let channel_holder = self.channel.as_ref().unwrap();
        let channel = match channel_holder.channel.write() {
            Ok(c) => c,
            Err(e) => return format!("{e:#}"),
        };

        if let Err(e) = channel.open_session() {
            let message = libssh_error_to_message(&e);
            return format!("Channel open session failed: {message}");
        };

        String::new()
    }

    pub fn is_open(&self) -> bool {
        let channel_holder = self.channel.as_ref().unwrap();
        let channel = match channel_holder.channel.write() {
            Ok(s) => s,
            Err(e) => {
                println!("Something failed obtaining write channel: {e:?}");
                return false;
            }
        };

        channel.is_open()
    }

    pub fn close_channel(&self) -> String {
        let channel_holder = self.channel.as_ref().unwrap();
        let channel = match channel_holder.channel.write() {
            Ok(s) => s,
            Err(e) => {
                return format!("Something failed obtaining write channel: {e:?}");
            }
        };

        match channel.close() {
            Ok(_) => String::new(),
            Err(e) => {
                let message = libssh_error_to_message(&e);
                format!("Channel closing failed: {message}")
            }
        }
    }

    pub fn request_exec(&self, command: String) -> String {
        let channel_holder = self.channel.as_ref().unwrap();
        let channel = match channel_holder.channel.write() {
            Ok(s) => s,
            Err(e) => {
                return format!("Something failed obtaining write channel: {e:?}");
            }
        };

        match channel.request_exec(&command) {
            Ok(_) => String::new(),
            Err(e) => {
                let message = libssh_error_to_message(&e);
                format!("Channel request exec failed: {message}")
            }
        }
    }

    /// Reads what has already arrived, without waiting for more. A read count of 0 means that nothing has arrived
    /// yet, or that the stream has ended (see `is_eof`).
    ///
    /// libssh-rs's `poll_timeout` can't be used to wait for data: it passes `is_stderr` and the timeout to
    /// `ssh_channel_poll_timeout` in the wrong order, so it polls stderr for at most 1 ms whichever stream is asked.
    pub fn read_available(&self, is_stderr: bool, len: u64) -> Option<ReadResult> {
        let channel = match self.get_channel() {
            Ok(c) => c,
            Err(e) => {
                println!("Something failed obtaining write channel: {e:?}");
                return None;
            }
        };

        let mut buffer = vec![0; len as usize];
        let read = match channel.read_nonblocking(&mut buffer, is_stderr) {
            Ok(s) => s,
            Err(e) => {
                let message = libssh_error_to_message(&e);
                println!("Something failed reading SSH channel: {message}");
                return None;
            }
        };

        Some(ReadResult {
            read_count: read as u64,
            data: buffer,
        })
    }

    /// Whether the server has ended its output, and everything it sent on both streams has been read.
    pub fn is_eof(&self) -> bool {
        match self.get_channel() {
            Ok(channel) => channel.is_eof(),
            Err(e) => {
                println!("Something failed obtaining write channel: {e:?}");
                true
            }
        }
    }

    /// The command's exit status, or -1 if the server closed the channel without one. Waits until the server sends
    /// it.
    pub fn exit_status(&self) -> i32 {
        match self.get_channel() {
            Ok(channel) => channel.get_exit_status().unwrap_or(-1),
            Err(e) => {
                println!("Something failed obtaining write channel: {e:?}");
                -1
            }
        }
    }

    pub fn read(&self, is_stderr: bool, len: u64) -> Option<ReadResult> {
        let ulen = len as usize;

        let channel = match self.channel.as_ref()?.channel.write() {
            Ok(s) => s,
            Err(e) => {
                println!("Something failed obtaining write channel: {e:?}");
                return None;
            }
        };

        let mut buffer = vec![0; ulen];
        let read = match channel.read_timeout(&mut buffer, is_stderr, None) {
            Ok(s) => s,
            Err(e) => {
                let message = libssh_error_to_message(&e);
                println!("Something failed reading SSH channel: {message}");
                return None;
            }
        };

        Some(ReadResult {
            read_count: read as u64,
            data: buffer,
        })
    }

    pub fn write_byte(&self, byte: i32) -> String {
        let channel = match self.get_channel() {
            Ok(c) => c,
            Err(e) => {
                return format!("Something failed obtaining write channel: {e:?}");
            }
        };

        let res = channel.stdin().write_all(&byte.to_ne_bytes());

        match res {
            Ok(_) => String::new(),
            Err(e) => {
                format!("Something failed writing to channel STDIN: {e:?}")
            }
        }
    }

    pub fn write_bytes(&self, data: &Vec<u8>) -> String {
        let channel = match self.get_channel() {
            Ok(c) => c,
            Err(e) => {
                return format!("Something failed obtaining write channel: {e:?}");
            }
        };

        let res = channel.stdin().write_all(data);

        match res {
            Ok(_) => String::new(),
            Err(e) => {
                format!("Something failed writing to channel STDIN: {e:?}")
            }
        }
    }
}

impl Channel {
    fn get_channel(&'_ self) -> LockResult<RwLockWriteGuard<'_, libssh_rs::Channel>> {
        self.channel.as_ref().unwrap().channel.write()
    }
}

fn to_int(auth_status: AuthStatus) -> i32 {
    match auth_status {
        AuthStatus::Success => 1,
        AuthStatus::Denied => 2,
        AuthStatus::Partial => 3,
        AuthStatus::Info => 4,
        AuthStatus::Again => 5,
    }
}

/// How the server's host key compares with the known_hosts files.
#[derive(uniffi::Enum)]
pub enum HostKeyState {
    /// The key is in a known_hosts file.
    Known,
    /// The host isn't in any known_hosts file, or there is none.
    Unknown,
    /// A known_hosts file has another key for the host.
    Changed,
    /// A known_hosts file has a key of another type for the host.
    OtherType,
    /// The key couldn't be checked, see `HostKeyCheck::error`.
    Failed,
}

#[derive(uniffi::Record)]
pub struct HostKeyCheck {
    pub state: HostKeyState,
    /// The server's key as ssh shows it ("SHA256:..."), or empty when it couldn't be read.
    pub fingerprint: String,
    pub error: String,
}

/// The SHA256 fingerprint of the server's host key, in ssh's format.
fn server_key_fingerprint(session: &libssh_rs::Session) -> Result<String, String> {
    let key = session
        .get_server_public_key()
        .map_err(|e| libssh_error_to_message(&e))?;
    let mut hash = key
        .get_public_key_hash(PublicKeyHashType::Sha256)
        .map_err(|e| libssh_error_to_message(&e))?;

    let fingerprint = unsafe {
        libssh_rs_sys::ssh_get_fingerprint_hash(
            libssh_rs_sys::ssh_publickey_hash_type::SSH_PUBLICKEY_HASH_SHA256,
            hash.as_mut_ptr(),
            hash.len(),
        )
    };

    if fingerprint.is_null() {
        return Err("Could not format the host key's fingerprint".to_string());
    }

    let text = unsafe { CStr::from_ptr(fingerprint) }
        .to_string_lossy()
        .into_owned();
    unsafe { libssh_rs_sys::ssh_string_free_char(fingerprint) };

    Ok(text)
}

fn libssh_error_to_message(err: &libssh_rs::Error) -> String {
    match err {
        libssh_rs::Error::RequestDenied(message) => message.clone(),
        libssh_rs::Error::Fatal(message) => message.clone(),
        libssh_rs::Error::TryAgain => "Something went wrong, please try again".to_string(),
        libssh_rs::Error::Sftp(_) => "Sftp not supported".to_string(),
    }
}

#[derive(uniffi::Record)]
pub struct ReadResult {
    pub read_count: u64,
    pub data: Vec<u8>,
}

#[derive(uniffi::Object)]
pub struct Signing;

#[uniffi::export]
impl Signing {
    #[uniffi::constructor]
    fn new() -> Signing {
        Signing {}
    }

    fn sign_data(&self, data: &Vec<u8>, key: String, password: Option<String>) -> String {
        let key =
            SshKey::from_privkey_file(&key, password.as_deref()).expect("Unable to load private key");
        ssh_sign(&data, key, SignAlgorithm::SHA512, None, "git".to_string())
            .expect("Unable to sign data")
    }
}
