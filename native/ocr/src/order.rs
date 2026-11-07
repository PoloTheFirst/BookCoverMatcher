// SPDX-License-Identifier: GPL-3.0-or-later

//! Reading order for book-cover text.
//!
//! Sorting by the top-left corner is wrong twice over on a Japanese cover: a vertical column must be
//! read **top-to-bottom**, and the columns themselves run **right-to-left**. A horizontal block
//! (a Latin subtitle, an author line) is read left-to-right. Mixed covers need both rules in one
//! deterministic pass, so the algorithm groups regions into bands first and only then orders inside
//! a band.

use crate::geom::Quad;

/// One ordered region: the caller keeps the geometry next to the text.
#[derive(Debug, Clone, Copy)]
pub struct Ordered {
    /// Index into the input slice, so the caller's own data stays authoritative.
    pub index: usize,
    /// 1-based reading position.
    pub rank: usize,
}

/// A region as the ordering stage sees it.
#[derive(Debug, Clone, Copy)]
pub struct Region {
    pub quad: Quad,
    pub vertical: bool,
}

/// Orders regions for reading.
///
/// * Regions whose height is at least twice their width are treated as **vertical columns**: they
///   are walked right-to-left by column, and top-to-bottom inside a column.
/// * Everything else is treated as a **line**: top-to-bottom by band, left-to-right inside a band.
///   Bands overlap by half a line height, so a slightly tilted line does not become two bands.
/// * The result is stable: equal keys keep their input order (`sort_by_key` on `(key, index)`).
pub fn reading_order(regions: &[Region]) -> Vec<Ordered> {
    let mut lines: Vec<usize> = Vec::new();
    let mut columns: Vec<usize> = Vec::new();
    for (i, region) in regions.iter().enumerate() {
        if region.vertical {
            columns.push(i);
        } else {
            lines.push(i);
        }
    }

    let mut ordered: Vec<Ordered> = Vec::with_capacity(regions.len());

    // Horizontal text first: band by vertical centre, then left-to-right inside the band.
    lines.sort_by(|a, b| {
        let (qa, qb) = (&regions[*a].quad, &regions[*b].quad);
        let (ca, cb) = ((qa.min_y() + qa.max_y()) / 2.0, (qb.min_y() + qb.max_y()) / 2.0);
        let (ha, hb) = (qa.max_y() - qa.min_y(), qb.max_y() - qb.min_y());
        let tolerance = (ha + hb) * 0.25;
        if (ca - cb).abs() <= tolerance {
            // Same band: left-to-right, and only then by index so the order stays deterministic.
            qa.min_x().total_cmp(&qb.min_x()).then_with(|| a.cmp(b))
        } else {
            ca.total_cmp(&cb)
        }
    });
    for index in lines {
        let rank = ordered.len() + 1;
        ordered.push(Ordered { index, rank });
    }

    // Vertical Japanese columns: right-to-left by x centre, then top-to-bottom.
    columns.sort_by(|a, b| {
        let (qa, qb) = (&regions[*a].quad, &regions[*b].quad);
        let (ca, cb) = ((qa.min_x() + qa.max_x()) / 2.0, (qb.min_x() + qb.max_x()) / 2.0);
        if (ca - cb).abs() <= 1.0 {
            qa.min_y().total_cmp(&qb.min_y())
        } else {
            cb.total_cmp(&ca) // right before left
        }
    });
    for index in columns {
        let rank = ordered.len() + 1;
        ordered.push(Ordered { index, rank });
    }

    ordered
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::image::axis_aligned_quad;

    fn region(x0: f32, y0: f32, x1: f32, y1: f32) -> Region {
        let quad = axis_aligned_quad(x0, y0, x1, y1);
        Region { quad, vertical: (y1 - y0) >= 2.0 * (x1 - x0) }
    }

    #[test]
    fn two_horizontal_lines_are_read_top_to_bottom() {
        let regions = vec![region(0.0, 100.0, 200.0, 130.0), region(0.0, 10.0, 200.0, 40.0)];
        let order = reading_order(&regions);
        assert_eq!(order.iter().map(|o| o.index).collect::<Vec<_>>(), vec![1, 0]);
        assert_eq!(order.iter().map(|o| o.rank).collect::<Vec<_>>(), vec![1, 2]);
    }

    #[test]
    fn a_line_with_two_words_is_read_left_to_right() {
        let regions = vec![region(120.0, 10.0, 200.0, 40.0), region(0.0, 12.0, 100.0, 42.0)];
        let order = reading_order(&regions);
        assert_eq!(order.iter().map(|o| o.index).collect::<Vec<_>>(), vec![1, 0]);
    }

    #[test]
    fn vertical_japanese_columns_are_read_right_to_left() {
        // Tall, narrow columns: right one first, then the left one.
        let regions = vec![region(10.0, 0.0, 40.0, 200.0), region(60.0, 0.0, 90.0, 200.0)];
        let order = reading_order(&regions);
        assert_eq!(order.iter().map(|o| o.index).collect::<Vec<_>>(), vec![1, 0]);
    }

    #[test]
    fn a_mixed_cover_reads_horizontal_text_then_columns_in_a_stable_order() {
        let regions = vec![
            region(60.0, 0.0, 90.0, 200.0),   // 0: vertical right column
            region(0.0, 210.0, 200.0, 240.0), // 1: horizontal author line
            region(10.0, 0.0, 40.0, 200.0),   // 2: vertical left column
        ];
        let order = reading_order(&regions);
        let indices: Vec<usize> = order.iter().map(|o| o.index).collect();
        assert_eq!(indices, vec![1, 0, 2], "line first, then columns right-to-left");
        assert_eq!(order.iter().map(|o| o.rank).collect::<Vec<_>>(), vec![1, 2, 3]);
    }

    #[test]
    fn ordering_is_deterministic_for_identical_geometry() {
        let regions = vec![region(0.0, 0.0, 10.0, 10.0), region(0.0, 0.0, 10.0, 10.0)];
        assert_eq!(
            reading_order(&regions).iter().map(|o| o.index).collect::<Vec<_>>(),
            vec![0, 1]
        );
    }

    #[test]
    fn an_empty_input_is_an_empty_order() {
        assert!(reading_order(&[]).is_empty());
    }
}
