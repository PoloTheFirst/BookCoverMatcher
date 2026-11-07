// SPDX-License-Identifier: GPL-3.0-or-later

//! A process-wide handle table for native engines.
//!
//! Kotlin never receives a raw pointer. It receives an `i64` id, and this table maps that id to an
//! `Arc<T>`. That buys three properties that matter across a JNI boundary:
//!
//! * a stale or forged id is reported as "unknown handle" instead of dereferencing freed memory;
//! * `destroy` racing with an in-flight call cannot free the engine under the caller — the call
//!   holds an `Arc`, so the engine lives until the last user finishes;
//! * ids are never reused, so a late result can never be attributed to a newer engine.
//!
//! Ids start at 1 because 0 is what the JNI layer returns when creation failed.
//!
//! A poisoned lock is recovered instead of propagated: a panic in one OCR call must not brick the
//! engine table for the rest of the process.

use std::collections::BTreeMap;
use std::sync::atomic::{AtomicI64, Ordering};
use std::sync::{Arc, Mutex};

pub struct Registry<T> {
    items: Mutex<BTreeMap<i64, Arc<T>>>,
    next: AtomicI64,
}

impl<T> Default for Registry<T> {
    fn default() -> Self {
        Self::new()
    }
}

impl<T> Registry<T> {
    pub const fn new() -> Self {
        Self { items: Mutex::new(BTreeMap::new()), next: AtomicI64::new(1) }
    }

    /// Stores `value` and returns its id.
    pub fn insert(&self, value: T) -> i64 {
        let id = self.next.fetch_add(1, Ordering::Relaxed);
        self.items
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
            .insert(id, Arc::new(value));
        id
    }

    /// The stored value, or `None` when the id is unknown (already destroyed, or never existed).
    pub fn get(&self, id: i64) -> Option<Arc<T>> {
        self.items
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
            .get(&id)
            .cloned()
    }

    /// Removes `id`. Returns false when it was already gone, so a double destroy is a no-op rather
    /// than an error the caller has to special-case.
    pub fn remove(&self, id: i64) -> bool {
        self.items
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
            .remove(&id)
            .is_some()
    }

    pub fn len(&self) -> usize {
        self.items.lock().unwrap_or_else(|poisoned| poisoned.into_inner()).len()
    }

    pub fn is_empty(&self) -> bool {
        self.len() == 0
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn ids_start_at_one_and_are_never_reused() {
        let registry: Registry<String> = Registry::new();
        let first = registry.insert("a".to_string());
        let second = registry.insert("b".to_string());
        assert_eq!(first, 1, "0 is reserved for a failed create");
        assert!(second > first);
        assert!(registry.remove(first));
        let third = registry.insert("c".to_string());
        assert!(third > second, "a destroyed id must not come back");
    }

    #[test]
    fn lookups_return_the_stored_value_and_unknown_ids_are_none() {
        let registry: Registry<u32> = Registry::new();
        let id = registry.insert(7);
        assert_eq!(registry.get(id).map(|v| *v), Some(7));
        assert!(registry.get(9_999).is_none());
    }

    #[test]
    fn a_double_destroy_is_a_no_op() {
        let registry: Registry<u32> = Registry::new();
        let id = registry.insert(1);
        assert!(registry.remove(id));
        assert!(!registry.remove(id));
        assert_eq!(registry.len(), 0);
        assert!(registry.is_empty());
    }

    #[test]
    fn an_in_flight_arc_keeps_the_value_alive_after_removal() {
        let registry: Registry<String> = Registry::new();
        let id = registry.insert("engine".to_string());
        let held = registry.get(id).expect("present");
        assert!(registry.remove(id));
        assert_eq!(*held, "engine", "the caller's Arc is still valid");
    }
}
