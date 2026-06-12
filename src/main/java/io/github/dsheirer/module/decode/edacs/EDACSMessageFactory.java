package io.github.dsheirer.module.decode.edacs;

import io.github.dsheirer.bits.CorrectedBinaryMessage;
import io.github.dsheirer.module.decode.edacs.message.EDACSMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * EDACS Extended Addressing (EA) message parser.
 *
 * Parses 28-bit EDACS data payloads from BCH-corrected message words.
 * Based on dsd-fme's EDACS EA message parsing.
 */
public class EDACSMessageFactory
{
    private static final Logger mLog = LoggerFactory.getLogger(EDACSMessageFactory.class);

    public static EDACSMessage create(CorrectedBinaryMessage data, long timestamp)
    {
        int msg_1 = getInt(data, 0, 28);

        //Apply ESK mask (MBTA uses 0xA0)
        int eskMask = 0xA0;
        int beforeMsk = msg_1;
        msg_1 = msg_1 ^ (eskMask << 20);

        int mt1 = (msg_1 >> 23) & 0x1F;
        int mt2 = (msg_1 >> 19) & 0x0F;

        //Log first message to verify ESK is applied
        mLog.info("EDACS msg raw=" + Integer.toHexString(beforeMsk) + " mt1=" + mt1 + " (" + ((beforeMsk >> 23) & 0x1F) + " before XOR)");

        EDACSMessageType type = EDACSMessageType.UNKNOWN;
        StringBuilder details = new StringBuilder();

        if(mt1 == 0x1F)
        {
            switch(mt2)
            {
                case 0x0:
                    type = EDACSMessageType.TEST_CALL;
                    details.append("Initiate Test Call");
                    break;
                case 0x1:
                    type = EDACSMessageType.ADJACENT_SITE;
                    details.append("Adjacent Site");
                    break;
                case 0x4:
                    type = EDACSMessageType.STATUS;
                    details.append("Status/Message");
                    break;
                case 0x7:
                    type = EDACSMessageType.STATUS;
                    details.append("Unit Enable/Disable");
                    break;
                case 0x8:
                    type = EDACSMessageType.SYSTEM_INFO;
                    int ccLcn = (msg_1 >> 13) & 0x1F;
                    int systemId = msg_1 & 0xFFFF;
                    details.append("CC LCN:").append(ccLcn).append(" SYS:").append(Integer.toHexString(systemId));
                    break;
                case 0xA:
                    type = EDACSMessageType.SYSTEM_INFO;
                    details.append("Site ID");
                    break;
                case 0xB:
                    type = EDACSMessageType.DYNAMIC_REGROUP;
                    details.append("Regroup Plan");
                    break;
                case 0xC:
                    type = EDACSMessageType.DYNAMIC_REGROUP;
                    details.append("Dynamic Regroup");
                    break;
                default:
                    details.append("MT1:1F MT2:").append(mt2);
                    break;
            }
        }
        else
        {
            switch(mt1)
            {
                case 0x01:
                    type = EDACSMessageType.GROUP_CALL;
                    details.append("TDMA Group Call");
                    break;
                case 0x02:
                    type = EDACSMessageType.STATUS;
                    details.append("Data Group Call");
                    break;
                case 0x03:
                case 0x06:
                {
                    type = EDACSMessageType.GROUP_CALL;
                    int lcn = (msg_1 >> 17) & 0x1F;
                    int group = msg_1 & 0xFFFF;
                    boolean digital = (mt1 == 0x03);
                    details.append(String.format("%s Group Call TG:%d LCN:%d [%07X]", 
                        digital ? "Digital" : "Analog", group, lcn, msg_1 & 0xFFFFFFF));
                    break;
                }
                case 0x10:
                    type = EDACSMessageType.INDIVIDUAL_CALL;
                    details.append("I-Call");
                    break;
                case 0x12:
                    type = EDACSMessageType.STATUS;
                    details.append("Channel Assignment");
                    break;
                case 0x16:
                    type = EDACSMessageType.ALL_CALL;
                    details.append("All-Call");
                    break;
                default:
                    details.append(String.format("MT1:%d [%07X]", mt1, msg_1 & 0xFFFFFFF));
                    break;
            }
        }

        EDACSMessage message = new EDACSMessage(type, data, timestamp);
        message.setDetails(details.toString());
        return message;
    }

    private static int getInt(CorrectedBinaryMessage msg, int offset, int length)
    {
        int value = 0;
        for(int x = 0; x < length; x++)
        {
            if(msg.get(offset + x))
            {
                value |= (1 << (length - 1 - x));
            }
        }
        return value;
    }
}
