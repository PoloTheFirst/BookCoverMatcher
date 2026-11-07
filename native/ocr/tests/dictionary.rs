// SPDX-License-Identifier: GPL-3.0-or-later

//! Pins the **real** PP-OCRv6 vocabulary: counts, ordering and index→character mapping.
//!
//! The dictionary is committed at `native/ocr/assets/ppocrv6_dict.txt` (75 KB, Apache-2.0), taken
//! from the upstream PaddleOCR repository:
//!
//! ```text
//! https://github.com/PaddlePaddle/PaddleOCR/blob/main/ppocr/utils/dict/ppocrv6_dict.txt
//! git blob sha1 270082aa29aec1ff3ac74d0d575c90399216f74d
//! sha256        B5F2BFE2BDD9448429E3E82B51C789775D9B42F2403D082B00662EB77E401C5D
//! ```
//!
//! It is the vocabulary of `PP-OCRv6_medium_rec_onnx` because 18,708 characters + CTC blank + the
//! appended space class = the **18,710** classes the exported graph emits. The inline copy in the
//! model's `inference.yml` has the same 18,708 entries, but its entry 1748 (`U+3000`) survives only
//! if the parser does not trim whitespace — one dropped entry shifts every later character, which is
//! exactly the defect that blocked decoding at commit `ae362fd`.
//!
//! The class layout is not inferred from the count alone. It comes from three agreeing sources:
//!
//! 1. the graph: `MatMul(linear_8.w_0 [192, 18710]) + bias [18710]`, then `Softmax(axis = 2)`;
//! 2. the model package's `inference.yml`: `PostProcess.name: CTCLabelDecode` with exactly 18,708
//!    `character_dict` entries, entry 1748 = `U+3000`, last entry = `U+1F6C5`;
//! 3. the official training config
//!    `PaddleOCR/configs/rec/PP-OCRv6/PP-OCRv6_medium_rec.yml` (git blob `d8149ed9…`):
//!    `character_dict_path: ppocr/utils/dict/ppocrv6_dict.txt` and `use_space_char: true`;
//!    `ppocr/postprocess/rec_postprocess.py` then builds `["blank"] + dict_character + [" "]`.

use bc_ocr::dict::Charset;

const DICT: &str = include_str!("../assets/ppocrv6_dict.txt");

fn charset() -> Charset {
    Charset::from_dict_text(DICT, true).expect("the committed dictionary must parse")
}

#[test]
fn the_dictionary_has_the_exact_entry_count_the_model_requires() {
    let cs = charset();
    assert_eq!(cs.dict_len(), 18_708, "18,708 characters");
    assert_eq!(
        cs.class_count(),
        18_710,
        "18,708 + CTC blank + the appended space class = the graph's 18,710 outputs"
    );
    let without_space = Charset::from_dict_text(DICT, false).expect("parses without a space class");
    assert_eq!(without_space.class_count(), 18_709);
}

#[test]
fn every_entry_is_exactly_one_character_and_none_is_empty() {
    let cs = charset();
    // char_at(0) is the blank; 1..=18,708 are the dictionary entries.
    for class in 1..=cs.dict_len() {
        assert!(cs.char_at(class).is_some(), "class {class} must map to a character");
    }
    assert_eq!(cs.char_at(0), None, "class 0 is the CTC blank");
    assert_eq!(cs.char_at(cs.dict_len() + 1), Some(' '), "the space class is last");
    assert_eq!(cs.char_at(cs.dict_len() + 2), None, "there is no class past the space");
}

#[test]
fn known_indices_map_to_their_documented_characters() {
    // Verified against the upstream file (see the probe in the agent report):
    //   entry index 0 = '!'  13 = '.'  41 = '9'  42 = 'A'
    //   1747 = U+2B55  1748 = U+3000  1749 = U+3001  18707 = U+1F6C5
    let cs = charset();
    assert_eq!(cs.char_at(1), Some('!'));
    assert_eq!(cs.char_at(2), Some('"'));
    assert_eq!(cs.char_at(14), Some('.'));
    assert_eq!(cs.char_at(42), Some('9'));
    assert_eq!(cs.char_at(43), Some('A'));
    assert_eq!(cs.char_at(1748), Some('\u{2B55}'));
    assert_eq!(
        cs.char_at(1749),
        Some('\u{3000}'),
        "entry 1748 is U+3000 IDEOGRAPHIC SPACE - the entry a whitespace-trimming parser loses"
    );
    assert_eq!(cs.char_at(1750), Some('\u{3001}'));
    assert_eq!(cs.char_at(18_708), Some('\u{1F6C5}'));
}

#[test]
fn the_vocabulary_covers_the_scripts_the_application_needs() {
    let cs = charset();
    let mut has_hiragana = false;
    let mut has_katakana = false;
    let mut has_han = false;
    let mut has_latin = false;
    for class in 1..=cs.dict_len() {
        let ch = cs.char_at(class).expect("character");
        let code = ch as u32;
        has_hiragana |= (0x3040..=0x309F).contains(&code);
        has_katakana |= (0x30A0..=0x30FF).contains(&code);
        has_han |= (0x4E00..=0x9FFF).contains(&code);
        has_latin |= ch.is_ascii_alphabetic();
    }
    assert!(has_hiragana && has_katakana, "Japanese kana must be present");
    assert!(has_han, "CJK ideographs must be present (Japanese/Chinese)");
    assert!(has_latin, "Latin letters must be present (English)");
}

#[test]
fn a_dictionary_with_an_empty_entry_is_refused_instead_of_shifting_every_character() {
    // The exact failure mode that blocked decoding: an entry that whitespace trimming emptied.
    let broken = DICT.replacen('\u{3000}', "\n", 1);
    let err = Charset::from_dict_text(&broken, true).expect_err("an empty entry must be refused");
    assert!(err.contains("empty"), "the error must say what is wrong: {err}");
    // And the unbroken dictionary is 18,710 classes, so a 18,709-class tensor is refused too.
    let cs = charset();
    let logits = vec![0f32; 40 * 18_709];
    assert!(bc_ocr::rec::decode_output(&logits, 40, 18_709, &cs).is_err());
}

#[test]
fn a_full_width_tensor_decodes_through_the_real_vocabulary() {
    // Two steps: class 43 ('A'), then the blank -> "A". Uses the real 18,710-class width.
    let cs = charset();
    let classes = cs.class_count();
    let steps = 2usize;
    let mut logits = vec![0f32; steps * classes];
    logits[43] = 30.0; // step 0, class 43 = 'A'; large enough to dominate 18,709 competitors
    logits[classes] = 30.0; // step 1, class 0 = blank
    let (text, confidence) = bc_ocr::rec::decode_output(&logits, steps, classes, &cs).expect("decode");
    assert_eq!(text, "A");
    assert!(confidence > 0.99, "a dominant logit yields ~1.0: {confidence}");
}

#[test]
fn repeated_classes_collapse_and_a_blank_keeps_a_genuine_repeat() {
    let cs = charset();
    let classes = cs.class_count();
    // "AA" alone collapses to "A"; a blank between them keeps both.
    let mut collapsed = vec![0f32; 2 * classes];
    collapsed[43] = 20.0;
    collapsed[classes + 43] = 20.0;
    assert_eq!(
        bc_ocr::rec::decode_output(&collapsed, 2, classes, &cs).unwrap().0,
        "A",
        "two identical timesteps are one character in CTC"
    );
    let mut separated = vec![0f32; 3 * classes];
    separated[43] = 20.0;
    separated[classes] = 20.0; // blank
    separated[2 * classes + 43] = 20.0;
    assert_eq!(
        bc_ocr::rec::decode_output(&separated, 3, classes, &cs).unwrap().0,
        "AA",
        "a blank between two identical classes is a genuine double letter"
    );
}

#[test]
fn the_last_dictionary_entry_and_the_appended_space_are_both_addressable() {
    let cs = charset();
    let classes = cs.class_count();
    // 18,708 = last dictionary character (U+1F6C5), 18,709 = the appended space class.
    let mut emoji = vec![0f32; classes];
    emoji[18_708] = 25.0;
    assert_eq!(bc_ocr::rec::decode_output(&emoji, 1, classes, &cs).unwrap().0, "\u{1F6C5}");
    let mut space = vec![0f32; classes];
    space[18_709] = 25.0;
    assert_eq!(bc_ocr::rec::decode_output(&space, 1, classes, &cs).unwrap().0, " ");
}

#[test]
fn a_class_count_that_is_one_off_in_either_direction_is_refused() {
    let cs = charset();
    for wrong in [cs.class_count() - 1, cs.class_count() + 1] {
        let logits = vec![0f32; 8 * wrong];
        let err = bc_ocr::rec::decode_output(&logits, 8, wrong, &cs)
            .expect_err("an off-by-one class count must be refused");
        assert!(err.contains("refusing to decode"), "the refusal must be explicit: {err}");
    }
}

#[test]
fn a_japanese_and_a_chinese_character_decode_through_their_real_classes() {
    let cs = charset();
    let classes = cs.class_count();
    // Find a hiragana and a Han character, then encode them the way the model would.
    let hiragana = (1..=cs.dict_len())
        .find(|c| (cs.char_at(*c).unwrap() as u32) >= 0x3040 && (cs.char_at(*c).unwrap() as u32) <= 0x309F)
        .expect("hiragana");
    let han = (1..=cs.dict_len())
        .find(|c| (cs.char_at(*c).unwrap() as u32) >= 0x4E00 && (cs.char_at(*c).unwrap() as u32) <= 0x9FFF)
        .expect("han");
    let steps = 2usize;
    let mut logits = vec![0f32; steps * classes];
    logits[hiragana] = 10.0;
    logits[classes + han] = 10.0;
    let (text, _) = bc_ocr::rec::decode_output(&logits, steps, classes, &cs).expect("decode");
    assert_eq!(text, format!("{}{}", cs.char_at(hiragana).unwrap(), cs.char_at(han).unwrap()));
}
