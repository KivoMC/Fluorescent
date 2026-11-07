#version 330
#extension GL_ARB_separate_shader_objects : require
#define PAINT_RAIN
#include <stillbloom:rain_fog.glsl>
uniform sampler2D DepthSampler;
uniform sampler2D AtlasSampler;
uniform sampler2D SourcesSampler;
uniform sampler2D CustomSampler;
uniform usampler2D GeometrySampler;
layout(std140) uniform SamplerInfo { vec2 OutSize; vec2 DepthSize; vec2 AtlasSize; vec2 SourcesSize; vec2 CustomSize; vec2 GeometrySize; };
layout(std140) uniform BloomConfig { float Threshold; float Knee; float Range; float FadeStart; };
layout(std140) uniform SourceMask { mat4 InverseViewProjection; vec4 CameraFromOrigin; };
layout(location=0) in vec2 texCoord;
layout(location=0) out vec4 fragColor;
#include <stillbloom:paint_emission.glsl>
