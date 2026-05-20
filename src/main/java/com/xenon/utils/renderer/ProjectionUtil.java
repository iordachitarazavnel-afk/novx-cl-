package com.xenon.utils.renderer;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.Camera;
import net.minecraft.util.Pair;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.opengl.GL11;

public class ProjectionUtil {

    public static final Matrix4f projectionMatrix = new Matrix4f();
    public static final Matrix4f modelViewMatrix = new Matrix4f();
    public static final Matrix4f positionMatrix = new Matrix4f();

    static MinecraftClient mc = MinecraftClient.getInstance();

    public static final class ScreenProjection {
        public double x;
        public double y;
        public double z;
        public double w;
        public boolean visible;

        public void set(double x, double y, double z, double w, boolean visible) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.w = w;
            this.visible = visible;
        }
    }

    public static Vec3d worldSpaceToScreenSpace(Vec3d pos) {
        Camera camera = mc.getEntityRenderDispatcher().camera;
        int displayHeight = mc.getWindow().getHeight();
        int[] viewport = new int[4];
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);
        Vector3f target = new Vector3f();

        double deltaX = pos.x - camera.getCameraPos().x;
        double deltaY = pos.y - camera.getCameraPos().y;
        double deltaZ = pos.z - camera.getCameraPos().z;

        Vector4f transformedCoordinates = new Vector4f((float) deltaX, (float) deltaY, (float) deltaZ, 1.f).mul(positionMatrix);

        Matrix4f matrixProj = new Matrix4f(projectionMatrix);
        Matrix4f matrixModel = new Matrix4f(modelViewMatrix);

        matrixProj.mul(matrixModel).project(transformedCoordinates.x(), transformedCoordinates.y(), transformedCoordinates.z(), viewport, target);

        return new Vec3d(target.x / mc.getWindow().getScaleFactor(), (displayHeight - target.y) / mc.getWindow().getScaleFactor(), target.z);
    }

    public static Pair<Vec3d, Boolean> project(Matrix4f modelView, Matrix4f projection, Vec3d vector) {
        if (mc.gameRenderer == null || mc.getCameraEntity() == null) return null;

        ScreenProjection screenProjection = new ScreenProjection();
        if (!projectToScreen(modelView, projection, vector.x, vector.y, vector.z, screenProjection)) {
            return null;
        }

        return new Pair<>(new Vec3d(screenProjection.x, screenProjection.y, screenProjection.z), screenProjection.visible);
    }

    public static boolean projectToScreen(Matrix4f modelView, Matrix4f projection, double worldX, double worldY, double worldZ, ScreenProjection output) {
        if (mc.gameRenderer == null || mc.getCameraEntity() == null || output == null) {
            return false;
        }

        Vec3d cameraPos = mc.gameRenderer.getCamera().getCameraPos();
        double relX = worldX - cameraPos.x;
        double relY = worldY - cameraPos.y;
        double relZ = worldZ - cameraPos.z;

        double viewX = modelView.m00() * relX + modelView.m10() * relY + modelView.m20() * relZ + modelView.m30();
        double viewY = modelView.m01() * relX + modelView.m11() * relY + modelView.m21() * relZ + modelView.m31();
        double viewZ = modelView.m02() * relX + modelView.m12() * relY + modelView.m22() * relZ + modelView.m32();
        double viewW = modelView.m03() * relX + modelView.m13() * relY + modelView.m23() * relZ + modelView.m33();

        double clipX = projection.m00() * viewX + projection.m10() * viewY + projection.m20() * viewZ + projection.m30() * viewW;
        double clipY = projection.m01() * viewX + projection.m11() * viewY + projection.m21() * viewZ + projection.m31() * viewW;
        double clipZ = projection.m02() * viewX + projection.m12() * viewY + projection.m22() * viewZ + projection.m32() * viewW;
        double clipW = projection.m03() * viewX + projection.m13() * viewY + projection.m23() * viewZ + projection.m33() * viewW;

        boolean isVisible = clipW > 0.0;
        double invW = clipW != 0.0 ? 1.0 / clipW : 0.0;
        double ndcX = clipX * invW;
        double ndcY = clipY * invW;
        double ndcZ = clipZ * invW;

        double screenX = (ndcX * 0.5 + 0.5) * mc.getWindow().getScaledWidth();
        double screenY = (0.5 - ndcY * 0.5) * mc.getWindow().getScaledHeight();

        output.set(screenX, screenY, ndcZ, clipW, isVisible);
        return true;
    }

    public static Vector3f projectVector(Matrix4f modelView, Matrix4f projection, Vec3d vector) {
        Pair<Vec3d, Boolean> result = project(modelView, projection, vector);
        if (result == null) return null;
        Vec3d pos = result.getLeft();
        return new Vector3f((float) pos.x, (float) pos.y, (float) pos.z);
    }

    public static Vector3f project(double x, double y, double z) {
        if (mc.gameRenderer == null || mc.getCameraEntity() == null) return null;
        return project(new Vec3d(x, y, z));
    }

    public static Vector3f project(Vec3d vector) {
        if (mc.gameRenderer == null || mc.getCameraEntity() == null) return null;
        Vec3d result = worldSpaceToScreenSpace(vector);
        if (result.z < 0 || result.z > 1) return null;
        return new Vector3f((float) result.x, (float) result.y, (float) result.z);
    }

    public static Vector3f projectWithClamp(Matrix4f modelView, Matrix4f projection, Vec3d vector) {
        if (mc.gameRenderer == null || mc.getCameraEntity() == null) return null;

        Vec3d camPos = vector.subtract(mc.gameRenderer.getCamera().getCameraPos());
        if (camPos.lengthSquared() < 0.0001) {
            return new Vector3f(mc.getWindow().getScaledWidth() / 2f, mc.getWindow().getScaledHeight() / 2f, 0);
        }
        Vector4f vec = new Vector4f((float) camPos.x, (float) camPos.y, (float) camPos.z, 1F);

        vec.mul(modelView);
        vec.mul(projection);

        boolean isBehind = vec.w() <= 0.0f;

        float w = Math.abs(vec.w());
        if (w < 0.001f) w = 0.001f;

        float nX = vec.x() / w;
        float nY = vec.y() / w;

        float screenWidth = mc.getWindow().getScaledWidth();
        float screenHeight = mc.getWindow().getScaledHeight();
        float centerX = screenWidth / 2f;
        float centerY = screenHeight / 2f;

        float screenX = (nX * 0.5f + 0.5f) * screenWidth;
        float screenY = (0.5f - nY * 0.5f) * screenHeight;

        if (!isBehind && screenX >= 0 && screenX <= screenWidth && screenY >= 0 && screenY <= screenHeight) {
            return new Vector3f(screenX, screenY, 0);
        }

        float dirX = screenX - centerX;
        float dirY = screenY - centerY;

        if (dirX == 0 && dirY == 0) {
            dirY = 1;
        }

        float margin = 10f;
        float halfW = screenWidth / 2f - margin;
        float halfH = screenHeight / 2f - margin;

        float kX = Float.MAX_VALUE;
        float kY = Float.MAX_VALUE;

        if (dirX != 0) kX = Math.abs(halfW / dirX);
        if (dirY != 0) kY = Math.abs(halfH / dirY);

        float k = Math.min(kX, kY);

        return new Vector3f(centerX + dirX * k, centerY + dirY * k, 0);
    }
}
