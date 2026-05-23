#version 150

#define MAX_HISTORY 16

uniform sampler2D u_textures[MAX_HISTORY];
uniform float u_weights[MAX_HISTORY];
uniform int u_historySize;

in vec2 vTexCoord;
out vec4 fragColor;

void main() {
    vec3 color = vec3(0.0);
    float totalWeight = 0.0;

    for (int i = 0; i < u_historySize; i++) {
        float weight = u_weights[i];
        color += texture(u_textures[i], vTexCoord).rgb * weight;
        totalWeight += weight;
    }

    fragColor = vec4(color / max(totalWeight, 0.0001), 1.0);
}
