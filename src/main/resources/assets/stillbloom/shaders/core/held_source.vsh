#version 330
#extension GL_ARB_separate_shader_objects : require
layout(location=0) in vec4 ClipPosition;
layout(location=1) in vec4 TexCoords;
layout(location=2) in vec4 TintColor;
layout(location=3) in vec4 BloomColor;
layout(location=4) in vec4 SourceParams;
layout(std140) uniform HeldFrame { vec4 Crop; vec4 Frame; };
layout(location=0) out vec4 uv;
// Keep one flat interface resource shared by both fragment shaders. Vulkan 26.2
// compresses locations when a fragment shader omits individual vertex outputs.
layout(location=1) flat out vec4 heldData[3];
void main(){
    vec2 pixel=(ClipPosition.xy+ClipPosition.ww)*0.5*Frame.xy;
    gl_Position=vec4((pixel-Crop.xy*ClipPosition.w)*2.0/Crop.zw-ClipPosition.ww,ClipPosition.zw);
    uv=TexCoords;heldData[0]=SourceParams;heldData[1]=TintColor;heldData[2]=BloomColor;
}
