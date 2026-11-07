#version 330
#extension GL_ARB_separate_shader_objects : require
uniform sampler2D InSampler;
uniform sampler2D HistorySampler;
layout(std140) uniform SamplerInfo { vec2 OutSize; vec2 InSize; vec2 HistorySize; };
layout(std140) uniform Stability { float HistoryWeight; float Pad0; float Pad1; float Pad2; };
layout(location=0) in vec2 texCoord;
layout(location=0) out vec4 fragColor;
void main(){
    ivec2 cell=ivec2(floor(texCoord*OutSize));
    // Extraction has already averaged each 2x2 source cell and quantized RGB.
    // Do not downsample it again: history and current emission share this grid.
    vec4 c=texelFetch(InSampler,cell,0);
    float peak=max(c.r,max(c.g,c.b));
    if(peak>0.0&&HistoryWeight>0.0){
        vec3 h=texelFetch(HistorySampler,cell,0).rgb;
        float previous=max(h.r,max(h.g,h.b));
        // Keep this frame's distance ratio and hue as brightness is stabilized.
        if(previous>0.0)c*=mix(peak,clamp(previous,peak*0.75,peak*1.25),HistoryWeight)/peak;
    }
    fragColor=c;
}
