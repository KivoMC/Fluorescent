#version 330
#extension GL_ARB_separate_shader_objects : require
uniform sampler2D InSampler;
uniform sampler2D HistorySampler;
layout(std140) uniform SamplerInfo { vec2 OutSize; vec2 InSize; vec2 HistorySize; };
layout(std140) uniform Stability { float HistoryWeight; float Pad0; float Pad1; float Pad2; };
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
    float peak=max(c.r,max(c.g,c.b));
    if(peak>0.0&&HistoryWeight>0.0){
        vec3 h=texelFetch(HistorySampler,cell,0).rgb;
        float previous=max(h.r,max(h.g,h.b));
        // Scale distance mass together with RGB; hue and distance always come from this frame.
        if(previous>0.0)c*=mix(peak,clamp(previous,peak*0.75,peak*1.25),HistoryWeight)/peak;
    }
    fragColor=c;
}
