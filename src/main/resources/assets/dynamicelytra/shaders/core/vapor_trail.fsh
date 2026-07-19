#version 150

uniform sampler2D Sampler0;

uniform vec4 ColorModulator;

in vec2 texCoord0;
in vec4 vertexColor;

out vec4 fragColor;

// A copy of vanilla's position_tex_color with its `if (color.a < 0.1) discard;` cutoff lowered to near zero.
//
// Vanilla's 0.1 cutoff exists for cutout/particle sprites, but it destroys soft translucent geometry: the vapor
// wash peaks around 0.27 alpha and most of it sits below 0.1, so vanilla's shader chopped it into a hard-edged
// mask that popped in and out as values crossed the threshold (the trail's "glitching") and erased faint trails
// entirely. At 0.01 the wash keeps its full soft gradient and the tip line gets soft edges.
//
// The tiny cutoff is still needed: the tip line writes depth (so volumetric clouds are occluded by it rather
// than painted over it), and without any cutoff its fully transparent fragments would write depth too, punching
// invisible holes into whatever is drawn behind them.
void main() {
    vec4 color = texture(Sampler0, texCoord0) * vertexColor;
    if (color.a < 0.01) {
        discard;
    }
    fragColor = color * ColorModulator;
}
