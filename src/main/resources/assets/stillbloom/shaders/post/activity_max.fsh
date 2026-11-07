#version 330
#extension GL_ARB_separate_shader_objects : require
uniform sampler2D InSampler;
layout(std140) uniform SamplerInfo { vec2 OutSize; vec2 InSize; };
layout(location=0) in vec2 texCoord;
layout(location=0) out vec4 fragColor;
void main(){
    ivec2 origin=ivec2(floor(texCoord*OutSize))*4;
    vec3 peak=vec3(0);
    // Max, not average: a small/distant light must never disappear in the reduction.
    for(int y=0;y<4;y++)for(int x=0;x<4;x++)
        peak=max(peak,texelFetch(InSampler,min(origin+ivec2(x,y),ivec2(InSize)-1),0).rgb);
    fragColor=vec4(peak,1);
}
