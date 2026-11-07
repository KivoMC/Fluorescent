#version 330
#extension GL_ARB_separate_shader_objects : require
uniform sampler2D SceneSampler;
uniform sampler2D NearSampler;
uniform sampler2D WideSampler;
uniform sampler2D CoreSampler;
uniform sampler2D FlareSampler;
uniform sampler2D DepthSampler;
uniform sampler2D SourcesSampler;
uniform sampler2D DetailsSampler;
// Detail storage has fixed dimensions; its trailing sampler metadata is unused.
layout(std140) uniform SamplerInfo { vec2 OutSize; vec2 SceneSize; vec2 NearSize; vec2 WideSize; vec2 CoreSize; vec2 FlareSize; vec2 DepthSize; vec2 SourcesSize; };
layout(std140) uniform SourceMask { mat4 InverseViewProjection; vec4 CameraFromOrigin; };
float sourceProtection(ivec2 pixel,float inherited){
    float depth=texelFetch(DepthSampler,pixel,0).r;
    if(depth<=0.0)return 0.0;
    vec2 uv=(vec2(pixel)+0.5)/SceneSize;
    float z=CameraFromOrigin.w>0.5?depth:depth*2.0-1.0;
    vec4 position=InverseViewProjection*vec4(uv*2.0-1.0,z,1);
    vec3 relative=position.xyz/position.w;
    ivec3 voxel=ivec3(floor(relative+CameraFromOrigin.xyz+normalize(relative)*0.003));
    if(any(lessThan(voxel,ivec3(0)))||any(greaterThanEqual(voxel,ivec3(128))))return 0.0;
    int index=voxel.x+128*(voxel.y+128*voxel.z),word=index>>5;
    uvec4 bytes=uvec4(round(texelFetch(SourcesSampler,ivec2(word&255,word>>8),0)*255.0));
    uint bits=bytes.r|(bytes.g<<8u)|(bytes.b<<16u)|(bytes.a<<24u);
    if((bits&(1u<<uint(index&31)))==0u)return 0.0;
    // Protection belongs to the block under this pixel, regardless of which
    // style group emitted the glow. The sign bit distinguishes explicit zero.
    uvec4 detailBytes=uvec4(round(texelFetch(DetailsSampler,ivec2(index&1023,index>>10),0)*255.0));
    uint detail=detailBytes.r|(detailBytes.g<<8u)|(detailBytes.b<<16u)|(detailBytes.a<<24u);
    return detail==0u?inherited:uintBitsToFloat(detail&0x7FFFFFFFu);
}
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
    // Empty cells leave the scene untouched; no coverage or color work is needed.
    if(all(equal(glow,vec3(0)))){fragColor=scene;return;}
    float luminance=dot(glow,vec3(0.2126,0.7152,0.0722));
    glow=max(mix(vec3(luminance),glow,Saturation),vec3(0));
    glow*=vec3(1.0+0.3*Warmth,1.0+0.05*Warmth,1.0-0.3*Warmth);
    vec3 screenBlend=1.0-(1.0-scene.rgb)*exp(-glow*2.4);
    vec3 additive=min(scene.rgb+glow*2.4,vec3(1));
    vec3 color=mix(screenBlend,additive,AdditiveBlend);
    color=mix(color,scene.rgb,sourceProtection(pixel,PreserveDetail));
    fragColor=vec4(color,scene.a);
}
