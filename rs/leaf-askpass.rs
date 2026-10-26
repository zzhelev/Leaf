// SPDX-FileCopyrightText: 2026 Zhelyazko Zhelev
// SPDX-License-Identifier: AGPL-3.0-only

//! `leaf-askpass`, the askpass program and credential helper of the git commands that Leaf runs.
//!
//! - As `GIT_ASKPASS` and `SSH_ASKPASS`, it is run with a prompt (`Password for 'https://host': `) and prints the
//!   answer. With `SSH_ASKPASS_PROMPT=confirm`, ssh only wants to know whether the user agreed: it reads the exit
//!   code.
//! - As a credential helper (`credential.helper=!'<path>' credential`), it is run with `get`, `store` or `erase` and
//!   the credential on stdin. It serves Leaf's in-memory credentials cache.
//!
//! - As git's LFS filter (`filter.lfs.process=<path> lfs-filter`), it passes git's long-running filter protocol on to
//!   Leaf, which speaks it, and back.
//!
//! Each way it passes the request to Leaf through the socket named by `LEAF_ASKPASS_SOCKET` (a Unix socket, or a
//! loopback TCP port on Windows), with the token from `LEAF_ASKPASS_TOKEN`. A request is the token, the kind and the
//! payload, each followed by a NUL byte, which can't appear in arguments or in the credential protocol. The reply is
//! `1` followed by the answer, or `0` when the user refused. For the filter, the payload is the working directory, and
//! once Leaf has answered `1` the connection carries git's pipe both ways.

use std::io::{self, Read, Write};
use std::process::ExitCode;

const SOCKET_VARIABLE: &str = "LEAF_ASKPASS_SOCKET";
const TOKEN_VARIABLE: &str = "LEAF_ASKPASS_TOKEN";

enum Request {
    Prompt(String),
    Confirm(String),
    Credential { operation: String, input: String },
}

fn main() -> ExitCode {
    let args: Vec<String> = std::env::args().skip(1).collect();

    let request = match args.as_slice() {
        [mode] if mode == "lfs-filter" => return lfs_filter(),
        [mode, operation] if mode == "credential" => {
            // Operations that git may add later are left to the other helpers
            if !matches!(operation.as_str(), "get" | "store" | "erase") {
                return ExitCode::SUCCESS;
            }

            let mut input = String::new();
            if io::stdin().read_to_string(&mut input).is_err() {
                return ExitCode::SUCCESS;
            }

            Request::Credential { operation: operation.clone(), input }
        }
        _ => {
            let prompt = args.first().cloned().unwrap_or_default();

            match std::env::var("SSH_ASKPASS_PROMPT").as_deref() {
                // A notice that ssh shows while it waits, such as for touching a security key. It ends it by killing
                // this program, and Leaf already shows that the operation is running.
                Ok("none") => return ExitCode::SUCCESS,
                Ok("confirm") => Request::Confirm(prompt),
                _ => Request::Prompt(prompt),
            }
        }
    };

    let reply = match ask_leaf(&request) {
        Ok(reply) => reply,
        Err(error) => {
            if let Request::Credential { .. } = request {
                // Like a helper that has nothing to give: git asks the user instead
                return ExitCode::SUCCESS;
            }

            eprintln!("leaf-askpass: {error}");
            return ExitCode::FAILURE;
        }
    };

    let mut stdout = io::stdout();

    match (request, reply) {
        (Request::Prompt(_), Some(answer)) => {
            let printed = stdout
                .write_all(answer.as_bytes())
                .and_then(|_| stdout.write_all(b"\n"))
                .and_then(|_| stdout.flush());

            if printed.is_ok() { ExitCode::SUCCESS } else { ExitCode::FAILURE }
        }
        (Request::Confirm(_), Some(_)) => ExitCode::SUCCESS,
        (Request::Credential { .. }, Some(answer)) => {
            let _ = stdout.write_all(answer.as_bytes()).and_then(|_| stdout.flush());
            ExitCode::SUCCESS
        }
        (Request::Credential { .. }, None) => ExitCode::SUCCESS,
        (_, None) => ExitCode::FAILURE,
    }
}

/// Sends [request] to Leaf and returns its answer, or `None` when the user refused.
fn ask_leaf(request: &Request) -> io::Result<Option<String>> {
    let (kind, payload) = match request {
        Request::Prompt(prompt) => ("prompt".to_string(), prompt.as_str()),
        Request::Confirm(prompt) => ("confirm".to_string(), prompt.as_str()),
        Request::Credential { operation, input } => (format!("credential-{operation}"), input.as_str()),
    };

    let mut stream = send_request(&kind, payload.as_bytes())?;

    let mut reply = Vec::new();
    stream.read_to_end(&mut reply)?;

    match reply.split_first() {
        Some((b'1', answer)) => Ok(Some(String::from_utf8_lossy(answer).into_owned())),
        Some((b'0', _)) => Ok(None),
        _ => Err(io::Error::new(io::ErrorKind::InvalidData, "Leaf sent no answer")),
    }
}

/// As git's long-running LFS filter: passes git's pipe on to Leaf, which speaks the protocol, until either ends.
fn lfs_filter() -> ExitCode {
    match relay_to_leaf() {
        Ok(()) => ExitCode::SUCCESS,
        Err(error) => {
            eprintln!("leaf-askpass: {error}");
            ExitCode::FAILURE
        }
    }
}

fn relay_to_leaf() -> io::Result<()> {
    // git runs the filter at the top of the working tree, which tells Leaf the repository
    let directory = std::env::current_dir()?;
    let mut stream = send_request("lfs-filter", directory.as_os_str().as_encoded_bytes())?;

    // None of git's bytes go before Leaf has taken the request, so that they don't arrive with it
    let mut answer = [0u8; 1];
    stream.read_exact(&mut answer)?;
    if answer[0] != b'1' {
        return Err(io::Error::new(io::ErrorKind::PermissionDenied, "Leaf refused to filter"));
    }

    let mut to_leaf = stream.try_clone()?;
    // git's requests, until git closes the pipe, which ends the session
    std::thread::spawn(move || {
        let _ = copy_flushing(&mut io::stdin().lock(), &mut to_leaf);
        let _ = to_leaf.shutdown(std::net::Shutdown::Write);
    });

    // Leaf's answers, until Leaf closes the connection
    copy_flushing(&mut stream, &mut io::stdout().lock())
}

/// Copies until `from` ends, flushing after each write, as git waits for each answer.
fn copy_flushing(from: &mut impl Read, to: &mut impl Write) -> io::Result<()> {
    let mut buffer = vec![0u8; 64 * 1024];

    loop {
        let read = match from.read(&mut buffer) {
            Ok(0) => return Ok(()),
            Ok(read) => read,
            Err(error) if error.kind() == io::ErrorKind::Interrupted => continue,
            Err(error) => return Err(error),
        };

        to.write_all(&buffer[..read])?;
        to.flush()?;
    }
}

/// Connects to Leaf and sends the token, [kind] and [payload], each followed by a NUL byte.
fn send_request(kind: &str, payload: &[u8]) -> io::Result<LeafStream> {
    let address = std::env::var(SOCKET_VARIABLE)
        .map_err(|_| io::Error::new(io::ErrorKind::NotFound, format!("{SOCKET_VARIABLE} isn't set")))?;
    let token = std::env::var(TOKEN_VARIABLE)
        .map_err(|_| io::Error::new(io::ErrorKind::NotFound, format!("{TOKEN_VARIABLE} isn't set")))?;

    let mut message = Vec::new();
    for field in [token.as_bytes(), kind.as_bytes(), payload] {
        message.extend_from_slice(field);
        message.push(0);
    }

    let mut stream = connect(&address)?;
    stream.write_all(&message)?;
    stream.flush()?;

    Ok(stream)
}

#[cfg(unix)]
type LeafStream = std::os::unix::net::UnixStream;

#[cfg(windows)]
type LeafStream = std::net::TcpStream;

#[cfg(unix)]
fn connect(address: &str) -> io::Result<LeafStream> {
    std::os::unix::net::UnixStream::connect(address)
}

/// Rust's standard library has no Unix sockets on Windows, so Leaf listens on a loopback port there.
#[cfg(windows)]
fn connect(address: &str) -> io::Result<LeafStream> {
    let port: u16 = address
        .parse()
        .map_err(|_| io::Error::new(io::ErrorKind::InvalidInput, format!("{SOCKET_VARIABLE} isn't a port")))?;

    std::net::TcpStream::connect((std::net::Ipv4Addr::LOCALHOST, port))
}
