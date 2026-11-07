#version 330
#extension GL_ARB_separate_shader_objects : require
uniform sampler2D InSampler;
layout(std140) uniform SamplerInfo { vec2 OutSize; vec2 InSize; };
layout(std140) uniform BloomConfig { float Extent; float CrossStyle; float Pad0; float Pad1; };
layout(location=0) in vec2 texCoord;
layout(location=0) out vec4 fragColor;
void main(){
    ivec2 p=ivec2(floor(texCoord*OutSize));
    vec3 result=vec3(0);float total=0;
    for(int i=-int(ceil(Extent));i<=int(ceil(Extent));i++){
        float w=max(0.0,1.0-abs(float(i))/(Extent+1.0));w*=w;
        result+=texelFetch(InSampler,clamp(p+ivec2(i,0),ivec2(0),ivec2(InSize)-1),0).rgb*w;
        total+=w;
        if(CrossStyle>0.5){
            result+=texelFetch(InSampler,clamp(p+ivec2(0,i),ivec2(0),ivec2(InSize)-1),0).rgb*w;
            total+=w;
        }
    }
    fragColor=vec4(result/max(total,0.00001),1);
}
