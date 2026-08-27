package et.elisa.signaling;

public record Rule(String name, int priority, Matcher when, Action then) {
}
