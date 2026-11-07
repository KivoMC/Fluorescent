#version 330
#extension GL_ARB_separate_shader_objects : require
uniform sampler2D InSampler;
layout(std140) uniform SamplerInfo { vec2 OutSize; vec2 InSize; };
layout(location=0) in vec2 texCoord;
layout(location=0) out vec4 fragColor;
vec4 quantizeEmission(vec4 value){
    float mass=dot(value.rgb,vec3(0.2126,0.7152,0.0722));
    vec3 rgb=roundEven(clamp(value.rgb,0.0,1.0)*255.0)/255.0;
    return vec4(rgb,value.a*dot(rgb,vec3(0.2126,0.7152,0.0722))/max(mass,1e-12));
}
void main(){
    ivec2 cell=ivec2(floor(texCoord*OutSize)),origin=cell*2;
    vec4 c=vec4(0);
    for(int y=0;y<2;y++)for(int x=0;x<2;x++)c+=texelFetch(InSampler,clamp(origin+ivec2(x,y),ivec2(0),ivec2(InSize)-1),0);
    c=quantizeEmission(c/float(2*2));
    fragColor=c;
}
