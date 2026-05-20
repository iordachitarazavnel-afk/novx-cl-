package com.xenon.gui;

import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ChatScreen;

public class XenonChatScreen extends ChatScreen {

    public XenonChatScreen(String originalChatText) {
        super(originalChatText, false);
    }

    @Override
    public boolean mouseClicked(Click click, boolean bl) {
        double mouseX = click.x();
        double mouseY = click.y();
        int button = click.button();
        if (button == 0) {
            if (com.xenon.module.modules.client.SpotifyHud.handleClick(mouseX, mouseY)) {
                return true;
            }
            if (HudEditor.INSTANCE.onMouseClick(mouseX, mouseY, button)) {
                return true;
            }
        }
        return super.mouseClicked(click, bl);
    }

    @Override
    public boolean mouseDragged(Click click, double deltaX, double deltaY) {
        if (HudEditor.INSTANCE.isDragging()) {
            HudEditor.INSTANCE.onMouseDrag(click.x(), click.y());
            return true;
        }
        return super.mouseDragged(click, deltaX, deltaY);
    }

    @Override
    public boolean mouseReleased(Click click) {
        HudEditor.INSTANCE.onMouseRelease();
        return super.mouseReleased(click);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);
        HudEditor.INSTANCE.render(context, mouseX, mouseY);
    }
}
