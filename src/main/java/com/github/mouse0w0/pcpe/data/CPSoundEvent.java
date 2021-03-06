package com.github.mouse0w0.pcpe.data;

public class CPSoundEvent {
    private String id;
    private String translationKey;

    public CPSoundEvent(String id, String translationKey) {
        this.id = id;
        this.translationKey = translationKey;
    }

    public String getId() {
        return id;
    }

    public String getTranslationKey() {
        return translationKey;
    }
}
