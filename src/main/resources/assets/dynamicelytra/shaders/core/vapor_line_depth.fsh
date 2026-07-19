#version 150

uniform sampler2D Sampler0;

uniform vec4 ColorModulator;

in vec2 texCoord0;
in vec4 vertexColor;

out vec4 fragColor;

// Depth-only pass for the tip line. Its RenderType masks colour off, so this exists purely to stamp depth —
// and it stamps it ONLY where the line is genuinely solid.
//
// Why: volumetric clouds (Simple Clouds) are depth-tested against the main framebuffer, so writing depth is the
// only way to stop them painting over the trail. But depth is binary while the line is translucent: if every
// fragment down to 0.01 alpha wrote depth, the cloud would be culled across the line's soft edges and faded
// tail too, punching a hole far wider than the visible line and showing sky where cloud should be.
//
// Cutting at 0.4 means only the line's opaque core displaces cloud — so the hole is exactly the bright line
// that fills it — while the soft edges and old tail let the cloud through and blend over them, as they should.
void main() {
    vec4 color = texture(Sampler0, texCoord0) * vertexColor;
    if (color.a < 0.4) {
        discard;
    }
    fragColor = color * ColorModulator;
}
