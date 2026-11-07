// Rain-only extraction correction. Clear weather uses the original extraction shaders.
layout(std140) uniform RainFog {
    vec4 RainFogColor;
    vec4 RainFogDistances; // environmental start/end, render-distance start/end
    vec4 RainFogBase; // dry environmental start/end, enabled, reserved
};

// Match Minecraft's fog.glsl endpoint behavior, including zero-length spans.
float rainFogRamp(float distanceValue, float start, float end) {
    if(distanceValue<=start)return 0.0;
    if(distanceValue>=end)return 1.0;
    return (distanceValue-start)/(end-start);
}

// Apply only after source identity and painted coverage have been established.
// Minecraft interpolates vertex fog distances; reconstructed surface distances are
// a close per-fragment approximation. Deep fog keeps the old bounded extraction.
float rainFogEmission(inout vec3 color, vec3 relative, float sphericalDistance) {
    if(RainFogBase.z<=0.5)return 1.0;
    float cylindricalDistance=max(length(relative.xz),abs(relative.y));
    float renderFog=rainFogRamp(cylindricalDistance,RainFogDistances.z,RainFogDistances.w);
    float currentFog=max(rainFogRamp(sphericalDistance,RainFogDistances.x,RainFogDistances.y),renderFog)*RainFogColor.a;
    float baseFog=max(rainFogRamp(sphericalDistance,RainFogBase.x,RainFogBase.y),renderFog)*RainFogColor.a;
    if(currentFog<=baseFog)return 1.0;
    float transmission=1.0-currentFog;
    float baseTransmission=1.0-baseFog;
    if(transmission<=0.05||baseTransmission<=0.05)return 1.0;
    vec3 raw=clamp((color-currentFog*RainFogColor.rgb)/transmission,0.0,1.0);
    vec3 baseColor=mix(raw,RainFogColor.rgb,baseFog);
    // Fade back to the untouched scene in dense fog instead of crossing a hard
    // recovery cutoff. Smooth endpoints avoid brightness steps at either edge.
    float correction=smoothstep(0.05,0.10,min(transmission,baseTransmission));
    color=mix(color,baseColor,correction);
    // Threshold the source before the extra rain fog, then attenuate emission
    // linearly. This retains natural fog dimming without threshold amplification.
    return mix(1.0,transmission/baseTransmission,correction);
}
