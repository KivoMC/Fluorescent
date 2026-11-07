#version 330
#extension GL_ARB_separate_shader_objects : require
uniform sampler2D InSampler;
uniform sampler2D DepthSampler;
uniform sampler2D SourcesSampler;
layout(std140) uniform SamplerInfo { vec2 OutSize; vec2 InSize; vec2 DepthSize; vec2 SourcesSize; };
layout(std140) uniform BloomConfig { float Threshold; float Knee; float Range; float FadeStart; };
layout(std140) uniform SourceMask {
    mat4 InverseViewProjection;
    vec4 CameraFromOrigin;
};
layout(location=0) in vec2 texCoord;
layout(location=0) out vec4 fragColor;

vec3 bright(ivec2 p) {
    p=clamp(p,ivec2(0),ivec2(InSize)-1);
    vec3 c = texelFetch(InSampler,p,0).rgb;
    float peak = max(c.r, max(c.g, c.b));
    // Below the soft knee, extraction is exactly zero. Avoid depth/world lookups.
    if(peak<=Threshold-Knee)return vec3(0.0);
    float depth=texelFetch(DepthSampler,p,0).r;
    if(depth<=0.0)return vec3(0.0);
    vec2 uv=(vec2(p)+0.5)/InSize;
    float z=CameraFromOrigin.w>0.5?depth:depth*2.0-1.0;
    vec4 position=InverseViewProjection*vec4(uv*2.0-1.0,z,1.0);
    vec3 relative=position.xyz/position.w;
    float distanceToSource=length(relative);
    if(distanceToSource>=Range)return vec3(0.0);
    float distanceFade=1.0-smoothstep(FadeStart,Range,distanceToSource);
    // Move just inside the visible surface, avoiding ambiguous voxel boundaries.
    ivec3 voxel=ivec3(floor(relative+CameraFromOrigin.xyz+normalize(relative)*0.003));
    if(any(lessThan(voxel,ivec3(0)))||any(greaterThanEqual(voxel,ivec3(128))))return vec3(0.0);
    int index=voxel.x+128*(voxel.y+128*voxel.z);
    int word=index>>5;
    uvec4 bytes=uvec4(round(texelFetch(SourcesSampler,ivec2(word&255,word>>8),0)*255.0));
    uint bits=bytes.r|(bytes.g<<8u)|(bytes.b<<16u)|(bytes.a<<24u);
    if((bits&(1u<<uint(index&31)))==0u)return vec3(0.0);
    float soft = clamp(peak - Threshold + Knee, 0.0, 2.0 * Knee);
    soft = soft * soft / (4.0 * Knee + 0.00001);
    return c * (max(peak - Threshold, soft) / max(peak, 0.00001)) * distanceFade;
}
void main() {
#ifdef BLOOM_GRID_THREE
    // Exact screen-anchored 3x3 area: retain every painted source sample.
    ivec2 p=ivec2(floor(texCoord*OutSize))*3;
    vec3 value=vec3(0);
    for(int y=0;y<3;y++)for(int x=0;x<3;x++)value+=bright(p+ivec2(x,y));
    fragColor=vec4(value/9.0,1.0);
#else
    // Area-average every source texel. A single moving sample causes crawling.
    ivec2 p = ivec2(floor(texCoord * OutSize)) * 2;
    fragColor = vec4((bright(p) + bright(p + ivec2(1,0)) + bright(p + ivec2(0,1)) + bright(p + ivec2(1,1))) * 0.25, 1.0);
#endif
}
