//! Yuu trait mapping: full YuuFaceConfig JSON bridge + derived scales.
//!
//! Split from `avatar.rs` along module lines (repo `rustFileLength` gate).
//! Parses all 29 Kotlin camelCase fields (via serde aliases); the bone-driving
//! subset feeds `compute_bone_matrices`, texture params feed face decals,
//! mesh indices feed attachments.

use glam::Vec3;
use serde::{Deserialize, Serialize};

/// Coarse trait mapping from `YuuFaceConfig` JSON.
///
/// Parses the full 29-field Kotlin config (camelCase via aliases). The
/// bone-driving subset (body/face) feeds forward kinematics; face-detail
/// fields feed texture regions (decals) or attachments in later phases —
/// all parse+store with serde defaults so nothing silently drops.
#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct YuuTraits {
    /// Face shape index 0..7 (drives head scale approximation).
    #[serde(default, alias = "faceShape")]
    pub face_shape: i32,
    /// ARGB long from the fixed skin palette.
    /// `alias` accepts the Kotlin camelCase key; the snake_case name keeps
    /// existing test fixtures working.
    #[serde(default, alias = "skinTone")]
    pub skin_tone: u64,
    /// Ear size 0..1 (drives ear offset — ears attach to the head sides).
    #[serde(default = "half", alias = "earSize")]
    pub ear_size: f32,
    /// Normalized body height 0..1.
    #[serde(default = "half", alias = "bodyHeight")]
    pub body_height: f32,
    /// Normalized body weight 0..1.
    #[serde(default = "half", alias = "bodyWeight")]
    pub body_weight: f32,
    /// Hair style index (selects hair attachment node, Phase 4).
    #[serde(default, alias = "hairStyle")]
    pub hair_style: i32,
    /// ARGB long from the fixed hair palette.
    #[serde(default, alias = "hairColor")]
    pub hair_color: u64,
    /// Eye style index (selects face-atlas region, Phase 3).
    #[serde(default, alias = "eyeStyle")]
    pub eye_style: i32,
    /// ARGB long from the fixed eye palette.
    #[serde(default, alias = "eyeColor")]
    pub eye_color: u64,
    /// Eye size 0..1 (drives eye decal scale, Phase 3).
    #[serde(default = "half", alias = "eyeSize")]
    pub eye_size: f32,
    /// Eye spacing 0..1 (drives eye decal offsets, Phase 3).
    #[serde(default = "half", alias = "eyeSpacing")]
    pub eye_spacing: f32,
    /// Eye height 0..1 (drives eye decal offsets, Phase 3).
    #[serde(default = "half", alias = "eyeHeight")]
    pub eye_height: f32,
    /// Eye rotation 0..1 (stored; decal rotation lands with the atlas).
    #[serde(default = "half", alias = "eyeRotation")]
    pub eye_rotation: f32,
    /// Brow style index (selects face-atlas region, Phase 3).
    #[serde(default, alias = "browStyle")]
    pub brow_style: i32,
    /// ARGB long (brow palette = hair palette).
    #[serde(default, alias = "browColor")]
    pub brow_color: u64,
    /// Brow height 0..1 (drives brow decal offset, Phase 3).
    #[serde(default = "half", alias = "browHeight")]
    pub brow_height: f32,
    /// Brow rotation 0..1 (stored; decal rotation lands with the atlas).
    #[serde(default = "half", alias = "browRotation")]
    pub brow_rotation: f32,
    /// Nose style index (selects face-atlas region, Phase 3).
    #[serde(default, alias = "noseStyle")]
    pub nose_style: i32,
    /// Nose height 0..1 (drives nose decal offset, Phase 3).
    #[serde(default = "half", alias = "noseHeight")]
    pub nose_height: f32,
    /// Mouth style index (selects face-atlas region, Phase 3).
    #[serde(default, alias = "mouthStyle")]
    pub mouth_style: i32,
    /// ARGB long from the fixed mouth palette.
    #[serde(default, alias = "mouthColor")]
    pub mouth_color: u64,
    /// Mouth size 0..1 (drives mouth decal scale, Phase 3).
    #[serde(default = "half", alias = "mouthSize")]
    pub mouth_size: f32,
    /// Mouth height 0..1 (drives mouth decal offset, Phase 3).
    #[serde(default = "half", alias = "mouthHeight")]
    pub mouth_height: f32,
    /// Glasses style index (selects attachment node, Phase 4; 0 = none).
    #[serde(default, alias = "glassesStyle")]
    pub glasses_style: i32,
    /// Beard style index (selects attachment node, Phase 4; 0 = none).
    #[serde(default, alias = "beardStyle")]
    pub beard_style: i32,
    /// Mole style index (selects decal, Phase 3; 0 = none).
    #[serde(default, alias = "moleStyle")]
    pub mole_style: i32,
    /// Wrinkle style index (selects decal, Phase 3; 0 = none).
    #[serde(default, alias = "wrinkleStyle")]
    pub wrinkle_style: i32,
    /// Blush style index (selects decal, Phase 3; 0 = none).
    #[serde(default, alias = "blushStyle")]
    pub blush_style: i32,
    /// Pose id passthrough (engine keeps using the `set_pose_id` path;
    /// stored here for fixture parity, not consumed).
    #[serde(default, alias = "poseId")]
    pub pose_id: Option<String>,
}

fn half() -> f32 {
    0.5
}

impl Default for YuuTraits {
    fn default() -> Self {
        Self {
            face_shape: 0,
            skin_tone: 0,
            ear_size: 0.5,
            body_height: 0.5,
            body_weight: 0.5,
            hair_style: 0,
            hair_color: 0,
            eye_style: 0,
            eye_color: 0,
            eye_size: 0.5,
            eye_spacing: 0.5,
            eye_height: 0.5,
            eye_rotation: 0.5,
            brow_style: 0,
            brow_color: 0,
            brow_height: 0.5,
            brow_rotation: 0.5,
            nose_style: 0,
            nose_height: 0.5,
            mouth_style: 0,
            mouth_color: 0,
            mouth_size: 0.5,
            mouth_height: 0.5,
            glasses_style: 0,
            beard_style: 0,
            mole_style: 0,
            wrinkle_style: 0,
            blush_style: 0,
            pose_id: None,
        }
    }
}

impl YuuTraits {
    /// Parse from the `YuuFaceConfig` JSON string; unknown/missing fields fall
    /// back to defaults (mirrors the Kotlin `ignoreUnknownKeys` behavior).
    pub fn from_config_json(json: &str) -> Self {
        serde_json::from_str(json).unwrap_or_default()
    }

    /// Skin albedo as linear-ish RGB 0..1 (sRGB bytes unpacked, no transfer).
    pub fn skin_rgb(&self) -> [f32; 3] {
        argb_to_rgb(self.skin_tone, [0.91, 0.71, 0.54])
    }

    /// Hair albedo as RGB 0..1.
    pub fn hair_rgb(&self) -> [f32; 3] {
        argb_to_rgb(self.hair_color, [0.17, 0.17, 0.17])
    }

    /// Spine-chain height scale from `body_height`.
    pub fn height_scale(&self) -> f32 {
        0.9 + self.body_height.clamp(0.0, 1.0) * 0.2
    }

    /// Chest/arm width scale from `body_weight`.
    pub fn weight_scale(&self) -> f32 {
        0.85 + self.body_weight.clamp(0.0, 1.0) * 0.3
    }

    /// Head non-uniform scale approximation from `face_shape`.
    pub fn head_scale(&self) -> Vec3 {
        match self.face_shape.rem_euclid(8) {
            1 => Vec3::new(0.95, 1.0, 0.95),
            2 => Vec3::new(1.08, 0.96, 0.96),
            3 => Vec3::new(1.05, 1.0, 1.0),
            4 => Vec3::new(0.92, 1.08, 0.94),
            5 => Vec3::new(1.1, 1.0, 1.0),
            6 => Vec3::new(1.02, 0.98, 1.0),
            7 => Vec3::new(1.12, 0.94, 1.0),
            _ => Vec3::ONE,
        }
    }
}

/// Unpack an ARGB long to RGB floats, with a fallback.
fn argb_to_rgb(argb: u64, fallback: [f32; 3]) -> [f32; 3] {
    if argb == 0 {
        return fallback;
    }
    let r = ((argb >> 16) & 0xFF) as f32 / 255.0;
    let g = ((argb >> 8) & 0xFF) as f32 / 255.0;
    let b = (argb & 0xFF) as f32 / 255.0;
    [r, g, b]
}

#[cfg(test)]
mod tests {
    use super::*;

    fn rgb_approx(a: [f32; 3], b: [f32; 3]) -> bool {
        a.iter().zip(b).all(|(x, y)| (x - y).abs() < 1e-6)
    }

    #[test]
    fn default_traits_are_neutral() {
        let t = YuuTraits::default();
        assert_eq!(t.body_height, 0.5);
        assert_eq!(t.body_weight, 0.5);
        assert_eq!(t.face_shape, 0);
        assert_eq!(t.pose_id, None);
        assert!((t.height_scale() - 1.0).abs() < 1e-6);
        assert!((t.weight_scale() - 1.0).abs() < 1e-6);
        assert_eq!(t.head_scale(), Vec3::ONE);
    }

    #[test]
    fn height_and_weight_scales_hit_endpoints_and_clamp() {
        let mut t = YuuTraits::default();
        t.body_height = 0.0;
        assert!((t.height_scale() - 0.9).abs() < 1e-6);
        t.body_height = 1.0;
        assert!((t.height_scale() - 1.1).abs() < 1e-6);
        t.body_height = -3.0;
        assert!((t.height_scale() - 0.9).abs() < 1e-6, "under-range clamps low");
        t.body_height = 7.0;
        assert!((t.height_scale() - 1.1).abs() < 1e-6, "over-range clamps high");
        t.body_weight = 0.0;
        assert!((t.weight_scale() - 0.85).abs() < 1e-6);
        t.body_weight = 1.0;
        assert!((t.weight_scale() - 1.15).abs() < 1e-6);
        t.body_weight = 99.0;
        assert!((t.weight_scale() - 1.15).abs() < 1e-6);
        // Monotonic in the unclamped range.
        t.body_height = 0.25;
        let lo = t.height_scale();
        t.body_height = 0.75;
        assert!(t.height_scale() > lo);
    }

    #[test]
    fn head_scale_covers_all_shapes_and_wraps() {
        let expected = [
            Vec3::ONE,
            Vec3::new(0.95, 1.0, 0.95),
            Vec3::new(1.08, 0.96, 0.96),
            Vec3::new(1.05, 1.0, 1.0),
            Vec3::new(0.92, 1.08, 0.94),
            Vec3::new(1.1, 1.0, 1.0),
            Vec3::new(1.02, 0.98, 1.0),
            Vec3::new(1.12, 0.94, 1.0),
        ];
        for (shape, want) in expected.iter().enumerate() {
            let t = YuuTraits { face_shape: shape as i32, ..Default::default() };
            assert_eq!(t.head_scale(), *want, "shape {shape}");
            assert!(t.head_scale().to_array().iter().all(|v| *v > 0.0));
        }
        // rem_euclid wrap: 8 == 0, -1 == 7, -8 == 0.
        for (given, want) in [(8, 0usize), (15, 7), (-1, 7), (-8, 0), (-9, 7)] {
            let t = YuuTraits { face_shape: given, ..Default::default() };
            assert_eq!(t.head_scale(), expected[want], "face_shape {given}");
        }
    }

    #[test]
    fn argb_unpacking_ignores_alpha_and_zero_falls_back() {
        let mut t = YuuTraits::default();
        assert!(rgb_approx(t.skin_rgb(), [0.91, 0.71, 0.54]), "zero skin = fallback");
        assert!(rgb_approx(t.hair_rgb(), [0.17, 0.17, 0.17]), "zero hair = fallback");
        t.skin_tone = 0xFF11_2233;
        assert!(rgb_approx(t.skin_rgb(), [0x11 as f32 / 255.0, 0x22 as f32 / 255.0, 0x33 as f32 / 255.0]));
        t.skin_tone = 0x0011_2233;
        assert!(rgb_approx(t.skin_rgb(), [0x11 as f32 / 255.0, 0x22 as f32 / 255.0, 0x33 as f32 / 255.0]), "alpha byte ignored");
        t.hair_color = 0x0000_00FF;
        assert!(rgb_approx(t.hair_rgb(), [0.0, 0.0, 1.0]));
        t.hair_color = 1;
        assert!(rgb_approx(t.hair_rgb(), [0.0, 0.0, 1.0 / 255.0]), "only exactly 0 falls back");
        assert!(rgb_approx(argb_to_rgb(0xABCDEF, [9.0; 3]), [0xAB as f32 / 255.0, 0xCD as f32 / 255.0, 0xEF as f32 / 255.0]));
    }

    #[test]
    fn config_json_snake_case_unknown_and_bad_input() {
        let t = YuuTraits::from_config_json(r#"{"body_height":0.25,"face_shape":3}"#);
        assert_eq!(t.body_height, 0.25);
        assert_eq!(t.face_shape, 3);
        let t = YuuTraits::from_config_json(r#"{"nonsense":42,"bodyHeight":0.75}"#);
        assert_eq!(t.body_height, 0.75, "unknown keys ignored");
        for bad in ["", " ", "null", "[]", "42", "{", r#"{"bodyHeight":"tall"}"#] {
            let t = YuuTraits::from_config_json(bad);
            assert_eq!(t.body_height, 0.5, "bad input {bad:?} must default wholesale");
            assert_eq!(t.face_shape, 0);
        }
        // A present-but-null pose and explicit nulls on defaulted scalars.
        let t = YuuTraits::from_config_json(r#"{"poseId":null}"#);
        assert_eq!(t.pose_id, None);
    }

    #[test]
    fn traits_serde_roundtrip_preserves_all_fields() {
        let t = YuuTraits {
            face_shape: 6, skin_tone: 0xFFAA_BBCC, ear_size: 0.1, body_height: 0.2,
            body_weight: 0.9, hair_style: 11, hair_color: 7, eye_style: 5, eye_color: 8,
            eye_size: 0.3, glasses_style: 4, beard_style: 2, mole_style: 1,
            pose_id: Some("cheering".into()), ..Default::default()
        };
        let back: YuuTraits =
            serde_json::from_str(&serde_json::to_string(&t).expect("ser")).expect("de");
        assert_eq!(back.face_shape, 6);
        assert_eq!(back.skin_tone, 0xFFAA_BBCC);
        assert_eq!(back.hair_style, 11);
        assert_eq!(back.glasses_style, 4);
        assert_eq!(back.pose_id.as_deref(), Some("cheering"));
        assert!((back.body_weight - 0.9).abs() < 1e-6);
    }
}
