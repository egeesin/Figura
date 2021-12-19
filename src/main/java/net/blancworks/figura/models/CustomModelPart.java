package net.blancworks.figura.models;

import com.google.common.collect.ImmutableMap;
import it.unimi.dsi.fastutil.floats.FloatArrayList;
import it.unimi.dsi.fastutil.floats.FloatList;
import net.blancworks.figura.avatar.AvatarData;
import net.blancworks.figura.lua.api.model.*;
import net.blancworks.figura.models.shaders.FiguraRenderLayer;
import net.blancworks.figura.models.shaders.FiguraVertexConsumerProvider;
import net.blancworks.figura.models.tasks.RenderTask;
import net.blancworks.figura.utils.MathUtils;
import net.fabricmc.fabric.api.util.NbtType;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.*;
import net.minecraft.client.render.entity.model.PlayerEntityModel;
import net.minecraft.client.texture.MissingSprite;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.*;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

public class CustomModelPart {
    public String name = "NULL";
    public CustomModel model;

    //Transform data
    public Vec3f pivot = Vec3f.ZERO.copy();
    public Vec3f pos = Vec3f.ZERO.copy();
    public Vec3f rot = Vec3f.ZERO.copy();
    public Vec3f scale = new Vec3f(1f, 1f, 1f);
    public Vec3f color = new Vec3f(1f, 1f, 1f);

    //uv stuff
    public Map<UV, uvData> UVCustomizations = new HashMap<>();
    public Vec2f texSize = new Vec2f(64f, 64f);
    public Vec2f uvOffset = new Vec2f(0f, 0f);

    //model properties
    public boolean visible = true;

    public ParentType parentType = ParentType.Model;
    public boolean isMimicMode = false;

    public RotationType rotationType = RotationType.BlockBench;

    public ShaderType shaderType = ShaderType.None;

    public FiguraRenderLayer customLayer = null;

    public TextureType textureType = TextureType.Custom;
    public Identifier textureVanilla = FiguraTexture.DEFAULT_ID;

    public boolean extraTex = true;
    public boolean cull = false;

    public float alpha = 1f;

    //All the vertex data is stored here! :D
    public FloatList vertexData = new FloatArrayList();
    public int vertexCount = 0;

    public Matrix4f lastModelMatrix = new Matrix4f();
    public Matrix3f lastNormalMatrix = new Matrix3f();

    public Matrix4f lastModelMatrixInverse = new Matrix4f();
    public Matrix3f lastNormalMatrixInverse = new Matrix3f();

    //Extra special rendering for this part
    public final HashMap<String, RenderTask> renderTasks = new LinkedHashMap<>();

    public static boolean canRenderHitBox = false;

    //Renders a model part (and all sub-parts) using the textures provided by a PlayerData instance.
    public int render(AvatarData data, MatrixStack matrices, MatrixStack transformStack, VertexConsumerProvider vcp, int light, int overlay, float alpha) {
        //no model to render
        if (data.model == null || data.vanillaModel == null || vcp == null || !data.isAvatarLoaded() || data.model.leftToRender <= 0)
            return 0;

        //lets render boys!!
        boolean applyHiddenTransforms = data.model.applyHiddenTransforms;
        ParentType renderOnly = data.model.renderOnly;
        int ret = data.model.leftToRender;
        data.model.renderOnly = null;

        //main texture
        Function<Identifier, RenderLayer> layerFunction = RenderLayer::getEntityTranslucent;
        ret = renderTextures(data, ret, matrices, transformStack, vcp, null, light, overlay, 0, 0, new Vec3f(1f, 1f, 1f), alpha, false, getTexture(data), layerFunction, false, applyHiddenTransforms, renderOnly);

        //extra textures
        for (FiguraTexture figuraTexture : data.extraTextures) {
            Function<Identifier, RenderLayer> renderLayerGetter = FiguraTexture.EXTRA_TEXTURE_TO_RENDER_LAYER.get(figuraTexture.type);

            if (renderLayerGetter != null) {
                renderTextures(data, ret, matrices, transformStack, vcp, null, light, overlay, 0, 0, new Vec3f(1f, 1f, 1f), alpha, false, figuraTexture.id, renderLayerGetter, true, applyHiddenTransforms, renderOnly);
            }
        }
        draw(vcp);

        //shaders
        ret = renderShaders(data, ret, matrices, vcp, light, overlay, 0, 0, new Vec3f(1f, 1f, 1f), alpha, false, (byte) 0, applyHiddenTransforms, renderOnly);
        draw(vcp);

        //extra stuff and hitboxes
        ret = renderExtraParts(data, ret, matrices, vcp, light, false, applyHiddenTransforms, renderOnly);
        draw(vcp);

        return ret;
    }

    //Renders this custom model part and all its children.
    //Returns the cuboids left to render after this one, and only renders until leftToRender is zero.
    public int renderTextures(AvatarData data, int leftToRender, MatrixStack matrices, MatrixStack transformStack, VertexConsumerProvider vcp, RenderLayer layer, int light, int overlay, float u, float v, Vec3f prevColor, float alpha, boolean canRender, Identifier texture, Function<Identifier, RenderLayer> layerFunction, boolean isExtraTex, boolean applyHiddenTransforms, ParentType renderOnly) {
        //do not render invisible parts
        if (!this.visible || (isExtraTex && !this.extraTex))
            return leftToRender;

        matrices.push();
        transformStack.push();

        if (applyHiddenTransforms) {
            applyVanillaTransforms(data, matrices, transformStack);

            applyTransforms(matrices);
            applyTransforms(transformStack);

            updateModelMatrices(transformStack);
        } else if (canRender) {
            applyTransforms(matrices);
            applyTransforms(transformStack);
        }

        if (renderOnly == null || this.parentType == renderOnly)
            canRender = true;

        //uv -> color -> alpha -> cull
        u += this.uvOffset.x;
        v += this.uvOffset.y;

        Vec3f color = this.color.copy();
        color.multiplyComponentwise(prevColor.getX(), prevColor.getY(), prevColor.getZ());

        alpha = this.alpha * alpha;

        if (!isExtraTex && this.cull)
            layerFunction = RenderLayer::getEntityTranslucentCull;

        //texture
        if (this.textureType != TextureType.Custom)
            texture = getTexture(data);

        //render!
        if (canRender) {
            //get vertex consumer
            VertexConsumer consumer;

            if (customLayer != null) {
                consumer = vcp.getBuffer(customLayer);
                layer = customLayer;
            }
            else if (layer instanceof FiguraRenderLayer) {
                consumer = vcp.getBuffer(layer);
            } else {
                consumer = vcp.getBuffer(layerFunction.apply(texture));
            }

            //render
            leftToRender = renderCube(leftToRender, matrices, consumer, light, overlay, u, v, color, alpha);
        }

        if (this instanceof CustomModelPartGroup group) {
            for (CustomModelPart child : group.children) {
                if (leftToRender <= 0)
                    break;

                //Don't render special parts.
                if (child.isSpecial())
                    continue;

                //render part
                leftToRender = child.renderTextures(data, leftToRender, matrices, transformStack, vcp, layer, light, overlay, u, v, color, alpha, canRender, texture, layerFunction, isExtraTex, applyHiddenTransforms, renderOnly);
            }
        }

        matrices.pop();
        transformStack.pop();

        return leftToRender;
    }

    public int renderShaders(AvatarData data, int leftToRender, MatrixStack matrices, VertexConsumerProvider vcp, int light, int overlay, float u, float v, Vec3f prevColor, float alpha, boolean canRender, byte shadersToRender, boolean applyHiddenTransforms, ParentType renderOnly) {
        //do not render invisible parts
        if (!this.visible)
            return leftToRender;

        matrices.push();

        if (applyHiddenTransforms) {
            applyVanillaTransforms(data, matrices, new MatrixStack());
            applyTransforms(matrices);
        } else if (canRender) {
            applyTransforms(matrices);
        }

        if (renderOnly == null || this.parentType == renderOnly)
            canRender = true;

        //uv -> color -> alpha -> shaders
        u += this.uvOffset.x;
        v += this.uvOffset.y;

        Vec3f color = this.color.copy();
        color.multiplyComponentwise(prevColor.getX(), prevColor.getY(), prevColor.getZ());

        alpha = this.alpha * alpha;

        byte shaders = shadersToRender;
        if (this.shaderType != ShaderType.None)
            shaders = (byte) (shaders | this.shaderType.id);

        //render!
        if (canRender) {
            if (ShaderType.EndPortal.isShader(shaders))
                leftToRender = renderCube(leftToRender, matrices, vcp.getBuffer(RenderLayer.getEndGateway()), light, overlay, u, v, color, alpha);
            if (ShaderType.Glint.isShader(shaders))
                leftToRender = renderCube(leftToRender, matrices, vcp.getBuffer(RenderLayer.getDirectEntityGlint()), light, overlay, u, v, color, alpha);
        }

        if (this instanceof CustomModelPartGroup group) {
            for (CustomModelPart child : group.children) {
                if (leftToRender <= 0)
                    break;

                //Don't render special parts.
                if (child.isSpecial())
                    continue;

                //render part
                leftToRender = child.renderShaders(data, leftToRender, matrices, vcp, light, overlay, u, v, color, alpha, canRender, shaders, applyHiddenTransforms, renderOnly);
            }
        }

        matrices.pop();

        return leftToRender;
    }

    public int renderExtraParts(AvatarData data, int leftToRender, MatrixStack matrices, VertexConsumerProvider vcp, int light, boolean canRender, boolean applyHiddenTransforms, ParentType renderOnly) {
        //do not render invisible parts
        if (!this.visible)
            return leftToRender;

        matrices.push();

        if (applyHiddenTransforms) {
            applyVanillaTransforms(data, matrices, new MatrixStack());
            applyTransforms(matrices);
        } else if (canRender) {
            applyTransforms(matrices);
        }

        if (renderOnly == null || this.parentType == renderOnly)
            canRender = true;

        //render!
        if (canRender)
            leftToRender = renderExtras(leftToRender, matrices, vcp, light);

        if (this instanceof CustomModelPartGroup group) {
            for (CustomModelPart child : group.children) {
                if (leftToRender <= 0)
                    break;

                //Don't render special parts.
                if (child.isSpecial())
                    continue;

                //render part
                leftToRender = child.renderExtraParts(data, leftToRender, matrices, vcp, light, canRender, applyHiddenTransforms, renderOnly);
            }
        }

        matrices.pop();

        return leftToRender;
    }

    public void draw(VertexConsumerProvider vcp) {
        if (vcp instanceof FiguraVertexConsumerProvider customVCP) customVCP.draw();
        else if (vcp instanceof VertexConsumerProvider.Immediate immediate) immediate.draw();
        else if (vcp instanceof OutlineVertexConsumerProvider outline) outline.draw();
    }

    public Identifier getTexture(AvatarData data) {
        Identifier textureId = null;

        if (textureType == TextureType.Resource) {
            textureId = MinecraftClient.getInstance().getResourceManager().containsResource(textureVanilla) ? textureVanilla : MissingSprite.getMissingSpriteId();
        } else if (textureType == TextureType.Elytra) {
            if (data.playerListEntry != null)
                textureId = data.playerListEntry.getElytraTexture();

            if (textureId == null)
                textureId = new Identifier("minecraft", "textures/entity/elytra.png");
        } else if (data.playerListEntry != null && textureType != TextureType.Custom) {
            textureId = this.textureType == TextureType.Cape ? data.playerListEntry.getCapeTexture() : data.playerListEntry.getSkinTexture();
        } else if (data.texture != null) {
            textureId = data.texture.id;
        } else if (data.lastEntity != null) {
            textureId = MinecraftClient.getInstance().getEntityRenderDispatcher().getRenderer(data.lastEntity).getTexture(data.lastEntity);
        }

        return textureId == null ? FiguraTexture.DEFAULT_ID : textureId;
    }

    public int renderCube(int leftToRender, MatrixStack matrices, VertexConsumer vertices, int light, int overlay, float u, float v, Vec3f color, float alpha) {
        Matrix4f modelMatrix = matrices.peek().getModel();
        Matrix3f normalMatrix = matrices.peek().getNormal();

        for (int i = 1; i <= this.vertexCount; i++) {
            int startIndex = (i - 1) * 8;

            //Get vertex.
            Vector4f fullVert = new Vector4f(
                    this.vertexData.getFloat(startIndex++),
                    this.vertexData.getFloat(startIndex++),
                    this.vertexData.getFloat(startIndex++),
                    1
            );

            float vertU = this.vertexData.getFloat(startIndex++);
            float vertV = this.vertexData.getFloat(startIndex++);

            Vec3f normal = new Vec3f(
                    this.vertexData.getFloat(startIndex++),
                    this.vertexData.getFloat(startIndex++),
                    this.vertexData.getFloat(startIndex)
            );

            fullVert.transform(modelMatrix);
            normal.transform(normalMatrix);

            //Push vertex.
            vertices.vertex(
                    fullVert.getX(), fullVert.getY(), fullVert.getZ(),
                    color.getX(), color.getY(), color.getZ(), alpha,
                    vertU + u, vertV + v,
                    overlay, light,
                    normal.getX(), normal.getY(), normal.getZ()
            );

            //Every 4 verts (1 face)
            if (i % 4 == 0) {
                leftToRender -= 4;

                if (leftToRender <= 0)
                    break;
            }
        }

        return leftToRender;
    }

    public int renderExtras(int leftToRender, MatrixStack matrices, VertexConsumerProvider vcp, int light) {
        //render extra parts
        synchronized (this.renderTasks) {
            for (Map.Entry<String, RenderTask> entry : this.renderTasks.entrySet()) {
                leftToRender -= entry.getValue().render(matrices, vcp, light);
                if (leftToRender <= 0) break;
            }
        }

        //render hit box
        if (canRenderHitBox) renderHitBox(matrices, vcp.getBuffer(RenderLayer.LINES));

        return leftToRender;
    }

    public void renderHitBox(MatrixStack matrices, VertexConsumer vertices) {
        Vec3f color;
        float boxSize;
        if (this.getPartType() == PartType.CUBE) {
            color = new Vec3f(1f, 0.45f, 0.72f); //0xff72b7 aka fran_pink
            boxSize = 1 / 48f;
        }
        else {
            color = new Vec3f(0.69f, 0.95f, 1f); //0xaff2ff aka ace_blue
            boxSize = 1 / 24f;
        }

        //render the box
        WorldRenderer.drawBox(matrices, vertices, -boxSize, -boxSize, -boxSize, boxSize, boxSize, boxSize, color.getX(), color.getY(), color.getZ(), 1f);
    }

    //clear all extra render tasks
    public void clearExtraRendering() {
        this.renderTasks.clear();
    }

    public int getComplexity() {
        //don't render invisible parts
        return this.visible ? this.vertexCount : 0;
    }

    public void updateModelMatrices(MatrixStack stack) {
        lastModelMatrix = stack.peek().getModel().copy();
        lastNormalMatrix = stack.peek().getNormal().copy();

        lastModelMatrixInverse = lastModelMatrix.copy();
        lastModelMatrixInverse.invert();
        lastNormalMatrixInverse = lastNormalMatrix.copy();
        lastNormalMatrixInverse.invert();
    }

    public void applyVanillaTransforms(AvatarData data, MatrixStack matrices, MatrixStack transformStack) {
        if (parentType == ParentType.Model)
            return;

        try {
            PlayerEntityModel<?> model;
            if (data.vanillaModel instanceof PlayerEntityModel)
                model = (PlayerEntityModel<?>) data.vanillaModel;
            else return;

            //mimic rotations
            if (this.isMimicMode) {
                switch (this.parentType) {
                    case Head -> this.rot = new Vec3f(model.head.pitch, model.head.yaw, model.head.roll);
                    case Torso -> this.rot = new Vec3f(model.body.pitch, model.body.yaw, model.body.roll);
                    case LeftArm -> this.rot = new Vec3f(model.leftArm.pitch, model.leftArm.yaw, model.leftArm.roll);
                    case LeftLeg -> this.rot = new Vec3f(model.leftLeg.pitch, model.leftLeg.yaw, model.leftLeg.roll);
                    case RightArm -> this.rot = new Vec3f(model.rightArm.pitch, model.rightArm.yaw, model.rightArm.roll);
                    case RightLeg -> this.rot = new Vec3f(model.rightLeg.pitch, model.rightLeg.yaw, model.rightLeg.roll);
                }

                float multiply = MathHelper.DEGREES_PER_RADIAN;
                this.rot.multiplyComponentwise(multiply, multiply, multiply);
            }
            //custom rotations
            else {
                switch (this.parentType) {
                    case Head -> {
                        model.head.rotate(matrices);
                        model.head.rotate(transformStack);
                    }
                    case Torso -> {
                        model.body.rotate(matrices);
                        model.body.rotate(transformStack);
                    }
                    case LeftArm -> {
                        model.leftArm.rotate(matrices);
                        model.leftArm.rotate(transformStack);
                    }
                    case LeftLeg -> {
                        model.leftLeg.rotate(matrices);
                        model.leftLeg.rotate(transformStack);
                    }
                    case RightArm -> {
                        model.rightArm.rotate(matrices);
                        model.rightArm.rotate(transformStack);
                    }
                    case RightLeg -> {
                        model.rightLeg.rotate(matrices);
                        model.rightLeg.rotate(transformStack);
                    }
                    case LeftItemOrigin -> data.model.originModifications.put(ItemModelAPI.VANILLA_LEFT_HAND_ID, new VanillaModelPartCustomization() {{
                        matrices.push();
                        applyTransformsAsItem(matrices);
                        applyTransformsAsItem(transformStack);
                        stackReference = matrices.peek();
                        part = CustomModelPart.this;
                        visible = true;
                        matrices.pop();
                    }});
                    case RightItemOrigin -> data.model.originModifications.put(ItemModelAPI.VANILLA_RIGHT_HAND_ID, new VanillaModelPartCustomization() {{
                        matrices.push();
                        applyTransformsAsItem(matrices);
                        applyTransformsAsItem(transformStack);
                        stackReference = matrices.peek();
                        part = CustomModelPart.this;
                        visible = true;
                        matrices.pop();
                    }});
                    case LeftElytraOrigin -> data.model.originModifications.put(ElytraModelAPI.VANILLA_LEFT_WING_ID, new VanillaModelPartCustomization() {{
                        matrices.push();
                        applyTransformsAsElytraOrParrot(matrices);
                        applyTransformsAsElytraOrParrot(transformStack);
                        stackReference = matrices.peek();
                        part = CustomModelPart.this;
                        visible = true;
                        matrices.pop();
                    }});
                    case RightElytraOrigin -> data.model.originModifications.put(ElytraModelAPI.VANILLA_RIGHT_WING_ID, new VanillaModelPartCustomization() {{
                        matrices.push();
                        applyTransformsAsElytraOrParrot(matrices);
                        applyTransformsAsElytraOrParrot(transformStack);
                        stackReference = matrices.peek();
                        part = CustomModelPart.this;
                        visible = true;
                        matrices.pop();
                    }});
                    case LeftParrotOrigin -> data.model.originModifications.put(ParrotModelAPI.VANILLA_LEFT_PARROT_ID, new VanillaModelPartCustomization() {{
                        matrices.push();
                        applyTransformsAsElytraOrParrot(matrices);
                        applyTransformsAsElytraOrParrot(transformStack);
                        stackReference = matrices.peek();
                        part = CustomModelPart.this;
                        visible = true;
                        matrices.pop();
                    }});
                    case RightParrotOrigin -> data.model.originModifications.put(ParrotModelAPI.VANILLA_RIGHT_PARROT_ID, new VanillaModelPartCustomization() {{
                        matrices.push();
                        applyTransformsAsElytraOrParrot(matrices);
                        applyTransformsAsElytraOrParrot(transformStack);
                        stackReference = matrices.peek();
                        part = CustomModelPart.this;
                        visible = true;
                        matrices.pop();
                    }});
                    case LeftSpyglass -> data.model.originModifications.put(SpyglassModelAPI.VANILLA_LEFT_SPYGLASS_ID, new VanillaModelPartCustomization() {{
                        matrices.push();
                        applyTransformsAsSpyglass(matrices);
                        applyTransformsAsSpyglass(transformStack);
                        stackReference = matrices.peek();
                        part = CustomModelPart.this;
                        visible = true;
                        matrices.pop();
                    }});
                    case RightSpyglass -> data.model.originModifications.put(SpyglassModelAPI.VANILLA_RIGHT_SPYGLASS_ID, new VanillaModelPartCustomization() {{
                        matrices.push();
                        applyTransformsAsSpyglass(matrices);
                        applyTransformsAsSpyglass(transformStack);
                        stackReference = matrices.peek();
                        part = CustomModelPart.this;
                        visible = true;
                        matrices.pop();
                    }});
                    case Camera -> {
                        Quaternion rot = MinecraftClient.getInstance().getEntityRenderDispatcher().getRotation().copy();
                        Vec3f euler = MathUtils.quaternionToEulerXYZ(rot);
                        matrices.multiply(Vec3f.POSITIVE_Z.getDegreesQuaternion(euler.getZ()));
                        matrices.multiply(Vec3f.POSITIVE_Y.getDegreesQuaternion(-euler.getY()));
                        matrices.multiply(Vec3f.POSITIVE_X.getDegreesQuaternion(-euler.getX()));
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void applyTransforms(MatrixStack stack) {
        stack.translate(this.pos.getX() / 16f, this.pos.getY() / 16f, this.pos.getZ() / 16f);

        stack.translate(-this.pivot.getX() / 16f, -this.pivot.getY() / 16f, -this.pivot.getZ() / 16f);

        if (this.isMimicMode || this.rotationType == RotationType.Vanilla)
            vanillaRotate(stack, this.rot);
        else if (this.rotationType == RotationType.BlockBench)
            rotate(stack, this.rot);

        stack.scale(this.scale.getX(), this.scale.getY(), this.scale.getZ());

        stack.translate(this.pivot.getX() / 16f, this.pivot.getY() / 16f, this.pivot.getZ() / 16f);

        if (this instanceof CustomModelPartGroup group)
            group.applyAnimationTransforms(stack);
    }

    //TODO move these to the mixins, probably.
    public void applyTransformsAsItem(MatrixStack stack) {
        stack.multiply(Vec3f.POSITIVE_X.getDegreesQuaternion(-90f));
        stack.multiply(Vec3f.POSITIVE_Y.getDegreesQuaternion(180f));
        //stack.translate(0, 0.125D, -0.625D);
        stack.translate(pivot.getX() / 16f, pivot.getZ() / 16f, pivot.getY() / 16f);
        rotate(stack, this.rot);
        stack.translate(this.pos.getX() / 16f, this.pos.getY() / 16f, this.pos.getZ() / 16f);
    }

    //TODO move these to the mixins, probably.
    public void applyTransformsAsElytraOrParrot(MatrixStack stack) {
        stack.translate(pivot.getX() / 16f, pivot.getY() / 16f, -pivot.getZ() / 16f);
        rotate(stack, this.rot);
        stack.translate(this.pos.getX() / 16f, this.pos.getY() / 16f, this.pos.getZ() / 16f);
    }

    //TODO move these to the mixins, probably. //OK GOT IT
    public void applyTransformsAsSpyglass(MatrixStack stack) {
        stack.translate(-pivot.getX() / 16f, -pivot.getY() / 16f, -pivot.getZ() / 16f);
        rotate(stack, this.rot);
        stack.translate(this.pos.getX() / 16f, this.pos.getY() / 16f, this.pos.getZ() / 16f);
    }

    //Re-builds the mesh data for a custom model part.
    public void rebuild(Vec2f newTexSize) {
        this.texSize = newTexSize;
    }

    public void rotate(MatrixStack stack, Vec3f rot) {
        stack.multiply(Vec3f.POSITIVE_Z.getDegreesQuaternion(rot.getZ()));
        stack.multiply(Vec3f.POSITIVE_Y.getDegreesQuaternion(-rot.getY()));
        stack.multiply(Vec3f.POSITIVE_X.getDegreesQuaternion(-rot.getX()));
    }

    public void vanillaRotate(MatrixStack stack, Vec3f rot) {
        stack.multiply(Vec3f.POSITIVE_Z.getDegreesQuaternion(rot.getZ()));
        stack.multiply(Vec3f.POSITIVE_Y.getDegreesQuaternion(rot.getY()));
        stack.multiply(Vec3f.POSITIVE_X.getDegreesQuaternion(rot.getX()));
    }

    public void addVertex(Vec3f vert, float u, float v, Vec3f normal, FloatList vertexData) {
        vertexData.add(vert.getX() / 16f);
        vertexData.add(vert.getY() / 16f);
        vertexData.add(vert.getZ() / 16f);
        vertexData.add(u);
        vertexData.add(v);
        vertexData.add(-normal.getX());
        vertexData.add(-normal.getY());
        vertexData.add(-normal.getZ());
    }

    public void readNbt(NbtCompound partNbt) {
        if (partNbt.contains("nm"))
            this.name = partNbt.getString("nm");
        else
            this.name = "NULL";

        if (partNbt.contains("pos")) {
            NbtList list = (NbtList) partNbt.get("pos");
            this.pos = vec3fFromNbt(list);
        }
        if (partNbt.contains("rot")) {
            NbtList list = (NbtList) partNbt.get("rot");
            this.rot = vec3fFromNbt(list);
        }
        if (partNbt.contains("scl")) {
            NbtList list = (NbtList) partNbt.get("scl");
            this.scale = vec3fFromNbt(list);
        }
        if (partNbt.contains("piv")) {
            NbtList list = (NbtList) partNbt.get("piv");
            this.pivot = vec3fFromNbt(list);
        }

        if (partNbt.contains("stype")) {
            try {
                this.shaderType = ShaderType.valueOf(partNbt.getString("stype"));
            } catch (Exception ignored) {
                this.shaderType = ShaderType.None;
            }
        }
    }

    public PartType getPartType() {
        return null;
    }

    public enum ParentType {
        Model,
        Head,
        LeftArm,
        RightArm,
        LeftLeg,
        RightLeg,
        Torso,
        WORLD(true),
        LeftItemOrigin, //Origin position of the held item in the left hand
        RightItemOrigin, //Origin position of the held item
        LeftElytraOrigin, //Left origin position of the elytra
        RightElytraOrigin, //Right origin position of the elytra
        LeftParrotOrigin, //Left origin position of the shoulder parrot
        RightParrotOrigin, //Right origin position of the shoulder parrot
        LeftElytra(true), //Left position of the elytra model
        RightElytra(true), //Right position of the elytra model
        LeftSpyglass, //Left position of the spyglass model
        RightSpyglass, //Right position of the spyglass model
        Camera, //paparazzi
        Skull(true), //A replacement for the "Head" type, but only rendered in the tab list and player head item/blocks
        Hud(true); //hud rendering

        private final boolean special;
        ParentType(boolean special) {
            this.special = special;
        }
        ParentType() {
            this.special = false;
        }
    }

    public boolean isSpecial() {
        return this.parentType.special;
    }

    public enum RotationType {
        BlockBench,
        Vanilla
    }

    public enum ShaderType {
        None(0),
        EndPortal(1),
        Glint(2);

        public final int id;
        ShaderType(int id) {
            this.id = id;
        }

        public boolean isShader(int shader) {
            return (id & shader) == id;
        }
    }

    public enum TextureType {
        Custom,
        Skin,
        Cape,
        Elytra,
        Resource
    }

    public enum UV {
        ALL,
        NORTH,
        SOUTH,
        WEST,
        EAST,
        UP,
        DOWN
    }

    public enum PartType {
        GROUP("na"),
        CUBE("cub"),
        MESH("msh");

        public final String val;
        PartType(String value) {
            this.val = value;
        }
    }

    public static class uvData {
        public Vec2f uvOffset, uvSize;

        public void setUVOffset(Vec2f uvOffset) {
            this.uvOffset = uvOffset;
        }

        public void setUVSize(Vec2f uvSize) {
            this.uvSize = uvSize;
        }
    }

    public void applyUVMods(Vec2f v) {
        rebuild(v);
    }

    //---------MODEL PART TYPES---------

    public static final Map<String, Supplier<CustomModelPart>> MODEL_PART_TYPES =
            new ImmutableMap.Builder<String, Supplier<CustomModelPart>>()
                    .put("na", CustomModelPartGroup::new)
                    .put("cub", CustomModelPartCuboid::new)
                    .put("msh", CustomModelPartMesh::new)
                    .build();

    /**
     * Gets a CustomModelPart from NBT, automatically reading the type from that NBT.
     */
    public static CustomModelPart fromNbt(NbtCompound nbt, CustomModel model) {
        NbtElement pt = nbt.get("pt");
        String partType = pt == null ? "na" : pt.asString();

        if (!MODEL_PART_TYPES.containsKey(partType))
            return null;

        Supplier<CustomModelPart> sup = MODEL_PART_TYPES.get(partType);
        CustomModelPart part = sup.get();

        part.model = model;
        part.readNbt(nbt);
        return part;
    }

    public static Vec3f vec3fFromNbt(@Nullable NbtList nbt) {
        if (nbt == null || nbt.getHeldType() != NbtType.FLOAT)
            return new Vec3f(0f, 0f, 0f);
        return new Vec3f(nbt.getFloat(0), nbt.getFloat(1), nbt.getFloat(2));
    }

    public static Vector4f v4fFromNbtList(NbtList list) {
        return new Vector4f(list.getFloat(0), list.getFloat(1), list.getFloat(2), list.getFloat(3));
    }

    public static Vec2f v2fFromNbtList(NbtList list) {
        return new Vec2f(list.getFloat(0), list.getFloat(1));
    }
}
