package et.elisa.iwf.ra;

import com.microjainslee.api.SleeEvent;

import com.mobius.software.telco.protocols.diameter.commands.DiameterAnswer;
import com.mobius.software.telco.protocols.diameter.commands.DiameterRequest;

/** Inbound Diameter request from the DRA (server-initiated CLR/IDR/DSR/NOR). */
public record IwfRequestEvent(String linkId, DiameterRequest msg) implements SleeEvent {
}
