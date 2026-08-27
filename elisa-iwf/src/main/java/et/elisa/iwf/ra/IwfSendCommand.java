package et.elisa.iwf.ra;

import com.microjainslee.api.OutboundCommand;

import com.mobius.software.telco.protocols.diameter.commands.DiameterMessage;

/** Outbound Diameter frame routed back through the RA onto the DRA link. */
public record IwfSendCommand(String linkId, DiameterMessage msg)
        implements OutboundCommand {
}
