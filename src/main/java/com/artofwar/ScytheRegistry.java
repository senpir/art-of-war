package com.artofwar;

import java.awt.Color;
import java.util.EnumMap;
import java.util.Map;

final class ScytheRegistry
{
    enum ModelBehavior
    {
        STANDARD,
        FLICKER_ALTERNATE
    }

    static final class Definition
    {
        private final String primaryFileName;
        private final String alternateFileName;
        private final Color trailStartColor;
        private final Color trailEndColor;
        private final ModelBehavior modelBehavior;

        private Definition(
                String primaryFileName,
                String alternateFileName,
                Color trailStartColor,
                Color trailEndColor,
                ModelBehavior modelBehavior)
        {
            this.primaryFileName = primaryFileName;
            this.alternateFileName = alternateFileName;
            this.trailStartColor = trailStartColor;
            this.trailEndColor = trailEndColor;
            this.modelBehavior = modelBehavior;
        }

        String getPrimaryFileName()
        {
            return primaryFileName;
        }

        String getAlternateFileName()
        {
            return alternateFileName;
        }

        Color getTrailStartColor()
        {
            return trailStartColor;
        }

        Color getTrailEndColor()
        {
            return trailEndColor;
        }

        boolean hasAlternateModel()
        {
            return alternateFileName != null;
        }

        boolean usesFlickerAlternate()
        {
            return modelBehavior == ModelBehavior.FLICKER_ALTERNATE;
        }
    }

    private static final Map<ArtOfWarConfig.ScytheModel, Definition> DEFINITIONS =
            new EnumMap<>(ArtOfWarConfig.ScytheModel.class);

    static
    {
        DEFINITIONS.put(
                ArtOfWarConfig.ScytheModel.MAIDEN,
                new Definition(
                        "maiden_scythe.json",
                        null,
                        new Color(255, 255, 255),
                        new Color(170, 35, 35),
                        ModelBehavior.STANDARD
                )
        );

        DEFINITIONS.put(
                ArtOfWarConfig.ScytheModel.BLOAT,
                new Definition(
                        "bloat_scythe_on.json",
                        "bloat_scythe_off.json",
                        new Color(239, 189, 115),
                        new Color(221, 144, 26),
                        ModelBehavior.FLICKER_ALTERNATE
                )
        );


        DEFINITIONS.put(
                ArtOfWarConfig.ScytheModel.NYLO_MAGE,
                new Definition(
                        "nylo_mage_scythe.json",
                        null,
                        new Color(9, 100, 103),
                        new Color(28, 230, 223),
                        ModelBehavior.STANDARD
                )
        );


        DEFINITIONS.put(
                ArtOfWarConfig.ScytheModel.SOTETSEG,
                new Definition(
                        "sote_scythe.json",
                        null,
                        new Color(70, 70, 70),
                        new Color(20, 20, 20),
                        ModelBehavior.STANDARD
                )
        );

        DEFINITIONS.put(
                ArtOfWarConfig.ScytheModel.XARPUS,
                new Definition(
                        "xarpus_scythe.json",
                        null,
                        new Color(92, 72, 62),
                        new Color(0, 140, 0),
                        ModelBehavior.STANDARD
                )
        );

        DEFINITIONS.put(
                ArtOfWarConfig.ScytheModel.VERZIK,
                new Definition(
                        "verzik_scythe.json",
                        null,
                        new Color(255, 231, 115),
                        new Color(95, 35, 115),
                        ModelBehavior.STANDARD
                )
        );

    }

    private ScytheRegistry()
    {
    }

    static Definition get(ArtOfWarConfig.ScytheModel scytheModel)
    {
        return DEFINITIONS.get(scytheModel);
    }
}
