package dev.stillbloom.render.source;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class UniformEmissionTest {
    @Test
    void standardFacesNormalizePackedShadingToTheExistingBrightVerticalSide() {
        float[] shades = {.5f, 1, .8f, .8f, .6f, .6f};
        float reference = TextureGeometry.Emission.reference(.8f, .8f, .6f, .6f);
        float[] expected = {204f / 127, .8f, 1, 1, 204f / 153, 204f / 153};
        float[] texture = {.26f, .81f, .93f};
        for (int i = 0; i < shades.length; i++) {
            float gain = TextureGeometry.Emission.compensation(shades[i], reference, true);
            assertEquals(expected[i], gain, 0.000001f);
            for (float channel : texture) {
                float shaded = channel * ((int) (shades[i] * 255) / 255f);
                assertEquals(
                        channel * .8f,
                        shaded * gain,
                        0.000001f,
                        "All cardinal directions should emit the same texture color");
            }
        }
    }

    @Test
    void netherReferenceRetainsItsExistingBrightVerticalSide() {
        float reference = TextureGeometry.Emission.reference(.8f, .8f, .6f, .6f),
                target = 204f / 255;
        for (float shade : new float[] {.9f, .8f, .6f}) {
            float source = .74f * ((int) (shade * 255) / 255f);
            assertEquals(
                    .74f * target,
                    source * TextureGeometry.Emission.compensation(shade, reference, true),
                    0.000001f);
        }
        assertEquals(1, TextureGeometry.Emission.compensation(.8f, reference, true));
        assertEquals(1, TextureGeometry.Emission.compensation(.6f, reference, false));
    }

    @Test
    void compensationPreventsDirectionalShadingFromCrossingTheSameBrightnessCutoff() {
        float texturePeak = .65f, cutoff = .4f;
        float reference = TextureGeometry.Emission.reference(.8f, .8f, .6f, .6f);
        float eastWest = texturePeak * 153 / 255;
        float northSouth = texturePeak * 204 / 255;
        assertTrue(eastWest <= cutoff, "The uncorrected dark side can fail extraction");
        assertTrue(northSouth > cutoff);
        float correctedEastWest =
                eastWest * TextureGeometry.Emission.compensation(.6f, reference, true);
        float correctedNorthSouth =
                northSouth * TextureGeometry.Emission.compensation(.8f, reference, true);
        assertTrue(correctedEastWest > cutoff);
        assertEquals(correctedNorthSouth, correctedEastWest, 0.000001f);
    }

    @Test
    void unshadedFacesNeverReceiveAnExtraBoost() {
        for (float shade : new float[] {.5f, .6f, .8f, 1, Float.NaN, Float.POSITIVE_INFINITY})
            assertEquals(1, TextureGeometry.Emission.compensation(shade, .9f, false));
    }

    @Test
    void malformedAndSubChannelFactorsSafelyKeepOriginalEmission() {
        for (float invalid :
                new float[] {
                    Float.NaN, Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY, -1, 0, 1.001f
                }) {
            assertEquals(1, TextureGeometry.Emission.compensation(invalid, 1, true));
            assertEquals(1, TextureGeometry.Emission.compensation(.6f, invalid, true));
        }
        assertEquals(1, TextureGeometry.Emission.compensation(.001f, 1, true));
        assertEquals(1, TextureGeometry.Emission.compensation(.6f, .001f, true));
    }

    @Test
    void customReferenceUsesTheActualBrightestVerticalSide() {
        float reference = TextureGeometry.Emission.reference(.25f, .72f, .9f, .6f);
        assertEquals(.9f, reference);
        assertEquals(1, TextureGeometry.Emission.compensation(.9f, reference, true));
        assertEquals(
                229f / 255, TextureGeometry.Emission.compensation(1, reference, true), 0.000001f);
        assertEquals(1, TextureGeometry.Emission.reference(1, .8f, .6f, .6f));
    }

    @Test
    void boundedGainSupportsBrighterCustomFacesWithoutChangingTheReference() {
        assertEquals(
                TextureGeometry.Emission.MAX_GAIN,
                TextureGeometry.Emission.compensation(.01f, 1, true));
        assertEquals(.6f, TextureGeometry.Emission.compensation(1, .6f, true), 0.000001f);
        assertEquals(
                TextureGeometry.Emission.MAX_GAIN,
                TextureGeometry.Emission.compensation(.5f, 1, true));
        assertTrue(
                TextureGeometry.Emission.MAX_GAIN >= 204f / 127,
                "The bound must retain the full standard downward-face correction");
        assertTrue(
                TextureGeometry.Emission.MAX_GAIN < 1.62f,
                "Do not admit avoidable dim pixels beyond the calibrated shade range");
    }
}
