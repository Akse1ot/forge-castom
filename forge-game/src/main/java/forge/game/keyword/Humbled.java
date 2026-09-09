package forge.game.keyword;

public class Humbled extends KeywordWithAmount {

    boolean withoutAmount = false;

    public String getTitle() {
        if (withoutAmount) {
            return getKeyword().toString();
        }
        return super.getTitle();
    }

    @Override
    protected void parse(String details) {
        if ("".equals(details)) {
            withoutAmount = true;
        } else {
            super.parse(details);
        }
    }

    @Override
    protected String formatReminderText(String reminderText) {
        if (withoutAmount) {
            return "As long as this permanent has a defeat counter on it, it's an enchantment and loses all other card types. At the beginning of your upkeep, remove a defeat counter from it.";
        } else {
            return super.formatReminderText(reminderText);
        }
    }
}