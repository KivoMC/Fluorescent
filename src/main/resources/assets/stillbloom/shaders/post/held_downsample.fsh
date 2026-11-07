#version 330
#extension GL_ARB_separate_shader_objects : require
uniform sampler2D InSampler;
layout(std140) uniform SamplerInfo { vec2 OutSize; vec2 InSize; };
layout(location=0) in vec2 texCoord;
layout(location=0) out vec4 fragColor;
vec3 sampleAt(ivec2 p) { return texelFetch(InSampler, clamp(p, ivec2(0), ivec2(InSize)-1), 0).rgb; }
void main() {
#ifdef BLOOM_GRID_THREE
    ivec2 p=ivec2(floor(texCoord*OutSize))*3;
    vec3 value=vec3(0);
    for(int y=0;y<3;y++)for(int x=0;x<3;x++)value+=sampleAt(p+ivec2(x,y));
    fragColor=vec4(value/9.0,1.0);
#else
    ivec2 p = ivec2(floor(texCoord * OutSize)) * 2;
    fragColor = vec4((sampleAt(p) + sampleAt(p+ivec2(1,0)) + sampleAt(p+ivec2(0,1)) + sampleAt(p+ivec2(1,1))) * 0.25, 1.0);
#endif
}
