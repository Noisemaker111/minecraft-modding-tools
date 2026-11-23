import java.util.regex.*;

/** Toasts, whose visibility the game now asks for instead of taking from render. */
final class ToastMigration {

    /**
     * 1.21.2 split a toast's Visibility render(GuiGraphics, ToastComponent, long) into update(ToastManager, long),
     * getWantedVisibility() and void render(GuiGraphics, Font, long); ToastComponent became ToastManager, with the same
     * methods. Every toast in the file keeps its drawing code unchanged as mmtRender: update keeps the manager, render
     * draws through mmtRender and keeps the visibility it answers, and getWantedVisibility hands that to the game, which
     * asks after each update. A toast that already has getWantedVisibility is left alone.
     */
    static String toastVisibility(String text) {
        Matcher old = Pattern.compile(
            "(?:@Override\\s+)?public\\s+((?:[\\w.]*\\.)?Visibility)\\s+render\\s*\\(\\s*(?:final\\s+)?GuiGraphics\\s+(\\w+)\\s*,\\s*" +
            "(?:final\\s+)?(?:[\\w.]*\\.)?(?:ToastComponent|ToastManager)\\s+(\\w+)\\s*,\\s*(?:final\\s+)?long\\s+(\\w+)\\s*\\)\\s*\\{"
        ).matcher(text);
        StringBuilder out = new StringBuilder();
        int done = 0;
        while (old.find()) {
            int close = JavaText.closingBrace(text, old.end() - 1);
            int classOpen = text.lastIndexOf("class ", old.start());
            if (close < 0 || classOpen < 0 || text.substring(classOpen, old.start()).contains("getWantedVisibility")) continue;
            String manager = "net.minecraft.client.gui.components.toasts.ToastManager";
            String visibility = "net.minecraft.client.gui.components.toasts.Toast.Visibility";
            out.append(text, done, old.start())
                .append("private ").append(visibility).append(" mmtRender(GuiGraphics ").append(old.group(2)).append(", ").append(manager)
                .append(' ').append(old.group(3)).append(", long ").append(old.group(4)).append(") {")
                .append(text, old.end(), close + 1)
                .append("\n\n    private ").append(manager).append(" mmtToastManager;\n")
                .append("    private ").append(visibility).append(" mmtVisibility = ").append(visibility).append(".SHOW;\n\n")
                .append("    @Override\n    public void update(").append(manager).append(" manager, long time) {\n")
                .append("        this.mmtToastManager = manager;\n    }\n\n")
                .append("    @Override\n    public ").append(visibility).append(" getWantedVisibility() {\n")
                .append("        return this.mmtVisibility;\n    }\n\n")
                .append("    @Override\n    public void render(GuiGraphics graphics, net.minecraft.client.gui.Font font, long time) {\n")
                .append("        this.mmtVisibility = mmtRender(graphics, this.mmtToastManager, time);\n    }");
            done = close + 1;
        }
        return done == 0 ? text : out.append(text.substring(done)).toString();
    }
}
