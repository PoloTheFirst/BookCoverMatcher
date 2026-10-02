export const OPENCV_URL  = ['libs/opencv.js',      'https://docs.opencv.org/4.10.0/opencv.js'];
export const EXCELJS_URL = ['libs/exceljs.min.js', 'https://cdnjs.cloudflare.com/ajax/libs/exceljs/4.4.0/exceljs.min.js'];
export const JSZIP_URL   = ['libs/jszip.min.js',   'https://cdnjs.cloudflare.com/ajax/libs/jszip/3.10.1/jszip.min.js'];

export const GUIDE_W = 0.70;
export const GUIDE_H = 0.78;

export const BTN_MATCH_LABEL = '🔗 Link & Find Top 5';

export const THUMB_SCAN_W = 112;
export const THUMB_SCAN_H = 152;
export const THUMB_MATCH_W = 88;
export const THUMB_MATCH_H = 118;

export const MAX_IMAGE_DECODE = 800;
export const MAX_BUFFER_DECODE = 640;

export const MAX_EXTRACTED_IMAGES = 300;
export const TOP_MATCHES = 5;

export const WISHLIST_PROMPT_MS = 5000;
export const WISHLIST_STORAGE_KEY = 'bcm_wishlist_v1';

/* ---------- auto-scan ---------- */
export const AUTO_SCAN_INTERVAL_MS     = 400;
export const AUTO_SCAN_STABLE_FRAMES   = 3;
export const AUTO_SCAN_STABLE_DISTANCE = 4;
export const AUTO_SCAN_DUPLICATE_DISTANCE = 8;
export const AUTO_SCAN_COOLDOWN_MS     = 1500;
export const AUTO_SCAN_BLUR_THRESHOLD  = 40;
export const AUTO_SCAN_DARK_THRESHOLD  = 45;
export const AUTO_SCAN_BRIGHT_THRESHOLD = 215;
export const AUTO_SCAN_RESET_BAD_FRAMES = 5;
export const AUTO_SCAN_STORAGE_KEY     = 'bcm_autoscan_v1';