//! Planar geometry: the quadrilaterals the detector produces and the crops they describe.
//!
//! PaddleOCR's DB post-processing hands each text region to `get_mini_boxes` (a rotated
//! rectangle of four points) and then to `get_rotate_crop_image`, which maps that rectangle onto a
//! flat, upright recogniser input through a perspective transform. Both steps are reproduced here
//! without OpenCV, because the recogniser's accuracy depends on the crop being *upright*: a
//! vertical Japanese column fed in sideways is unreadable to the model even when the box was found
//! perfectly.

/// One point in image space. `x` is the column, `y` the row.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct Point {
    pub x: f32,
    pub y: f32,
}

impl Point {
    pub fn new(x: f32, y: f32) -> Self {
        Self { x, y }
    }

    fn distance(self, other: Point) -> f32 {
        let dx = self.x - other.x;
        let dy = self.y - other.y;
        (dx * dx + dy * dy).sqrt()
    }
}

/// A text region as four corners, ordered top-left, top-right, bottom-right, bottom-left.
///
/// The order matters: it is what makes the crop upright, and it is what the reading-order stage
/// uses to tell a horizontal line from a vertical column.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct Quad(pub [Point; 4]);

impl Quad {
    /// Orders four arbitrary corners into the canonical order PaddleOCR uses.
    ///
    /// The rule is the one `get_mini_boxes` relies on: the two points with the smallest and
    /// largest `x + y` are the top-left and bottom-right corners; of the remaining two, the smaller
    /// `y - x` is the top-right and the larger is the bottom-left. Sorting by `x` alone would swap
    /// the corners of a rotated line and mirror its crop.
    pub fn ordered(points: [Point; 4]) -> Self {
        let mut sorted = points;
        sorted.sort_by(|a, b| (a.x + a.y).total_cmp(&(b.x + b.y)));
        let (tl, br) = (sorted[0], sorted[3]);
        let (mut tr, mut bl) = (sorted[1], sorted[2]);
        if (tr.y - tr.x) > (bl.y - bl.x) {
            std::mem::swap(&mut tr, &mut bl);
        }
        Quad([tl, tr, br, bl])
    }

    /// Width and height of the rectangle that best matches this quad, in pixels.
    pub fn rect_size(&self) -> (f32, f32) {
        let [tl, tr, br, bl] = self.0;
        let width = tl.distance(tr).max(bl.distance(br));
        let height = tl.distance(bl).max(tr.distance(br));
        (width, height)
    }

    /// True when the region is taller than it is wide — a vertical line/column on a cover.
    pub fn is_vertical(&self) -> bool {
        let (w, h) = self.rect_size();
        h > w
    }

    /// Smallest `x`/`y` of the four corners.
    pub fn min_x(&self) -> f32 {
        self.0.iter().map(|p| p.x).fold(f32::MAX, f32::min)
    }

    pub fn max_x(&self) -> f32 {
        self.0.iter().map(|p| p.x).fold(f32::MIN, f32::max)
    }

    pub fn min_y(&self) -> f32 {
        self.0.iter().map(|p| p.y).fold(f32::MAX, f32::min)
    }

    pub fn max_y(&self) -> f32 {
        self.0.iter().map(|p| p.y).fold(f32::MIN, f32::max)
    }

    /// True when every corner is finite and the region has a usable area.
    pub fn is_valid(&self) -> bool {
        let finite = self
            .0
            .iter()
            .all(|p| p.x.is_finite() && p.y.is_finite());
        if !finite {
            return false;
        }
        let (w, h) = self.rect_size();
        w >= 2.0 && h >= 2.0
    }
}


/// Perspective crop of one quad into an upright, axis-aligned image, as
/// `get_rotate_crop_image` does in PaddleOCR.
///
/// The source quad is split along `(tl, tr, br)` / `(tl, br, bl)`, each half resolved with an
/// affine transform and sampled bilinearly. Every sampled coordinate is clamped to the source, so
/// a quad that reaches slightly outside the picture yields edge pixels instead of a panic or a
/// black border — and a degenerate triangle leaves its pixels to the other half.
pub fn crop_quad(
    src: &[u8],
    src_w: usize,
    src_h: usize,
    quad: &Quad,
    out_w: usize,
    out_h: usize,
) -> Vec<u8> {
    let [tl, tr, br, bl] = quad.0;
    let dst = [
        Point::new(0.0, 0.0),
        Point::new(out_w as f32, 0.0),
        Point::new(out_w as f32, out_h as f32),
        Point::new(0.0, out_h as f32),
    ];
    let triangles = [
        ([tl, tr, br], [dst[0], dst[1], dst[2]]),
        ([tl, br, bl], [dst[0], dst[2], dst[3]]),
    ];
    let mut out = vec![0u8; out_w * out_h * 4];
    for (s, d) in triangles {
        let m = match affine_from_triangle(d, s) {
            Some(m) => m,
            None => continue,
        };
        let (mut x0, mut x1) = (f32::MAX, f32::MIN);
        let (mut y0, mut y1) = (f32::MAX, f32::MIN);
        for p in d {
            x0 = x0.min(p.x);
            x1 = x1.max(p.x);
            y0 = y0.min(p.y);
            y1 = y1.max(p.y);
        }
        let x_start = x0.floor().max(0.0) as usize;
        let x_end = (x1.ceil() as usize).min(out_w);
        let y_start = y0.floor().max(0.0) as usize;
        let y_end = (y1.ceil() as usize).min(out_h);
        for y in y_start..y_end {
            for x in x_start..x_end {
                let (sx, sy) = m.apply(x as f32 + 0.5, y as f32 + 0.5);
                let px = sample_bilinear(src, src_w, src_h, sx, sy);
                let at = (y * out_w + x) * 4;
                out[at..at + 4].copy_from_slice(&px);
            }
        }
    }
    out
}

/// The six coefficients of an affine map carrying triangle `from` onto triangle `to`.
pub struct Affine {
    a: f32,
    b: f32,
    c: f32,
    d: f32,
    e: f32,
    f: f32,
}

impl Affine {
    pub fn apply(&self, x: f32, y: f32) -> (f32, f32) {
        (self.a * x + self.b * y + self.c, self.d * x + self.e * y + self.f)
    }
}

/// Solves the affine transform taking the three `from` points onto the three `to` points.
pub fn affine_from_triangle(from: [Point; 3], to: [Point; 3]) -> Option<Affine> {
    let (x0, y0) = (from[0].x, from[0].y);
    let (x1, y1) = (from[1].x, from[1].y);
    let (x2, y2) = (from[2].x, from[2].y);
    let det = (x1 - x0) * (y2 - y0) - (x2 - x0) * (y1 - y0);
    if det.abs() < 1e-6 {
        return None; // collinear: no transform exists
    }
    let (u0, v0) = (to[0].x, to[0].y);
    let (u1, v1) = (to[1].x, to[1].y);
    let (u2, v2) = (to[2].x, to[2].y);
    let a = ((u1 - u0) * (y2 - y0) - (u2 - u0) * (y1 - y0)) / det;
    let b = ((u2 - u0) * (x1 - x0) - (u1 - u0) * (x2 - x0)) / det;
    let c = u0 - a * x0 - b * y0;
    let d = ((v1 - v0) * (y2 - y0) - (v2 - v0) * (y1 - y0)) / det;
    let e = ((v2 - v0) * (x1 - x0) - (v1 - v0) * (x2 - x0)) / det;
    let f = v0 - d * x0 - e * y0;
    Some(Affine { a, b, c, d, e, f })
}

/// Bilinear sample of one RGBA pixel, clamped to the image edges.
pub fn sample_bilinear(src: &[u8], width: usize, height: usize, x: f32, y: f32) -> [u8; 4] {
    if width == 0 || height == 0 || src.len() < width * height * 4 {
        return [0, 0, 0, 255];
    }
    let cx = x.clamp(0.0, (width - 1) as f32);

    let cy = y.clamp(0.0, (height - 1) as f32);
    let x0 = cx.floor() as usize;
    let y0 = cy.floor() as usize;
    let x1 = (x0 + 1).min(width - 1);
    let y1 = (y0 + 1).min(height - 1);
    let fx = cx - x0 as f32;
    let fy = cy - y0 as f32;
    let mut out = [0u8; 4];
    for ch in 0..4 {
        let p00 = src[(y0 * width + x0) * 4 + ch] as f32;
        let p10 = src[(y0 * width + x1) * 4 + ch] as f32;
        let p01 = src[(y1 * width + x0) * 4 + ch] as f32;
        let p11 = src[(y1 * width + x1) * 4 + ch] as f32;
        let top = p00 + (p10 - p00) * fx;
        let bottom = p01 + (p11 - p01) * fx;
        out[ch] = (top + (bottom - top) * fy).round().clamp(0.0, 255.0) as u8;
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn corner_ordering_follows_paddleocrs_mini_box_rule() {
        // A rotated strip, corners supplied in a deliberately wrong order.
        let p = [
            Point::new(70.0, 30.0),
            Point::new(20.0, 10.0),
            Point::new(20.0, 40.0),
            Point::new(70.0, 10.0),
        ];
        let q = Quad::ordered(p);
        assert_eq!(q.0[0], Point::new(20.0, 10.0)); // tl
        assert_eq!(q.0[1], Point::new(70.0, 10.0)); // tr
        assert_eq!(q.0[2], Point::new(70.0, 30.0)); // br
        assert_eq!(q.0[3], Point::new(20.0, 40.0)); // bl
    }

    #[test]
    fn rect_size_and_orientation() {
        let horizontal = Quad::ordered([
            Point::new(0.0, 0.0),
            Point::new(100.0, 0.0),
            Point::new(100.0, 20.0),
            Point::new(0.0, 20.0),
        ]);
        assert_eq!(horizontal.rect_size(), (100.0, 20.0));
        assert!(!horizontal.is_vertical());

        // A vertical Japanese column is tall and narrow.
        let vertical = Quad::ordered([
            Point::new(10.0, 0.0),
            Point::new(40.0, 0.0),
            Point::new(40.0, 120.0),
            Point::new(10.0, 120.0),
        ]);
        assert!(vertical.is_vertical());
        assert_eq!(vertical.rect_size(), (30.0, 120.0));
    }

    #[test]
    fn a_degenerate_quad_is_rejected_before_it_reaches_the_crop() {
        let flat = Quad::ordered([
            Point::new(0.0, 0.0),
            Point::new(0.0, 0.0),
            Point::new(0.0, 0.0),
            Point::new(0.0, 0.0),
        ]);
        assert!(!flat.is_valid());

        let nan = Quad::ordered([
            Point::new(f32::NAN, 0.0),
            Point::new(10.0, 0.0),
            Point::new(10.0, 10.0),
            Point::new(0.0, 10.0),
        ]);
        assert!(!nan.is_valid());
    }

    #[test]
    fn cropping_an_axis_aligned_quad_reproduces_the_source_region() {
        // 4x2 image; each pixel carries its index as a grey level:
        //   0 10 20 30
        //  40 50 60 70
        let (w, h) = (4usize, 2usize);
        let mut src = Vec::new();
        for i in 0..(w * h) {
            let v = (i * 10) as u8;
            src.extend_from_slice(&[v, v, v, 255]);
        }
        let quad = Quad::ordered([
            Point::new(1.0, 0.0),
            Point::new(3.0, 0.0),
            Point::new(3.0, 2.0),
            Point::new(1.0, 2.0),
        ]);
        let cropped = crop_quad(&src, w, h, &quad, 2, 2);
        assert_eq!(cropped.len(), 2 * 2 * 4);
        // Sampling is bilinear *at pixel centres*, so the first output pixel sits at source
        // (1.5, 0.5): halfway between 10 and 20 on the top row and 50/60 on the bottom row -> 35.
        assert_eq!(cropped[0], 35);
        // The second output pixel sits at source (2.5, 0.5): 25 and 65 -> 45.
        assert_eq!(cropped[4], 45);
    }

    #[test]
    fn a_quad_reaching_outside_the_image_is_clamped_rather_than_panicking() {
        let src = vec![128u8; 2 * 2 * 4];
        let quad = Quad::ordered([
            Point::new(-50.0, -50.0),
            Point::new(500.0, -50.0),
            Point::new(500.0, 500.0),
            Point::new(-50.0, 500.0),
        ]);
        let out = crop_quad(&src, 2, 2, &quad, 4, 4);
        assert_eq!(out.len(), 4 * 4 * 4);
        assert!(out.iter().all(|v| *v == 128));
    }

    #[test]
    fn an_affine_transform_maps_source_corners_onto_destination_corners() {
        let from = [
            Point::new(0.0, 0.0),
            Point::new(10.0, 0.0),
            Point::new(0.0, 10.0),
        ];
        let to = [
            Point::new(5.0, 5.0),
            Point::new(15.0, 5.0),
            Point::new(5.0, 15.0),
        ];
        let m = affine_from_triangle(from, to).expect("invertible");
        let (x, y) = m.apply(0.0, 0.0);
        assert!((x - 5.0).abs() < 1e-4 && (y - 5.0).abs() < 1e-4);
        let (x, y) = m.apply(10.0, 10.0);
        assert!((x - 15.0).abs() < 1e-4 && (y - 15.0).abs() < 1e-4);
        assert!(affine_from_triangle(
            [Point::new(0.0, 0.0), Point::new(1.0, 1.0), Point::new(2.0, 2.0)],
            to
        )
        .is_none());
    }
}

