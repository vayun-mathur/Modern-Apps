// Generated constant tables: the scale literals below are read off the
// source bundle with more digits than `f32` can represent. Rounding them
// would change ML numerics, so each generated table carries a scoped
// `excessive_precision` allow instead of rounded literals.
// Live int8 round-trip scales for Gemma 4's attention path (part of `nets::gemma4`).
//
// S10's per-tensor quantization scales, one per layer, for the attention
// activations live round-trips through int8 (the Track-16..20 qint set).
// Without these the device runs the attention at full precision while live
// quantizes, and small input perturbations survive to the softmax instead of
// being rounded away.
//
// A push constant like [`LOGIT_CAP`](super::LOGIT_CAP), not weight tensors:
// fixed by the source bundle, and the parity harness fails loudly if the
// bundle ever changes them. Re-derive with the tag scan in
// `analysis/bisect_layers.py`: `layer_{i}/attn_vec_einsum/composite` (mm),
// `q_einsum/composite` (qi), `q_einsum/dot_general1` (qo),
// `k_einsum/dot_general1` (ko, owning layers), `v_einsum/dot_general1`
// (vo, owning layers), `attn_vec_einsum/dot_general1` (o).
//
// The `o` grid matches live exactly only at full-scale O tables: the old
// /4-rescaled build flipped codes at the o-tail (see the `MM_SCALE` docs in
// `nets::gemma4`). The scales below were read off the S10 bundle on
// 2026-09-25 and verified bit-exact against it.

/// The per-layer int8 scale live applies to the attention mixture (`Mm`)
/// before the O projection consumes it.
///
/// S10's `attn_vec_einsum/composite` tensor, one scale per layer (layer 0:
/// 0.03026575781404972). The live graph round-trips `Mm` through int8 at this
/// scale (the t420 op); without it the device runs the O-tail at full precision
/// and diverges past any cosine gate (fp `Mm` vs live: 0.67; round-tripped:
/// 0.97865 at L0 - see `analysis/ablate_o_tail.py`).
#[rustfmt::skip]
#[allow(clippy::excessive_precision)]
pub const MM_SCALE: [f32; LAYERS] = [
    0.03026575781404972,
    0.023129930719733238,
    0.0216535534709692,
    0.02706693857908249,
    0.017962608486413956,
    0.026451781392097473,
    0.0354330837726593,
    0.028912410140037537,
    0.025713592767715454,
    0.017962608486413956,
    0.028051190078258514,
    0.026082687079906464,
    0.028543315827846527,
    0.026205718517303467,
    0.017962608486413956,
    0.02436024509370327,
    0.023745087906718254,
    0.023375993594527245,
    0.023006899282336235,
    0.014886821620166302,
    0.02436024509370327,
    0.025221465155482292,
    0.023868119344115257,
    0.02436024509370327,
    0.01457924209535122,
    0.02325296215713024,
    0.023129930719733238,
    0.024237213656306267,
    0.024237213656306267,
    0.017962608486413956,
    0.02399115078151226,
    0.02399115078151226,
    0.023006899282336235,
    0.02276083640754223,
    0.012118612416088581,
];

/// Live int8 scale for q_in (`q_einsum/composite`). See [`MM_SCALE`].
#[rustfmt::skip]
#[allow(clippy::excessive_precision)]
pub const QI_SCALE: [f32; 35] = [
    0.6088034510612488,
    0.4842597544193268,
    0.15746013820171356,
    0.12591414153575897,
    0.023374712094664574,
    0.07399173825979233,
    0.341854453086853,
    0.29519250988960266,
    0.0820433720946312,
    0.016753647476434708,
    0.15428009629249573,
    0.11506716161966324,
    0.08767248690128326,
    0.02862965501844883,
    0.20956376194953918,
    0.18704485893249512,
    0.44548678398132324,
    0.25072598457336426,
    0.3068588376045227,
    0.3205932080745697,
    0.3106735646724701,
    0.20073647797107697,
    0.21987482905387878,
    0.17847737669944763,
    0.10295870155096054,
    0.1674470156431198,
    0.14451591670513153,
    0.3104223608970642,
    0.4655068814754486,
    0.41653531789779663,
    0.621565580368042,
    0.6227923035621643,
    0.6824333071708679,
    0.9355496764183044,
    1.450958251953125,
];

/// Live int8 scale for q_out (`q_einsum/dot_general1`). See [`MM_SCALE`].
#[rustfmt::skip]
#[allow(clippy::excessive_precision)]
pub const QO_SCALE: [f32; 35] = [
    3.118110179901123,
    0.35039371252059937,
    0.1643700897693634,
    0.15354332327842712,
    0.03248032554984093,
    0.13385827839374542,
    0.4566929042339325,
    0.31496062874794006,
    0.1250000149011612,
    0.029650600627064705,
    0.3484252095222473,
    0.2736220359802246,
    0.12795276939868927,
    0.03764764964580536,
    0.3385826647281647,
    0.2814960777759552,
    0.3641732335090637,
    0.374015748500824,
    0.36220473051071167,
    0.4685039222240448,
    0.36614173650741577,
    0.23622049391269684,
    0.19685040414333344,
    0.16338583827018738,
    0.1919291466474533,
    0.15452757477760315,
    0.25196850299835205,
    0.437007874250412,
    0.34645670652389526,
    0.312992125749588,
    0.45866140723228455,
    0.6574802994728088,
    0.4645669162273407,
    0.33464565873146057,
    0.6535432934761047,
];

/// Live int8 scale for k_out (`k_einsum/dot_general1`, owning layers only).
/// See [`MM_SCALE`].
#[rustfmt::skip]
#[allow(clippy::excessive_precision)]
pub const KO_SCALE: [f32; 15] = [
    1.535433053970337,
    0.41535434126853943,
    0.180118128657341,
    0.08710630983114243,
    0.024852370843291283,
    0.09842520207166672,
    0.28740158677101135,
    0.3681102395057678,
    0.09251969307661057,
    0.020177174359560013,
    0.19881890714168549,
    0.12696851789951324,
    0.08070866763591766,
    0.035925209522247314,
    0.21751970052719116,
];

/// Live int8 scale for v_out (`v_einsum/dot_general1`, owning layers only).
/// See [`MM_SCALE`].
#[rustfmt::skip]
#[allow(clippy::excessive_precision)]
pub const VO_SCALE: [f32; 15] = [
    1.535433053970337,
    0.41535434126853943,
    0.180118128657341,
    0.08710630983114243,
    0.024852370843291283,
    0.09842520207166672,
    0.28740158677101135,
    0.3681102395057678,
    0.09251969307661057,
    0.020177174359560013,
    0.19881890714168549,
    0.12696851789951324,
    0.08070866763591766,
    0.035925209522247314,
    0.21751970052719116,
];

/// Live int8 scale for o_in (`attn_vec_einsum/dot_general1`). See [`MM_SCALE`].
#[rustfmt::skip]
#[allow(clippy::excessive_precision)]
pub const O_SCALE: [f32; 35] = [
    0.21056734025478363,
    0.03322756290435791,
    0.03426840156316757,
    0.07367152720689774,
    0.17608338594436646,
    0.043322544544935226,
    0.05930274724960327,
    0.025238478556275368,
    0.022537967190146446,
    0.02776472456753254,
    0.029417896643280983,
    0.028822369873523712,
    0.021804405376315117,
    0.03592992201447487,
    0.019122116267681122,
    0.060289591550827026,
    0.02639034017920494,
    0.020179564133286476,
    0.023901576176285744,
    0.01861305721104145,
    0.035965267568826675,
    0.03553423285484314,
    0.047558318823575974,
    0.027484547346830368,
    0.02337142638862133,
    0.040348730981349945,
    0.04343831539154053,
    0.05315101891756058,
    0.03035588562488556,
    0.03030368685722351,
    0.05247194319963455,
    0.06142711639404297,
    0.056797921657562256,
    0.02861599810421467,
    0.017325349152088165,
];
