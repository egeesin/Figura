package net.blancworks.figura.gui;

import com.mojang.blaze3d.systems.RenderSystem;
import net.blancworks.figura.FiguraMod;
import net.blancworks.figura.PlayerData;
import net.blancworks.figura.PlayerDataManager;
import net.blancworks.figura.access.GameRendererAccess;
import net.blancworks.figura.lua.api.nameplate.NamePlateAPI;
import net.blancworks.figura.trust.PlayerTrustManager;
import net.blancworks.figura.trust.TrustContainer;
import net.blancworks.figura.utils.MathUtils;
import net.blancworks.figura.utils.TextUtils;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawableHelper;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;
import net.minecraft.text.LiteralText;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.text.TranslatableText;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3f;
import net.minecraft.util.math.Vector4f;

import java.util.List;

public class PlayerPopup extends DrawableHelper {

    private static final Identifier POPUP_TEXTURE = new Identifier("figura", "textures/gui/popup.png");
    private static int index = 0;
    private static boolean enabled = false;
    public static Entity entity;

    private static final List<Text> buttons = List.of(
            new TranslatableText("gui.figura.playerpopup.cancel"),
            new TranslatableText("gui.figura.playerpopup.reload"),
            new TranslatableText("gui.figura.playerpopup.increasetrust"),
            new TranslatableText("gui.figura.playerpopup.decreasetrust")
    );

    public static boolean render(MatrixStack matrices) {
        VertexConsumerProvider vcp = FiguraMod.vertexConsumerProvider;
        PlayerData data = entity == null ? null : PlayerDataManager.getDataForPlayer(entity.getUuid());
        if (data == null || vcp == null) return false;

        MinecraftClient client = MinecraftClient.getInstance();
        TextRenderer textRenderer = client.textRenderer;
        RenderSystem.enableDepthTest();
        matrices.push();

        //world to screen space
        Vec3f worldPos = new Vec3f(entity.getLerpedPos(client.getTickDelta()));
        worldPos.add(0f, entity.getHeight() + 0.1f, 0f);

        Vector4f vec = MathUtils.worldToScreenSpace(worldPos);
        if (vec.getZ() < 1) return false;

        float w = client.getWindow().getScaledWidth();
        float h = client.getWindow().getScaledHeight();
        float s = client.getWindow().getHeight() * 0.035f / vec.getW() * (float) (1f / client.getWindow().getScaleFactor());

        ((GameRendererAccess) client.gameRenderer).figura$bobViewWhenHurt(matrices, vec.getW());
        if (client.options.bobView) ((GameRendererAccess) client.gameRenderer).figura$bobView(matrices, vec.getW());

        matrices.translate((vec.getX() + 1f) / 2f * w, (vec.getY() + 1f) / 2f * h, -100f);
        matrices.scale(s / 2f, s / 2f, 1f);

        //title
        Text title = buttons.get(index);
        TextUtils.renderOutlineText(textRenderer, title, -textRenderer.getWidth(title) / 2f, -40, 0xFFFFFF, 0x202020, matrices);

        //background
        RenderSystem.setShaderTexture(0, POPUP_TEXTURE);
        drawTexture(matrices, -36, -30, 72, 30, 0f, 0f, 72, 30, 72, 66);

        //icons
        matrices.translate(0f, 0f, -2f);
        for (int i = 0; i < 4; i++) {
            drawTexture(matrices, -36 + (18 * i), -23, 18, 18, 18f * i, i == index ? 48f : 30f, 18, 18, 72, 66);
        }

        //playername
        MutableText name = data.playerName.shallowCopy();
        Text badges = NamePlateAPI.getBadges(data);
        if (badges != null) name.append(badges);

        Text trust = new TranslatableText("gui.figura." + data.getTrustContainer().parentID.getPath());

        matrices.scale(0.5f, 0.5f, 0.5f);
        matrices.translate(0f, 0f, -1f);
        textRenderer.draw(matrices, name, -66, -55, 0);
        textRenderer.draw(matrices, trust, -textRenderer.getWidth(trust) + 66, -55, 0);

        //return
        matrices.pop();
        data.hasPopup = true;
        enabled = true;
        return true;
    }

    public static boolean mouseScrolled(double d) {
        if (enabled) index = (int) (index - d + 4) % 4;
        return enabled;
    }

    public static void hotbarKeyPressed(int i) {
        if (enabled) index = i % 4;
    }

    public static void execute() {
        PlayerData data = entity == null ? null : PlayerDataManager.getDataForPlayer(entity.getUuid());

        if (data != null) {
            data.hasPopup = false;
            MutableText playerName = new LiteralText("").append(data.playerName);
            Text badges = NamePlateAPI.getBadges(data);
            if (badges != null) playerName.append(badges);

            switch (index) {
                case 1 -> {
                    if (data.hasAvatar() && data.isAvatarLoaded()) {
                        PlayerDataManager.clearPlayer(entity.getUuid());
                        FiguraMod.sendToast(playerName, "gui.figura.toast.avatar.reload.title");
                    }
                }
                case 2 -> {
                    TrustContainer tc = data.getTrustContainer();
                    if (PlayerTrustManager.increaseTrust(tc))
                        FiguraMod.sendToast(playerName, new TranslatableText("gui.figura.toast.avatar.trust.title").append(new TranslatableText("gui.figura." + tc.parentID.getPath())));
                }
                case 3 -> {
                    TrustContainer tc = data.getTrustContainer();
                    if (PlayerTrustManager.decreaseTrust(tc))
                        FiguraMod.sendToast(playerName, new TranslatableText("gui.figura.toast.avatar.trust.title").append(new TranslatableText("gui.figura." + tc.parentID.getPath())));
                }
            }
        }

        index = 0;
        enabled = false;
        entity = null;
    }
}
