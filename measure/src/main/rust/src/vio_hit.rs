impl VioSession {
    /// All sane hits along a screen ray, best feature point first.
    ///
    /// The previous implementation returned the landmark with the smallest
    /// perpendicular distance with no threshold, and intersected one
    /// infinite plane with no distance or extent check, so any tap
    /// produced a confident point however meaningless it was. Candidates
    /// here must lie within [MIN_HIT_DISTANCE_M, MAX_HIT_DISTANCE_M] of
    /// the camera; feature points must sit within MAX_FEATURE_ANGLE_RAD
    /// of the ray; a plane hit must fall near the observed inlier extent
    /// rather than anywhere on the infinite fitted plane.
    pub fn hit_candidates(&self, px: f32, py: f32) -> Vec<HitCandidate> {
        if self.quality == Quality::Initialising || self.quality == Quality::Lost {
            return Vec::new();
        }
        let (nx, ny) = self.intr.unproject(px, py);
        let dir_cam = Vector3::new(nx, ny, 1.0);
        let rt = self.pose.r.transpose();
        let dir_world = rt * dir_cam;
        let origin = self.pose.center();
        let dn = dir_world.norm();
        if dn < 1e-9 {
            return Vec::new();
        }
        let unit = dir_world / dn;
        let mut out = Vec::new();

        if let Some(ref plane) = self.plane {
            if let Some(hit) = plane.intersect_ray(&origin, &dir_world) {
                let dist = (hit - origin).norm();
                if (MIN_HIT_DISTANCE_M..=MAX_HIT_DISTANCE_M).contains(&dist)
                    && self.within_plane_extent(plane, &hit)
                {
                    let up = -self.gravity;
                    let normal_dot_up = if up.norm() > 1e-9 {
                        (plane.normal.dot(&up) / up.norm()).abs()
                    } else {
                        1.0
                    };
                    out.push(HitCandidate {
                        kind: HitCandidate::KIND_PLANE,
                        point: hit,
                        distance_m: dist,
                        angular_error_rad: 0.0,
                        normal_dot_up,
                    });
                }
            }
        }

        let mut features: Vec<HitCandidate> = Vec::new();
        for lm in &self.landmarks {
            let v = *lm - origin;
            let along = v.dot(&unit);
            if !(MIN_HIT_DISTANCE_M..=MAX_HIT_DISTANCE_M).contains(&along) {
                continue;
            }
            let perp = (v - unit * along).norm();
            let angle = (perp / along).atan();
            if angle > MAX_FEATURE_ANGLE_RAD {
                continue;
            }
            features.push(HitCandidate {
                kind: HitCandidate::KIND_FEATURE,
                point: *lm,
                distance_m: along,
                angular_error_rad: angle,
                normal_dot_up: -1.0,
            });
        }
        features.sort_by(|a, b| {
            a.angular_error_rad
                .total_cmp(&b.angular_error_rad)
                .then_with(|| a.distance_m.total_cmp(&b.distance_m))
        });
        features.truncate(MAX_FEATURE_CANDIDATES);
        out.extend(features);
        out
    }

    /// A plane hit must land near where the plane was actually observed:
    /// within the inlier spread around their centroid, plus a margin.
    fn within_plane_extent(&self, plane: &Plane, hit: &Vector3<f64>) -> bool {
        if plane.inliers.is_empty() || self.landmarks.is_empty() {
            return true;
        }
        let mut centroid = Vector3::zeros();
        let mut count = 0usize;
        for &i in &plane.inliers {
            if let Some(p) = self.landmarks.get(i) {
                centroid += *p;
                count += 1;
            }
        }
        if count == 0 {
            return true;
        }
        centroid /= count as f64;
        let mut max_r = 0.0f64;
        for &i in &plane.inliers {
            if let Some(p) = self.landmarks.get(i) {
                max_r = max_r.max((*p - centroid).norm());
            }
        }
        (*hit - centroid).norm() <= max_r + PLANE_EXTENT_MARGIN_M
    }
}


/// One sane hit along a ray. `kind` mirrors the Kotlin HitKind ordinals
/// used across JNI: 0 = plane, 1 = feature point. `normal_dot_up` is -1
/// when the hit has no surface normal.
#[derive(Clone, Copy, Debug)]
pub struct HitCandidate {
    pub kind: i32,
    pub point: Vector3<f64>,
    pub distance_m: f64,
    pub angular_error_rad: f64,
    pub normal_dot_up: f64,
}

impl HitCandidate {
    pub const KIND_PLANE: i32 = 0;
    pub const KIND_FEATURE: i32 = 1;
}

const MIN_HIT_DISTANCE_M: f64 = 0.15;
const MAX_HIT_DISTANCE_M: f64 = 8.0;
/// ~2.5 degrees: a tap is only allowed to claim a landmark near its ray.
const MAX_FEATURE_ANGLE_RAD: f64 = 0.0436;
const PLANE_EXTENT_MARGIN_M: f64 = 0.75;
const MAX_FEATURE_CANDIDATES: usize = 8;

