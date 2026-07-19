//! waveCorrect (motion_estimators.cpp, WAVE_CORRECT_HORIZ): removes the vertical
//! "wave" by re-aligning all camera rotations to a common up/right frame.

use crate::camera::CameraParams;
use nalgebra::{Matrix3, Vector3};

pub fn wave_correct_horizontal(cams: &mut [CameraParams]) {
    if cams.len() <= 1 {
        return;
    }
    // moment = Σ col0 · col0ᵀ
    let mut moment = Matrix3::<f64>::zeros();
    for c in cams.iter() {
        let col0 = c.r.column(0).into_owned();
        moment += col0 * col0.transpose();
    }
    let se = moment.symmetric_eigen();
    // eigenvector for the smallest eigenvalue
    let mut min_i = 0;
    for i in 1..3 {
        if se.eigenvalues[i] < se.eigenvalues[min_i] {
            min_i = i;
        }
    }
    let mut rg1 = se.eigenvectors.column(min_i).into_owned();

    // img_k = Σ col2
    let mut img_k = Vector3::<f64>::zeros();
    for c in cams.iter() {
        img_k += c.r.column(2).into_owned();
    }

    let mut rg0 = rg1.cross(&img_k);
    let n0 = rg0.norm();
    if n0 < 1e-9 {
        return;
    }
    rg0 /= n0;
    let rg2 = rg0.cross(&rg1);

    // orientation confidence
    let mut conf = 0.0;
    for c in cams.iter() {
        conf += rg0.dot(&c.r.column(0));
    }
    if conf < 0.0 {
        rg0 = -rg0;
        rg1 = -rg1;
    }
    let rg2 = if conf < 0.0 { rg0.cross(&rg1) } else { rg2 };

    // R_wave has rows rg0ᵀ, rg1ᵀ, rg2ᵀ
    let r_wave = Matrix3::from_rows(&[rg0.transpose(), rg1.transpose(), rg2.transpose()]);
    for c in cams.iter_mut() {
        c.r = r_wave * c.r;
    }
}
