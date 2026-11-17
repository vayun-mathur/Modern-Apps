//! Yuu avatar model: joint hierarchy, trait mapping, forward kinematics.
//!
//! The skeleton is the v1 upper-body rig: `root -> spine -> chest -> neck ->
//! head`, plus `clavicle_L/R -> upperArm -> forearm -> hand` on each side
//! (~13 joints, no hips/legs/feet). Joint order here is the bone-matrix UBO
//! order, so it must match the skinning shader's `bones[]` indexing.
//!
//! Poses live in [`crate::pose`]. Trait mapping from `YuuFaceConfig` JSON is
//! coarse by design (see the plan): skin tone tints the material, body
//! height/weight scale bones, face shape scales the head, style indices pick
//! attachment/texture variants, and mole/wrinkle/blush are v1-skipped.

use crate::pose::{JointRotation, YuuPose};
use crate::traits::YuuTraits;
use glam::{Mat4, Quat, Vec3};

/// Bone-matrix UBO capacity: 13 v1 joints fit with headroom for attachments.
pub const MAX_BONES: usize = 32;

/// Joint indices in UBO order.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
#[repr(usize)]
pub enum Joint {
    /// Scene root (identity base for the turntable sway).
    Root = 0,
    /// Lower torso; body height scales this chain.
    Spine = 1,
    /// Upper torso; body weight widens this chain.
    Chest = 2,
    /// Head stalk; face shape scales the head above it.
    Neck = 3,
    /// Face + hair + attachments anchor.
    Head = 4,
    /// Left collar anchor.
    ClavicleL = 5,
    /// Left upper arm.
    UpperArmL = 6,
    /// Left forearm.
    ForearmL = 7,
    /// Left hand (wave/thumbs-up end effector).
    HandL = 8,
    /// Right collar anchor.
    ClavicleR = 9,
    /// Right upper arm.
    UpperArmR = 10,
    /// Right forearm.
    ForearmR = 11,
    /// Right hand (wave/thumbs-up end effector).
    HandR = 12,
}

/// Number of modeled joints (indices `0..JOINT_COUNT`).
pub const JOINT_COUNT: usize = 13;

/// Rest-pose local offsets (metres) from each joint's parent.
pub fn rest_offset(joint: Joint) -> Vec3 {
    match joint {
        Joint::Root => Vec3::ZERO,
        Joint::Spine => Vec3::new(0.0, 0.55, 0.0),
        Joint::Chest => Vec3::new(0.0, 0.28, 0.0),
        Joint::Neck => Vec3::new(0.0, 0.22, 0.0),
        Joint::Head => Vec3::new(0.0, 0.14, 0.0),
        Joint::ClavicleL => Vec3::new(-0.16, 0.16, 0.0),
        Joint::UpperArmL => Vec3::new(-0.10, -0.04, 0.0),
        Joint::ForearmL => Vec3::new(0.0, -0.26, 0.0),
        Joint::HandL => Vec3::new(0.0, -0.24, 0.0),
        Joint::ClavicleR => Vec3::new(0.16, 0.16, 0.0),
        Joint::UpperArmR => Vec3::new(0.10, -0.04, 0.0),
        Joint::ForearmR => Vec3::new(0.0, -0.26, 0.0),
        Joint::HandR => Vec3::new(0.0, -0.24, 0.0),
    }
}

/// Parent joint index, if any.
pub fn parent_of(joint: Joint) -> Option<Joint> {
    match joint {
        Joint::Root => None,
        Joint::Spine => Some(Joint::Root),
        Joint::Chest => Some(Joint::Spine),
        Joint::Neck => Some(Joint::Chest),
        Joint::Head => Some(Joint::Neck),
        Joint::ClavicleL => Some(Joint::Chest),
        Joint::UpperArmL => Some(Joint::ClavicleL),
        Joint::ForearmL => Some(Joint::UpperArmL),
        Joint::HandL => Some(Joint::ForearmL),
        Joint::ClavicleR => Some(Joint::Chest),
        Joint::UpperArmR => Some(Joint::ClavicleR),
        Joint::ForearmR => Some(Joint::UpperArmR),
        Joint::HandR => Some(Joint::ForearmR),
    }
}

/// Children of a joint, in hierarchy order.
pub fn children_of(joint: Joint) -> &'static [Joint] {
    match joint {
        Joint::Root => &[Joint::Spine],
        Joint::Spine => &[Joint::Chest],
        Joint::Chest => &[Joint::Neck, Joint::ClavicleL, Joint::ClavicleR],
        Joint::Neck => &[Joint::Head],
        Joint::Head => &[],
        Joint::ClavicleL => &[Joint::UpperArmL],
        Joint::UpperArmL => &[Joint::ForearmL],
        Joint::ForearmL => &[Joint::HandL],
        Joint::HandL => &[],
        Joint::ClavicleR => &[Joint::UpperArmR],
        Joint::UpperArmR => &[Joint::ForearmR],
        Joint::ForearmR => &[Joint::HandR],
        Joint::HandR => &[],
    }
}

/// All joints in parent-before-child order (safe for forward kinematics).
pub const ALL_JOINTS: [Joint; JOINT_COUNT] = [
    Joint::Root,
    Joint::Spine,
    Joint::Chest,
    Joint::Neck,
    Joint::Head,
    Joint::ClavicleL,
    Joint::UpperArmL,
    Joint::ForearmL,
    Joint::HandL,
    Joint::ClavicleR,
    Joint::UpperArmR,
    Joint::ForearmR,
    Joint::HandR,
];

/// Joint name key used in pose joint maps.
pub fn joint_name(joint: Joint) -> &'static str {
    match joint {
        Joint::Root => "root",
        Joint::Spine => "spine",
        Joint::Chest => "chest",
        Joint::Neck => "neck",
        Joint::Head => "head",
        Joint::ClavicleL => "clavicle_L",
        Joint::UpperArmL => "upperArm_L",
        Joint::ForearmL => "forearm_L",
        Joint::HandL => "hand_L",
        Joint::ClavicleR => "clavicle_R",
        Joint::UpperArmR => "upperArm_R",
        Joint::ForearmR => "forearm_R",
        Joint::HandR => "hand_R",
    }
}

/// Look up a joint by its [`joint_name`] key.
pub fn joint_by_name(name: &str) -> Option<Joint> {
    ALL_JOINTS.iter().copied().find(|j| joint_name(*j) == name)
}

/// Compute world-space bone matrices for a pose over the rest skeleton.
///
/// Applies trait scales (height on spine/chest, weight on chest/arms, head
/// scale on head), then forward kinematics through [`ALL_JOINTS`]. Output is
/// `MAX_BONES` skinning matrices (`world * inverse_bind`): mesh vertices are
/// authored in model space, so each bone must first un-apply its rest pose
/// before applying the posed transform — without the inverse bind, parts
/// render scattered/exploded (double-transformed).
pub fn compute_bone_matrices(traits: &YuuTraits, pose: &YuuPose, time_s: f32) -> [Mat4; MAX_BONES] {
    let inverse_bind = rest_world_matrices();
    let mut out = [Mat4::IDENTITY; MAX_BONES];
    // Gentle idle sway on the root so heroes never look frozen.
    let sway = (time_s * 1.2).sin() * 0.02;
    // World matrices keyed by UBO joint index (= discriminant today; the
    // lookup below keeps parenting correct even if the enum is reordered).
    let mut world = [Mat4::IDENTITY; JOINT_COUNT];
    for joint in ALL_JOINTS.iter().copied() {
        let mut offset = rest_offset(joint);
        // Trait scales.
        match joint {
            Joint::Spine | Joint::Chest => {
                offset.y *= traits.height_scale();
            }
            _ => {}
        }
        match joint {
            Joint::Chest | Joint::ClavicleL | Joint::ClavicleR | Joint::UpperArmL | Joint::UpperArmR => {
                offset.x *= traits.weight_scale();
            }
            _ => {}
        }
        let mut rot = pose
            .joints
            .get(joint_name(joint))
            .copied()
            .unwrap_or_else(JointRotation::identity)
            .to_quat();
        if joint == Joint::Root {
            rot *= Quat::from_rotation_y(sway);
        }
        let mut scale = Vec3::ONE;
        if joint == Joint::Head {
            scale = traits.head_scale();
        }
        let local = Mat4::from_translation(offset) * Mat4::from_quat(rot) * Mat4::from_scale(scale);
        // Parent lookup by ALL_JOINTS position (not discriminant) so a future
        // enum reorder/insert can't silently break parenting.
        let parent_world = match parent_of(joint) {
            Some(p) => {
                let pi = ALL_JOINTS.iter().position(|jj| *jj == p).unwrap_or(usize::MAX);
                if pi == usize::MAX {
                    Mat4::IDENTITY
                } else {
                    world[pi]
                }
            }
            None => Mat4::IDENTITY,
        };
        let ji = ALL_JOINTS.iter().position(|jj| *jj == joint).unwrap_or(usize::MAX);
        if ji != usize::MAX {
            world[ji] = parent_world * local;
        }
        // Skinning matrix: un-apply the rest pose, then apply the posed world
        // transform. Vertices are authored in model space.
        out[joint as usize] = parent_world * local * inverse_bind[joint as usize];
    }
    out
}

/// Inverse bind-pose matrices: inverse of each joint's rest-pose world matrix
/// (identity rotations, unit scales, unscaled offsets). Precomputed per call
/// (13 joints — negligible) so `compute_bone_matrices` stays pure.
fn rest_world_matrices() -> [Mat4; MAX_BONES] {
    let mut world = [Mat4::IDENTITY; JOINT_COUNT];
    for joint in ALL_JOINTS.iter().copied() {
        let local = Mat4::from_translation(rest_offset(joint));
        let parent_world = match parent_of(joint) {
            Some(p) => {
                let pi = ALL_JOINTS.iter().position(|jj| *jj == p).unwrap_or(usize::MAX);
                if pi == usize::MAX {
                    Mat4::IDENTITY
                } else {
                    world[pi]
                }
            }
            None => Mat4::IDENTITY,
        };
        let ji = ALL_JOINTS.iter().position(|jj| *jj == joint).unwrap_or(usize::MAX);
        if ji != usize::MAX {
            world[ji] = parent_world * local;
        }
    }
    let mut out = [Mat4::IDENTITY; MAX_BONES];
    for joint in ALL_JOINTS.iter().copied() {
        let ji = ALL_JOINTS.iter().position(|jj| *jj == joint).unwrap_or(usize::MAX);
        if ji != usize::MAX {
            out[joint as usize] = world[ji].inverse();
        }
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::pose::YuuPose;
    use crate::traits::YuuTraits;
    use std::collections::HashMap;

    #[test]
    fn skeleton_order_is_topological() {
        for (i, joint) in ALL_JOINTS.iter().copied().enumerate() {
            if let Some(p) = parent_of(joint) {
                let pi = ALL_JOINTS.iter().position(|j| *j == p).unwrap();
                assert!(pi < i);
            }
        }
    }

    #[test]
    fn idle_pose_is_identity_at_origin() {
        let traits = YuuTraits::default();
        let pose = YuuPose { id: "idle".into(), name: "Idle".into(), joints: HashMap::new() };
        let bones = compute_bone_matrices(&traits, &pose, 0.0);
        // Root stays at origin (sway is ~0 at t=0).
        let t = bones[Joint::Root as usize].transform_point3(glam::Vec3::ZERO);
        assert!(t.length() < 1e-5);
    }

    #[test]
    fn idle_bones_hold_model_space_points() {
        // Regression: bone matrices must include the inverse bind pose, so an
        // idle skeleton leaves model-space vertices where authored. (Without
        // it, every part renders double-transformed — scattered/exploded.)
        let traits = YuuTraits::default();
        let pose = YuuPose { id: "idle".into(), name: "Idle".into(), joints: HashMap::new() };
        let bones = compute_bone_matrices(&traits, &pose, 0.0);
        // Head-center vertex (bound fully to head) must stay at its authored
        // position under the idle pose.
        let head_m = bones[Joint::Head as usize];
        let p = head_m.transform_point3(glam::Vec3::new(0.0, 1.28, 0.0));
        assert!((p - glam::Vec3::new(0.0, 1.28, 0.0)).length() < 1e-3, "head drifted to {p:?}");
        // Hand-center vertex (bound fully to hand) likewise.
        let hand_m = bones[Joint::HandR as usize];
        let q = hand_m.transform_point3(glam::Vec3::new(0.31, 0.34, 0.0));
        assert!((q - glam::Vec3::new(0.31, 0.34, 0.0)).length() < 1e-3, "hand drifted to {q:?}");
    }

    #[test]
    fn traits_parse_leniently() {
        let t = YuuTraits::from_config_json("{}");
        assert_eq!(t.body_height, 0.5);
        let bad = YuuTraits::from_config_json("not json");
        assert_eq!(bad.body_height, 0.5);
    }

    #[test]
    fn traits_parse_kotlin_camel_case() {
        // Regression: Kotlin emits camelCase keys (YuuFaceConfig.encode()).
        // Before the alias bridge these all silently defaulted, so no slider
        // or selection ever reached the engine.
        let json = r#"{"faceShape":5,"skinTone":4293457546,"bodyHeight":0.8,"bodyWeight":0.2,"hairStyle":3,"hairColor":4284510252}"#;
        let t = YuuTraits::from_config_json(json);
        assert_eq!(t.face_shape, 5);
        assert_eq!(t.skin_tone, 4293457546);
        assert!((t.body_height - 0.8).abs() < 1e-6);
        assert!((t.body_weight - 0.2).abs() < 1e-6);
        assert_eq!(t.hair_style, 3);
        assert_eq!(t.hair_color, 4284510252);
    }

    #[test]
    fn traits_parse_full_kotlin_config() {
        // Full 29-field Kotlin-shaped fixture: every field must parse (no
        // silent drops), and the bone-driving subset must affect matrices.
        let json = r#"{"faceShape":2,"skinTone":4293457546,"earSize":0.7,"bodyHeight":0.8,"bodyWeight":0.2,"hairStyle":3,"hairColor":4284510252,"eyeStyle":4,"eyeColor":4284510253,"eyeSize":0.6,"eyeSpacing":0.4,"eyeHeight":0.7,"eyeRotation":0.3,"browStyle":2,"browColor":4284510254,"browHeight":0.6,"browRotation":0.4,"noseStyle":3,"noseHeight":0.8,"mouthStyle":5,"mouthColor":4284510255,"mouthSize":0.7,"mouthHeight":0.3,"glassesStyle":2,"beardStyle":1,"moleStyle":1,"wrinkleStyle":2,"blushStyle":1,"poseId":"wave"}"#;
        let t = YuuTraits::from_config_json(json);
        assert_eq!(t.face_shape, 2);
        assert_eq!(t.skin_tone, 4293457546);
        assert!((t.ear_size - 0.7).abs() < 1e-6);
        assert!((t.body_height - 0.8).abs() < 1e-6);
        assert!((t.body_weight - 0.2).abs() < 1e-6);
        assert_eq!(t.hair_style, 3);
        assert_eq!(t.hair_color, 4284510252);
        assert_eq!(t.eye_style, 4);
        assert_eq!(t.eye_color, 4284510253);
        assert!((t.eye_size - 0.6).abs() < 1e-6);
        assert!((t.eye_spacing - 0.4).abs() < 1e-6);
        assert!((t.eye_height - 0.7).abs() < 1e-6);
        assert!((t.eye_rotation - 0.3).abs() < 1e-6);
        assert_eq!(t.brow_style, 2);
        assert_eq!(t.brow_color, 4284510254);
        assert!((t.brow_height - 0.6).abs() < 1e-6);
        assert!((t.brow_rotation - 0.4).abs() < 1e-6);
        assert_eq!(t.nose_style, 3);
        assert!((t.nose_height - 0.8).abs() < 1e-6);
        assert_eq!(t.mouth_style, 5);
        assert_eq!(t.mouth_color, 4284510255);
        assert!((t.mouth_size - 0.7).abs() < 1e-6);
        assert!((t.mouth_height - 0.3).abs() < 1e-6);
        assert_eq!(t.glasses_style, 2);
        assert_eq!(t.beard_style, 1);
        assert_eq!(t.mole_style, 1);
        assert_eq!(t.wrinkle_style, 2);
        assert_eq!(t.blush_style, 1);
        assert_eq!(t.pose_id.as_deref(), Some("wave"));
        // Bone-driving subset spot-check: bodyHeight 0.8 → taller spine.
        assert!((t.height_scale() - 1.06).abs() < 1e-6);
        assert!((t.weight_scale() - 0.91).abs() < 1e-6);
    }
}


#[cfg(test)]
mod tests_extra {
    use super::*;
    use crate::pose::{preset_by_id, JointRotation, YuuPose};
    use crate::traits::YuuTraits;
    use std::collections::HashMap;

    fn idle() -> YuuPose {
        YuuPose { id: "idle".into(), name: "Idle".into(), joints: HashMap::new() }
    }

    #[test]
    fn name_roundtrip_and_discriminants_match_ubo_order() {
        for (i, joint) in ALL_JOINTS.iter().copied().enumerate() {
            assert_eq!(joint as usize, i, "UBO discriminant drifted for {joint:?}");
            assert_eq!(joint_by_name(joint_name(joint)), Some(joint));
        }
        assert_eq!(JOINT_COUNT, 13);
        assert_eq!(ALL_JOINTS.len(), JOINT_COUNT);
        assert!(MAX_BONES >= JOINT_COUNT);
        for bad in ["", "Head", "head ", "clavicle_l", "unknown"] {
            assert_eq!(joint_by_name(bad), None, "{bad:?}");
        }
    }

    #[test]
    fn parent_children_are_mutually_consistent() {
        let mut edges = 0;
        for joint in ALL_JOINTS {
            for child in children_of(joint) {
                assert_eq!(parent_of(*child), Some(joint), "{child:?}");
                edges += 1;
            }
            if let Some(parent) = parent_of(joint) {
                assert!(children_of(parent).contains(&joint), "{joint:?}");
            }
        }
        assert_eq!(edges, JOINT_COUNT - 1, "skeleton must be a single tree");
        assert_eq!(parent_of(Joint::Root), None);
        assert!(children_of(Joint::Head).is_empty());
        assert!(children_of(Joint::HandL).is_empty() && children_of(Joint::HandR).is_empty());
        assert_eq!(children_of(Joint::Chest), &[Joint::Neck, Joint::ClavicleL, Joint::ClavicleR]);
    }

    #[test]
    fn rest_offsets_are_mirror_symmetric_and_form_chains() {
        assert_eq!(rest_offset(Joint::Root), Vec3::ZERO);
        for (l, r) in [
            (Joint::ClavicleL, Joint::ClavicleR),
            (Joint::UpperArmL, Joint::UpperArmR),
            (Joint::ForearmL, Joint::ForearmR),
            (Joint::HandL, Joint::HandR),
        ] {
            let (a, b) = (rest_offset(l), rest_offset(r));
            assert_eq!(a.x, -b.x, "{l:?}/{r:?} x must mirror");
            assert_eq!((a.y, a.z), (b.y, b.z));
        }
        // Head chain accumulates upward; arms hang downward from the clavicles.
        let torso_y: f32 = [Joint::Spine, Joint::Chest, Joint::Neck, Joint::Head]
            .iter().map(|j| rest_offset(*j).y).sum();
        assert!((torso_y - 1.19).abs() < 1e-6, "head joint rest height {torso_y}");
        assert!(rest_offset(Joint::ForearmL).y < 0.0 && rest_offset(Joint::HandR).y < 0.0);
    }

    #[test]
    fn bones_beyond_joint_count_are_identity_and_all_finite() {
        for pose_id in ["idle", "wave", "thumbs_up", "thinking", "cheering"] {
            let pose = preset_by_id(pose_id).expect("preset");
            let bones = compute_bone_matrices(&YuuTraits::default(), &pose, 0.37);
            for (i, m) in bones.iter().enumerate() {
                assert!(m.to_cols_array().iter().all(|v| v.is_finite()), "{pose_id} bone {i}");
                if i >= JOINT_COUNT {
                    assert_eq!(*m, Mat4::IDENTITY, "padding bone {i} must stay identity");
                }
            }
        }
    }

    #[test]
    fn compute_is_deterministic_and_unknown_pose_joints_are_ignored() {
        let mut pose = idle();
        pose.joints.insert("not_a_joint".into(), JointRotation { x: 45.0, y: 0.0, z: 0.0 });
        pose.joints.insert("head".into(), JointRotation { x: 0.0, y: 30.0, z: 0.0 });
        let a = compute_bone_matrices(&YuuTraits::default(), &pose, 0.0);
        let b = compute_bone_matrices(&YuuTraits::default(), &pose, 0.0);
        assert_eq!(a, b);
        // Head rotation actually moves the head bone vs idle.
        let plain = compute_bone_matrices(&YuuTraits::default(), &idle(), 0.0);
        assert_ne!(a[Joint::Head as usize], plain[Joint::Head as usize]);
    }

    #[test]
    fn taller_traits_raise_and_heavier_traits_widen_skinning() {
        let short = YuuTraits { body_height: 0.0, ..Default::default() };
        let tall = YuuTraits { body_height: 1.0, ..Default::default() };
        let head_pt = Vec3::new(0.0, 1.28, 0.0);
        let y = |t: &YuuTraits| {
            compute_bone_matrices(t, &idle(), 0.0)[Joint::Head as usize].transform_point3(head_pt).y
        };
        assert!(y(&tall) > y(&short) + 0.05, "tall {} vs short {}", y(&tall), y(&short));
        let lean = YuuTraits { body_weight: 0.0, ..Default::default() };
        let heavy = YuuTraits { body_weight: 1.0, ..Default::default() };
        let hand_pt = Vec3::new(0.31, 0.34, 0.0);
        let x = |t: &YuuTraits| {
            compute_bone_matrices(t, &idle(), 0.0)[Joint::HandR as usize]
                .transform_point3(hand_pt).x.abs()
        };
        assert!(x(&heavy) > x(&lean), "heavy {} vs lean {}", x(&heavy), x(&lean));
    }

    #[test]
    fn head_scale_applies_and_idle_sway_is_small_and_periodic_in_effect() {
        let wide = YuuTraits { face_shape: 7, ..Default::default() };
        let bones = compute_bone_matrices(&wide, &idle(), 0.0);
        let p = bones[Joint::Head as usize].transform_point3(Vec3::new(0.1, 1.28, 0.0));
        // Head-local +x point is scaled by head_scale().x = 1.12 about the head origin.
        assert!(p.x > 0.1, "scaled head point {p:?}");
        // Sway at t=0 is zero; a quarter period later the root bone has rotated
        // but a point on the rotation axis (origin) stays put.
        let t_quarter = std::f32::consts::FRAC_PI_2 / 1.2;
        let swayed = compute_bone_matrices(&YuuTraits::default(), &idle(), t_quarter);
        let origin = swayed[Joint::Root as usize].transform_point3(Vec3::ZERO);
        assert!(origin.length() < 1e-5);
        let off_axis = swayed[Joint::Root as usize].transform_point3(Vec3::new(1.0, 0.0, 0.0));
        assert!((off_axis - Vec3::new(1.0, 0.0, 0.0)).length() > 1e-4, "sway must rotate");
        assert!((off_axis - Vec3::new(1.0, 0.0, 0.0)).length() < 0.05, "sway must stay gentle");
    }
}
