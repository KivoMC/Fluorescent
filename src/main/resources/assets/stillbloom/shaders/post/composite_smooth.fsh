#version 330
#extension GL_ARB_separate_shader_objects : require
uniform sampler2D SceneSampler;
uniform sampler2D NearSampler;
uniform sampler2D WideSampler;
uniform sampler2D CoreSampler;
uniform sampler2D FlareSampler;
layout(std140) uniform SamplerInfo { vec2 OutSize; vec2 SceneSize; vec2 NearSize; vec2 WideSize; vec2 CoreSize; vec2 FlareSize; };
layout(std140) uniform BloomConfig {
    float PixelSize; float Intensity; float WideHalo; float CoreStrength;
    float Saturation; float Warmth; float AdditiveBlend; float PreserveDetail;
    float FlareStrength; float Pad0; float SmoothBlend; float Pad2;
};
layout(location=0) in vec2 texCoord;
layout(location=0) out vec4 fragColor;
// Interpolate glow only. Source textures, depth and surface protection stay exact.
vec3 glowAt(vec2 uv){
    vec3 value=mix(texture(NearSampler,uv).rgb,texture(WideSampler,uv).rgb,WideHalo)+texture(CoreSampler,uv).rgb*CoreStrength;
    if(FlareStrength>0.0)value+=texture(FlareSampler,uv).rgb*FlareStrength;
    return value;
}
void main(){
    ivec2 pixel=clamp(ivec2(floor(texCoord*OutSize)),ivec2(0),ivec2(SceneSize)-1);
    vec4 scene=texelFetch(SceneSampler,pixel,0);
    // PixelSize*NearSize retains the same grid phase at odd framebuffer sizes
    // and inside globally aligned held-item crops; SceneSize would stretch it.
    vec2 smoothUv=(vec2(pixel)+0.5)/(PixelSize*NearSize);
    vec3 glow;
    if(SmoothBlend>=1.0)glow=glowAt(smoothUv);
    else{
        ivec2 cell=clamp(pixel/int(PixelSize),ivec2(0),ivec2(NearSize)-1);
        vec3 nearGlow=texelFetch(NearSampler,cell,0).rgb;
        vec3 wideGlow=texelFetch(WideSampler,cell,0).rgb;
        vec3 coreGlow=texelFetch(CoreSampler,cell,0).rgb;
        vec3 pixelated=mix(nearGlow,wideGlow,WideHalo)+coreGlow*CoreStrength;
        if(FlareStrength>0.0)pixelated+=texelFetch(FlareSampler,cell,0).rgb*FlareStrength;
        glow=mix(pixelated,glowAt(smoothUv),SmoothBlend);
    }
    glow*=Intensity;
    if(all(equal(glow,vec3(0)))){fragColor=scene;return;}
    float luminance=dot(glow,vec3(0.2126,0.7152,0.0722));
    glow=max(mix(vec3(luminance),glow,Saturation),vec3(0));
    glow*=vec3(1.0+0.3*Warmth,1.0+0.05*Warmth,1.0-0.3*Warmth);
    vec3 screenBlend=1.0-(1.0-scene.rgb)*exp(-glow*2.4);
    vec3 additive=min(scene.rgb+glow*2.4,vec3(1));
    vec3 color=mix(screenBlend,additive,AdditiveBlend);
    // PROTECTION
    fragColor=vec4(color,scene.a);
}
