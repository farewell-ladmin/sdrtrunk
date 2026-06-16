// Reference: OP25 tk_smartnet.py lines 806-1764 (boatbod/op25)
package io.github.dsheirer.module.decode.moto.message;

import io.github.dsheirer.module.decode.moto.Bandplan;
import io.github.dsheirer.module.decode.moto.message.OswQueue.OswEntry;

import java.util.ArrayList;
import java.util.List;

public class MotorolaTypeIIMessageFactory
{
    private static final int CMD_IDLE = 0x2F8;
    private static final int CMD_ANALOG_GRANT = 0x308;
    private static final int CMD_GROUP_BUSY = 0x300;
    private static final int CMD_EMERGENCY_BUSY = 0x303;
    private static final int CMD_SEND_AFFILIATION = 0x32A;
    private static final int CMD_SYSTEM_ID = 0x32B;
    private static final int CMD_ROAMING = 0x32C;
    private static final int CMD_INTERCONNECT_REJECT = 0x324;
    private static final int CMD_DIGITAL_GRANT = 0x321;
    private static final int CMD_NETWORK_STATUS = 0x3BF;
    private static final int CMD_SYSTEM_STATUS = 0x3C0;
    private static final int CMD_BSI = 0x3A0;
    private static final int CMD_SYSTEM_INFO = 0x320;

    private static final int CMD2_AFFILIATION = 0x310;
    private static final int CMD2_MESSAGE = 0x311;
    private static final int CMD2_PRIVATE_CALL_RING_ENC = 0x315;
    private static final int CMD2_PRIVATE_CALL_RING_CLR = 0x317;
    private static final int CMD2_PRIVATE_CALL_RING_ACK = 0x318;
    private static final int CMD2_CALL_ALERT = 0x319;
    private static final int CMD2_CALL_ALERT_ACK = 0x31A;
    private static final int CMD2_OMNILINK_TRESPASS = 0x31B;
    private static final int CMD2_DATE_TIME = 0x322;
    private static final int CMD2_EMERGENCY_PTT = 0x32E;
    private static final int CMD2_PATCH = 0x340;
    private static final int CMD2_DYNAMIC_REGROUP = 0x30A;
    private static final int CMD2_SYSTEM_ID_CC = 0x30B;
    private static final int CMD2_STATUS_ACK = 0x30D;
    private static final int CMD2_PRIVATE_BUSY = 0x302;

    private static final int ADDR_CC_BROADCAST_MASK = 0xFF00;
    private static final int ADDR_CC_BROADCAST_VALUE = 0x1F00;
    private static final int ADDR_ADJACENT_MASK = 0x6000;
    private static final int ADDR_ALT_CC_MASK = 0x2800;

    public static List<MotorolaTypeIIMessage> process(OswQueue queue, Bandplan bandplan)
    {
        List<MotorolaTypeIIMessage> messages = new ArrayList<>();

        if(!queue.isFull() || queue.hasReset())
        {
            if(queue.hasReset())
            {
                queue.clearToReset();
            }
            return messages;
        }

        OswEntry osw2 = queue.getNewest();
        OswEntry osw1 = queue.getMiddle();
        OswEntry osw0 = queue.getOldest();

        if(osw2.command == CMD_IDLE && !osw2.isGroup)
        {
            if(osw1.command == CMD_IDLE && !osw1.isGroup)
            {
                queue.removeNewest();
                queue.removeNewest();
            }
            else
            {
                queue.removeNewest();
            }
            return messages;
        }

        if(osw2.command == CMD_ANALOG_GRANT && osw1.command == CMD_ANALOG_GRANT &&
           osw0.command == CMD2_SYSTEM_ID_CC && (osw0.address & ADDR_ADJACENT_MASK) == ADDR_ADJACENT_MASK)
        {
            messages.add(createMessage(MotorolaTypeIIMessageType.ADJACENT_SITE, osw2, true));
            queue.removeNewest();
            queue.removeNewest();
            queue.removeNewest();
            return messages;
        }

        if(osw2.command == CMD_SYSTEM_INFO)
        {
            messages.add(createMessage(MotorolaTypeIIMessageType.SYSTEM_INFO, osw2, true));
            queue.removeNewest();
            queue.removeNewest();
            queue.removeNewest();
            return messages;
        }

        if(osw2.command == CMD_ANALOG_GRANT)
        {
            return processAnalogGrant(queue, osw2, osw1);
        }

        if(osw2.command == CMD_DIGITAL_GRANT)
        {
            return processDigitalGrant(queue, osw2, osw1);
        }

        return processOneOsw(queue, osw2);
    }

    private static List<MotorolaTypeIIMessage> processAnalogGrant(OswQueue queue, OswEntry osw2, OswEntry osw1)
    {
        List<MotorolaTypeIIMessage> messages = new ArrayList<>();

        if(osw1.command == CMD_IDLE && !osw1.isGroup)
        {
            queue.removeNewest();
            queue.removeNewest();
            queue.add(osw2);
            return messages;
        }

        if(osw1.isChannel() && osw1.isGroup)
        {
            messages.add(createGrantMessage(MotorolaTypeIIMessageType.ANALOG_GROUP_GRANT, osw2, osw1, true));
            queue.removeNewest();
            queue.removeNewest();
            return messages;
        }

        if(osw1.isChannel() && !osw1.isGroup)
        {
            messages.add(createGrantMessage(MotorolaTypeIIMessageType.ANALOG_PRIVATE_CALL, osw2, osw1, false));
            queue.removeNewest();
            queue.removeNewest();
            return messages;
        }

        MotorolaTypeIIMessageType subType = mapAnalogSubCommand(osw1.command);

        if(subType != null)
        {
            messages.add(createMessage(subType, osw2, osw2.isGroup));
            queue.removeNewest();
            queue.removeNewest();
            return messages;
        }

        return messages;
    }

    private static List<MotorolaTypeIIMessage> processDigitalGrant(OswQueue queue, OswEntry osw2, OswEntry osw1)
    {
        List<MotorolaTypeIIMessage> messages = new ArrayList<>();

        if(osw1.command == CMD_IDLE && !osw1.isGroup)
        {
            queue.removeNewest();
            queue.removeNewest();
            queue.add(osw2);
            return messages;
        }

        if(osw1.isChannel() && osw1.isGroup)
        {
            messages.add(createGrantMessage(MotorolaTypeIIMessageType.DIGITAL_GROUP_GRANT, osw2, osw1, true));
            queue.removeNewest();
            queue.removeNewest();
            return messages;
        }

        if(osw1.isChannel() && !osw1.isGroup)
        {
            messages.add(createGrantMessage(MotorolaTypeIIMessageType.DIGITAL_PRIVATE_CALL, osw2, osw1, false));
            queue.removeNewest();
            queue.removeNewest();
            return messages;
        }

        return messages;
    }

    private static List<MotorolaTypeIIMessage> processOneOsw(OswQueue queue, OswEntry osw2)
    {
        List<MotorolaTypeIIMessage> messages = new ArrayList<>();
        MotorolaTypeIIMessageType type = null;

        if(osw2.isChannel() && osw2.isGroup)
        {
            type = MotorolaTypeIIMessageType.GROUP_UPDATE;
        }
        else if((osw2.address & ADDR_CC_BROADCAST_MASK) == ADDR_CC_BROADCAST_VALUE && !osw2.isGroup)
        {
            type = MotorolaTypeIIMessageType.CC_BROADCAST;
        }
        else if(osw2.command == CMD_SYSTEM_ID && !osw2.isGroup)
        {
            type = MotorolaTypeIIMessageType.SYSTEM_ID;
        }
        else if(osw2.command >= 0x360 && osw2.command <= 0x39F && !osw2.isGroup)
        {
            type = MotorolaTypeIIMessageType.AMSS;
        }
        else if(osw2.command == CMD_NETWORK_STATUS && !osw2.isGroup)
        {
            type = MotorolaTypeIIMessageType.NETWORK_STATUS;
        }
        else if(osw2.command == CMD_SYSTEM_STATUS && !osw2.isGroup)
        {
            type = MotorolaTypeIIMessageType.SYSTEM_STATUS;
        }
        else if(osw2.command == CMD_GROUP_BUSY && osw2.isGroup)
        {
            type = MotorolaTypeIIMessageType.GROUP_BUSY;
        }
        else if(osw2.command == CMD_EMERGENCY_BUSY && osw2.isGroup)
        {
            type = MotorolaTypeIIMessageType.EMERGENCY_BUSY;
        }
        else if(osw2.command == CMD_SEND_AFFILIATION && !osw2.isGroup)
        {
            type = MotorolaTypeIIMessageType.SEND_AFFILIATION;
        }
        else if(osw2.command == CMD_ROAMING && !osw2.isGroup)
        {
            type = MotorolaTypeIIMessageType.ROAMING;
        }
        else if(osw2.command == CMD_INTERCONNECT_REJECT && !osw2.isGroup)
        {
            type = MotorolaTypeIIMessageType.INTERCONNECT_REJECT;
        }
        else if(osw2.command == CMD_BSI && !osw2.isGroup)
        {
            type = MotorolaTypeIIMessageType.BSI_DIAGNOSTIC;
        }

        if(type != null)
        {
            messages.add(createMessage(type, osw2, osw2.isGroup));
            queue.removeNewest();
        }

        return messages;
    }

    private static MotorolaTypeIIMessageType mapAnalogSubCommand(int command)
    {
        switch(command)
        {
            case CMD2_AFFILIATION:
                return MotorolaTypeIIMessageType.AFFILIATION;
            case CMD2_MESSAGE:
                return MotorolaTypeIIMessageType.MESSAGE;
            case CMD2_PRIVATE_CALL_RING_ENC:
            case CMD2_PRIVATE_CALL_RING_CLR:
            case CMD2_PRIVATE_CALL_RING_ACK:
                return MotorolaTypeIIMessageType.PRIVATE_CALL_RING;
            case CMD2_CALL_ALERT:
                return MotorolaTypeIIMessageType.CALL_ALERT;
            case CMD2_CALL_ALERT_ACK:
                return MotorolaTypeIIMessageType.CALL_ALERT_ACK;
            case CMD2_OMNILINK_TRESPASS:
                return MotorolaTypeIIMessageType.OMNILINK_TRESPASS;
            case CMD2_DATE_TIME:
                return MotorolaTypeIIMessageType.DATE_TIME;
            case CMD2_EMERGENCY_PTT:
                return MotorolaTypeIIMessageType.EMERGENCY_PTT;
            case CMD2_PATCH:
                return MotorolaTypeIIMessageType.PATCH;
            case CMD2_DYNAMIC_REGROUP:
                return MotorolaTypeIIMessageType.DYNAMIC_REGROUP;
            case CMD2_SYSTEM_ID_CC:
                return MotorolaTypeIIMessageType.SYSTEM_ID_CC;
            case CMD2_STATUS_ACK:
                return MotorolaTypeIIMessageType.STATUS_ACK;
            case CMD2_PRIVATE_BUSY:
                return MotorolaTypeIIMessageType.PRIVATE_CALL_BUSY;
            case CMD_GROUP_BUSY:
                return MotorolaTypeIIMessageType.GROUP_BUSY_QUEUED;
            case CMD_EMERGENCY_BUSY:
                return MotorolaTypeIIMessageType.EMERGENCY_BUSY_QUEUED;
            default:
                return null;
        }
    }

    private static MotorolaTypeIIMessage createMessage(MotorolaTypeIIMessageType type, OswEntry entry, boolean isGroup)
    {
        return new MotorolaTypeIIMessage(entry.address, isGroup, entry.command, entry.isReset ? null :
                new io.github.dsheirer.bits.CorrectedBinaryMessage(0), true, type);
    }

    private static MotorolaTypeIIMessage createGrantMessage(MotorolaTypeIIMessageType type, OswEntry talkgroupEntry,
                                                             OswEntry channelEntry, boolean isGroup)
    {
        return new MotorolaTypeIIMessage(talkgroupEntry.address, isGroup, talkgroupEntry.command,
                channelEntry.address, talkgroupEntry.isReset ? null :
                new io.github.dsheirer.bits.CorrectedBinaryMessage(0), true, type);
    }
}
