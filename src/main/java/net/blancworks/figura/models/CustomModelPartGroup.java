package net.blancworks.figura.models;

import net.blancworks.figura.models.animations.KeyFrame;
import net.fabricmc.fabric.api.util.NbtType;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.util.math.Vec2f;
import net.minecraft.util.math.Vec3f;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

public class CustomModelPartGroup extends CustomModelPart {

    public ArrayList<CustomModelPart> children = new ArrayList<>();
    public Vec3f animRot = Vec3f.ZERO.copy();
    public Vec3f animPos = Vec3f.ZERO.copy();
    public Vec3f animScale = new Vec3f(1f, 1f, 1f);

    @Override
    public void applyTransforms(MatrixStack stack) {
        //pos
        stack.translate(animPos.getX() / 16f, -animPos.getY() / 16f, animPos.getZ() / 16f);

        //part transforms
        super.applyTransforms(stack);

        //rotation and scale
        stack.translate(-this.pivot.getX() / 16f, -this.pivot.getY() / 16f, -this.pivot.getZ() / 16f);

        stack.multiply(Vec3f.POSITIVE_Z.getDegreesQuaternion(animRot.getZ()));
        stack.multiply(Vec3f.POSITIVE_Y.getDegreesQuaternion(animRot.getY()));
        stack.multiply(Vec3f.POSITIVE_X.getDegreesQuaternion(animRot.getX()));

        stack.scale(animScale.getX(), animScale.getY(), animScale.getZ());

        stack.translate(this.pivot.getX() / 16f, this.pivot.getY() / 16f, this.pivot.getZ() / 16f);
    }

    @Override
    public void clearExtraRendering() {
        super.clearExtraRendering();

        this.children.forEach(CustomModelPart::clearExtraRendering);
    }

    @Override
    public int getComplexity() {
        if (!this.visible) return 0;

        int complexity = 0;

        //iterate over children
        for (CustomModelPart child : this.children)
            complexity += child.getComplexity();

        return complexity;
    }

    @Override
    public void rebuild(Vec2f newTexSize) {
        super.rebuild(newTexSize);

        this.children.forEach(child -> child.rebuild(texSize));
    }

    @Override
    public void readNbt(NbtCompound partNbt) {
        super.readNbt(partNbt);

        if (partNbt.contains("ptype")) {
            try {
                this.parentType = ParentType.valueOf(partNbt.getString("ptype"));
            } catch (Exception ignored) {
                this.parentType = ParentType.Model;
            }
        }

        if (partNbt.contains("mmc")) {
            this.isMimicMode = partNbt.getByte("mmc") == 1;
        }

        if (partNbt.contains("anims")) {
            NbtList animList = partNbt.getList("anims", NbtElement.COMPOUND_TYPE);
            if (animList != null) {
                for (NbtElement nbtElement : animList) {
                    NbtCompound animTag = (NbtCompound) nbtElement;

                    String animationID = animTag.getString("id");

                    TreeMap<Float, KeyFrame> posKeys = new TreeMap<>();
                    TreeMap<Float, KeyFrame> rotKeys = new TreeMap<>();
                    TreeMap<Float, KeyFrame> scaleKeys = new TreeMap<>();

                    NbtList keyFrameList = animTag.getList("keyf", NbtElement.COMPOUND_TYPE);
                    if (keyFrameList != null) {
                        for (NbtElement nbtElement2 : keyFrameList) {
                            NbtCompound keyFrameTag = (NbtCompound) nbtElement2;

                            KeyFrame frame = KeyFrame.fromNbt(keyFrameTag);
                            switch (frame.type) {
                                case POSITION -> posKeys.put(frame.time, frame);
                                case ROTATION -> rotKeys.put(frame.time, frame);
                                case SCALE -> scaleKeys.put(frame.time, frame);
                            }
                        }
                    }

                    this.model.animations.get(animationID).keyFrames.put(this, List.of(posKeys, rotKeys, scaleKeys));
                }
            }
        }

        if (partNbt.contains("chld")) {
            NbtList childrenNbt = (NbtList) partNbt.get("chld");
            if (childrenNbt == null || childrenNbt.getHeldType() != NbtType.COMPOUND)
                return;

            for (NbtElement child : childrenNbt) {
                NbtCompound childNbt = (NbtCompound) child;
                CustomModelPart part = fromNbt(childNbt, this.model);
                if (part != null) this.children.add(part);
            }
        }
    }

    @Override
    public PartType getPartType() {
        return PartType.GROUP;
    }

    @Override
    public void applyUVMods(Vec2f v) {
        super.applyUVMods(v);

        children.forEach(child -> {
            child.UVCustomizations = UVCustomizations;
            child.applyUVMods(v);
        });
    }
}