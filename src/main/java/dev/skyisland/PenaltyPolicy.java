package dev.skyisland;

final class PenaltyPolicy {
    private static final long DAY = 86_400_000L;

    static boolean temporaryBan(String signal, long previousIncident, long now) {
        if (signal.equals("tnt") || signal.equals("spawn-egg")) return true;
        return previousIncident > 0 && now >= previousIncident && now - previousIncident < DAY;
    }
}
