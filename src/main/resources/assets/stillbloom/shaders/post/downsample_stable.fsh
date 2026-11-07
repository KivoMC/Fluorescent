#version 330
#extension GL_ARB_separate_shader_objects : require
uniform sampler2D InSampler;
uniform sampler2D HistorySampler;
layout(std140) uniform SamplerInfo { vec2 OutSize; vec2 InSize; vec2 HistorySize; };
layout(std140) uniform Stability { float HistoryWeight; float Pad0; float Pad1; float Pad2; };
layout(location=0) in vec2 texCoord;
layout(location=0) out vec4 fragColor;
vec3 sampleAt(ivec2 p){return texelFetch(InSampler,clamp(p,ivec2(0),ivec2(InSize)-1),0).rgb;}
void main(){
    ivec2 cell=ivec2(floor(texCoord*OutSize)),p=cell*2;
    vec3 c=(sampleAt(p)+sampleAt(p+ivec2(1,0))+sampleAt(p+ivec2(0,1))+sampleAt(p+ivec2(1,1)))*0.25;
    // Match the original RGBA8 reduction before filtering, but keep history at float precision.
    c=roundEven(clamp(c,0.0,1.0)*255.0)/255.0;
    float peak=max(c.r,max(c.g,c.b));
    if(peak>0.0&&HistoryWeight>0.0){
        vec3 h=texelFetch(HistorySampler,cell,0).rgb;
        float previous=max(h.r,max(h.g,h.b));
        if(previous>0.0)c*=mix(peak,clamp(previous,peak*0.75,peak*1.25),HistoryWeight)/peak;
    }
    // Newly lit and empty cells respond immediately; hue always comes from this frame.
    fragColor=vec4(c,1.0);
}
