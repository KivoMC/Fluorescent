#version 330
#extension GL_ARB_separate_shader_objects : require
uniform sampler2D SceneSampler;
uniform sampler2D NearSampler;
uniform sampler2D WideSampler;
uniform sampler2D CoreSampler;
uniform sampler2D FlareSampler;
uniform sampler2D CoverageSampler;
layout(std140) uniform SamplerInfo { vec2 OutSize; vec2 SceneSize; vec2 NearSize; vec2 WideSize; vec2 CoreSize; vec2 FlareSize; vec2 CoverageSize; };
layout(std140) uniform BloomConfig {
    float PixelSize; float Intensity; float WideHalo; float CoreStrength;
    float Saturation; float Warmth; float AdditiveBlend; float PreserveDetail;
    float FlareStrength; float Pad0; float Pad1; float Pad2;
};
layout(location=0) in vec2 texCoord;
layout(location=0) out vec4 fragColor;
void main(){
    ivec2 pixel=clamp(ivec2(floor(texCoord*OutSize)),ivec2(0),ivec2(SceneSize)-1);
    // Only framebuffer coordinates select cells. No jitter, time or camera offset.
    ivec2 cell=clamp(pixel/int(PixelSize),ivec2(0),ivec2(NearSize)-1);
    vec4 scene=texelFetch(SceneSampler,pixel,0);
    vec3 nearGlow=texelFetch(NearSampler,cell,0).rgb;
    vec3 wideGlow=texelFetch(WideSampler,cell,0).rgb;
    vec3 coreGlow=texelFetch(CoreSampler,cell,0).rgb;
    vec3 glow=mix(nearGlow,wideGlow,WideHalo)+coreGlow*CoreStrength;
    if(FlareStrength>0.0)glow+=texelFetch(FlareSampler,cell,0).rgb*FlareStrength;
    glow*=Intensity;
    if(all(equal(glow,vec3(0)))){fragColor=scene;return;}
    float luminance=dot(glow,vec3(0.2126,0.7152,0.0722));
    glow=max(mix(vec3(luminance),glow,Saturation),vec3(0));
    glow*=vec3(1.0+0.3*Warmth,1.0+0.05*Warmth,1.0-0.3*Warmth);
    vec3 screenBlend=1.0-(1.0-scene.rgb)*exp(-glow*2.4);
    vec3 additive=min(scene.rgb+glow*2.4,vec3(1));
    vec3 color=mix(screenBlend,additive,AdditiveBlend);
    color=mix(color,scene.rgb,clamp(texelFetch(CoverageSampler,pixel,0).r,0.0,1.0));
    fragColor=vec4(color,scene.a);
}
