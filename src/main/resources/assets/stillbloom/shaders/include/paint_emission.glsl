// Self-lit placed paint. Match the nearest visible triangle first; only its UV,
// saved paint bit and animated atlas texel can contribute to the glow.
uvec4 geometry4(int i){int t=i>>2;return texelFetch(GeometrySampler,ivec2(t&511,t>>9),0);}
uint geometryWord(int i){return geometry4(i)[i&3];}
vec4 geometryFloat4(int i){return uintBitsToFloat(geometry4(i));}
uint packedWord(vec4 sampleValue){uvec4 b=uvec4(round(sampleValue*255.0));return b.r|(b.g<<8u)|(b.b<<16u)|(b.a<<24u);}

vec3 paintedLight(int index,vec3 local,vec3 ray){
    int pairIndex=index>>1;
    uint pair=packedWord(texelFetch(CustomSampler,ivec2(pairIndex&1023,pairIndex>>10),0));
    int model=int((pair>>uint((index&1)*16))&65535u);
    if(model==0||model==65535)return vec3(0);
    uvec4 header=geometry4(model*4);
    int start=int(header.x),count=int(header.y),list=0;
    if(header.z!=0u){
        ivec3 cell=clamp(ivec3(floor(local*8.0)),ivec3(0),ivec3(7));
        uvec4 candidates=geometry4(int(header.z)+(cell.x+8*(cell.y+8*cell.z))*4);
        list=int(candidates.x);count=int(candidates.y);
    }
    vec4 point=vec4(local,1);float nearest=0.006;
    int chosen=-1;bool chosenFront=false;vec2 chosenBC=vec2(0);
    for(int t=0;t<128;t++){
        if(t>=count)break;
        int base=start+(list==0?t:int(geometryWord(list+t)))*24;
        vec4 plane=geometryFloat4(base);float dist=abs(dot(plane,point));
        if(dist>min(0.006,nearest+0.000001))continue;
        vec2 bc=vec2(dot(geometryFloat4(base+4),point),dot(geometryFloat4(base+8),point));
        if(bc.x< -0.001||bc.y< -0.001||bc.x+bc.y>1.001)continue;
        bool front=dot(plane.xyz,ray)<0.0;
        bool opposed=chosen>=0&&front!=chosenFront&&abs(dist-nearest)<=0.000001;
        if(opposed?!front:dist>nearest)continue;
        chosen=base;nearest=dist;chosenBC=bc;chosenFront=front;
    }
    if(chosen<0)return vec3(0);
    uvec4 meta=geometry4(chosen+20);int mask=int(meta.x),rect=int(meta.w>>8u);
    if(mask<0||rect==0)return vec3(0);
    vec4 tail=geometryFloat4(chosen+16);
    if(tail.z<=0.0)return vec3(0);
    vec4 row=geometryFloat4(chosen+12);
    vec2 uv=clamp(row.xy+row.zw*chosenBC.x+tail.xy*chosenBC.y,0.0,1.0);
    ivec2 size=ivec2(meta.yz),xy=min(ivec2(uv*vec2(size)),size-1);
    int bit=xy.x+xy.y*size.x;
    if((geometryWord(mask+(bit>>5))&(1u<<uint(bit&31)))==0u)return vec3(0);
    ivec4 sprite=ivec4(geometry4(rect));
    vec4 texel=texelFetch(AtlasSampler,sprite.xy+min(ivec2(uv*vec2(sprite.zw)),sprite.zw-1),0);
    if(texel.a<0.1)return vec3(0);
    float ink=max(texel.r,max(texel.g,texel.b));
    vec3 hue=ink>0.0?texel.rgb/ink:vec3(1);
#ifdef PAINT_COLOR
    vec4 tint=texelFetch(ColorsSampler,ivec2(index&1023,index>>10),0);
    if(tint.a>0.5)hue=tint.rgb;
#endif
    // A brush mark is an emitter even on dark artwork. Keep full-bright texture
    // contrast above the floor, then apply the user's usual highlight response.
    float peak=max(ink,mix(Threshold,1.0,0.55));
    float soft=clamp(peak-Threshold+Knee,0.0,2.0*Knee);
    float energy=max(peak-Threshold,soft*soft/(4.0*Knee+0.00001));
#ifndef PAINT_UNIFORM
    energy*=float(meta.w&255u)/255.0;
#endif
    return hue*(energy*tail.z*texel.a);
}
vec4 bright(ivec2 p){
    p=clamp(p,ivec2(0),ivec2(DepthSize)-1);
    float depth=texelFetch(DepthSampler,p,0).r;
    if(depth<=0.0)return vec4(0);
    vec2 uv=(vec2(p)+0.5)/DepthSize;
    float z=CameraFromOrigin.w>0.5?depth:depth*2.0-1.0;
    vec4 position=InverseViewProjection*vec4(uv*2.0-1.0,z,1);
    vec3 relative=position.xyz/position.w;
    float dist=length(relative);
    if(dist>=Range)return vec4(0);
    vec3 surface=relative+CameraFromOrigin.xyz;
    ivec3 voxel=ivec3(floor(surface+normalize(relative)*0.003));
    if(any(lessThan(voxel,ivec3(0)))||any(greaterThanEqual(voxel,ivec3(128))))return vec4(0);
    int index=voxel.x+128*(voxel.y+128*voxel.z),word=index>>5;
    uint bits=packedWord(texelFetch(SourcesSampler,ivec2(word&255,word>>8),0));
    if((bits&(1u<<uint(index&31)))==0u)return vec4(0);
    vec3 glow=paintedLight(index,surface-vec3(voxel),relative)*(1.0-smoothstep(FadeStart,Range,dist));
#ifdef PAINT_RAIN
    if(RainFogBase.z>0.5){
        // The atlas has no scene fog to undo. Attenuate intrinsic emission once.
        float environment=rainFogRamp(dist,RainFogDistances.x,RainFogDistances.y);
        float renderFog=rainFogRamp(max(length(relative.xz),abs(relative.y)),RainFogDistances.z,RainFogDistances.w);
        glow*=1.0-clamp(max(environment,renderFog)*RainFogColor.a,0.0,1.0);
    }
#endif
#ifdef PAINT_DISTANCE
    return vec4(glow,dot(glow,vec3(0.2126,0.7152,0.0722))*(1.0-smoothstep(PixelStart,PixelEnd,dist)));
#else
    return vec4(glow,1);
#endif
}
void main(){
#ifdef BLOOM_GRID_THREE
    ivec2 p=ivec2(floor(texCoord*OutSize))*3;
    vec4 value=vec4(0);
    for(int y=0;y<3;y++)for(int x=0;x<3;x++)value+=bright(p+ivec2(x,y));
    value/=9.0;
#else
    ivec2 p=ivec2(floor(texCoord*OutSize))*2;
    vec4 value=(bright(p)+bright(p+ivec2(1,0))+bright(p+ivec2(0,1))+bright(p+ivec2(1,1)))*0.25;
#endif
#ifdef PAINT_DISTANCE
    float mass=dot(value.rgb,vec3(0.2126,0.7152,0.0722));
    vec3 rgb=roundEven(clamp(value.rgb,0.0,1.0)*255.0)/255.0;
    fragColor=vec4(rgb,value.a*dot(rgb,vec3(0.2126,0.7152,0.0722))/max(mass,1e-12));
#else
    fragColor=vec4(value.rgb,1);
#endif
}
