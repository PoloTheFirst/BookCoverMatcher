// SPDX-License-Identifier: GPL-3.0-or-later

//! The text protocol used across the JNI boundary.
//!
//! Kotlin could be handed a Java object graph, but every field would then be a second JNI call and
//! every mistake a `NoSuchMethodError` at run time. A single escaped `String` is cheaper to produce,
//! impossible to mis-declare, and can be parsed by pure Kotlin — which is what makes the parser
//! unit-testable on a desktop JVM.
//!
//! Format (UTF-8, `\n`-terminated lines, `\t`-separated fields):
//!
//! ```text
//! BC-OCR-RESULT 1
//! REGIONS<TAB><regions the detector proposed>
//! LINE<TAB><rank><TAB><score><TAB><vertical 0|1><TAB><x0,y0,x1,y1,x2,y2,x3,y3><TAB><text>
//! ERROR<TAB><message>            (zero or more; one per region that failed)
//! END
//! ```
//!
//! and, for diagnostics:
//!
//! ```text
//! BC-OCR-INFO 1
//! VERSION<TAB><crate version>
//! CLASSES<TAB><recogniser class count>
//! DICT<TAB><dictionary entries>
//! SPACE<TAB><0|1>
//! DET<TAB><input name><TAB><shape>
//! REC<TAB><output name><TAB><shape>
//! END
//! ```
//!
//! The header carries a version so an older Kotlin side can refuse a newer format instead of
//! misreading it. Text and messages are escaped (`\\`, `\t`, `\n`, `\r`) because recognised text is
//! attacker-influenced data: a cover could print a tab or a newline, and a naive format would then
//! let one line impersonate two.

use crate::engine::{ModelInfo, OcrOutcome};

pub const RESULT_HEADER: &str = "BC-OCR-RESULT 1";
pub const INFO_HEADER: &str = "BC-OCR-INFO 1";
pub const END: &str = "END";

/// Escapes the four characters that could break the line/field structure.
pub fn escape(value: &str) -> String {
    let mut out = String::with_capacity(value.len());
    for ch in value.chars() {
        match ch {
            '\\' => out.push_str("\\\\"),
            '\t' => out.push_str("\\t"),
            '\n' => out.push_str("\\n"),
            '\r' => out.push_str("\\r"),
            other => out.push(other),
        }
    }
    out
}

/// The exact inverse of [`escape`]; an unknown escape keeps the escaped character.
pub fn unescape(value: &str) -> String {
    let mut out = String::with_capacity(value.len());
    let mut chars = value.chars();
    while let Some(ch) = chars.next() {
        if ch != '\\' {
            out.push(ch);
            continue;
        }
        match chars.next() {
            Some('\\') => out.push('\\'),
            Some('t') => out.push('\t'),
            Some('n') => out.push('\n'),
            Some('r') => out.push('\r'),
            Some(other) => out.push(other),
            None => out.push('\\'),
        }
    }
    out
}

/// One recognised line, in the format Kotlin parses.
pub fn result(outcome: &OcrOutcome) -> String {
    let mut out = String::with_capacity(64 + outcome.lines.len() * 96);
    out.push_str(RESULT_HEADER);
    out.push('\n');
    out.push_str("REGIONS\t");
    out.push_str(&outcome.regions_detected.to_string());
    out.push('\n');
    for line in &outcome.lines {
        let corners = line.quad.0;
        out.push_str("LINE\t");
        out.push_str(&line.rank.to_string());
        out.push('\t');
        // Six decimals: enough to compare host and device scores, short enough to keep the payload
        // small for a 300-region cover.
        out.push_str(&format!("{:.6}", line.score));
        out.push('\t');
        out.push_str(if line.vertical { "1" } else { "0" });
        out.push('\t');
        out.push_str(&format!(
            "{:.2},{:.2},{:.2},{:.2},{:.2},{:.2},{:.2},{:.2}",
            corners[0].x, corners[0].y, corners[1].x, corners[1].y,
            corners[2].x, corners[2].y, corners[3].x, corners[3].y,
        ));
        out.push('\t');
        out.push_str(&escape(&line.text));
        out.push('\n');
    }
    for error in &outcome.region_errors {
        out.push_str("ERROR\t");
        out.push_str(&escape(error));
        out.push('\n');
    }
    out.push_str(END);
    out.push('\n');
    out
}

/// Model metadata, so a device log can be tied to the exact graphs that were loaded.
pub fn info(info: &ModelInfo, crate_version: &str) -> String {
    let mut out = String::new();
    out.push_str(INFO_HEADER);
    out.push('\n');
    out.push_str(&format!("VERSION\t{}\n", escape(crate_version)));
    out.push_str(&format!("CLASSES\t{}\n", info.rec_classes));
    out.push_str(&format!("DICT\t{}\n", info.dict_len));
    out.push_str(&format!("SPACE\t{}\n", u8::from(info.use_space_char)));
    out.push_str(&format!("DET\t{}\t{}\n", escape(&info.det_input), shape(&info.det_input_shape)));
    out.push_str(&format!("REC\t{}\t{}\n", escape(&info.rec_output), shape(&info.rec_input_shape)));
    out.push_str(END);
    out.push('\n');
    out
}

/// Shapes render as `n` for a fixed dimension and `?` for a dynamic one (`-1` in the graph).
fn shape(dims: &[i64]) -> String {
    let mut out = String::with_capacity(dims.len() * 3);
    out.push('[');
    for (index, dim) in dims.iter().enumerate() {
        if index > 0 {
            out.push(',');
        }
        if *dim > 0 {
            out.push_str(&dim.to_string());
        } else {
            out.push('?');
        }
    }
    out.push(']');
    out
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::engine::OcrLine;
    use crate::geom::{Point, Quad};

    fn model_info() -> ModelInfo {
        ModelInfo {
            det_input: "x".into(),
            det_input_shape: vec![-1, 3, -1, -1],
            det_output: "fetch_name_0".into(),
            det_output_shape: vec![-1, 1, -1, -1],
            rec_input: "x".into(),
            rec_input_shape: vec![-1, 3, 48, -1],
            rec_output: "fetch_name_0".into(),
            rec_output_shape: vec![-1, -1, 18_710],
            rec_classes: 18_710,
            dict_len: 18_708,
            use_space_char: true,
        }
    }

    fn line(rank: usize, text: &str) -> OcrLine {
        OcrLine {
            text: text.into(),
            score: 0.5,
            quad: Quad::ordered([
                Point::new(0.0, 0.0),
                Point::new(10.0, 0.0),
                Point::new(10.0, 20.0),
                Point::new(0.0, 20.0),
            ]),
            vertical: false,
            rank,
        }
    }

    #[test]
    fn escaping_round_trips_every_separator() {
        let raw = "line\tone\ntwo\rthree\\four 日本語";
        assert_eq!(unescape(&escape(raw)), raw);
        assert!(!escape(raw).contains('\t'), "no raw tab survives");
        assert!(!escape(raw).contains('\n'), "no raw newline survives");
    }

    #[test]
    fn an_unknown_escape_keeps_the_escaped_character() {
        assert_eq!(unescape("a\\qb"), "aqb");
        assert_eq!(unescape("trailing\\"), "trailing\\");
    }

    #[test]
    fn a_result_carries_the_header_the_counts_and_the_end_marker() {
        let outcome = OcrOutcome {
            lines: vec![line(1, "THE HOBBIT")],
            regions_detected: 3,
            region_errors: vec!["region 2: degenerate".into()],
        };
        let text = result(&outcome);
        let lines: Vec<&str> = text.lines().collect();
        assert_eq!(lines.first().copied(), Some(RESULT_HEADER));
        assert_eq!(lines.last().copied(), Some(END));
        assert!(lines.contains(&"REGIONS\t3"));
        assert!(
            text.contains("LINE\t1\t0.500000\t0\t0.00,0.00,10.00,0.00,10.00,20.00,0.00,20.00\tTHE HOBBIT"),
            "{text}"
        );
        assert!(text.contains("ERROR\tregion 2: degenerate"));
    }

    #[test]
    fn a_cover_that_prints_a_tab_cannot_forge_an_extra_field_or_line() {
        let outcome = OcrOutcome {
            lines: vec![line(1, "fake\t0.9\t1\tEND")],
            regions_detected: 1,
            region_errors: Vec::new(),
        };
        let text = result(&outcome);
        assert_eq!(text.matches("\nEND").count(), 1, "only the real terminator");
        assert!(text.contains("fake\\t0.9\\t1\\tEND"), "{text}");
    }

    #[test]
    fn the_info_record_matches_the_verified_model_layout() {
        let text = info(&model_info(), "0.1.0");
        assert!(text.starts_with(INFO_HEADER));
        assert!(text.contains("VERSION\t0.1.0"));
        assert!(text.contains("CLASSES\t18710"));
        assert!(text.contains("DICT\t18708"));
        assert!(text.contains("SPACE\t1"));
        assert!(text.contains("DET\tx\t[?,3,?,?]"), "dynamic dims render as ?: {text}");
        assert!(text.contains("REC\tfetch_name_0\t[?,3,48,?]"), "{text}");
        assert!(text.ends_with("END\n"));
    }
}
