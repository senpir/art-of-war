package com.artofwar;

import java.util.ArrayList;
import java.util.List;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.JagexColor;
import net.runelite.api.Model;
import net.runelite.api.ModelData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ScytheModelBuilder
{
    private static final Logger log = LoggerFactory.getLogger(ScytheModelBuilder.class);
    private static final int BASIC_MODEL = 823;
    private static final int TRANSPARENT_MODEL = 18871;
    private static final int PRIORITY_MODEL = 6733;

    @Inject
    private Client client;

    public Model build(ScytheModelData blender, int pitchDegrees, int rollDegrees)
    {
        if (blender == null || blender.vertices == null || blender.faces == null)
        {
            return null;
        }

        int vertexCount = blender.vertices.length;
        int faceCount = blender.faces.length;
        int[] verticesX = new int[vertexCount];
        int[] verticesY = new int[vertexCount];
        int[] verticesZ = new int[vertexCount];

        double pitch = Math.toRadians(pitchDegrees);
        double roll = Math.toRadians(rollDegrees);
        double pitchCos = Math.cos(pitch);
        double pitchSin = Math.sin(pitch);
        double rollCos = Math.cos(roll);
        double rollSin = Math.sin(roll);

        for (int i = 0; i < vertexCount; i++)
        {
            int[] vertex = blender.vertices[i];

            if (vertex == null || vertex.length < 3)
            {
                return null;
            }

            double x = vertex[0];
            double y = vertex[1];
            double z = vertex[2];

            double pitchedY = y * pitchCos - z * pitchSin;
            double pitchedZ = y * pitchSin + z * pitchCos;
            y = pitchedY;
            z = pitchedZ;

            double rolledX = x * rollCos - y * rollSin;
            double rolledY = x * rollSin + y * rollCos;
            x = rolledX;
            y = rolledY;

            verticesX[i] = (int) Math.round(x);
            verticesY[i] = (int) Math.round(y);
            verticesZ[i] = (int) Math.round(z);
        }

        int[] faces1 = new int[faceCount];
        int[] faces2 = new int[faceCount];
        int[] faces3 = new int[faceCount];

        for (int i = 0; i < faceCount; i++)
        {
            int[] face = blender.faces[i];

            if (face == null || face.length < 3)
            {
                return null;
            }

            faces1[i] = face[0];
            faces2[i] = face[1];
            faces3[i] = face[2];
        }

        int[] polys = {1, 0, 0, 0, 1};

        try
        {
            polys = getPolyCount(polys, faceCount - 3, vertexCount - 3);
        }
        catch (IllegalArgumentException ex)
        {
            log.error("Unable to construct model geometry.", ex);
            return null;
        }

        ModelData modelData = constructModel(polys);

        if (modelData == null
                || modelData.getVerticesCount() != vertexCount
                || modelData.getFaceCount() != faceCount)
        {
            return null;
        }

        float[] modelX = modelData.getVerticesX();
        float[] modelY = modelData.getVerticesY();
        float[] modelZ = modelData.getVerticesZ();
        int[] modelFace1 = modelData.getFaceIndices1();
        int[] modelFace2 = modelData.getFaceIndices2();
        int[] modelFace3 = modelData.getFaceIndices3();

        for (int i = 0; i < vertexCount; i++)
        {
            modelX[i] = verticesX[i];
            modelY[i] = verticesY[i];
            modelZ[i] = verticesZ[i];
        }

        for (int i = 0; i < faceCount; i++)
        {
            modelFace1[i] = faces1[i];
            modelFace2[i] = faces2[i];
            modelFace3[i] = faces3[i];
        }

        if (!applyFaceColours(blender, modelData))
        {
            return null;
        }

        Model model = modelData.light(64, 850, -30, -30, -50);

        if (model == null)
        {
            return null;
        }

        if (blender.priorities != null)
        {
            byte[] priorities = model.getFaceRenderPriorities();

            if (priorities != null)
            {
                int amount = Math.min(model.getFaceCount(), blender.priorities.length);

                for (int i = 0; i < amount; i++)
                {
                    priorities[i] = (byte) blender.priorities[i];
                }
            }
        }

        return model;
    }

    private boolean applyFaceColours(ScytheModelData blender, ModelData modelData)
    {
        if (blender.useVertexColours)
        {
            return true;
        }

        if (blender.faceColours == null
                || blender.faceColourIndex == null
                || blender.faceColourIndex.length < modelData.getFaceCount())
        {
            return false;
        }

        short[] colors = modelData.getFaceColors();
        byte[] transparencies = modelData.getFaceTransparencies();
        short[] palette = new short[blender.faceColours.length];
        byte[] transparencyPalette = new byte[blender.faceColours.length];

        for (int i = 0; i < blender.faceColours.length; i++)
        {
            double[] colour = blender.faceColours[i];

            if (colour == null || colour.length < 4)
            {
                continue;
            }

            int hue = (int) (63 - colour[0] * 63);
            int luminance = (int) (colour[1] * 127);
            int saturation = (int) (colour[2] * 7);

            palette[i] = JagexColor.packHSL(hue, saturation, luminance);

            double transparency = colour[3] * -256;

            if (transparency < -128)
            {
                transparency += 256;
            }

            transparencyPalette[i] = (byte) transparency;
        }

        for (int i = 0; i < modelData.getFaceCount(); i++)
        {
            int paletteIndex = blender.faceColourIndex[i];

            if (paletteIndex < 0 || paletteIndex >= palette.length)
            {
                return false;
            }

            colors[i] = palette[paletteIndex];

            if (transparencies != null)
            {
                transparencies[i] = transparencyPalette[paletteIndex];
            }
        }

        return true;
    }

    private int[] getPolyCount(int[] polys, int facesRemaining, int verticesRemaining)
    {
        if (facesRemaining == 0 && verticesRemaining == 0)
        {
            return polys;
        }

        if (facesRemaining < 0 || verticesRemaining < 0)
        {
            throw new IllegalArgumentException("Invalid model geometry.");
        }

        if (facesRemaining == 0)
        {
            throw new IllegalArgumentException("Invalid model geometry.");
        }

        double divisor = (double) verticesRemaining / facesRemaining;

        if (divisor == 3)
        {
            polys[1] += facesRemaining;
            return polys;
        }

        if (divisor == 2)
        {
            polys[2] += facesRemaining;
            return polys;
        }

        if (facesRemaining + 2 > verticesRemaining)
        {
            int change = facesRemaining + 2 - verticesRemaining;
            polys[3] += change;
            return getPolyCount(polys, facesRemaining - change, verticesRemaining);
        }

        if (verticesRemaining % 2 == 0)
        {
            polys[2]++;
            facesRemaining--;
            verticesRemaining -= 2;
        }
        else
        {
            polys[1]++;
            facesRemaining--;
            verticesRemaining -= 3;
        }

        return getPolyCount(polys, facesRemaining, verticesRemaining);
    }

    private ModelData constructModel(int[] polys)
    {
        List<ModelData> pieces = new ArrayList<>();

        ModelData priority = client.loadModelData(PRIORITY_MODEL);

        if (priority == null)
        {
            return null;
        }

        priority.cloneColors().cloneVertices();

        float[] priorityX = priority.getVerticesX();
        float[] priorityY = priority.getVerticesY();
        float[] priorityZ = priority.getVerticesZ();

        priorityX[0] = priorityX[1] = 64;
        priorityY[0] = priorityY[1] = 0;
        priorityZ[0] = priorityZ[1] = -64;

        priorityX[2] = priorityX[3] = -64;
        priorityY[2] = priorityY[3] = 0;
        priorityZ[2] = priorityZ[3] = 64;

        priorityX[4] = priorityX[5] = -64;
        priorityY[4] = priorityY[5] = 0;
        priorityZ[4] = priorityZ[5] = -64;

        pieces.add(priority);

        ModelData transparent = client.loadModelData(TRANSPARENT_MODEL);

        if (transparent == null)
        {
            return null;
        }

        transparent.cloneColors().cloneVertices().cloneTransparencies();

        float[] transparentX = transparent.getVerticesX();
        float[] transparentY = transparent.getVerticesY();
        float[] transparentZ = transparent.getVerticesZ();

        transparentX[0] = 64;
        transparentY[0] = 0;
        transparentZ[0] = -64;

        transparentX[1] = -64;
        transparentY[1] = 0;
        transparentZ[1] = 64;

        transparentX[2] = -64;
        transparentY[2] = 0;
        transparentZ[2] = -64;

        pieces.add(transparent);

        for (int i = 0; i < polys[1]; i++)
        {
            ModelData piece = client.loadModelData(BASIC_MODEL);

            if (piece == null)
            {
                return null;
            }

            piece.cloneColors().cloneVertices().translate(i + 1, i + 1, i + 1);
            pieces.add(piece);
        }

        for (int i = 0; i < polys[2]; i++)
        {
            ModelData piece = client.loadModelData(BASIC_MODEL);

            if (piece == null)
            {
                return null;
            }

            piece.cloneColors().cloneVertices().translate((i + 1) * 128, 0, 0);
            pieces.add(piece);
        }

        for (int i = 0; i < polys[3]; i++)
        {
            ModelData piece = client.loadModelData(BASIC_MODEL);

            if (piece == null)
            {
                return null;
            }

            piece.cloneColors().cloneVertices();
            pieces.add(piece);
        }

        return client.mergeModels(pieces.toArray(new ModelData[0]));
    }
}
