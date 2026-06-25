package io.github.dsheirer.module.decode.moto.identifier;

import io.github.dsheirer.identifier.Role;
import io.github.dsheirer.identifier.radio.RadioIdentifier;
import io.github.dsheirer.protocol.Protocol;

public class MotorolaTypeIIRadioIdentifier extends RadioIdentifier
{
    public MotorolaTypeIIRadioIdentifier(int value, Role role)
    {
        super(value, role);
    }

    @Override
    public Protocol getProtocol()
    {
        return Protocol.MOTOROLA_TYPE_II;
    }
}
