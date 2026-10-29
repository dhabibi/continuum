package ml.docilealligator.infinityforreddit.events;

public class ChangeGifAutoplayEvent {
    public final boolean enabled;

    public ChangeGifAutoplayEvent(boolean enabled) {
        this.enabled = enabled;
    }
}
