#version 450

// Globe symbol vertex: label quads bent onto the sphere at their anchor.
//
// Same inputs as `symbol_billboard.vert` (tile-local position + uv + ground
// anchor). The globe record path pushes the globe matrix (translation column =
// centre lon/lat, third row = tile ox/oy/span) with `misc`/`morph` keeping
// their flat meaning: `misc.x` is the tile's world-px span (Dp), `misc.yzw`
// the halo rgb exactly as on the flat path, `line` the text size/halo/sdf/
// billboard flag, and `morph` the globe half-viewport + radius (see above).
// This shader bends the anchor through the CPU-matching `globe_point` basis
// and hangs the glyph quad off it at its screen offset — upright labels on a
// curved planet, with halo intact. Curved (line) labels carry each vertex as
// sphere exactly as they lie flat on the plane. Screen y follows the CPU
// anchor convention (`halfH - y`, north up).
layout(location = 0) in vec2 inPosition;
layout(location = 1) in vec2 inUv;
layout(location = 2) in vec2 inAnchor;
layout(location = 3) in float inAnchorH;
layout(location = 0) out vec2 outUv;
layout(location = 1) out float outZ;
layout(push_constant) uniform Push {
    mat4 tileToClip;
    vec4 color;
    vec4 line;
    vec4 misc;
    vec4 morph;
} push;

const float PI = 3.141592653589793;

// Must match `camera::globe_point`.
vec3 globeOf(float lon, float lat, float cLon, float cLat) {
    float latR = radians(lat);
    float dLon = radians(lon - cLon);
    float cLatR = radians(cLat);
    float sy = sin(cLatR);
    float cy = cos(cLatR);
    return vec3(
        cos(latR) * sin(dLon),
        cy * sin(latR) - sy * cos(latR) * cos(dLon),
        sy * sin(latR) + cy * cos(latR) * cos(dLon));
}

void main() {
    outUv = inUv;
    float ox = push.tileToClip[0][2];
    float oy = push.tileToClip[1][2];
    float span = push.tileToClip[2][2];
    // Anchor tile-local -> lon/lat.
    float awx = ox + inAnchor.x * span;
    float awy = oy + inAnchor.y * span;
    float alon = awx * 360.0 - 180.0;
    float an = PI - 2.0 * PI * awy;
    float alat = atan((exp(an) - exp(-an)) * 0.5) * 180.0 / PI;
    // Sphere basis from the matrix translation column (CPU-side centre).
    float cLon = push.tileToClip[3][0];
    float cLat = push.tileToClip[3][1];
    vec3 a = globeOf(alon, alat, cLon, cLat);
    float r = push.morph.z;
    float kx = push.tileToClip[0][0] / max(r, 1e-6);
    float ky = push.tileToClip[1][1] / max(r, 1e-6);
    float hw = push.morph.x;
    float hh = push.morph.y;
    // Screen offset of this corner from its anchor. Labels are shaped in flat tile-local
    // units, where one unit is `span * world` Dp (`world` rides in m[14]); the globe 2x2 is
    // `k * r`, so the delta is scaled by `span * world / r` to keep the flat screen size.
    float m00 = push.tileToClip[0][0];
    float m01 = push.tileToClip[0][1];
    float m10 = push.tileToClip[1][0];
    float m11 = push.tileToClip[1][1];
    vec2 dTile = (inPosition - inAnchor) * (span * push.tileToClip[3][2] / max(r, 1e-6));
    vec2 off = vec2(m00 * dTile.x + m10 * dTile.y, m01 * dTile.x + m11 * dTile.y);
    float ax = kx * (a.x * r) + (kx * hw - 1.0);
    float ay = -(ky * (a.y * r)) + (ky * hh - 1.0);
    float depth = clamp(1.0 - a.z, 0.0, 1.0);
    outZ = a.z;
    if (a.z < 0.0) {
        gl_Position = vec4(ax, ay, 1.0, 1.0);
    } else {
        // Tile +y and clip +y both point down the screen, so the offset adds unflipped.
        gl_Position = vec4(ax + off.x, ay + off.y, depth, 1.0);
    }
}
