//! The Rust port of sync-core: the record model, repeat rules, iCalendar, vCard, the backup file, the
//! sync codec, encryption and round logic, and the palmsync client. sync-core/testdata holds the
//! vectors that the Kotlin and the Rust code both must match (tests/vectors.rs).

pub mod backup;
pub mod client;
pub mod codec;
pub mod crypto;
pub mod days;
pub mod ical;
pub mod json;
pub mod logic;
pub mod model;
pub mod runner;
pub mod session;
pub mod vcard;
