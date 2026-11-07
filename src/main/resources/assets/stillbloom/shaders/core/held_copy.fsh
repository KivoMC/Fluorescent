#version 330
#extension GL_ARB_separate_shader_objects : require
uniform sampler2D InSampler;
layout(std140) uniform CopyRegion { vec4 Offset; };
layout(location=0) out vec4 fragColor;
void main(){fragColor=texelFetch(InSampler,ivec2(gl_FragCoord.xy)+ivec2(Offset.xy),0);}
