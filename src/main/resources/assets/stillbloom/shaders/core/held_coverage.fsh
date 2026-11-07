#version 330
#extension GL_ARB_separate_shader_objects : require
uniform sampler2D AtlasSampler;
uniform sampler2D DepthSampler;
layout(std140) uniform HeldFrame { vec4 Crop; vec4 Frame; };
layout(location=0) in vec4 uv;
layout(location=1) flat in vec4 heldData[3];
#define params heldData[0]
layout(location=0) out vec4 coverage;
void main(){
    float depthTolerance=max(0.00000005,abs(gl_FragCoord.z)*0.000002)+fwidth(gl_FragCoord.z)*0.0625;
    ivec2 pixel=ivec2(gl_FragCoord.xy+Crop.xy);
    if(any(greaterThanEqual(pixel,ivec2(Frame.xy))))discard;
    float depth=texelFetch(DepthSampler,pixel,0).r;
    if(depth<=0.0||abs(depth-gl_FragCoord.z)>depthTolerance)discard;
    if(texture(AtlasSampler,uv.xy).a<0.1)discard;
    coverage=vec4(params.y,0,0,1);
}
