package com.artofwar;

import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.Model;
import net.runelite.api.Player;
import net.runelite.api.RuneLiteObject;
import net.runelite.api.coords.LocalPoint;

public class ScytheModelSpawner
{
    private static final int FULL_ROTATION = 2048;
    private static final int RENDER_RADIUS = 255;

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

    public void updateTransform(int side, int forward, int height, int yawDegrees)
    {
        if (!isSpawned())
        {
            return;
        }

        Player player = client.getLocalPlayer();

        if (player == null)
        {
            return;
        }

        LocalPoint playerLocation = player.getLocalLocation();

        if (playerLocation == null)
        {
            return;
        }

        int playerOrientation = player.getCurrentOrientation();
        double angle = playerOrientation * (2.0 * Math.PI / FULL_ROTATION);
        double sin = Math.sin(angle);
        double cos = Math.cos(angle);

        int rotatedX = (int) Math.round(side * cos + forward * sin);
        int rotatedY = (int) Math.round(forward * cos - side * sin);

        spawnedObject.setLocation(playerLocation, client.getPlane());
        spawnedObject.setX(spawnedObject.getX() + rotatedX);
        spawnedObject.setY(spawnedObject.getY() + rotatedY);
        spawnedObject.setZ(spawnedObject.getZ() + height);

        int yawUnits = (int) Math.round(yawDegrees * FULL_ROTATION / 360.0);
        int orientation = (playerOrientation + yawUnits) % FULL_ROTATION;

        if (orientation < 0)
        {
            orientation += FULL_ROTATION;
        }

        spawnedObject.setOrientation(orientation);
        spawnedObject.setRadius(RENDER_RADIUS);
    }

    public void despawn()
    {
        if (spawnedObject != null)
        {
            spawnedObject.setActive(false);
            spawnedObject = null;
        }
    }

    public boolean isSpawned()
    {
        return spawnedObject != null && spawnedObject.isActive();
    }
}
