use std::collections::HashMap;
use super::config::{self, Config};
use self::inner::Helper;

mod inner {
    pub struct Helper;
}

pub fn load(path: &str) -> Result<HashMap<String, u32>, std::io::Error> {
    let text = std::fs::read_to_string(path)?;
    let mut counts = HashMap::new();
    for word in text.split_whitespace() {
        *counts.entry(word.to_string()).or_insert(0) += 1;
    }
    let _ = super::helper(&config::DEFAULT);
    Ok(counts)
}
