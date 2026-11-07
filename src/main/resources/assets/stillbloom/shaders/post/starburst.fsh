#version 330
#extension GL_ARB_separate_shader_objects : require
uniform sampler2D InSampler;
layout(std140) uniform SamplerInfo { vec2 OutSize; vec2 InSize; };
layout(std140) uniform BloomConfig { float Extent; float InnerFalloff; float OuterFalloff; float HaloBalance; };
layout(location=0) in vec2 texCoord;
layout(location=0) out vec4 fragColor;
void main(){
    vec2 p=floor(texCoord*OutSize)+0.5;
    vec3 inner=texture(InSampler,p/InSize).rgb,outer=inner;
    vec2 total=vec2(1.0),sigma=max(vec2(InnerFalloff,OuterFalloff)*Extent,vec2(0.15));
    for(int i=1;i<=int(ceil(Extent));i++){
        vec2 w=clamp(Extent+1.0-float(i),0.0,1.0)*exp(-float(i*i)/(2.0*sigma*sigma));
        for(int axis=0;axis<4;axis++){
            vec2 dir=axis==0?vec2(1,0):axis==1?vec2(0,1):axis==2?vec2(0.70710678,0.70710678):vec2(0.70710678,-0.70710678);
            vec3 sampleColor=texture(InSampler,(p+dir*float(i))/InSize).rgb+texture(InSampler,(p-dir*float(i))/InSize).rgb;
            inner+=sampleColor*w.x;outer+=sampleColor*w.y;
            total+=2.0*w;
        }
    }
    fragColor=vec4(mix(inner/total.x,outer/total.y,HaloBalance),1);
}
