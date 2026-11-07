#version 330
#extension GL_ARB_separate_shader_objects : require
uniform sampler2D AtlasSampler;
uniform sampler2D MaskSampler;
uniform sampler2D DepthSampler;
layout(std140) uniform HeldFrame { vec4 Crop; vec4 Frame; };
layout(location=0) in vec4 uv;
layout(location=1) flat in vec4 heldData[3];
#define params heldData[0]
#define tint heldData[1]
#define bloomColor heldData[2]
// Vulkan 26.2 renumbers separate output resources by reflection order. One
// array keeps emission at attachment 0 and coverage at 1 on both backends.
layout(location=0) out vec4 heldTargets[2];
void main(){
    float depthTolerance=max(0.00000005,abs(gl_FragCoord.z)*0.000002)+fwidth(gl_FragCoord.z)*0.0625;
    ivec2 pixel=ivec2(gl_FragCoord.xy+Crop.xy);
    if(any(greaterThanEqual(pixel,ivec2(Frame.xy))))discard;
    float depth=texelFetch(DepthSampler,pixel,0).r;
    // The same submitted triangles wrote scene depth. Reject intervening hands, players and walls.
    if(depth<=0.0||abs(depth-gl_FragCoord.z)>depthTolerance)discard;
    vec4 texel=texture(AtlasSampler,uv.xy);
    if(texel.a<0.1)discard;
    heldTargets[1]=vec4(params.y,0,0,1);
    vec3 color=texel.rgb*tint.rgb;
    float peak=max(color.r,max(color.g,color.b));
    float soft=clamp(peak-Frame.z+Frame.w,0.0,2.0*Frame.w);
    soft=soft*soft/(4.0*Frame.w+0.00001);
    if(bloomColor.a>0.5)color=peak*bloomColor.rgb;
    vec3 glow=color*(max(peak-Frame.z,soft)/max(peak,0.00001))*params.x*params.z*texture(MaskSampler,uv.zw).r;
    heldTargets[0]=vec4(glow,dot(glow,vec3(0.2126,0.7152,0.0722))*params.w);
}
