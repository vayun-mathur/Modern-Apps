//! A [`Net`] that re-records itself when the utterance changes length.
//!
//! Every Supertonic net is utterance-shaped: the duration predictor and the text encoder run at
//! the character count, the sampler at the latent length and the character count, the vocoder at
//! the latent length. A [`Plan`] is one command buffer recorded at fixed shapes, so a plan is only
//! ever right for one utterance, and [`Net::rebuild`] is how the next one gets its own.
//!
//! Doing that by hand needs two things kept in step per net — the table to rebuild from, and the
//! shape it is currently recorded at — and getting the second wrong is silent: a net left at the
//! previous sentence's length runs this sentence's data through it and returns the wrong number of
//! frames, or the right number computed over positions the arena happened to hold. So they live
//! together here, and a caller asks for the net *at a shape* rather than asking for the net.

use std::sync::Arc;

use crate::nets::Plan;
use crate::preprocess::RESCALE_ONLY;
use crate::weights::{Blob, Offsets, Weights};

type Planner<S> = fn(&Offsets, S) -> Result<Plan, String>;

use super::context::Context;
use super::run::Net;

/// A net, the table to rebuild its plan from, and the shape it is recorded at.
///
/// `S` is whatever identifies a shape for this net — `u32` for the three that depend only on a
/// length, `(u32, u32)` for the sampler, which depends on the frame count and the character count
/// independently. It is compared, not interpreted, so a net cannot be reshaped to a shape it is
/// already at and cannot be left at one it is not.
pub struct Reshaped<S> {
    net: Net,
    offsets: Offsets,
    at: S,
    plan: Planner<S>,
    /// The most recently built plans, newest last, so a shape seen again skips the build.
    ///
    /// Building a plan is pure CPU work over the graph and costs more than recording it: on a
    /// Pixel 8, 17-39 ms for NLLB's two passes against 5-8 ms to record. A translation switches
    /// between the same two shapes for every sentence of the same length, so this turns the
    /// re-record into a clone of an already-built plan. Keyed by `S` because the plan is a pure
    /// function of the shape and the table.
    built: Vec<(S, Plan)>,
}

/// Plans [`Reshaped`] keeps. Enough for an encoder and a decoder at a couple of lengths.
const BUILT_PLANS: usize = 4;

impl<S: Copy + PartialEq> Reshaped<S> {
    /// Upload `weights`, record at `shape`, and keep only the table.
    ///
    /// The `Weights` is borrowed rather than held: [`Net::new`] copies the blob to device memory
    /// and [`Weights::offsets`] keeps the few kilobytes a rebuild actually reads, so the caller can
    /// drop 127 MB of host allocation the moment this returns. That is the whole reason rebuilding
    /// beats constructing a second net per utterance.
    pub fn new(
        context: Arc<Context>,
        weights: &Weights,
        shape: S,
        plan: fn(&Offsets, S) -> Result<Plan, String>,
    ) -> Result<Reshaped<S>, String> {
        Self::streamed(context, weights.offsets(), weights, shape, plan)
    }

    /// [`Reshaped::new`] against a table and a data section that need not be the same object.
    ///
    /// The bundled path: a [`crate::weights::Streamed`] hands over its table and streams its data
    /// section out of the APK, so the 105 MB is never resident at all. The `Weights` path above is
    /// this with both halves coming from the same in-memory parse.
    ///
    /// [`RESCALE_ONLY`] because no bitmap ever reaches these nets — every input is a tensor a
    /// previous stage computed, handed over by [`Net::infer_raw_many`], so the preprocessing the
    /// vision nets need has nothing to do here.
    pub fn streamed(
        context: Arc<Context>,
        offsets: Offsets,
        data: &dyn Blob,
        shape: S,
        plan: fn(&Offsets, S) -> Result<Plan, String>,
    ) -> Result<Reshaped<S>, String> {
        let first = plan(&offsets, shape)?;
        let built = vec![(shape, first.clone())];
        let net = Net::whole(context, first, data, RESCALE_ONLY)?;
        Ok(Reshaped { net, offsets, at: shape, plan, built })
    }

    /// [`Reshaped::streamed`], uploading only the tensors the plans at `shapes` read.
    ///
    /// Recorded at `shapes[0]`. [`Reshaped::at`] any shape whose plan reads outside that window
    /// is an error the net survives. For a file much larger than what its plans touch, such as
    /// Gemma's 3.6 GB EMBED read by a 14 MB gather plan.
    pub fn windowed(
        context: Arc<Context>,
        offsets: Offsets,
        data: &dyn Blob,
        shapes: &[S],
        plan: fn(&Offsets, S) -> Result<Plan, String>,
    ) -> Result<Reshaped<S>, String> {
        let Some(&shape) = shapes.first() else {
            return Err("Reshaped::windowed needs at least one shape".into());
        };
        let mut built = Vec::with_capacity(shapes.len());
        for &each in shapes {
            built.push((each, plan(&offsets, each)?));
        }
        let first = built.first().map(|(_, plan)| plan.clone()).ok_or("no plan was built")?;
        let also: Vec<&Plan> = built.iter().skip(1).map(|(_, plan)| plan).collect();
        let net = Net::covering(context, first, &also, data, RESCALE_ONLY)?;
        let keep = built.len().saturating_sub(BUILT_PLANS);
        built.drain(..keep);
        Ok(Reshaped { net, offsets, at: shape, plan, built })
    }

    /// The net recorded at `shape`, re-recording it first if it is not already.
    ///
    /// Cheap when the shape is unchanged, which is the common case and the one that matters: the
    /// sampler is called `2 * STEPS` times at one shape for a single sentence, and the vocoder once
    /// per chunk at one shape, so re-recording per call would build thirty-odd command buffers
    /// where one is needed.
    pub fn at(&mut self, shape: S) -> Result<&mut Net, String> {
        if shape != self.at {
            let started = std::time::Instant::now();
            let plan = match self.built.iter().position(|(key, _)| *key == shape) {
                Some(index) => {
                    let entry = self.built.remove(index);
                    let plan = entry.1.clone();
                    self.built.push(entry);
                    plan
                }
                None => {
                    let plan = (self.plan)(&self.offsets, shape)?;
                    if self.built.len() == BUILT_PLANS {
                        self.built.remove(0);
                    }
                    self.built.push((shape, plan.clone()));
                    plan
                }
            };
            let built = started.elapsed();
            self.net.rebuild(plan)?;
            crate::timing!(
                "reshape {:.1} ms: plan {:.1} ms, rebuild {:.1} ms",
                started.elapsed().as_secs_f64() * 1e3,
                built.as_secs_f64() * 1e3,
                (started.elapsed() - built).as_secs_f64() * 1e3
            );
            self.at = shape;
        }
        Ok(&mut self.net)
    }

    /// What [`Reshaped::at`] would call to build a plan it does not hold, so a caller can build
    /// one off the thread that owns the net. See [`Reshaped::adopt`].
    pub fn planner(&self) -> (Planner<S>, Offsets) {
        (self.plan, self.offsets.clone())
    }

    /// Whether [`Reshaped::at`] would re-record nothing or reuse a plan already built for `shape`.
    pub fn holds(&self, shape: S) -> bool {
        shape == self.at || self.built.iter().any(|(key, _)| *key == shape)
    }

    /// Hand over a plan built elsewhere from [`Reshaped::planner`] at `shape`, so the next
    /// [`Reshaped::at`] there only re-records. The plan must be exactly what the planner returns.
    pub fn adopt(&mut self, shape: S, plan: Plan) {
        if self.holds(shape) {
            return;
        }
        if self.built.len() == BUILT_PLANS {
            self.built.remove(0);
        }
        self.built.push((shape, plan));
    }
}
