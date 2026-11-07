// SPDX-License-Identifier: GPL-3.0-or-later

//! The recognition charset, and the CTC decoder built on it.
//!
//! PP-OCR's `CTCLabelDecode` is *not* "map index to character". Its index space is
//!
//! ```text
//! 0     -> the CTC blank (never emitted)
//! 1..=n -> character_dict[0..n-1]        (the list embedded in inference.yml)
//! n+1   -> ' '  (only when the model was trained with use_space_char)
//! ```
//!
//! Getting that shift wrong produces text that looks plausible and is wrong in every character,
//! so the mapping is built once, from the model's own dictionary, and the class count is checked
//! against the graph's output width at load time.

/// The character space of one recognition model.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Charset {
    /// `character_dict` in its original order (index 0 of this vector is class 1).
    chars: Vec<char>,
    /// True when the model appends a space class after the dictionary.
    use_space_char: bool,
}

impl Charset {
    /// Builds a charset from a dictionary file: one character per line, order preserved.
    ///
    /// A trailing `\r`, a UTF-8 BOM and the final empty line are stripped; **empty lines inside**
    /// the dictionary are rejected, because silently skipping one would shift every later index.
    pub fn from_dict_text(text: &str, use_space_char: bool) -> Result<Self, String> {
        let text = text.strip_prefix('\u{feff}').unwrap_or(text);
        let mut chars = Vec::new();
        let mut lines = text
            .split('\n')
            .map(|line| line.strip_suffix('\r').unwrap_or(line))
            .peekable();
        while let Some(line) = lines.next() {
            if line.is_empty() {
                // Tolerate only the final terminator; anything else is a broken dictionary.
                if lines.peek().is_none() {
                    break;
                }
                return Err(format!("dictionary line {} is empty", chars.len() + 1));
            }
            let mut iter = line.chars();
            let ch = iter.next().expect("non-empty line");
            if iter.next().is_some() {
                return Err(format!(
                    "dictionary line {} holds more than one character",
                    chars.len() + 1
                ));
            }
            chars.push(ch);
        }
        if chars.is_empty() {
            return Err("dictionary is empty".into());
        }
        Ok(Self { chars, use_space_char })
    }

    /// Number of dictionary characters (classes minus blank and the optional space).
    pub fn dict_len(&self) -> usize {
        self.chars.len()
    }

    /// The number of classes the model must output for this charset.
    pub fn class_count(&self) -> usize {
        self.chars.len() + 1 + usize::from(self.use_space_char)
    }

    /// The character for a class index, or `None` for the blank class.
    pub fn char_at(&self, index: usize) -> Option<char> {
        if index == 0 {
            return None;
        }
        if index <= self.chars.len() {
            return Some(self.chars[index - 1]);
        }
        if self.use_space_char && index == self.chars.len() + 1 {
            return Some(' ');
        }
        None
    }

    /// Greedy CTC decoding of one timestep series.
    ///
    /// `probs` is `[t][c]` row-major scores for a single sample. Returns the decoded string and the
    /// mean score of the classes that were actually emitted — the number a UI can honestly call
    /// "confidence"; blanks and collapsed repeats are excluded because they are not characters.
    pub fn decode_ctc(&self, probs: &[Vec<f32>]) -> (String, f32) {
        let mut out = String::new();
        let mut total = 0.0f32;
        let mut emitted = 0usize;
        let mut prev = usize::MAX;
        for row in probs {
            let (best, score) = argmax(row);
            if best != 0 && best != prev {
                if let Some(ch) = self.char_at(best) {
                    out.push(ch);
                    total += score;
                    emitted += 1;
                }
            }
            prev = best;
        }
        let confidence = if emitted == 0 { 0.0 } else { total / emitted as f32 };
        (out, confidence)
    }
}

/// Index and value of the maximum of one row; ties resolve to the lowest index, as PaddleOCR does.
pub fn argmax(row: &[f32]) -> (usize, f32) {
    let mut best = 0usize;
    let mut value = f32::NEG_INFINITY;
    for (i, v) in row.iter().enumerate() {
        if *v > value {
            value = *v;
            best = i;
        }
    }
    (best, if value.is_finite() { value } else { 0.0 })
}


#[cfg(test)]
mod tests {
    use super::*;

    fn charset() -> Charset {
        // A tiny alphabet that keeps the index arithmetic visible: a, b, あ.
        Charset::from_dict_text("a\nb\nあ\n", true).unwrap()
    }

    #[test]
    fn index_space_is_shifted_by_the_blank_class() {
        let cs = charset();
        assert_eq!(cs.dict_len(), 3);
        assert_eq!(cs.class_count(), 5); // blank + 3 characters + space
        assert_eq!(cs.char_at(0), None);
        assert_eq!(cs.char_at(1), Some('a'));
        assert_eq!(cs.char_at(3), Some('あ'));
        assert_eq!(cs.char_at(4), Some(' '));
        assert_eq!(cs.char_at(5), None);
    }

    #[test]
    fn greedy_decode_skips_blanks_and_collapses_repeats() {
        let cs = charset();
        let rows = vec![
            vec![0.0, 0.9, 0.1, 0.0, 0.0],   // a
            vec![0.0, 0.8, 0.1, 0.1, 0.0],   // still a -> collapsed
            vec![0.9, 0.0, 0.0, 0.0, 0.1],   // blank
            vec![0.0, 0.1, 0.85, 0.05, 0.0], // b
            vec![0.0, 0.0, 0.05, 0.9, 0.05], // a-in-kana position
            vec![0.0, 0.0, 0.0, 0.0, 0.95],  // space
        ];
        let (text, confidence) = cs.decode_ctc(&rows);
        assert_eq!(text, "abあ ");
        assert!((confidence - (0.9 + 0.85 + 0.9 + 0.95) / 4.0).abs() < 1e-5);
    }

    #[test]
    fn a_repeated_character_survives_a_blank_between_the_two_copies() {
        let cs = charset();
        let rows = vec![
            vec![0.0, 0.9, 0.0, 0.0, 0.0], // a
            vec![0.9, 0.0, 0.0, 0.0, 0.0], // blank separates the two a's
            vec![0.0, 0.9, 0.0, 0.0, 0.0], // a again -> kept
        ];
        assert_eq!(cs.decode_ctc(&rows).0, "aa");
    }

    #[test]
    fn an_all_blank_series_decodes_to_nothing_with_zero_confidence() {
        let cs = charset();
        let rows = vec![vec![1.0, 0.0, 0.0, 0.0, 0.0]; 4];
        let (text, confidence) = cs.decode_ctc(&rows);
        assert_eq!(text, "");
        assert_eq!(confidence, 0.0);
    }

    #[test]
    fn an_out_of_range_class_is_ignored_rather_than_panicking() {
        let cs = Charset::from_dict_text("a\n", false).unwrap();
        let rows = vec![vec![0.0, 0.0, 0.99]];
        assert_eq!(cs.decode_ctc(&rows).0, "");
    }

    #[test]
    fn an_empty_line_inside_the_dictionary_is_rejected() {
        assert!(Charset::from_dict_text("a\n\nb\n", false).is_err());
        assert!(Charset::from_dict_text("ab\n", false).is_err());
        assert!(Charset::from_dict_text("", false).is_err());
        // A single trailing newline is the normal case and must be accepted.
        assert!(Charset::from_dict_text("a\nb\n", false).is_ok());
    }

    #[test]
    fn a_byte_order_mark_does_not_become_a_character() {
        let cs = Charset::from_dict_text("\u{feff}あ\nい\n", false).unwrap();
        assert_eq!(cs.char_at(1), Some('あ'));
        assert_eq!(cs.dict_len(), 2);
    }
}
