//! Preset 3D poses: joint rotations over the rest skeleton.
//!
//! Split from `avatar.rs` along module lines (repo `rustFileLength` gate).
//! The engine owns the authoritative preset list; the Kotlin gallery mirrors
//! it (`YUU_POSES` — update both).

use glam::Quat;
use serde::{Deserialize, Serialize};
use std::collections::HashMap;

/// Serializable joint rotation (Euler degrees, XYZ order) for one joint.
#[derive(Clone, Copy, Debug, Default, Serialize, Deserialize)]
pub struct JointRotation {
    /// Pitch degrees.
    #[serde(default)]
    pub x: f32,
    /// Yaw degrees.
    #[serde(default)]
    pub y: f32,
    /// Roll degrees.
    #[serde(default)]
    pub z: f32,
}

impl JointRotation {
    /// Identity rotation.
    pub const fn identity() -> Self {
        Self { x: 0.0, y: 0.0, z: 0.0 }
    }

    /// Convert to a quaternion.
    pub fn to_quat(self) -> Quat {
        Quat::from_euler(
            glam::EulerRot::XYZ,
            self.x.to_radians(),
            self.y.to_radians(),
            self.z.to_radians(),
        )
    }
}

/// A named preset pose: per-joint rotations over the rest pose.
#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct YuuPose {
    /// Stable id (matches the Kotlin `poseId`).
    pub id: String,
    /// Display name.
    pub name: String,
    /// Joint rotations; joints absent from the map stay at rest.
    #[serde(default)]
    pub joints: HashMap<String, JointRotation>,
}

/// Preset gallery ids, in gallery order.
pub const POSE_IDLE: &str = "idle";
/// Friendly wave with the right hand.
pub const POSE_WAVE: &str = "wave";
/// Right-hand thumbs-up.
pub const POSE_THUMBS_UP: &str = "thumbs_up";
/// Chin resting on the right hand.
pub const POSE_THINKING: &str = "thinking";
/// Both arms raised in celebration.
pub const POSE_CHEERING: &str = "cheering";

/// Build the v1 preset gallery.
pub fn preset_poses() -> Vec<YuuPose> {
    vec![
        YuuPose { id: POSE_IDLE.into(), name: "Idle".into(), joints: HashMap::new() },
        YuuPose {
            id: POSE_WAVE.into(),
            name: "Wave".into(),
            joints: HashMap::from([
                ("upperArm_R".into(), JointRotation { x: 0.0, y: 0.0, z: -140.0 }),
                ("forearm_R".into(), JointRotation { x: 0.0, y: 0.0, z: -20.0 }),
                ("hand_R".into(), JointRotation { x: 0.0, y: 25.0, z: 0.0 }),
                ("head".into(), JointRotation { x: 0.0, y: -8.0, z: 4.0 }),
            ]),
        },
        YuuPose {
            id: POSE_THUMBS_UP.into(),
            name: "Thumbs up".into(),
            joints: HashMap::from([
                ("upperArm_R".into(), JointRotation { x: -50.0, y: 0.0, z: -30.0 }),
                ("forearm_R".into(), JointRotation { x: -40.0, y: 0.0, z: 0.0 }),
                ("hand_R".into(), JointRotation { x: -10.0, y: 0.0, z: 90.0 }),
                ("head".into(), JointRotation { x: -4.0, y: 0.0, z: 0.0 }),
            ]),
        },
        YuuPose {
            id: POSE_THINKING.into(),
            name: "Thinking".into(),
            joints: HashMap::from([
                ("upperArm_R".into(), JointRotation { x: -30.0, y: 0.0, z: -45.0 }),
                ("forearm_R".into(), JointRotation { x: -95.0, y: 0.0, z: 15.0 }),
                ("hand_R".into(), JointRotation { x: -20.0, y: 0.0, z: 0.0 }),
                ("head".into(), JointRotation { x: 8.0, y: 12.0, z: -6.0 }),
            ]),
        },
        YuuPose {
            id: POSE_CHEERING.into(),
            name: "Cheering".into(),
            joints: HashMap::from([
                ("upperArm_L".into(), JointRotation { x: 0.0, y: 0.0, z: 140.0 }),
                ("forearm_L".into(), JointRotation { x: 0.0, y: 0.0, z: 20.0 }),
                ("upperArm_R".into(), JointRotation { x: 0.0, y: 0.0, z: -140.0 }),
                ("forearm_R".into(), JointRotation { x: 0.0, y: 0.0, z: -20.0 }),
                ("head".into(), JointRotation { x: -10.0, y: 0.0, z: 0.0 }),
            ]),
        },
    ]
}

/// Find a preset by id.
pub fn preset_by_id(id: &str) -> Option<YuuPose> {
    preset_poses().into_iter().find(|p| p.id == id)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::avatar::joint_by_name;

    fn approx(a: f32, b: f32) -> bool { (a - b).abs() < 1e-5 }

    #[test]
    fn identity_rotation_is_identity_quat() {
        assert!(approx(JointRotation::identity().to_quat().length(), 1.0));
        let q = JointRotation::identity().to_quat();
        assert_eq!(q, Quat::IDENTITY);
        assert_eq!(JointRotation::default().to_quat(), Quat::IDENTITY);
    }

    #[test]
    fn axis_rotations_rotate_basis_vectors() {
        // 90 degrees about X sends +Y to +Z (XYZ Euler, glam RH).
        let v = JointRotation { x: 90.0, y: 0.0, z: 0.0 }.to_quat() * glam::Vec3::Y;
        assert!(v.distance(glam::Vec3::Z) < 1e-5, "{v:?}");
        let v = JointRotation { x: 0.0, y: 90.0, z: 0.0 }.to_quat() * glam::Vec3::Z;
        assert!(v.distance(glam::Vec3::X) < 1e-5, "{v:?}");
        let v = JointRotation { x: 0.0, y: 0.0, z: 90.0 }.to_quat() * glam::Vec3::X;
        assert!(v.distance(glam::Vec3::Y) < 1e-5, "{v:?}");
    }

    #[test]
    fn to_quat_is_unit_for_arbitrary_angles() {
        for r in [
            JointRotation { x: 33.0, y: -71.0, z: 129.0 },
            JointRotation { x: 360.0, y: 360.0, z: 360.0 },
            JointRotation { x: -180.0, y: 45.0, z: -45.0 },
        ] {
            assert!(approx(r.to_quat().length(), 1.0), "{r:?}");
            assert!(r.to_quat().to_array().iter().all(|v| v.is_finite()));
        }
        // A full turn is (numerically) identity.
        let q = JointRotation { x: 360.0, y: 0.0, z: 0.0 }.to_quat();
        assert!(q.to_array()[3].abs() > 0.999_9, "{q:?}");
    }

    #[test]
    fn joint_rotation_serde_defaults_and_roundtrip() {
        let r: JointRotation = serde_json::from_str("{}").expect("empty object");
        assert_eq!((r.x, r.y, r.z), (0.0, 0.0, 0.0));
        let r: JointRotation = serde_json::from_str(r#"{"x": 12.5}"#).expect("partial");
        assert_eq!((r.x, r.y, r.z), (12.5, 0.0, 0.0));
        let orig = JointRotation { x: 1.5, y: -2.5, z: 3.5 };
        let back: JointRotation =
            serde_json::from_str(&serde_json::to_string(&orig).expect("ser")).expect("de");
        assert_eq!((back.x, back.y, back.z), (orig.x, orig.y, orig.z));
    }

    #[test]
    fn pose_serde_defaults_joints_and_roundtrips() {
        let p: YuuPose =
            serde_json::from_str(r#"{"id":"x","name":"X"}"#).expect("pose without joints");
        assert!(p.joints.is_empty());
        let wave = preset_by_id(POSE_WAVE).expect("wave");
        let back: YuuPose =
            serde_json::from_str(&serde_json::to_string(&wave).expect("ser")).expect("de");
        assert_eq!(back.id, wave.id);
        assert_eq!(back.joints.len(), wave.joints.len());
        assert_eq!(back.joints["hand_R"].y, 25.0);
    }

    #[test]
    fn preset_gallery_shape_and_valid_joint_keys() {
        let poses = preset_poses();
        assert_eq!(poses.len(), 5);
        assert_eq!(
            poses.iter().map(|p| p.id.as_str()).collect::<Vec<_>>(),
            vec![POSE_IDLE, POSE_WAVE, POSE_THUMBS_UP, POSE_THINKING, POSE_CHEERING]
        );
        let mut ids = std::collections::HashSet::new();
        for p in &poses {
            assert!(ids.insert(p.id.clone()), "duplicate pose id {}", p.id);
            assert!(!p.name.is_empty());
            for (key, rot) in &p.joints {
                assert!(joint_by_name(key).is_some(), "pose {} bad key {key}", p.id);
                assert!(rot.x.is_finite() && rot.y.is_finite() && rot.z.is_finite());
            }
        }
        assert!(poses[0].joints.is_empty(), "idle must be the rest pose");
    }

    #[test]
    fn preset_contents_match_documented_gestures() {
        let wave = preset_by_id(POSE_WAVE).expect("wave");
        assert_eq!(wave.joints["upperArm_R"].z, -140.0);
        assert_eq!(wave.joints["hand_R"].y, 25.0);
        let cheer = preset_by_id(POSE_CHEERING).expect("cheering");
        assert_eq!(cheer.joints["upperArm_L"].z, 140.0);
        assert_eq!(cheer.joints["upperArm_R"].z, -140.0);
        let think = preset_by_id(POSE_THINKING).expect("thinking");
        assert_eq!(think.joints["forearm_R"].x, -95.0);
    }

    #[test]
    fn preset_by_id_misses_return_none() {
        assert!(preset_by_id("").is_none());
        assert!(preset_by_id("WAVE").is_none(), "ids are case-sensitive");
        assert!(preset_by_id("wave ").is_none());
        assert!(preset_by_id("unknown").is_none());
        for id in [POSE_IDLE, POSE_WAVE, POSE_THUMBS_UP, POSE_THINKING, POSE_CHEERING] {
            assert_eq!(preset_by_id(id).expect("known").id, id);
        }
    }
}
