package dev.stillbloom.render;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

// I check fog math here; paired native images check the actual shader output.
class RainFogEmissionTest {
    private static final double EPS = 1e-10;
    private static final Path SHADERS =
            Files.isDirectory(Path.of("src/main/resources"))
                    ? Path.of("src/main/resources/assets/stillbloom/shaders")
                    : Path.of("outputs/StillBloom/src/main/resources/assets/stillbloom/shaders");

    @Test
    void nativeFogEndpointsAndCylinderUseTheOriginalRampConvention() {
        assertEquals(0, ramp(-160, -160, 768));
        assertEquals(1, ramp(768, -160, 768));
        assertEquals(168.0 / 928, ramp(8, -160, 768), EPS);
        assertEquals(0, ramp(5, 5, 5));
        assertEquals(1, ramp(6, 5, 5));
        // I include vertical distance because Minecraft's cylindrical distance does too.
        assertEquals(12, cylinder(3, 12, 4));
        assertEquals(5, cylinder(3, 2, 4));
        assertEquals(.5, Math.max(ramp(8, 0, 1024), ramp(cylinder(3, 12, 4), 10, 14)), EPS);
    }

    @Test
    void forwardFogRoundTripsToDryColorWithoutLosingHue() {
        for (double[] raw : List.of(rgb(1, .65, .1), rgb(.2, .75, 1), rgb(.1, .06, .03)))
            for (double[] fog : List.of(rgb(.015, .02, .04), rgb(.35, .3, .25)))
                for (double base : new double[] {0, .02, .1})
                    for (double extra : new double[] {.1, .3, .7}) {
                        double wet = base + extra;
                        var sample = correct(forward(raw, fog, wet), fog, wet, base, true);
                        assertArrayEquals(forward(raw, fog, base), sample.color, EPS);
                        assertEquals((1 - wet) / (1 - base), sample.ratio, EPS);
                    }
    }

    @Test
    void emissionKeepsPhysicalRainAttenuationInsteadOfAmplifyingThresholdLoss() {
        double[] raw = rgb(.78, .53, .08), fog = rgb(.01, .01, .02);
        double wet = .18, base = .01;
        double[] dry = forward(raw, fog, base), rain = forward(raw, fog, wet);
        var sample = correct(rain, fog, wet, base, true);
        double[] expected = scale(emission(dry), (1 - wet) / (1 - base));
        double[] result = scale(emission(sample.color), sample.ratio);
        assertArrayEquals(expected, result, EPS);
        assertTrue(peak(result) < peak(emission(dry)), "Natural fog still dims emission");
        assertTrue(peak(result) > peak(emission(rain)), "Fog must not be thresholded twice");
    }

    @Test
    void aDarkHandleDoesNotBecomeAnEmitterAndColorOverridesUseRecoveredPeak() {
        double[] fog = rgb(.02, .03, .04);
        var handle = correct(forward(rgb(.2, .12, .04), fog, .7), fog, .7, 0, true);
        assertArrayEquals(rgb(0, 0, 0), emission(handle.color), EPS);
        var flame = correct(forward(rgb(.9, .65, .1), fog, .35), fog, .35, 0, true);
        double[] green = scale(rgb(0, 1, 0), peak(flame.color));
        assertEquals(.9, peak(green), EPS);
        assertEquals(0, green[0]);
        assertEquals(0, green[2]);
        assertTrue(emission(green)[1] > 0);
    }

    @Test
    void noExtraFogAndDisabledCorrectionKeepOriginalColorAndEnergyExactly() {
        for (double[] color : List.of(rgb(.4, .3, .2), rgb(1, .25, 0), rgb(0, 0, 0))) {
            var disabled = correct(color, rgb(.1, .2, .3), .4, 0, false);
            var noExtra = correct(color, rgb(.1, .2, .3), .2, .2, true);
            var dry = correct(color, rgb(.1, .2, .3), 0, 0, true);
            for (var sample : List.of(disabled, noExtra, dry)) {
                assertArrayEquals(color, sample.color, 0);
                assertEquals(1, sample.ratio);
                assertArrayEquals(emission(color), scale(emission(sample.color), sample.ratio), 0);
            }
        }
    }

    @Test
    void recoveryIsContinuousForOrdinaryWeatherAndBoundedInDenseFog() {
        double[] raw = rgb(.9, .6, .2), fog = rgb(.015, .02, .025);
        double previous = Double.POSITIVE_INFINITY;
        for (int i = 0; i <= 900; i++) {
            double amount = i / 1000.0;
            var sample = correct(forward(raw, fog, amount), fog, amount, 0, true);
            double brightness = peak(scale(emission(sample.color), sample.ratio));
            assertTrue(Double.isFinite(brightness));
            assertTrue(brightness >= 0);
            assertTrue(brightness <= previous + EPS);
            if (i > 0)
                assertTrue(previous - brightness < .001, "No brightness steps in normal rain");
            previous = brightness;
        }
        for (double amount : new double[] {.950001, .99, 1}) {
            double[] observed = forward(raw, fog, amount);
            var sample = correct(observed, fog, amount, 0, true);
            assertArrayEquals(observed, sample.color, 0);
            assertEquals(1, sample.ratio);
            assertTrue(Double.isFinite(peak(emission(sample.color))));
        }
        var quantized = correct(rgb(0, .99, .1), rgb(.1, .2, .3), .8, 0, true);
        for (double channel : quantized.color) assertTrue(channel >= 0 && channel <= 1);
    }

    @Test
    void denseFogFadeIsContinuousAtBothRecoveryBoundaries() {
        double[] raw = rgb(1, .7, .1), fog = rgb(.42, .3, .2);
        for (double transmission : new double[] {.05, .10}) {
            double wet = 1 - transmission;
            double previous = Double.NaN;
            for (int i = -100; i <= 100; i++) {
                double amount = wet + i * 1e-7;
                var sample = correct(forward(raw, fog, amount), fog, amount, .02, true);
                double brightness = peak(scale(emission(sample.color), sample.ratio));
                assertTrue(Double.isFinite(brightness));
                if (!Double.isNaN(previous))
                    assertTrue(
                            Math.abs(brightness - previous) < 1e-6,
                            "Dense fog must not step at transmission " + transmission);
                previous = brightness;
            }
        }
        double[] observed = forward(raw, fog, .95);
        var dense = correct(observed, fog, .950001, .02, true);
        assertArrayEquals(observed, dense.color, 0);
        assertEquals(1, dense.ratio);
        var full = correct(forward(raw, fog, .899999), fog, .899999, .02, true);
        assertArrayEquals(forward(raw, fog, .02), full.color, EPS);
        assertEquals((1 - .899999) / .98, full.ratio, EPS);
    }

    @Test
    void allShaderVariantsCorrectOnlyValidatedSourcesAndPreserveDistanceMass() throws IOException {
        for (String stem :
                List.of("extract", "extract_auto", "extract_color", "extract_auto_color"))
            for (boolean distance : new boolean[] {false, true}) {
                String suffix = distance ? "_distance" : "";
                String original =
                        Files.readString(SHADERS.resolve("post/" + stem + suffix + ".fsh"));
                String rain =
                        Files.readString(
                                SHADERS.resolve("post/" + stem + "_rain" + suffix + ".fsh"));
                assertTrue(rain.contains("#include <stillbloom:rain_fog.glsl>"));
                int correction = rain.indexOf("rainFogEmission(c,relative,distanceToSource)");
                int source = rain.indexOf("if((bits&(1u<<uint(index&31)))==0u)");
                assertTrue(correction > source, "Non-source pixels must not receive correction");
                if (!stem.contains("auto"))
                    assertTrue(correction > rain.indexOf("if(sourceStrength<=0.0)"));
                assertTrue(rain.contains("if(RainFogBase.z<=0.5&&peak<=Threshold-Knee)"));
                assertTrue(rain.indexOf("peak=max(c.r,max(c.g,c.b));", correction) > correction);
                if (stem.contains("color")) assertTrue(correction < rain.indexOf("vec4 tint="));
                if (distance) {
                    String alpha =
                            "return vec4(glow,dot(glow,vec3(0.2126,0.7152,0.0722))*(1.0-smoothstep(PixelStart,PixelEnd,distanceToSource)));";
                    assertTrue(original.contains(alpha));
                    assertTrue(rain.contains(alpha));
                    assertTrue(rain.indexOf("glow*=rainAttenuation;") < rain.indexOf(alpha));
                }
            }
        String shared = Files.readString(SHADERS.resolve("include/rain_fog.glsl"));
        assertEquals(3, shared.lines().filter(s -> s.trim().startsWith("vec4 RainFog")).count());
        assertTrue(shared.contains("transmission<=0.05"));
        assertTrue(shared.contains("smoothstep(0.05,0.10,min(transmission,baseTransmission))"));
        assertTrue(shared.contains("vertex fog distances"));
    }

    private record Sample(double[] color, double ratio) {}

    private static Sample correct(
            double[] observed, double[] fog, double wet, double base, boolean enabled) {
        if (!enabled || wet <= base || 1 - wet <= .05 || 1 - base <= .05)
            return new Sample(observed.clone(), 1);
        double blend = Math.clamp((Math.min(1 - wet, 1 - base) - .05) / .05, 0, 1);
        blend = blend * blend * (3 - 2 * blend);
        double[] recovered = new double[3];
        for (int c = 0; c < 3; c++) {
            double raw = Math.clamp((observed[c] - wet * fog[c]) / (1 - wet), 0, 1);
            double dry = raw * (1 - base) + fog[c] * base;
            recovered[c] = observed[c] * (1 - blend) + dry * blend;
        }
        return new Sample(recovered, 1 * (1 - blend) + (1 - wet) / (1 - base) * blend);
    }

    private static double ramp(double distance, double start, double end) {
        return distance <= start ? 0 : distance >= end ? 1 : (distance - start) / (end - start);
    }

    private static double cylinder(double x, double y, double z) {
        return Math.max(Math.hypot(x, z), Math.abs(y));
    }

    private static double[] forward(double[] raw, double[] fog, double amount) {
        return rgb(
                raw[0] * (1 - amount) + fog[0] * amount,
                raw[1] * (1 - amount) + fog[1] * amount,
                raw[2] * (1 - amount) + fog[2] * amount);
    }

    private static double[] emission(double[] color) {
        double peak = peak(color);
        if (peak <= .56 - .22) return rgb(0, 0, 0);
        double soft = Math.clamp(peak - .56 + .22, 0, .44);
        soft = soft * soft / (.88 + .00001);
        return scale(color, Math.max(peak - .56, soft) / Math.max(peak, .00001));
    }

    private static double[] scale(double[] color, double factor) {
        return rgb(color[0] * factor, color[1] * factor, color[2] * factor);
    }

    private static double peak(double[] c) {
        return Math.max(c[0], Math.max(c[1], c[2]));
    }

    private static double[] rgb(double r, double g, double b) {
        return new double[] {r, g, b};
    }
}
