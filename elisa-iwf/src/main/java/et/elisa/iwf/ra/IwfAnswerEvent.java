package et.elisa.iwf.ra;

import com.microjainslee.api.SleeEvent;

import com.mobius.software.telco.protocols.diameter.commands.DiameterAnswer;

/** Diameter answer correlated back to a leg-initiated request. */
public record IwfAnswerEvent(String linkId, long hopByHopId, DiameterAnswer msg)
        implements SleeEvent {
}
