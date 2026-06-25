package io.github.dsheirer.module.decode.edacs.identifier;

import io.github.dsheirer.identifier.Role;
import io.github.dsheirer.identifier.radio.RadioIdentifier;
import io.github.dsheirer.protocol.Protocol;

/**
 * EDACS radio identifier. Source / target values are 20-bit (0-1048575).
 */
public class EDACSRadio extends RadioIdentifier
{
    public EDACSRadio(int value, Role role)
    {
        super(value, role);
    }

    @Override
    public Protocol getProtocol()
    {
        return Protocol.EDACS;
    }
}
