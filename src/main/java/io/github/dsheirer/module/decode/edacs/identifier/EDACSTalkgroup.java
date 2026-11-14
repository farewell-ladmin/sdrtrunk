package io.github.dsheirer.module.decode.edacs.identifier;

import io.github.dsheirer.identifier.Role;
import io.github.dsheirer.identifier.talkgroup.TalkgroupIdentifier;
import io.github.dsheirer.protocol.Protocol;

/**
 * EDACS talkgroup identifier. Talkgroup values are 16-bit (0-65535)
 * with 0 used for the system all-call.
 */
public class EDACSTalkgroup extends TalkgroupIdentifier
{
    public static final int ALLCALL_TALKGROUP = 0;

    public EDACSTalkgroup(int value)
    {
        super(value, Role.TO);
    }

    public EDACSTalkgroup(int value, Role role)
    {
        super(value, role);
    }

    @Override
    public Protocol getProtocol()
    {
        return Protocol.EDACS;
    }
}
