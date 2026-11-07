#version 330
#extension GL_ARB_separate_shader_objects : require
#include <stillbloom:rain_fog.glsl>
uniform sampler2D InSampler;
uniform sampler2D DepthSampler;
uniform sampler2D SourcesSampler;
uniform sampler2D CustomSampler;
uniform usampler2D GeometrySampler;
layout(std140) uniform SamplerInfo { vec2 OutSize; vec2 InSize; vec2 DepthSize; vec2 SourcesSize; vec2 CustomSize; vec2 GeometrySize; };
layout(std140) uniform BloomConfig { float Threshold; float Knee; float Range; float FadeStart; };
layout(std140) uniform SourceMask {
    mat4 InverseViewProjection;
    vec4 CameraFromOrigin;
};
layout(location=0) in vec2 texCoord;
layout(location=0) out vec4 fragColor;

uint wordAt(sampler2D source, int index) {
    uvec4 b=uvec4(round(texelFetch(source,ivec2(index&1023,index>>10),0)*255.0));
    return b.r|(b.g<<8u)|(b.b<<16u)|(b.a<<24u);
}
uvec4 geometry4(int i){int texel=i>>2;return texelFetch(GeometrySampler,ivec2(texel&511,texel>>9),0);}
uint geometryWord(int i){return geometry4(i)[i&3];}
vec4 geometryFloat4(int i){return uintBitsToFloat(geometry4(i));}
float paintedStrength(int index,vec3 local,vec3 viewRay) {
    uint pair=wordAt(CustomSampler,index>>1);
    int model=int((pair>>uint((index&1)*16))&65535u);
    if(model==0)return 0.0;
    uvec4 header=geometry4(model*4);
    int start=int(header.x),count=int(header.y);
    int listStart=0;
    if(header.z!=0u){
        ivec3 cell=clamp(ivec3(floor(local*8.0)),ivec3(0),ivec3(7));
        uvec4 candidates=geometry4(int(header.z)+(cell.x+8*(cell.y+8*cell.z))*4);
        listStart=int(candidates.x);count=int(candidates.y);
    }
    vec4 point=vec4(local,1.0);
    float nearest=0.006, result=0.0;
    bool matched=false, chosenFront=false;
    for(int t=0;t<128;t++){
        if(t>=count)break;
        int triangle=listStart==0?t:int(geometryWord(listStart+t));
        int base=start+triangle*24;
        vec4 plane=geometryFloat4(base);
        float distanceToPlane=abs(dot(plane,point));
        if(distanceToPlane>min(0.006,nearest+0.000001))continue;
        vec2 bc=vec2(dot(geometryFloat4(base+4),point),dot(geometryFloat4(base+8),point));
        if(bc.x< -0.001||bc.y< -0.001||bc.x+bc.y>1.001)continue;
        // Opposing faces may share a plane but map different texture pixels.
        // Prefer the visible face only for that tie; keep legacy nearest/order otherwise.
        bool front=dot(plane.xyz,viewRay)<0.0;
        bool opposedTie=matched&&front!=chosenFront&&abs(distanceToPlane-nearest)<=0.000001;
        if(opposedTie?!front:distanceToPlane>nearest)continue;
        vec4 uvRow=geometryFloat4(base+12),tail=geometryFloat4(base+16);
        vec2 uv=uvRow.xy+uvRow.zw*bc.x+tail.xy*bc.y;
        uvec4 metadata=geometry4(base+20);
        int mask=int(metadata.x);
        float strength=tail.z;
        if(mask>=0){
            ivec2 size=ivec2(metadata.yz);
            ivec2 texel=clamp(ivec2(floor(uv*vec2(size))),ivec2(0),size-1);
            int bit=texel.x+texel.y*size.x;
            if((geometryWord(mask+(bit>>5))&(1u<<uint(bit&31)))==0u)strength=0.0;
        }
        nearest=distanceToPlane;result=strength;chosenFront=front;matched=true;
    }
    return result;
}

vec3 bright(ivec2 p) {
    p=clamp(p,ivec2(0),ivec2(InSize)-1);
    vec3 c = texelFetch(InSampler,p,0).rgb;
    float peak = max(c.r, max(c.g, c.b));
    // Pixels below the soft knee cannot contribute, regardless of mask strength.
    if(RainFogBase.z<=0.5&&peak<=Threshold-Knee)return vec3(0.0);
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
    float sourceStrength=paintedStrength(index,relative+CameraFromOrigin.xyz-vec3(voxel),relative);
    if(sourceStrength<=0.0)return vec3(0.0);
    float rainAttenuation=1.0;
    if(RainFogBase.z>0.5){
        rainAttenuation=rainFogEmission(c,relative,distanceToSource);
        peak=max(c.r,max(c.g,c.b));
        if(peak<=Threshold-Knee)return vec3(0.0);
    }
    float soft = clamp(peak - Threshold + Knee, 0.0, 2.0 * Knee);
    soft = soft * soft / (4.0 * Knee + 0.00001);
    vec3 glow=c * (max(peak - Threshold, soft) / max(peak, 0.00001)) * distanceFade * sourceStrength;
    if(RainFogBase.z>0.5)glow*=rainAttenuation;
    return glow;
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

