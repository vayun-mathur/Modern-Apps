#version 450
// Globe line: same screen-space extrusion as `line.vert`, then the globe bend.
//
// The offset math (casing bands, lateral shift, sub-pixel floor) is verbatim
// from `line.vert` — a width is a screen measurement either way. The difference
// is the final transform: instead of the flat tile matrix, the extruded
// tile-local point is bent onto the orthographic sphere by the same
// lon/lat -> unit-sphere -> clip derivation `fill_globe.vert` uses. Draped
// height (`inZ`) is ignored on the globe: relief at globe zoom is sub-pixel.
//
// Push layout: identical block to the flat path. `tileToClip` is the globe
// matrix (2x2 = globe scale, row 2 = tile ox/oy/span, translation column =
// centre lon/lat degrees), `misc.xyzw` keep their flat meaning (tile px,
// edge AA, lateral shift, clock). `morph` is (fade, halfW, radius, halfH) on
// the globe branch, as `record.rs` pushes it.
layout(location = 0) in vec2 inPosition;
layout(location = 1) in vec2 inNormal;
layout(location = 2) in vec2 inExtrude;
layout(location = 3) in float inDistance;
layout(location = 4) in float inZ;
layout(location = 0) out float outDistancePx;
layout(location = 1) out float outEdgePx;
layout(location = 2) out float outZ;
layout(push_constant) uniform Push {
    mat4 tileToClip;
    vec4 color;
    vec4 line;
    vec4 misc;
    vec4 morph;
} push;

const float MIN_HALF_WIDTH_PX = 0.5;
const float PI = 3.141592653589793;

void main() {
    float ox = push.tileToClip[0][2];
    float oy = push.tileToClip[1][2];
    float span = push.tileToClip[2][2];
    // `misc.x` is the flat tile's px span (span * world). On the ball one tile unit spans
    // 2 * pi * r * cos(lat) * span Dp in both directions (Mercator is conformal), so widths
    // and dash lengths convert through that span instead. `world` (Dp) rides in the globe
    // matrix's m[14]; cos(lat) = 1 / cosh(n) for the Mercator n of the unbent position.
    float n0 = PI - 2.0 * PI * (oy + inPosition.y * span);
    float worldDp = max(push.tileToClip[3][2], 1e-6);
    float tilePx = max(push.misc.x, 1.0)
        * (2.0 * PI * push.morph.z * max(1.0 / cosh(n0), 0.05) / worldDp);
    float wantHalfPx = push.line.x;
    float gapHalfPx = push.line.y;
    float lateralPx = push.misc.z;
    float halfWidthPx = max(wantHalfPx, MIN_HALF_WIDTH_PX);
    float offsetPx = inExtrude.x * gapHalfPx + inExtrude.y * halfWidthPx + lateralPx;
    float centrePx = inExtrude.x * gapHalfPx + sign(inExtrude.x) * halfWidthPx + lateralPx;
    outEdgePx = offsetPx - centrePx;
    vec2 offsetTile = inNormal * (offsetPx / tilePx);
    vec2 bent = inPosition + offsetTile;

    float wx = ox + bent.x * span;
    float wy = oy + bent.y * span;
    float lon = wx * 360.0 - 180.0;
    float n = PI - 2.0 * PI * wy;
    float lat = atan((exp(n) - exp(-n)) * 0.5) * 180.0 / PI;
    // Sphere basis from the matrix translation column (CPU-side centre).
    float cLon = push.tileToClip[3][0];
    float cLat = push.tileToClip[3][1];
    float latR = radians(lat);
    float dLon = radians(lon - cLon);
    float cLatR = radians(cLat);
    // Must match `camera::globe_point`.
    float sy = sin(cLatR);
    float cy = cos(cLatR);
    float x = cos(latR) * sin(dLon);
    float y = cy * sin(latR) - sy * cos(latR) * cos(dLon);
    float z = sy * sin(latR) + cy * cos(latR) * cos(dLon);
    float r = push.morph.z;
    float kx = push.tileToClip[0][0] / max(r, 1e-6);
    float ky = push.tileToClip[1][1] / max(r, 1e-6);
    float hw = push.morph.y;
    float hh = push.morph.w;
    // Screen point y-up like the CPU anchor math (`halfH - y`): north renders up.
    float cx = kx * (x * r) + (kx * hw - 1.0);
    float cyy = -(ky * (y * r)) + (ky * hh - 1.0);
    float depth = clamp(1.0 - z, 0.0, 1.0);
    outZ = z;
    outDistancePx = inDistance * tilePx;
    if (z < 0.0) {
        gl_Position = vec4(cx, cyy, 1.0, 1.0);
    } else {
        gl_Position = vec4(cx, cyy, depth, 1.0);
    }
}
