package io.github.dsheirer.module.decode.moto;

public enum VoiceMode
{
    ANALOG("Analog"),
    DIGITAL("Digital"),
    AUTO("Auto");

    private final String mLabel;

    VoiceMode(String label)
    {
        mLabel = label;
    }

    public String getLabel()
    {
        return mLabel;
    }

    @Override
    public String toString()
    {
        return mLabel;
    }
}
