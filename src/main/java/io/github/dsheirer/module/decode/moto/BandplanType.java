package io.github.dsheirer.module.decode.moto;

import com.fasterxml.jackson.annotation.JsonCreator;

public enum BandplanType
{
    EIGHT_HUNDRED_REBANDED("800 MHz Rebanded"),
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

    @JsonCreator
    public static BandplanType fromValue(String value)
    {
        if(value != null)
        {
            if(value.equals("EIGHT_HUNDRED_REBANTED"))
            {
                return EIGHT_HUNDRED_REBANDED;
            }

            for(BandplanType type : values())
            {
                if(type.name().equals(value) || type.getLabel().equals(value))
                {
                    return type;
                }
            }
        }

        return EIGHT_HUNDRED_REBANDED;
    }

    @Override
    public String toString()
    {
        return mLabel;
    }
}
