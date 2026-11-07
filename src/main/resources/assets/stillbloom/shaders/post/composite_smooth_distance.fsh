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
    float FlareStrength; float FinePixelSize; float SmoothBlend; float Pad2;
};
layout(location=0) in vec2 texCoord;
layout(location=0) out vec4 fragColor;
vec4 glowAt(vec2 uv){
    vec4 glow=mix(texture(NearSampler,uv),texture(WideSampler,uv),WideHalo)+texture(CoreSampler,uv)*CoreStrength;
    if(FlareStrength>0.0)glow+=texture(FlareSampler,uv)*FlareStrength;
    return glow;
}
vec4 glowAtCell(ivec2 cell){
    vec4 glow=mix(texelFetch(NearSampler,cell,0),texelFetch(WideSampler,cell,0),WideHalo)+texelFetch(CoreSampler,cell,0)*CoreStrength;
    if(FlareStrength>0.0)glow+=texelFetch(FlareSampler,cell,0)*FlareStrength;
    return glow;
}
void main(){
    ivec2 pixel=clamp(ivec2(floor(texCoord*OutSize)),ivec2(0),ivec2(SceneSize)-1);
    vec4 scene=texelFetch(SceneSampler,pixel,0);
    // Continuous glow uses the existing fine-grid emission. Distance alpha
    // controls pixel representation only and must not dim the smooth layer.
    vec2 smoothUv=(vec2(pixel)+0.5)/(FinePixelSize*NearSize);
    vec3 glow;
    if(SmoothBlend>=1.0)glow=glowAt(smoothUv).rgb;
    else{
        vec2 coarseCenter=(floor(vec2(pixel)/PixelSize)+0.5)*PixelSize;
        vec4 coarse=glowAt(coarseCenter/(FinePixelSize*NearSize));
        float mass=dot(coarse.rgb,vec3(0.2126,0.7152,0.0722));
        float nearWeight=mass>0.0?clamp(coarse.a/mass,0.0,1.0):0.0;
        vec3 pixelated=coarse.rgb;
        if(nearWeight<0.9999){
            ivec2 fineCell=clamp(pixel/int(FinePixelSize),ivec2(0),ivec2(NearSize)-1);
            vec4 fine=glowAtCell(fineCell);
            pixelated=mix(fine.rgb,coarse.rgb,nearWeight);
        }
        glow=mix(pixelated,glowAt(smoothUv).rgb,SmoothBlend);
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
