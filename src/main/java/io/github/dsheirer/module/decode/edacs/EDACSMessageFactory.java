package io.github.dsheirer.module.decode.edacs;

import io.github.dsheirer.bits.CorrectedBinaryMessage;
import io.github.dsheirer.module.decode.edacs.message.EDACSMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class EDACSMessageFactory
{
    private static final Logger mLog = LoggerFactory.getLogger(EDACSMessageFactory.class);

    public static EDACSMessage create(CorrectedBinaryMessage data, long timestamp)
    {
        return create(data, null, timestamp);
    }

    public static EDACSMessage create(CorrectedBinaryMessage data, CorrectedBinaryMessage data2, long timestamp)
    {
        int msg_1 = getInt(data, 0, 28);
        int msg_2 = data2 != null ? getInt(data2, 0, 28) : 0;

        int mt1 = (msg_1 >> 23) & 0x1F;
        if(mt1 != 0x03 && mt1 != 0x06 && mt1 != 0x1F && mt1 != 0x19 && mt1 != 0x10)
            return null;
        int mt2 = (msg_1 >> 19) & 0x0F;

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
                    int adjSite = msg_2 & 0xFF;
                    int adjLcn = msg_1 & 0x1F;
                    details.append("Adjacent Site LCN:").append(adjLcn).append(" Idx:").append(adjSite);
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
                    int ccLcn = msg_2 & 0x1F;
                    int systemId = msg_1 & 0xFFFF;
                    if(ccLcn == 0 || ccLcn > 15) return null;
                    if(ccLcn == 0 || ccLcn > 15) return null;
                    type = EDACSMessageType.SYSTEM_INFO;
                    details.append("CC LCN:").append(ccLcn).append(" SYS:").append(String.format("%04X", systemId));
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
        else if(mt1 == 0x19)
        {
            int group = msg_1 & 0xFFFF;
            int source = msg_2 & 0xFFFF;
            if(group == 0 || group > 65535 || source == 0 || source > 65535) return null;
            type = EDACSMessageType.STATUS;
            details.append("Login Group:").append(group).append(" Source:").append(source);
        }
        else
        {
            switch(mt1)
            {
                case 0x01:
                    type = EDACSMessageType.GROUP_CALL;
                    details.append("Group Call MT1:01");
                    break;
                case 0x02:
                    type = EDACSMessageType.STATUS;
                    details.append("Data Group Call");
                    break;
                case 0x03:
                case 0x06:
                {
                    int lcn = (msg_1 >> 17) & 0x1F;
                    int group = msg_1 & 0xFFFF;
                    if(lcn == 0 || lcn > 32 || group == 0) return null;
                    type = EDACSMessageType.GROUP_CALL;
                    int src = data2 != null ? (msg_2 & 0xFFFF) : 0;
                    boolean digital = (mt1 == 0x03);
                    details.append(String.format("%s Group Call TG:%d LCN:%d",
                        digital ? "Digital" : "Analog", group, lcn));
                    if(src > 0) details.append(" Src:").append(src);
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
                    details.append(String.format("MT1:%02X [%07X]", mt1, msg_1 & 0xFFFFFFF));
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
