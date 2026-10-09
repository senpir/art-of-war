package com.artofwar;

import javax.inject.Inject;

import net.runelite.api.Client;
import net.runelite.api.Model;
import net.runelite.api.Perspective;
import net.runelite.api.Player;
import net.runelite.api.RuneLiteObject;
import net.runelite.api.coords.LocalPoint;

public class ScytheModelSpawner
{

    private static final int FULL_ROTATION = 2048;
    private static final int RENDER_RADIUS = 255;
    private static final int SLOPE_SAMPLE_DISTANCE = 64;

    private static final int HILL_CORRECTION_DEADZONE = 24;
    private static final int TERRAIN_DISCONTINUITY_THRESHOLD = 128;

    private static final double HILL_CORRECTION_SCALE = 0.22;

    @Inject
    private Client client;

    private RuneLiteObject spawnedObject;

    public void spawn(Model model)
    {
        if (model == null || client.getLocalPlayer() == null)
        {
            return;
        }

        despawn();

        spawnedObject = client.createRuneLiteObject();
        spawnedObject.setModel(model);
        spawnedObject.setRadius(RENDER_RADIUS);
        spawnedObject.setActive(true);
    }

    public void setModel(Model model)
    {
        if (spawnedObject != null && model != null)
        {
            spawnedObject.setModel(model);
        }
    }

    public void setVisible(boolean visible)
    {
        if (spawnedObject != null
                && spawnedObject.isActive() != visible)
        {
            spawnedObject.setActive(visible);
        }
    }

    public void updateTransform(int yawDegrees)
    {
        if (spawnedObject == null)
        {
            return;
        }

        Player player = client.getLocalPlayer();

        if (player == null)
        {
            return;
        }

        LocalPoint playerLocation =
                player.getLocalLocation();

        if (playerLocation == null)
        {
            return;
        }

        int plane = client.getPlane();

        spawnedObject.setLocation(
                playerLocation,
                plane
        );

        int baseZ = spawnedObject.getZ();

        int hillCorrection =
                calculateHillCorrection(
                        playerLocation,
                        plane
                );

        int finalZ = baseZ - hillCorrection;

        spawnedObject.setZ(finalZ);

        int playerOrientation =
                player.getCurrentOrientation();

        int yawUnits =
                (int) Math.round(
                        yawDegrees
                                * FULL_ROTATION
                                / 360.0
                );

        int orientation =
                (playerOrientation + yawUnits)
                        % FULL_ROTATION;

        if (orientation < 0)
        {
            orientation += FULL_ROTATION;
        }

        spawnedObject.setOrientation(
                orientation
        );

        spawnedObject.setRadius(
                RENDER_RADIUS
        );
    }

    private int calculateHillCorrection(
            LocalPoint playerLocation,
            int plane
    )
    {
        LocalPoint eastPoint =
                playerLocation.dx(
                        SLOPE_SAMPLE_DISTANCE
                );

        LocalPoint westPoint =
                playerLocation.dx(
                        -SLOPE_SAMPLE_DISTANCE
                );

        LocalPoint northPoint =
                playerLocation.dy(
                        SLOPE_SAMPLE_DISTANCE
                );

        LocalPoint southPoint =
                playerLocation.dy(
                        -SLOPE_SAMPLE_DISTANCE
                );

        int center =
                Perspective.getTileHeight(
                        client,
                        playerLocation,
                        plane
                );

        int east =
                Perspective.getTileHeight(
                        client,
                        eastPoint,
                        plane
                );

        int west =
                Perspective.getTileHeight(
                        client,
                        westPoint,
                        plane
                );

        int north =
                Perspective.getTileHeight(
                        client,
                        northPoint,
                        plane
                );

        int south =
                Perspective.getTileHeight(
                        client,
                        southPoint,
                        plane
                );

        int slopeX = east - west;
        int slopeY = north - south;

        int curvatureX =
                Math.abs(
                        (east + west)
                                - (center * 2)
                );

        int curvatureY =
                Math.abs(
                        (north + south)
                                - (center * 2)
                );

        boolean terrainDiscontinuity =
                curvatureX > TERRAIN_DISCONTINUITY_THRESHOLD
                        || curvatureY > TERRAIN_DISCONTINUITY_THRESHOLD;

        double steepness =
                Math.hypot(
                        slopeX,
                        slopeY
                );

        int hillCorrection = 0;

        if (!terrainDiscontinuity
                && steepness > HILL_CORRECTION_DEADZONE)
        {
            hillCorrection =
                    (int) Math.round(
                            steepness
                                    * HILL_CORRECTION_SCALE
                    );
        }

        return hillCorrection;
    }

    public void despawn()
    {
        if (spawnedObject != null)
        {
            spawnedObject.setActive(false);
            spawnedObject = null;
        }
    }

    public boolean hasObject()
    {
        return spawnedObject != null;
    }
}