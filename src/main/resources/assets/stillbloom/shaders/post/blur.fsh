#version 330
#extension GL_ARB_separate_shader_objects : require
uniform sampler2D InSampler;
layout(std140) uniform SamplerInfo { vec2 OutSize; vec2 InSize; };
layout(std140) uniform BloomConfig {
    float DirectionX; float DirectionY; float Softness; float Extent;
    float BoxShape; float Pad0; float Pad1; float Pad2;
};
layout(location=0) in vec2 texCoord;
layout(location=0) out vec4 fragColor;
void main() {
    vec2 p=floor(texCoord*OutSize)+0.5;
    vec2 direction=vec2(DirectionX,DirectionY);
    vec3 result=vec3(0.0);float total=0.0;
    int radius=int(ceil(Extent));
    float sigma=max(Softness*Extent,0.15);
    // Bound work by the configured width; fractional edge weights avoid width jumps.
    for(int i=-radius;i<=radius;i++){
        float edge=clamp(Extent+1.0-abs(float(i)),0.0,1.0);
        float weight=edge*(BoxShape>0.5?1.0:exp(-float(i*i)/(2.0*sigma*sigma)));
        result+=texture(InSampler,(p+direction*float(i))/InSize).rgb*weight;
        total+=weight;
    }
    fragColor=vec4(result/max(total,0.00001),1.0);
}
