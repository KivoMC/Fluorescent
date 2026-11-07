#version 330
#extension GL_ARB_separate_shader_objects : require
uniform sampler2D InSampler;
layout(std140) uniform SamplerInfo { vec2 OutSize; vec2 InSize; };
layout(location=0) in vec2 texCoord;
layout(location=0) out vec4 fragColor;
void main() {
    fragColor = texelFetch(InSampler, clamp(ivec2(texCoord * OutSize), ivec2(0), ivec2(InSize)-1), 0);
}
