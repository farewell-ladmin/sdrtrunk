package io.github.dsheirer.module.decode.moto;

public enum BandplanType
{
    EIGHT_HUNDRED_REBANTED("800 MHz Rebandded"),
    EIGHT_HUNDRED_DOMESTIC("800 MHz Domestic"),
    EIGHT_HUNDRED_DOMESTIC_SPLINTER("800 MHz Splinter"),
    EIGHT_HUNDRED_INTERNATIONAL("800 MHz International"),
    EIGHT_HUNDRED_INTERNATIONAL_SPLINTER("800 MHz Intl Splinter"),
    NINE_HUNDRED("900 MHz"),
    OBT("Other Band Trunking");

    private final String mLabel;

    BandplanType(String label)
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
